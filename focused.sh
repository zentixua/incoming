#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME=/home/artem/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta
cd /mnt/data/projects/incoming/mod/run/wt-MT/mod
./gradlew --no-daemon test --tests '*Lod*Test' --tests '*Voxy*Test' --tests '*S2CGoldenTest' --tests '*RuinInboxTest' --tests '*RetainedPlansTest' --tests '*RadialChunksTest' --tests '*WitnessesTest' > run/mt-b3/focused.log 2>&1
