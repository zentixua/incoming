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
 * убранным ({@code KILLED}, {@code DISCARDED}) в радиусе цели залпа по горизонтали и рядом с ним (≤ {@link #NEAR} м)
 * начался взрыв ({@code ExplosionEvent.Start}) — за {@link #BEFORE} тиков до ухода или до {@link #AFTER} после: работа
 * попаданий Incoming идёт под бюджетом тика и под нагрузкой отстаёт. Одного ухода мало: снаряд, уходящий в полёт вне
 * загруженного мира ({@code VirtualFlights.park}), тоже убирается {@code discard} и летит дальше тем же UUID; такой уход
 * без взрыва рядом истекает (событие {@code leave-unconfirmed}). Время попадания — миг ухода. Взрыв сам по себе
 * попаданием не считается: у удара бывают вторичные подрывы, каждый — свой взрыв. Снаряд вне мира сущностью не бывает,
 * поэтому «нет снарядов в мире» конец залпа не значит — конец по числу попаданий ({@link Director}). Каждый снаряд
 * засчитывается один раз.
 */
final class ServerWatch {
    private record Target(String salvo, double x, double z, double r) {}

    /** Уход у цели, ждущий взрыва рядом. */
    private record Leave(UUID uuid, String salvo, Vec3 at, long nanos, int tick) {}

    private record Blast(Vec3 at, int tick) {}

    /** Взрыв — не дальше стольких метров от места ухода снаряда. */
    static final double NEAR = 16;
    /** Взрыв раньше ухода — не больше стольких тиков; позже — не больше. */
    static final int BEFORE = 20, AFTER = 600;

    private final Out out;
    private final Set<String> projectiles;
    private final List<Target> targets = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> expected = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> impacts = new ConcurrentHashMap<>();
    private final Set<UUID> counted = ConcurrentHashMap.newKeySet();
    private final Set<UUID> alive = ConcurrentHashMap.newKeySet();
    /** Только поток сервера. */
    private final List<Leave> pending = new ArrayList<>();
    private final List<Blast> recent = new ArrayList<>();
    /** Уходов, ждущих взрыва, — для потока клиента. */
    private volatile int waiting;
    private int ticks;

    ServerWatch(Out out, Set<String> projectiles) {
        this.out = out;
        this.projectiles = projectiles;
        NeoForge.EVENT_BUS.addListener(this::onJoin);
        NeoForge.EVENT_BUS.addListener(this::onLeave);
        NeoForge.EVENT_BUS.addListener(this::onStart);
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

    /** Уходов у целей, ждущих взрыва рядом. */
    int waiting() {
        return waiting;
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
        if ((why == Entity.RemovalReason.KILLED || why == Entity.RemovalReason.DISCARDED) && !counted.contains(p.getUUID())) {
            for (Target t : targets) {
                if (Math.hypot(at.x - t.x, at.z - t.z) > t.r) continue;
                Leave l = new Leave(p.getUUID(), t.salvo, at, now, ticks);
                Blast b = nearest(recent, at, ticks - BEFORE);
                if (b != null) confirm(l, "blast-before");
                else pending.add(l);
                waiting = pending.size();
                return;
            }
        }
        out.event("leave", now, "at", new double[]{at.x, at.y, at.z}, "reason", String.valueOf(why), "uuid", p.getUUID().toString());
    }

    private static Blast nearest(List<Blast> blasts, Vec3 at, int since) {
        Blast best = null;
        for (Blast b : blasts) {
            if (b.tick >= since && b.at.distanceTo(at) <= NEAR && (best == null || b.at.distanceTo(at) < best.at.distanceTo(at))) best = b;
        }
        return best;
    }

    /** Взрыв начался: ближайший ждущий уход рядом — попадание. */
    private void onStart(ExplosionEvent.Start e) {
        if (e.getLevel().isClientSide()) return;
        Vec3 c = e.getExplosion().center();
        recent.add(new Blast(c, ticks));
        Leave best = null;
        for (Leave l : pending) {
            if (l.at.distanceTo(c) <= NEAR && ticks - l.tick <= AFTER && (best == null || l.at.distanceTo(c) < best.at.distanceTo(c))) best = l;
        }
        if (best != null) confirm(best, "blast-after");
    }

    private void confirm(Leave l, String how) {
        UUID id = l.uuid;
        pending.removeIf(x -> x.uuid.equals(id));
        waiting = pending.size();
        if (!counted.add(id)) return;
        List<Long> list = impacts.get(l.salvo);
        int n;
        synchronized (list) {
            // подтверждения приходят не по порядку уходов: список — по времени попадания
            int i = list.size();
            while (i > 0 && list.get(i - 1) > l.nanos) i--;
            list.add(i, l.nanos);
            n = list.size();
        }
        out.event("impact", l.nanos, "salvo", l.salvo, "n", n, "at", new double[]{l.at.x, l.at.y, l.at.z}, "uuid", id.toString(),
                "by", how, "confirm_ms", Stand.ms(System.nanoTime()));
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
        ticks++;
        recent.removeIf(b -> ticks - b.tick > BEFORE);
        for (var it = pending.iterator(); it.hasNext(); ) {
            Leave l = it.next();
            if (ticks - l.tick <= AFTER) continue;
            it.remove();
            out.event("leave-unconfirmed", l.nanos, "salvo", l.salvo, "at", new double[]{l.at.x, l.at.y, l.at.z}, "uuid", l.uuid.toString());
        }
        waiting = pending.size();
        if (ticks % 20 != 0) return;
        MinecraftServer s = e.getServer();
        ServerLevel level = s.overworld();
        int entities = 0;
        for (Entity ignored : level.getAllEntities()) entities++;
        out.event("srv", System.nanoTime(), "mspt", s.getAverageTickTimeNanos() / 1e6, "game_time", level.getGameTime(),
                "projectiles", alive.size(), "entities", entities);
    }
}
