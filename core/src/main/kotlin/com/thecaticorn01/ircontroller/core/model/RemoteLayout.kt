package com.thecaticorn01.ircontroller.core.model

import com.thecaticorn01.ircontroller.core.model.RemoteButtonRole.Kind

/**
 * Arranges a remote's buttons into the familiar zones of a physical remote
 * (power, navigation pad, volume/channel rockers, transport, keypad…).
 * Each button appears in exactly one zone; anything unrecognised goes to [others].
 */
class RemoteLayout(buttons: List<IRButton>) {
    var power: IRButton? = null; private set
    var powerOn: IRButton? = null; private set
    var powerOff: IRButton? = null; private set
    var input: IRButton? = null; private set
    var mute: IRButton? = null; private set

    var volumeUp: IRButton? = null; private set
    var volumeDown: IRButton? = null; private set
    var channelUp: IRButton? = null; private set
    var channelDown: IRButton? = null; private set

    var up: IRButton? = null; private set
    var down: IRButton? = null; private set
    var left: IRButton? = null; private set
    var right: IRButton? = null; private set
    var ok: IRButton? = null; private set

    /** Back, home, menu, exit, info, guide, settings. */
    val navigation: List<IRButton>
    /** Rewind, play, pause, stop… in a conventional order. */
    val transport: List<IRButton>
    /** Digit buttons indexed 0…9. */
    val digits: Map<Int, IRButton>
    val colors: List<IRButton>
    /** Climate / fan / lighting controls. */
    val climate: List<IRButton>
    val others: List<IRButton>

    val hasDirectionalPad: Boolean get() = up != null || down != null || left != null || right != null || ok != null
    val hasVolume: Boolean get() = volumeUp != null || volumeDown != null
    val hasChannel: Boolean get() = channelUp != null || channelDown != null
    val hasNumberPad: Boolean get() = digits.size >= 3

    /** True when the keypad has anything to show besides [others]. */
    val hasKeypad: Boolean
        get() = power != null || powerOn != null || powerOff != null || input != null || mute != null ||
            hasVolume || hasChannel || hasDirectionalPad || navigation.isNotEmpty() || transport.isNotEmpty() ||
            hasNumberPad || colors.isNotEmpty() || climate.isNotEmpty()

