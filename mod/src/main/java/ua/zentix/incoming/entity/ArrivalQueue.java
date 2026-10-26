package ua.zentix.incoming.entity;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;

/**
 * Projectile positions from server to client, one per client tick. The server sends a position each server tick,
 * but packets can arrive in either of two adjacent client ticks (the server falls behind and catches up;
 * Flashback recordings store them by client tick too). Taking “the last packet per tick” makes the projectile
 * stand still for a tick then jump two steps, jerking the tracking camera along with it (for example, a Shahed
 * stutters in a chase shot).
 * <p>
 * The queue yields one position per tick. When empty, predict from the last received position using its step per
 * server tick (a borrowed tick, at most {@link #MAX_DEBT} ticks ahead; then stop). Positions received later for
 * borrowed ticks are not queued because prediction already showed them, but prediction starts from them: the
 * projectile stays within {@link #MAX_DEBT} steps of the server and follows its path even below 20 server ticks/s
 * (fewer packets than client ticks, so debt never clears), rather than a straight line. If the queue exceeds
 * {@link #MAX_QUEUE}, skip older entries: the projectile stays at most one tick behind the server. A position
 * farther than {@link #JUMP} from the previous one (materialization, teleport) applies immediately and clears the
 * queue and debt.
 */
public final class ArrivalQueue {
    static final int MAX_QUEUE = 2, MAX_DEBT = 2;
    static final double JUMP = 64;

    /** Position and rotation: x, y, z, yaw, pitch. */
    public record Sample(double x, double y, double z, float yRot, float xRot) {}

    private final ArrayDeque<Sample> queue = new ArrayDeque<>();
    /** Ticks shown by prediction beyond the last received position. */
    private int debt;
    /** Last received position and the one before it: their difference is the prediction step. */
    @Nullable
    private Sample last, before;
    /** Last tick's step, blocks: prediction while only one position has arrived. */
    private double stepX, stepY, stepZ;

    /** A position packet arrived. */
    public void add(Sample s) {
        if (last != null && Math.abs(s.x() - last.x()) + Math.abs(s.y() - last.y()) + Math.abs(s.z() - last.z()) > JUMP) {
            queue.clear();
            debt = 0;
            stepX = stepY = stepZ = 0;
            last = null;
        }
        before = last;
        last = s;
        if (debt > 0) {
            // Prediction has already shown this position.
            debt--;
            return;
        }
        queue.add(s);
        while (queue.size() > MAX_QUEUE) queue.poll();
    }

    /**
     * Where the projectile goes this client tick from {@code x, y, z}: a queued position, prediction from the
     * last received position (borrowed tick), or null to stay still.
     */
    @Nullable
    public Sample next(double x, double y, double z, float yRot, float xRot) {
        Sample s = queue.poll();
        if (s != null) {
            // After a jump (materialization), prediction stays still until a second position arrives.
            boolean jump = Math.abs(s.x() - x) + Math.abs(s.y() - y) + Math.abs(s.z() - z) > JUMP;
            stepX = jump ? 0 : s.x() - x;
            stepY = jump ? 0 : s.y() - y;
            stepZ = jump ? 0 : s.z() - z;
            return s;
        }
        if (last == null || debt >= MAX_DEBT) return null;
        debt++;
        if (before != null) {
            stepX = last.x() - before.x();
            stepY = last.y() - before.y();
            stepZ = last.z() - before.z();
        }
        return new Sample(last.x() + stepX * debt, last.y() + stepY * debt, last.z() + stepZ * debt, last.yRot(), last.xRot());
    }

    /** Last received position (where the projectile is heading); null if none arrived. */
    @Nullable
    public Sample latest() {
        return last;
    }
}
