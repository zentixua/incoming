#!/usr/bin/env bash
# Assembling the mod and installing it in the Prism instance.
#
#   tools/deploy.sh assembly and unit tests → jar in mods/ instance and in dist/
#   tools/deploy.sh --test also GameTest (server without window with Create, Sable and Aeronautics)
#   tools/deploy.sh --dry assembly and testing only, do not touch the game
#   tools/deploy.sh --jar F without assembly: install ready-made jar F (assembly CI or release - the same bytes as friends)
#
# Nothing is deleted: old jar mod (incoming-*.jar and airstrike-*.jar), copies of datapack (saves/*/datapacks/airstrike|shahed) and sound pack
# (resourcepacks/"Airstrike Sounds"|"Shahed Sounds") are transferred to incoming-backup/< time>/ next to mods/.
# The mod replaces them completely; It transfers the datapack settings to its config itself when the world is first launched.
# After installation, restart the game. Friends need the same jar (dist/incoming-*.jar) in their mods/.
# The instance with auto-updating of the assembly (zentixua/open-skies) deploy.sh does not touch: jar the mod there is from the assembly on its main.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
path() { python3 "$ROOT/tools/paths.py" "$1"; }
MC="$(path MC)"
MODS="$(path MODS)"
DIST="$(path DIST)"
BACKUP="$(path BACKUP)/$(date +%Y-%m-%d_%H%M%S)"
export JAVA_HOME="${JAVA_HOME:-$(path JAVA)}"

TEST=0; DRY=0; JAR=""
while [ $# -gt 0 ]; do
  case "$1" in
    --test) TEST=1 ;;
    --dry) DRY=1 ;;
    --jar) [ $# -ge 2 ] || { echo "--jar: path to jar required" >&2; exit 2; }; JAR="$(realpath "$2")"; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

mkdir -p "$DIST"
if [ -n "$JAR" ]; then
  [[ "$(basename "$JAR")" == incoming-*.jar && -f "$JAR" ]] || { echo "--jar: file needed incoming-<version>.jar" >&2; exit 2; }
  [ "$(dirname "$JAR")" = "$(realpath "$DIST")" ] || cp "$JAR" "$DIST/"
  echo "ready: $JAR ($(sha256sum "$JAR" | cut -c1-64)) → dist/"
else
  cd "$ROOT/mod"
  ./gradlew build --console=plain -q
  if [ "$TEST" = 1 ]; then ./gradlew runGameTestServer --console=plain -q; fi
  JAR="$(realpath "$(ls -t build/libs/incoming-*.jar | grep -v -- '-sources' | head -n1)")"
  cp "$JAR" "$DIST/"
  echo "assembled: $JAR → dist/"
fi
if [ "$DRY" = 1 ]; then echo "--dry: game untouched"; exit 0; fi
# Before launching such an instance, packwiz-installer installs an assembly: it will return the removed jar assembly, and Incoming will become two.
if grep -qs '^PreLaunchCommand=.*packwiz-installer' "$(dirname "$MC")/instance.cfg"; then
  echo "the instance is updated itself from the assembly open-skies (packwiz-installer): the mod comes to the game with the release of the PR assembly;" \
    "the game is untouched" >&2
  exit 1
fi

[ -d "$MODS" ] || { echo "there is no folder $MODS - check tools/paths.py or set MC_DIR" >&2; exit 1; }
aside() { # move to the side while maintaining the path relative to .minecraft
  local src="$1" rel="${1#"$MC"/}"
  mkdir -p "$BACKUP/$(dirname "$rel")"
  mv "$src" "$BACKUP/$rel"
  echo "to the side: $rel"
}
for old in "$MODS"/incoming-*.jar "$MODS"/airstrike-*.jar; do
  if [ -e "$old" ]; then aside "$old"; fi
done
for w in "$MC/saves"/*/; do
  for dp in $(path LEGACY_DATAPACKS); do
    if [ -e "$w/datapacks/$dp" ]; then aside "${w%/}/datapacks/$dp"; fi
  done
done
IFS='|' read -r -a rps <<< "$(path LEGACY_RESOURCEPACKS)"
for rp in "${rps[@]}"; do
  if [ -e "$MC/resourcepacks/$rp" ]; then aside "$MC/resourcepacks/$rp"; fi
done
cp "$JAR" "$MODS/"
echo "installed: mods/$(basename "$JAR") - restart the game"
