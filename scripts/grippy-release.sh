#!/usr/bin/env bash
# Builds Grippy and publishes it to the public releases repo; the in-app updater picks it up.
#   scripts/grippy-release.sh "What changed"
# The APK is signed with this PC's debug key (~/.android/debug.keystore), the same key as Distill: keep it
# backed up, every update must be signed with the same key or Android refuses to install it.
set -euo pipefail

NOTES="${1:-}"
REPO="HuiPetrov1/grippy-releases"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/jdk/jdk-21.0.12.1+1}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

# This PC is RAM-constrained: refuse to build when memory is tight.
avail=$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)
if [ "$avail" -lt 4096 ]; then
  echo "Only ${avail} MB RAM available; need 4 GB free to build safely." >&2
  exit 3
fi

# Minutes since 2026-01-01: always increasing, fits Android's versionCode.
CODE=$(( ( $(date +%s) - 1767225600 ) / 60 ))
NAME="0.1.$CODE"

cd "$ROOT/android"
nice -n 19 ionice -c3 ./gradlew :app:assembleRelease \
  -Pgetagrip.versionCode="$CODE" -Pgetagrip.versionName="$NAME" --console=plain -q

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
cp app/build/outputs/apk/release/app-release.apk "$OUT/grippy.apk"
NOTES="$NOTES" CODE="$CODE" NAME="$NAME" REPO="$REPO" python3 - > "$OUT/update.json" <<'EOF'
import json, os
code = int(os.environ["CODE"])
print(json.dumps({
    "versionCode": code,
    "versionName": os.environ["NAME"],
    "apkUrl": f"https://github.com/{os.environ['REPO']}/releases/download/v{code}/grippy.apk",
    "notes": os.environ["NOTES"],
}))
EOF

gh release create "v$CODE" "$OUT/grippy.apk" "$OUT/update.json" \
  --repo "$REPO" --title "$NAME" --notes "${NOTES:-Grippy $NAME}" --latest
echo "Published Grippy $NAME"
