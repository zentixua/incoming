package ua.zentix.incoming.net;

import ua.zentix.incoming.Incoming;

import java.util.Locale;

/**
 * Per-batch timing of Voxy LOD delivery ({@code compat/VoxyLods}, {@code client/ClientLodBatches}), off by default:
 * {@code -Dincoming.lodDiag=true}. Lines start with {@code LODDIAG} and carry wall-clock milliseconds, so server and
 * client logs on one machine join by time.
 */
public final class LodDiag {
    public static final boolean ON = Boolean.getBoolean("incoming.lodDiag");

    private LodDiag() {}

    public static long now() {
        return System.currentTimeMillis();
    }

    public static void log(String format, Object... args) {
        if (ON) Incoming.LOG.info("LODDIAG t={} " + String.format(Locale.ROOT, format, args), now());
    }
}
