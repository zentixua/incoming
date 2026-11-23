package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.network.PacketDistributor;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.nuclear.material.MaterialBirth;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.util.Terrain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Dimension owner of surveys. The earliest point is ground zero, so one early revision can commit the entire footprint. */
public final class MaterialSurveys {
    public static final long LEAD_TICKS = 200;
    private final Map<Integer, MaterialSurvey> open = new LinkedHashMap<>();
    private final AtomicLong reserved = new AtomicLong();
    long reservedBytes() { return reserved.get(); }
    int pendingReads() { return (int) (reserved.get() / NuclearPrep.READ_BYTES); }

    private final ua.zentix.incoming.nuclear.material.LoadedSurfaces loaded = new ua.zentix.incoming.nuclear.material.LoadedSurfaces();
    private java.util.Iterator<net.minecraft.server.level.ChunkHolder> refresh;
    void live(LevelChunk chunk) {
        loaded.put(chunk.getPos().toLong(), MaterialSurvey.liveVotes(chunk));
    }
    void unload(LevelChunk chunk) { loaded.remove(chunk.getPos().toLong()); }

    /** Revisit ready columns to include player edits between chunk load and birth. */
    private void refresh(ServerLevel level, long deadline) {
        if (refresh == null || !refresh.hasNext()) refresh = level.getChunkSource().chunkMap.getChunks().iterator();
        for (int n = 0; n < 32 && System.nanoTime() < deadline && refresh.hasNext(); n++) {
            var p = refresh.next().getPos();
            var c = level.getChunkSource().getChunkNow(p.x, p.z);
            if (c != null) live(c);
        }
    }
    void disk(long key, int votes) {
        for (var survey : open.values()) survey.disk(key, votes);
    }
    void clear() {
        open.values().forEach(MaterialSurvey::close);
        open.clear();
    }

    public void tick(ServerLevel level, NuclearEvents events, long deadline) {
        long now = level.getGameTime();
        for (var s : events.scheduled()) {
            if (events.material(s.id()) != null) continue;
            MaterialSurvey survey = open.get(s.id());
            if (survey == null) {
                if (!Terrain.ready(level, BlockPos.containing(s.target())) && now < s.detonateTime() - LEAD_TICKS) continue;
                Detonation geometry = NuclearWarhead.geometry(level, s.target(), s.yieldKt(), s.airBurst(),
                        (float) IncomingConfig.SERVER.effectsScale());
                survey = new MaterialSurvey(level, geometry, reserved);
                open.put(s.id(), survey);
            }
            if (now >= s.detonateTime() - LEAD_TICKS) {
                publish(level, events, s.id(), survey.seal(level, now, loaded));
                open.remove(s.id());
            }
        }
        // Deliver committed work before spending the remaining time on optional pre-birth samples.
        for (var p : level.players()) {
            if (!p.hasData(ModAttachments.MATERIAL_DELIVERY)) continue;
            var packet = p.getData(ModAttachments.MATERIAL_DELIVERY).next(level.getServer().getTickCount());
            if (packet != null) PacketDistributor.sendToPlayer(p, packet);
            if (System.nanoTime() >= deadline) break;
        }
        for (var survey : open.values()) {
            if (System.nanoTime() >= deadline) break;
            boolean room = NuclearWorld.get(level).prep().pendingReads() + pendingReads() < NuclearPrep.READS;
            survey.work(level, deadline, room);
        }
        refresh(level, deadline);
    }

    public MaterialBirth detonate(ServerLevel level, NuclearEvents events, int strike, Detonation d) {
        MaterialBirth frozen = events.moveMaterial(strike, d.id());
        if (frozen != null) return frozen;
        MaterialSurvey survey = open.remove(strike);
        long now = level.getGameTime();
        if (survey != null) frozen = survey.seal(level, now, loaded);
        else {
            // A command or interception can only commit at birth. The loaded index is already available.
            var p = BlockPos.containing(d.burst());
            var chunk = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
            if (chunk != null) live(chunk);
            frozen = MaterialBirth.seal(d, now, MaterialSurvey.origin(level, d),
                    new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(), loaded.snapshot());
        }
        publish(level, events, d.id(), frozen);
        return frozen;
    }

    private static void publish(ServerLevel level, NuclearEvents events, int id, MaterialBirth birth) {
        events.material(id, birth);
        for (var player : level.players()) player.getData(ModAttachments.MATERIAL_DELIVERY).offer(birth);
    }
}
