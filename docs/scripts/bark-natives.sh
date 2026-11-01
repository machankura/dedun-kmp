#!/usr/bin/env bash
# Builds Bark's native library for the host this runs on, and puts it where JNA looks for it
# on the desktop target: docs/bark-wallet.md, Phase 11.
#
# bark-jvm ships one library, for Linux x86-64. macOS and Windows need theirs built from the
# same bark-ffi-bindings tag, on a host of that platform (Phase 0, spike 7: there is no
# cross-linker for either here). Run it on each:
#
#   docs/scripts/bark-natives.sh                 # into composeApp/src/jvmMain/resources/<prefix>/
#   docs/scripts/bark-natives.sh --out DIR       # into DIR/<prefix>/ instead
#
# <prefix> is JNA's resource prefix for the host: darwin-aarch64, darwin-x86-64, win32-x86-64.
# The results are build outputs and are not committed (see .gitignore); a desktop package is
# made on the host that built them.
set -euo pipefail

TAG="v0.26.1+bark-0.8.0"   # must match libs.versions.toml's `bark`
REPO="https://gitlab.com/ark-bitcoin/bark-ffi-bindings.git"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="$ROOT/composeApp/src/jvmMain/resources"
WORK=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) OUT="$2"; shift 2 ;;
    --work) WORK="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64)               PREFIX=darwin-aarch64; LIB=libbark_ffi_kotlin.dylib ;;
  Darwin-x86_64)              PREFIX=darwin-x86-64;  LIB=libbark_ffi_kotlin.dylib ;;
  MINGW*-x86_64|MSYS*-x86_64) PREFIX=win32-x86-64;   LIB=bark_ffi_kotlin.dll ;;
  Linux-x86_64) echo "bark-jvm already ships linux-x86-64; nothing to build" >&2; exit 0 ;;
  *) echo "no JNA prefix known for $(uname -s) $(uname -m)" >&2; exit 1 ;;
esac

command -v cargo >/dev/null || { echo "cargo is needed: https://rustup.rs" >&2; exit 1; }

WORK="${WORK:-$(mktemp -d)}"
if [[ ! -d "$WORK/bark-ffi-bindings" ]]; then
  git clone --quiet --depth 1 --branch "$TAG" "$REPO" "$WORK/bark-ffi-bindings"
fi

# The crate's own rust-toolchain.toml pins the Rust version it was released with.
(cd "$WORK/bark-ffi-bindings/kotlin/rust" && cargo build --release --lib)

TARGET_DIR="$(cd "$WORK/bark-ffi-bindings/kotlin/rust" && cargo metadata --format-version 1 --no-deps | sed -n 's/.*"target_directory":"\([^"]*\)".*/\1/p')"
mkdir -p "$OUT/$PREFIX"
cp "$TARGET_DIR/release/$LIB" "$OUT/$PREFIX/$LIB"
echo "$OUT/$PREFIX/$LIB"
