#!/usr/bin/env bash
# Downloads the open-licensed font families (SIL OFL / Apache 2.0) from the google/fonts repository,
# subsets them to Latin characters and writes app/src/main/assets/fonts (fonts + manifest.json + LICENSES.txt).
# Needs: git, python3, and the python package "fonttools". Runs automatically in the GitHub Actions build.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${TMPDIR:-/tmp}/timer-google-fonts"
rm -rf "$WORK"
python3 -c "import fontTools" 2>/dev/null || python3 -m pip install --quiet fonttools || python3 -m pip install --quiet --break-system-packages fonttools
GIT_LFS_SKIP_SMUDGE=1 git clone --depth 1 --filter=blob:none --sparse https://github.com/google/fonts "$WORK"
cd "$WORK"
# shellcheck disable=SC2046
GIT_LFS_SKIP_SMUDGE=1 git sparse-checkout set $(python3 -I "$ROOT/tools/make_fonts.py" --list)
python3 -I "$ROOT/tools/make_fonts.py" "$WORK" "$ROOT/app/src/main/assets/fonts"
echo "fonts ready: $(ls "$ROOT/app/src/main/assets/fonts" | wc -l) files"
