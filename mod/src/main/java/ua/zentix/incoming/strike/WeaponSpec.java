package ua.zentix.incoming.strike;

import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.LauncherRack;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.guidance.Ballistics;
import ua.zentix.incoming.guidance.BombDrop;
import ua.zentix.incoming.guidance.FlightController;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.sound.Spectra;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.util.StandardAtmosphere;

import java.util.SplittableRandom;
import java.util.function.Supplier;

/**
 * Weapon spec: everything distinguishing weapon types in one description per type ({@link WeaponType#spec()}).
 * Launch, explosion, siren, hearing, launcher, projectiles read values here rather than keeping copies and
 * weapon-type {@code switch} statements. Client portion (sound layers, models) is {@code
 * client.ClientWeaponSpec}; server loads no client classes.
 * <p>
 * Guidance laws ({@code guidance/}) and entity flight phases live elsewhere: spec supplies speeds, turn limits,
 * heights; they decide how to fly.
 *
 * @param siren target warning type
 * @param sirenSeconds seconds before impact when target “sees” projectile and warning starts (ICBM uses its own
 * countdown)
 * @param salvo launch gaps and designator defaults on weapon selection
 * @param warhead nuclear warhead: always, optional, never
 * @param launch launch method ({@link StrikeService#launch})
 * @param route target path: flight time, final straight leg, launcher standoff
 * @param tracking whether follows a moving target sighted by player ({@code target.Target.Sighted})
 * @param rack launcher rack near shooter; null means no player-side launcher start
 * @param blast ground explosion visuals and secondary detonations ({@code Warheads})
 * @param penetrates strikes at point depth (bomb seeks cave beneath target), rather than surface
 * @param confirmPitch designator click pitch on accepted launch
 * @param airframe weapon flight entity (B-2 bomber itself)
 * @param payload second flight entity: carrier-released bomb; null if absent
 * @param munition item launch deducts from shooter inventory ({@link Munitions})
 * @param ascent thrust/mass rocket-stage boost (ICBM); null if absent
 */
