package ua.zentix.incoming.strike;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.entity.flight.ProximityFuse;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.entity.BomberEntity;
import ua.zentix.incoming.entity.Launcher;
import ua.zentix.incoming.entity.LauncherEntity;
import ua.zentix.incoming.entity.LoiterEntity;
import ua.zentix.incoming.entity.RocketEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.guidance.FlightController;
import ua.zentix.incoming.guidance.Route;
import ua.zentix.incoming.launcher.FixedLauncherBlockEntity;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.util.Local;

import java.util.List;
import java.util.UUID;

/**
 * Launch: shared entry for commands, designator, salvos, tests.
 * <p>
 * Shahed and cruise missile start from a mobile launcher near shooter ({@link LaunchSite}): ignition, boosted
 * launch rail exit, booster drop, route entry. Route ({@link Route}) detours and approaches along shooter view
 * heading, “from behind”, over configured flight time (Shahed 50 s, missile 30 s), or uses operator map waypoints
 * ({@link Waypoints}). Most path is outside loaded world ({@link VirtualFlights}). Without nearby shooter
 * (console, command block, player in another world) or suitable launcher site, approach from afar with same
 * scheme. B-2 always approaches from afar.
 * <p>
 * Order with launch site ({@code /incoming salvo … from x z} or designator map) places launcher there regardless
 * of issuer ({@link LaunchSite.Post}); no suitable placement or unready chunk starts projectile virtually from
 * site. Fixed-launcher order ({@link LaunchOrigin.Fixed}) uses its rack/inventory, one projectile paid as it
 * takes rail; unavailable (unready chunk, missing block, empty inventory, blocked sector) means failed launch,
 * without distant-approach fallback.
 */
public final class StrikeService {
    /** Radius of audible siren and visible warning. */
    public static final double ALERT_RADIUS = 350;

    private StrikeService() {}

    /**
     * Launch result.
     *
     * @param eta ticks until impact (shooter message)
     */
    public record Result(boolean ok, int eta) {
        static final Result FAILED = new Result(false, 0);
    }

    /**
     * @param approachYaw approach heading (usually player view): projectile arrives “from behind” shooter
     * @param siren first projectile of order (single or first salvo shot): enable target siren on approach (one
     * per salvo), search launcher site anew
     * @param nuke nuclear yield/burst; cruise missile/bomb only with {@code onCarrier} (ICBM always nuclear)
     * @param via operator points: Shahed, missile, Lancet use these instead of detour ({@code approachYaw} unused
     * then); other weapons have no route
     * @param from launch origin ({@link LaunchOrigin}). Ordered horizontal site (launch resolves height): Shahed,
     * missile, MLRS, Lancet start on launcher there, or virtually from it without suitable placement; B-2
     * approaches from its side at {@code approachYaw}, ICBM from its own pad. Fixed launcher accepts only
     * rack-launched weapons, from itself only. Null means near shooter or from afar
     */
    public static Result launch(ServerLevel level, WeaponType weapon, Target target, Vec3 point, float approachYaw,
                                @Nullable UUID owner, boolean siren, Loadout.Nuke nuke, Waypoints via, @Nullable LaunchOrigin from) {
        return launch(level, weapon, target, point, approachYaw, owner, siren, nuke, via, from, 0);
    }

    /**
     * Salvo projectile ({@link SalvoData}) as {@link #launch}, in its own lane: route shifted sideways {@code
     * lane} blocks ({@link WeaponSpec.Lanes}, {@link Route#inLane}); 0 follows path itself.
     */
    public static Result launch(ServerLevel level, WeaponType weapon, Target target, Vec3 point, float approachYaw,
                                @Nullable UUID owner, boolean siren, Loadout.Nuke nuke, Waypoints via, @Nullable LaunchOrigin from, double lane) {
        ServerPlayer shooter = owner == null ? null : level.getServer().getPlayerList().getPlayer(owner);
        return launch(level, weapon, target, point, approachYaw, owner, shooter, siren, nuke, via, from, lane);
    }

    /**
     * Launch for already known player (designator, player command), like {@link #launch} without player-list
     * search (GameTest shooter is NeoForge {@code FakePlayer}, absent from list).
     */
    public static Result launchBy(ServerLevel level, WeaponType weapon, Target target, Vec3 point, float approachYaw,
                                  ServerPlayer shooter, boolean siren, Loadout.Nuke nuke, Waypoints via, @Nullable LaunchOrigin from) {
        return launch(level, weapon, target, point, approachYaw, shooter.getUUID(), shooter, siren, nuke, via, from, 0);
    }

