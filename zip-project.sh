#!/usr/bin/env bash
set -euo pipefail

dir_name="$(basename "$PWD")"
out_dir="${HOME}/tmp"
zip_path="${out_dir}/${dir_name}.zip"

mkdir -p "$out_dir"
rm -f "$zip_path"

# Recursively zip current folder, excluding common build/IDE dirs
zip -r "$zip_path" . -x "*/.git/*" "*/target/*" "*/.idea/*"

echo "Created: $zip_path"
