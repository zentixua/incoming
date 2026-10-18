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

/** Модель горящего города: где и сколько горит, когда разгорается и гаснет, вторичные взрывы, тишина животных. */
class FireFieldTest {
    private static final Detonation GROUND = new Detonation(1, new Vec3(0, 64, 0), 64, 15, true, 0, 0, 0, 20_000, 7, 1, false, 1);
    private static final Detonation AIR = new Detonation(2, new Vec3(0, 64 + 500, 0), 64, 15, false, 0, 0, 0, 20_000, 9, 1, false, 1);

    @Test
    void firesStopAtTheServerCapAndStartOutsideTheFireball() {
        for (Detonation d : List.of(GROUND, AIR)) {
            FireField f = new FireField(d);
            double lit = Arrays.stream(f.lit).sum();
            assertTrue(f.total > 1000 && f.total <= lit + 1e-6 && f.total <= FireField.MAX_FIRES + 1e-6, "пожаров " + f.total + " из " + lit);
            int first = 0;
            while (f.fires[first] == 0) first++;
            if (d.surface()) assertTrue(FireField.radius(first) >= d.fireballRadius() * 0.8 - FireField.RING, "в шаре не горит");
            double last = 0;
            for (int i = 0; i < f.fires.length; i++) {
                assertTrue(f.fires[i] >= 0 && f.fires[i] <= f.lit[i] + 1e-9);
                assertTrue(f.keep[i] == 1 || f.keep[i] == FireField.BLOWN_KEEP);
                if (f.lit[i] == 0) continue;
                assertTrue(f.ignite[i] >= last - 1e-9 && f.ignite[i] <= d.pulseTicks() + 1e-9, "вспышка зажигает изнутри наружу за импульс: " + f.ignite[i]);
                last = f.ignite[i];
            }
            for (int i = 1; i < f.arrival.length; i++) assertTrue(f.arrival[i] >= f.arrival[i - 1], "волна идёт изнутри наружу");
        }
    }

