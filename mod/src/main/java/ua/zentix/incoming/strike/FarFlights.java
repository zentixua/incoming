package ua.zentix.incoming.strike;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.entity.BomberEntity;
import ua.zentix.incoming.defense.DefenseWorld;
import ua.zentix.incoming.defense.Interceptor;
import ua.zentix.incoming.defense.InterceptorSpec;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.guidance.FlightController;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Снаряды, которых у клиента нет. Сущность уходит клиенту только в пределах его дальности прорисовки (и ближе дальности
 * слежения своего типа), а в полёте вне мира ({@link VirtualFlights}) её нет ни у кого, — но мотор ракеты или вой
 * снаряда РСЗО слышно дальше, а факел, шлейф и сам корпус видно ещё дальше. Каждому игроку уходят снаряды его мира,
 * которых у него нет и которые он уже может слышать ({@link Hearing#audible}) или видеть ({@link #range}): слышимые —
 * раз в {@link S2C.FarFlights#PERIOD} тика, только видимые — раз в {@link S2C.FarFlights#FAR_PERIOD}, не больше
 * {@link #LIMIT} ближних. Клиент ведёт по ним тот же звук, что и по сущности, и рисует снаряд вдали, а когда сущность
 * появится, продолжает по ней без шва.
 */
public final class FarFlights {
    /** Снарядов в пакете одному игроку, не больше: ближние (дальние в большом залпе — точки в одном пикселе). */
    static final int LIMIT = 64;

    private FarFlights() {}

    public static void onServerTick(ServerTickEvent.Post e) {
        int tick = e.getServer().getTickCount();
        if (tick % S2C.FarFlights.PERIOD != 0) return;
        boolean silent = tick % S2C.FarFlights.FAR_PERIOD == 0;
        for (ServerLevel level : e.getServer().getAllLevels()) {
            if (level.players().isEmpty()) continue;
            List<StrikeProjectile> flights = flights(level);
            List<Interceptor> interceptors = DefenseWorld.get(level).flights();
            if (flights.isEmpty() && interceptors.isEmpty()) continue;
            for (ServerPlayer player : level.players()) {
                List<S2C.FarFlight> sent = sample(level, flights, player.position(), player, silent);
                sent.addAll(sampleInterceptors(level, interceptors, player.position(), player, silent));
                if (sent.size() > LIMIT) {
                    Vec3 eye = player.position();
                    sent.sort(Comparator.comparingDouble(f -> f.pos().subtract(eye).horizontalDistanceSqr()));
                    sent = sent.subList(0, LIMIT);
                }
                if (!sent.isEmpty()) PacketDistributor.sendToPlayer(player, new S2C.FarFlights(sent));
            }
        }
    }

    /** Все снаряды мира в полёте: в мире и вне его (на пусковой в закрытой ячейке — нет). */
    public static List<StrikeProjectile> flights(ServerLevel level) {
        List<StrikeProjectile> flights = StrikeWorld.projectiles(level);
        flights.removeIf(p -> !p.isActive());
        return flights;
    }

    /** Докуда игрокам видно снаряды (по горизонтали) и взрывы, блоков (настройка мира). */
    public static double range() {
        return IncomingConfig.SERVER.farRange.get();
    }

    /**
     * Что из точки {@code eye} слышно или видно из того, чего у игрока {@code player} нет (без игрока — всё), не больше
     * {@link #LIMIT} ближних.
     *
     * @param silent и те, которых не слышно, а только видно (они уходят реже)
     */
    public static List<S2C.FarFlight> sample(ServerLevel level, List<StrikeProjectile> flights, Vec3 eye, @Nullable ServerPlayer player, boolean silent) {
        double sight = silent ? range() : 0;
        Propagation.Weather weather = ua.zentix.incoming.util.Air.weather(level);
        List<Candidate> found = new ArrayList<>();
        for (StrikeProjectile p : flights) {
            if (player != null && !p.isVirtual() && seenBy(level, p, player)) continue;
            // видно и ближние — по горизонтали, как прорисовку и отслеживание сущностей: высота полёта (B-2 на настоящем
            // эшелоне 12 км при шкале полёта 1) снаряд над головой не прячет
            double d2 = p.position().subtract(eye).horizontalDistanceSqr();
            boolean audible = Hearing.audible(p, eye, weather);
            if (!audible && d2 > sight * sight) continue;
            found.add(new Candidate(p, d2, audible));
        }
        if (found.size() > LIMIT) {
            found.sort(Comparator.comparingDouble(Candidate::d2));
            found = found.subList(0, LIMIT);
        }
        List<S2C.FarFlight> out = new ArrayList<>(found.size());
        for (Candidate c : found) out.add(sample(c.p, c.audible));
        return out;
    }

    /** Дальний путь ЗРК нужен дыму и после ухода сущности из прорисовки; никакой новой загрузки чанков. */
    public static List<S2C.FarFlight> sampleInterceptors(ServerLevel level, List<Interceptor> flights, Vec3 eye,
                                                       @Nullable ServerPlayer player, boolean silent) {
        List<S2C.FarFlight> out = new ArrayList<>();
        if (!silent) return out;
        double reach2 = range() * range();
        for (Interceptor f : flights) {
            if (f.done() || f.position().subtract(eye).horizontalDistanceSqr() > reach2) continue;
            if (player != null && f.view() != null) {
                ChunkMap.TrackedEntity entity = level.getChunkSource().chunkMap.entityMap.get(f.view().getId());
                if (entity != null && entity.seenBy.contains(player.connection)) continue;
            }
            out.add(interceptor(level, f, f.position(), false));
        }
        if (out.size() > LIMIT) {
            out.sort(Comparator.comparingDouble(f -> f.pos().subtract(eye).horizontalDistanceSqr()));
            return new ArrayList<>(out.subList(0, LIMIT));
        }
        return out;
    }

    /** Последнее место пути — до сообщения разрыва: конец следа не экстраполируется за точку встречи. */
    public static void endInterceptor(ServerLevel level, Interceptor f, Vec3 at) {
        S2C.FarFlights packet = new S2C.FarFlights(List.of(interceptor(level, f, at, true)));
        double reach2 = range() * range();
        for (ServerPlayer p : level.players()) {
            if (p.position().subtract(at).horizontalDistanceSqr() <= reach2) PacketDistributor.sendToPlayer(p, packet);
        }
    }

    private static S2C.FarFlight interceptor(ServerLevel level, Interceptor f, Vec3 at, boolean ended) {
        int age = f.age(), boost = InterceptorSpec.SAM.boostTicks();
        boolean burning = age <= boost;
        Vec3 velocity = f.direction().scale(f.speed());
        float[] angles = FlightController.anglesTo(Vec3.ZERO, f.direction());
        Terrain.Surface surface = burning ? Terrain.estimate(level, Heightmap.Types.MOTION_BLOCKING, Mth.floor(at.x), Mth.floor(at.z), Terrain.Allowed.CHUNK) : null;
        float ground = surface != null && surface.source() == Terrain.Source.CHUNK ? surface.y() : Float.NaN;
        return new S2C.FarFlight(f.id(), S2C.FarFlight.INTERCEPTOR, false, false, false, at, velocity, angles[0], angles[1], 0,
                (burning ? FlightPhase.BOOST : FlightPhase.CRUISE).ordinal(), burning ? age : age - boost,
                at.add(velocity.scale(Hearing.MAX_DELAY)), ground, ended);
    }

    private record Candidate(StrikeProjectile p, double d2, boolean audible) {}

    /** Сущность уже есть у этого игрока — её звук и картинку он ведёт сам. */
    private static boolean seenBy(ServerLevel level, StrikeProjectile p, ServerPlayer player) {
        ChunkMap.TrackedEntity tracked = level.getChunkSource().chunkMap.entityMap.get(p.getId());
        return tracked != null && tracked.seenBy.contains(player.connection);
    }

    private static S2C.FarFlight sample(StrikeProjectile p, boolean audible) {
        return new S2C.FarFlight(p.getUUID(), p.weapon().id(), p instanceof BomberEntity, p instanceof BunkerBusterEntity b && b.isDrilling(),
                audible, p.position(), p.velocity(), p.getYRot(), p.getXRot(), p.roll(), p.flightPhase().ordinal(), p.phaseAge(), p.aimPoint(),
                ground(p));
    }

    /**
     * Земля под снарядом на старте (горит ускоритель — облако старта у клиента встаёт на неё): первый воздух по карте высот
     * {@code MOTION_BLOCKING} готового чанка, как у клиента в его чанках; не на старте или чанк не готов — NaN.
     */
    private static float ground(StrikeProjectile p) {
        if (!p.flightPhase().boosterLit()) return Float.NaN;
        Terrain.Surface s = Terrain.estimate(p.level(), Heightmap.Types.MOTION_BLOCKING, Mth.floor(p.getX()), Mth.floor(p.getZ()), Terrain.Allowed.CHUNK);
        return s.source() == Terrain.Source.CHUNK ? s.y() : Float.NaN;
    }
}
