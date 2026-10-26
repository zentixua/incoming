package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.ThermalModel;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Model of a burning city: where and how long it burns, when it flares up and goes out, secondary explosions, the silence of animals. */
class FireFieldTest {
    private static final Detonation GROUND = new Detonation(1, new Vec3(0, 64, 0), 64, 15, true, 0, 0, 0, 20_000, 7, 1, false, 1);
    private static final Detonation AIR = new Detonation(2, new Vec3(0, 64 + 500, 0), 64, 15, false, 0, 0, 0, 20_000, 9, 1, false, 1);

    @Test
    void firesStopAtTheServerCapAndStartOutsideTheFireball() {
        for (Detonation d : List.of(GROUND, AIR)) {
            FireField f = new FireField(d);
            double lit = Arrays.stream(f.lit).sum();
            assertTrue(f.total > 1000 && f.total <= lit + 1e-6 && f.total <= FireField.MAX_FIRES + 1e-6, "fires " + f.total + " from " + lit);
            int first = 0;
            while (f.fires[first] == 0) first++;
            if (d.surface()) assertTrue(FireField.radius(first) >= d.fireballRadius() * 0.8 - FireField.RING, "the ball doesn't light up");
            double last = 0;
            for (int i = 0; i < f.fires.length; i++) {
                assertTrue(f.fires[i] >= 0 && f.fires[i] <= f.lit[i] + 1e-9);
                assertTrue(f.keep[i] == 1 || f.keep[i] == FireField.BLOWN_KEEP);
                if (f.lit[i] == 0) continue;
                assertTrue(f.ignite[i] >= last - 1e-9 && f.ignite[i] <= d.pulseTicks() + 1e-9, "the flash lights from the inside out per impulse: " + f.ignite[i]);
                last = f.ignite[i];
            }
            for (int i = 1; i < f.arrival.length; i++) assertTrue(f.arrival[i] >= f.arrival[i - 1], "the wave comes from inside to outside");
        }
    }

    /**
     * The limit of fires is by server count ({@code RuinPlan.write}): a layer of flare goes from the epicenter place by place, ignites,
     *  until those unbeaten by the wave are less than the limit, those knocked down - without counting. The model after the wave holds the same amount before the same
     * rings, and before the wave the knocked down one burns. The air blast 15 kt at the epicenter has everything - from 2 psi and in full light:
     * after the wave 20 000, with a flash 40 000, fire - to the ring 128–160 blocks (according to the count of all lit blocks there would be 10 000,
     * 20 000 and 96–128).
     */
    @Test
    void capCountsWhatTheWaveLeavesAsTheServerDoes() {
        for (Detonation d : List.of(GROUND, AIR)) {
            FireField f = new FireField(d);
            double counted = 0, lit = 0;
            int last = -1, rings = 0;
            rings:
            for (int i = 0; i < f.lit.length; i++) {
                long places = Math.round(FireField.candidates(d, i));
                double keep = FireField.keepAt(d, i);
                long blown = 0;
                for (long j = 0; j < places; j++) {
                    if (counted >= FireField.MAX_FIRES) break rings;
                    // knocked down - mixed with the rest, in their share (the server has its own throw for each place)
                    boolean isBlown = Math.floor((j + 1) * (1 - keep) + 1e-9) > blown;
                    if (isBlown) blown++;
                    lit++;
                    if (!isBlown) counted++;
                    if (last != i) rings++;
                    last = i;
                }
            }
            // the server's ring spaces are whole: there is a difference of up to one space per ring
            double lastLit = 0;
            int modelLast = -1;
            for (int i = 0; i < f.lit.length; i++) {
                if (f.lit[i] > 0) modelLast = i;
                lastLit += f.lit[i];
            }
            assertEquals(counted, f.total, rings + 1, "after the wave: server " + counted + ", model " + f.total);
            assertEquals(lit, lastLit, 2 * (rings + 1), "with flash: server " + lit + ", model " + lastLit);
            assertEquals(last, modelLast, "the last fire ring");
        }
        FireField air = new FireField(AIR);
        assertEquals(FireField.MAX_FIRES, air.total, 1e-6, "after the wave");
        assertEquals(2 * FireField.MAX_FIRES, Arrays.stream(air.lit).sum(), 1e-6, "with flash: the wave from 2 psi knocks down half");
        int last = air.lit.length - 1;
        while (air.lit[last] == 0) last--;
        assertEquals(4, last, "fire - up to the ring 128–160 blocks");
    }

