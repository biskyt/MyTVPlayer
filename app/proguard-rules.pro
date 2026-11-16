# kotlinx.serialization keeps its generated serializers via the library's own consumer rules.
# Media3 FFmpeg extension is loaded by reflection from DefaultRenderersFactory.
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary { *; }
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { <init>(...); }

# libac3enc's JNI_OnLoad finds this class by its name (native/ac3enc/ac3enc.c). The default rules keep classes with
# native methods too; this rule states the need here, in case those rules change.
-keepclasseswithmembernames class com.biskyt.mytvplayer.player.Ac3Encoder { native <methods>; }
