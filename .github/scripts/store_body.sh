#!/usr/bin/env bash
# Assemble separate language sources for a store editor; never contacts a store.
set -euo pipefail
english=${1:?English Markdown file required}
russian=${2:-}
# Check both paths before emitting anything, so a missing translation cannot produce a partial body.
[[ -f "$english" ]] && { [[ -z "$russian" ]] || [[ -f "$russian" ]]; }
cat "$english"
if [[ -n "$russian" ]]; then
  printf '\n\n---\n\n'
  cat "$russian"
fi
