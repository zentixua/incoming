package ua.zentix.incoming.nuclear.world;

/** Admission feedback, separate from the work clock that bounds each actual operation. */
final class SettleWindow {
    private int target = 8;

    int limit(long room, int height) {
        // Reserve two uncompressed chunk snapshots per root in addition to already retained data.
        // Adjacent roots share their window; HeapWatch also guards vanilla chunks and pending saves.
        long perRoot = Math.max(1L << 20, (long) height * 256 * 4 * 2);
        int memoryLimit = (int) Math.min(RuinSettle.PENDING, Math.max(0, room) / perRoot);
        target = Math.min(target, Math.max(1, memoryLimit));
        return Math.min(target, memoryLimit);
    }

    void adapt(double externalMs, double workBudgetMs, boolean spare, boolean memory) {
        double room = 50 - workBudgetMs;
        if (!memory || externalMs > room + RuinWorkers.MARGIN_MS || !spare) {
            target = Math.max(1, target - Math.max(1, target / 4));
        } else if (externalMs < room - RuinWorkers.MARGIN_MS) {
            target = Math.min(RuinSettle.PENDING, target + Math.max(1, target / 4));
        }
    }
}
