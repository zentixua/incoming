#!/usr/bin/env bash
set -euo pipefail
cd /mnt/data/projects/incoming/mod/run/wt-MT
export JAVA_HOME=/home/artem/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta
python3 tools/stand/stand.py prepare /mnt/data/projects/incoming/mod/run/wt-MT/mod/build/libs/incoming-3.10.4.jar --packs none --scenes 100 --label 11f4c67f7
python3 tools/stand/stand.py prepare ci:main --packs none --scenes 100
