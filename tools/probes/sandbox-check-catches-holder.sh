#!/usr/bin/env bash
# Probe (review of PR #789): `tools/sandbox.sh check` fails, naming the Sable class, when a GameTest holder calls Sable
# directly. A synthetic holder is placed in devtest for the run and removed after it. Two client starts on the CPU
# (a few minutes); not in CI, whose job “Песочница” runs the positive half on every mod change. Run by hand after
# changing `check` (it puts itself into a laptop job where systemd exists):
#   bash tools/probes/sandbox-check-catches-holder.sh [tree]
# Exit 0: the tree starts, and with the planted holder the check fails on NoClassDefFoundError of a Sable type.
set -uo pipefail
WT=$(cd "${1:-$(dirname "$0")/../..}" && pwd)
F=$WT/mod/src/devtest/java/ua/zentix/incoming/gametest/ProbeSableHolderGameTests.java
LOG=$WT/mod/run/probe-sable-holder.log
cd "$WT" || exit 2
mkdir -p "$WT/mod/run"
[ -e "$F" ] && { echo "PROBE: $F exists already" >&2; exit 2; }
tools/sandbox.sh check > "$LOG" 2>&1 || { echo "PROBE FAIL: the tree does not start (see $LOG)"; exit 1; }
trap 'rm -f "$F"' EXIT
cat > "$F" <<'JAVA'
package ua.zentix.incoming.gametest;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import ua.zentix.incoming.Incoming;

/** Probe only (tools/probes/sandbox-check-catches-holder.sh): a holder that hands a Sable type to Sable's pipeline. */
@GameTestHolder(Incoming.MOD_ID)
public final class ProbeSableHolderGameTests {
    private ProbeSableHolderGameTests() {}

    @GameTest(template = "runway", batch = "probe_sable_holder")
    public static void holds(GameTestHelper h) {
        h.succeed();
    }

    static void stop(GameTestHelper h, SubLevelAccess craft) {
        SubLevelContainer.getContainer(h.getLevel()).physicsSystem().getPipeline().resetVelocity((ServerSubLevel) craft);
    }
}
JAVA
INCOMING_SANDBOX_CHECK_LIMIT=${INCOMING_SANDBOX_CHECK_LIMIT:-600} tools/sandbox.sh check >> "$LOG" 2>&1 \
  && { echo "PROBE FAIL: the planted holder passed the check (see $LOG)"; exit 1; }
grep -qE 'NoClassDefFoundError: dev/ryanhcode/sable' "$LOG" \
  || { echo "PROBE FAIL: the check failed, but not on a Sable class"; tail -n 20 "$LOG"; exit 1; }
echo "PROBE OK: the tree starts; the planted holder stops the client and the check names the Sable class"
