#!/usr/bin/env bash
# Scene bench: one Incoming version across packs, or a pair against accepted (README.md).
#
#   tools/stand/run.sh <name> <jar> [--against <accepted jar>] [--packs none,complementary,photon] [--scenes 100,…]
#                      [--against-label …] [--size 2560x1600] [--label …] [--tries 1] [--no-warmup]
#
# <jar>: release (v3.7.0), ci:<branch|SHA> (incoming-jar artifact of build), or jar path. Prepare without GPU first:
# jar (and accepted jar), modpack Sodium, Iris and shader packs by sha512, client script (stand.py prepare). Then clients
# in one GPU laptop job (tools/laptop_job.sh --gpu --wait: queued lease, waits for Artem's game to close;
# game starts: client stops, code 75), each in windowless nested KWin with no host audio; --against runs
# A1, B, A2 per pack under one lease. Release at the end: next waiter acquires the GPU immediately.
# Output: bench out/<name>/ (mod/run/stand in main copy), results.json; pairs also have verdict.json and A1, B, A2 runs.
set -uo pipefail
[ $# -ge 2 ] || { sed -n '2,13p' "$0"; exit 2; }
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../.." && pwd)
NAME=$1 SPEC=$2; shift 2
export JAVA_HOME=${JAVA_HOME:-$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta}
# GPU lease owner is the bench (a Claude tree would otherwise name it after its session)
export INCOMING_GPU_OWNER=${INCOMING_GPU_OWNER:-stand}
# play does not receive its own --label/--spec from CLI: label and path come from prepare
args=() label= against= against_label= play_only=()
while [ $# -gt 0 ]; do
  case $1 in
    --label) label=$2; shift 2 ;;
    --against) against=$2; shift 2 ;;
    --against-label) against_label=$2; shift 2 ;;
    --tries) play_only+=(--tries "$2"); shift 2 ;;
    --no-warmup) play_only+=(--no-warmup); shift ;;
    *) args+=("$1"); shift ;;
  esac
done
prepare() {  # <spec> [--label …] → "JAR	path	label" line
  python3 "$HERE/stand.py" prepare "$@" "${args[@]}" | tee /dev/stderr | grep $'^JAR\t' | tail -n 1
}
line=$(prepare "$SPEC" ${label:+--label "$label"}) || exit 1
[ -n "$line" ] || { echo "stand $NAME: prepare returned no jar" >&2; exit 1; }
IFS=$'\t' read -r _ JAR LABEL <<< "$line"
pair=()
if [ -n "$against" ]; then
  bline=$(prepare "$against") || exit 1
  [ -n "$bline" ] || { echo "stand $NAME: prepare returned no accepted jar" >&2; exit 1; }
  IFS=$'\t' read -r _ BJAR BLABEL <<< "$bline"
  pair=(--against "$BJAR" --against-label "${against_label:-$BLABEL}" --against-spec "$against")
fi
# Release on every exit, including signals: without a trap SIGTERM would kill the script before release,
# leaving GPU busy until lease expiry. Bash handles a signal to run.sh alone after the job exits (client
# already closed). A whole-group signal (Ctrl+C) stops laptop_job.sh too, while the job in its own systemd service
# continues: release refuses (75) while its client holds the lock. A background retry then releases after the client exits.
release_lease() {
  "$ROOT/tools/gpu_lease.sh" release >/dev/null 2>&1
  [ $? -eq 75 ] || return 0
  setsid nohup bash -c 'for _ in $(seq 720); do sleep 5; "$1" release >/dev/null 2>&1; [ $? -eq 75 ] || exit 0; done' \
    _ "$ROOT/tools/gpu_lease.sh" >/dev/null 2>&1 &
}
trap release_lease EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
"$ROOT/tools/laptop_job.sh" --gpu --wait "stand-$NAME" -- python3 "$HERE/stand.py" play "$NAME" "$JAR" --spec "$SPEC" --label "$LABEL" \
  "${pair[@]}" "${play_only[@]}" "${args[@]}"
code=$?
# play reports output location ("results in …"): bench directories are shared by repo trees (stand.py, STAND)
echo "stand $NAME: exit $code"
exit $code