    @Test
    void fireRisesWithTheFlashTheWaveBlowsSomeOutThenItDiesDown() {
        assertEquals(0, FireField.envelope(-1), 1e-9);
        assertEquals(0.5, FireField.envelope(FireField.RISE / 2), 1e-9);
        assertEquals(1, FireField.envelope(FireField.RISE), 1e-9);
        assertEquals(Math.exp(-1), FireField.envelope(FireField.RISE + FireField.TAU), 1e-9);
        for (Detonation d : List.of(GROUND, AIR)) {
            FireField f = new FireField(d);
            assertEquals(0, f.burning(0), 1e-9, "does not light up before the flash");
            double peak = f.burning(f.lastIgnition() + FireField.RISE);
            assertTrue(peak > 0.5 * f.total, "flared up: " + peak);
            assertTrue(f.burning(f.endTicks()) < 0.03 * f.total, "almost went out towards the end");
        }
        // in an air blast, the light lights up the ground before the wave reaches it, and the wave from 2 psi knocks down half
        FireField f = new FireField(AIR);
        int rings = 0, before = 0, blown = 0;
        for (int i = 0; i < f.lit.length; i++) {
            if (f.lit[i] == 0) continue;
            rings++;
            if (f.ignite[i] + 1 < f.arrival[i]) {
                before++;
                assertEquals(f.lit[i] * FireField.envelope(f.arrival[i] - 1 - f.ignite[i]), f.burning(i, f.arrival[i] - 1), 1e-9);
                assertTrue(f.burning(i, f.arrival[i] - 1) > 0, "burns to wave: ring " + i);
            }
            if (f.keep[i] < 1) {
                blown++;
                assertEquals(f.keep[i] * f.lit[i] * FireField.envelope(f.arrival[i] - f.ignite[i]), f.burning(i, f.arrival[i]), 1e-9, "the wave knocked down the share");
            }
        }
        assertTrue(rings > 0 && before == rings && blown > 0, "fire rings: " + rings + ", burning to the wave: " + before + ", shot down: " + blown);
    }

    /** The place reaches the ignition threshold per light pulse: the brighter, the sooner; at the threshold and below - towards the end of the impulse. */
    @Test
    void lightReachesTheThresholdWithinThePulse() {
        double pulse = AIR.pulseTicks();
        assertEquals(20 * FireballModel.pulseSeconds(15), pulse, 1e-9);
        assertEquals(pulse, AIR.flashTicks(ThermalModel.IGNITE, ThermalModel.IGNITE), 1e-9, "on the threshold - towards the end of the impulse");
        assertEquals(pulse, AIR.flashTicks(1, ThermalModel.IGNITE), 1e-9, "below the threshold - towards the end of the impulse");
        double last = pulse;
        for (double q = ThermalModel.IGNITE * 1.1; q < 1e4; q *= 1.5) {
            double t = AIR.flashTicks(q, ThermalModel.IGNITE);
            assertTrue(t < last && t > 0, "the brighter, the sooner: " + q + " — " + t);
            last = t;
        }
    }

    @Test
    void secondaryBlastsAreDeterministicSpreadOverTimeAndInsideTheFire() {
        FireField f = new FireField(GROUND);
        List<FireField.Secondary> a = f.secondaries(), b = new FireField(GROUND).secondaries();
        assertEquals(a, b, "from the seed detonation - the same");
        assertTrue(a.size() >= 4 && a.size() <= 24, "explosions " + a.size());
        double reach = 0;
        for (int i = 0; i < f.fires.length; i++) if (f.fires[i] > 0) reach = (i + 1) * FireField.RING;
        double last = 0;
        for (FireField.Secondary s : a) {
            assertTrue(s.ticks() >= last, "by time");
            last = s.ticks();
            assertTrue(s.ticks() >= FireField.SECONDARY_AFTER && s.ticks() <= f.arrival[f.arrival.length - 1] + FireField.SECONDARY_AFTER + FireField.SECONDARY_END,
                    "time " + s.ticks());
            assertTrue(Math.hypot(s.x(), s.z()) <= reach + 1e-6, "where it burns: " + Math.hypot(s.x(), s.z()));
        }
        assertTrue(a.get(a.size() - 1).ticks() - a.get(0).ticks() > 60 * 20, "not all at once, but in minutes");
        assertTrue(!new FireField(AIR).secondaries().equals(a), "at another explosion - others");
    }

    @Test
    void animalsFallSilentThenComeBack() {
        assertEquals(1, AnimalSilence.share(10), 1e-9);
        assertEquals(1, AnimalSilence.share(AnimalSilence.FULL - 1), 1e-9);
        assertEquals(0.5, AnimalSilence.share((AnimalSilence.FULL + AnimalSilence.BACK) / 2), 1e-9);
        assertEquals(0, AnimalSilence.share(AnimalSilence.BACK + 1), 1e-9);
    }
}
