#!/usr/bin/env bash
# Usage: ./apply_fixes.sh /path/to/Audiophile-player   (copies the fixed files over the repo)
set -euo pipefail
DEST="${1:?path to your Audiophile-player checkout}"
HERE="$(cd "$(dirname "$0")" && pwd)"
cp -rv "$HERE/app" "$DEST/"
cp -v "$HERE/NO_DSP_TAMPERING.md" "$DEST/"
echo "Done. Commit and let CI build. (Root-level ProfessionalStereoWidenerDSP_v10.h is a stale duplicate; delete it.)"
