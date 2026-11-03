#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME=/home/artem/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta
cd /mnt/data/projects/incoming/mod/run/wt-MT/mod
while systemctl --user is-active --quiet incoming-job-mt-b1-checks-296112.service; do sleep 10; done
if ! rg -q 'BUILD SUCCESSFUL' run/mt-b1/gametest.log; then exit 2; fi
if [ -d run/gametest/world ]; then mv run/gametest/world "run/mt-b1/gametest-world-before-focused-$(date +%s)"; fi
./gradlew runGameTestServer -PwithLss -PlssTestVersion=jxJ4956L '-PgametestOnly=ruin_pool_promote|nuke_lifecycle_.*|lss_.*' > run/mt-b1/focused.log 2>&1