    private static Result launch(ServerLevel level, WeaponType weapon, Target target, Vec3 point, float approachYaw, @Nullable UUID owner,
                                 @Nullable ServerPlayer shooter, boolean siren, Loadout.Nuke nuke, Waypoints via, @Nullable LaunchOrigin from,
                                 double lane) {
        if (shooter != null && shooter.level() != level) shooter = null;
        WeaponSpec spec = weapon.spec();
        FixedLauncherBlockEntity fixed = null;
        Munitions.Bill shot = null;
        if (from instanceof LaunchOrigin.Fixed f) {
            // Fixed-launcher rack: launcher present, turntable carries this weapon's rack, pays projectile from inventory.
            fixed = f.launcher(level);
            if (fixed == null || spec.rack() == null || !fixed.hasRack() || fixed.weapon() != weapon) return Result.FAILED;
            shot = new Munitions.Bill(weapon, 1, 0);
            MutableComponent missing = Munitions.pay(fixed.store(), shot);
            if (missing != null) {
                fixed.report(missing);
                return Result.FAILED;
            }
        }
        boolean ok = false;
        try {
            Result r = launch(level, weapon, target, point, approachYaw, owner, shooter, siren, nuke, via, from, fixed, lane);
            ok = r.ok();
            return r;
        } finally {
            // Failed launch, including exception: refund projectile to inventory.
            if (!ok && fixed != null) Munitions.refund(fixed.store(), shot);
        }
    }

    private static Result launch(ServerLevel level, WeaponType weapon, Target target, Vec3 point, float approachYaw, @Nullable UUID owner,
                                 @Nullable ServerPlayer shooter, boolean siren, Loadout.Nuke nuke, Waypoints via, @Nullable LaunchOrigin from,
                                 @Nullable FixedLauncherBlockEntity fixed, double lane) {
        if (siren) StrikeWorld.get(level).newOrder(owner, weapon);
        WeaponSpec spec = weapon.spec();
        // This launch's miss (spec): aim at shifted target, relative offset for entity.
        Vec3 miss = spec.route().miss(level.random);
        if (!miss.equals(Vec3.ZERO)) {
            target = target.offset(miss);
            point = point.add(miss);
        }
        if (spec.launch() == WeaponSpec.Launch.ICBM) return launchIcbm(level, target, point, nuke, shooter, IncomingConfig.SERVER.nukeFlightTime.get());
        Loadout.Nuke warhead = nuke.onCarrier() && Loadout.carriesNuke(weapon) ? nuke : null;
        Stand stand = fixed != null ? Stand.fixed(fixed) : new Stand(post(level, shooter, owner, from, point), null, from == null ? null : from.at());
        StrikeProjectile p = switch (spec.launch()) {
            case GUIDED -> launchGuided(level, weapon, target, point, approachYaw, owner, shooter, stand, via, lane);
            case ROCKET -> launchRocket(level, target, point, approachYaw, owner, shooter, stand);
            case LOITER -> launchLoiter(level, target, point, approachYaw, owner, shooter, stand, via, lane);
            case BOMBER -> launchBomber(level, target, point, approachYaw, owner);
            case ICBM -> throw new IllegalStateException("ICBMs are launched by NuclearStrikes");
        };
        if (p == null) return Result.FAILED;
        p.setNuclear(warhead);
        if (siren && IncomingConfig.SERVER.siren.get()) p.armSiren(spec.sirenLead());
        int eta = p.etaTicks();
        if (!p.isVirtual()) {
            // On launcher: acquire tickets before world entry; ordered site may not entity-tick.
            p.holdChunks(level);
            boolean added = false;
            try {
                added = level.addFreshEntity(p);
            } finally {
                // Entry rejected or handler threw (claim-protection mods): projectile is neither in world nor
                // virtual flights; without discard, pre-entry tickets would hold chunks until server stop;
                // propagate exception: failed launch refunds payment.
                if (!added) p.discard();
            }
            if (!added) return Result.FAILED;
        }
        return new Result(true, eta);
    }

    /**
     * ICBM strikes coordinates without following moving target; launch itself raises warning.
     *
     * @param flightTicks launch-to-detonation flight (world setting; non-operator strike at least 90 s, {@link
     * NuclearKeys})
     */
    public static Result launchIcbm(ServerLevel level, Target target, Vec3 point, Loadout.Nuke nuke, @Nullable ServerPlayer shooter, int flightTicks) {
        if (shooter != null && shooter.level() != level) shooter = null;
        boolean ok = target instanceof Target.Ground
                ? NuclearStrikes.launch(level, point, true, nuke.yieldKt(), nuke.airBurst(), shooter, flightTicks)
                : NuclearStrikes.launch(level, NuclearStrikes.ground(level, point), false, nuke.yieldKt(), nuke.airBurst(), shooter, flightTicks);
        return new Result(ok, flightTicks);
    }

