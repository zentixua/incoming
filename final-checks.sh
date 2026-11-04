#!/usr/bin/env bash
set -euo pipefail
cd /mnt/data/projects/incoming/mod/run/wt-MT
bash mod/run/mt-b3/checks.sh
bash mod/run/mt-b3/gametest.sh
bash mod/run/mt-b3/prepare-client.sh
