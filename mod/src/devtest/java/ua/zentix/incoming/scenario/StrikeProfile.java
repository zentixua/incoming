package ua.zentix.incoming.scenario;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.strike.PickHints;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.stress.StressDirector;
import ua.zentix.incoming.util.Terrain;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Measure the server thread around impacts in a copy of the player's world
 * ({@code tools/prod_client.py strike-profile --world … --prop incoming.profile.steps=…}, full modpack,
 * JFR via {@code --jvm -XX:StartFlightRecording=…}): semicolon-separated steps, each {@code tp x y z} (player teleport,
 * as in game) or {@code weapon count spread x y z} (strike as that player, as from the console: map hint at the same
 * point 5 s before it, like clicking the map). First step 30 s after joining, later steps every
 * {@code incoming.profile.gap} ticks (default 2400). Log: each server tick longer than 50 ms
 * ({@code SCENARIO strike-profile tick}), tick-start interval longer than 100 ms ({@code … period}: includes server-thread
 * work between ticks — parsing disk chunks, transition to FULL — and waiting for the next tick, hence the higher
 * threshold), and once per second mean tick, longest tick, and wall time of 20 ticks ({@code … second}), with server
 * ticks and wall-clock ms since the last step. For each projectile's first explosion, {@code … hit}: position at hit,
 * phase, armed state, heading, target point and hit block (impact far from target: collision en route or not).
 * First line {@code … clock utc …}: actual UTC beside log-line time. One client can have different time zones in its
 * log, GC log and JFR (host laptop: log +3, gc.log +2); extract aligns them in UTC and takes log offset from this line.
 */
final class StrikeProfile {
    private static final long SLOW_NANOS = 50_000_000L, SLOW_PERIOD_NANOS = 100_000_000L;
    private static final int FIRST = 600, PICK_LEAD = 100;

    private final List<String[]> steps = new ArrayList<>();
    private final int gap = Integer.getInteger("incoming.profile.gap", 2400);
    private int tick;
    /** Server tick of the last step and its name (for log lines). */
    private volatile long stepServerTick = -1, stepNanos;
    private volatile String stepName = "-";
    // server thread
    private long tickStart, secondStart, secondNanos, secondMax;
    private int secondTicks;
    /** Shells whose first explosion is already in the log (the warhead has several explosions). */
    private final Set<UUID> hit = new HashSet<>();

    StrikeProfile() {
        Incoming.LOG.info("SCENARIO strike-profile clock utc {}", Instant.now());
        String spec = System.getProperty("incoming.profile.steps", "");
        for (String s : spec.split(";")) {
            String[] a = s.strip().split("\\s+");
            if (a.length == 4 && a[0].equals("tp") || a.length == 6) steps.add(a);
            else if (!s.isBlank()) Incoming.LOG.warn("SCENARIO strike-profile: step “{}” is skipped - you need “tp x y z” or “weapon how much spread x y z\"", s);
        }
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onServerTickPre);
        NeoForge.EVENT_BUS.addListener(this::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(this::onBlast);
    }

