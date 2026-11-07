package ua.zentix.incoming.grid;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.function.LongPredicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lamps on their own supply: placed in a dark district, they stay lit for that outage, are the grid's again for the next. */
class PowerGridTest {
    private static final BlockPos OWN = new BlockPos(3, 70, 5), OLD = new BlockPos(4, 70, 5);

    @Test
    void ownLampsStayLitUntilTheNextOutage() {
        PowerGrid grid = new PowerGrid();
        // instant cascade, power only by command
        grid.addOutage(0, 0, 512, 0, 1000, Outage.NEVER, 0, -1);
        assertTrue(grid.dark(0, 0, 100));
        assertNull(grid.keepsLit(0, 0, 100), "no own lamps yet");
        grid.placeOwn(OWN, 100);
        LongPredicate keep = grid.keepsLit(0, 0, 150);
        assertNotNull(keep);
        assertTrue(keep.test(OWN.asLong()));
        assertFalse(keep.test(OLD.asLong()), "a lamp that stood before the outage stays lit");

        // saved with the world
        PowerGrid loaded = PowerGrid.load(grid.save(new CompoundTag(), null), null);
        LongPredicate again = loaded.keepsLit(0, 0, 150);
        assertTrue(again != null && again.test(OWN.asLong()), "own lamp forgotten after save");

        // another outage reaches the district after the lamp was placed: it is the grid's lamp again
        grid.addOutage(0, 0, 512, 200, 1000, Outage.NEVER, 0, -1);
        LongPredicate later = grid.keepsLit(0, 0, 300);
        assertTrue(later == null || !later.test(OWN.asLong()), "own lamp survived a new outage");
    }

    @Test
    void ownLampsForgottenWithTheirOutage() {
        PowerGrid grid = new PowerGrid();
        Outage o = grid.addOutage(0, 0, 512, 0, 1000, Outage.NEVER, 0, -1);
        grid.placeOwn(OWN, 100);
        grid.replace(o.restoring(200, 0));
        grid.swept(o.id(), true, Long.MAX_VALUE);
        grid.prune(300);
        assertTrue(grid.outages().isEmpty());
        // a new outage: the old record must not keep the lamp lit (it was placed before this one)
        grid.addOutage(0, 0, 512, 400, 1000, Outage.NEVER, 0, -1);
        assertNull(grid.keepsLit(0, 0, 500), "own lamp record outlived its outage");
        assertFalse(grid.save(new CompoundTag(), null).contains("own_lamps"));
    }

    /** Destroyed zone: dark by damage with no repair; a command restore ends it. */
    @Test
    void destroyedZone() {
        PowerGrid grid = new PowerGrid();
        Outage o = grid.addOutage(new Outage(0, 0, 0, 8000, 0, 1000, Outage.NEVER, 0, -1, 2500, 6000));
        assertTrue(grid.destroyed(0, 0, 10));
        assertFalse(grid.destroyed(300, 0, 10), "a district 4800 blocks out is in the damaged belt, not destroyed");
        grid.replace(o.restoring(20, 0));
        assertFalse(grid.destroyed(0, 0, 30));
    }
}
