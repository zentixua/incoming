package ua.zentix.incoming.client.sound;

import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Лучи преграды от уха к пожарам в воронках — не больше {@link #PER_TICK} за тик, сколько бы пожаров ни горело (без
 * Minecraft, проверяется юнит-тестом). Луч на каждый слышимый пожар в каждом тике стоил налёту из 300 пожаров в 1,7–1,9 км
 * 300 лучей по ~1800 блоков пути в тик; тик клиента в сцене Sol после #456 — 0,85 → 17,9 мс. Теперь каждый пожар помнит
 * открытость своего последнего луча; в тике лучи достаются слышимым пожарам без луча (по одному на сектор, пока такие
 * есть), потом самым давним. Сам луч кончается на последнем загруженном чанке ({@link SoundFilters#reach}): дальше мир
 * клиента пуст.
 * <p>
 * Бюджет решает только, когда уточняется преграда, а не слышен ли пожар: слышимый пожар идёт в сумму в тот же тик
 * ({@link #tick}), до своего луча — с открытостью, известной в его стороне ({@link #known}). Цена бюджета — запаздывание
 * преграды: луч к каждому пожару обновляется раз в {@link #lag} тиков (300 пожаров — до 19 тиков, 0,95 с; 3000 — до 188,
 * 9,4 с), и пожар, за которым стена встала или ушла (игрок шагнул за угол), до своего луча звучит по-прежнему. Петля этого
 * не скрывает: её громкость идёт к цели на 0,05 за тик ({@code AmbientLoop}), шаг меньше — за один тик.
 */
final class FireRays {
    /** Лучей за тик (бюджет чтений мира: 300 пожаров — круг за ~1 с). */
    static final int PER_TICK = 16;
    /**
     * Секторов направления вокруг уха для открытости, известной в стороне пожара: столько же, сколько лучей за тик, —
     * в первый тик налёта по лучу в каждый сектор, где есть пожары.
     */
    static final int SECTORS = PER_TICK;
    /** Поздние первыми: очередь держит самые давние — по тику последнего луча, без луча — по очереди в своём секторе. */
    private static final Comparator<CraterFire> LATEST_FIRST = Comparator.comparingLong((CraterFire f) -> f.rayAt).thenComparingInt(f -> f.turn).reversed();

    private long tick;

    /**
     * Тик после {@link CraterFire#hear} у всех: лучи ({@link #cast}), потом в {@code loops} — каждый слышимый пожар: через
     * свой последний луч, без луча — через открытость, известную в его стороне ({@link #known}). Сколько лучей.
     */
    int tick(List<CraterFire> fires, Vec3 ear, SoundFilters.Path path, Quarters loops) {
        int cast = cast(fires, ear, path);
        float[] known = known(fires);
        for (CraterFire f : fires) f.mix(loops, path, f.rayed() ? f.open : known[sector(f.azimuth)]);
        return cast;
    }

    /**
     * Лучи из уха {@code ear} слышимым пожарам ({@link CraterFire#heard}) — сперва тем, у кого луча не было (первыми — по
     * одному из каждого сектора), потом самым давним, не больше {@link #PER_TICK}. Сколько лучей.
     */
    int cast(List<CraterFire> fires, Vec3 ear, SoundFilters.Path path) {
        tick++;
        int[] line = new int[SECTORS];
        PriorityQueue<CraterFire> oldest = new PriorityQueue<>(PER_TICK + 1, LATEST_FIRST);
        for (CraterFire f : fires) {
            if (!f.heard) continue;
            f.turn = f.rayed() ? 0 : line[sector(f.azimuth)]++;
            oldest.add(f);
            if (oldest.size() > PER_TICK) oldest.poll();
        }
        for (CraterFire f : oldest) f.ray(ear, path, tick);
        return oldest.size();
    }

    /**
     * Открытость, известная в каждом секторе: средняя по последним лучам слышимых пожаров сектора, в секторе без них — у
     * ближайшего сектора с ними. Преграду решает путь у уха (стены дома, гребень, лес), а дальше загруженного мира пусто:
     * пожары в одной стороне на одной дальности проходят одни и те же блоки у уха; ⚠ пожар много ближе или дальше соседей
     * сектора может стоять по другую сторону преграды — это оценка до его луча, в пределах {@link #lag} тиков. После
     * {@link #cast} луч есть хоть у одного слышимого пожара, если слышен хоть один.
     */
    static float[] known(List<CraterFire> fires) {
        float[] sum = new float[SECTORS], known = new float[SECTORS];
        int[] n = new int[SECTORS];
        for (CraterFire f : fires) {
            if (!f.heard || !f.rayed()) continue;
            int s = sector(f.azimuth);
            sum[s] += f.open;
            n[s]++;
        }
        for (int s = 0; s < SECTORS; s++) {
            known[s] = Float.NaN;
            for (int d = 0; d <= SECTORS / 2 && Float.isNaN(known[s]); d++) {
                int a = Math.floorMod(s - d, SECTORS), b = Math.floorMod(s + d, SECTORS);
                if (n[a] + n[b] > 0) known[s] = (sum[a] + (a != b ? sum[b] : 0)) / (n[a] + (a != b ? n[b] : 0));
            }
        }
        return known;
    }

    /** Сектор направления {@code azimuth} (atan2(dz, dx)). */
    static int sector(double azimuth) {
        return Math.floorMod((int) Math.floor(azimuth / (2 * Math.PI) * SECTORS), SECTORS);
    }

    /** Тиков, за которые луч доходит до каждого из {@code fires} слышимых пожаров (и обновляется у каждого). */
    static int lag(int fires) {
        return Math.max(1, (fires + PER_TICK - 1) / PER_TICK);
    }
}
