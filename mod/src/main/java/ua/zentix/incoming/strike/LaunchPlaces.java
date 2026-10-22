package ua.zentix.incoming.strike;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.util.Terrain;

import java.util.Comparator;
import java.util.UUID;

/**
 * Район места пуска из приказа ({@link LaunchOrigin}): залп с него грузит его в фоне ({@link AreaLoader}, как район цели
 * у снаряда) и держит, пока идёт, — пусковая встаёт на самом месте, а снаряд проходит пуск и разгон в мире. Пуск видно
 * издалека (факел, облако старта, шлейф — {@link FarFlights} до {@code far_range}), поэтому место, которого нет
 * в загруженных чанках, — не повод пропустить пуск: без района снаряд стартовал бы вне мира уже на маршевом участке
 * (у ракеты — на высоте крейсера над местом, у РСЗО — на нисходящей ветви), и пуска не видел бы никто.
 * Район живёт {@link #KEEP} тиков после последнего продления: залп продлевает его каждый тик, а отменённый или упавший
 * залп отпускает его сам.
 */
public final class LaunchPlaces {
    private static final TicketType<UUID> TYPE = TicketType.create("incoming_launch_place", Comparator.<UUID>naturalOrder());
    /** Тиков района после последнего продления. */
    static final int KEEP = 100;
    /**
     * Сколько залп ждёт района места пуска, тиков (30 с): свежий район готов за 0,7–4,6 с (замер у района цели,
     * {@code RocketEntity#preloadDistance}). Не дождался — старт вне мира без пусковой, как раньше.
     */
    public static final int WAIT = 600;

    private LaunchPlaces() {}

    /**
     * Пуск оружия {@code weapon} с места {@code from} ждёт района: место есть, оружие стартует с пусковой (у B-2 место
     * пуска — только курс захода, МБР его не берёт), а место ещё не готово ({@link #ready}).
     */
    public static boolean waits(ServerLevel level, WeaponType weapon, @Nullable LaunchOrigin from) {
        return from != null && fromPad(weapon) && !ready(level, from);
    }

    /** Оружие стартует с пусковой на месте пуска (у него есть пакет): району места пуска есть что держать. */
    public static boolean fromPad(WeaponType weapon) {
        return weapon.spec().rack() != null;
    }

    /**
     * Место готово к пуску: у места из приказа — все чанки на {@link LaunchSite#PLACE_REACH} вокруг (там ищется место
     * пусковой), у стационарной пусковой — её чанк.
     */
    public static boolean ready(ServerLevel level, LaunchOrigin from) {
        return from instanceof LaunchOrigin.Fixed f ? Terrain.ready(level, f.pos()) : Terrain.readyAround(level, from.at(), LaunchSite.PLACE_REACH);
    }

    /** Взять или продлить район места {@code from} залпа {@code key}. */
    public static void hold(ServerLevel level, LaunchOrigin from, UUID key) {
        AreaLoader areas = StrikeWorld.get(level).areas();
        AreaLoader.Area area = area(from, key);
        long until = level.getGameTime() + KEEP;
        if (areas.holds(area)) areas.renew(area, until);
        else areas.hold(level, area, until);
    }

    /** Держит ли залп {@code key} район своего места (проверки). */
    public static boolean holds(ServerLevel level, LaunchOrigin from, UUID key) {
        return StrikeWorld.get(level).areas().holds(area(from, key));
    }

    /** Район вокруг места: тот же радиус, что у района цели ({@link FlightTickets#DISTANCE}, 4 чанка — больше {@link LaunchSite#PLACE_REACH}). */
    private static AreaLoader.Area area(LaunchOrigin from, UUID key) {
        return new AreaLoader.Area(TYPE, new ChunkPos(BlockPos.containing(from.at())), FlightTickets.DISTANCE, key);
    }
}