    /**
     * Launcher location: ordered {@code from}, otherwise near shooter (if configured); null means launch without
     * launcher.
     */
    @Nullable
    private static LaunchSite.Post post(ServerLevel level, @Nullable ServerPlayer shooter, @Nullable UUID owner, @Nullable LaunchOrigin from, Vec3 point) {
        if (from != null) return LaunchSite.Post.ordered(level, from.at(), point, owner);
        return shooter != null && IncomingConfig.SERVER.launchNearPlayer.get() ? LaunchSite.Post.of(shooter) : null;
    }

    /**
     * Shahed, missile, MLRS, Lancet launch origin.
     *
     * @param post mobile firing position (near shooter or ordered site); null means none
     * @param fixed fixed launcher, only from itself, no shooter launcher/distant approach
     * @param from horizontal launch site: without launcher, start virtually here and approach from behind it
     */
    private record Stand(@Nullable LaunchSite.Post post, @Nullable FixedLauncherBlockEntity fixed, @Nullable Vec3 from) {
        static Stand fixed(FixedLauncherBlockEntity be) {
            return new Stand(null, be, be.position());
        }
    }

    /** Route length for configured flight time (spec). */
    private static double pathLength(WeaponType weapon) {
        return weapon.spec().airframe().cruiseSpeed() * weapon.spec().route().seconds() * 20;
    }

    /**
     * Final straight leg before target (spec: Shahed 300 blocks, missile 500), always from behind shooter or
     * launch site ({@code stand}; null means neither).
     */
    private static double entryDistance(WeaponType weapon, Vec3 point, @Nullable Vec3 stand) {
        double base = weapon.spec().finalLeg();
        if (stand == null) return base;
        double dx = point.x - stand.x, dz = point.z - stand.z;
        return Math.max(base, Math.sqrt(dx * dx + dz * dz) + 150);
    }

    /** Weapon projectile, not yet in world. */
    @Nullable
    private static StrikeProjectile create(ServerLevel level, WeaponType weapon) {
        return weapon.spec().airframe().entity().get().create(level);
    }

    /**
     * Shahed/missile: fixed launcher, firing-position launcher, otherwise virtual launch site or distant approach
     * ({@link Stand}). Projectile not yet added to world.
     */
    @Nullable
    private static StrikeProjectile launchGuided(ServerLevel level, WeaponType weapon, Target target, Vec3 point, float yaw, @Nullable UUID owner,
                                                 @Nullable ServerPlayer shooter, Stand stand, Waypoints via, double lane) {
        Vec3 behind = stand.from() != null ? stand.from() : shooter != null ? shooter.position() : null;
        Course course = via.isEmpty()
                ? new Loop(point, Local.horizontal(yaw), pathLength(weapon), entryDistance(weapon, point, behind), level.random.nextBoolean() ? 1 : -1)
                : new Through(point, via);
        if (stand.fixed() != null) {
            int option = aimFixed(level, stand.fixed(), weapon, point, course);
            return option < 0 ? null : onLauncher(level, weapon, target, point, course, stand.fixed(), option, owner, lane);
        }
        if (stand.post() != null) {
            StrikeProjectile p = fromLauncher(level, weapon, target, point, course, stand.post(), shooter, lane);
            if (p != null) return p;
        }
        return fromAfar(level, weapon, target, point, course, owner, stand.from(), lane);
    }

    /**
     * Shahed/missile for {@code shooter}, like designator but no player-list search (GameTest shooter is NeoForge
     * {@code FakePlayer}). Not yet added: add if on launcher; virtual one already flying.
     */
    @Nullable
    public static StrikeProjectile launchGuided(ServerLevel level, WeaponType weapon, Vec3 point, float yaw, ServerPlayer shooter, Waypoints via) {
        StrikeWorld.get(level).newOrder(shooter.getUUID(), weapon);
        Stand stand = new Stand(post(level, shooter, shooter.getUUID(), null, point), null, null);
        return launchGuided(level, weapon, new Target.Point(point), point, yaw, shooter.getUUID(), shooter, stand, via, 0);
    }

    /**
     * Shahed/missile path from launch to target: detour ({@link Loop}) or operator route ({@link Through}).
     * Launcher faces first waypoint, so choose heading from route at its site.
     */
    private sealed interface Course permits Loop, Through {
        /** Route options in preference order (loop detours on either side). */
        int options();

