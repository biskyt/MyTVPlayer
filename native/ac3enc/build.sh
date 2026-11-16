#!/bin/bash
# Builds libac3enc.so for arm64-v8a and armeabi-v7a: FFmpeg with only the AC-3 encoder, and the JNI wrapper ac3enc.c.
# The output goes to app/src/main/jniLibs. See README.md for when and how to run it.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
NDK=${NDK:-$ANDROID_HOME/ndk/27.2.12479018}
SRC=${FFMPEG_SRC:?Set FFMPEG_SRC to the unpacked FFmpeg 7.1 source}
WORK=${WORK:-$(mktemp -d)}
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
API=24
OUTDIR=$HERE/../../app/src/main/jniLibs
for ABI in arm64-v8a armeabi-v7a; do
  case $ABI in
    arm64-v8a) ARCH=aarch64; CPU=armv8-a; CC=$TC/bin/aarch64-linux-android$API-clang; EXTRA="";;
    armeabi-v7a) ARCH=arm; CPU=armv7-a; CC=$TC/bin/armv7a-linux-androideabi$API-clang; EXTRA="--enable-neon";;
  esac
  B=$WORK/out-$ABI
  rm -rf "$B" && mkdir -p "$B" && cd "$B"
  "$SRC/configure" --prefix="$B/install" --target-os=android --arch=$ARCH --cpu=$CPU --enable-cross-compile \
    --cc="$CC" --cxx="${CC}++" --ar="$TC/bin/llvm-ar" --ranlib="$TC/bin/llvm-ranlib" --strip="$TC/bin/llvm-strip" \
    --nm="$TC/bin/llvm-nm" --sysroot="$TC/sysroot" --enable-pic --enable-static --disable-shared --disable-everything \
    --disable-programs --disable-doc --disable-avdevice --disable-avformat --disable-swscale --disable-swresample \
    --disable-postproc --disable-avfilter --disable-network --disable-autodetect --enable-encoder=ac3 --enable-small \
    --extra-cflags="-fvisibility=hidden -O2" $EXTRA > configure.log 2>&1
  make -j"$(nproc)" > make.log 2>&1
  make install > install.log 2>&1
  mkdir -p "$OUTDIR/$ABI"
  "$CC" -shared -fPIC -O2 -fvisibility=hidden -I"$B/install/include" "$HERE/ac3enc.c" \
    -L"$B/install/lib" -lavcodec -lavutil -lm -Wl,--gc-sections -Wl,-z,max-page-size=16384 -o "$OUTDIR/$ABI/libac3enc.so"
  "$TC/bin/llvm-strip" --strip-unneeded "$OUTDIR/$ABI/libac3enc.so"
  echo "$ABI: $(wc -c < "$OUTDIR/$ABI/libac3enc.so") bytes, $("$TC/bin/llvm-nm" -D --defined-only "$OUTDIR/$ABI/libac3enc.so" | grep -c JNI_OnLoad) JNI_OnLoad (must be 1)"
done
