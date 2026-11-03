"""Install the same disposable structural fixture in either sandbox worktree."""
from pathlib import Path
import sys
root = Path(sys.argv[1]).resolve()
p = root / 'mod/src/devtest/java/ua/zentix/incoming/scenario/Sandbox.java'
s = p.read_text()
a = s.index('    private static void buildHouses(MinecraftServer server, BlockPos o) {')
b = s.index('\n    /**', a)
s = s[:a] + '''    private static void buildHouses(MinecraftServer server, BlockPos o) {
        int y = o.getY(), z = o.getZ();
        for (int side : new int[]{-1, 1}) {
            int x = o.getX() + side * 18;
            run(server, "fill %d %d %d %d %d %d minecraft:glass hollow".formatted(x - 12, y, z - 12, x + 12, y + 5, z + 12));
            run(server, "fill %d %d %d %d %d %d minecraft:air".formatted(x - 11, y, z - 11, x + 11, y + 4, z + 11));
            run(server, "fill %d %d %d %d %d %d minecraft:%s".formatted(x - 12, y + 5, z - 12, x + 12, y + 5, z + 12,
                    side < 0 ? "stone" : "white_concrete"));
        }
        for (int side : new int[]{-1, 1}) {
            int x = o.getX() + side * 8, tz = z + 24, height = side < 0 ? 16 : 30;
            run(server, "fill %d %d %d %d %d %d minecraft:oak_log".formatted(x, y, tz, x, y + height - 1, tz));
            if (side > 0) run(server, "fill %d %d %d %d %d %d minecraft:oak_leaves[persistent=true]".formatted(
                    x - 2, y + height, tz - 2, x + 2, y + height + 1, tz + 2));
        }
    }
''' + s[b:]
needle = '        if (cap == null || effectTick < 0 || cap.next >= cap.times.length) return;'
assert needle in s
# This final diagnostic image isolates authoritative geometry after the ordinary motion/audio frames.
s = s.replace(needle, needle + '\n        if (tick - effectTick >= 160) IncomingClient.resetSession();')
p.write_text(s)
