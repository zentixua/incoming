package ua.zentix.incoming.nuclear;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.nuclear.model.ArrivalTable;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.BlackRainModel;
import ua.zentix.incoming.nuclear.model.FalloutModel;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.FrontProfile;
import ua.zentix.incoming.nuclear.model.HobBlast;
import ua.zentix.incoming.nuclear.model.ThermalModel;
import ua.zentix.incoming.util.StreamCodecs;
import ua.zentix.incoming.util.Terrain;

/**
 * One nuclear detonation: everything from which server and each client derive wave, light,
 * fireball, mushroom and fallout using the same model. Stored in the world ({@link NuclearEvents}) and sent to clients in one packet.
 * <p>
 * Scale {@code scale}: 1 block = 1/scale meters (1.0 is real life). Model distances are divided by it,
 * front time multiplied. Distant in-game front travels slower than model as a visible wall at speed {@code wave}, followed by
 * sound at sound speed ({@link FrontProfile}): {@link #arrivalTicks}, {@link #frontRadius}.
 *
 * @param id        detonation number in world (increasing)
 * @param burst     detonation point
 * @param groundY   ground height beneath epicenter
 * @param yieldKt   yield, kt
 * @param surface   surface burst (fireball touches ground): crater and fallout
 * @param gameTime  detonation game time, ticks
 * @param windDir   wind direction, rad (blows toward (cos, sin) in x–z plane)
 * @param windSpeed wind speed, m/s
 * @param visibility visibility, m (clear 20 km, rain 5, thunderstorm 1), for light attenuation
 * @param seed      random-detail seed (identical for all clients)
 * @param fallout   fallout enabled ({@code nuclear.fallout} at detonation): surface-burst plume and
 *                   black-rain radioactivity; old-version saved detonations only for surface bursts
 * @param wave      dust-wall speed far from fireball, fraction of sound speed ({@code nuclear.wave_speed} at detonation)
 * @param dayTime   world time of day at detonation (ticks, as in {@code Level.getDayTime}): fire haze waits for
 *                   morning; {@link #NO_DAY_TIME} means unknown (detonation saved or replayed from an older version)
 * @param fires     server ignites fires for this detonation ({@code nuclear.fires} and {@code fires_per_detonation}
 *                   at detonation): without them client has no firestorm or fire roar; old-version saved or
 *                   replayed detonations default to yes, as before
 */