        /**
         * Route {@code option} from {@code start}, after projectile already traveled {@code travelled} blocks
         * from launcher to it.
         */
        Route from(Vec3 start, double travelled, int option);

        /** Launcher heading within {@link #MAX_OFF_TARGET} of this point's direction: path goes there. */
        Vec3 heading();

        /** Distant approach start (horizontal; launch sets height), option 0. */
        Vec3 afar(WeaponType weapon);
    }

    /**
     * Detour and approach behind shooter ({@link Route#plan}): {@code length} path for configured time, final
     * straight {@code entry}, detour {@code side} (option 1 opposite).
     */
    private record Loop(Vec3 point, Vec3 dir, double length, double entry, double side) implements Course {
        @Override
        public int options() {
            return 2;
        }

        @Override
        public Route from(Vec3 start, double travelled, int option) {
            return Route.plan(start, point, dir, Math.max(0, length - travelled), entry, option == 0 ? side : -side);
        }

        @Override
        public Vec3 heading() {
            return point;
        }

        /**
         * On approach line, path-length distance from target; short configured flight starts no closer than entry
         * point, otherwise first move turns backward.
         */
        @Override
        public Vec3 afar(WeaponType weapon) {
            double back = Math.max(length, entry);
            return new Vec3(point.x - dir.x * back, 0, point.z - dir.z * back);
        }
    }

    /** Operator route through waypoints, then target. */
    private record Through(Vec3 point, Waypoints via) implements Course {
        @Override
        public int options() {
            return 1;
        }

        @Override
        public Route from(Vec3 start, double travelled, int option) {
            return via.route(start);
        }

        @Override
        public Vec3 heading() {
            return via.points().getFirst();
        }

        /** Before first point by spec final-leg distance: approach it along route heading. */
        @Override
        public Vec3 afar(WeaponType weapon) {
            return via.afar(point, weapon.spec().finalLeg());
        }
    }

    /**
     * Shahed/missile launcher heading within this many degrees of path direction ({@link Course#heading}: target,
     * or operator's first point); otherwise salvo would depart the wrong way.
     */
    private static final float MAX_OFF_TARGET = 90;

    /** Shahed/missile on firing-position {@code post} launcher; null if absent and no placement possible. */
    @Nullable
    private static StrikeProjectile fromLauncher(ServerLevel level, WeaponType weapon, Target target, Vec3 point, Course course,
                                                 LaunchSite.Post post, @Nullable ServerPlayer shooter, double lane) {
        // Launcher with sector blocked by structure is unsuitable: projectile would crash before arming; owned check
        // and placement search share one budget.
        LaunchSite.Budget budget = new LaunchSite.Budget();
        LauncherEntity launcher = LaunchSite.existing(level, post, weapon, l -> LaunchSite.clearAhead(level, l, point, budget));
        int option = 0;
        if (launcher == null) {
            if (StrikeWorld.get(level).noLaunchSite(post.owner(), weapon, post.chunk())) return null;
            // Rack faces first waypoint: either detour side, whichever clear; otherwise
            // turn (within MAX_OFF_TARGET of path direction) until sector clear.
            LaunchSite.Pick pick = LaunchSite.findClear(level, post, weapon, course.heading(), site -> {
                float[] yaws = new float[course.options()];
                for (int i = 0; i < yaws.length; i++) {
                    Route plan = course.from(site, 0, i);
                    yaws[i] = FlightController.anglesTo(site, plan.current() == null ? point : plan.current())[0];
                }
                return yaws;
            }, MAX_OFF_TARGET, budget);
            if (pick == null) {
                noLaunchSite(level, post, shooter, weapon, budget);
                return null;
            }
            option = Math.max(0, pick.preferred());
            launcher = LaunchSite.deploy(level, pick.site(), pick.yaw(), weapon, post);
        }
        return onLauncher(level, weapon, target, point, course, launcher, option, post.owner(), lane);
    }

    /**
     * Shahed/missile in {@code launcher} cell (mobile/fixed), path {@code option} in {@code lane} ({@link
     * #inLane}).
     */
    @Nullable
    private static StrikeProjectile onLauncher(ServerLevel level, WeaponType weapon, Target target, Vec3 point, Course course, Launcher launcher,
                                               int option, @Nullable UUID owner, double lane) {
        StrikeProjectile p = create(level, weapon);
        if (p == null) return null;
        Slot slot = Slot.reserve(level, launcher);
        p.placeOnLauncher(slot.rail(), launcher.yaw(), launcher.elevation(), slot.ready(), slot.hidden(), target, point, owner);
        // First waypoint on launcher heading at arming distance: follow the checked sector straight
        // until there, then turn onto route armed; salvo projectile enters its lane after gate.
        Vec3 gate = gate(slot.rail(), launcher.yaw());
        p.setRoute(inLane(course.from(gate, ProximityFuse.ARM_DISTANCE, option), weapon, lane, point).after(gate, slot.rail()));
        return p;
    }

