package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import ua.zentix.incoming.warhead.Warheads;

import java.util.ArrayList;
import java.util.List;

/**
 * Подрывы боевых частей по заряду ({@code ExplosionEvent.Start} с зарядом, {@link Warheads#chargeOf}) за весь прогон
 * GameTest: тест полёта проверяет, что снаряд взорвался у цели, не завязываясь на размер воронки (она — по закону
 * заряда и грунта, у шахеда на камне её нет). Слушатель ставит {@link GameTestShards}.
 */
public final class BlastLog {
    record Blast(Level level, Vec3 at, double charge, long gameTime) {}

    private static final List<Blast> BLASTS = new ArrayList<>();

    private BlastLog() {}

    static void onStart(ExplosionEvent.Start e) {
        double charge = Warheads.chargeOf(e.getExplosion());
        if (charge <= 0 || e.isCanceled() || e.getLevel().isClientSide) return;
        synchronized (BLASTS) {
            BLASTS.add(new Blast(e.getLevel(), e.getExplosion().center(), charge, e.getLevel().getGameTime()));
        }
    }

    /** Был ли подрыв по заряду не дальше {@code radius} от {@code at} в мире теста. */
    static boolean near(GameTestHelper h, BlockPos at, double radius) {
        Vec3 c = Vec3.atCenterOf(h.absolutePos(at));
        synchronized (BLASTS) {
            for (Blast b : BLASTS) if (b.level() == h.getLevel() && b.at().distanceTo(c) <= radius) return true;
        }
        return false;
    }
}
