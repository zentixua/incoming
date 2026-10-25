package ua.zentix.incoming.client.sound;

import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Лучи преграды от уха к пожарам в воронках — не больше {@link #PER_TICK} за тик, сколько бы пожаров ни горело (без
 * Minecraft, проверяется юнит-тестом). Луч на каждый слышимый пожар в каждом тике стоил налёту из 300 пожаров в 1,7–1,9 км
 * треть тика клиента: 0,85 → 17,9 мс (WAV-прогон Sol, финал #456) — 300 лучей по ~1800 шагов в мире. Теперь каждый пожар
 * помнит открытость своего последнего луча; в тике лучи достаются слышимым пожарам, у которых луча ещё не было, потом —
 * самым давним. Пожар и ухо за тик сдвигаются на доли блока, а громкость петли пожара и так подходит к цели за секунду
 * ({@code AmbientLoop}, 0,05 за тик): пока пожаров не больше {@code 20 · PER_TICK}, луч к каждому обновляется не реже
 * раза в секунду — быстрее петля бы не отозвалась. Сам луч кончается на последнем загруженном чанке
 * ({@link SoundFilters#reach}): дальше мир клиента пуст.
 */
final class FireRays {
    /** Лучей за тик. */
    static final int PER_TICK = 16;

    private long tick;

    /**
     * Тик: лучи из уха {@code ear} слышимым пожарам ({@link CraterFire#heard}) — сперва тем, у кого луча не было, потом
     * самым давним, не больше {@link #PER_TICK}. Сколько лучей.
     */
    int cast(List<CraterFire> fires, Vec3 ear, SoundFilters.Path path) {
        tick++;
        PriorityQueue<CraterFire> oldest = new PriorityQueue<>(PER_TICK + 1, Comparator.comparingLong((CraterFire f) -> f.rayAt).reversed());
        for (CraterFire f : fires) {
            if (!f.heard) continue;
            oldest.add(f);
            if (oldest.size() > PER_TICK) oldest.poll();
        }
        for (CraterFire f : oldest) f.ray(ear, path, tick);
        return oldest.size();
    }

    /** Тиков, за которые луч доходит до каждого из {@code fires} слышимых пожаров (и обновляется у каждого). */
    static int lag(int fires) {
        return Math.max(1, (fires + PER_TICK - 1) / PER_TICK);
    }
}
