# libac3enc: the Dolby Digital encoder

`libac3enc.so` encodes surround sound to Dolby Digital (AC-3) for "Send surround sound as Dolby Digital"
(`player/DolbyDigitalSink.kt` and `player/Ac3Encoder.kt`).

- It is FFmpeg 7.1 with only the AC-3 encoder, and the JNI wrapper `ac3enc.c`.
- MyTVPlayer and MyPlexPlayer use the same library file. `JNI_OnLoad` registers the functions with the app's own
  `Ac3Encoder` class (the list is `CLASSES` in `ac3enc.c`). Copy the libraries to MyPlexPlayer unchanged.
- It is built once and committed to `app/src/main/jniLibs/<abi>/libac3enc.so`. CI and releases do not build it.
- It is built for `arm64-v8a` and `armeabi-v7a` only. On other devices (emulators) and in JVM tests the library is
  missing, so the setting is not shown.
- FFmpeg is built without `--enable-gpl`, so it comes under the LGPL 2.1 or later. The licences page lists it.

## When to rebuild it
Rebuild it only when one of these changes:
- `ac3enc.c` (for example, a new JNI function, or a new or renamed `Ac3Encoder` class in `CLASSES`).
- The FFmpeg version (for a security fix).
- Android's needs (for example, a new page size or ABI).

## How to rebuild it
1. Install Android NDK 27.2.12479018 (`sdkmanager "ndk;27.2.12479018"`).
2. Download the FFmpeg 7.1 source from https://ffmpeg.org/releases/ffmpeg-7.1.tar.xz.
3. Check the download. Its SHA-256 must be
   `40973d44970dbc83ef302b0609f2e74982be2d85916dd2ee7472d30678a7abe6`.
4. Unpack it: `tar xf ffmpeg-7.1.tar.xz`.
5. Run the build on Linux x86-64 from the repository root:
   `NDK=<ndk folder> FFMPEG_SRC=<path>/ffmpeg-7.1 native/ac3enc/build.sh`
6. The script writes the two libraries and prints their sizes. Each must have 1 `JNI_OnLoad`.
7. Test on a device: turn on the setting and play a film with 5.1 AAC sound. The sound line in Options must say "AAC sent
   as Dolby Digital 5.1".
8. Update the checksums below and commit the libraries with this file.

## The committed libraries
Built from FFmpeg 7.1 with NDK 27.2.12479018 (16 KB page size):

| ABI | Size (bytes) | SHA-256 |
|---|---|---|
| arm64-v8a | 632984 | `7ad0fec8e130d833384e07defe8f0f97a08ed5a7e27796faedbb6bc6ab483e27` |
| armeabi-v7a | 494976 | `58ef3d43ca92ccefb07a4209e743f7015c52c375ca37846eb01ad923419a436a` |

A rebuild can give different bytes (the build is not reproducible), so new checksums are normal.
