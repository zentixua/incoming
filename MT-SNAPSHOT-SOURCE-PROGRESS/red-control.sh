#!/usr/bin/env bash
set -euo pipefail
task_root=$PWD
restore_source() {
  cd "$task_root"
  for task_path in mod/src/main/java/ua/zentix/incoming/nuclear/world/RuinContext.java mod/src/main/java/ua/zentix/incoming/nuclear/world/RuinInbox.java mod/src/main/java/ua/zentix/incoming/nuclear/world/RuinMemory.java; do
    git show "HEAD:$task_path" > "$task_path"
  done
}
trap restore_source EXIT
for task_path in mod/src/main/java/ua/zentix/incoming/nuclear/world/RuinContext.java mod/src/main/java/ua/zentix/incoming/nuclear/world/RuinInbox.java mod/src/main/java/ua/zentix/incoming/nuclear/world/RuinMemory.java; do
  git show "4e1f8f6021c10d586758f30639efec6c0de7fe17:$task_path" > "$task_path"
done
export JAVA_HOME=/home/artem/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta
export JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Xmx2G'
cd mod
./gradlew runGameTestServer -PgametestOnly=nuke_source_progress -Dorg.gradle.jvmargs='-Xmx2G -XX:ActiveProcessorCount=4'