    /**
     * Предел пожаров — счётом сервера ({@code RuinPlan.write}): слой вспышки идёт от эпицентра место за местом, зажигает,
     * пока несбиваемых волной меньше предела, сбиваемые — без счёта. Модель после волны держит столько же и до того же
     * кольца, а до волны горит и сбиваемое. У воздушного подрыва 15 кт у эпицентра всё — от 2 psi и в полном свете:
     * после волны 20 000, со вспышкой 40 000, огонь — до кольца 128–160 блоков (по счёту всех зажжённых было бы 10 000,
     * 20 000 и 96–128).
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
                    // сбиваемые — вперемешку с остальными, в своей доле (у сервера у каждого места свой бросок)
                    boolean isBlown = Math.floor((j + 1) * (1 - keep) + 1e-9) > blown;
                    if (isBlown) blown++;
                    lit++;
                    if (!isBlown) counted++;
                    if (last != i) rings++;
                    last = i;
                }
            }
            // места кольца у сервера — целые: на кольцо до одного места разницы
            double lastLit = 0;
            int modelLast = -1;
            for (int i = 0; i < f.lit.length; i++) {
                if (f.lit[i] > 0) modelLast = i;
                lastLit += f.lit[i];
            }
            assertEquals(counted, f.total, rings + 1, "после волны: сервер " + counted + ", модель " + f.total);
            assertEquals(lit, lastLit, 2 * (rings + 1), "со вспышкой: сервер " + lit + ", модель " + lastLit);
            assertEquals(last, modelLast, "последнее кольцо с огнём");
        }
        FireField air = new FireField(AIR);
        assertEquals(FireField.MAX_FIRES, air.total, 1e-6, "после волны");
        assertEquals(2 * FireField.MAX_FIRES, Arrays.stream(air.lit).sum(), 1e-6, "со вспышкой: волна от 2 psi сбивает половину");
        int last = air.lit.length - 1;
        while (air.lit[last] == 0) last--;
        assertEquals(4, last, "огонь — до кольца 128–160 блоков");
    }

    @Test
    void fireRisesWithTheFlashTheWaveBlowsSomeOutThenItDiesDown() {
        assertEquals(0, FireField.envelope(-1), 1e-9);
        assertEquals(0.5, FireField.envelope(FireField.RISE / 2), 1e-9);
        assertEquals(1, FireField.envelope(FireField.RISE), 1e-9);
        assertEquals(Math.exp(-1), FireField.envelope(FireField.RISE + FireField.TAU), 1e-9);
        for (Detonation d : List.of(GROUND, AIR)) {
            FireField f = new FireField(d);
            assertEquals(0, f.burning(0), 1e-9, "до вспышки не горит");
            double peak = f.burning(f.lastIgnition() + FireField.RISE);
            assertTrue(peak > 0.5 * f.total, "разгорелось: " + peak);
            assertTrue(f.burning(f.endTicks()) < 0.03 * f.total, "к концу почти погасло");
        }
        // у воздушного подрыва свет зажигает землю раньше, чем до неё дойдёт волна, а волна от 2 psi сбивает половину
        FireField f = new FireField(AIR);
        int rings = 0, before = 0, blown = 0;
        for (int i = 0; i < f.lit.length; i++) {
            if (f.lit[i] == 0) continue;
            rings++;
            if (f.ignite[i] + 1 < f.arrival[i]) {
                before++;
                assertEquals(f.lit[i] * FireField.envelope(f.arrival[i] - 1 - f.ignite[i]), f.burning(i, f.arrival[i] - 1), 1e-9);
                assertTrue(f.burning(i, f.arrival[i] - 1) > 0, "горит до волны: кольцо " + i);
            }
            if (f.keep[i] < 1) {
                blown++;
                assertEquals(f.keep[i] * f.lit[i] * FireField.envelope(f.arrival[i] - f.ignite[i]), f.burning(i, f.arrival[i]), 1e-9, "волна сбила долю");
            }
        }
        assertTrue(rings > 0 && before == rings && blown > 0, "колец с огнём: " + rings + ", горящих до волны: " + before + ", сбитых: " + blown);
    }

    /** Место набирает порог поджига за световой импульс: чем ярче, тем раньше; на пороге и ниже — к концу импульса. */
    @Test
    void lightReachesTheThresholdWithinThePulse() {
        double pulse = AIR.pulseTicks();
        assertEquals(20 * FireballModel.pulseSeconds(15), pulse, 1e-9);
        assertEquals(pulse, AIR.flashTicks(ThermalModel.IGNITE, ThermalModel.IGNITE), 1e-9, "на пороге — к концу импульса");
        assertEquals(pulse, AIR.flashTicks(1, ThermalModel.IGNITE), 1e-9, "ниже порога — к концу импульса");
        double last = pulse;
        for (double q = ThermalModel.IGNITE * 1.1; q < 1e4; q *= 1.5) {
            double t = AIR.flashTicks(q, ThermalModel.IGNITE);
            assertTrue(t < last && t > 0, "чем ярче, тем раньше: " + q + " — " + t);
            last = t;
        }
    }

    @Test
    void secondaryBlastsAreDeterministicSpreadOverTimeAndInsideTheFire() {
        FireField f = new FireField(GROUND);
        List<FireField.Secondary> a = f.secondaries(), b = new FireField(GROUND).secondaries();
        assertEquals(a, b, "из зерна подрыва — одни и те же");
        assertTrue(a.size() >= 4 && a.size() <= 24, "взрывов " + a.size());
        double reach = 0;
        for (int i = 0; i < f.fires.length; i++) if (f.fires[i] > 0) reach = (i + 1) * FireField.RING;
        double last = 0;
        for (FireField.Secondary s : a) {
            assertTrue(s.ticks() >= last, "по времени");
            last = s.ticks();
            assertTrue(s.ticks() >= FireField.SECONDARY_AFTER && s.ticks() <= f.arrival[f.arrival.length - 1] + FireField.SECONDARY_AFTER + FireField.SECONDARY_END,
                    "время " + s.ticks());
            assertTrue(Math.hypot(s.x(), s.z()) <= reach + 1e-6, "там, где горит: " + Math.hypot(s.x(), s.z()));
        }
        assertTrue(a.get(a.size() - 1).ticks() - a.get(0).ticks() > 60 * 20, "не разом, а минутами");
        assertTrue(!new FireField(AIR).secondaries().equals(a), "у другого подрыва — другие");
    }

    @Test
    void animalsFallSilentThenComeBack() {
        assertEquals(1, AnimalSilence.share(10), 1e-9);
        assertEquals(1, AnimalSilence.share(AnimalSilence.FULL - 1), 1e-9);
        assertEquals(0.5, AnimalSilence.share((AnimalSilence.FULL + AnimalSilence.BACK) / 2), 1e-9);
        assertEquals(0, AnimalSilence.share(AnimalSilence.BACK + 1), 1e-9);
    }
}
