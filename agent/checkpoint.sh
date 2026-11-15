#!/usr/bin/env bash
# Whole-machine checkpoint for moving a running task to a fresh machine.
# usage: checkpoint.sh baseline | save TAG | restore TAG | cleanup TAG
set -uo pipefail
MODE="${1:-}"; TAG="${2:-}"
WSDIR="${GITHUB_WORKSPACE:-$PWD}"; HOME_DIR="${HOME:-/home/runner}"
WORK=/tmp/ckpt_work
LOCAL="${CKPT_LOCAL_DIR:-}"      # test mode: a plain folder instead of GitHub releases
REPO="${CKPT_REPO:-}"            # PRIVATE repo whose releases hold the checkpoint
DELTA_ROOTS="${CKPT_DELTA_ROOTS:-/usr/local/lib/android/sdk}"   # only new/changed files are captured here
SUDO=""; [ "$(id -u)" -eq 0 ] || SUDO="sudo"
rel() { echo "${1#/}"; }

baseline() {
  dpkg-query -W -f='${Package}\n' 2>/dev/null | sort > /tmp/base_apt.txt
  pip freeze 2>/dev/null | sort > /tmp/base_pip.txt
  touch /tmp/base_marker
}

save() {
  rm -rf "$WORK"; mkdir -p "$WORK/meta"
  dpkg-query -W -f='${Package}\n' 2>/dev/null | sort > "$WORK/end_apt.txt"; comm -13 /tmp/base_apt.txt "$WORK/end_apt.txt" > "$WORK/meta/apt_new.txt"
  pip freeze 2>/dev/null | sort > "$WORK/end_pip.txt"; comm -13 /tmp/base_pip.txt "$WORK/end_pip.txt" > "$WORK/meta/pip_new.txt"
  : > "$WORK/list.txt"
  for p in "$WSDIR" "$HOME_DIR/.local/share/opencode" "$HOME_DIR/.gradle" "$HOME_DIR/.cache" "$HOME_DIR/.npm" "$HOME_DIR/.m2" /tmp/work "$WORK/meta"; do
    [ -e "$p" ] && rel "$p" >> "$WORK/list.txt"
  done
  for r in $DELTA_ROOTS; do
    [ -d "$r" ] && $SUDO find "$r" -type f -newer /tmp/base_marker 2>/dev/null | sed 's#^/##' >> "$WORK/list.txt"
  done
  W="$(rel "$WSDIR")"
  Z="zstd -T0 -3"; EXT=zst; command -v zstd >/dev/null || { Z="gzip"; EXT=gz; }
  $SUDO tar -I "$Z" -cf "$WORK/ckpt.$EXT" -C / --ignore-failed-read --warning=no-file-changed \
    --exclude="$W/.git" --exclude="$W/data/.git" --exclude="$W/agent" --exclude="$W/.github" \
    --exclude="$W/.opencode" --exclude="$W/AGENTS.md" --exclude="$W/opencode.json" \
    --files-from="$WORK/list.txt"
  rc=$?; [ $rc -le 1 ] || { echo "tar failed ($rc)"; return 1; }
  split -b 1900M -d "$WORK/ckpt.$EXT" "$WORK/part.$EXT." && rm -f "$WORK/ckpt.$EXT"
  echo "checkpoint size: $(du -sh "$WORK" | cut -f1) in $(ls "$WORK"/part.* | wc -l) part(s)"
  if [ -n "$LOCAL" ]; then
    mkdir -p "$LOCAL/$TAG" && cp "$WORK"/part.* "$LOCAL/$TAG/"
  else
    [ "$(gh repo view "$REPO" --json isPrivate -q .isPrivate)" = "true" ] || { echo "checkpoint repo is not private - refusing to upload"; return 3; }
    gh release create "$TAG" --repo "$REPO" --title "$TAG" --notes "auto hand-off checkpoint (deleted after use)" "$WORK"/part.*
  fi
}

restore() {
  mkdir -p "$WORK/dl"
  if [ -n "$LOCAL" ]; then cp "$LOCAL/$TAG"/part.* "$WORK/dl/"
  else gh release download "$TAG" --repo "$REPO" --pattern 'part.*' --dir "$WORK/dl" || return 1; fi
  if ls "$WORK/dl"/part.zst.* >/dev/null 2>&1; then
    command -v zstd >/dev/null || $SUDO apt-get install -y -qq zstd >/dev/null
    D="zstd -d -T0"
  else D="gzip -d"; fi
  cat "$WORK/dl"/part.* | $SUDO tar -I "$D" -xf - -C / || return 2
  rm -rf "$WORK/dl"
  if [ -s "$WORK/meta/apt_new.txt" ]; then
    $SUDO apt-get update -qq; $SUDO apt-get install -y -qq --no-install-recommends $(cat "$WORK/meta/apt_new.txt") || echo "some apt packages failed"
  fi
  if [ -s "$WORK/meta/pip_new.txt" ]; then pip install -q -r "$WORK/meta/pip_new.txt" || echo "some pip packages failed"; fi
  touch /tmp/ckpt_restored
  echo "checkpoint restored"
}

cleanup() {
  if [ -n "$LOCAL" ]; then rm -rf "$LOCAL/$TAG"; else gh release delete "$TAG" --repo "$REPO" --cleanup-tag -y; fi
}

case "$MODE" in
  baseline) baseline ;;
  save) save ;;
  restore) restore ;;
  cleanup) cleanup ;;
  *) echo "usage: checkpoint.sh baseline|save TAG|restore TAG|cleanup TAG"; exit 2 ;;
esac