    /**
     * Fixed launcher {@code be} heading for Shahed/missile on {@code course}. Non-quiet rack (running salvo)
     * launches at own heading if clear; quiet rack retains heading if path within {@link Launcher#TURN_THRESHOLD}
     * and sector clear, otherwise turns to a clear path option or within {@link #MAX_OFF_TARGET} of path
     * direction ({@link LaunchSite#pickOn} on its mount). Cannot relocate launcher: it is a block.
     *
     * @return path option; −1 if sector blocked (reason in launcher status, {@link
     * FixedLauncherBlockEntity#report})
     */
    private static int aimFixed(ServerLevel level, FixedLauncherBlockEntity be, WeaponType weapon, Vec3 point, Course course) {
        long now = level.getGameTime();
        Vec3 site = be.position();
        float[] want = new float[course.options()];
        int option = 0;
        for (int i = 0; i < want.length; i++) {
            Route plan = course.from(site, 0, i);
            want[i] = FlightController.anglesTo(site, plan.current() == null ? point : plan.current())[0];
            if (Math.abs(Mth.wrapDegrees(want[i] - be.yaw())) < Math.abs(Mth.wrapDegrees(want[option] - be.yaw()))) option = i;
        }
        LaunchSite.Budget budget = new LaunchSite.Budget();
        boolean silent = be.queue().silent(now);
        if (!silent || Math.abs(Mth.wrapDegrees(want[option] - be.yaw())) <= Launcher.TURN_THRESHOLD) {
            if (LaunchSite.clearAhead(level, be, point, budget)) return option;
            if (!silent) return sectorClosed(be);
        }
        LaunchSite.Pick pick = LaunchSite.pickOn(level, be.mount(), site, weapon, want, course.heading(), MAX_OFF_TARGET, budget);
        if (pick == null) return sectorClosed(be);
        be.turnTo(pick.yaw(), now);
        // Clear heading within small correction threshold: rack stayed at old heading, sector checked above.
        if (be.yaw() != pick.yaw() && !LaunchSite.clearAhead(level, be, point, budget)) return sectorClosed(be);
        return Math.max(0, pick.preferred());
    }

    /** Fixed-launcher sector blocked: reason in its status. */
    private static int sectorClosed(FixedLauncherBlockEntity be) {
        be.report(Component.translatable("incoming.fixed_launcher.sector"));
        return -1;
    }

    /** Waypoint at arming distance from {@code rail} along launcher {@code yaw}. */
    private static Vec3 gate(Vec3 rail, float yaw) {
        return rail.add(Local.horizontal(yaw).scale(ProximityFuse.ARM_DISTANCE));
    }

    /**
     * No launcher site at {@code post} (no flat open-sky site, all sectors hit structures, or {@code budget}
     * exhausted): strike from afar, or from ordered site without launcher. Above-hotbar shooter line and log once
     * per order (salvo): remainder from this chunk skips launcher searches ({@link StrikeWorld#noLaunchSite}).
     */
    private static void noLaunchSite(ServerLevel level, LaunchSite.Post post, @Nullable ServerPlayer shooter, WeaponType weapon,
                                     LaunchSite.Budget budget) {
        if (!StrikeWorld.get(level).rememberNoLaunchSite(post.owner(), weapon, post.chunk())) return;
        String why = budget.out() ? "launch sector checks hit the limit of " + LaunchSite.Budget.CELLS + " cells"
                : "no room, or the launch sector is blocked by buildings";
        String who = shooter != null ? shooter.getGameProfile().getName() : "the console";
        Incoming.LOG.info("Launch: {} — no place for a launcher {} ({}) at {}, {}", weapon.getSerializedName(),
                post.ordered() ? "at the ordered launch point" : "near " + who, why, BlockPos.containing(post.center()),
                post.ordered() ? "launching from there without it" : "coming in from afar");
        if (shooter != null) {
            shooter.displayClientMessage(Component.translatable(post.ordered() ? "incoming.launch.no_site.ordered" : "incoming.launch.no_site")
                    .withStyle(ChatFormatting.GOLD), true);
        }
    }

    /**
     * Launcher cell for projectile.
     *
     * @param rail launch rail exit point
     * @param ready ticks until ignition
     * @param hidden ticks projectile stays hidden in rack while raising
     */
    private record Slot(Vec3 rail, int ready, int hidden) {
        static Slot reserve(ServerLevel level, Launcher launcher) {
            long now = level.getGameTime();
            int[] slot = launcher.reserve(now, launcher.rack().minReady(), launcher.rack().busyTicks());
            return new Slot(launcher.railPoint(slot[0]), slot[1], launcher.raisingTicks(now));
        }
    }

