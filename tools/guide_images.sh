#!/usr/bin/env bash
# Player's Guide pictures (docs/guide/img) from scenario frames guide: tools/client_scenario.sh guide,
# then this script (you only need ffmpeg with libwebp). Recipes are cut at the location of the workbench window (scale GUI 2, as
# in options.txt script), the rest are animations from the series guide-gif-<name>-NNNN. There is no series - its animation is not affected
# (this is how one section is re-shot: INCOMING_GUIDE=<section>).
# Which frame to start with and how many to take can only be seen with the eyes: the numbers are below, they are corrected after shooting.
#   tools/guide_images.sh [frame folder] (default mod/run/scenario/screenshots)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${1:-$ROOT/mod/run/scenario/screenshots}"
OUT="$ROOT/docs/guide/img"
mkdir -p "$OUT"

ff() { ffmpeg -loglevel error -y "$@"; }

# workbench: window 176×166 GUI (352×332 at scale 2) in the middle of the frame, in the frame - title, grid and result; place - from
# frame size: the game window in the nested KWin is below the screen by a frame (1280×692 instead of 1280×720)
for f in "$SRC"/guide-recipe-*.png; do
  [ -e "$f" ] || continue
  name="$(basename "$f" .png)"
  ff -i "$f" -vf "crop=352:144:(iw-352)/2:(ih-332)/2" "$OUT/${name#guide-}.png"
done

# screenshots: remote control with the nuke (power slider) - a cutout of the remote control window, like its animation
for f in "$SRC"/guide-nuke-remote.png; do
  [ -e "$f" ] || continue
  ff -i "$f" -vf "crop=656:496:(iw-656)/2:(ih-496)/2" "$OUT/remote-nuke.png"
done

# Animations: name, first frame, number of frames (0 - to the end), size filter. No series - skip.
# Screens GUI almost do not change from frame to frame - GIF (remote screen - cutout without scale: 320×240 GUI with fields
# 8 pixels). The whole world changes in every frame: in GIF it is 50–100 KB per frame, animated WebP is three to four times lighter
# with the same picture (browsers and GitHub show it).
frames() {
  local name=$1 start=$2 count=$3
  local first; first=$(printf "%s/guide-gif-%s-%04d.png" "$SRC" "$name" "$start")
  [ -e "$first" ] || { echo "no frames $name - skip" >&2; return 1; }
  ARGS=(-framerate 12 -start_number "$start" -i "$SRC/guide-gif-$name-%04d.png")
  [ "$count" -gt 0 ] && ARGS+=(-frames:v "$count")
  return 0
}
gif() {
  frames "$1" "$2" "$3" || return 0
  ff "${ARGS[@]}" -vf "$4,split[a][b];[a]palettegen=max_colors=$5:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle" "$OUT/$1.gif"
}
webp() {
  frames "$1" "$2" "$3" || return 0
  ff "${ARGS[@]}" -vf "scale=640:-1:flags=lanczos" -c:v libwebp_anim -quality 60 -compression_level 6 -loop 0 "$OUT/$1.webp"
}
gif  remote   0 112 "crop=656:496:(iw-656)/2:(ih-496)/2" 96   # the last frame is already peace
gif  map      0 0 "scale=960:-1:flags=lanczos" 256
webp scope    0 0
webp hud      0 0
webp camera   0 0
webp loiter   0 96    # further - the view from the eyes after leaving the camera
webp sam      0 72    # further - one smoke launch
webp launcher 0 64
ls -la "$OUT"
