package ua.zentix.incoming.client.sound;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.FuelBurn;
import ua.zentix.incoming.nuclear.model.FuelPool;
import ua.zentix.incoming.sound.Propagation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Лучи преграды к пожарам ({@link FireRays}, {@link SoundFilters#reach}): налёт из 300 дальних пожаров стоил клиенту
 * треть тика — луч на каждый слышимый пожар в каждом тике, ~1800 шагов в мире каждый (0,85 → 17,9 мс, WAV-прогон Sol,
 * финал #456). Сцена того прогона: пожары по 2 МВт на кольце 1,7–1,9 км, ночь, ухо в центре.
 */
class FireRaysTest {
    private static final Vec3 EAR = new Vec3(0.5, 66.62, 0.5);
    /** Загруженные столбцы чанков клиента: прорисовка 12 чанков вокруг уха (с запасом движка в 3). */
    private static final SoundFilters.Loaded LOADED = (cx, cz) -> Math.max(Math.abs(cx), Math.abs(cz)) <= 15;

    /** {@code n} пожаров по 2 МВт на кольце 1,7–1,9 км, как в WAV-прогоне Sol, через 15 с после удара. */
    private static List<CraterFire> ring(int n) {
        FuelPool law = new FuelPool(2e6 * 150 / FuelPool.Fuel.PETROL.heatJPerKg, 2, 150, 2e6, FuelPool.Fuel.PETROL);
        List<CraterFire> fires = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n, r = 1700 + 200.0 * i / Math.max(1, n - 1);
            fires.add(new CraterFire(FuelBurn.at(law, BlockPos.containing(r * Math.cos(a), 65, r * Math.sin(a))), null, 300));
        }
        return fires;
    }

    /**
     * Лучей в мир за тик (всех: из тика пожара и из бюджета) — не больше {@link FireRays#PER_TICK} и не растёт с числом
     * пожаров: у 30, 300 и 3000 слышимых пожаров одно и то же; каждый пожар получает луч не позже {@link FireRays#lag}
     * тиков и дальше обновляется не реже; когда лучи есть у всех, в сумме — все пожары (ни один не выпал). Шагов в мире за
     * тик: лучи кончаются на последнем загруженном чанке — не больше, чем у {@code PER_TICK} лучей через загруженный
     * квадрат, против луча до каждого пожара прежде.
     */
    @Test
    void raysPerTickStayBoundedAsFiresGrow() {
        for (int n : new int[]{30, 300, 3000}) {
            List<CraterFire> fires = ring(n);
            FireRays rays = new FireRays();
            Map<Vec3, List<Integer>> rayed = new HashMap<>();
            int[] tick = {0}, opened = {0};
            double[] steps = {0};
            SoundFilters.Path path = new SoundFilters.Path() {
                @Override
                public float open(Vec3 from, Vec3 to) {
                    rayed.computeIfAbsent(to, k -> new ArrayList<>()).add(tick[0]);
                    opened[0]++;
                    steps[0] += SoundFilters.reach(from, to, LOADED) * from.distanceTo(to);
                    return 1;
                }

                @Override
                public float air(double distance) {
                    return 1;
                }
            };
            int lag = FireRays.lag(n), ticks = 3 * lag + 20;
            double before = 0, worst = 0;
            for (tick[0] = 0; tick[0] < ticks; tick[0]++) {
                Quarters q = new Quarters();
                double expected = 0;
                int heard = 0;
                steps[0] = 0;
                opened[0] = 0;
                for (CraterFire f : fires) {
                    assertTrue(f.hear(EAR, Propagation.Weather.NIGHT, path, q));
                    if (f.heard) heard++;
                }
                assertEquals(n, heard, n + " пожаров: все слышны (ночь, 2 МВт, ≤ 1,9 км)");
                int cast = rays.cast(fires, EAR, path);
                assertEquals(Math.min(n, FireRays.PER_TICK), cast, n + " пожаров, тик " + tick[0] + ": лучей");
                // все обращения к миру за тик — и из тика каждого пожара, и из бюджета
                assertEquals(cast, opened[0], n + " пожаров, тик " + tick[0] + ": лучей в мир");
                worst = Math.max(worst, steps[0]);
                if (tick[0] == 0) for (CraterFire f : fires) before += f.burnCentre().add(0, 1.5, 0).distanceTo(EAR);
                if (tick[0] > lag) {
                    // все пожары с лучом — в сумме все: мощность суммы = сумма мощностей
                    for (CraterFire f : fires) {
                        float v = CraterFire.volume(Sources.BLAST_FIRE, f.wattsNow(EAR), f.burnCentre(), EAR, Propagation.Weather.NIGHT);
                        expected += (double) v * v;
                    }
                    double got = 0;
                    for (int k = 0; k < Quarters.N; k++) got += q.volume(k) * q.volume(k);
                    assertEquals(expected, got, 1e-6 * expected, n + " пожаров: в сумме все");
                }
            }
            assertEquals(n, rayed.size(), n + " пожаров: луч у каждого");
            for (List<Integer> at : rayed.values()) {
                assertTrue(at.get(0) < lag, n + " пожаров: первый луч к тику " + at.get(0) + ", а не позже " + lag);
                for (int k = 1; k < at.size(); k++) assertTrue(at.get(k) - at.get(k - 1) <= lag, n + " пожаров: обновление реже " + lag + " тиков");
            }
            System.out.printf(java.util.Locale.ROOT, "%d far fires: rays per tick %d (before: %d), world steps per tick %.0f (before: %.0f), every fire refreshed within %d ticks%n",
                    n, Math.min(n, FireRays.PER_TICK), n, worst, before, lag);
            assertTrue(worst <= FireRays.PER_TICK * 17 * 16 * Math.sqrt(2), n + " пожаров: шагов в мире за тик " + worst);
        }
    }

    /**
     * Луч кончается на последнем загруженном столбце чанков по пути, и только там: перебор пути шагом в четверть блока
     * находит тот же последний загруженный столбец (в пределах столбца), и с загруженным чанком далеко за пустыми — луч
     * доходит до него; цель в загруженной области — луч до цели.
     */
    @Test
    void reachEndsAtTheLastLoadedChunk() {
        Random r = new Random(456);
        for (int i = 0; i < 2000; i++) {
            Vec3 from = new Vec3(r.nextDouble() * 64 - 32, 66, r.nextDouble() * 64 - 32);
            double a = r.nextDouble() * 2 * Math.PI, d = 5 + r.nextDouble() * 2500;
            Vec3 to = from.add(d * Math.cos(a), r.nextDouble() * 40 - 20, d * Math.sin(a));
            boolean island = i % 3 == 0;
            int ix = Math.floorDiv((int) Math.floor(from.x + (to.x - from.x) * 0.8), 16), iz = Math.floorDiv((int) Math.floor(from.z + (to.z - from.z) * 0.8), 16);
            SoundFilters.Loaded loaded = (cx, cz) -> LOADED.at(cx, cz) || island && cx == ix && cz == iz;
            double got = SoundFilters.reach(from, to, loaded), brute = 0, len = Math.hypot(to.x - from.x, to.z - from.z);
            for (double t = 0; t <= 1; t += 0.25 / Math.max(1, len)) {
                if (loaded.at(Math.floorDiv((int) Math.floor(from.x + (to.x - from.x) * t), 16), Math.floorDiv((int) Math.floor(from.z + (to.z - from.z) * t), 16))) brute = t;
            }
            assertTrue(got >= brute - 1e-9 && got <= brute + 23 / Math.max(1, len) + 1e-9, "луч " + i + ": до " + got + ", перебор " + brute);
            if (island) assertTrue(got >= 0.8 - 23 / len, "луч " + i + ": чанк за пустыми — луч до него");
        }
        assertEquals(1, SoundFilters.reach(EAR, EAR.add(100, 0, 50), LOADED), 1e-12, "цель в загруженной области — луч до цели");
        assertEquals(1, SoundFilters.reach(EAR, EAR.add(0, 30, 0), LOADED), 1e-12, "отвесно над ухом");
    }
}
