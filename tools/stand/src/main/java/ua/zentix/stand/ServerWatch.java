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
 * Поток встроенного сервера: попадания залпов, взрывы, тик. События Minecraft — в {@link Impacts} (правило попадания
 * там): уход снаряда (тип из плана) из мира убитым или убранным, возврат в мир, начало взрыва ({@code ExplosionEvent.Start},
 * первым и отменённое тоже — попадание было, даже если взрыв отменил мод защиты). Подрыв боевой части — тип урона
 * взрыва из плана ({@code incoming:strike} у всех версий 3.4.1+); авария снаряда до взведения — ванильный взрыв
 * ({@code minecraft:explosion}), не попадание. Снаряд взрыва — его источник
 * ({@code getDirectSourceEntity}, так у Incoming после 3.7.0) или прямой источник его урона ({@code DamageSource.getDirectEntity}:
 * в 3.4.1–3.7.0 боевая часть даёт снаряд только ему, {@code Warheads.detonate(…, this, …)} → {@code ModDamageTypes.source}),
 * — одно правило для всех версий. Время попадания — миг ухода. Снаряд вне мира сущностью не бывает, поэтому «нет
 * снарядов в мире» конец залпа не значит — конец по числу попаданий ({@link Director}).
 */
final class ServerWatch {
    private final Out out;
    private final Set<String> projectiles, warheads;
    /** Только поток сервера (кроме {@link Impacts#waiting}). */
    private final Impacts hits = new Impacts();
    private final Map<String, Integer> expected = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> impacts = new ConcurrentHashMap<>();
    private final Set<UUID> alive = ConcurrentHashMap.newKeySet();
    /** Цели, заявленные потоком клиента: сервер берёт их перед уходом снаряда (Impacts — только его поток). */
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
     * Залп ждёт {@code expect} попаданий в радиусе {@code r} от (x, z) (поток клиента: шаг залпа). Поток сервера берёт
     * заявленные цели перед каждым уходом ({@link #takeTargets}): снаряды залпа уходят только после его команд.
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

    /** Уходов залпа у цели, ждущих взрыва или возврата в мир. */
    int waiting(String salvo) {
        return hits.waiting(salvo);
    }

    /** Снарядов в мире сейчас (вне загруженного мира — не в счёт). */
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

    /** Снаряд взрыва: источник взрыва или прямой источник его урона — если это снаряд плана. */
    @Nullable
    static Entity projectileOf(Explosion x) {
        Entity direct = x.getDirectSourceEntity();
        return direct != null ? direct : x.damageSource.getDirectEntity();
    }

    /** Тип урона взрыва: «incoming:strike» и т. п. */
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
            // подтверждения приходят не по порядку уходов: список — по времени попадания
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
