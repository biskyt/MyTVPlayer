# libac3enc: the Dolby Digital encoder

`libac3enc.so` encodes surround sound to Dolby Digital (AC-3) for "Send surround sound as Dolby Digital"
(`player/DolbyDigitalSink.kt` and `player/Ac3Encoder.kt`).

- It is FFmpeg 7.1 with only the AC-3 encoder, and the JNI wrapper `ac3enc.c`.
- It is built once and committed to `app/src/main/jniLibs/<abi>/libac3enc.so`. CI and releases do not build it.
- It is built for `arm64-v8a` and `armeabi-v7a` only. On other devices (emulators) and in JVM tests the library is
  missing, so the setting is not shown.
- FFmpeg is built without `--enable-gpl`, so it comes under the LGPL 2.1 or later. The licences page lists it.

## When to rebuild it
Rebuild it only when one of these changes:
- `ac3enc.c` (for example, a new JNI function or a renamed Kotlin class: the function names hold the class name).
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
6. The script writes the two libraries and prints their sizes. Each must have 4 JNI functions.
7. Test on a device: turn on the setting and play a film with 5.1 AAC sound. The sound line in Options must say "AAC sent
   as Dolby Digital 5.1".
8. Update the checksums below and commit the libraries with this file.

## The committed libraries
Built from FFmpeg 7.1 with NDK 27.2.12479018 (16 KB page size):

| ABI | Size (bytes) | SHA-256 |
|---|---|---|
| arm64-v8a | 632392 | `e7f6d6d33952b8f0c6f3bf897f2659916f7bdea03896478c97d54a06691a4724` |
| armeabi-v7a | 494656 | `35d2cd708cce8e82eed7f482057f8a805232d4f73c659aa0bc5a8b547da9e72c` |

A rebuild can give different bytes (the build is not reproducible), so new checksums are normal.
