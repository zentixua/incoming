#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME=/home/artem/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta
ROOT=/mnt/data/projects/incoming/mod/run/wt-SM
cd "$ROOT"
trap 'tools/sandbox.sh stop; tools/gpu_lease.sh release' EXIT
pgrep -f 'java.*PrismLauncher/[i]nstances/' && exit 75
{
  date -u +%FT%TZ
  git rev-parse HEAD
  powerprofilesctl get
  cat /sys/firmware/acpi/platform_profile
  echo 'SM did not change either power setting.'
} > mod/run/sm-r2/final-power-state.txt
git apply --check mod/run/sm-r2/probe-evidence/Sandbox.patch
git apply mod/run/sm-r2/probe-evidence/Sandbox.patch
mkdir -p mod/src/devtest/java/ua/zentix/incoming/client/far
cp mod/run/sm-r2/probe-evidence/SmokeRayProbe.java mod/src/devtest/java/ua/zentix/incoming/client/far/SmokeRayProbe.java
shot() {
 tools/sandbox.sh shot "$@"
 python3 mod/run/sm-r2/wait-png.py "mod/run/sandbox/fx/out/$1"
}
tools/sandbox.sh start --wait
shot sm37-final-raid 'raid 16 120 3' --at 20,40,55 --wav 4 --cam '30 25 0 0'
shot sm37-final-drone 'blast drone 1' --at 3,10,30 --wav 4 --cam '30 10 90 8' --no-swap
shot sm37-final-fire 'fire 6' --at 10,20 --wav 4 --cam '30 10 90 8' --no-swap
shot sm37-final-early-missile-day 'blast missile 1' --at 0.05,0.1,0.2,0.5,1,2 --wav 0 --cam '30 10 90 8' --no-swap
shot sm37-final-early-missile-night 'blast missile 1' --at 0.05,0.1,0.2,0.5,1,2 --wav 0 --cam '30 10 90 8' --time night --no-swap
tools/sandbox.sh stop
tools/sandbox.sh start --wait --size 2560x1600
shot sm37-final-hi-raid 'raid 16 120 3' --at 20,40,55 --wav 0 --cam '30 25 0 0' --no-swap
