package com.thecaticorn01.ircontroller.blaster

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.thecaticorn01.ircontroller.BuildConfig
import com.thecaticorn01.ircontroller.core.elksmart.ElkSmartDriver
import com.thecaticorn01.ircontroller.core.elksmart.ElkSmartException
import com.thecaticorn01.ircontroller.core.elksmart.ElkSmartModel
import com.thecaticorn01.ircontroller.core.elksmart.ElkSmartPayloadEncoding
import com.thecaticorn01.ircontroller.core.elksmart.hexString
import com.thecaticorn01.ircontroller.core.model.IRSignal
import com.thecaticorn01.ircontroller.core.model.RawSignal
import com.thecaticorn01.ircontroller.core.protocols.IRProtocolException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class BlasterNotConnectedException :
    Exception("No IR blaster is connected. Plug in your ELK Smart USB-C IR blaster.")

/**
 * Owns the USB connection to the ELK Smart dongle. All blocking USB I/O runs
 * on a private single-thread dispatcher; this object publishes state for the UI.
 */
class BlasterManager(private val context: Context, private val scope: CoroutineScope) {

    /** Connection details reported after a successful handshake. */
    data class Info(
        val deviceName: String,
        val model: ElkSmartModel,
        val productName: String?,
        val serialNumber: String?,
        val encoding: ElkSmartPayloadEncoding,
        val identity: List<Int>,
        val endpoints: String,
    )

    sealed interface State {
        data object Searching : State
        data class NeedsPermission(val deviceName: String) : State
        data class Connecting(val name: String) : State
        data class Ready(val info: Info) : State
        data class Failed(val message: String) : State
    }

    sealed interface Activity {
        data object Idle : Activity
        data class Sending(val name: String) : Activity
        data object Learning : Activity
    }

    data class LogEntry(val id: Long, val timeMillis: Long, val message: String, val isError: Boolean)

    private val usbManager = context.getSystemService(UsbManager::class.java)

    private val _state = MutableStateFlow<State>(State.Searching)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _activity = MutableStateFlow<Activity>(Activity.Idle)
    val activity: StateFlow<Activity> = _activity.asStateFlow()

    private val _devices = MutableStateFlow<List<UsbDevice>>(emptyList())
    val devices: StateFlow<List<UsbDevice>> = _devices.asStateFlow()

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    /** Incremented on every successful transmission; views animate on change. */
    private val _sendCount = MutableStateFlow(0)
    val sendCount: StateFlow<Int> = _sendCount.asStateFlow()

    /** Name of the most recently sent button. */
    private val _lastSentName = MutableStateFlow<String?>(null)
    val lastSentName: StateFlow<String?> = _lastSentName.asStateFlow()

