package ua.zentix.incoming.client.fx.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.RandomSource;
import org.joml.FrustumIntersection;
import ua.zentix.incoming.client.fx.layer.FxFrame;
import ua.zentix.incoming.client.fx.layer.FxQuads;

import java.util.Arrays;

/**
 * All live effects particles: custom pool instead of the vanilla particle engine. Drawn in one post-world pass together
 * with distant visuals ({@link ua.zentix.incoming.client.fx.layer.FxLayer}): sorted far to near,
 * with soft edges against ground and Distant Horizons terrain. The vanilla engine has one unsorted queue per layer (later
 * births draw on top), and its limit evicts old clouds.
 * <p>
 * Slots by {@link FxBudget} groups: full groups reject new particles. Particle objects are reused;
 * birth and death allocate nothing. Ticked by the game thread (unpaused, world unfrozen), drawn by the render thread
 * (the same thread).
 */
public final class FxPool {
    public static final FxPool INSTANCE = new FxPool();

    final RandomSource random = RandomSource.create();
    private final FxParticle[] parts = new FxParticle[FxBudget.total()];
    /** Live particles — {@code parts[0..count)}; following objects reserved for new ones. */
    private int count;
    /** Live count after previous tick: later births wait for the next tick, like the vanilla engine. */
    private int settled;
    private final int[] live = new int[FxBudget.values().length];
    private final long[] refused = new long[FxBudget.values().length];
    /** Particle world: changes (another dimension) remove old particles, like the vanilla engine. */
    private ClientLevel level;

    private FxPool() {}

    /**
     * New configured particle at (x, y, z), sizes multiplied (spark tail thins toward its end). Full group —
     * no spawn (counted).
     */
    void spawn(Fx.Spec spec, ClientLevel level, double x, double y, double z, float sizeScale0, float sizeScale1) {
        int g = spec.budget.ordinal();
        if (live[g] >= spec.budget.limit) {
            refused[g]++;
            return;
        }
        FxParticle p = parts[count];
        if (p == null) parts[count] = p = new FxParticle();
        p.init(spec, x, y, z, sizeScale0, sizeScale1, random, level);
        count++;
        live[g]++;
    }

    /**
     * Particle tick (after world tick, like vanilla; neither paused nor frozen). Births after the previous
     * tick — from entity ticks, effects, spark tails — append at the end and tick from the next tick, like vanilla;
     * dead particles move past live ones in a single pass, preserving live order.
     */
    public void tick(ClientLevel level) {
        if (level != this.level) {
            clear();
            this.level = level;
            return;
        }
        int end = settled;
        for (int i = 0; i < end; i++) parts[i].tick(level, this);
        int w = 0;
        for (int i = 0; i < count; i++) {
            FxParticle p = parts[i];
            if (p.dead) {
                live[p.budget.ordinal()]--;
                continue;
            }
            if (w != i) {
                parts[i] = parts[w];
                parts[w] = p;
            }
            w++;
        }
        count = w;
        settled = w;
    }

    /** Visible particles into the shared layer frame. */
    public void collect(FxFrame frame, FxQuads out, float partial) {
        FrustumIntersection frustum = frame.frustum;
        for (int i = 0; i < count; i++) {
            FxParticle p = parts[i];
            float x = (float) (p.x - frame.cam.x), y = (float) (p.y - frame.cam.y), z = (float) (p.z - frame.cam.z);
            if (!frustum.testSphere(x, y, z, (float) p.reach())) continue;
            p.emit(frame, out, partial);
        }
    }

    /** World exit, dimension change, scenarios. */
    public void clear() {
        level = null;
        count = settled = 0;
        Arrays.fill(live, 0);
    }

    public int count() {
        return count;
    }

    /** Live particles per group. */
    public int live(FxBudget b) {
        return live[b.ordinal()];
    }

    /** Particles denied group slots since game launch. */
    public long refused(FxBudget b) {
        return refused[b.ordinal()];
    }

    /** Live particle count within {@code radius} of a point and cone {@code cos} around a direction (for scenarios). */
    public int near(double ex, double ey, double ez, double lx, double ly, double lz, double radius, double cos) {
        int n = 0;
        for (int i = 0; i < count; i++) {
            FxParticle p = parts[i];
            double dx = p.x - ex, dy = p.y - ey, dz = p.z - ez, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < radius && dx * lx + dy * ly + dz * lz > len * cos) n++;
        }
        return n;
    }
}