    /**
     * Launcher at {@code post} aimed at {@code point} (MLRS target, loiterer target/first operator waypoint):
     * turn owned launcher if quiet; otherwise place new. {@code null} if no site.
     */
    @Nullable
    private static LauncherEntity aimedLauncher(ServerLevel level, LaunchSite.Post post, @Nullable ServerPlayer shooter, WeaponType weapon, Vec3 point) {
        // Owned launcher usable if sector (catapult, rack tube) clear at heading after turning toward
        // target; otherwise projectile crashes into structure. Owned check and site search share budget.
        long now = level.getGameTime();
        LaunchSite.Budget budget = new LaunchSite.Budget();
        LauncherEntity launcher = LaunchSite.existing(level, post, weapon,
                l -> LaunchSite.clearAhead(level, l.position(), l.turnedYaw(FlightController.anglesTo(l.position(), point)[0], now), weapon, point, budget));
        if (launcher != null) {
            launcher.turnTo(FlightController.anglesTo(launcher.position(), point)[0], now);
            return launcher;
        }
        if (StrikeWorld.get(level).noLaunchSite(post.owner(), weapon, post.chunk())) return null;
        // Rack aims at target itself: cannot rotate it, only choose another site.
        LaunchSite.Pick pick = LaunchSite.findClear(level, post, weapon, point, site -> new float[]{FlightController.anglesTo(site, point)[0]}, 0, budget);
        if (pick == null) {
            noLaunchSite(level, post, shooter, weapon, budget);
            return null;
        }
        return LaunchSite.deploy(level, pick.site(), pick.yaw(), weapon, post);
    }

    /**
     * Fixed launcher {@code be} aimed at {@code point}, like {@link #aimedLauncher}: quiet rack turns; null if
     * sector at its heading blocked (reason in status).
     */
    @Nullable
    private static Launcher aimedFixed(ServerLevel level, FixedLauncherBlockEntity be, WeaponType weapon, Vec3 point) {
        long now = level.getGameTime();
        float yaw = be.turnedYaw(FlightController.anglesTo(be.position(), point)[0], now);
        if (!LaunchSite.clearAhead(level, be.mount(), be.position(), yaw, weapon, point, new LaunchSite.Budget())) {
            sectorClosed(be);
            return null;
        }
        be.turnTo(yaw, now);
        return be;
    }

    /** MLRS/Lancet launcher aimed at {@code point}: fixed, otherwise firing position; null if none. */
    @Nullable
    private static Launcher aimed(ServerLevel level, Stand stand, @Nullable ServerPlayer shooter, WeaponType weapon, Vec3 point) {
        if (stand.fixed() != null) return aimedFixed(level, stand.fixed(), weapon, point);
        return stand.post() == null ? null : aimedLauncher(level, stand.post(), shooter, weapon, point);
    }

    /**
     * No launcher: projectile starts virtually at {@code from}, otherwise afar on approach line at flight-time
     * distance from target, or before first operator waypoint (loaded site enters world immediately).
     */
    private static StrikeProjectile fromAfar(ServerLevel level, WeaponType weapon, Target target, Vec3 point, Course course,
                                             @Nullable UUID owner, @Nullable Vec3 from, double lane) {
        StrikeProjectile p = create(level, weapon);
        if (p == null) return null;
        Vec3 at = from != null ? from : course.afar(weapon);
        Vec3 start = new Vec3(at.x, point.y + weapon.spec().airframe().cruiseHeight(), at.z);
        p.launch(start, target, point, owner);
        p.setRoute(inLane(course.from(start, 0, 0), weapon, lane, point));
        startVirtual(level, p);
        return p;
    }

    /**
     * MLRS: rocket in fixed/firing-position rack tube (turns toward target if quiet), otherwise launch site or
     * spec standoff position ({@link WeaponSpec.Route#standoff}). Unguided: dispersion from launch rail exit
     * spread/wind ({@link WeaponSpec.Ballistic}, rocket itself), no target tracking.
     */
    @Nullable
    private static StrikeProjectile launchRocket(ServerLevel level, Target target, Vec3 point, float yaw, @Nullable UUID owner,
                                                 @Nullable ServerPlayer shooter, Stand stand) {
        RocketEntity r = ModEntities.ROCKET.get().create(level);
        if (r == null) return null;
        Launcher launcher = aimed(level, stand, shooter, WeaponType.ROCKET, point);
        if (launcher != null) {
            Slot slot = Slot.reserve(level, launcher);
            r.placeInTube(slot.rail(), launcher.yaw(), launcher.elevation(), slot.ready(), slot.hidden(), target,
                    point, owner);
            r.fireData(level.random);
            return r;
        }
        if (stand.fixed() != null) return null;
        Vec3 from = stand.from();
        Vec3 start = from != null ? new Vec3(from.x, point.y, from.z)
                : point.subtract(Local.horizontal(yaw).scale(WeaponType.ROCKET.spec().standoff()));
        r.fireData(level.random);
        r.launchFrom(start, target, point, owner);
        startVirtual(level, r);
        return r;
    }

