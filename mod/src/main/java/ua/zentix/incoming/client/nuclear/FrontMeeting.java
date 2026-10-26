package ua.zentix.incoming.client.nuclear;

/**
 * When the detonation front meets the listener and player body — without Minecraft (unit-tested). The camera hears sound
 * (the engine listener is there), while the body receives the ear impact and wind push: the projectile camera orbits the blast site at 32–42 blocks,
 * while the player stands kilometers away. A camera on the player (first- and third-person views) or nearby (closer to or farther from the blast
 * by no more than the front travels in a tick) meets the front with the body, based on the body: wave sounds and ear impact occur in one
 * tick (wave sounds remain audible as before it, {@link Deafness#before}). The front meets the listener and body only if it passed
 * them this tick: it did not meet those teleported behind it (camera returning to a body the front has passed, impact orbit after
 * the front has left there). A listener ahead of the front again (camera returned to the player, teleport) meets
 * it again; the body only once.
 */
final class FrontMeeting {
    /**
     * A listener more than this many blocks ahead of the front will meet it again: right at the boundary (camera flying alongside
     * the front), the hit does not repeat every few ticks.
     */
    static final double AHEAD = 64;
    /** A shift of more than this many blocks in a tick is a teleport, not movement: the front has not passed someone teleported behind it. */
    static final double JUMP = 64;
    /** What happened this tick: previous listener meeting cleared, front at listener, front at body. */
    static final int AGAIN = 1, HEARD = 2, HIT = 4;

    private boolean heard, hit;
    /** Front radius last tick; before the first — negative infinity (a listener inside the ball hears it immediately). */
    private double last = Double.NEGATIVE_INFINITY;
    /** Where the listener and body were last tick (from the detonation); NaN — absent. */
    private double lastAt = Double.NaN, lastBody = Double.NaN;

    /**
     * Tick: front, listener and body positions.
     *
     * @param front    front radius, blocks
     * @param ear      distance from detonation to camera, blocks
     * @param body     distance from detonation to player eyes, blocks; NaN — no player
     * @param attached camera is on the player
     * @return what happened ({@link #AGAIN}, {@link #HEARD}, {@link #HIT}), in that order
     */
    int tick(double front, double ear, double body, boolean attached) {
        double step = Double.isInfinite(last) ? 0 : front - last;
        double at = !Double.isNaN(body) && (attached || Math.abs(ear - body) <= step) ? body : ear;
        int out = 0;
        if (heard && front < at - AHEAD) {
            heard = false;
            out |= AGAIN;
        }
        if (!heard && front >= at) {
            heard = true;
            if (passed(at, lastAt)) out |= HEARD;
        }
        if (!hit && body <= front && passed(body, lastBody)) {
            hit = true;
            out |= HIT;
        }
        last = front;
        lastAt = at;
        lastBody = body;
        return out;
    }

    /**
     * The front passed the point this tick: it was short of the point's current position, or the point was ahead of the front and moving toward the blast (so it
     * crosses the front between ticks). A shift beyond {@link #JUMP} in a tick is not movement.
     */
    private boolean passed(double now, double before) {
        return last < now || last < before && Math.abs(now - before) <= JUMP;
    }

    /** The front is already at the listener. */
    boolean heard() {
        return heard;
    }
}