public record WeaponSpec(WeaponType.SirenKind siren, int sirenSeconds, Salvo salvo, Warhead warhead,
                         Launch launch, Route route, Tracking tracking, @Nullable LauncherRack rack,
                         Blast blast, boolean penetrates, float confirmPitch, Airframe airframe, @Nullable Airframe payload,
                         Munition munition, @Nullable Ascent ascent) {

    /** How weapon launches. */
    public enum Launch {
        /** Shahed/cruise missile: nearby launcher with detour route, otherwise distant approach. */
        GUIDED,
        /** MLRS rocket from rack tube, ballistic. */
        ROCKET,
        /** Catapult loitering munition: straight to target, orbit above it. */
        LOITER,
        /** B-2 approaches from afar and drops bomb. */
        BOMBER,
        /** ICBM: scheduled nuclear strike ({@code NuclearStrikes}). */
        ICBM
    }

    /**
     * How weapon strikes a moving entity/craft selected while visible ({@code target.Target.Sighted}).
     * Unrestricted orders (console, command block, game master, operator command) unaffected: projectile follows
     * target there.
     */
    public enum Tracking {
        /**
         * Follows target while operator holds it in this projectile camera frame ({@link CameraLink}); otherwise
         * flies to last-seen position. One operator camera means one guided projectile.
         */
        CAMERA,
        /** Strikes target's order-time position without following. */
        POINT
    }

    /**
     * Salvo.
     *
     * @param gapMin launch gap ticks, from
     * @param gapMax to
     * @param count designator salvo count on weapon selection instead of single shot (1 keeps current)
     * @param spread minimum spread, blocks
     * @param lanes salvo route lanes ({@link Lanes}); {@link Lanes#NONE} means weapon has no route
     */
    public record Salvo(int gapMin, int gapMax, int count, int spread, Lanes lanes) {
        static Salvo gaps(int gapMin, int gapMax) {
            return new Salvo(gapMin, gapMax, 1, 0, Lanes.NONE);
        }

        /** This salvo with route lanes. */
        Salvo inLanes(double halfWidth, double exit, double converge) {
            return new Salvo(gapMin, gapMax, count, spread, new Lanes(halfWidth, exit, converge));
        }
    }

    /**
     * Salvo route lanes (game model, not physical): one salvo flies as a wave rather than a thread on one path;
     * each has a lateral lane beside launch-generated/operator route ({@code guidance.Route#inLane}). Enter over
     * {@code exit} path blocks from route start; converge toward own aim point over final {@code converge}
     * blocks. Width is comparable to neighboring projectile spacing (salvo gap × cruise speed), making a wave in
     * sky rather than a chain; projectiles enter SAM area from different sides and over different terrain folds.
     * Lane entry spans several turn radii; convergence spans spec final straight leg, preserving target approach
     * and accuracy ({@link Route#miss}). Lengths grow with cruise ({@link Airframe#stretch}), like projectile
     * spacing.
     *
     * @param halfWidth maximum lane offset on either side of path, blocks
     * @param exit path blocks from route start to enter lane
     * @param converge path blocks before target over which lanes converge
     */
    public record Lanes(double halfWidth, double exit, double converge) {
        public static final Lanes NONE = new Lanes(0, 0, 0);

        /** Same lanes with lengths × {@code k}. */
        public Lanes scaled(double k) {
            return k == 1 ? this : new Lanes(halfWidth * k, exit * k, converge * k);
        }

        /**
         * Lateral shift of projectile {@code round} (0-based launch order) of {@code total} salvo rounds, blocks
         * (sign gives side, like {@code side} in {@code guidance.Route#plan}): across path, {@code total} equal
         * lanes of width 2·{@code halfWidth}/{@code total}, one each, in salvo-seed-shuffled order (otherwise
         * first projectiles would come from one edge, turning wave into diagonal chain). Within lane, seeded
         * position in middle half, so neighbors stay at least half a lane apart. Same seed, same lanes.
         * One-projectile salvo follows path itself.
         */
        public double offset(long seed, int round, int total) {
            if (total <= 1 || halfWidth <= 0 || round < 0 || round >= total) return 0;
            int[] order = new int[total];
            for (int i = 0; i < total; i++) order[i] = i;
            SplittableRandom shuffle = new SplittableRandom(seed);
            for (int i = total - 1; i > 0; i--) {
                int j = shuffle.nextInt(i + 1);
                int t = order[i];
                order[i] = order[j];
                order[j] = t;
            }
            double u = new SplittableRandom(seed ^ (round + 1) * 0x9E3779B97F4A7C15L).nextDouble();
            return halfWidth * (2 * (order[round] + 0.25 + 0.5 * u) / total - 1);
        }
    }

    /**
     * Nuclear warhead.
     *
     * @param always always nuclear (ICBM munition already nuclear)
     * @param optional may replace conventional warhead ({@code Loadout.Nuke#onCarrier}: missile, B-2 bomb);
     * launch also deducts nuclear warhead ({@link Munitions#warhead()}, shared item for all carriers)
     * @param chooseBurst air/surface burst choice (bomb detonates underground, no choice)
     */
    public record Warhead(boolean always, boolean optional, boolean chooseBurst) {
        static final Warhead CONVENTIONAL = new Warhead(false, false, false);
    }

    /**
     * Munition deducted from shooter inventory ({@link Munitions}), one item per projectile or pack.
     *
     * @param item item supplier: spec does not touch registry until queried
     * @param rounds projectiles per item; above one means a pack opened/spent round by round
     */
    public record Munition(Supplier<? extends Item> item, int rounds) {
        static Munition single(Supplier<? extends Item> item) {
            return new Munition(item, 1);
        }
    }

    /** Warhead ground explosion: client visuals ({@code S2C.Blast}) and effects beyond main detonation. */
    public enum Blast {
        /** Shahed explosion: main detonation and fireball. */
        DRONE,
        /** Cruise missile explosion: stronger fireball, fire ring, secondary detonations, own debris. */
        MISSILE,
        /** MLRS rocket explosion. */
        ROCKET,
        /** No warhead ground explosion: bomb underground ({@code BunkerBlast}), ICBM nuclear detonation. */
        NONE
    }

    /**
     * Path to target.
     *
     * Speed-derived lengths (leg, standoff, point capture) are game values at flight scale 0; scaled values via
     * spec ({@link WeaponSpec#finalLeg}, {@link WeaponSpec#standoff}, {@link WeaponSpec#capture}).
     *
     * @param flightSeconds launch-to-target seconds (world setting): route length = speed × time; null means
     * flight time follows range
     * @param gameFinalLeg final straight leg, blocks (B-2 release range at game altitude: release angle to point,
     * {@code entity.BomberEntity#releaseLine})
     * @param gameStandoff without nearby shooter, launcher stands this far from target along approach direction
     * @param error guidance error: per-axis miss standard deviation, blocks; each launch aims at randomly shifted
     * target (satellite navigation, altitude release); 0 exact
     * @param gameCapture waypoint captured within this horizontal distance (or already behind, {@code
     * guidance.Route#update}); about turning radius, closer would circle it
     * @param reach operator route reach ({@link Waypoints}): path from operator through points to target no
     * longer than this, blocks; 0 means no operator waypoints
     */
    public record Route(@Nullable Supplier<ModConfigSpec.IntValue> flightSeconds, double gameFinalLeg, double gameStandoff,
                        double error, double gameCapture, double reach) {
        /** Configured flight time, seconds. */
        public int seconds() {
            return flightSeconds == null ? 0 : flightSeconds.get().get();
        }

        /** Flies operator waypoints ({@link Waypoints}). */
        public boolean waypoints() {
            return reach > 0;
        }

        /** This launch's miss ({@link #error}): horizontal offset. */
        public Vec3 miss(RandomSource random) {
            return error > 0 ? new Vec3(random.nextGaussian() * error, 0, random.nextGaussian() * error) : Vec3.ZERO;
        }
    }

    /**
     * Launcher start: burn on rail, booster duration, acceleration, and nose pitch by boost end.
     *
     * @param ignitionTicks booster burns while projectile stationary
     * @param boostTicks booster burn after launch rail exit
     * @param boostAccel speed gain per tick, blocks/tick²
     * @param railTicks first boost ticks holding rail angle
     * @param boostEndPitch pitch at boost end (°, < 0 nose up)
     */
    public record LaunchProfile(int ignitionTicks, int boostTicks, double boostAccel, int railTicks, float boostEndPitch) {
        /**
         * Boost tick {@code tick} (1 through {@code boostTicks}): thrust rises gradually, first ticks barely
         * slide off rail; after launch rail exit, nose moves toward boost-end pitch. Same law for flying
         * projectile and sector check ({@code LaunchSite.clearAhead}).
         *
         * @return speed after tick
         */
        public double boost(FlightController flight, double speed, int tick) {
            if (tick > railTicks) flight.holdPitch(boostEndPitch, 0.06, 1.2, 0.12);
            return speed + boostAccel * Math.min(1, (tick + 1) / 6.0);
        }
    }

    /**
     * Vertical-launch solid stage boost: constant thrust, mass declines with propellant burn, so acceleration
     * rises: a(t) = T / (m₀ − ṁ·t) − g, ṁ = propellant / burn time. Ignores air drag and altitude thrust
     * increase; subtract full g as for vertical ascent: within {@code 90 − climbPitch} of vertical, error under
     * 2% of acceleration. Simplified pitch, no public pitch program: strictly axial until {@code pitchOverTick}
     * (tail clears silo), then smoothly tilt toward target heading to {@code climbPitch}.
     *
     * @param thrust thrust, N
     * @param launchMass rocket launch mass, kg
     * @param propellant stage propellant, kg
     * @param burnSeconds stage burn time, s
     * @param pitchOverTick tick when nose starts tilting toward target heading
     * @param climbPitch ascent angle above horizon after tilt, °
     * @param ceiling blocks above world ceiling after which rocket unnecessary (strike flies on)
     */
    public record Ascent(double thrust, double launchMass, double propellant, double burnSeconds, int pitchOverTick, float climbPitch,
                         int ceiling) {
        /** Gravitational acceleration, m/s². */
        static final double G = 9.81;

        /** Propellant flow, kg/s. */
        public double flow() {
            return propellant / burnSeconds;
        }

        /** Exhaust velocity T / ṁ, m/s. */
        public double exhaust() {
            return thrust / flow();
        }

        /** Net axial acceleration {@code seconds} s after launch, m/s² (after burnout, use end value). */
        public double accel(double seconds) {
            double t = Math.min(Math.max(seconds, 0), burnSeconds);
            return thrust / (launchMass - propellant / burnSeconds * t) - G;
        }

        /**
         * Speed after boost tick {@code tick} (1 is first after launch), blocks/tick: gain from tick-start
         * acceleration, m/s² × 0.05² (block = meter, tick = 0.05 s).
         */
        public double step(double speed, int tick) {
            return speed + accel((tick - 1) / 20.0) / 400;
        }
    }

    /**
     * Airframe: values distinguishing projectile entities.
     *
     * Speeds, heights, turns, acceleration here are game values, not physical: flight is our own game model
     * (Artem, 21.10), slower/lower than real so approach is visible over hundreds of game blocks; real values in
     * article {@link Article#real}. World flight scale ({@link FlightScale}, Artem, 22.10) takes speeds/heights
     * toward real ({@link #cruiseSpeed()}, {@link #diveSpeed()}, {@link #cruiseHeight()}, drag {@link #drag()});
     * speed-derived lengths (range, approach strip, attack geometry) grow with cruise ({@link #stretch});
     * turns/climb remain game-based. Terrain lookahead ({@link #reliefLookahead}) does not grow: climb angle same
     * at any speed, so path to clear a hill is unchanged.
     *
     * @param entity entity type supplier: spec touches registry only when queried
     * @param gameCruise cruise speed, blocks/tick (game, not physical), for approach time
     * @param gameDive maximum speed (cruise if no dive), blocks/tick (game, not physical): neither dive nor
     * altitude/speed exchange ({@link #airspeed}) exceeds it
     * @param handling powered-projectile turns, climb, acceleration ({@link Handling}); null if no
     * engine/controls
     * @param gameHeight cruise height, blocks (game, not physical): above launch/target for Shahed, target orbit
     * for Lancet, terrain-hugging for missile, release point for B-2
     * @param clearance minimum terrain clearance on materialization
     * @param reliefLookahead terrain strip lookahead in path blocks ({@code guidance.Autopilot.reliefAhead}:
     * climb slope for Shahed/missile, straight heading without slope for Lancet); 0 none
     * @param noseLength half body length, center to nose, blocks
     * @param health damage before shot down; 0 invulnerable
     * @param gameRange range, blocks, if not assigned by flight plan ({@code guidance.Mission})
     * @param reachPad proximity fuze range margin beyond per-tick step, blocks
     * @param launchProfile launcher start; null if none (or own launch like MLRS)
     * @param gameVisibleLeg final blocks flown in-world when player at target ({@link FlightTickets#approach}); 0
     * target area only
     * @param gameAttack guided-projectile attack geometry
     * @param noise noise sources: engines, body, launch ({@link Noise}); audible range via {@link Hearing}
     * @param radar how SAM sees/engages it ({@code defense.Radar})
     * @param article real article specifications: mass, dimensions, engines, real speeds, impact conditions,
     * warhead
     * @param ballistic unpowered flight: gravity, drag, wind ({@link Ballistic}); null means powered
     */
    public record Airframe(Supplier<EntityType<? extends StrikeProjectile>> entity, double gameCruise, double gameDive,
                           @Nullable Handling handling, double gameHeight, double clearance, double reliefLookahead, double noseLength,
                           float health, double gameRange, double reachPad, @Nullable LaunchProfile launchProfile, double gameVisibleLeg,
                           Attack gameAttack, Noise noise, Signature radar, Article article, @Nullable Ballistic ballistic) {
        /**
         * Launch-booster thrust over time ({@link ThrustCurve}: flame, light, launch cloud, sound); null if no
         * jettisoned-booster launcher start.
         */
        @Nullable
        public ThrustCurve thrust() {
            return launchProfile == null || article.booster() == null ? null : ThrustCurve.of(launchProfile, article.booster());
        }

        /** Cruise speed on world flight scale, blocks/tick ({@link #cruiseSpeed(double)}). */
        public double cruiseSpeed() {
            return cruiseSpeed(FlightScale.x());
        }

        /** Cruise speed at {@code x}, blocks/tick: game to real ({@link Article.Envelope#cruise}). */
        public double cruiseSpeed(double x) {
            return FlightScale.blend(gameCruise, article.real().cruise() / 20, x);
        }

        /** Maximum speed on world flight scale, blocks/tick ({@link #diveSpeed(double)}). */
        public double diveSpeed() {
            return diveSpeed(FlightScale.x());
        }

        /** Maximum speed at {@code x}, blocks/tick: game to real dive ({@link Article.Envelope#dive}). */
        public double diveSpeed(double x) {
            return FlightScale.blend(gameDive, article.real().dive() / 20, x);
        }

        /** Cruise height on world flight scale, blocks ({@link #cruiseHeight(double)}). */
        public double cruiseHeight() {
            return cruiseHeight(FlightScale.x());
        }

        /**
         * Cruise height at {@code x}, blocks: game to real ({@link Article.Envelope#altitude}: Shahed 2 km,
         * missile 40 m, Lancet 700 m, B-2 release 12 km); absent means game height.
         */
        public double cruiseHeight(double x) {
            return FlightScale.blend(gameHeight, article.real().altitude(), x);
        }

        /**
         * Cruise speed at {@code x} divided by game cruise: speed-derived lengths with game turns/time (turn
         * radius v/ω, distance over time) grow equally. At 0 exactly 1.
         */
        public double stretch(double x) {
            return x == 0 ? 1 : cruiseSpeed(x) / gameCruise;
        }

        /** Range on world flight scale, blocks: same ticks at cruise. */
        public double range() {
            return gameRange * stretch(FlightScale.x());
        }

        /** In-world approach strip on world flight scale, blocks ({@link #visibleLeg(double)}). */
        public double visibleLeg() {
            return visibleLeg(FlightScale.x());
        }

        /** In-world approach strip at {@code x}, blocks: same approach ticks, containing missile popup. */
        public double visibleLeg(double x) {
            return gameVisibleLeg * stretch(x);
        }

        /** Attack geometry on world flight scale ({@link #attack(double)}). */
        public Attack attack() {
            return attack(FlightScale.x());
        }

        /** Attack geometry at {@code x}: lengths from turning radius and transition distance, growing with cruise. */
        public Attack attack(double x) {
            double k = stretch(x);
            return k == 1 ? gameAttack : new Attack(gameAttack.reattackMin() * k, gameAttack.terminalRange() * k, gameAttack.popUpMinRange() * k);
        }

        /**
         * Unpowered drag on world flight scale ({@link #drag(double)}); null means powered.
         */
        @Nullable
        public Ballistics.Drag drag() {
            return drag(FlightScale.x());
        }

        /**
         * Drag at {@code x}: coefficient blends game ({@link Ballistic#drag}) to real article ρ₀·C<sub>d</sub>A /
         * 2m (dry mass: Grad after burnout, bomb); f(M) curve in same proportion. Without Mach drag, scale-1 game
         * trajectory would not become real: Grad too far, bomb faster than spec. null means powered.
         */
        @Nullable
        public Ballistics.Drag drag(double x) {
            if (ballistic == null) return null;
            double real = StandardAtmosphere.density(0) * article.cda() / (2 * article.dryMass());
            return new Ballistics.Drag(FlightScale.blend(ballistic.drag(), real, x), x);
        }

        /** Maximum route/attack yaw rate, °/tick (0 unguided). */
        public double turnRate() {
            return handling == null ? 0 : handling.cruise().turnRate();
        }

        /** Same during post-launch climb. */
        public double climbTurnRate() {
            return handling == null ? 0 : handling.climb().turnRate();
        }

        /** Turn radius at {@code speed} with route turn limit ({@link #turnRadius(double, double)}). */
        public double turnRadius(double speed) {
            return WeaponSpec.turnRadius(speed, turnRate());
        }

        /**
         * Post-tick speed ({@link Handling#speed}): engine pulls toward {@code target}, altitude exchanged for
         * speed; capped at {@link #diveSpeed}.
         *
         * @param climbSine sine of flight-path angle to horizon this tick (> 0 up)
         */
        public double airspeed(double speed, double target, double climbSine) {
            return Math.min(diveSpeed(), handling.speed(speed, target, climbSine));
        }
    }

    /**
     * Turns for one flight leg (game values, not physical).
     *
     * @param turnRate maximum yaw rate, °/tick
     * @param turnAccel turn buildup/decay, °/tick²: inertia gives smooth turn entry/exit; model bank follows turn
     * rate ({@code FlightController.bankAngle})
     * @param pitchRate maximum pitch rate, °/tick
     * @param pitchAccel pitch rate buildup, °/tick²
     */
    public record Agility(double turnRate, double turnAccel, double pitchRate, double pitchAccel) {}

    /**
     * Our own powered-projectile game flight model (Artem, 21.10): shared laws for Shahed, missile, Lancet, B-2,
     * individual game values, not physical (chosen for blocks and visible flight; real articles do not fly 1:1).
     * Smooth speed, turns, climb: no instantaneous turns or speed jumps.
     *
     * @param climb post-launch climb turns (low speed, gentler)
     * @param cruise route/popup turns
     * @param dive dive turns
     * @param climbAngle maximum climb angle, ° (climb rate: v · sin angle)
     * @param descentAngle maximum route descent angle, °
     * @param accel maximum engine speed gain, blocks/tick²
     * @param relax fraction of desired-speed difference closed by engine/drag each tick (1/tick): below desired,
     * engine raises speed no faster than {@code accel}; above, drag removes excess
     */
    public record Handling(Agility climb, Agility cruise, Agility dive, double climbAngle, double descentAngle, double accel, double relax) {
        /**
         * Altitude/speed exchange, game coefficient, not physical: Δv = −{@value} · g · sin γ per tick (γ
         * flight-path angle). Energy conservation would give 1 · g; game speeds and engine recovering in a second
         * or two hide this, so exchange is 2.5 times stronger: Shahed loses ~15% climbing at 15°, accelerates
         * twice as fast diving as by engine alone.
         */
        public static final double TRADE = 2.5;

        /**
         * Post-tick speed: engine/drag pull toward {@code target} (up no faster than {@link #accel}), climb
         * reduces speed, descent adds ({@link #TRADE}); speed then returns naturally to desired.
         *
         * @param climbSine sine of flight-path angle to horizon (> 0 up)
         */
        public double speed(double speed, double target, double climbSine) {
            return speed(speed, target, climbSine, accel, relax);
        }

        /**
         * Same law with engine {@code accel} (maximum gain per tick) and {@code relax} (desired-speed difference
         * fraction per tick): shared by all powered flight, including SAM missile ({@code
         * defense.InterceptorSpec}).
         */
        public static double speed(double speed, double target, double climbSine, double accel, double relax) {
            double engine = (target - speed) * relax;
            return Math.max(0, speed + Math.min(accel, engine) - TRADE * ua.zentix.incoming.guidance.Ballistics.GRAVITY * climbSine);
        }
    }

    /**
     * Unpowered flight ({@code guidance.Ballistics}): Grad after burnout, falling bomb. Real gravity, quadratic
     * air-relative drag (world wind, {@code util.Air}) with standard-atmosphere altitude density; coefficient is
     * game-based, not physical: real article coefficient (ρ₀·C<sub>d</sub>A / 2m, {@link Article}) is barely
     * visible at game speeds/ranges; similarity-scaled coefficient (game/real length scale) would restrict Grad
     * to a few kilometers and cause wind drift of tens of percent. Chosen to make trajectory legible (descent
     * steeper than ascent, wind drift) without exhausting game range.
     *
     * @param drag sea-level drag coefficient, 1/block: acceleration = drag · ρ/ρ₀ · |v|·v (v air-relative,
     * blocks/tick); terminal fall speed √(g/drag)
     * @param spreadAngle launch rail exit direction standard deviation, ° (game salvo dispersion)
     * @param spreadSpeed launch rail exit speed standard deviation, fraction
     */
    public record Ballistic(double drag, double spreadAngle, double spreadSpeed) {
        /**
         * Wind drift unknown to fire solution (stronger aloft than at ground), fraction of range (upper
         * estimate).
         */
        static final double UNKNOWN_WIND = 0.01;

        /**
         * Impact-point per-axis standard deviation at {@code range} blocks, upper estimate: exit direction/speed
         * spread (high-arc range varies by roughly speed fraction) plus wind drift ({@link #UNKNOWN_WIND}); at
         * least one block, 0 no spread.
         */
        public double sigma(double range) {
            return spreadAngle == 0 && spreadSpeed == 0 ? 0 : Math.max(1, range * (Math.toRadians(spreadAngle) + spreadSpeed + UNKNOWN_WIND));
        }
    }

    /**
     * Projectile on SAM radar ({@code defense.Radar}): visibility range and whether worth a missile.
     *
     * @param visibility fraction of radar range: 1 full, 0.15 low-signature B-2 close only; terrain-hugging
     * cruise missile/small Lancet seen closer than Shahed
     * @param intercept SAM spends interceptor: Shahed, missile, Lancet, B-2. ICBM not a SAM target: mod air
     * defense counters site strikes; ICBM interception is another system. Unguided Grad rocket not worth a
     * missile, falling bomb too late
     */
    public record Signature(double visibility, boolean intercept) {}

    /**
     * Guided-projectile attack geometry. These values do not derive from speed/turn in one formula: chosen by
     * visuals/flight scenarios, retaining margin for angular-speed buildup, dive acceleration, terrain omitted by
     * formula. {@code WeaponSpecTest} checks relation to derived values (turn radius, popup geometry): changing
     * speed/turn tells which to revisit.
     *
     * @param reattackMin within this horizontal range, turning-circle condition does not cancel attack: proximity
     * fuze covers small miss; after passing, approach again. Below turning radius
     * @param terminalRange popup starts this far from target (0 none): at least popup geometry (climb from
     * terrain-hugging height to peak, dive at entry angle) plus pitch-transition margin
     * @param popUpMinRange minimum approach distance for popup: closer lacks climb room; exceeds {@code
     * terminalRange} by attack-heading acquisition distance
     */
    public record Attack(double reattackMin, double terminalRange, double popUpMinRange) {
        static final Attack NONE = new Attack(0, 0, 0);
    }

    /**
     * Turn-radius margin inside which point unreachable ({@code guidance.Autopilot.insideTurn}): angular speed
     * builds gradually.
     */
    public static final double TURN_MARGIN = 1.2;

    /**
     * Turning radius: v blocks/tick at ω °/tick, r = v / ω (ω in radians). Shahed 2.1 and 3°/tick ~40 blocks,
     * missile 4 and 3°/tick ~76, B-2 12 and 1°/tick ~690.
     */
    public static double turnRadius(double speed, double rateDeg) {
        return speed / Math.toRadians(rateDeg);
    }

    /** Final straight target leg on world flight scale, blocks ({@link #finalLeg(double)}). */
    public double finalLeg() {
        return finalLeg(FlightScale.x());
    }

    /**
     * Final straight leg at {@code x}, blocks: fits turn radii, grows with cruise ({@link Airframe#stretch}). B-2
     * release range follows altitude ({@code entity.BomberEntity#releaseLine}).
     */
    public double finalLeg(double x) {
        return route.gameFinalLeg() * airframe.stretch(x);
    }

    /** Waypoint capture distance on world flight scale, blocks ({@link #capture(double)}). */
    public double capture() {
        return capture(FlightScale.x());
    }

    /** Waypoint capture distance at {@code x}: about turn radius, grows with cruise. */
    public double capture(double x) {
        return route.gameCapture() * airframe.stretch(x);
    }

    /** Launcher standoff without nearby shooter on world flight scale, blocks ({@link #standoff(double)}). */
    public double standoff() {
        return standoff(FlightScale.x());
    }

    /**
     * Launcher standoff without nearby shooter at {@code x}, blocks. Guided weapons cover same time, growing with
     * cruise. Unguided rack rocket (Grad) scales launch rail exit speed (spec cruise is launch rail exit speed):
     * place rack at its trajectory range for rack angle/speed ({@link #reach}), in same proportion to game
     * standoff; at 1, real 683 m/s launch rail exit, like 9M22U at ~20 km.
     */
    public double standoff(double x) {
        if (x == 0) return route.gameStandoff();
        double k = airframe.ballistic() != null && rack != null ? reach(x) / reach(0) : airframe.stretch(x);
        return route.gameStandoff() * k;
    }

    /**
     * Unpowered trajectory range on flat sea-level ground at rack angle, exit speed, and drag for {@code x},
     * blocks, without wind.
     */
    private double reach(double x) {
        double v = airframe.cruiseSpeed(x), el = Math.toRadians(rack.elevation());
        Vec3 from = new Vec3(0, Air.SEA, 0);
        Ballistics.Path path = Ballistics.fly(from, new Vec3(v * Math.cos(el), v * Math.sin(el), 0), airframe.drag(x), Ballistics.Wind.CALM,
                Air.SEA - 1, REACH_TICKS);
        double k = path.landing(Air.SEA);
        return Double.isNaN(k) ? path.at(path.ticks()).x : path.at(k).x;
    }

    /** {@link #reach} calculation limit, ticks: 5 minutes, longer than any Grad trajectory (real 77 s). */
    private static final int REACH_TICKS = 6000;

    /** Ticks before impact when target warning starts. */
    public int sirenLead() {
        return sirenSeconds * 20;
    }

    /** Flight entity striking target: B-2 bomb, otherwise craft itself. */
    public Airframe striker() {
        return payload != null ? payload : airframe;
    }

    /**
     * Ground explosion TNT-equivalent charge, kg, from striking article warhead ({@link Article.Charge#tnt}):
     * wave pressure shatters glass ({@code Warheads}), also sets client explosion visuals/sound; 0 means no
     * ground explosion ({@link Blast#NONE}: bomb underground, ICBM nuclear).
     */
    public double charge() {
        return blast == Blast.NONE ? 0 : warheadTnt();
    }

    /** Striking article's conventional TNT-equivalent charge, kg; 0 absent (ICBM nuclear). */
    public double warheadTnt() {
        Article.Charge w = striker().article().warhead();
        return w == null ? 0 : w.tnt();
    }

    /**
     * Tank fuel at impact, kg ({@link Article#fuelAtImpact}): burns in explosion fireball (client visuals/sound).
     * Data, not flight logic: flight does not read it.
     */
    public double fuelAtImpact() {
        return striker().article().fuelAtImpact();
    }

    /**
     * Tank contents from article cruise engine: turbofan missile uses JP-10, piston propeller Shahed gasoline; no
     * impact fuel means none.
     */
    public ua.zentix.incoming.nuclear.model.FuelPool.Fuel fuel() {
        Article a = striker().article();
        if (a.fuelAtImpact() <= 0) return ua.zentix.incoming.nuclear.model.FuelPool.Fuel.NONE;
        return a.sustainer() instanceof Article.Turbofan ? ua.zentix.incoming.nuclear.model.FuelPool.Fuel.JP10
                : ua.zentix.incoming.nuclear.model.FuelPool.Fuel.PETROL;
    }

    /**
     * Fuel remaining in ground-impact crater and burning out there ({@link
     * ua.zentix.incoming.nuclear.model.FuelPool}): shared law for server block fire and client smoke/sound.
     */
    public ua.zentix.incoming.nuclear.model.FuelPool pool() {
        return ua.zentix.incoming.nuclear.model.FuelPool.of(fuel(), fuelAtImpact(), charge());
    }

    /**
     * Same fuel spread over {@code lit} cells (smaller than law area means thicker layer, longer/weaker burn,
     * {@link ua.zentix.incoming.nuclear.model.FuelPool#spread}), shared by server/client.
     */
    public ua.zentix.incoming.nuclear.model.FuelPool pool(int lit) {
        var law = pool();
        return law.spread(lit, law.cells());
    }

    /** Salvo lanes on world flight scale ({@link Lanes}): lengths grow with cruise like projectile spacing. */
    public Lanes lanes() {
        return salvo.lanes().scaled(airframe.stretch(FlightScale.x()));
    }

    /** Salvo launch gap, ticks. */
    public int salvoGap(RandomSource random) {
        return salvo.gapMin() + random.nextInt(salvo.gapMax() - salvo.gapMin() + 1);
    }

    // ---- article engines ({@link Article}): one value for server, sound ({@link Noise}), client plumes
    // ({@code client.ClientWeaponSpec}); “E” is a law-derived estimate from other values, with a range.

    /**
     * Shahed MD-550 copies Limbach L550E: two-stroke flat-four, 548 cm³, 37 kW (50 hp) at 7500 rpm (Wikipedia
     * “Limbach L550E”), direct-shaft two-blade pusher; cruise propeller efficiency 0.7 (E, 0.6–0.75); specific
     * fuel consumption 0.45 kg/(kW·h) for this two-stroke class (E, 0.4–0.5: article unpublished), giving 4.6 g/s
     * gasoline.
     */
    public static final Article.Propeller DRONE_PISTON = new Article.Propeller(37_000, 7500, 2, 0.7, 37_000 * 0.45 / 3.6e6);

    /**
     * Shahed booster (jettisoned solid motor under tail): ~2–3 s burn (launch videos, Militarnyi), thrust
     * unpublished. E: 4.2 kN (3–6 kN), ~215 kg including booster to ~35 m/s in 2.5 s at 15°; exhaust 2100 m/s
     * (small solid motor ~215 s specific impulse); whole booster ~15 kg (E).
     */
    public static final Article.SolidMotor DRONE_BOOSTER = new Article.SolidMotor(4200, 2100, 2.5, 15);

    /**
     * Tomahawk Williams F107-WR-402 cruise turbofan: 3.1 kN (700 lb), specific consumption 0.683 lb/(lb·h)
     * (Wikipedia “Williams F107”), giving 0.060 kg/s; airflow unpublished. E: 6.1 kg/s at ~510 m/s jet (bypass
     * ratio ~1, thrust ≈ flow × jet).
     */
    public static final Article.Turbofan MISSILE_TURBOFAN = new Article.Turbofan(1, 3100, 6.1, 510, 3100 / Article.G * 0.683 / 3600);

    /**
     * Tomahawk Atlantic Research Mk 106 booster: 26.7 kN for 12 s (designation-systems.net, “Raytheon BGM-109
     * Tomahawk”); exhaust ~2350 m/s (E: composite propellant ~240 s specific impulse), 136 kg propellant; whole
     * booster ~270 kg (E: 1.6 t with vs 1.3 t without, Wikipedia “Tomahawk (missile)”).
     */
    public static final Article.SolidMotor MISSILE_BOOSTER = new Article.SolidMotor(26_700, 2350, 12, 270);

    /**
     * B-2: four F118-GE-100 turbofans, 77 kN each (17300 lb, Wikipedia “Northrop B-2 Spirit”); static airflow
     * ~135 kg/s, jet ~570 m/s (E: bypass 0.76, thrust ≈ flow × jet); cruise kerosene ~0.25 kg/s each (E).
     */
    public static final Article.Turbofan BOMBER_TURBOFANS = new Article.Turbofan(4, 77_000, 135, 570, 0.25);

    /**
     * Grad 9M22U motor: burn time, impulse, propellant unpublished. E: 20 kg propellant (66.6 → 46.6 kg; modern
     * replacement EDePro G-2000 uses 27 kg composite, 62.5 kN·s over 2.7 s), exhaust 2000 m/s (690 m/s burnout
     * per Tsiolkovsky requires at least 1930; double-base 200–230 s), burn 1.9 s (1.5–2.5), giving 21 kN.
     */
    public static final Article.SolidMotor ROCKET_MOTOR = new Article.SolidMotor(20 / 1.9 * 2000, 2000, 1.9, 0);

    /**
     * Lancet-3: electric two-blade pusher motor; power unpublished. E: drag at 110 km/h ~0.5 kW, shaft ~0.8 kW at
     * propeller efficiency ~0.65; ~9000 rpm (E: ~0.36 m propeller tips at 0.5 M). Battery, no fuel flow.
     */
    public static final Article.Propeller LOITER_MOTOR = new Article.Propeller(800, 9000, 2, 0.65, 0);

    /** Minuteman III first-stage boost ({@link Ascent}; values/sources in {@link #NUKE}). */
    static final Ascent ICBM_ASCENT = new Ascent(903_700, 36_030, 20_780, 61, 60, 85, 320);

    // ---- real article specifications ({@link Article}); game speeds/heights in {@link Airframe} are not physical.

    /**
     * Shahed-136: 200 kg (Geran-2 up to 240), length 3.5 m, span 2.5 m, maximum ~185 km/h = 51 m/s at full power
     * (Wikipedia “HESA Shahed 136”); body Ø ~0.5 m (E from photos). Launch fuel E: ~50 kg (30–70; 71 l tank
     * stated only for 90 kg warhead variant). Open data conflict: full-power 50 kg lasts ~550 km (4.6 g/s);
     * claimed 2000–2500 km requires triple fuel or low-throttle flight. Flight step (S2) derives range from
     * consumption; unspecified here. CdA 0.3 m² (E, 0.27–0.34: 37 kW × 0.7 propeller at sea-level 51 m/s).
     * Altitude since 2025: cruise 1.5–2.8 km, dive from 2–2.5 km; claimed 60–4000 m (Defence Intelligence of
     * Ukraine, Euromaidan Press 06.2025 analyses, Gwara Media); use 2000 m. Dive E: vertical ~105 m/s, 60° impact
     * ~99 m/s (propeller thrust, weight, drag balance, {@code WeaponSpecTest}). Warhead 50 kg (estimates 30–50;
     * later Gerans 90, Wikipedia, ISIS): Artem chose original Shahed with 50 kg TNT. Explosive fraction
     * unpublished; E: ~40 kg explosive × 1.25, ~10 kg casing, warhead casing Ø ~0.4 m (E: in 0.5 m fuselage
     * nose). Impact fuel E: 30 kg gasoline (10–50, by path traveled).
     */
    static final Article DRONE_ARTICLE = new Article(150, 200, 3.5, 0.5, 2.5, 0.3, DRONE_PISTON, DRONE_BOOSTER,
            new Article.Envelope(51.4, 105, 2000), new Article.Impact(99, 60), new Article.Charge(50, 40, 10, 0.4), 30, null);

    /**
     * BGM-109 Tomahawk Block IV: 1.3 t without booster, 5.56 m, Ø 0.52 m, span 2.67 m, M0.74 ≈ 250 m/s (880
     * km/h), terrain-hugging 30–50 m (Wikipedia “Tomahawk (missile)”, designation-systems.net). Fuel (TH-dimer
     * like JP-10) unpublished; E: ~385 kg (1600 km × 0.060 kg/s ÷ 250 m/s). CdA 0.078 m² (E: 3.1 kN thrust equals
     * drag at sea-level 250 m/s). WDU-36/B warhead 310 kg (designation-systems 340), including 120 kg PBXN-107
     * (Wikipedia citing PEO U&W): Artem, 18.10 chose ~150 kg TNT (E: PBXN-107 relative strength ~1.25); casing
     * ~190 kg steel, Ø ~0.5 m (E inside 0.52 m missile body). Terminal popup/dive or straight; angle unpublished,
     * E: 45° at cruise speed. Impact fuel E: 275 kg (200–350, hundreds of kg and fireball size).
     */
    static final Article MISSILE_ARTICLE = new Article(925, 1310, 5.56, 0.52, 2.67, 0.078, MISSILE_TURBOFAN, MISSILE_BOOSTER,
            new Article.Envelope(250, 250, 40), new Article.Impact(250, 45), new Article.Charge(150, 120, 190, 0.5), 275, null);

    /**
     * B-2 Spirit: empty 71.7 t, takeoff up to 170.6 t, length 21 m, height 5.2 m, span 52.4 m, cruise M0.85 (~250
     * m/s at 12 km), ceiling 15.2 km (Wikipedia “Northrop B-2 Spirit”); release height E: 12 km (high-altitude
     * bomber 9–12 km). CdA E: ~4 m² (flying-wing C<sub>d0</sub> ~0.008 over 478 m²). No direct impact: bomb
     * strikes.
     */
    static final Article BOMBER_ARTICLE = new Article(71_700, 170_600, 21, 5.2, 52.4, 4, BOMBER_TURBOFANS, null,
            new Article.Envelope(250, 250, 12_000), null, null, 0, null);

    /** GBU-57 ogive nose, calibers (E, {@link #BOMB_ARTICLE}). */
    static final double BOMB_NOSE_CRH = 6;

    /**
     * GBU-57A/B MOP: 30000 lb class (13.6 t; BLU-127 casing with explosive 12304 kg), 6.2 × 0.8 m, explosive 2423
     * kg: 2082 kg AFX-757, 341 kg PBXN-114 (Wikipedia “GBU-57A/B MOP” citing DTRA). TNT equivalent E: explosive
     * mass (AFX-757 relative strength unpublished), casing ~9.9 t steel. Release/impact unpublished; E: from 12
     * km at 250 m/s with C<sub>d</sub> 0.3 (0.2–0.35, CdA 0.15 m²), falls ~50 s, strikes at 511 m/s (506–522),
     * 64° (point mass in standard atmosphere, {@code WeaponSpecTest}). No fuel. Nose E: {@value
     * #BOMB_NOSE_CRH}-caliber ogive (Young nose coefficient N ≈ 0.99; nose geometry unpublished, penetrating
     * bodies 6–9 calibers); at 511 m/s gives MOP 8.4–9.3 m rock penetration (S ≈ 0.84).
     */
    static final Article BOMB_ARTICLE = new Article(13_600, 13_600, 6.2, 0.8, 0.8, 0.15, null, null,
            new Article.Envelope(250, 511, 12_000), new Article.Impact(511, 64), new Article.Charge(2423, 2423, 9_900, 0.8), 0,
            new Article.Penetrator(BOMB_NOSE_CRH));

    /**
     * LGM-30G Minuteman III: 36030 kg, 18.2 × 1.67 m (USAF Fact Sheet); mod models only first stage ({@link
     * #ICBM_ASCENT}), mass without its propellant 15250 kg. CdA E: 0.66 m² (C<sub>d</sub> ~0.3 over 2.19 m²).
     * First-stage burnout E: ~1680 m/s (Tsiolkovsky minus gravity, no drag). Warhead reentry E: ~7.2 km/s at
     * 22.5° to horizon (minimum energy over ~10000 km: γ = (180° − 90°)/4, v from Kepler); Wikipedia “LGM-30
     * Minuteman” states terminal 7.83 km/s. Nuclear warhead, no conventional.
     */
    static final Article ICBM_ARTICLE = new Article(ICBM_ASCENT.launchMass() - ICBM_ASCENT.propellant(), ICBM_ASCENT.launchMass(), 18.2, 1.67, 1.67, 0.66, null, null,
            new Article.Envelope(1680, 7200, 0), new Article.Impact(7200, 22.5), null, 0, null);

    /**
     * Grad 9M22U: 66.6 kg, 2.87 m, 122 mm, range 5–20.38 km, ~690 m/s (Wikipedia “BM-21 Grad”); motor {@link
     * #ROCKET_MOTOR}. Subsonic CdA E: 0.0034 m² (C<sub>d</sub> 0.29); with {@link Article#machFactor} curve,
     * maximum range 20.4 km at 53°, burnout 683 m/s, flight 77 s, apogee 7.2 km, impact 337 m/s at 62° ({@code
     * WeaponSpecTest}). Warhead 18.4 kg, including 6.4 kg explosive (Wikipedia, Russian “Grad (MLRS)”); TNT
     * equals explosive mass (as before), casing 12 kg, Ø 122 mm caliber.
     */
    static final Article ROCKET_ARTICLE = new Article(46.6, 66.6, 2.87, 0.122, 0.122, 0.0034, null, ROCKET_MOTOR,
            new Article.Envelope(683, 683, 0), new Article.Impact(337, 62), new Article.Charge(6.4, 6.4, 12, 0.122), 0, null);

    /**
     * Lancet-3: 12 kg, warhead 3 kg, 80–110 km/h, dive up to ~300 km/h from 600–800 m (Wikipedia “ZALA Lancet”,
     * Army Recognition); length 1.65 m (source); Ø ~0.2, span ~1.2 m (E from model). CdA E: 0.028 m² (vertical
     * dive 300 km/h at 12 kg). Impact E: 70° at ~82 m/s (weight/thrust/drag balance, {@code WeaponSpecTest}).
     * Warhead explosive fraction unpublished; E: ~2.3 kg explosive × 1.3 = 3 kg TNT, casing 0.7 kg, Ø ~0.12 m (E
     * inside 0.2 m body). No fuel (battery).
     */
    static final Article LOITER_ARTICLE = new Article(12, 12, 1.65, 0.2, 1.2, 0.028, LOITER_MOTOR, null,
            new Article.Envelope(30.6, 83, 700), new Article.Impact(82, 70), new Article.Charge(3, 2.3, 0.7, 0.12), 0, null);

    // ---- sound specs ({@link Noise}): source laws from engine values above; “E” is class-based estimate.

    /**
     * Unmuffled two-stroke louder than same muffled engine, dB (E: small two-stroke muffler insertion loss
     * 10–20).
     */
    static final double OPEN_EXHAUST = 10;

    /**
     * Shahed class reference measurement: ultralight 15–30 hp full throttle, ~70 dBA 150 m below (F. W. J. van
     * Deventer, “Noise aspects of ultralights and model airplanes”, Proc. IOA), ~16.4 kW (22 hp); microphone 1.2
     * m above grass, as aircraft noise measurements (ICAO Annex 16; ⚠ paper conditions recalled, not stated).
     * Power inverted through mod propagation law ({@link Noise#fromMeasured}): former formula 70 + 20·lg 150 + 11
     * − 3 “microphone reflection” (121.5 dBA), with ISO 9613-2 meadow ground, gave 65 dBA at the measurement site
     * itself.
     */
    static final double UL_REF_DBA = 70, UL_REF_M = 150, UL_REF_MIC = 1.2, UL_REF_W = 16_400;

    /** Shahed launcher start (game, not physical): ignition, {@link #DRONE_BOOSTER} acceleration. */
    static final LaunchProfile DRONE_LAUNCH = new LaunchProfile(8, 38, 0.075, 8, -9);
    /** Tomahawk launcher start (game, not physical): ignition, {@link #MISSILE_BOOSTER} acceleration. */
    static final LaunchProfile MISSILE_LAUNCH = new LaunchProfile(6, 40, 0.09, 8, -14);

    /**
     * Shahed ({@link #DRONE_PISTON}): blades 250 Hz, shaft 125 Hz; sound nominal at cruise 185 km/h = 51 m/s
     * (Wikipedia “HESA Shahed 136”). Loudness by class ({@link #UL_REF_DBA}: ~126.4 dBA at 16.4 kW). Ultralights
     * have mufflers; MD-550 has open exhaust (short unmuffled pipes on wreckage): add {@value #OPEN_EXHAUST} dB,
     * small two-stroke muffler insertion loss (E, 10–20 dB; large diesel mufflers 20–30 dB, TRB Special Report
     * 152, 1975). Reality check: Shahed engine audible beyond 10 km in quiet weather (computingonline.net,
     * “Intelligent analysis of sound patterns of UAVs”, 2024; Ukrainian witnesses likewise); this value gives
     * model night ~12.6 km, day ~5.9 km ({@code HearingTest}); muffled ultralight level alone gave 4.3 km, 20 dB
     * quieter than 3.4.1. At 54 m overhead, ~92 dBA at real cruise (~90 game: RPM follows speed). Static full
     * throttle 0.8 nominal RPM, dive spinup capped at 1.1 (E). Body from {@link #DRONE_ARTICLE}; airflow
     * recording at its cruise. Booster separation ~125 dBA (E).
     */
    static final Noise DRONE_NOISE = new Noise(
            new Noise.Propeller(DRONE_PISTON.power(), DRONE_PISTON.rpm(), DRONE_PISTON.blades(), DRONE_ARTICLE.real().cruise(), 0.8, 1.1,
                    Noise.fromMeasured(UL_REF_DBA, Spectra.DRONE_ENGINE, new Propagation.Path(UL_REF_M, UL_REF_M, UL_REF_MIC, Propagation.MEADOW,
                            Propagation.Weather.NOON)) + WeaponSpec.OPEN_EXHAUST, UL_REF_W, Spectra.DRONE_ENGINE),
            new Noise.Rocket(DRONE_BOOSTER.thrust(), DRONE_BOOSTER.exhaust(), Spectra.BOOSTER_ENGINE, ThrustCurve.of(DRONE_LAUNCH, DRONE_BOOSTER)),
            new Noise.Body(DRONE_ARTICLE.diameter(), DRONE_ARTICLE.length(), DRONE_ARTICLE.real().cruise(), Spectra.BOMB_FALL), Double.NaN, Double.NaN, 125);

    /**
     * Tomahawk ({@link #MISSILE_TURBOFAN}, {@link #MISSILE_BOOSTER}): spoolup from 0.55 idle over 2.5 s (E). Body
     * {@link #MISSILE_ARTICLE}.
     */
    static final Noise MISSILE_NOISE = new Noise(
            new Noise.Jet(MISSILE_TURBOFAN.thrust(), MISSILE_TURBOFAN.jet(), MISSILE_TURBOFAN.count(), 0.55, 50, Spectra.MISSILE_ENGINE_REAR),
            new Noise.Rocket(MISSILE_BOOSTER.thrust(), MISSILE_BOOSTER.exhaust(), Spectra.BOOSTER_ENGINE, ThrustCurve.of(MISSILE_LAUNCH, MISSILE_BOOSTER)),
            new Noise.Body(MISSILE_ARTICLE.diameter(), MISSILE_ARTICLE.length(), MISSILE_ARTICLE.real().cruise(), Spectra.BOMB_FALL), Double.NaN, Double.NaN, 125);

    /** B-2 ({@link #BOMBER_TURBOFANS}): static thrust, upper estimate (engines shielded at altitude). */
    static final Noise BOMBER_NOISE = new Noise(new Noise.Jet(BOMBER_TURBOFANS.thrust(), BOMBER_TURBOFANS.jet(), BOMBER_TURBOFANS.count(), 0.55, 50,
            Spectra.BOMBER_ENGINE), null, null, Double.NaN, Double.NaN, Double.NaN);

    /**
     * GBU-57 MOP: 6.2 × 0.8 m (Wikipedia); fall recording is KC-135 wind (~230 m/s, E). Rock drilling ~125 dBA
     * (E: stronger than heavy jackhammer).
     */
    static final Noise BOMB_NOISE = new Noise(null, null, new Noise.Body(BOMB_ARTICLE.diameter(), BOMB_ARTICLE.length(), 230, Spectra.BOMB_FALL), 125, Double.NaN, Double.NaN);

    /**
     * ICBM first stage ({@link #ICBM_ASCENT}): exhaust 903700 · 61 s / 20780 kg = 2653 m/s; body 1.67 × 18.2 m
     * (USAF).
     */
    static final Noise ICBM_NOISE = new Noise(null, new Noise.Rocket(ICBM_ASCENT.thrust(), ICBM_ASCENT.exhaust(), Spectra.BOOSTER_ENGINE),
            new Noise.Body(ICBM_ARTICLE.diameter(), ICBM_ARTICLE.length(), 500, Spectra.BOMB_FALL), Double.NaN, Double.NaN, Double.NaN);

    /**
     * Grad ({@link #ROCKET_MOTOR}): 122 mm × 2.87 m (Wikipedia “BM-21 Grad”); howl recording is incoming mortar
     * (~150 m/s, E).
     */
    static final Noise ROCKET_NOISE = new Noise(null, new Noise.Rocket(ROCKET_MOTOR.thrust(), ROCKET_MOTOR.exhaust(), Spectra.BOOSTER_ENGINE),
            new Noise.Body(ROCKET_ARTICLE.diameter(), ROCKET_ARTICLE.length(), 150, Spectra.ROCKET_INCOMING), Double.NaN, Double.NaN, Double.NaN);

    /**
     * Lancet-3 ({@link #LOITER_MOTOR}): 110 km/h = 30.6 m/s (Wikipedia “ZALA Lancet”); blades ~300 Hz.
     * Class-based loudness: hobby to heavy multicopters, hover sound power 72.8–97.5 dB (eight-type measurements,
     * Korea); ~0.8 kW near upper end, 95 dBA (E). Catapult 0.85 nominal RPM, dive spinup to 1.15 (E). Body ~0.2 ×
     * 1.65 m (E); dive recording RC aircraft pass ~40 m/s (E). Catapult ~120 dBA (E).
     */
    static final Noise LOITER_NOISE = new Noise(new Noise.Propeller(LOITER_MOTOR.power(), LOITER_MOTOR.rpm(), LOITER_MOTOR.blades(), LOITER_ARTICLE.real().cruise(), 0.85, 1.15,
            95, 800, Spectra.LOITER_ENGINE), null, new Noise.Body(LOITER_ARTICLE.diameter(), LOITER_ARTICLE.length(), 40, Spectra.LOITER_DIVE), Double.NaN, 120, Double.NaN);

    // ---- our own game flight model ({@link Handling}, {@link Ballistic}): game values, not physical.

    /**
     * Shahed: route turn 3°/tick (radius ~40 blocks), post-launch climb 1.6°/tick; gentle pitch, dive up to
     * 4°/tick; engine recovers speed over ~20 ticks (0.05 fraction/tick), acceleration capped at 0.04
     * blocks/tick².
     */
    static final Handling DRONE_HANDLING = new Handling(new Agility(1.6, 0.12, 1.0, 0.12), new Agility(3.0, 0.3, 1.2, 0.15),
            new Agility(3.0, 0.3, 4.0, 0.25), 15, 12, 0.04, 0.05);

    /**
     * Cruise missile: turn 3°/tick (radius ~76 blocks), climb 2°/tick; sharp pitch for terrain-hugging (8°/tick),
     * popup/dive (16°/tick); climb up to 20° (also popup); turbine accelerates up to 0.1 blocks/tick².
     */
    static final Handling MISSILE_HANDLING = new Handling(new Agility(2.0, 0.15, 4, 0.6), new Agility(3.0, 0.3, 8, 1.8),
            new Agility(3.0, 0.3, 16, 3.5), 20, 12, 0.1, 0.15);

    /** Lancet: turn 3°/tick, climb 2°/tick; dive sharp banked correction up to 8°/tick, pitch up to 6°/tick. */
    static final Handling LOITER_HANDLING = new Handling(new Agility(2.0, 0.15, 1.4, 0.15), new Agility(3.0, 0.3, 1.2, 0.15),
            new Agility(8.0, 1.0, 6.0, 0.6), 15, 12, 0.1, 0.08);

    /**
     * B-2: turn 1°/tick (radius ~690 blocks), pitch up to 0.3°/tick; post-release departure turn up to 1.2°/tick
     * and climb (“climb” leg); heavy, slow speed change.
     */
    static final Handling BOMBER_HANDLING = new Handling(new Agility(1.2, 0.08, 0.4, 0.04), new Agility(1.0, 0.1, 0.3, 0.05),
            new Agility(1.0, 0.1, 0.3, 0.05), 15, 12, 0.02, 0.02);

    /**
     * GBU-57 fall ({@link #BOMB_ARTICLE}): game drag, sea-level terminal speed √(g/drag) ≈ 11 blocks/tick;
     * inherits B-2 speed ({@link #BOMBER_SPEED}) and slows toward terminal by ground. No spread: guided bomb.
     */
    static final Ballistic BOMB_FALL = new Ballistic(2e-4, 0, 0);
    /**
     * Bomb sea-level terminal speed √(g/drag), blocks/tick ({@link #BOMB_FALL}): its range.
     */
    static final double BOMB_TERMINAL = Math.sqrt(ua.zentix.incoming.guidance.Ballistics.GRAVITY / BOMB_FALL.drag());
    /**
     * B-2 speed, blocks/tick (game, not physical), also bomb release speed: released bomb retains carrier
     * velocity. Cannot go faster: drag exceeds gravity; fall slows it toward {@link #BOMB_TERMINAL}.
     */
    static final double BOMBER_SPEED = 12;

    /**
     * 9M22U post-burnout flight ({@link #ROCKET_ARTICLE}): game drag; at 50° launch rail exit over 600 blocks,
     * 4.0 blocks/tick instead of drag-free 3.9 and 51° impact; 2 km, 7.7 instead of 7.1, 54°; 10 km, 24 instead
     * of 16, 65° (stronger drag would require 76 blocks/tick at 10 km). Ordered fire solution knows only ground
     * wind, stronger aloft, drifting salvo downwind. Exit dispersion game-based (not physical): direction
     * standard deviation 0.4°, speed 0.5%, at 600 blocks ~1% range, as before (9M22U maximum range: 1/130 range,
     * 1/200 direction, Wikipedia Russian “Grad (MLRS)”).
     */
    static final Ballistic ROCKET_FLIGHT = new Ballistic(1e-4, 0.4, 0.005);

    /**
     * Shahed-136 kamikaze drone ({@link #DRONE_ARTICLE}). Game (not physical): ≈150 km/h, cruise 45 blocks above
     * terrain, dive acceleration to 3 blocks/tick. Booster burns ~2 s (real ~2.5 s), then drops. Warhead 50 kg
     * TNT: glass shattering to ~70 blocks. Operator waypoints up to 30 km (~12 minutes), triple default
     * designator map. Satellite/inertial navigation: per-axis miss standard deviation 3 blocks (mean ~3.5 from
     * target). Salvo wave ({@link Lanes}, game, not physical): lanes up to ±32 blocks (route spacing 42–84),
     * entry over 100 blocks, convergence over final 300.
     */
    public static final WeaponSpec DRONE = new WeaponSpec(WeaponType.SirenKind.AIR_RAID, 25, Salvo.gaps(20, 40).inLanes(32, 100, 300), Warhead.CONVENTIONAL,
            Launch.GUIDED,
            new Route(() -> IncomingConfig.SERVER.droneFlightTime, 300, 0, 3, 40, 30_000), Tracking.CAMERA, LauncherRack.DRONE, Blast.DRONE, false, 0.6f,
            new Airframe(() -> ModEntities.DRONE.get(), 2.1, 3.0, DRONE_HANDLING, 45, 20, 256, 1.83, 12, 900 * 2.1, 4.3,
                    DRONE_LAUNCH, 0, new Attack(16, 0, 0),
                    DRONE_NOISE, new Signature(1.0, true), DRONE_ARTICLE, null),
            null, Munition.single(() -> ModItems.SHAHED.get()), null);

    /**
     * Tomahawk cruise missile ({@link #MISSILE_ARTICLE}). Game (not physical): 80 m/s (4 blocks/tick, ≈290 km/h),
     * slower so approach visible; terrain-hugging 12 blocks, popup/dive to 5 blocks/tick. Booster brings speed to
     * ~3.4, below cruise; turbine accelerates afterward without downward jump. Final 256 blocks in-world, beyond
     * 12-chunk render distance, making approach visible from its edge even with lower simulation distance.
     * Warhead ~150 kg TNT (WDU-36/B): glass to ~105 blocks. Operator route up to 30 km (~6 minutes). More
     * accurate than Shahed (terrain/camera matching on approach): per-axis miss standard deviation 1.5 blocks.
     * Salvo wave ({@link Lanes}, game, not physical): lanes ±48 blocks (spacing 60–120), entry over 300 (point
     * capture radius 120), convergence over final 500-block leg.
     */
    public static final WeaponSpec MISSILE = new WeaponSpec(WeaponType.SirenKind.MISSILE, 15, Salvo.gaps(15, 30).inLanes(48, 300, 500), new Warhead(false, true, true),
            Launch.GUIDED,
            new Route(() -> IncomingConfig.SERVER.missileFlightTime, 500, 0, 1.5, 120, 30_000), Tracking.POINT, LauncherRack.MISSILE, Blast.MISSILE, false, 0.5f,
            new Airframe(() -> ModEntities.CRUISE_MISSILE.get(), 4.0, 5.0, MISSILE_HANDLING, 12, 12, 256, 2.96, 8, 700 * 4.0, 6.5,
                    MISSILE_LAUNCH, 256, new Attack(64, 160, 185),
                    MISSILE_NOISE, new Signature(0.7, true), MISSILE_ARTICLE, null),
            null, Munition.single(() -> ModItems.CRUISE_MISSILE.get()), null);

    /**
     * B-2 ({@link #BOMBER_ARTICLE}) and GBU-57 ({@link #BOMB_ARTICLE}). Game (not physical): +170 altitude over
     * bomb impact (real ~12 km), 12 blocks/tick, turn 1°/tick (radius ~690), release ~145 blocks before target;
     * bunker-buster penetrates and detonates underground. Satellite guidance: per-axis miss standard deviation
     * 1.5 blocks. Bomb has no fuel.
     */
    public static final WeaponSpec BUNKER = new WeaponSpec(WeaponType.SirenKind.AIR_RAID, 20, Salvo.gaps(60, 80), new Warhead(false, true, false),
            Launch.BOMBER,
            new Route(() -> IncomingConfig.SERVER.bomberFlightTime, 145, 0, 1.5, 0, 0), Tracking.POINT, null, Blast.NONE, true, 0.5f,
            new Airframe(() -> ModEntities.BOMBER.get(), BOMBER_SPEED, BOMBER_SPEED, BOMBER_HANDLING, 170, 30, 0, 8.5, 0, 120 * BOMBER_SPEED, 0, null, 0, Attack.NONE,
                    BOMBER_NOISE, new Signature(0.15, true), BOMBER_ARTICLE, null),
            new Airframe(() -> ModEntities.BUNKER_BUSTER.get(), BOMBER_SPEED, Math.max(BOMBER_SPEED, BOMB_TERMINAL), null, 0, 12, 0, BombDrop.NOSE, 0, 300 * BOMB_TERMINAL,
                    BombDrop.REACH_PAD, null, 0, Attack.NONE,
                    BOMB_NOISE, new Signature(0.1, false), BOMB_ARTICLE, BOMB_FALL),
            Munition.single(() -> ModItems.BUNKER_BUSTER.get()), null);

    /**
     * Nuclear ICBM ({@link #ICBM_ARTICLE}): boost only (25 blocks/tick limit, not physical); strike is {@code
     * NuclearStrikes} timer. Boost is LGM-30G Minuteman III first stage ({@link #ICBM_ASCENT}): launch mass 36030
     * kg (79432 lb), first-stage thrust 203158 lb (903.7 kN), USAF Fact Sheet “LGM-30G Minuteman III”
     * (https://www.af.mil/About-Us/Fact-Sheets/Display/Article/104466/lgm-30g-minuteman-iii/); first-stage
     * propellant 20780 kg over 61 s (S. Fetter, “A Ballistic Missile Primer”, Minuteman first-stage table, same
     * stage in Minuteman III, https://gotech.spp.umd.edu/sites/default/files/2019-08/1990-MissilePrimer.pdf).
     * Liftoff 1.56 g, at 10 s 1.82 g: 165 m/s, ~810 m axially. Strictly vertical 3 s (tail clears 25-block silo
     * at ~1.8 s), then tilt toward target heading to 85° above horizon; exits world ceiling +320 after ~8.5 s at
     * y 64, ~30 blocks sideways: farther would leave ready launch-site chunks. Nuclear detonation (kiloton yield
     * {@code Loadout.Nuke}). No miss: real ICBM miss is hundreds of meters, immaterial with nuclear charge; exact
     * marker detonation matches siren/warning. No impact fuel (warhead).
     */
    public static final WeaponSpec NUKE = new WeaponSpec(WeaponType.SirenKind.NUCLEAR, 0, Salvo.gaps(200, 300), new Warhead(true, false, true),
            Launch.ICBM,
            new Route(null, 0, 0, 0, 0, 0), Tracking.POINT, null, Blast.NONE, false, 0.5f,
            new Airframe(() -> ModEntities.ICBM.get(), 25, 25, null, 0, 12, 0, 9, 0, 600 * 25, 0, null, 0, Attack.NONE,
                    ICBM_NOISE, new Signature(1.0, false), ICBM_ARTICLE, null),
            null, Munition.single(() -> ModItems.ICBM.get()), ICBM_ASCENT);

    /**
     * BM-21 Grad MLRS, 9M22U rocket ({@link #ROCKET_ARTICLE}): unguided ballistic rockets from 40 tubes,
     * half-second launch sequence; no single fire, designator defaults to 12 over 15-block spread. Without nearby
     * shooter, rack 600 blocks from target (game, not physical: real range 5–20.4 km). Game dispersion, not
     * physical: per-axis standard deviation 1% range; 9M22U maximum range 1/130 range, 1/200 direction (Wikipedia
     * Russian “Grad (MLRS)”), no public short-range tables. Charge 6.4 kg explosive: glass to ~37 blocks.
     * Munition pack covers all 40 tubes (BM-21 reload); salvo spends rounds individually. No impact fuel: motor
     * burns out a second or two after launch rail exit.
     */
    public static final WeaponSpec ROCKET = new WeaponSpec(WeaponType.SirenKind.MISSILE, 8, new Salvo(8, 12, 12, 15, Lanes.NONE), Warhead.CONVENTIONAL,
            Launch.ROCKET,
            new Route(null, 0, 600, 0, 0, 0), Tracking.POINT, LauncherRack.ROCKET, Blast.ROCKET, false, 0.5f,
            new Airframe(() -> ModEntities.ROCKET.get(), 4, 4, null, 0, 4, 0, 1.45, 2, 2400 * 4, 1.5, null, 0, Attack.NONE,
                    ROCKET_NOISE, new Signature(1.0, false), ROCKET_ARTICLE, ROCKET_FLIGHT),
            null, new Munition(() -> ModItems.GRAD_ROCKETS.get(), LauncherRack.ROCKET.slots()), null);

    /**
     * Lancet-3 loitering munition ({@link #LOITER_ARTICLE}). Game (not physical): ≈115 km/h, catapult
     * (half-second impulse, no flame) to target, orbit 45 blocks above it (real 600–800 m), dive acceleration to
     * 4 blocks/tick; without nearby shooter, afar from 500 blocks. Warhead 3 kg: glass to ~29 blocks. Operator
     * route up to 15 km (~8 minutes): short-range weapon, half Shahed reach. Camera guidance (operator/own): no
     * miss. No fuel, electric battery motor. Salvo wave ({@link Lanes}, game, not physical): lanes ±32 blocks
     * (spacing 64–96), entry 80, convergence to orbit 150; no lane on direct path shorter than 230 (from arming
     * gate for catapult).
     */
    public static final WeaponSpec LOITER = new WeaponSpec(WeaponType.SirenKind.AIR_RAID, 20, Salvo.gaps(40, 60).inLanes(32, 80, 150), Warhead.CONVENTIONAL,
            Launch.LOITER,
            new Route(null, 0, 500, 0, 32, 15_000), Tracking.CAMERA, LauncherRack.LOITER, Blast.DRONE, false, 0.5f,
            new Airframe(() -> ModEntities.LOITER.get(), 1.6, 4.0, LOITER_HANDLING, 45, 25, 35, 0.87, 4, 2400 * 1.6, 3.0,
                    new LaunchProfile(4, 10, 0.16, 4, -8), 0, Attack.NONE,
                    LOITER_NOISE, new Signature(0.5, true), LOITER_ARTICLE, null),
            null, Munition.single(() -> ModItems.LANCET.get()), null);
}
