package com.thecaticorn01.ircontroller.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Input
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.PowerOff
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsInputAntenna
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Toys
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import com.thecaticorn01.ircontroller.core.model.DeviceCategory
import com.thecaticorn01.ircontroller.core.model.RemoteButtonRole
import com.thecaticorn01.ircontroller.core.model.RemoteButtonRole.Kind

/** Icon for a device category. */
val DeviceCategory.icon: ImageVector
    get() = when (this) {
        DeviceCategory.TV -> Icons.Rounded.Tv
        DeviceCategory.PROJECTOR -> Icons.Rounded.Theaters
        DeviceCategory.AUDIO -> Icons.Rounded.Equalizer
        DeviceCategory.SOUNDBAR -> Icons.Rounded.SpeakerGroup
        DeviceCategory.SPEAKER -> Icons.Rounded.Speaker
        DeviceCategory.STREAMING -> Icons.Rounded.CastConnected
        DeviceCategory.CABLE_BOX -> Icons.Rounded.SettingsInputAntenna
        DeviceCategory.MEDIA_PLAYER -> Icons.Rounded.Album
        DeviceCategory.AIR_CONDITIONER -> Icons.Rounded.AcUnit
        DeviceCategory.FAN -> Icons.Rounded.Air
        DeviceCategory.HEATER -> Icons.Rounded.LocalFireDepartment
        DeviceCategory.LIGHTING -> Icons.Rounded.Lightbulb
        DeviceCategory.CAMERA -> Icons.Rounded.PhotoCamera
        DeviceCategory.TOY -> Icons.Rounded.Toys
        DeviceCategory.OTHER -> Icons.Rounded.SettingsRemote
    }

/** Icon for a button role, if the role has a natural one. Directions are laid out left-to-right. */
val RemoteButtonRole.icon: ImageVector?
    get() = when (kind) {
        Kind.POWER -> Icons.Rounded.PowerSettingsNew
        Kind.POWER_ON -> Icons.Rounded.Power
        Kind.POWER_OFF -> Icons.Rounded.PowerOff
        Kind.VOLUME_UP -> Icons.AutoMirrored.Rounded.VolumeUp
        Kind.VOLUME_DOWN -> Icons.AutoMirrored.Rounded.VolumeDown
        Kind.MUTE -> Icons.AutoMirrored.Rounded.VolumeOff
        Kind.CHANNEL_UP, Kind.UP -> Icons.Rounded.KeyboardArrowUp
        Kind.CHANNEL_DOWN, Kind.DOWN -> Icons.Rounded.KeyboardArrowDown
        Kind.LEFT -> DirectionIcons.left
        Kind.RIGHT -> DirectionIcons.right
        Kind.OK -> null
        Kind.MENU -> Icons.Rounded.Menu
        Kind.BACK -> Icons.AutoMirrored.Rounded.ArrowBack
        Kind.HOME -> Icons.Rounded.Home
        Kind.EXIT -> Icons.Rounded.Close
        Kind.INFO -> Icons.Rounded.Info
        Kind.GUIDE -> Icons.Rounded.DateRange
        Kind.SETTINGS -> Icons.Rounded.Settings
        Kind.INPUT -> Icons.AutoMirrored.Rounded.Input
        Kind.PLAY -> Icons.Rounded.PlayArrow
        Kind.PAUSE -> Icons.Rounded.Pause
        Kind.PLAY_PAUSE -> Icons.Rounded.PlayCircle
        Kind.STOP -> Icons.Rounded.Stop
        Kind.RECORD -> Icons.Rounded.FiberManualRecord
        Kind.NEXT -> Icons.Rounded.SkipNext
        Kind.PREVIOUS -> Icons.Rounded.SkipPrevious
        Kind.REWIND -> Icons.Rounded.FastRewind
        Kind.FAST_FORWARD -> Icons.Rounded.FastForward
        Kind.DIGIT -> null
        Kind.RED, Kind.GREEN, Kind.YELLOW, Kind.BLUE -> Icons.Rounded.Circle
        Kind.TEMPERATURE_UP -> Icons.Rounded.WbSunny
        Kind.TEMPERATURE_DOWN -> Icons.Rounded.AcUnit
        Kind.MODE -> Icons.Rounded.Tune
        Kind.FAN_SPEED -> Icons.Rounded.Air
        Kind.SWING -> Icons.Rounded.SwapVert
        Kind.TIMER -> Icons.Rounded.Timer
        Kind.BRIGHTNESS_UP -> Icons.Rounded.BrightnessHigh
        Kind.BRIGHTNESS_DOWN -> Icons.Rounded.BrightnessLow
        Kind.SUBTITLES -> Icons.Rounded.ClosedCaption
        Kind.AUDIO_TRACK -> Icons.Rounded.Audiotrack
        Kind.OTHER -> null
    }

/** Left/right chevrons that never mirror: a D-pad's left is always on the left. */
object DirectionIcons {
    @Suppress("DEPRECATION")
    val left: ImageVector = Icons.Rounded.KeyboardArrowLeft

    @Suppress("DEPRECATION")
    val right: ImageVector = Icons.Rounded.KeyboardArrowRight
}
