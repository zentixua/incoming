package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlastGustTest {
    /** 15 кт у земли на высоте 64, блок — метр; волна как в жизни или игровая (0,2 звука). */
    private static Detonation ground(float wave) {
        return new Detonation(1, new Vec3(0, 64, 0), 64, 15, true, 0, 0, 0, 20_000, 7, 1f, false, wave);
    }

    private static final double[] RANGES = {150, 300, 600, 1000, 2000, 4000};

    /**
     * Ветер — Фридлендер на месте: до фронта ноль, сразу за ним — ветер Ренкина–Гюгонио модели, к концу фазы ноль и потом
     * обратный; путь воздуха — его интеграл (тот же, что несёт пыль головы стены: доля {@link BlastWall#carriedShare}), к
     * концу фазы U·t₊/e.
     */
    @Test
    void windIsFriedlanderAtThePlace() {
        Detonation d = ground(1);
        for (double r : RANGES) {
            Vec3 at = new Vec3(r, 65, 0);
            BlastGust g = BlastGust.of(d, at, () -> 0);
            assertEquals(0, g.speed(g.arrival - 1), 0, r + ": до фронта");
            assertEquals(d.blastWind(at) / 20, g.speed(g.arrival + 1e-6), 1e-6 * g.u0 + 1e-9, r + ": сразу за фронтом");
            assertEquals(0, g.speed(g.arrival + g.phase), 1e-12, r + ": конец фазы");
            assertTrue(g.speed(g.arrival + 2 * g.phase) < 0, r + ": фаза разрежения");
            double end = g.u0 * g.phase * Math.exp(-1);
            assertEquals(end, g.path(g.arrival + g.phase), 1e-6 * end, r + ": путь за фазу");
            for (double x = 0.1; x <= 1; x += 0.1)
                assertEquals(BlastWall.carriedShare(x) * end, g.path(g.arrival + x * g.phase), 1e-6 * end, r + ": путь как у пыли головы");
        }
    }

    /**
     * Ничто не опережает фронт волны: путь воздуха от места не уходит дальше фронта в игре (и при игровой волне 0,2 звука,
     * где ветер вблизи быстрее фронта), впереди фронта ветра нет; пройденный воздухом путь не убывает.
     */
    @Test
    void nothingOutrunsTheFront() {
        for (float wave : new float[]{1f, 0.2f}) {
            Detonation d = ground(wave);
            double[] into = new double[3];
            for (double r : RANGES) {
                Vec3 at = new Vec3(r, 65, 0);
                double slant = at.distanceTo(d.burst());
                double[] now = {0};
                BlastGust g = BlastGust.of(d, at, () -> now[0]);
                double travel = 0;
                for (double t = g.arrival; t < g.arrival + 4 * g.phase; t += g.phase / 50) {
                    assertTrue(slant + g.path(t) <= d.frontRadius(t) + 1e-6, wave + " " + r + " at " + t);
                    assertTrue(g.travel(t) >= travel - 1e-9, wave + " " + r + ": путь туда и обратно не убывает");
                    travel = g.travel(t);
                    now[0] = t;
                    double ahead = d.frontRadius(t) + 1;
                    g.at(ahead, 64, 0, into);
                    assertEquals(0, into[1], 0, wave + " " + r + ": впереди фронта ветра нет");
                }
            }
        }
    }

    /**
     * Полоса пыли пролома: первая пыль — дальше всех, на путь воздуха с прихода; последняя — у пролома, пока дует фаза;
     * до фронта пыли нет; слой, в котором она растворяется, у фронта нулевой и растёт.
     */
    @Test
    void breachDustLiesAlongTheWind() {
        Detonation d = ground(1);
        Vec3 at = new Vec3(1000, 65, 0);
        BlastGust g = BlastGust.of(d, at, () -> 0);
        double[] head = new double[4], tail = new double[4];
        BlastDust.parcel(g, g.arrival, g.arrival - 5, head);
        assertEquals(0, head[0], 0, "до фронта");
        for (double x = 0.2; x <= 1; x += 0.2) {
            double t = g.arrival + x * g.phase;
            BlastDust.parcel(g, g.arrival, t, head);
            BlastDust.parcel(g, t, t, tail);
            assertEquals(g.path(t), head[0], 1e-9, "первая пыль — на путь воздуха");
            assertEquals(0, tail[0], 1e-9, "последняя — у пролома");
            assertTrue(head[1] > 0 && head[3] > 0, "поднимается и расплывается");
        }
        double groundM = d.metres(1000);
        assertEquals(0, BlastDust.layerTop(d, groundM, 0), 1e-9);
        assertTrue(BlastDust.layerTop(d, groundM, 20) > 0 && BlastDust.layerTop(d, groundM, 100) > BlastDust.layerTop(d, groundM, 20));
    }
}
