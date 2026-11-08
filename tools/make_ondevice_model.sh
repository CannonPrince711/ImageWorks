#!/usr/bin/env bash
# Converts a Stable Diffusion 1.5-based checkpoint into an Image Studio on-device model zip.
# Run on a computer (not the phone). Needs Python 3.10+, ~10 GB free disk, a few minutes.
# Works on macOS, Linux, or Windows (WSL / Git Bash).
#
#   bash tools/make_ondevice_model.sh                       # base SD 1.5  -> sd15.zip
#   bash tools/make_ondevice_model.sh dreamshaper_8.safetensors
#   bash tools/make_ondevice_model.sh https://huggingface.co/.../model.safetensors my-model
#
# Custom models must be SD 1.5 based (not SDXL, SD3, Flux or Pony). Most Civitai models tagged "SD 1.5" work.
# Then copy the zip to the phone and use Settings > "Import zip from phone",
# or host it at an https link and use Settings > "Download".
set -euo pipefail
SRC="${1:-https://huggingface.co/stable-diffusion-v1-5/stable-diffusion-v1-5/resolve/main/v1-5-pruned-emaonly.ckpt}"
OUT_NAME="${2:-}"
HERE="$(pwd)"
WORK="${WORK:-$HERE/sd-convert-work}"
mkdir -p "$WORK"

if [ -z "$OUT_NAME" ]; then
  OUT_NAME="$(basename "${SRC%%\?*}")"; OUT_NAME="${OUT_NAME%.*}"
  [ "$OUT_NAME" = "v1-5-pruned-emaonly" ] && OUT_NAME="sd15"
fi

cd "$WORK"
if [ ! -d venv ]; then python3 -m venv venv; fi
# shellcheck disable=SC1091
source venv/bin/activate 2>/dev/null || source venv/Scripts/activate
pip install --quiet torch typing_extensions numpy Pillow requests pytorch_lightning absl-py safetensors

# Google's converter (from the MediaPipe Image Generator docs). If this link has moved,
# search the google-ai-edge/mediapipe-samples repo for image_generator_converter/convert.py.
[ -f convert.py ] || curl -fL -o convert.py https://raw.githubusercontent.com/googlesamples/mediapipe/main/tools/image_generator_converter/convert.py

# Fetch the checkpoint if a URL was given.
case "$SRC" in
  http*) FILE="$WORK/$(basename "${SRC%%\?*}")"; [ -f "$FILE" ] || curl -fL -o "$FILE" "$SRC" ;;
  *)     FILE="$(cd "$HERE" && realpath "$SRC")" ;;
esac

# The converter reads .ckpt; turn .safetensors into one first.
if [[ "$FILE" == *.safetensors ]]; then
  CKPT="${FILE%.safetensors}.ckpt"
  [ -f "$CKPT" ] || python3 - "$FILE" "$CKPT" <<'PY'
import sys, torch
from safetensors.torch import load_file
sd = load_file(sys.argv[1])
torch.save({"state_dict": sd}, sys.argv[2])
print("converted safetensors -> ckpt")
PY
  FILE="$CKPT"
fi

rm -rf "bins-$OUT_NAME"
python3 convert.py --ckpt_path "$FILE" --output_path "bins-$OUT_NAME"
rm -f "$HERE/$OUT_NAME.zip"
(cd "bins-$OUT_NAME" && zip -q -r -0 "$HERE/$OUT_NAME.zip" .)
echo "Done: $HERE/$OUT_NAME.zip  (import it in Image Studio > Settings > On-device models)"
