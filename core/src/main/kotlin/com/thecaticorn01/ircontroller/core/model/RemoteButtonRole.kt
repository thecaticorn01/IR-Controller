package com.thecaticorn01.ircontroller.core.model

/**
 * Semantic role of a remote button, inferred from its (often inconsistent) name.
 * Used to lay out a physical-remote-like keypad and to pick icons.
 */
data class RemoteButtonRole(val kind: Kind, val digit: Int = -1) {

    enum class Kind {
        POWER, POWER_ON, POWER_OFF,
        VOLUME_UP, VOLUME_DOWN, MUTE,
        CHANNEL_UP, CHANNEL_DOWN,
        UP, DOWN, LEFT, RIGHT, OK,
        MENU, BACK, HOME, EXIT, INFO, GUIDE, SETTINGS,
        INPUT,
        PLAY, PAUSE, PLAY_PAUSE, STOP, RECORD,
        NEXT, PREVIOUS, REWIND, FAST_FORWARD,
        DIGIT,
        RED, GREEN, YELLOW, BLUE,
        TEMPERATURE_UP, TEMPERATURE_DOWN, MODE, FAN_SPEED, SWING, TIMER,
        BRIGHTNESS_UP, BRIGHTNESS_DOWN,
        SUBTITLES, AUDIO_TRACK,
        OTHER,
    }

    val isDigit: Boolean get() = kind == Kind.DIGIT

