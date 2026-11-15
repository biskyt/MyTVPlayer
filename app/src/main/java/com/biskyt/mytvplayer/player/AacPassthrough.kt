package com.biskyt.mytvplayer.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Whether the TV (or box) can send AAC sound unchanged over HDMI, to an amplifier or a TV: a check in Settings (the
 * owner, 2026-11-15). Media3 sends only Dolby and DTS unchanged: this shows whether "Send AAC unchanged" can work.
 */
enum class AacPassthrough {
    /** The TV accepts AAC for its audio output as it is. */
    YES,

    /** The HDMI device says that it accepts AAC, but this device does not send it unchanged. */
    NOT_BY_TV,

    /** Neither says yes. */
    NO,

    /** The TV cannot tell (Android 9, or its audio service failed). */
    UNKNOWN;

    companion object {
        /** AudioDeviceInfo.TYPE_HDMI_EARC (Android 12): an eARC amplifier shows as ARC before that. */
        private const val TYPE_HDMI_EARC = 29
        private val LAYOUTS = listOf(AudioFormat.CHANNEL_OUT_5POINT1, AudioFormat.CHANNEL_OUT_STEREO)
        private val HDMI = setOf(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, TYPE_HDMI_EARC)

        /** Asks Android's audio service, which can fail (then UNKNOWN). */
        fun check(context: Context): AacPassthrough {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return UNKNOWN
            return try {
                ask(context)
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                Log.w("AacPassthrough", "Could not check", e)
                UNKNOWN
            }
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        private fun ask(context: Context): AacPassthrough {
            // Only over HDMI: a TV's own speakers (or a phone's) can take AAC as it is too.
            val outputs = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val hdmi = outputs.filter { it.type in HDMI }
            if (hdmi.isEmpty()) return NO
            // The player's question (see AacUnchanged), for 5.1 and stereo at 48 kHz.
            val direct = AacUnchanged.ENCODINGS.any { encoding ->
                LAYOUTS.any { layout -> AacUnchanged.takesDirectly(encoding, layout, AacUnchanged.SAMPLE_RATE) }
            }
            // (A device can list no encodings at all: null, whatever its type says.)
            val amplifier = hdmi.any { device -> device.encodings?.any(AacUnchanged.ENCODINGS::contains) == true }
            return of(direct, amplifier)
        }

        internal fun of(direct: Boolean, amplifier: Boolean) = when {
            direct -> YES
            amplifier -> NOT_BY_TV
            else -> NO
        }
    }
}
