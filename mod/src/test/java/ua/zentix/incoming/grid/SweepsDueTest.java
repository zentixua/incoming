package ua.zentix.incoming.grid;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A built repair cascade waiting for its next due district is not blackout work: a nuclear repair waits weeks of game
 * time, and counting it as pending would keep the blackout share of the tick budget from impacts and ruins.
 */
class SweepsDueTest {
    @Test
    void waitingCascadeIsNotWork() throws ReflectiveOperationException {
        Outage o = new Outage(1, 0, 0, 300, 0, 50, Outage.NEVER, 0, -1, 50, 150);
        BlackoutWorld world = new BlackoutWorld();
        world.onRestore(o);
        assertTrue(world.busy(), "a new cascade must build");
        Field f = BlackoutWorld.class.getDeclaredField("sweeps");
        f.setAccessible(true);
        @SuppressWarnings("unchecked") List<BlackoutWorld.Sweep> sweeps = (List<BlackoutWorld.Sweep>) f.get(world);
        BlackoutWorld.Sweep s = sweeps.get(0);
        while (!s.built()) s.buildRow();
        Method due = BlackoutWorld.class.getDeclaredMethod("updateSweepsDue", long.class);
        due.setAccessible(true);
        due.invoke(world, s.first - 100);
        assertFalse(world.busy(), "a cascade waiting for its first district counts as work");
        due.invoke(world, s.first - 1);
        assertTrue(world.busy(), "a district due next tick is not work");
    }
}