    companion object {
        private fun role(kind: Kind) = RemoteButtonRole(kind)
        fun digit(value: Int) = RemoteButtonRole(Kind.DIGIT, value)

        fun of(buttonName: String): RemoteButtonRole {
            var name = buttonName.replace("+", " plus ")
            if (name.endsWith("-")) name = name.dropLast(1) + " minus"
            val key = name.searchKey
            val compact = key.replace(" ", "")
            return lookup[compact] ?: fuzzy(key)
        }

        private val lookup: Map<String, RemoteButtonRole> = buildMap {
            fun add(kind: Kind, vararg names: String) = names.forEach { put(it, role(kind)) }
            add(Kind.POWER, "power", "pwr", "onoff", "powertoggle", "standby", "power1", "powerbutton", "keypower", "toggle")
            add(Kind.POWER_ON, "on", "poweron", "pwron", "discreteon")
            add(Kind.POWER_OFF, "off", "poweroff", "pwroff", "discreteoff")
            add(Kind.VOLUME_UP, "volup", "volumeup", "volplus", "vup", "keyvolumeup", "volumeplus")
            add(Kind.VOLUME_DOWN, "voldn", "voldown", "volumedown", "volminus", "vdn", "vdown", "keyvolumedown", "volumeminus", "voldwn")
            add(Kind.MUTE, "mute", "keymute", "muting", "audiomute")
            add(Kind.CHANNEL_UP, "chnext", "chup", "channelup", "chplus", "progup", "prup", "pgup", "programup", "keychannelup", "pageup")
            add(Kind.CHANNEL_DOWN, "chprev", "chdn", "chdown", "channeldown", "chminus", "progdown", "prdown", "pgdn", "programdown", "keychanneldown", "pagedown")
            add(Kind.UP, "up", "keyup", "cursorup", "arrowup", "navup", "dpadup")
            add(Kind.DOWN, "down", "keydown", "cursordown", "arrowdown", "navdown", "dpaddown")
            add(Kind.LEFT, "left", "keyleft", "cursorleft", "arrowleft", "navleft", "dpadleft")
            add(Kind.RIGHT, "right", "keyright", "cursorright", "arrowright", "navright", "dpadright")
            add(Kind.OK, "ok", "enter", "select", "keyok", "keyenter", "keyselect", "confirm", "center")
            add(Kind.MENU, "menu", "keymenu", "mainmenu", "setupmenu", "popupmenu", "topmenu")
            add(Kind.BACK, "back", "return", "keyback", "previouschannel", "prevch", "last", "keyreturn")
            add(Kind.HOME, "home", "keyhome", "smarthub", "smart")
            add(Kind.EXIT, "exit", "keyexit", "close", "cancel")
            add(Kind.INFO, "info", "display", "keyinfo", "osd", "status")
            add(Kind.GUIDE, "guide", "epg", "keyepg", "tvguide")
            add(Kind.SETTINGS, "settings", "setup", "options", "tools", "keysetup")
            add(Kind.INPUT, "input", "source", "inputs", "keysource", "src", "av", "tvav", "inputselect", "hdmi", "keyinput")
            add(Kind.PLAY, "play", "keyplay")
            add(Kind.PAUSE, "pause", "keypause")
            add(Kind.PLAY_PAUSE, "playpause", "playstop", "keyplaypause")
            add(Kind.STOP, "stop", "keystop")
            add(Kind.RECORD, "rec", "record", "keyrecord")
            add(Kind.NEXT, "next", "skipnext", "keynext", "skip", "skipfwd", "nexttrack", "nextchapter")
            add(Kind.PREVIOUS, "prev", "previous", "skipprev", "keyprevious", "skipback", "prevtrack", "previoustrack")
            add(Kind.REWIND, "rew", "rewind", "keyrewind", "fastback", "rwd")
            add(Kind.FAST_FORWARD, "ff", "fastfo", "fastforward", "fwd", "keyfastforward", "ffwd", "forward")
            add(Kind.RED, "red", "keyred", "a")
            add(Kind.GREEN, "green", "keygreen", "b")
            add(Kind.YELLOW, "yellow", "keyyellow", "c")
            add(Kind.BLUE, "blue", "keyblue", "d")
            add(Kind.TEMPERATURE_UP, "tempup", "temperatureup", "tempplus", "warmer", "heathi")
            add(Kind.TEMPERATURE_DOWN, "tempdown", "tempdn", "temperaturedown", "tempminus", "cooler", "coollo")
            add(Kind.MODE, "mode", "keymode")
            add(Kind.FAN_SPEED, "speed", "fanspeed", "fan", "speedup", "wind")
            add(Kind.SWING, "swing", "oscillate", "osc", "rotate", "oscillation")
            add(Kind.TIMER, "timer", "sleep", "keysleep", "sleeptimer")
            add(Kind.BRIGHTNESS_UP, "brightnessup", "brightup", "brighter", "dimup", "lightup")
            add(Kind.BRIGHTNESS_DOWN, "brightnessdown", "brightdown", "dimmer", "dimdown", "dim", "lightdown")
            add(Kind.SUBTITLES, "subtitle", "subtitles", "cc", "subt", "keysubtitle")
            add(Kind.AUDIO_TRACK, "audio", "language", "lang", "keyaudio")
            val digitNames = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
            for (d in 0..9) {
                for (n in listOf("$d", "key$d", "num$d", "digit$d", "btn$d", digitNames[d])) put(n, digit(d))
            }
        }

        private fun fuzzy(key: String): RemoteButtonRole {
            val words = key.split(" ").toSet()
            fun has(w: String) = w in words
            if (has("power") || has("pwr")) {
                if (has("on")) return role(Kind.POWER_ON)
                if (has("off")) return role(Kind.POWER_OFF)
                return role(Kind.POWER)
            }
            if (has("vol") || has("volume")) {
                if (has("up") || has("plus")) return role(Kind.VOLUME_UP)
                if (has("dn") || has("down") || has("minus")) return role(Kind.VOLUME_DOWN)
            }
            if (has("ch") || has("channel") || has("prog")) {
                if (has("up") || has("next")) return role(Kind.CHANNEL_UP)
                if (has("down") || has("dn") || has("prev")) return role(Kind.CHANNEL_DOWN)
            }
            if (has("mute")) return role(Kind.MUTE)
            if (has("temp") || has("temperature")) {
                if (has("up")) return role(Kind.TEMPERATURE_UP)
                if (has("down") || has("dn")) return role(Kind.TEMPERATURE_DOWN)
            }
            if (has("bright") || has("brightness")) {
                if (has("up")) return role(Kind.BRIGHTNESS_UP)
                if (has("down") || has("dn")) return role(Kind.BRIGHTNESS_DOWN)
            }
            return role(Kind.OTHER)
        }
    }
}
