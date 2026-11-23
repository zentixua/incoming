package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.material.LoadedSurfaces;
import ua.zentix.incoming.nuclear.material.MaterialBirth;
import ua.zentix.incoming.nuclear.material.MaterialDelivery;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Dimension owner of surveys. The earliest point is ground zero, so one early revision can commit the entire footprint. */
public final class MaterialSurveys {
    public static final long LEAD_TICKS = 200;
    private final Map<Integer, MaterialSurvey> open = new LinkedHashMap<>();
    private final AtomicLong reserved = new AtomicLong();
    long reservedBytes() { return reserved.get(); }

    private final LoadedSurfaces loaded = new LoadedSurfaces();
    private final LongLinkedOpenHashSet refresh = new LongLinkedOpenHashSet();
    private int playerCursor, surveyCursor;
    long lastCommitNanos;
    void live(LevelChunk chunk) {
        long key = chunk.getPos().toLong();
        refresh.add(key);
        loaded.put(key, MaterialSurvey.liveVotes(chunk));
    }
    void unload(LevelChunk chunk) {
        long key = chunk.getPos().toLong();
        refresh.remove(key);
        loaded.remove(key);
    }

    /** Revisit ready columns to include player edits between chunk load and birth. */
    private void refresh(ServerLevel level, long deadline) {
        int count = Math.min(refresh.size(), open.isEmpty() ? 4 : 32);
        for (int n = 0; n < count && System.nanoTime() < deadline; n++) {
            long key = refresh.removeFirstLong();
            refresh.add(key);
            var c = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (c != null) loaded.put(key, MaterialSurvey.liveVotes(c));
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
        // Reserve a tick per concurrent source before the first-cell deadline so a salvo can seal in portions.
        int runway = events.scheduled().size() - 1;
        boolean sealed = false, opened = false;
        lastCommitNanos = 0;
        for (var s : events.scheduled()) {
            if (events.material(s.id()) != null) continue;
            MaterialSurvey survey = open.get(s.id());
            if (survey == null) {
                if ((opened || System.nanoTime() >= deadline) && now < s.detonateTime() - LEAD_TICKS) continue;
                if (!Terrain.ready(level, BlockPos.containing(s.target())) && now < s.detonateTime() - LEAD_TICKS) continue;
                Detonation geometry = NuclearWorld.get(level).planned(NuclearWarhead.geometry(level,
                        s.surface() ? NuclearWarhead.surfaceAt(level, s.target()) : s.target(), s.yieldKt(), s.airBurst(),
                        (float) IncomingConfig.SERVER.effectsScale()));
                survey = new MaterialSurvey(level, geometry, reserved);
                open.put(s.id(), survey);
                opened = true;
            }
            long deadlineTick = s.detonateTime() - LEAD_TICKS;
            if (now >= deadlineTick || !sealed && now >= deadlineTick - runway) {
                sealed = true;
                long start = System.nanoTime();
                var birth = survey.seal(level, now, loaded);
                events.material(s.id(), birth);
                for (var player : level.players()) {
                    if (NuclearStrikes.warns(s, player)) player.getData(ModAttachments.MATERIAL_DELIVERY).announce(birth);
                }
                open.remove(s.id());
                lastCommitNanos += System.nanoTime() - start;
            }
        }
        // Deliver committed work before spending the remaining time on optional pre-birth samples.
        var players = level.players();
        for (int n = 0; n < players.size() && System.nanoTime() < deadline; n++) {
            if (playerCursor >= players.size()) playerCursor = 0;
            MaterialDelivery.send(players.get(playerCursor++), level.getServer().getTickCount());
        }
        if (!open.isEmpty()) {
            var surveys = new ArrayList<>(open.values());
            for (int n = 0; n < surveys.size() && System.nanoTime() < deadline; n++) {
                if (surveyCursor >= surveys.size()) surveyCursor = 0;
                surveys.get(surveyCursor++).work(level, deadline, NuclearPrep.readSlots(level));
            }
        }
        if (System.nanoTime() < deadline) events.encodeMaterial();
        refresh(level, deadline);
    }

    public MaterialBirth detonate(ServerLevel level, NuclearEvents events, int strike, Detonation d) {
        MaterialBirth frozen = events.moveMaterial(strike, d.id());
        if (frozen != null) {
            deliver(level, frozen);
            return frozen;
        }
        MaterialSurvey survey = open.remove(strike);
        long now = level.getGameTime();
        if (survey != null) frozen = survey.seal(level, now, loaded);
        else {
            // A command or interception can only commit at birth. The loaded index is already available.
            var p = BlockPos.containing(d.burst());
            var chunk = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
            if (chunk != null) live(chunk);
            frozen = MaterialBirth.seal(new java.util.UUID(d.seed(), now ^ d.id()), d, now, MaterialSurvey.origin(level, d),
                    new Long2IntOpenHashMap(), loaded.snapshot());
        }
        events.material(d.id(), frozen);
        deliver(level, frozen);
        return frozen;
    }

    private static void deliver(ServerLevel level, MaterialBirth birth) {
        for (var player : level.players()) player.getData(ModAttachments.MATERIAL_DELIVERY).detonate(birth);
    }
}
