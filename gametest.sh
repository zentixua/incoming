#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME=/home/artem/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta
cd /mnt/data/projects/incoming/mod/run/wt-MT/mod
./gradlew runGameTestServer -PgametestDir="run/mt-b3/gametest-$(date +%Y%m%d-%H%M%S)" -PgametestOnly='voxy_.*|dh_front|dh_far_.*|nuke_stop|nuke_zone_heap|nuke_zone_edge|nuke_lifecycle_.*' > run/mt-b3/gametest.log 2>&1