    val info: Info? get() = (_state.value as? State.Ready)?.info
    val isReady: Boolean get() = _state.value is State.Ready

    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "ir-blaster").apply { isDaemon = true } }
        .asCoroutineDispatcher()

    // Accessed only on `dispatcher`.
    private var transport: UsbBulkTransport? = null
    private var driver: ElkSmartDriver? = null

    /** Bumped to cancel an in-flight learn; a newer learn also supersedes older ones. */
    private val learnGeneration = AtomicInteger()
    private val logIds = AtomicLong()
    private var connectJob: Job? = null
    private var connectingDeviceName: String? = null
    /** Devices we already asked the user about, so we don't nag on every scan. */
    private val askedPermission = mutableSetOf<String>()
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> scan()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    device?.let { askedPermission.remove(it.deviceName) }
                    scan()
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        record("USB access granted")
                        scan(force = true)
                    } else {
                        record("USB access to the IR blaster was denied", isError = true)
                    }
                }
            }
        }
    }

    /** Starts watching for dongles being plugged in or removed. */
    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        scan()
    }

    /** Re-reads the USB device list and connects to the first dongle if needed. */
    fun scan(force: Boolean = false) {
        val found = usbManager?.deviceList?.values?.filter { it.vendorId == ElkSmartModel.VENDOR_ID }.orEmpty()
        val hadDevices = _devices.value.isNotEmpty()
        _devices.value = found
        val first = found.firstOrNull()
        if (first == null) {
            if (hadDevices) record("IR blaster disconnected")
            connectJob?.cancel()
            connectingDeviceName = null
            scope.launch { disconnect() }
            _state.value = State.Searching
            return
        }
        if (!force) {
            // Stay on a working (or in-progress) connection if that device is still present.
            val current = info
            if (current != null && found.any { it.deviceName == current.deviceName }) return
            if (_state.value is State.Connecting && found.any { it.deviceName == connectingDeviceName }) return
        }
        connect(first)
    }

    /** (Re)connects to the first detected dongle. */
    fun reconnect() = scan(force = true)

    private fun connect(device: UsbDevice) {
        connectJob?.cancel()
        val manager = usbManager ?: return
        if (!manager.hasPermission(device)) {
            _state.value = State.NeedsPermission(device.productName ?: device.deviceName)
            if (askedPermission.add(device.deviceName)) requestPermission(device)
            return
        }
        val name = device.productName ?: "ELK Smart IR Blaster"
        connectingDeviceName = device.deviceName
        _state.value = State.Connecting(name)
        connectJob = scope.launch {
            // Give the system a moment to finish enumerating a freshly plugged device.
            delay(300)
            try {
                val info = withContext(dispatcher) {
                    closeOnWorker()
                    val transport = UsbBulkTransport.open(manager, device)
                    val driver = ElkSmartDriver(transport)
                    try {
                        val encoding = driver.identify()
                        this@BlasterManager.transport = transport
                        this@BlasterManager.driver = driver
                        Info(
                            deviceName = device.deviceName,
                            model = ElkSmartModel.forProductId(device.productId),
                            productName = device.productName,
                            serialNumber = runCatching { device.serialNumber }.getOrNull(),
                            encoding = encoding,
                            identity = driver.identity,
                            endpoints = transport.endpointDescription,
                        )
                    } catch (e: Exception) {
                        transport.close()
                        throw e
                    }
                }
                if (!currentCoroutineContext().isActive) return@launch
                _state.value = State.Ready(info)
                record("Connected to ${info.model.name}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e.toString()
                _state.value = State.Failed(message)
                record("Connection failed: $message", isError = true)
            } finally {
                if (connectingDeviceName == device.deviceName) connectingDeviceName = null
            }
        }
    }

    /** Shows the system dialog asking for access to the dongle. */
    fun requestPermission(device: UsbDevice? = _devices.value.firstOrNull()) {
        val manager = usbManager ?: return
        device ?: return
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        manager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
    }

    private suspend fun disconnect() = withContext(dispatcher) { closeOnWorker() }

    private fun closeOnWorker() {
        driver = null
        transport?.close()
        transport = null
    }

    /** Waits briefly for a connection, e.g. when a widget starts the app. */
    suspend fun awaitReady(timeoutMillis: Long = 4_000): Boolean {
        start()
        if (isReady) return true
        return withTimeoutOrNull(timeoutMillis) {
            state.first { it is State.Ready || it is State.Failed || it is State.NeedsPermission }
        } is State.Ready
    }

    // Sending

    /** Sends a signal. Errors are logged and rethrown. */
    suspend fun send(signal: IRSignal, name: String, extraRepeats: Int = 0) {
        if (!isReady) throw BlasterNotConnectedException()
        // Render before switching threads so RC5/RC6 toggle bits alternate in press order.
        val raw = signal.rendered(repeats = extraRepeats)
        _activity.value = Activity.Sending(name)
        try {
            withContext(dispatcher) {
                val driver = driver ?: throw BlasterNotConnectedException()
                driver.transmit(raw)
            }
            _sendCount.update { it + 1 }
            _lastSentName.value = name
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            record("Sending “$name” failed: ${e.message ?: e}", isError = true)
            handleIOError(e)
            throw e
        } finally {
            if (_activity.value is Activity.Sending) _activity.value = Activity.Idle
        }
    }

    // Learning

    /** Waits for the user to press a button on a physical remote. */
    suspend fun learn(timeoutMillis: Long, frequency: Int): RawSignal {
        if (!isReady) throw BlasterNotConnectedException()
        val generation = learnGeneration.incrementAndGet()
        val job = currentCoroutineContext()[Job]
        _activity.value = Activity.Learning
        try {
            val signal = withContext(dispatcher) {
                val driver = driver ?: throw BlasterNotConnectedException()
                driver.learn(timeoutMillis, frequency) { learnGeneration.get() != generation || job?.isActive == false }
            }
            record("Learned a code with ${signal.timings.size} timings")
            return signal
        } catch (e: ElkSmartException.LearnCancelled) {
            throw e
        } catch (e: ElkSmartException.LearnTimedOut) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            record("Learning failed: ${e.message ?: e}", isError = true)
            handleIOError(e)
            throw e
        } finally {
            // A newer learn may have started meanwhile; only the latest one resets the state.
            if (learnGeneration.get() == generation) _activity.value = Activity.Idle
        }
    }

    fun cancelLearning() {
        learnGeneration.incrementAndGet()
        if (_activity.value is Activity.Learning) _activity.value = Activity.Idle
    }

    // Helpers

    private fun handleIOError(error: Exception) {
        // USB-level failures usually mean the device went away or got wedged; reconnect.
        if (error !is ElkSmartException && error !is IRProtocolException && error !is BlasterNotConnectedException) {
            if (error is IOException) reconnect()
        }
    }

    fun record(message: String, isError: Boolean = false) {
        val entry = LogEntry(logIds.incrementAndGet(), System.currentTimeMillis(), message, isError)
        _log.update { (listOf(entry) + it).take(100) }
    }

    /** Text report for bug reports: versions, connection state, USB devices and the activity log. */
    fun diagnosticReport(): String = buildString {
        appendLine("IR Controller ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) on Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("USB host supported: ${context.packageManager.hasSystemFeature("android.hardware.usb.host")}")
        appendLine("State: ${_state.value}")
        info?.let { appendLine("Identity: ${it.identity.hexString}") }
        appendLine()
        val all = usbManager?.deviceList?.values.orEmpty()
        appendLine("USB devices (${all.size}):")
        for (device in all) {
            appendLine(
                "- %04X:%04X %s %s, permission: %s".format(
                    device.vendorId, device.productId, device.manufacturerName ?: "", device.productName ?: device.deviceName,
                    usbManager?.hasPermission(device),
                ),
            )
            if (device.vendorId == ElkSmartModel.VENDOR_ID) append(UsbBulkTransport.describe(device))
        }
        appendLine()
        appendLine("Activity log:")
        val format = DateFormat.getTimeInstance(DateFormat.MEDIUM)
        for (entry in _log.value.reversed()) {
            appendLine("${format.format(Date(entry.timeMillis))} ${if (entry.isError) "ERROR " else ""}${entry.message}")
        }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.thecaticorn01.ircontroller.USB_PERMISSION"
    }
}
