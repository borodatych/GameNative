#!/usr/bin/env bash
# Собирает overlay поверх пакета gamescope: наши пересобранные файлы, которые APK кладёт поверх распакованного payload'а
# Сейчас это libadrenotools_gamescope.so (ICD-шим gamenative-drm) с патчами из tools/gamescope-overlay/patches
#
# Почему overlay, а не свой payload: пакет целиком собирается CI GameNative под Linux,
# а поменять нужно одну библиотеку, собранную из того же коммита, что стоит в пакете
#
# Нужны: Rust с целью aarch64-linux-android, NDK r27c, zstd, git, curl
# Исходники и кэш сборки — в CACHE_DIR, а не в репозитории
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PAYLOAD_KT="$ROOT/app/src/main/java/app/gamenative/display/GamescopePayload.kt"
PATCH_DIR="$ROOT/tools/gamescope-overlay/patches/gamenative-drm"
OVERLAY_DIR="$ROOT/app/src/main/assets/gamescope-overlay"

CACHE_DIR="${CACHE_DIR:-/Volumes/Storage/Caches/ThorHub/gamescope-overlay}"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/27.3.13750724}"
PAYLOAD_HOST="https://downloads.gamenative.app"
GNDRM_REPO="https://github.com/GameNative/gamenative-drm.git"

case "$(uname -s)" in
    Darwin) HOST_TAG="darwin-x86_64" ;;
    *) HOST_TAG="linux-x86_64" ;;
esac

# Версия payload'а берётся из приложения: overlay собирается ровно под неё
PAYLOAD_VERSION="$(grep -oE 'const val VERSION = "[^"]+"' "$PAYLOAD_KT" | cut -d'"' -f2)"
[ -n "$PAYLOAD_VERSION" ] || { echo "Не нашёл VERSION в $PAYLOAD_KT" >&2; exit 1; }
ARCHIVE="gamescope-gamenative-$PAYLOAD_VERSION.tzst"

mkdir -p "$CACHE_DIR"
PAYLOAD_DIR="$CACHE_DIR/payload-$PAYLOAD_VERSION"
if [ ! -d "$PAYLOAD_DIR/lib" ]; then
    curl -sSfL -o "$CACHE_DIR/$ARCHIVE" "$PAYLOAD_HOST/$ARCHIVE"
    mkdir -p "$PAYLOAD_DIR"
    zstd -dc "$CACHE_DIR/$ARCHIVE" | tar -x -C "$PAYLOAD_DIR"
fi

# Коммит, из которого собран шим пакета: без правки он даёт байт в байт тот же файл, что в payload'е
# Сменилась версия payload'а — сверить заново и поменять вместе с VERSION в GamescopePayload.kt
SHIM_COMMIT="868ace5"
EXPECTED_PAYLOAD="20261105.4"
[ "$PAYLOAD_VERSION" = "$EXPECTED_PAYLOAD" ] || {
    echo "Шим сверен с payload $EXPECTED_PAYLOAD, а приложение просит $PAYLOAD_VERSION: сверьте SHIM_COMMIT" >&2
    exit 1
}

SRC="$CACHE_DIR/gamenative-drm"
[ -d "$SRC/.git" ] || git clone -q "$GNDRM_REPO" "$SRC"
git -C "$SRC" fetch -q origin
git -C "$SRC" checkout -q --detach "$SHIM_COMMIT"
git -C "$SRC" reset -q --hard "$SHIM_COMMIT"
for patch in "$PATCH_DIR"/*.patch; do
    git -C "$SRC" apply "$patch"
done

# Шим линкуется с libwayland-client и libxcb*: берём их из самого payload'а, а не из termux-префикса
export GAMESCOPE_TERMUX_PREFIX="$PAYLOAD_DIR"
export ANDROID_NDK_HOME="$NDK"
export CARGO_TARGET_DIR="$CACHE_DIR/target"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android28-clang"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-args=-Wl,-z,max-page-size=16384"
(cd "$SRC" && cargo build --release --target aarch64-linux-android -p adrenotools_gamescope)

mkdir -p "$OVERLAY_DIR/lib"
cp "$CARGO_TARGET_DIR/aarch64-linux-android/release/libadrenotools_gamescope.so" "$OVERLAY_DIR/lib/"
printf '%s\n' "$PAYLOAD_VERSION" > "$OVERLAY_DIR/PAYLOAD_VERSION"
echo "Overlay для payload $PAYLOAD_VERSION (шим $SHIM_COMMIT) собран в $OVERLAY_DIR"
