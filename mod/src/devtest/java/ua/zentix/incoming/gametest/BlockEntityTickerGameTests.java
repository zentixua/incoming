package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Removed block-entity tickers leave the level's list in one pass at the end of its block-entity tick
 * ({@code mixin/level/BlockEntityTickerRemovalMixin}), not one {@code ArrayList} removal each: the client re-creates every
 * chest of a ruined chunk it receives whole, and on the Zearth Silo map (20,074 chests in view) removing them one by one
 * held single client ticks for 200–300 ms while the wave crossed the city (passage-burst run 24.11.2026).
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BlockEntityTickerGameTests {
    private BlockEntityTickerGameTests() {}

    /**
     * One block-entity tick with 1001 removed tickers: none leaves by a single-element removal (the list counts them),
     * all leave by the end of that tick, and a ticker removed after its own turn stays listed until the next loop, as in
     * vanilla. Without the mixin this test fails.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "be_ticker_removal")
    public static void removedTickersLeaveInOnePass(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(1, 2, 1));
        Fake early = new Fake(pos), gone = new Fake(pos), many = new Fake(pos);
        List<Fake> removed = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            Fake f = new Fake(pos);
            f.removed = true;
            removed.add(f);
        }
        // the ticker after "early" removes it during the loop, after early's own turn
        many.onTick = () -> early.removed = true;
        Counting[] counting = {null};
        String[] firstTick = {null};
        boolean[] earlyGoneNext = {false};
        h.runAfterDelay(1, () -> {
            counting[0] = swap(level);
            level.addBlockEntityTicker(early);
            level.addBlockEntityTicker(many);
            removed.forEach(level::addBlockEntityTicker);
            level.addBlockEntityTicker(gone);
            gone.removed = true;
        });
        h.onEachTick(() -> {
            Counting list = counting[0];
            if (list == null) return;
            // between the first block-entity tick that ran "many" and the next one
            if (many.ticks == 1 && firstTick[0] == null) {
                firstTick[0] = "single removals " + list.single + ", removed left listed "
                        + (list.contains(gone) || removed.stream().anyMatch(list::contains)) + ", early listed " + list.contains(early);
            }
            if (many.ticks == 2) earlyGoneNext[0] = !list.contains(early);
        });
        h.succeedWhen(() -> {
            h.assertTrue(firstTick[0] != null && many.ticks >= 2, "the test tickers have not ticked twice yet");
            h.assertTrue(firstTick[0].equals("single removals 0, removed left listed false, early listed true"),
                    "after the first block-entity tick: " + firstTick[0] + " (expected none, false, true)");
            h.assertTrue(earlyGoneNext[0], "the ticker removed after its turn did not leave on the next tick");
            h.assertTrue(counting[0].contains(many), "a live ticker left the list");
        });
        StrikeGameTests.afterTest(h, () -> {
            many.removed = true;
            if (counting[0] != null) restore(level, counting[0]);
        });
    }

    /**
     * A chunk's ticker wrapper whose block entity a mod marks removed after the wrapper's turn, and which the chunk
     * rebinds to a new block entity at that position before the next loop, stays listed and ticks, as in vanilla: the
     * one-pass removal drops only what the loop itself found removed (review probe, round 1).
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "be_ticker_rebind")
    public static void reboundWrapperKeepsTicking(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos furnace = h.absolutePos(new BlockPos(3, 2, 3));
        List<TickingBlockEntity> list = tickers(level);
        Object[] wrapper = {null};
        Fake marker = new Fake(furnace), remover = new Fake(furnace);
        marker.removed = true; // any ticker removed this tick, as in a live world: the loop has something to compact
        int[] phase = {0};
        h.runAfterDelay(1, () -> {
            level.setBlock(furnace, Blocks.FURNACE.defaultBlockState(), 2);
            LevelChunk chunk = level.getChunkAt(furnace);
            wrapper[0] = tickersInLevel(chunk).get(furnace);
            h.assertTrue(wrapper[0] != null && list.contains((TickingBlockEntity) wrapper[0]), "furnace ticker not listed");
            level.addBlockEntityTicker(marker);
            level.addBlockEntityTicker(remover);
            // after the furnace's turn: its block entity is marked removed but stays bound (a mod's setRemoved())
            remover.onTick = () -> {
                if (phase[0] == 0) {
                    BlockEntity be = level.getBlockEntity(furnace);
                    if (be != null) be.setRemoved();
                    phase[0] = 1;
                }
            };
        });
        h.onEachTick(() -> {
            if (phase[0] == 1) {
                // between level ticks: same block, new state; LevelChunk.setBlockState replaces the removed block
                // entity and updateBlockEntityTicker rebinds the same wrapper
                level.setBlock(furnace, Blocks.FURNACE.defaultBlockState().setValue(FurnaceBlock.LIT, true), 2);
                phase[0] = 2;
            } else if (phase[0] == 2) {
                phase[0] = 3; // one more block-entity loop
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(phase[0] == 3, "phase " + phase[0]);
            TickingBlockEntity w = (TickingBlockEntity) wrapper[0];
            h.assertTrue(tickersInLevel(level.getChunkAt(furnace)).get(furnace) == w, "chunk dropped the wrapper");
            h.assertFalse(w.isRemoved(), "wrapper not rebound");
            h.assertTrue(list.contains(w), "rebound furnace ticker is no longer in the level's list: the new furnace never ticks");
        });
        StrikeGameTests.afterTest(h, () -> remover.removed = true);
    }

    @SuppressWarnings("unchecked")
    private static List<TickingBlockEntity> tickers(Level level) {
        try {
            java.lang.reflect.Field f = Level.class.getDeclaredField("blockEntityTickers");
            f.setAccessible(true);
            return (List<TickingBlockEntity>) f.get(level);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The level's original ticker list, swapped out while the test counts single-element removals. */
    private static List<TickingBlockEntity> original;

    /** A ticker list that counts {@code remove(int)}: vanilla's iterator removal goes through it, one pass does not. */
    private static final class Counting extends ArrayList<TickingBlockEntity> {
        int single;

        Counting(List<TickingBlockEntity> from) {
            super(from);
        }

        @Override
        public TickingBlockEntity remove(int index) {
            single++;
            return super.remove(index);
        }
    }

    private static Counting swap(Level level) {
        original = tickers(level);
        Counting c = new Counting(original);
        set(level, c);
        return c;
    }

    private static void restore(Level level, Counting c) {
        original.clear();
        original.addAll(c);
        set(level, original);
        original = null;
    }

    private static void set(Level level, List<TickingBlockEntity> list) {
        try {
            java.lang.reflect.Field f = Level.class.getDeclaredField("blockEntityTickers");
            f.setAccessible(true);
            f.set(level, list);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<BlockPos, Object> tickersInLevel(LevelChunk chunk) {
        try {
            java.lang.reflect.Field f = LevelChunk.class.getDeclaredField("tickersInLevel");
            f.setAccessible(true);
            return (Map<BlockPos, Object>) f.get(chunk);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Fake implements TickingBlockEntity {
        final BlockPos pos;
        boolean removed;
        int ticks;
        Runnable onTick;

        Fake(BlockPos pos) {
            this.pos = pos;
        }

        @Override
        public void tick() {
            ticks++;
            if (onTick != null) onTick.run();
        }

        @Override
        public boolean isRemoved() {
            return removed;
        }

        @Override
        public BlockPos getPos() {
            return pos;
        }

        @Override
        public String getType() {
            return "incoming:test";
        }
    }
}
