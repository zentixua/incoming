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
 * Explosions of warheads by charge ({@code ExplosionEvent.Start} with charge, {@link Warheads#chargeOf}) for the entire run
 * GameTest: the flight test checks that the projectile exploded at the target, without being tied to the size of the crater (it is according to the law of
 *  charge and soil, Shahed does not have it on the stone), and that the source explosion - the projectile itself. The listener puts {@link GameTestShards}.
 */
public final class BlastLog {
    /** {@code source} - type of explosion source entity ({@code getDirectSourceEntity}), null - without source. */
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

    /** Since the beginning of the test, has there been a charge explosion no further than {@code radius} from {@code at} in the world of the test. */
    static boolean near(GameTestHelper h, BlockPos at, double radius) {
        return !around(h, at, radius).isEmpty();
    }

    /** Charge explosions from the beginning of the test are no further than {@code radius} from {@code at} in the world of the test. */
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
