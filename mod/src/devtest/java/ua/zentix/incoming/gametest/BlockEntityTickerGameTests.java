package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;

import java.util.ArrayList;
import java.util.List;

/**
 * Removed block-entity tickers leave the level's list in one pass at the end of its block-entity tick
 * ({@code mixin/level/BlockEntityTickerRemovalMixin}), not one {@code ArrayList} removal each: the client re-creates every
 * chest of a ruined chunk it receives whole, and on the Zearth Silo map (20,074 chests in view) removing them one by one
 * held single client ticks for 200–300 ms while the wave crossed the city (passage-burst run 24.11.2026).
 * <p>
 * Seen from the list: a ticker removed during the loop after its own turn is still listed after vanilla's loop (it
 * goes on the next tick) and gone after the one-pass removal. Without the mixin this test fails.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BlockEntityTickerGameTests {
    private BlockEntityTickerGameTests() {}

    @GameTest(template = "range", timeoutTicks = 60, batch = "be_ticker_removal")
    public static void removedTickersLeaveInOnePass(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(1, 2, 1));
        List<TickingBlockEntity> list = tickers(level);
        Fake early = new Fake(pos), gone = new Fake(pos), many = new Fake(pos);
        List<Fake> removed = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            Fake f = new Fake(pos);
            f.removed = true;
            removed.add(f);
        }
        // the ticker after "early" removes it during the loop, after early's own turn
        many.onTick = () -> early.removed = true;
        boolean[] seen = {false, false};
        h.runAfterDelay(1, () -> {
            level.addBlockEntityTicker(early);
            level.addBlockEntityTicker(many);
            removed.forEach(level::addBlockEntityTicker);
            level.addBlockEntityTicker(gone);
            gone.removed = true;
        });
        h.onEachTick(() -> {
            // between the first block-entity tick that ran "many" and the next one
            if (many.ticks == 1 && !seen[0]) {
                seen[0] = true;
                seen[1] = list.contains(early) || list.contains(gone) || removed.stream().anyMatch(list::contains);
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(seen[0], "the test tickers have not ticked yet");
            h.assertFalse(seen[1], "removed tickers are still listed after the block-entity tick that removed them");
            h.assertTrue(list.contains(many), "a live ticker left the list");
        });
        StrikeGameTests.afterTest(h, () -> many.removed = true);
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
