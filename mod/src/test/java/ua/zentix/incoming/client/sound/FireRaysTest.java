package ua.zentix.incoming.client.sound;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.FuelBurn;
import ua.zentix.incoming.nuclear.model.FuelPool;
import ua.zentix.incoming.sound.Propagation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Лучи преграды к пожарам ({@link FireRays}, {@link SoundFilters#reach}): налёт из 300 дальних пожаров стоил клиенту
 * треть тика — луч на каждый слышимый пожар в каждом тике, ~1800 блоков пути каждый (0,85 → 17,9 мс, WAV-прогон Sol,
 * финал #456). Сцена того прогона: пожары по 2 МВт на кольце 1,7–1,9 км, ночь, ухо в центре.
 */
class FireRaysTest {
    private static final Vec3 EAR = new Vec3(0.5, 66.62, 0.5);
    /** Загруженные столбцы чанков клиента: прорисовка 12 чанков вокруг уха (с запасом движка в 3). */
    private static final SoundFilters.Loaded LOADED = (cx, cz) -> Math.max(Math.abs(cx), Math.abs(cz)) <= 15;

    /**
     * {@code n} пожаров по 2 МВт на кольце от {@code near} до {@code far} м (углы — посередине между соседними: ни один не
     * стоит ровно на границе секторов), через 15 с после удара.
     */
    private static List<CraterFire> ring(int n, double near, double far) {
        FuelPool law = new FuelPool(2e6 * 150 / FuelPool.Fuel.PETROL.heatJPerKg, 2, 150, 2e6, FuelPool.Fuel.PETROL);
        List<CraterFire> fires = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * (i + 0.5) / n, r = near + (far - near) * i / Math.max(1, n - 1);
            fires.add(new CraterFire(FuelBurn.at(law, BlockPos.containing(0.5 + r * Math.cos(a), 65, 0.5 + r * Math.sin(a))), null, 300));
        }
        return fires;
    }

    /** Мир для лучей: открытость пути к точке — {@code open}; считает лучи, проверки чанков и чтения блока и жидкости. */
    private static final class World implements SoundFilters.Path {
        final ToDoubleFunction<Vec3> open;
        final Map<Vec3, List<Integer>> rayed = new HashMap<>();
        int tick, rays;
        long chunks, reads;

        World(ToDoubleFunction<Vec3> open) {
            this.open = open;
        }

        @Override
        public float open(Vec3 from, Vec3 to) {
            rayed.computeIfAbsent(to, k -> new ArrayList<>()).add(tick);
            rays++;
            reads += reads(from, from.add(to.subtract(from).scale(SoundFilters.reach(from, to, (cx, cz) -> {
                chunks++;
                return LOADED.at(cx, cz);
            }))));
            return (float) open.applyAsDouble(to);
        }

        @Override
        public float air(double distance) {
            return 1;
        }

        /** Чтений мира у луча {@code from → to} пустым путём: {@code Level.clip} читает в каждой клетке блок и жидкость. */
        static long reads(Vec3 from, Vec3 to) {
            long[] n = {0};
            BlockGetter.traverseBlocks(from, to, null, (c, pos) -> {
                n[0] += 2;
                return null;
            }, c -> null);
            return n[0];
        }
    }

    /** Мощность суммы: столько, сколько дают все слышимые пожары через настоящую открытость своего пути (до потолка). */
    private static double law(List<CraterFire> fires, World world) {
        double p = 0;
        for (CraterFire f : fires) {
            double v = CraterFire.volume(Sources.BLAST_FIRE, f.wattsNow(EAR), f.burnCentre(), EAR, Propagation.Weather.NIGHT);
            double g = SoundFilters.blockedGain((float) world.open.applyAsDouble(f.burnCentre().add(0, 1.5, 0)));
            p += v * v * g * g;
        }
        return Math.min(1, p);
    }

    private static double power(Quarters q) {
        double p = 0;
        for (int k = 0; k < Quarters.N; k++) p += q.volume(k) * q.volume(k);
        return p;
    }

    /**
     * Лучей в мир за тик (все обращения пути: и из тика пожара, и из бюджета) — не больше {@link FireRays#PER_TICK} и не
     * растёт с числом пожаров: у 30, 300 и 3000 слышимых пожаров одно и то же; каждый пожар получает луч не позже
     * {@link FireRays#lag} тиков и дальше обновляется не реже. Чтений мира за тик (блок и жидкость в каждой клетке луча,
     * как у {@code Level.clip}, и проверки загруженных столбцов): лучи кончаются на последнем загруженном чанке — их не
     * больше, чем у {@code PER_TICK} лучей через загруженный квадрат, против луча до каждого пожара прежде.
     */
    @Test
    void raysPerTickStayBoundedAsFiresGrow() {
        for (int n : new int[]{30, 300, 3000}) {
            List<CraterFire> fires = ring(n, 1700, 1900);
            FireRays rays = new FireRays();
            World world = new World(to -> 1);
            int lag = FireRays.lag(n), ticks = 3 * lag + 20;
            long before = 0, worstReads = 0, worstChunks = 0;
            for (world.tick = 0; world.tick < ticks; world.tick++) {
                Quarters q = new Quarters();
                int heard = 0;
                for (CraterFire f : fires) {
                    assertTrue(f.hear(EAR, Propagation.Weather.NIGHT));
                    if (f.heard) heard++;
                }
                assertEquals(n, heard, n + " пожаров: все слышны (ночь, 2 МВт, ≤ 1,9 км)");
                if (world.tick == 0) for (CraterFire f : fires) before += World.reads(EAR, f.burnCentre().add(0, 1.5, 0));
                world.rays = 0;
                world.chunks = world.reads = 0;
                int cast = rays.tick(fires, EAR, world, q);
                assertEquals(Math.min(n, FireRays.PER_TICK), cast, n + " пожаров, тик " + world.tick + ": лучей");
                assertEquals(cast, world.rays, n + " пожаров, тик " + world.tick + ": лучей в мир");
                worstReads = Math.max(worstReads, world.reads);
                worstChunks = Math.max(worstChunks, world.chunks);
            }
            assertEquals(n, world.rayed.size(), n + " пожаров: луч у каждого");
            for (List<Integer> at : world.rayed.values()) {
                assertTrue(at.get(0) < lag, n + " пожаров: первый луч к тику " + at.get(0) + ", а не позже " + lag);
                for (int k = 1; k < at.size(); k++) assertTrue(at.get(k) - at.get(k - 1) <= lag, n + " пожаров: обновление реже " + lag + " тиков");
            }
            System.out.printf(Locale.ROOT, "%d far fires: rays per tick %d (before: %d), block+fluid reads per tick %d (before: %d), chunk checks %d, every fire refreshed within %d ticks%n",
                    n, Math.min(n, FireRays.PER_TICK), n, worstReads, before, worstChunks, lag);
            // луч через загруженный квадрат 31×31 столбцов: не больше 2 × (|dx| + |dy| + |dz| + 1) клеток
            assertTrue(worstReads <= FireRays.PER_TICK * 2 * (3 * 31 * 16 + 1), n + " пожаров: чтений мира за тик " + worstReads);
        }
    }

    /**
     * Слышимый пожар — в сумме с первого тика, а не с первого луча (ревью R12: до луча пожар молчал, и 300 новых пожаров
     * набирали мощность 19 тиков — 0 % в первый тик, 5 % во второй). Бюджет уточняет преграду, но не решает, звучит ли
     * пожар: до своего луча пожар идёт через открытость, известную в его стороне. Мощность суммы с тика 0 и в каждом тике
     * до конца очереди — как у всех пожаров через их настоящий путь: всё открыто (сцена ревьюера: 300 пожаров в 40 м), всё
     * за стеной, и стена на половину круга (восток: путь закрыт, где x растёт) — первые 16 лучей идут по одному в каждый
     * сектор, и в каждом секторе открытость уже известна.
     */
    @Test
    void heardFiresJoinTheMixOnTheirFirstTick() {
        Map<String, ToDoubleFunction<Vec3>> worlds = Map.of(
                "всё открыто", to -> 1,
                "всё за стеной", to -> 0,
                "стена на востоке", to -> to.x > EAR.x ? 0 : 1);
        for (var w : worlds.entrySet()) {
            boolean wall = w.getKey().equals("стена на востоке");
            for (double[] r : new double[][]{{40, 40}, {1700, 1900}}) {
                // пожар ровно на границе секторов (у 30 на кольце — на 270°) или в 40 м в клетке у самой стены (x = x уха)
                // стоит по другую сторону стены от своего сектора: стена на половину круга — вдали и без таких
                if (wall && r[0] == 40) continue;
                for (int n : r[0] == 40 ? new int[]{1, 300} : wall ? new int[]{32, 300, 3000} : new int[]{1, 32, 300, 3000}) {
                    List<CraterFire> fires = ring(n, r[0], r[1]);
                    FireRays rays = new FireRays();
                    World world = new World(w.getValue());
                    for (world.tick = 0; world.tick <= FireRays.lag(n) + 1; world.tick++) {
                        Quarters q = new Quarters();
                        for (CraterFire f : fires) f.hear(EAR, Propagation.Weather.NIGHT);
                        rays.tick(fires, EAR, world, q);
                        double want = law(fires, world), got = power(q);
                        assertEquals(want, got, 1e-6 * want, String.format(Locale.ROOT, "%s, %d пожаров в %.0f–%.0f м, тик %d: мощность суммы %.8f из %.8f",
                                w.getKey(), n, r[0], r[1], world.tick, got, want));
                    }
                }
            }
        }
    }

    /**
     * Открытость в стороне пожара без луча: средняя по лучам слышимых пожаров его сектора, в пустом секторе — ближайшего
     * сектора с лучами (два на равном расстоянии — средняя обоих).
     */
    @Test
    void knownOpennessIsTheNearestSectorWithRays() {
        List<CraterFire> fires = ring(FireRays.SECTORS * 2, 100, 100);
        for (CraterFire f : fires) f.hear(EAR, Propagation.Weather.NIGHT);
        // лучи — только у двух пожаров сектора 0 (один открыт, другой закрыт) и у двух в секторе 8 (закрыты)
        for (CraterFire f : fires) {
            int s = FireRays.sector(f.azimuth);
            if (s == 0 || s == 8) f.ray(EAR, new World(to -> FireRays.sector(Math.atan2(to.z - EAR.z, to.x - EAR.x)) == 0 && to.z > EAR.z + 20 ? 1 : 0), 1);
        }
        float[] known = FireRays.known(fires);
        assertEquals(0.5f, known[0], 1e-6, "сектор 0: средняя его лучей");
        assertEquals(0f, known[8], 1e-6, "сектор 8: его луч");
        assertEquals(0.5f, known[3], 1e-6, "сектор 3: ближе к 0");
        assertEquals(0f, known[6], 1e-6, "сектор 6: ближе к 8");
        assertEquals(0.25f, known[4], 1e-6, "сектор 4: 0 и 8 на равном — средняя обоих");
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