    init {
        val transportByRole = sortedMapOf<Int, IRButton>()
        val navigationByRole = sortedMapOf<Int, IRButton>()
        val colorByRole = sortedMapOf<Int, IRButton>()
        val digitMap = sortedMapOf<Int, IRButton>()
        val climateList = mutableListOf<IRButton>()
        val otherList = mutableListOf<IRButton>()

        fun ordered(map: MutableMap<Int, IRButton>, order: Int, button: IRButton): Boolean {
            if (map.containsKey(order)) return false
            map[order] = button
            return true
        }

        for (button in buttons) {
            val role = button.role
            val placed = when (role.kind) {
                Kind.POWER -> if (power == null) { power = button; true } else false
                Kind.POWER_ON -> if (powerOn == null) { powerOn = button; true } else false
                Kind.POWER_OFF -> if (powerOff == null) { powerOff = button; true } else false
                Kind.INPUT -> if (input == null) { input = button; true } else false
                Kind.MUTE -> if (mute == null) { mute = button; true } else false
                Kind.VOLUME_UP -> if (volumeUp == null) { volumeUp = button; true } else false
                Kind.VOLUME_DOWN -> if (volumeDown == null) { volumeDown = button; true } else false
                Kind.CHANNEL_UP -> if (channelUp == null) { channelUp = button; true } else false
                Kind.CHANNEL_DOWN -> if (channelDown == null) { channelDown = button; true } else false
                Kind.UP -> if (up == null) { up = button; true } else false
                Kind.DOWN -> if (down == null) { down = button; true } else false
                Kind.LEFT -> if (left == null) { left = button; true } else false
                Kind.RIGHT -> if (right == null) { right = button; true } else false
                Kind.OK -> if (ok == null) { ok = button; true } else false
                Kind.BACK -> ordered(navigationByRole, 0, button)
                Kind.HOME -> ordered(navigationByRole, 1, button)
                Kind.MENU -> ordered(navigationByRole, 2, button)
                Kind.EXIT -> ordered(navigationByRole, 3, button)
                Kind.INFO -> ordered(navigationByRole, 4, button)
                Kind.GUIDE -> ordered(navigationByRole, 5, button)
                Kind.SETTINGS -> ordered(navigationByRole, 6, button)
                Kind.PREVIOUS -> ordered(transportByRole, 0, button)
                Kind.REWIND -> ordered(transportByRole, 1, button)
                Kind.PLAY -> ordered(transportByRole, 2, button)
                Kind.PLAY_PAUSE -> ordered(transportByRole, 3, button)
                Kind.PAUSE -> ordered(transportByRole, 4, button)
                Kind.STOP -> ordered(transportByRole, 5, button)
                Kind.RECORD -> ordered(transportByRole, 6, button)
                Kind.FAST_FORWARD -> ordered(transportByRole, 7, button)
                Kind.NEXT -> ordered(transportByRole, 8, button)
                Kind.DIGIT -> ordered(digitMap, role.digit, button)
                Kind.RED -> ordered(colorByRole, 0, button)
                Kind.GREEN -> ordered(colorByRole, 1, button)
                Kind.YELLOW -> ordered(colorByRole, 2, button)
                Kind.BLUE -> ordered(colorByRole, 3, button)
                Kind.TEMPERATURE_UP, Kind.TEMPERATURE_DOWN, Kind.MODE, Kind.FAN_SPEED, Kind.SWING, Kind.TIMER,
                Kind.BRIGHTNESS_UP, Kind.BRIGHTNESS_DOWN -> { climateList.add(button); true }
                Kind.SUBTITLES, Kind.AUDIO_TRACK, Kind.OTHER -> false
            }
            if (!placed) otherList.add(button)
        }

        navigation = navigationByRole.values.toList()
        transport = transportByRole.values.toList()
        var colorList = colorByRole.values.toList()

        // A lone number button is more useful in the general grid than as a keypad.
        if (digitMap.size < 3) {
            otherList.addAll(digitMap.values)
            digitMap.clear()
        }
        // Colour keys only make sense as a full set.
        if (colorList.size < 2) {
            otherList.addAll(colorList)
            colorList = emptyList()
        }
        digits = digitMap.toMap()
        colors = colorList
        climate = climateList
        others = otherList
    }
}

/** Suggested button names when learning a new remote of a given category. */
object LearningTemplate {
    fun buttonNames(category: DeviceCategory): List<String> {
        val media = listOf("Power", "Volume Up", "Volume Down", "Mute")
        val navigation = listOf("Up", "Down", "Left", "Right", "OK", "Back", "Home", "Menu")
        val transport = listOf("Play", "Pause", "Stop", "Rewind", "Fast Forward", "Previous", "Next")
        val digits = (1..9).map { it.toString() } + "0"
        return when (category) {
            DeviceCategory.TV ->
                media + listOf("Channel Up", "Channel Down", "Input") + navigation + listOf("Info", "Guide") + digits
            DeviceCategory.PROJECTOR ->
                listOf("Power", "Input", "Menu", "Up", "Down", "Left", "Right", "OK", "Back", "Volume Up", "Volume Down", "Mute")
            DeviceCategory.AUDIO, DeviceCategory.SOUNDBAR, DeviceCategory.SPEAKER ->
                media + listOf("Input", "Play", "Pause", "Previous", "Next", "Mode")
            DeviceCategory.STREAMING ->
                listOf("Power") + navigation + listOf("Play Pause", "Volume Up", "Volume Down", "Mute")
            DeviceCategory.CABLE_BOX ->
                media + listOf("Channel Up", "Channel Down", "Guide", "Info") + navigation + listOf("Record") + digits
            DeviceCategory.MEDIA_PLAYER -> listOf("Power", "Eject") + transport + navigation
            DeviceCategory.AIR_CONDITIONER -> listOf("Power", "Temp Up", "Temp Down", "Mode", "Fan Speed", "Swing", "Timer")
            DeviceCategory.FAN, DeviceCategory.HEATER -> listOf("Power", "Speed", "Swing", "Timer", "Mode")
            DeviceCategory.LIGHTING -> listOf("On", "Off", "Brightness Up", "Brightness Down", "Red", "Green", "Blue", "White", "Mode")
            DeviceCategory.CAMERA -> listOf("Shutter", "Record", "Zoom In", "Zoom Out")
            DeviceCategory.TOY, DeviceCategory.OTHER -> listOf("Power")
        }
    }
}
