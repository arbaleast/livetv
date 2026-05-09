#!/usr/bin/env bash
set -euo pipefail

# Installs Android SDK components under ~/Android/Sdk (or $ANDROID_SDK_ROOT) using
# the F-Droid-compatible `sdkmanager` (downloads ZIPs from dl.google.com).
# Requires: Python 3, working DNS/HTTPS to dl.google.com (use VPN/proxy if needed).
#
# Usage:
#   ./scripts/install-android-sdk.sh              # install SDK only
#   ./scripts/install-android-sdk.sh /path/to/livetv   # also write local.properties

SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
VENV_DIR="${SDK_VENV:-$HOME/.venvs/android-sdk-tools}"
PROJECT_DIR="${1:-}"

mkdir -p "$SDK_ROOT"

if [[ ! -x "$VENV_DIR/bin/sdkmanager" ]]; then
  python3 -m venv "$VENV_DIR"
  "$VENV_DIR/bin/pip" install -q sdkmanager
fi

SM="$VENV_DIR/bin/sdkmanager"
export ANDROID_SDK_ROOT="$SDK_ROOT"

yes | "$SM" --sdk_root="$SDK_ROOT" --licenses >/dev/null
"$SM" --sdk_root="$SDK_ROOT" "platform-tools" "platforms;android-34" "build-tools;34.0.0"

if [[ -n "$PROJECT_DIR" ]]; then
  printf 'sdk.dir=%s\n' "$SDK_ROOT" >"$PROJECT_DIR/local.properties"
  echo "Wrote $PROJECT_DIR/local.properties"
fi

echo "ANDROID_SDK_ROOT=$SDK_ROOT"
echo "Add to your shell profile if you like: export ANDROID_SDK_ROOT=\"$SDK_ROOT\""
