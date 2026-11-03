#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME=/home/artem/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta
cd /mnt/data/projects/incoming/mod/run/wt-MT/mod
./gradlew --no-daemon build > run/mt-lod-buffer/build.log 2>&1
./gradlew runGameTestServer -PgametestDir=run/mt-lod-buffer/gametest -PgametestOnly='voxy_.*' > run/mt-lod-buffer/gametest.log 2>&1
