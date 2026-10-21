package ua.zentix.incoming.client.flight;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import ua.zentix.incoming.entity.BomberEntity;
import ua.zentix.incoming.entity.InterceptorEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.strike.Hearing;
import ua.zentix.incoming.strike.WeaponType;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Пути снарядов на клиенте по UUID ({@link FlightTrack}): по сущности, пока она есть у клиента, без неё — по пакетам
 * сервера {@link S2C.FarFlights}; один путь переходит с одного на другое без шва. Из них звук снарядов
 * ({@code ClientSounds}) и картинка вдали ({@code client.far.FarFlightRenderer}). Время — свои тики клиента
 * ({@link #now}), на паузе стоят.
 */
public final class FlightTracks {
    private static final Map<UUID, FlightTrack> TRACKS = new HashMap<>();
    /** Пути зенитных ракет ЗРК по виду у клиента — отдельно: оружия у них нет ({@link FlightTrack#interceptor}). */
    private static final Map<UUID, FlightTrack> INTERCEPTORS = new HashMap<>();
    /**
     * Без новых данных столько тиков (сущность пропала, пакетов о ней нет) — полёт кончился. Молчит звук и пропадает
     * снаряд вдали раньше, как только данных на нужный момент нет ({@link FlightTrack#covers}); этот срок — чтобы
     * сервер, вставший на секунду, не обрывал далёкий звук.
     */
    private static final int STALE = 40;
    /**
     * Кончившийся полёт помнится столько: звук идёт до уха не дольше ({@link Hearing#MAX_DELAY}), шлейф вдали за это
     * время тает.
     */
    public static final int RINGOUT = Hearing.MAX_DELAY;
    private static long tick;
    /** Мир, в котором записаны пути: смена измерения — новый мир, прошлые пути там не летят. */
    private static ClientLevel world;

    private FlightTracks() {}

    /** Текущее время путей, тики клиента. */
    public static long now() {
        return tick;
    }

    /** Раз в тик клиента (не на паузе): запись по сущностям, конец полётов, о которых давно ничего нет. */
    public static void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            reset();
            world = null;
            return;
        }
        if (level != world) {
            reset();
            world = level;
        }
        tick++;
        for (Entity e : level.entitiesForRendering()) {
            if (e instanceof StrikeProjectile p && p.isActive()) track(p.getUUID(), p.weapon(), p instanceof BomberEntity).record(tick, p);
            else if (e instanceof InterceptorEntity i && !i.isRemoved()) {
                FlightTrack t = INTERCEPTORS.get(i.getUUID());
                if (t == null || t.isDead()) INTERCEPTORS.put(i.getUUID(), t = FlightTrack.interceptor(i.getUUID()));
                t.record(tick, i);
            }
        }
        expire(TRACKS);
        expire(INTERCEPTORS);
    }

    /** Без данных дольше {@link #STALE} — полёт кончился; кончившийся забывается через {@link #RINGOUT}. */
    private static void expire(Map<UUID, FlightTrack> tracks) {
        tracks.values().removeIf(t -> {
            // вид ракеты ЗРК пропал (разрыв или ушла из тикающих чанков): её полёт у клиента кончился в тот же тик
            if (!t.isDead() && tick - t.lastTick() > (t.weapon == null ? 1 : STALE)) t.die(tick);
            return t.isDead() && tick - t.deathTick() > RINGOUT;
        });
    }

    /** Снаряды, которых у клиента нет, но которые уже слышно или видно: путь по данным сервера. */
    public static void received(S2C.FarFlights packet) {
        if (Minecraft.getInstance().level == null) return;
        for (S2C.FarFlight f : packet.flights()) track(f.id(), WeaponType.byId(f.weapon()), f.bomber()).record(tick, f);
    }

    /** Путь снаряда: уже идущий или новый (прошлый полёт с тем же UUID уже кончился). */
    private static FlightTrack track(UUID id, WeaponType weapon, boolean bomber) {
        FlightTrack t = TRACKS.get(id);
        if (t == null || t.isDead()) {
            t = new FlightTrack(id, weapon, bomber);
            TRACKS.put(id, t);
        }
        return t;
    }

    /** Нынешний путь снаряда или {@code null}. */
    public static FlightTrack get(UUID id) {
        return TRACKS.get(id);
    }

    public static Collection<FlightTrack> all() {
        return Collections.unmodifiableCollection(TRACKS.values());
    }

    /** Пути зенитных ракет ЗРК (звук мотора, шлейф и облако старта). */
    public static Collection<FlightTrack> interceptors() {
        return Collections.unmodifiableCollection(INTERCEPTORS.values());
    }

    /** Нынешний путь зенитной ракеты или {@code null}. */
    public static FlightTrack interceptor(UUID id) {
        return INTERCEPTORS.get(id);
    }

    /** Снаряд взорвался (пакет взрыва): его полёт кончился в этот тик ({@link FlightTrack#impact}). */
    public static void impact(UUID id) {
        FlightTrack t = TRACKS.get(id);
        if (t != null) t.impact(tick);
    }

    /** Обычный отбой: эти полёты кончились сразу (без взрыва — и без шлейфа, который тает). */
    public static void cancelled(Collection<UUID> projectiles) {
        projectiles.forEach(TRACKS::remove);
    }

    /** Отбой с ядерными. */
    public static void reset() {
        TRACKS.clear();
        INTERCEPTORS.clear();
    }

    /**
     * Выход из мира: и ссылка на его клиентский мир — без мира {@link #tick} не идёт, и она держала бы старый мир
     * с чанками и сущностями в меню до входа в следующий.
     */
    public static void logout() {
        reset();
        world = null;
    }
}
