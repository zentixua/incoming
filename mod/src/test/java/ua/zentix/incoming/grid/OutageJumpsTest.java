package ua.zentix.incoming.grid;

import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.util.RecoveryClock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An outage held by a conventional strike's show across jumps ({@link Blackouts#step}): an unseen night moves the repair
 * and keeps the show, a later strike holds it again for its own show, and the shortened end survives a restart.
 */
class OutageJumpsTest {
    private long game = 1000;
    private double rate, counted, unseen;

    private Outage run(Outage o, RecoveryClock c, long base, long ticks) {
        for (long t = 0; t < ticks; t += 20) {
            game += 20;
            rate += 20;
            o = Blackouts.step(o, c, base, game, rate, counted, unseen, "overworld", id -> RecoveryClock.NONE);
        }
        return o;
    }

    private static Outage struck(long until) {
        return new Outage(1, 0, 0, 512, 0, 3, 18_000, 90, 7).heldBy(-1, until);
    }

    /** Rule 7, awake case: the night moves the repair (its hours on the outage's clock) and keeps the show and the lag. */
    @Test
    void anUnseenNightMovesRepairsAndKeepsTheShow() {
        long until = game + 900 * 20;
        Outage o = struck(until);
        RecoveryClock c = RecoveryClock.district();
        o = run(o, c, 0, 120 * 20);
        long lag = o.lag();
        double before = c.clock();
        unseen += 8000;
        o = run(o, c, 0, 20);
        assertEquals(until, o.until(), "show kept");
        assertTrue(c.clock() - before >= 8000, "the night's hours reach the outage's clock: " + (c.clock() - before));
        assertTrue(o.lag() - lag <= 20, "the night adds no lag: " + (o.lag() - lag));
    }

    /** A later strike on the same substation after a counted night holds the repair again, for its own show only. */
    @Test
    void aLaterStrikeAfterACountedNightHoldsAgain() {
        Outage o = struck(game + 900 * 20);
        RecoveryClock c = RecoveryClock.district();
        o = run(o, c, 0, 120 * 20);
        counted += 8000;
        o = run(o, c, 0, 120 * 20); // the jump, the blend, settled
        assertTrue(c.settled());
        long second = game + 180 * 20; // a Shahed's apex-only show, 3 min
        o = o.heldBy(-1, second);
        double before = c.clock();
        o = run(o, c, 0, 120 * 20);
        assertEquals(second, o.until(), "the later strike's show holds");
        assertEquals(120 * 20 / 72.0, c.clock() - before, 1, "real rate inside the later show");
    }

    /** The shortened end survives the save: a restart after the jump step blends from the jump, not the planned end. */
    @Test
    void theJumpsEndIsSaved() {
        Outage o = struck(game + 900 * 20);
        RecoveryClock c = RecoveryClock.district();
        o = run(o, c, 0, 120 * 20);
        counted += 8000;
        o = run(o, c, 0, 20);
        long jumpEnd = o.until();
        Outage loaded = Outage.CODEC.parse(JsonOps.INSTANCE, Outage.CODEC.encodeStart(JsonOps.INSTANCE, o).getOrThrow()).getOrThrow();
        assertEquals(jumpEnd, loaded.until());
        RecoveryClock fresh = RecoveryClock.district();
        loaded = run(loaded, fresh, loaded.lag(), 120 * 20);
        assertTrue(fresh.settled(), "a restart after the jump step settles after the blend");
    }
}