    /**
     * Loitering munition: fixed/firing-position catapult (turns toward target if quiet), straight to target;
     * otherwise launch site or afar at orbit height. Operator route faces catapult toward first point; distant
     * approach starts spec standoff before it.
     */
    @Nullable
    private static StrikeProjectile launchLoiter(ServerLevel level, Target target, Vec3 point, float yaw, @Nullable UUID owner,
                                                 @Nullable ServerPlayer shooter, Stand stand, Waypoints via, double lane) {
        LoiterEntity e = ModEntities.LOITER.get().create(level);
        if (e == null) return null;
        Launcher launcher = aimed(level, stand, shooter, WeaponType.LOITER, via.isEmpty() ? point : via.points().getFirst());
        if (launcher != null) {
            Slot slot = Slot.reserve(level, launcher);
            e.placeOnLauncher(slot.rail(), launcher.yaw(), launcher.elevation(), slot.ready(), slot.hidden(), target, point, owner);
            e.setRoute(loiterRoute(via, slot.rail(), point, lane, gate(slot.rail(), launcher.yaw())));
            return e;
        }
        if (stand.fixed() != null) return null;
        Vec3 from = stand.from();
        WeaponSpec spec = WeaponType.LOITER.spec();
        Vec3 start = from != null ? new Vec3(from.x, point.y, from.z)
                : via.isEmpty() ? point.subtract(Local.horizontal(yaw).scale(spec.standoff()))
                : via.afar(point, spec.standoff()).add(0, point.y, 0);
        start = start.add(0, spec.airframe().cruiseHeight(), 0);
        e.launch(start, target, point, owner);
        e.setRoute(loiterRoute(via, start, point, lane, null));
        startVirtual(level, e);
        return e;
    }

    /**
     * Lancet path from {@code start}: operator points or straight toward target orbit, salvo projectile in own
     * lane ({@link #inLane}). Catapult lane starts after arming {@code gate} (like Shahed/missile), following
     * checked launch sector before it; no lane if first point precedes gate or direct path from gate is shorter
     * than lane entry plus convergence. Null means straight, no route.
     */
    @Nullable
    static Route loiterRoute(Waypoints via, Vec3 start, Vec3 point, double lane, @Nullable Vec3 gate) {
        Route plain = via.isEmpty() ? null : via.route(start);
        if (lane == 0) return plain;
        Vec3 from = gate != null ? gate : start;
        Vec3 first = via.isEmpty() ? point : via.points().getFirst();
        if (gate != null && Math.hypot(first.x - start.x, first.z - start.z) <= ProximityFuse.ARM_DISTANCE) return plain;
        WeaponSpec.Lanes lanes = WeaponType.LOITER.spec().lanes();
        if (via.isEmpty() && Math.hypot(point.x - from.x, point.z - from.z) < lanes.exit() + lanes.converge()) return plain;
        Route laned = inLane(via.isEmpty() ? new Route(List.of(), from) : via.route(from), WeaponType.LOITER, lane, point);
        return gate != null ? laned.after(gate, start) : laned;
    }

    /**
     * Salvo {@code route} in its lane: {@code lane} lateral blocks ({@link WeaponSpec.Lanes#offset});
     * entry/convergence toward {@code point} per spec ({@link Route#inLane}); 0 unchanged.
     */
    private static Route inLane(Route route, WeaponType weapon, double lane, Vec3 point) {
        if (lane == 0) return route;
        WeaponSpec.Lanes lanes = weapon.spec().lanes();
        return route.inLane(lane, lanes.exit(), lanes.converge(), point);
    }

