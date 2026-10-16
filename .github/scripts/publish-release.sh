#!/usr/bin/env bash
set -euo pipefail

repository="$1"
tag="$2"
title="$3"
notes="${4:-}"
target="${5:-}"
shopt -s nullglob
apks=(build/signed-apk/*.apk)
if (( ${#apks[@]} == 0 )); then
  printf '::error::没有可发布的已签名 APK\n'
  exit 1
fi
temporary="$(mktemp -d)"
trap 'rm -rf "$temporary"' EXIT

if gh release view "$tag" --repo "$repository" --json assets > "$temporary/release.json" 2> "$temporary/view-error.txt"; then
  pending=()
  for apk in "${apks[@]}"; do
    filename="$(basename "$apk")"
    if python3 - "$temporary/release.json" "$filename" <<'PY'
import json
import sys

with open(sys.argv[1]) as source:
    release = json.load(source)
sys.exit(0 if any(asset["name"] == sys.argv[2] for asset in release["assets"]) else 1)
PY
    then
      gh release download "$tag" --repo "$repository" --pattern "$filename" --dir "$temporary"
      if ! cmp -s "$apk" "$temporary/$filename"; then
        printf '::error::%s 的 %s 已有不同内容的同名 APK，请递增版本后发布\n' "$repository" "$tag"
        exit 1
      fi
      printf '已验证并跳过重复附件：%s / %s\n' "$repository" "$filename"
    else
      pending+=("$apk")
    fi
  done
  edit=(--repo "$repository" --title "$title")
  if [[ -n "$notes" ]]; then edit+=(--notes-file "$notes"); fi
  gh release edit "$tag" "${edit[@]}"
  if (( ${#pending[@]} > 0 )); then
    gh release upload "$tag" "${pending[@]}" --repo "$repository"
  fi
else
  create=(--repo "$repository" --title "$title")
  if [[ -n "$target" ]]; then create+=(--target "$target"); fi
  if [[ -n "$notes" ]]; then create+=(--notes-file "$notes"); else create+=(--generate-notes); fi
  gh release create "$tag" "${apks[@]}" "${create[@]}"
fi
