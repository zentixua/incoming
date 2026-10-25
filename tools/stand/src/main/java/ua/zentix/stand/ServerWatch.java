package ua.zentix.stand;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Поток встроенного сервера: попадания залпов, взрывы, тик. Попадание — снаряд (тип из плана) ушёл из мира убитым или
 * убранным ({@code KILLED}, {@code DISCARDED}) в радиусе цели залпа по горизонтали: так кончается каждый удар, и это видно
 * по реестру сущностей без кода Incoming. Взрыв ({@code ExplosionEvent.Detonate}) попаданием не считается: у удара бывают
 * вторичные подрывы (топливо), каждый — свой взрыв. Снаряд вне загруженного мира ({@code VirtualFlights} Incoming)
 * сущностью не бывает, поэтому «нет снарядов в мире» конец залпа не значит — конец по числу попаданий
 * ({@link Director}). Каждый снаряд (UUID) засчитывается один раз: уходя вне мира и возвращаясь, он остаётся тем же.
 */
final class ServerWatch {
    private record Target(String salvo, double x, double z, double r) {}

    private final Out out;
    private final Set<String> projectiles;
    private final List<Target> targets = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> expected = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> impacts = new ConcurrentHashMap<>();
    private final Set<UUID> counted = ConcurrentHashMap.newKeySet();
    private final Set<UUID> alive = ConcurrentHashMap.newKeySet();
    private int ticks;

    ServerWatch(Out out, Set<String> projectiles) {
        this.out = out;
        this.projectiles = projectiles;
        NeoForge.EVENT_BUS.addListener(this::onJoin);
        NeoForge.EVENT_BUS.addListener(this::onLeave);
        NeoForge.EVENT_BUS.addListener(this::onDetonate);
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    /** Залп ждёт {@code expect} попаданий в радиусе {@code r} от (x, z); позже заявленная цель берёт уход раньше прежних. */
    void target(String salvo, double x, double z, double r, int expect) {
        targets.addFirst(new Target(salvo, x, z, r));
        expected.put(salvo, expect);
        impacts.putIfAbsent(salvo, new ArrayList<>());
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

    /** Время n-го попадания залпа (1 — первое), nanoTime; −1 — ещё нет. */
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

    /** Снарядов в мире сейчас (вне загруженного мира — не в счёт). */
    int alive() {
        return alive.size();
    }

    private boolean projectile(Entity e) {
        return projectiles.contains(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
    }

    private void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !projectile(e.getEntity())) return;
        alive.add(e.getEntity().getUUID());
    }

    private void onLeave(EntityLeaveLevelEvent e) {
        if (e.getLevel().isClientSide() || !projectile(e.getEntity())) return;
        Entity p = e.getEntity();
        alive.remove(p.getUUID());
        Entity.RemovalReason why = p.getRemovalReason();
        Vec3 at = p.position();
        long now = System.nanoTime();
        if (why == Entity.RemovalReason.KILLED || why == Entity.RemovalReason.DISCARDED) {
            for (Target t : targets) {
                if (Math.hypot(at.x - t.x, at.z - t.z) > t.r) continue;
                if (!counted.add(p.getUUID())) return;
                List<Long> l = impacts.get(t.salvo);
                int n;
                synchronized (l) {
                    l.add(now);
                    n = l.size();
                }
                out.event("impact", now, "salvo", t.salvo, "n", n, "at", new double[]{at.x, at.y, at.z}, "uuid", p.getUUID().toString());
                return;
            }
        }
        out.event("leave", now, "at", new double[]{at.x, at.y, at.z}, "reason", String.valueOf(why), "uuid", p.getUUID().toString());
    }

    private void onDetonate(ExplosionEvent.Detonate e) {
        if (e.getLevel().isClientSide()) return;
        Explosion x = e.getExplosion();
        Vec3 c = x.center();
        Entity src = x.getDirectSourceEntity();
        out.event("blast", System.nanoTime(), "at", new double[]{c.x, c.y, c.z}, "radius", x.radius(),
                "source", src == null ? "none" : BuiltInRegistries.ENTITY_TYPE.getKey(src.getType()).toString());
    }

    /** Раз в секунду: средний тик сервера (последние 100 тиков, как F3), время игры, снаряды и все сущности мира. */
    private void onTick(ServerTickEvent.Post e) {
        if (++ticks % 20 != 0) return;
        MinecraftServer s = e.getServer();
        ServerLevel level = s.overworld();
        int entities = 0;
        for (Entity ignored : level.getAllEntities()) entities++;
        out.event("srv", System.nanoTime(), "mspt", s.getAverageTickTimeNanos() / 1e6, "game_time", level.getGameTime(),
                "projectiles", alive.size(), "entities", entities);
    }
}
