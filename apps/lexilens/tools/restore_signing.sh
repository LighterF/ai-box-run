#!/usr/bin/env bash
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo="$(cd "$here/../.." && pwd)"
source_dir="${1:-$repo/data/lexilens}"

if [[ ! -f "$source_dir/release.jks" || ! -f "$source_dir/keystore.properties" ]]; then
  echo "missing $source_dir/release.jks or keystore.properties" >&2
  exit 1
fi

cp "$source_dir/release.jks" "$here/release.jks"
cp "$source_dir/keystore.properties" "$here/keystore.properties"
chmod 600 "$here/release.jks" "$here/keystore.properties"
echo "signing files restored"
