#!/usr/bin/env bash
# Builds the tiny FFmpeg used by the built-in video decoder (for devices whose ROM ships no usable decoders).
# Only H.264 / AAC / MP3 decoding and the MP4, HLS, MPEG-TS, MP3 and ADTS demuxers are included. Built for speed
# (FFmpeg -O3, no --enable-small on ARM): video decoding is the one place this app spends real CPU.
# FFmpeg stays a separate shared library (libblffmpeg.so, LGPL 2.1) that libblplayer.so links against.
#
#   NDK=/path/to/android-ndk-r25c FFMPEG_SRC=/path/to/ffmpeg-6.1.2 tools/build-ffmpeg.sh
#   NDK=/path/to/android-ndk-r25c PLAYER_ONLY=1 tools/build-ffmpeg.sh     # rebuild only libblplayer.so
#
# r25c is the last NDK that still targets API 19 (Android 4.4).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${NDK:?set NDK to android-ndk-r25c}"
SRC="${FFMPEG_SRC:-}"
API=19
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
OUT="$ROOT/build/ffmpeg"
JNI="$ROOT/app/src/main/jniLibs"
JOBS="$(nproc 2>/dev/null || echo 2)"

build_abi() {
  local abi="$1" arch cpu triple extra=() tune=()
  case "$abi" in
    # Tuned for Cortex-A9 (the common core of Android 4.4 e-readers): ARM (not Thumb) code, A9 scheduling. The ISA
    # stays plain ARMv7 + VFPv3-D16 so NEON-less A9s (Tegra 2) run it too; FFmpeg picks its NEON code at runtime.
    armeabi-v7a) arch=arm; cpu=armv7-a; triple=armv7a-linux-androideabi
                 tune=(-marm -mtune=cortex-a9 -mfpu=vfpv3-d16 -mfloat-abi=softfp)
                 extra=(--enable-neon --disable-thumb) ;;
    # x86 Android 4.4 devices are few and fast: keep that copy small instead.
    x86)         arch=x86; cpu=i686; triple=i686-linux-android
                 tune=(-mtune=atom)
                 extra=(--disable-x86asm --disable-inline-asm --enable-small) ;;
  esac
  local cc="$TC/bin/${triple}${API}-clang"
  local work="$OUT/$abi"
  rm -rf "$work" && mkdir -p "$work/src" "$work/prefix"
  cp -a "$SRC/." "$work/src/"
  (cd "$work/src" && ./configure \
      --prefix="$work/prefix" --target-os=android --arch="$arch" --cpu="$cpu" --enable-cross-compile \
      --cc="$cc" --cxx="$cc++" --ld="$cc" --nm="$TC/bin/llvm-nm" --ar="$TC/bin/llvm-ar" \
      --ranlib="$TC/bin/llvm-ranlib" --strip="$TC/bin/llvm-strip" --sysroot="$TC/sysroot" \
      --extra-cflags="-fPIC -DANDROID -ffunction-sections -fdata-sections ${tune[*]}" \
      --enable-pic --enable-static --disable-shared --disable-debug --disable-doc \
      --disable-programs --disable-avdevice --disable-avfilter --disable-postproc --disable-swscale \
      --disable-swresample --disable-everything --disable-autodetect --disable-iconv --disable-zlib \
      --disable-bzlib --disable-lzma --disable-vulkan --disable-mediacodec --disable-jni \
      --enable-pthreads --enable-network \
      --enable-protocol=http,tcp,file \
      --enable-demuxer=mov,hls,mpegts,mp3,aac,h264 \
      --enable-decoder=h264,aac,aac_latm,mp3,mp3float \
      --enable-parser=h264,aac,aac_latm,mpegaudio \
      "${extra[@]}" > "$work/configure.log" 2>&1) || { tail -40 "$work/configure.log"; exit 1; }
  make -C "$work/src" -j"$JOBS" > "$work/make.log" 2>&1 || { tail -40 "$work/make.log"; exit 1; }
  make -C "$work/src" install > /dev/null 2>&1
  mkdir -p "$JNI/$abi" "$ROOT/app/src/main/cpp/ffmpeg/$abi"
  "$cc" -shared -o "$JNI/$abi/libblffmpeg.so" -Wl,-soname,libblffmpeg.so -Wl,--gc-sections \
      -Wl,--whole-archive "$work/prefix/lib/libavformat.a" "$work/prefix/lib/libavcodec.a" \
      "$work/prefix/lib/libavutil.a" -Wl,--no-whole-archive -lm
  "$TC/bin/llvm-strip" --strip-unneeded "$JNI/$abi/libblffmpeg.so"
  rm -rf "$ROOT/app/src/main/cpp/ffmpeg/include"
  cp -a "$work/prefix/include" "$ROOT/app/src/main/cpp/ffmpeg/include"
  ls -l "$JNI/$abi/libblffmpeg.so"
}

build_player() {
  local abi="$1" triple flags=()
  case "$abi" in
    armeabi-v7a) triple=armv7a-linux-androideabi; flags=(-marm -mtune=cortex-a9 -mfpu=vfpv3-d16 -mfloat-abi=softfp) ;;
    x86)         triple=i686-linux-android; flags=(-mtune=atom) ;;
  esac
  "$TC/bin/${triple}${API}-clang" -shared -fPIC -O2 -std=c11 -Wall -Wextra -Wno-unused-parameter "${flags[@]}" \
      -I"$ROOT/app/src/main/cpp/ffmpeg/include" -o "$JNI/$abi/libblplayer.so" "$ROOT/app/src/main/cpp/blplayer.c" \
      -L"$JNI/$abi" -lblffmpeg -landroid -llog -Wl,-soname,libblplayer.so -Wl,--no-undefined
  "$TC/bin/llvm-strip" --strip-unneeded "$JNI/$abi/libblplayer.so"
  ls -l "$JNI/$abi/libblplayer.so"
}

for abi in ${ABIS:-armeabi-v7a x86}; do
  if [ -z "${PLAYER_ONLY:-}" ]; then
    [ -n "$SRC" ] || { echo "set FFMPEG_SRC to an unpacked ffmpeg-6.1.2" >&2; exit 1; }
    build_abi "$abi"
  fi
  build_player "$abi"
done
