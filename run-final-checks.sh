#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME=/home/artem/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta
cd /mnt/data/projects/incoming/mod/run/wt-MT/mod
./gradlew --no-daemon build > run/mt-b1/final-build-retry.log 2>&1
if [ -d run/gametest/world ]; then mv run/gametest/world "run/mt-b1/gametest-world-before-$(date +%s)"; fi
./gradlew runGameTestServer -PwithLss '-PgametestOnly=nuke_lifecycle_.*|nuke_background.*|nuke_stop|nuke_look_ahead|nuke_stale_background|nuke_settle_.*|nuke_tile_.*|nuke_zone_heap|nuke_zone_edge|dh_.*|voxy_.*|lss_.*' > run/mt-b1/gametest.log 2>&1