    /**
     * Bomb strikes surface point above target (spec miss, {@link WeaponSpec.Route#error}), does not follow moving
     * target; target over 4 blocks underground (cave/bunker) means penetrate toward it.
     */
    private static StrikeProjectile launchBomber(ServerLevel level, Target target, Vec3 point, float yaw, @Nullable UUID owner) {
        int sx = Mth.floor(point.x), sz = Mth.floor(point.z);
        // Surface beneath target (no chunk load for launch): target may be airborne, bomb falls to ground below;
        // unready map-site chunk already has estimate (client map better than generator); by release B-2
        // refines using ready chunk.
        Terrain.Surface under = Terrain.estimate(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz,
                target instanceof Target.Ground ? Terrain.Allowed.CHUNK : Terrain.Allowed.ORDER);
        double sy = under.known() ? under.y() : point.y + 0.5;
        Vec3 surface = new Vec3(point.x, sy - 0.5, point.z);
        // Map site is surface, no bunker beneath (its height may be estimate, while miss-offset neighbor is ready).
        BlockPos goal = !(target instanceof Target.Ground) && surface.y - point.y >= 4 ? BlockPos.containing(point) : null;
        BomberEntity e = ModEntities.BOMBER.get().create(level);
        if (e == null) return null;
        WeaponSpec spec = WeaponType.BUNKER.spec();
        double length = spec.airframe().cruiseSpeed() * spec.route().seconds() * 20 + BomberEntity.releaseLine(spec.airframe().cruiseHeight());
        e.launch(surface.subtract(Local.horizontal(yaw).scale(length)), surface, goal, owner);
        e.setRoute(null);
        startVirtual(level, e);
        return e;
    }

    /** Flight starts virtually; at loaded site, projectile materializes next tick. */
    private static void startVirtual(ServerLevel level, StrikeProjectile p) {
        VirtualFlights.launch(level, p);
    }

    /** Siren and “AIR ALERT” for everyone within 350 blocks of target (each has own memory, see client). */
    public static void siren(ServerLevel level, WeaponType weapon, Vec3 at) {
        if (!IncomingConfig.SERVER.siren.get()) return;
        int kind = weapon.siren() == WeaponType.SirenKind.MISSILE ? S2C.Siren.MISSILE : S2C.Siren.AIR_RAID;
        PacketDistributor.sendToPlayersNear(level, null, at.x, at.y, at.z, ALERT_RADIUS, new S2C.Siren(at, kind));
    }

    /**
     * Server log line per order (tools/logscan.py): who, weapon, count, destination, projectile tracking, and
     * ordered launch site/operator waypoints if specified.
     */
    public static void log(ServerLevel level, String who, WeaponType weapon, int count, int spread, Target target, Vec3 point, Waypoints via,
                           @Nullable LaunchOrigin from) {
        StringBuilder route = new StringBuilder();
        switch (from) {
            case LaunchOrigin.Place p -> route.append(", launch from ").append(Mth.floor(p.at().x)).append(' ').append(Mth.floor(p.at().z));
            case LaunchOrigin.Fixed f -> route.append(", from launcher ").append(f.pos().toShortString());
            case null -> {}
        }
        for (int i = 0; i < via.size(); i++) {
            Vec3 q = via.points().get(i);
            route.append(i == 0 ? ", route " : " → ").append(Mth.floor(q.x)).append(' ').append(Mth.floor(q.z));
        }
        Incoming.LOG.info("Strike: {} ×{} spread {} at {} {} {} ({}{}) — {}", weapon.getSerializedName(), count, spread,
                Mth.floor(point.x), Mth.floor(point.y), Mth.floor(point.z), describe(level, target), route, who);
    }

    /** Log target: point, map site, named player, entity type, craft; sighted target “at last seen place”. */
    private static String describe(ServerLevel level, Target target) {
        return switch (target) {
            case Target.Point p -> "point";
            case Target.Ground g -> "map spot";
            case Target.OfEntity e -> {
                // Player anywhere (strike on player in another dimension).
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(e.uuid());
                if (player != null) yield "player " + player.getGameProfile().getName();
                Entity ent = level.getEntity(e.uuid());
                yield ent == null ? "entity " + e.uuid() : "entity " + BuiltInRegistries.ENTITY_TYPE.getKey(ent.getType());
            }
            case Target.OfSubLevel s -> "aircraft";
            case Target.Sighted s -> describe(level, s.quarry()) + " at last seen place";
        };
    }

    /** Above-hotbar line and designator click for launching player: launched weapon and time to impact. */
    public static void confirm(ServerPlayer player, WeaponType weapon, int etaTicks) {
        player.displayClientMessage(Component.translatable("incoming.launched." + weapon.getSerializedName(), (etaTicks + 19) / 20,
                        Component.keybind(Incoming.CAMERA_KEY))
                .withStyle(ChatFormatting.RED), true);
        player.playNotifySound(SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.MASTER, 1.0f, weapon.spec().confirmPitch());
    }
}
