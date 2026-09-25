#!/usr/bin/env bash
# Renders the promo video: fonts -> frames (headless Chromium) -> soundtrack -> muxed MP4.
# Needs: node + playwright (Chromium), python3 + numpy + scipy, ffmpeg (or FFMPEG=/path/to/ffmpeg).
set -euo pipefail
cd "$(dirname "$0")"
FFMPEG=${FFMPEG:-ffmpeg}; export FFMPEG
[ -f fonts/Pretendard-Black.woff2 ] || ./fetch-fonts.sh
node render.mjs video
python3 audio.py
"$FFMPEG" -y -loglevel error -i out/video.mp4 -i out/audio.wav -map 0:v -map 1:a \
  -c:v libx264 -preset slow -crf "${CRF:-20}" -pix_fmt yuv420p -profile:v high -r 60 \
  -c:a aac -b:a 192k -af "loudnorm=I=-14:TP=-1.5:LRA=11" -shortest -movflags +faststart \
  duckyslicer-promo-ko.mp4
echo "wrote $(pwd)/duckyslicer-promo-ko.mp4"
