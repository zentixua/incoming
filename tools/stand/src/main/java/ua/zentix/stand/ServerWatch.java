package ua.zentix.stand;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Integrated-server thread: salvo hits, explosions, ticks. Minecraft events feed {@link Impacts} (hit
 * rule there): projectile (plan type) killed/removed, rejoin, explosion start ({@code ExplosionEvent.Start},
 * first, including cancelled events: hit occurred even if a protection mod cancelled the blast). Warhead
 * detonation uses plan damage type ({@code incoming:strike}, all 3.4.1+); pre-arming crash is vanilla
 * ({@code minecraft:explosion}), not a hit. Explosion projectile is source ({@code getDirectSourceEntity},
 * Incoming after 3.7.0) or direct damage source ({@code DamageSource.getDirectEntity}: 3.4.1–3.7.0 supplies
 * projectile only there, {@code Warheads.detonate(…, this, …)} → {@code ModDamageTypes.source}); one rule
 * for all versions. Hit time is removal instant. Off-world projectiles are not entities, so absence of
 * world projectiles does not end a salvo; hit count does ({@link Director}).
 */
final class ServerWatch {
    private final Out out;
    private final Set<String> projectiles, warheads;
    /** Server thread only except {@link Impacts#waiting}. */
    private final Impacts hits = new Impacts();
    private final Map<String, Integer> expected = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> impacts = new ConcurrentHashMap<>();
    private final Set<UUID> alive = ConcurrentHashMap.newKeySet();
    /** Targets registered by client thread: server consumes before projectile removal (Impacts is server-thread only). */
    private final List<Impacts.Target> declared = new ArrayList<>();
    private int ticks;

    ServerWatch(Out out, Set<String> projectiles, Set<String> warheads) {
        this.out = out;
        this.projectiles = projectiles;
        this.warheads = warheads;
        NeoForge.EVENT_BUS.addListener(this::onJoin);
        NeoForge.EVENT_BUS.addListener(this::onLeave);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, ExplosionEvent.Start.class, this::onStart);
        NeoForge.EVENT_BUS.addListener(this::onDetonate);
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    /**
     * Salvo awaits {@code expect} hits within {@code r} of (x, z) (client salvo step). Server takes pending
     * targets before every removal ({@link #takeTargets}); salvo projectiles leave only after its commands.
     */
    void target(String salvo, double x, double z, double r, int expect) {
        expected.put(salvo, expect);
        impacts.putIfAbsent(salvo, new ArrayList<>());
        synchronized (declared) {
            declared.add(new Impacts.Target(salvo, x, z, r));
        }
    }

    private void takeTargets() {
        synchronized (declared) {
            for (Impacts.Target t : declared) hits.target(t.salvo(), t.x(), t.z(), t.r());
            declared.clear();
        }
    }

    int expected(String salvo) {
        return expected.getOrDefault(salvo, 0);
    }

    int impacts(String salvo) {
        List<Long> l = impacts.get(salvo);
        if (l == null) return 0;
        synchronized (l) {
            return l.size();
        }
    }

    /** Time of nth salvo hit (1 first), nanoTime; −1 if absent. */
    long impactTime(String salvo, int n) {
        List<Long> l = impacts.get(salvo);
        if (l == null) return -1;
        synchronized (l) {
            return l.size() >= n ? l.get(n - 1) : -1;
        }
    }

    long lastImpact(String salvo) {
        List<Long> l = impacts.get(salvo);
        if (l == null) return -1;
        synchronized (l) {
            return l.isEmpty() ? -1 : l.getLast();
        }
    }

    /** Salvo target removals awaiting explosion or rejoin. */
    int waiting(String salvo) {
        return hits.waiting(salvo);
    }

    /** World projectiles now (off-world excluded). */
    int alive() {
        return alive.size();
    }

    private boolean projectile(@Nullable Entity e) {
        return e != null && projectiles.contains(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
    }

    private void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !projectile(e.getEntity())) return;
        UUID id = e.getEntity().getUUID();
        alive.add(id);
        if (hits.join(id)) out.event("leave-returned", System.nanoTime(), "uuid", id.toString());
    }

    private void onLeave(EntityLeaveLevelEvent e) {
        if (e.getLevel().isClientSide() || !projectile(e.getEntity())) return;
        Entity p = e.getEntity();
        alive.remove(p.getUUID());
        takeTargets();
        Entity.RemovalReason why = p.getRemovalReason();
        Vec3 at = p.position();
        long now = System.nanoTime();
        out.event("leave", now, "at", new double[]{at.x, at.y, at.z}, "reason", String.valueOf(why), "uuid", p.getUUID().toString());
        if (why != Entity.RemovalReason.KILLED && why != Entity.RemovalReason.DISCARDED) return;
        record(hits.leave(p.getUUID(), at.x, at.y, at.z, now));
    }

    /** Explosion projectile: source or direct damage source, if a plan projectile. */
    @Nullable
    static Entity projectileOf(Explosion x) {
        Entity direct = x.getDirectSourceEntity();
        return direct != null ? direct : x.damageSource.getDirectEntity();
    }

    /** Explosion damage type, e.g. "incoming:strike". */
    static String damageType(Explosion x) {
        return x.damageSource.typeHolder().unwrapKey().map(k -> k.location().toString()).orElse("?");
    }

    private void onStart(ExplosionEvent.Start e) {
        if (e.getLevel().isClientSide()) return;
        Entity p = projectileOf(e.getExplosion());
        record(hits.blast(projectile(p) ? p.getUUID() : null, warheads.contains(damageType(e.getExplosion()))));
    }

    private void record(@Nullable Impacts.Hit hit) {
        if (hit == null) return;
        Impacts.Leave l = hit.leave();
        List<Long> list = impacts.get(l.salvo());
        int n;
        synchronized (list) {
            // Confirmations do not follow removal order: sort by hit time
            int i = list.size();
            while (i > 0 && list.get(i - 1) > l.nanos()) i--;
            list.add(i, l.nanos());
            n = list.size();
        }
        out.event("impact", l.nanos(), "salvo", l.salvo(), "n", n, "at", new double[]{l.x(), l.y(), l.z()}, "uuid", l.uuid().toString(),
                "by", hit.how(), "confirm_ms", Stand.ms(System.nanoTime()));
    }

    private void onDetonate(ExplosionEvent.Detonate e) {
        if (e.getLevel().isClientSide()) return;
        Explosion x = e.getExplosion();
        Vec3 c = x.center();
        Entity src = projectileOf(x);
        out.event("blast", System.nanoTime(), "at", new double[]{c.x, c.y, c.z}, "radius", x.radius(),
                "source", src == null ? "none" : BuiltInRegistries.ENTITY_TYPE.getKey(src.getType()).toString(),
                "uuid", src == null ? null : src.getUUID().toString(), "damage", damageType(x));
    }

    /**
     * Once a second: mean server tick (last 100 ticks, like F3), game time, projectile/all-entity counts,
     * wall time (aligns events with machine load; client.py uses wall clock).
     */
    private void onTick(ServerTickEvent.Post e) {
        if (++ticks % 20 != 0) return;
        MinecraftServer s = e.getServer();
        ServerLevel level = s.overworld();
        int entities = 0;
        for (Entity ignored : level.getAllEntities()) entities++;
        out.event("srv", System.nanoTime(), "mspt", s.getAverageTickTimeNanos() / 1e6, "game_time", level.getGameTime(),
                "projectiles", alive.size(), "entities", entities, "epoch_ms", System.currentTimeMillis());
    }
}
