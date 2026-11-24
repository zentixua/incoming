package ua.zentix.incoming.nuclear.world;

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

/** Dimension owner of scheduled and post-burst surveys. Material evidence follows each source through detonation. */
public final class MaterialSurveys {
    private static final int LEAD_TICKS = ua.zentix.incoming.nuclear.material.DustGrid.LEAD_TICKS;
    private final Map<Integer, MaterialSurvey> open = new LinkedHashMap<>();
    private final AtomicLong reserved = new AtomicLong();
    long reservedBytes() { return reserved.get(); }

    boolean wantsReads() {
        for (var survey : open.values()) if (survey.wantsReads()) return true;
        return false;
    }
    private final LoadedSurfaces loaded = new LoadedSurfaces();
    private final LongLinkedOpenHashSet refresh = new LongLinkedOpenHashSet();
    private int playerCursor, surveyCursor;
    long lastCommitNanos;
    void live(LevelChunk chunk) {
        long key = chunk.getPos().toLong();
        refresh.add(key);
        updateLive(key, chunk);
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
            if (c != null) updateLive(key, c);
        }
    }
    private void updateLive(long key, LevelChunk chunk) {
        int value = MaterialSurvey.liveVotes(chunk);
        if (loaded.put(key, value)) for (var survey : open.values()) survey.live(key, value);
    }

    void disk(long key, int votes) {
        for (var survey : open.values()) survey.disk(key, votes);
    }
    /**
     * Close the surveys nothing will read: published in full, or their strike called off before the birth was recorded
     * (its reserved bytes and read share go back to the other preparations).
     */
    void closeDone(NuclearEvents events) {
        open.entrySet().removeIf(e -> {
            int id = e.getKey();
            if (!e.getValue().publishedFinal() && (events.scheduled().stream().anyMatch(s -> s.id() == id)
                    || events.detonations().stream().anyMatch(d -> d.id() == id))) return false;
            e.getValue().close();
            return true;
        });
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
            var existing = events.material(s.id());
            if (existing != null) {
                restore(level, s.id(), existing);
                continue;
            }
            MaterialSurvey survey = open.get(s.id());
            if (survey == null) {
                if ((opened || System.nanoTime() >= deadline) && now < s.detonateTime() - LEAD_TICKS) continue;
                survey = new MaterialSurvey(level, geometry(level, s), reserved);
                open.put(s.id(), survey);
                opened = true;
            }
            long deadlineTick = survey.originDeadline(s.detonateTime());
            if (now >= deadlineTick || !sealed && now >= deadlineTick - runway && Terrain.ready(level, BlockPos.containing(s.target()))) {
                sealed = true;
                long start = System.nanoTime();
                var birth = survey.originReadyAtOpen && s.arrival().isEmpty()
                        ? survey.begin(level, s.detonateTime(), now, loaded)
                        : survey.begin(level, geometry(level, s), s.detonateTime(), now, loaded);
                events.material(s.id(), birth);
                for (var player : level.players()) {
                    if (NuclearStrikes.warns(s, player)) player.getData(ModAttachments.MATERIAL_DELIVERY).announce(birth);
                }

                lastCommitNanos += System.nanoTime() - start;
            }
        }
        for (var d : events.detonations()) restore(level, d.id(), events.material(d.id()));
        if (!open.isEmpty()) {
            long began = System.nanoTime();
            for (var survey : open.values()) if (survey.advance(now)) events.setDirty();
            lastCommitNanos += System.nanoTime() - began;
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
                var survey = surveys.get(surveyCursor++);
                if (survey.publish()) {
                    events.setDirty();
                    for (var player : players) if (player.hasData(ModAttachments.MATERIAL_DELIVERY))
                        player.getData(ModAttachments.MATERIAL_DELIVERY).refine(survey.birth);
                }
                int slots = Math.ceilDiv(Math.max(0, NuclearPrep.readSlots(level)), surveys.size() - n);
                survey.work(level, deadline, slots);
            }
        }
        closeDone(events);
        if (System.nanoTime() < deadline) events.encodeMaterial();
        refresh(level, deadline);
    }

    private static Detonation geometry(ServerLevel level, NuclearEvents.ScheduledStrike s) {
        if (s.arrival().isPresent()) return s.arrival().get().geometry();
        return NuclearWorld.get(level).planned(NuclearWarhead.geometry(level,
                s.surface() ? NuclearWarhead.surfaceAt(level, s.target()) : s.target(), s.yieldKt(), s.airBurst(),
                (float) IncomingConfig.SERVER.effectsScale()));
    }

    private void restore(ServerLevel level, int id, MaterialBirth birth) {
        if (birth == null || !birth.surveying() || open.containsKey(id)) return;
        var survey = new MaterialSurvey(level, birth.geometry(), reserved);
        survey.birth = birth;
        open.put(id, survey);
    }

    public MaterialBirth detonate(ServerLevel level, NuclearEvents events, int strike, Detonation d) {
        MaterialBirth birth = events.moveMaterial(strike, d.id());
        MaterialSurvey survey = open.remove(strike);
        if (birth == null) {
            if (survey == null) survey = new MaterialSurvey(level, d, reserved);
            birth = survey.begin(level, d, d.gameTime(), level.getGameTime(), loaded);
            events.material(d.id(), birth);
        }
        if (survey != null) open.put(d.id(), survey);
        deliver(level, birth);
        return birth;
    }

    private static void deliver(ServerLevel level, MaterialBirth birth) {
        for (var player : level.players()) {
            var delivery = player.getData(ModAttachments.MATERIAL_DELIVERY);
            delivery.detonate(birth);
            delivery.manifest(player);
        }
    }
}