    private void onClientTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (mc.player == null || server == null) return;
        tick++;
        // invulnerable: hitting the player's place is also a measuring step
        if (tick == 20) server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                "gamemode creative " + player(server).getGameProfile().getName()));
        for (int i = 0; i < steps.size(); i++) {
            String[] a = steps.get(i);
            int at = FIRST + i * gap;
            if (!a[0].equals("tp") && tick == at - PICK_LEAD) server.execute(() -> pick(server, a));
            if (tick == at) server.execute(() -> run(server, a));
        }
        if (tick == FIRST + steps.size() * gap) {
            Incoming.LOG.info("SCENARIO done");
            mc.stop();
        }
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static void pick(MinecraftServer server, String[] a) {
        ServerPlayer p = player(server);
        PickHints.pick(p.serverLevel(), p.getUUID(), p.getData(ModAttachments.PICK_HINT.get()), Double.parseDouble(a[3]), Double.parseDouble(a[5]));
    }

    private void run(MinecraftServer server, String[] a) {
        ServerPlayer p = player(server);
        String name = p.getGameProfile().getName();
        String c = a[0].equals("tp")
                ? String.format(Locale.ROOT, "tp %s %s %s %s", name, a[1], a[2], a[3])
                : String.format(Locale.ROOT, "execute as %s at @s run incoming salvo %s %s %s at %s %s %s", name, a[0], a[1], a[2], a[3], a[4], a[5]);
        stepServerTick = server.getTickCount();
        stepNanos = System.nanoTime();
        stepName = String.join(" ", a);
        Incoming.LOG.info("SCENARIO strike-profile step «{}» at {} {} {}: chunks={}", stepName, Math.round(p.getX()), Math.round(p.getY()), Math.round(p.getZ()),
                p.serverLevel().getChunkSource().getLoadedChunksCount());
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c);
    }

    private void onServerTickPre(ServerTickEvent.Pre e) {
        long now = System.nanoTime();
        if (tickStart != 0 && now - tickStart > SLOW_PERIOD_NANOS) {
            Incoming.LOG.info("SCENARIO strike-profile period {} ms after «{}» {}", (now - tickStart) / 1_000_000, stepName, since(e.getServer(), now));
        }
        if (secondTicks == 0) secondStart = now;
        tickStart = now;
    }

    /** From step: server tick and wall milliseconds (server behind - tick less than ms / 50). */
    private String since(MinecraftServer server, long now) {
        return stepServerTick < 0 ? "-" : String.format(Locale.ROOT, "+%d ticks +%d ms", server.getTickCount() - stepServerTick, (now - stepNanos) / 1_000_000);
    }

    private void onServerTickPost(ServerTickEvent.Post e) {
        long end = System.nanoTime(), took = end - tickStart;
        if (took > SLOW_NANOS) {
            Incoming.LOG.info("SCENARIO strike-profile tick {} ms after «{}» {}", took / 1_000_000, stepName, since(e.getServer(), end));
        }
        secondNanos += took;
        secondMax = Math.max(secondMax, took);
        if (e.getServer().getTickCount() % 100 == 0) logFlights(e.getServer().overworld());
        if (++secondTicks == 20) {
            Incoming.LOG.info("SCENARIO strike-profile second mspt={} max={} wall={} after «{}» {}", String.format(Locale.ROOT, "%.1f", secondNanos / 20 / 1e6),
                    secondMax / 1_000_000, (end - secondStart) / 1_000_000, stepName, since(e.getServer(), end));
            secondNanos = 0;
            secondMax = 0;
            secondTicks = 0;
        }
    }

    /**
     * First projectile explosion: the projectile is the source of explosion damage, already removed from the world, with the location, phase and course of the hit tick
     * . Block - at the point of explosion half a step along the course (the point of collision lies on the edge of the block).
     */
    private void onBlast(ExplosionEvent.Start e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        StrikeProjectile p = StressDirector.blastProjectile(e.getExplosion());
        if (p == null || !hit.add(p.getUUID())) return;
        try {
            logHit(level, p, e.getExplosion().center());
        } catch (RuntimeException x) {
            // line for information: the measurement should not fall because of it
            Incoming.LOG.warn("SCENARIO strike-profile hit: line not written", x);
        }
    }

    private void logHit(ServerLevel level, StrikeProjectile p, Vec3 at) {
        Vec3 pos = p.position(), aim = p.aimPoint(), v = p.getDeltaMovement();
        double heading = (Math.toDegrees(Math.atan2(v.x, -v.z)) + 360) % 360;
        double pitch = Math.toDegrees(Math.atan2(v.y, v.horizontalDistance()));
        Vec3 dir = v.lengthSqr() > 1e-6 ? v.normalize() : Vec3.ZERO;
        Incoming.LOG.info("SCENARIO strike-profile hit {} {} at {} (blast {}) phase {} armed {} heading {} pitch {} speed {} aim {}: {} blocks horizontal, {} above; block {} after «{}» {}",
                p.weapon().getSerializedName(), p.getUUID().toString().substring(0, 8), xyz(pos), xyz(at), p.flightPhase(), armed(p),
                Math.round(heading), Math.round(pitch), String.format(Locale.ROOT, "%.2f", v.length()), xyz(aim),
                Math.round(Math.hypot(at.x - aim.x, at.z - aim.z)), Math.round(at.y - aim.y), block(level, at, dir), stepName, since(level.getServer(), System.nanoTime()));
    }

    private static String xyz(Vec3 v) {
        return String.format(Locale.ROOT, "%.0f %.0f %.0f", v.x, v.y, v.z);
    }

    /** Block at the point of explosion or one step further along the course; does not look into an unready chunk. */
    private static String block(ServerLevel level, Vec3 at, Vec3 dir) {
        for (double d : new double[] {0.3, 1.0}) {
            BlockPos b = BlockPos.containing(at.add(dir.scale(d)));
            if (!Terrain.ready(level, b)) return "?";
            BlockState s = level.getBlockState(b);
            if (!s.isAir()) return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
        }
        return "air";
    }

    /** {@code StrikeProjectile.armed()} (protected); there is no such method - in the line “armed?”, and not a measurement drop. */
    private static final Method ARMED = armedMethod();

    private static Method armedMethod() {
        try {
            Method m = StrikeProjectile.class.getDeclaredMethod("armed");
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Incoming.LOG.warn("SCENARIO strike-profile: there is no method StrikeProjectile.armed() ({}) - in the lines hit “armed ?”", e.toString());
            return null;
        }
    }

    private static String armed(StrikeProjectile p) {
        if (ARMED == null) return "?";
        try {
            return (boolean) ARMED.invoke(p) ? "yes" : "no";
        } catch (ReflectiveOperationException e) {
            return "?";
        }
    }

    /**
     * Once every 5 s, while the shells are flying: how many of them are in the world and outside of it by flight phase - a strike without a single hit
     *  (run 740eec9) otherwise it cannot be distinguished from shells stuck in flight or on the launcher.
     */
    private void logFlights(ServerLevel level) {
        Map<String, Integer> in = new TreeMap<>(), off = new TreeMap<>();
        for (Entity en : level.getAllEntities()) {
            if (en instanceof StrikeProjectile p) in.merge(p.weapon().getSerializedName() + " " + p.flightPhase(), 1, Integer::sum);
        }
        for (StrikeProjectile p : VirtualFlights.get(level).flights()) off.merge(p.weapon().getSerializedName() + " " + p.flightPhase(), 1, Integer::sum);
        if (in.isEmpty() && off.isEmpty()) return;
        Incoming.LOG.info("SCENARIO strike-profile flights in world {} off world {} after «{}» {}", in, off, stepName, since(level.getServer(), System.nanoTime()));
    }
}
