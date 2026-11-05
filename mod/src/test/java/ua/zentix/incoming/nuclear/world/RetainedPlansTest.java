package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RetainedPlansTest {
    private static RuinPlan plan(int cells) {
        return new RuinPlan(new int[cells], new long[0], new BlockState[0], new BlockState[cells],
                new int[0], new int[0], new long[0], null, null, new LongArrayList(), List.of(), null, new long[0], null);
    }

    @Test
    void replacementTransferAndClearCountRetainedBytes() {
        RetainedPlans plans = new RetainedPlans();
        RuinPlan a = plan(100), b = plan(200);
        plans.put(1L, a);
        plans.put(1L, b);
        assertEquals(b.bytes() + 48, plans.bytes());
        plans.put(2L, a);
        assertSame(b, plans.remove(1L));
        assertEquals(a.bytes() + 48, plans.bytes(), "applied plan transfers ownership to its context");
        plans.remove(1L);
        assertEquals(a.bytes() + 48, plans.bytes());
        plans.clear();
        assertEquals(0, plans.bytes());
    }
}
