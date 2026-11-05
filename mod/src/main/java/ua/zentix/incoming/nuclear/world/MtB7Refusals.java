package ua.zentix.incoming.nuclear.world;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import ua.zentix.incoming.registry.ModAttachments;

/** TEST ONLY. Private A/B instrumentation; never include this branch in a player artifact.
 * Server-thread numeric state belongs to the selected world's existing pending observer. */
final class MtB7Refusals {
    static final boolean ENABLED = Boolean.getBoolean("incoming.mtB7") && Boolean.getBoolean("incoming.mtB7.refusals");
    private static final String DIMENSION = System.getProperty("incoming.mtB7.pendingDimension", "minecraft:overworld");
    static final int CHUNK_LIMIT = 2048;
    static final long INTERVAL = 200;
    enum Reason { MEMORY, WRITING, LOADING, STALE, WORKER, READ, ADMIT, SUBMIT, PLAN_WAIT, REPLAN }
    static final class Stamp {
        final long[] counts = new long[Reason.values().length];
        final long[] last = new long[Reason.values().length];
        Stamp() { Arrays.fill(last, Long.MIN_VALUE); }
    }
    private final Map<Long, Stamp> chunks = new HashMap<>();
    private final int selected;
    private boolean active;
    private long chunk, tick, lag, overflow;
    private final long[] ownership = new long[9];
    private final long[] material = new long[13];
    private long ownershipAt;
    boolean coldMemory;
    private int planCells, planMaterial;

    MtB7Refusals(int event) { selected = event; }

    static long cap() {
        return cap(Boolean.getBoolean("incoming.mtB7"), System.getProperty("incoming.mtB7.ruinCapMiB"));
    }

    static long cap(boolean enabled, String value) {
        if (!enabled || value == null) return 512L << 20;
        final int mib;
        try { mib = Integer.parseInt(value); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("MT-B7 ruinCapMiB must be an integer", error); }
        if (mib < 512 || mib > 2048 || mib % 64 != 0)
            throw new IllegalArgumentException("MT-B7 ruinCapMiB must be 512..2048 in steps of 64");
        return (long) mib << 20;
    }

    static MtB7Refusals active(ServerLevel level) {
        if (!ENABLED || !level.hasData(ModAttachments.NUCLEAR_WORLD)) return null;
        var pending = level.getData(ModAttachments.NUCLEAR_WORLD).scars().mtB7Pending;
        var trace = pending == null ? null : pending.refusals;
        return trace != null && trace.active ? trace : null;
    }

    void begin(ServerLevel level, int event, long key, long now, long wave, boolean seen) {
        active = seen && event == selected && DIMENSION.equals(level.dimension().location().toString());
        chunk = key; tick = now; lag = now - wave;
        planCells = planMaterial = 0;
    }

    void end() { active = false; }
    void plan(int cells, int material) { planCells = cells; planMaterial = material; }

    /** First observation, then at most once per 200 game ticks per chunk/reason. Never evict and re-admit. */
    boolean admit(long key, Reason reason, long now) {
        Stamp stamp = chunks.get(key);
        if (stamp == null) {
            if (chunks.size() == CHUNK_LIMIT) { overflow++; return false; }
            stamp = new Stamp(); chunks.put(key, stamp);
        }
        int index = reason.ordinal();
        stamp.counts[index]++;
        if (stamp.last[index] != Long.MIN_VALUE && now - stamp.last[index] < INTERVAL) return false;
        stamp.last[index] = now;
        return true;
    }

    void refuse(Reason reason, long requested, long retained, String detail) {
        if (!active || !admit(chunk, reason, tick)) return;
        emit(reason, requested, retained, detail);
    }

    void stale(int cell, int minY, BlockState expected, BlockState actual, long expectedHash, long actualHash) {
        if (!active || !admit(chunk, Reason.STALE, tick)) return;
        int x = (ChunkPos.getX(chunk) << 4) + (cell & 15);
        int z = (ChunkPos.getZ(chunk) << 4) + ((cell >> 4) & 15);
        int y = minY + (((cell >>> RuinPlan.SECTION_SHIFT) & 255) << 4) + ((cell >> 8) & 15);
        emit(Reason.STALE, -1, -1, "op=apply_current operand=" + (expected == null ? "unavailable" : "first_normalized_difference")
                + " x=" + x + " y=" + y + " z=" + z + " expected=" + state(expected) + " actual=" + state(actual)
                + " expected_hash=" + expectedHash + " actual_hash=" + actualHash);
    }

    private static String state(BlockState state) {
        if (state == null) return "unavailable";
        String value = state.toString();
        // A hostile/large mod property name cannot turn a diagnostic row into unbounded retained text.
        return value.substring(0, Math.min(value.length(), 1024)).replace(' ', '_');
    }

    private void emit(Reason reason, long requested, long retained, String detail) {
        MtB7Metrics.refusal("wall_ms=" + System.currentTimeMillis() + " mono_ns=" + System.nanoTime()
                + " server_session=" + MtB7Metrics.session() + " dimension=" + DIMENSION + " event=" + selected
                + " chunk_x=" + ChunkPos.getX(chunk) + " chunk_z=" + ChunkPos.getZ(chunk)
                + " game_time=" + tick + " lag_ticks=" + lag + " reason=" + reason
                + " reason_count=" + chunks.get(chunk).counts[reason.ordinal()]
                + " cap=" + NuclearPrep.PLANS_CAP + " requested=" + requested + " server_retained=" + retained
                + " plan_cells=" + planCells + " plan_material=" + planMaterial
                + " owner_age_ns=" + (ownershipAt == 0 ? -1 : System.nanoTime() - ownershipAt)
                + " owners=" + Arrays.toString(ownership) + " material=" + Arrays.toString(material)
                + " trace_chunks=" + chunks.size() + " trace_overflow=" + overflow + " " + detail);
    }

    void ownership(long prep, long reservation, long parked, long matter, long craterMatter,
                   long progress, long craters, long settle, long contexts) {
        ownership[0] = prep; ownership[1] = reservation; ownership[2] = parked; ownership[3] = matter;
        ownership[4] = craterMatter; ownership[5] = progress; ownership[6] = craters;
        ownership[7] = settle; ownership[8] = contexts;
        ownershipAt = System.nanoTime();
    }

    void material(long released, long deposited, long dispersed, long moving, long stranded,
                  long sources, long supports, long uncertain, long originals, long observations,
                  long maxLag, long latestLag, long sampleTick) {
        material[0] = released; material[1] = deposited; material[2] = dispersed; material[3] = moving;
        material[4] = stranded; material[5] = sources; material[6] = supports; material[7] = uncertain;
        material[8] = originals; material[9] = observations; material[10] = maxLag;
        material[11] = latestLag; material[12] = sampleTick;
    }

    String snapshot() {
        return " refusal_trace_chunks=" + chunks.size() + " refusal_trace_overflow=" + overflow
                + " owner_age_ns=" + (ownershipAt == 0 ? -1 : System.nanoTime() - ownershipAt)
                + " owners=" + Arrays.toString(ownership) + " material=" + Arrays.toString(material);
    }
}
