#!/usr/bin/env bash
# Renders Grippy's screens to PNGs on the JVM (Robolectric + Roborazzi); no phone or emulator needed.
#   scripts/grippy-screenshots.sh            # every screen, light + dark
#   scripts/grippy-screenshots.sh today      # only tests whose name starts with "today"
# Images: android/app/build/outputs/roborazzi/<nn-screen>-<light|dark>.png
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/jdk/jdk-21.0.12.1+1}"
OUT="$ROOT/android/app/build/outputs/roborazzi"

filters=()
if [ "$#" -eq 0 ]; then
  rm -f "$OUT"/*.png
  filters+=(--tests 'app.grippy.screenshots.*')
else
  for name in "$@"; do filters+=(--tests "app.grippy.screenshots.*.${name}*"); done
fi
cd "$ROOT/android"
nice -n 10 ./gradlew :app:testDebugUnitTest -Pgrippy.screenshots "${filters[@]}" --console=plain -q
ls "$OUT"
