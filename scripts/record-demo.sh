#!/usr/bin/env bash
# Record docs/demo.gif from the real UI -- no mock-ups, no hand-made slides.
#
#   scripts/record-demo.sh                       # Windows desktop, ffmpeg on PATH
#   TOUR_SECONDS=30 scripts/record-demo.sh       # longer tour if you add steps
#
# Two things make this work, and both are worth knowing before you edit it:
#
# 1. The page drives itself. `/?tour=1` runs a scripted sequence in index.html (typing, mode
#    switching, the admin tab), so nothing has to click and the recording is reproducible.
# 2. The crop comes from the live window rectangle, queried in PHYSICAL pixels after declaring the
#    helper process DPI-aware. Without SetProcessDPIAware() Windows hands you virtualised coordinates
#    and the crop lands somewhere else on any scaled display. gdigrab's window mode is not an option:
#    on a composited browser window it produces black frames.
set -eu
cd "$(dirname "$0")/.."

PORT="${PORT:-9231}"
TOUR_SECONDS="${TOUR_SECONDS:-23}"
TITLE_MATCH="*mini-search*"
PROFILE_DIR="C:\\Users\\${USERNAME}\\AppData\\Local\\Temp\\mini-search-demo-profile"
JAR=target/mini-search.jar
EDGE="${EDGE:-C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe}"

command -v ffmpeg >/dev/null || { echo "ffmpeg is required on PATH"; exit 1; }
[ -f "$JAR" ] || mvn -B -q -DskipTests package

ps() { powershell.exe -NoProfile -Command "$1" 2>/dev/null | tr -d '\r'; }

# a clean profile means a clean window placement
ps "Get-CimInstance Win32_Process -Filter \"Name='msedge.exe'\" | Where-Object { \$_.CommandLine -like '*mini-search-demo-profile*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }" >/dev/null || true
rm -rf "${PROFILE_DIR//\\//}" 2>/dev/null || true

java -jar "$JAR" serve --port "$PORT" --quiet > target/record-server.log 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null || true' EXIT
sleep 4

ps "Start-Process -FilePath '$EDGE' -ArgumentList '--app=http://localhost:$PORT/?tour=1','--window-position=100,60','--window-size=1000,720','--user-data-dir=$PROFILE_DIR','--no-first-run'" >/dev/null
sleep 4

RECT=$(ps "
Add-Type -TypeDefinition 'using System;using System.Runtime.InteropServices;public class Q{[DllImport(\"user32.dll\")]public static extern bool SetProcessDPIAware();[DllImport(\"user32.dll\")]public static extern bool GetWindowRect(IntPtr h,out P r);[StructLayout(LayoutKind.Sequential)]public struct P{public int L;public int T;public int Rt;public int B;}}'
[Q]::SetProcessDPIAware() | Out-Null
\$p = Get-Process msedge -ErrorAction SilentlyContinue | Where-Object { \$_.MainWindowTitle -like '$TITLE_MATCH' } | Select-Object -Last 1
\$r = New-Object Q+P
[Q]::GetWindowRect(\$p.MainWindowHandle, [ref]\$r) | Out-Null
Write-Output \"\$(\$r.L) \$(\$r.T) \$(\$r.Rt - \$r.L) \$(\$r.B - \$r.T)\"
")
read -r L T W H <<< "$RECT"
if [ "${W:-0}" -lt 400 ]; then echo "could not locate the demo window (got '$RECT')"; exit 1; fi
CH=$((H - 64))                  # drop the title bar, keep the page
echo "window ${W}x${H} at ${L},${T}; cropping to ${W}x${CH}"

ffmpeg -y -loglevel error -f gdigrab -framerate 12 -i desktop -t "$TOUR_SECONDS" target/demo.mp4
ffmpeg -y -loglevel error -i target/demo.mp4 \
  -vf "crop=${W}:${CH}:${L}:$((T + 64)),fps=10,scale=900:-1:flags=lanczos,split[s][o];[s]palettegen=max_colors=128[p];[o][p]paletteuse=dither=bayer:bayer_scale=4" \
  docs/demo.gif
ls -la docs/demo.gif
ps "Get-CimInstance Win32_Process -Filter \"Name='msedge.exe'\" | Where-Object { \$_.CommandLine -like '*mini-search-demo-profile*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }" >/dev/null || true
echo "wrote docs/demo.gif -- check a frame: ffmpeg -i docs/demo.gif -vf select=eq(n\\,120) -frames:v 1 target/check.png"
