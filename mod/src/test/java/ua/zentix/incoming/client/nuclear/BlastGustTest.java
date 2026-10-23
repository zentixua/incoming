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
     * Ничто не опережает фронт волны: клок листвы и доска, сорванные в покое у места, летят с ветром (сопротивление по своей
     * скорости падения, ветер — в точке куска, вверх — наибольшая доля) и не уходят дальше фронта в игре — и при игровой
     * волне 0,2 звука, где настоящий ветер вблизи быстрее фронта.
     */
    @Test
    void piecesStayBehindTheFront() {
        for (float wave : new float[]{1f, 0.2f}) {
            Detonation d = ground(wave);
            for (double r : RANGES) {
                for (ua.zentix.incoming.client.fx.Fragments.Kind kind : new ua.zentix.incoming.client.fx.Fragments.Kind[]{
                        ua.zentix.incoming.client.fx.Fragments.Kind.LEAF, ua.zentix.incoming.client.fx.Fragments.Kind.BOARD}) {
                    double[] now = {0}, air = new double[3], v = new double[3], p = {r, 70, 0};
                    BlastGust g = BlastGust.of(d, new Vec3(r, 70, 0), () -> now[0]);
                    double vt = ua.zentix.incoming.client.fx.Fragments.terminal(kind, 600, 0.3) / 20, drag = ua.zentix.incoming.client.fx.Fragments.G / (vt * vt);
                    for (double t = Math.floor(g.arrival); t < g.arrival + 3 * g.phase; t++) {
                        now[0] = t;
                        g.at(p[0], p[1], p[2], air);
                        ua.zentix.incoming.client.fx.Fragments.step(v, air[0], ua.zentix.incoming.client.fx.Fragments.LIFT_MAX * Math.max(0, air[1]), air[2], drag);
                        for (int i = 0; i < 3; i++) p[i] += v[i];
                        double slant = Math.sqrt(p[0] * p[0] + (p[1] - 64) * (p[1] - 64) + p[2] * p[2]);
                        assertTrue(slant <= d.frontRadius(t + 1) + 1e-6, wave + " " + r + " " + kind + " at " + t + ": " + slant + " > " + d.frontRadius(t + 1));
                    }
                }
            }
        }
    }

    /** Путь воздуха не уходит дальше фронта в игре; пройденный путь не убывает; без часов подрыва ветра нет. */
    @Test
    void airPathBehindTheFront() {
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
        double[] into = new double[3];
        BlastGust.of(ground(1), new Vec3(1000, 65, 0), () -> Double.NaN).at(1000, 65, 0, into);
        assertEquals(0, into[1], 0, "подрыва у клиента нет — ветра нет");
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
