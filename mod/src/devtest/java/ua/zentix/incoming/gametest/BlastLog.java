package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.warhead.Warheads;

import java.util.ArrayList;
import java.util.List;

/**
 * Подрывы боевых частей по заряду ({@code ExplosionEvent.Start} с зарядом, {@link Warheads#chargeOf}) за весь прогон
 * GameTest: тест полёта проверяет, что снаряд взорвался у цели, не завязываясь на размер воронки (она — по закону
 * заряда и грунта, у шахеда на камне её нет), и что источник взрыва — сам снаряд. Слушатель ставит {@link GameTestShards}.
 */
public final class BlastLog {
    /** {@code source} — тип сущности-источника взрыва ({@code getDirectSourceEntity}), null — без источника. */
    record Blast(Level level, Vec3 at, double charge, long gameTime, @Nullable EntityType<?> source) {}

    private static final List<Blast> BLASTS = new ArrayList<>();

    private BlastLog() {}

    static void onStart(ExplosionEvent.Start e) {
        double charge = Warheads.chargeOf(e.getExplosion());
        if (charge <= 0 || e.isCanceled() || e.getLevel().isClientSide) return;
        synchronized (BLASTS) {
            Entity source = e.getExplosion().getDirectSourceEntity();
            BLASTS.add(new Blast(e.getLevel(), e.getExplosion().center(), charge, e.getLevel().getGameTime(),
                    source == null ? null : source.getType()));
        }
    }

    /** Был ли с начала теста подрыв по заряду не дальше {@code radius} от {@code at} в мире теста. */
    static boolean near(GameTestHelper h, BlockPos at, double radius) {
        return !around(h, at, radius).isEmpty();
    }

    /** Подрывы по заряду с начала теста не дальше {@code radius} от {@code at} в мире теста. */
    static List<Blast> around(GameTestHelper h, BlockPos at, double radius) {
        Vec3 c = Vec3.atCenterOf(h.absolutePos(at));
        long since = h.getLevel().getGameTime() - h.getTick();
        List<Blast> out = new ArrayList<>();
        synchronized (BLASTS) {
            for (Blast b : BLASTS) if (b.level() == h.getLevel() && b.gameTime() >= since && b.at().distanceTo(c) <= radius) out.add(b);
        }
        return out;
    }
}