public record Detonation(int id, Vec3 burst, double groundY, double yieldKt, boolean surface, long gameTime,
                         float windDir, float windSpeed, float visibility, long seed, float scale, boolean fallout, float wave,
                         long dayTime, boolean fires) {

    /** Distant wall speed for detonations saved before {@code wave_speed}: 3 blocks per tick. */
    public static final float OLD_WAVE = (float) (3 / FrontProfile.SOUND_SPEED);
    /** Unknown detonation time of day: saved or recorded in a replay before this field existed. */
    public static final long NO_DAY_TIME = Long.MIN_VALUE;

    /** Detonation without time of day: advance detonation geometry ({@code NuclearWarhead.geometry}) and tests. */
    public Detonation(int id, Vec3 burst, double groundY, double yieldKt, boolean surface, long gameTime,
                      float windDir, float windSpeed, float visibility, long seed, float scale, boolean fallout, float wave) {
        this(id, burst, groundY, yieldKt, surface, gameTime, windDir, windSpeed, visibility, seed, scale, fallout, wave, NO_DAY_TIME);
    }

    /** Detonation with fires. */
    public Detonation(int id, Vec3 burst, double groundY, double yieldKt, boolean surface, long gameTime,
                      float windDir, float windSpeed, float visibility, long seed, float scale, boolean fallout, float wave,
                      long dayTime) {
        this(id, burst, groundY, yieldKt, surface, gameTime, windDir, windSpeed, visibility, seed, scale, fallout, wave, dayTime, true);
    }

    public static final Codec<Detonation> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(Detonation::id),
            Vec3.CODEC.fieldOf("burst").forGetter(Detonation::burst),
            Codec.DOUBLE.fieldOf("ground_y").forGetter(Detonation::groundY),
            Codec.DOUBLE.fieldOf("yield").forGetter(Detonation::yieldKt),
            Codec.BOOL.fieldOf("surface").forGetter(Detonation::surface),
            Codec.LONG.fieldOf("game_time").forGetter(Detonation::gameTime),
            Codec.FLOAT.fieldOf("wind_dir").forGetter(Detonation::windDir),
            Codec.FLOAT.fieldOf("wind_speed").forGetter(Detonation::windSpeed),
            Codec.FLOAT.fieldOf("visibility").forGetter(Detonation::visibility),
            Codec.LONG.fieldOf("seed").forGetter(Detonation::seed),
            Codec.FLOAT.fieldOf("scale").forGetter(Detonation::scale),
            Codec.BOOL.fieldOf("fallout").forGetter(Detonation::fallout),
            Codec.FLOAT.optionalFieldOf("wave", OLD_WAVE).forGetter(Detonation::wave),
            Codec.LONG.optionalFieldOf("day_time", NO_DAY_TIME).forGetter(Detonation::dayTime),
            Codec.BOOL.optionalFieldOf("fires", true).forGetter(Detonation::fires)
    ).apply(i, Detonation::new));

    /**
     * Packet detonation uses release 3.1.0 fields, without {@code wave} ({@link #OLD_WAVE}) or time of day. Format is frozen:
     * Flashback replays store raw packet bytes (replay.md), so a packet appends new detonation fields at its own end
     * ({@code S2C.NukeDetonation}, {@code S2C.NukeSync}), not here: {@code NukeSync} has other detonations following this one.
     */
    public static final StreamCodec<ByteBuf, Detonation> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public Detonation decode(ByteBuf b) {
            return new Detonation(ByteBufCodecs.VAR_INT.decode(b), StreamCodecs.VEC3.decode(b), b.readDouble(), b.readDouble(), b.readBoolean(),
                    b.readLong(), b.readFloat(), b.readFloat(), b.readFloat(), b.readLong(), b.readFloat(), b.readBoolean(), OLD_WAVE);
        }

        @Override
        public void encode(ByteBuf b, Detonation d) {
            ByteBufCodecs.VAR_INT.encode(b, d.id);
            StreamCodecs.VEC3.encode(b, d.burst);
            b.writeDouble(d.groundY);
            b.writeDouble(d.yieldKt);
            b.writeBoolean(d.surface);
            b.writeLong(d.gameTime);
            b.writeFloat(d.windDir);
            b.writeFloat(d.windSpeed);
            b.writeFloat(d.visibility);
            b.writeLong(d.seed);
            b.writeFloat(d.scale);
            b.writeBoolean(d.fallout);
        }
    };

    public Detonation withWave(float wave) {
        return new Detonation(id, burst, groundY, yieldKt, surface, gameTime, windDir, windSpeed, visibility, seed, scale, fallout, wave, dayTime, fires);
    }

    public Detonation withFires(boolean fires) {
        return new Detonation(id, burst, groundY, yieldKt, surface, gameTime, windDir, windSpeed, visibility, seed, scale, fallout, wave, dayTime, fires);
    }

    public Detonation withDayTime(long dayTime) {
        return new Detonation(id, burst, groundY, yieldKt, surface, gameTime, windDir, windSpeed, visibility, seed, scale, fallout, wave, dayTime, fires);
    }

    /** Whether detonation time of day is known ({@link #dayTime}). */
    public boolean hasDayTime() {
        return dayTime != NO_DAY_TIME;
    }

    /** Far-zone threshold: 0.5 psi (glass). */
    public static final double FAR_KPA = BlastModel.kpa(0.5);
    /**
     * Ruin edge: glass (0.8 psi threshold, variation from 0.85) breaks from reflected pressure on the blast-facing face, starting at 0.68 psi
     * reflected, i.e. ~0.34 psi incident (weak-wave reflected pressure is twice incident).
     */
    public static final double RUIN_EDGE_KPA = BlastModel.kpa(0.34);
    /** Below this, wave neither hits nor scares ({@code BlastFront}) nor lofts dust (dust-wall edge): 0.3 psi. */
    public static final double HIT_KPA = BlastModel.kpa(0.3);

    // ---------------------------------------------------------------- scale

    /** Model meters from world blocks. */
    public double metres(double blocks) {
        return blocks / scale;
    }

    public double blocks(double metres) {
        return metres * scale;
    }

    /** Burst height above ground, m. */
    public double hobMetres() {
        return metres(burst.y - groundY);
    }

    // ---------------------------------------------------------------- wave

    /**
     * Wave pressure at a point, kPa, by burst height above it ({@link HobBlast}): ground range from burst axis,
     * height is burst above point. Above burst, treat as ground at slant range (surface-burst hemisphere).
     */
    public double overpressureKpa(Vec3 p) {
        double h = heightAbove(p), s = metres(p.distanceTo(burst));
        return BlastModel.kpa(HobBlast.psi(Math.sqrt(Math.max(0, s * s - h * h)), h, yieldKt));
    }

    public double psi(Vec3 p) {
        return BlastModel.psi(overpressureKpa(p));
    }

    /** Pressure on epicenter ground at {@code groundBlocks} blocks ground range, kPa. */
    public double groundOverpressureKpa(double groundBlocks) {
        return BlastModel.kpa(HobBlast.psi(metres(groundBlocks), groundHob(), yieldKt));
    }

    /** Ground range on epicenter ground where pressure falls to {@code kpa}, blocks (0 if absent even beneath burst). */
    public double groundRange(double kpa) {
        return blocks(HobBlast.rangeFor(BlastModel.psi(kpa), groundHob(), yieldKt));
    }

    /** Slant range to epicenter ground at {@code groundBlocks} blocks ground range, blocks. */
    public double slantOf(double groundBlocks) {
        return Math.hypot(groundBlocks, blocks(groundHob()));
    }

    /** Ground range on epicenter ground for slant range {@code slantBlocks}, blocks (0 below burst height). */
    public double groundOf(double slantBlocks) {
        double h = blocks(groundHob());
        return Math.sqrt(Math.max(0, slantBlocks * slantBlocks - h * h));
    }

    /**
     * Post-front along-ground dynamic pressure at a point, kPa: from pressure (Rankine–Hugoniot); for regular reflection beneath
     * burst, only its along-ground share ({@link HobBlast#alongGround}).
     */
    public double dynamicPressureKpa(Vec3 p) {
        return BlastModel.dynamicPressureKpa(overpressureKpa(p)) * along(p);
    }

    /** Along-ground wind immediately behind front at a point, m/s. */
    public double blastWind(Vec3 p) {
        return BlastModel.windSpeed(overpressureKpa(p)) * Math.sqrt(along(p));
    }

    /** Along-ground wind immediately behind front on epicenter ground at {@code groundBlocks} blocks ground range, m/s. */
    public double groundWind(double groundBlocks) {
        double g = metres(groundBlocks);
        return BlastModel.windSpeed(groundOverpressureKpa(groundBlocks)) * Math.sqrt(HobBlast.alongGround(g, groundHob(), yieldKt));
    }

    /** Positive phase at a point, model s: Brode from 2 psi, Kinney–Graham at listener below 1 psi, blended between. */
    public double positivePhaseSeconds(Vec3 p) {
        double h = heightAbove(p), s = metres(p.distanceTo(burst));
        return HobBlast.positivePhaseSeconds(Math.sqrt(Math.max(0, s * s - h * h)), h, yieldKt);
    }

    /** Positive phase on epicenter ground at {@code groundBlocks} blocks ground range, model s. */
    public double groundPositivePhaseSeconds(double groundBlocks) {
        return HobBlast.positivePhaseSeconds(metres(groundBlocks), groundHob(), yieldKt);
    }

    /** Positive phase on ground at slant range {@code slantBlocks} from burst, game ticks. */
    public double positivePhaseTicks(double slantBlocks) {
        return groundPositivePhaseSeconds(groundOf(slantBlocks)) * 20 * scale;
    }

    /**
     * Range (slant, blocks) where positive phase has ended by {@code ticks} after burst: front arrival
     * plus following phase no later than {@code ticks}.
     */
    public double phaseEndRadius(double ticks) {
        double lo = 0, hi = frontRadius(ticks);
        if (hi <= 0 || arrivalTicks(lo) + positivePhaseTicks(lo) > ticks) return 0;
        for (int i = 0; i < 40; i++) {
            double m = 0.5 * (lo + hi);
            if (arrivalTicks(m) + positivePhaseTicks(m) <= ticks) lo = m;
            else hi = m;
        }
        return lo;
    }

    private double along(Vec3 p) {
        double h = heightAbove(p), s = metres(p.distanceTo(burst));
        return HobBlast.alongGround(Math.sqrt(Math.max(0, s * s - h * h)), h, yieldKt);
    }

    /** Burst height above point, m (at least 0). */
    private double heightAbove(Vec3 p) {
        return Math.max(0, metres(burst.y - p.y));
    }

    /** Burst height above epicenter ground, m (at least 0). */
    private double groundHob() {
        return Math.max(0, hobMetres());
    }

    /**
     * Speed at which wave dynamic pressure {@code qKpa} throws a detached fragment (client removed pieces),
     * blocks per tick, at most 6. {@code BlastBody} throws bodies by body drag.
     */
    public static double throwSpeed(double qKpa) {
        return Math.min(6.0, 0.8 * Math.sqrt(qKpa));
    }

    /** Front-arrival table (per-detonation cache). */
    public ArrivalTable arrival() {
        return Caches.arrival(this);
    }

    /** In-game front arrival (per-detonation cache): model near epicenter, then speed {@code wave} ({@link FrontProfile}). */
    public FrontProfile front() {
        return Caches.front(this);
    }

    /** Ticks after burst until front reaches a point at distance {@code blocks}. */
    public double arrivalTicks(double blocks) {
        return front().arrivalTicks(blocks);
    }

    /** In-game light pulse, ticks after burst ({@link FireballModel#pulseSeconds} at strike scale, like client flash). */
    public double pulseTicks() {
        return FireballModel.pulseSeconds(yieldKt) * 20 * scale;
    }

    /**
     * When a place receiving {@code q} cal/cm² over full pulse accumulates {@code need}, ticks after burst: from
     * emitted pulse fraction ({@link FireballModel#thermalFraction}). If not reached over full pulse, use pulse end.
     * Controls server ground light trace and fires ({@code FlashScorch}) and their client start.
     */
    public double flashTicks(double q, double need) {
        return FireballModel.thermalTau(q <= need ? 1 : need / q) / 10 * pulseTicks();
    }

    /** Front radius (blocks) at {@code ticks} after burst. */
    public double frontRadius(double ticks) {
        return front().radiusAt(ticks);
    }

    /** Ground reach of any change (blocks): glass or 1st-degree burns, whichever reaches farther. */
    public double radiusMax() {
        return Caches.radiusMax(this);
    }

    /**
     * Reach where wave still hits ({@link #HIT_KPA}), slant range, blocks: on ground and above. An elevated point is a ground
     * point with lowered burst ({@link #overpressureKpa}), so maximum reach spans heights from ground to burst:
     * for high bursts, pressure above ground reaches farther than at ground, where it may be absent even directly beneath burst.
     */
    public double blastReach() {
        return Caches.blastReach(this);
    }

    /**
     * Ground ruin reach (blocks): to {@link #RUIN_EDGE_KPA}, where reflected pressure still breaks glass, no closer than
     * {@link #radiusMax}. At most 1.5 {@link #radiusMax} (0.34 psi versus 0.5), the front-arrival table range.
     */
    public double ruinRadius() {
        return Caches.ruinRadius(this);
    }

    // ---------------------------------------------------------------- light and fireball

    /** Light pulse at point, cal/cm² (without shadow, checked by caller). */
    public double fluence(Vec3 p) {
        return ThermalModel.fluenceCalPerCm2(Math.max(1, metres(p.distanceTo(burst))), yieldKt, surface, visibility);
    }

    /** Maximum fireball radius, blocks. */
    public double fireballRadius() {
        return blocks(FireballModel.maxRadius(yieldKt, surface));
    }

    // ---------------------------------------------------------------- fallout

    public FalloutModel falloutModel() {
        return new FalloutModel(yieldKt, hobMetres(), windDir, windSpeed);
    }

    /** Black rain from this detonation's fires (no fires, no rain). */
    public BlackRainModel blackRainModel() {
        return Caches.blackRain(this);
    }

    /**
     * Whether detonation has radioactive fallout (enabled at detonation): surface-burst plume or matter washed to ground by
     * black rain.
     */
    public boolean hasFallout() {
        return fallout && (falloutModel().surfaceFraction() > 0 || blackRainModel().fireRadiusM() > 0);
    }

    /**
     * Fallout dose rate at point, R/h: surface-burst plume and black-rain washout, all by game clock.
     *
     * @param ticksSince game ticks after burst (1000 ticks = 1 hour)
     */
    public double falloutRate(double x, double z, long ticksSince) {
        if (!hasFallout()) return 0;
        double hours = ticksSince / 1000.0, mx = metres(x - burst.x), mz = metres(z - burst.z);
        double rain = blackRainModel().depositH1(mx, mz, ticksSince / 20.0);
        double decay = Math.pow(Math.max(hours, FalloutModel.MIN_DECAY_HOURS), -FalloutModel.DECAY_EXP);
        return falloutModel().doseRate(mx, mz, hours) + rain * decay;
    }

    /** Game ticks after burst until surface-burst plume deposits at point. */
    public double falloutArrivalTicks(double x, double z) {
        return falloutModel().arrivalHours(metres(x - burst.x), metres(z - burst.z)) * 1000;
    }

    /** Whether black rain falls at point (same on server and client): firestorm clock, tick seconds. */
    public boolean blackRain(double x, double z, long ticksSince) {
        return blackRainModel().raining(metres(x - burst.x), metres(z - burst.z), ticksSince / 20.0);
    }

    /** Game ticks after burst until black rain ends everywhere. */
    public double blackRainEndTicks() {
        return blackRainModel().endSeconds() * 20;
    }

    /**
     * Whether point is open to sky (receives rain): nothing motion-blocking above it, neither roof,
     * foliage nor water. Heightmap exists on both server and client, so they give the same answer.
     */
    public static boolean underOpenSky(net.minecraft.world.level.Level level, Vec3 pos) {
        return Terrain.height(level, net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                net.minecraft.util.Mth.floor(pos.x), net.minecraft.util.Mth.floor(pos.z)) <= pos.y;
    }

    /** Caches expensive to compute per call (arrival table, maximum radius). */
    private static final class Caches {
        private static final java.util.Map<Detonation, ArrivalTable> ARRIVAL = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
        private static final java.util.Map<Detonation, FrontProfile> FRONT = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
        private static final java.util.Map<Detonation, Double> RADIUS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
        private static final java.util.Map<Detonation, Double> RUINS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
        private static final java.util.Map<Detonation, Double> REACH = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
        /** Height samples between ground and burst for {@link #blastReach}. */
        private static final int REACH_HEIGHTS = 32;
        private static final java.util.Map<Detonation, BlackRainModel> RAIN = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

        static double ruinRadius(Detonation d) {
            return RUINS.computeIfAbsent(d, x -> Math.min(x.radiusMax() * 1.5, Math.max(x.radiusMax(), x.groundRange(RUIN_EDGE_KPA))));
        }

        static ArrivalTable arrival(Detonation d) {
            return ARRIVAL.computeIfAbsent(d, x -> ArrivalTable.of(x.yieldKt, x.groundHob(), Math.max(1000, x.metres(tableRange(x)))));
        }

        static FrontProfile front(Detonation d) {
            // model near epicenter: flash and fireball unchanged; beyond dust wall (0.3 psi), only sound
            return FRONT.computeIfAbsent(d, x -> FrontProfile.of(x.arrival(), x.scale, FrontProfile.slowFrom(x.fireballRadius()),
                    x.slantOf(x.groundRange(HIT_KPA)), tableRange(x), x.wave * FrontProfile.SOUND_SPEED));
        }

        /** Front-arrival table range, blocks: ruins (to 1.5 {@link #radiusMax}) and everything the wave still hits. */
        private static double tableRange(Detonation d) {
            return Math.max(d.radiusMax() * 1.5, d.blastReach());
        }

        static double blastReach(Detonation d) {
            return REACH.computeIfAbsent(d, x -> {
                double hob = x.groundHob(), psi = BlastModel.psi(HIT_KPA), m = 0;
                for (int i = 0; i <= REACH_HEIGHTS; i++) {
                    double h = hob * i / REACH_HEIGHTS, g = HobBlast.rangeFor(psi, h, x.yieldKt);
                    if (g > 0) m = Math.max(m, Math.hypot(g, h));
                }
                return x.blocks(m);
            });
        }

        static BlackRainModel blackRain(Detonation d) {
            return RAIN.computeIfAbsent(d, x -> {
                // fire zone where light ignites (as for ruins and client firestorm); absent if server does not ignite fires
                double hob = Math.max(0, x.hobMetres());
                double slant = ThermalModel.rangeForFluence(ThermalModel.IGNITE, x.yieldKt, x.surface, x.visibility);
                double fire = x.fires ? Math.sqrt(Math.max(0, slant * slant - hob * hob)) : 0;
                return new BlackRainModel(x.yieldKt, fire, x.windDir, x.windSpeed, 1 - x.falloutModel().surfaceFraction());
            });
        }

        static double radiusMax(Detonation d) {
            return RADIUS.computeIfAbsent(d, x -> {
                double light = ThermalModel.rangeForFluence(ThermalModel.BURN_1, x.yieldKt, x.surface, x.visibility);
                return Math.max(x.groundRange(FAR_KPA), x.groundOf(x.blocks(light)));
            });
        }
    }
}
