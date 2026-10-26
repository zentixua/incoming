package ua.zentix.incoming.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.Vec2Argument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.launcher.FixedLauncherBlockEntity;
import ua.zentix.incoming.launcher.LauncherOrders;
import ua.zentix.incoming.launcher.Mission;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.radiation.GeigerFormat;
import ua.zentix.incoming.nuclear.radiation.RadiationDose;
import ua.zentix.incoming.nuclear.radiation.RadiationTicker;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.strike.LaunchOrigin;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.NuclearKeys;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.TargetMode;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * /incoming — the same as the designator, for operators and automation (command blocks, functions):
 * <pre>
 *   /incoming drone|missile|bunker                   where I am looking
 *   /incoming missile Steve                          at a player or mob (an operator's projectile follows it)
 *   /incoming bunker at ~ ~-20 ~                     at a point
 *   /incoming missile at x z                         at a ground position: the server finds the height, as for a map position
 *   /incoming salvo drone 6 25 [me|look|name|at x y z|at x z] salvo: count, spread
 *          … [from x z] [via x z [x z …]]            launch position (operator) and waypoints, as on the designator map;
 *                                                     one projectile — salvo drone 1 0 …
 *   /incoming menu | clear | give [players] | help
 *   /incoming nuke [kt] [air|ground]                  ICBM where I am looking (default — airburst)
 *   /incoming nuke at x y z [kt] [air|ground]         ICBM at a point
 *   /incoming nuke now [at x y z] [kt] [air|ground]   immediate detonation, no flight (operator only)
 *   /incoming radiation [player] | radiation clear [players]
 *   /incoming grid …                                  power grid and blackout ({@link GridCommand})
 *   /incoming launcher x y z                          fixed launcher (operator): status;
 *          … fire | clear | load N | owner player|none   fire mission, clear mission, mission munitions, owner;
 *          … mission weapon N spread x y z|x z [via x z …] mission, as from the designator, without rules
 * </pre>
 * Tab completes player names; case does not matter. “shahed” is an alias for drone. A player command without
 * operator permission follows the designator rules ({@link #rules}) and consumes inventory munitions ({@code strike.Munitions});
 * operators, the console and command blocks do not pay.
 * <p>
 * The {@code from} launch position is where the launcher is placed (for staged battles: who fires from where is visible);
 * only operators, the console and command blocks can set it. {@code via} points are the same ones players place on the
 * designator map ({@link Waypoints}), and apply only to weapons that fly through them.
 */
public final class IncomingCommand {
    private static final SimpleCommandExceptionType TARGET_NOT_FOUND = new SimpleCommandExceptionType(Component.translatable("incoming.target_not_found"));
    private static final SimpleCommandExceptionType NO_LAUNCHER = new SimpleCommandExceptionType(Component.translatable("incoming.fixed_launcher.none_here"));
    private static final SimpleCommandExceptionType LAUNCHER_NOT_LOADED = new SimpleCommandExceptionType(
            Component.translatable("incoming.fixed_launcher.not_loaded"));
    private static final SimpleCommandExceptionType LAUNCHER_BUSY = new SimpleCommandExceptionType(Component.translatable("incoming.fixed_launcher.busy"));

    private IncomingCommand() {}

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("incoming")
                .requires(s -> s.hasPermission(2) || s.getEntity() instanceof Player p && ServerActions.mayUse(p));

        root.then(Commands.literal("help").executes(IncomingCommand::help));
        root.then(Commands.literal("menu").executes(ctx -> {
            PacketDistributor.sendToPlayer(ctx.getSource().getPlayerOrException(), new S2C.OpenRemote());
            return 1;
        }));
        root.then(Commands.literal("clear").executes(ctx -> {
            CommandSourceStack s = ctx.getSource();
            // operators (console, command blocks) and the host clear everything, including nuclear strikes; players clear only their own, as with the designator
            boolean trusted = s.hasPermission(2) || s.getEntity() instanceof ServerPlayer p && NuclearKeys.trusted(p);
            if (!trusted && s.getEntity() instanceof ServerPlayer p) {
                // cancel before producing the message: without sendCommandFeedback, sendSuccess does not call the message supplier
                Component done = ServerActions.recall(p);
                s.sendSuccess(() -> done, false);
                return 1;
            }
            int n = ServerActions.clearAll(s.getServer(), trusted, s.getTextName());
            s.sendSuccess(() -> ServerActions.clearedMessage(n, trusted), true);
            return n;
        }));
        root.then(Commands.literal("give").requires(s -> s.hasPermission(2))
                .executes(ctx -> give(ctx, List.of(ctx.getSource().getPlayerOrException())))
                .then(Commands.argument("players", EntityArgument.players())
                        .executes(ctx -> give(ctx, EntityArgument.getPlayers(ctx, "players")))));

        root.then(nuke());
        root.then(GridCommand.build());
        root.then(launcher());
        root.then(Commands.literal("radiation").requires(s -> s.hasPermission(2))
                .executes(ctx -> radiation(ctx, ctx.getSource().getPlayerOrException()))
                .then(Commands.literal("clear")
                        .executes(ctx -> radiationClear(ctx, List.of(ctx.getSource().getPlayerOrException())))
                        .then(Commands.argument("players", EntityArgument.players())
                                .executes(ctx -> radiationClear(ctx, EntityArgument.getPlayers(ctx, "players")))))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> radiation(ctx, EntityArgument.getPlayer(ctx, "player")))));

        LiteralArgumentBuilder<CommandSourceStack> salvo = Commands.literal("salvo");
        for (WeaponType w : WeaponType.values()) {
            for (String name : names(w)) {
                root.then(weapon(name, w));
                salvo.then(salvo(name, w));
            }
        }
        d.register(root.then(salvo));
    }

    private static String[] names(WeaponType w) {
        return switch (w) {
            case DRONE -> new String[]{"drone", "shahed"};
            case MISSILE -> new String[]{"missile"};
            case BUNKER -> new String[]{"bunker"};
            case ROCKET -> new String[]{"rocket", "grad"};
            case LOITER -> new String[]{"loiter", "lancet"};
            case NUKE -> new String[0]; // separate branch: yield and burst type instead of count and spread
        };
    }

    // ---------------------------------------------------------------- nuclear strike

    private interface NukeAction {
        int run(CommandContext<CommandSourceStack> ctx, Loadout.Nuke nuke) throws CommandSyntaxException;
    }

    /** “[kt] [air|ground]” after any nuclear command. */
    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T yieldArgs(T node, NukeAction action) {
        return node.executes(ctx -> action.run(ctx, new Loadout.Nuke(IncomingConfig.SERVER.nukeDefaultYield.get(), true)))
                .then(Commands.argument("kt", IntegerArgumentType.integer(1, Loadout.Nuke.MAX_YIELD))
                        .executes(ctx -> action.run(ctx, new Loadout.Nuke(IntegerArgumentType.getInteger(ctx, "kt"), true)))
                        .then(Commands.literal("air").executes(ctx -> action.run(ctx, new Loadout.Nuke(IntegerArgumentType.getInteger(ctx, "kt"), true))))
                        .then(Commands.literal("ground").executes(ctx -> action.run(ctx, new Loadout.Nuke(IntegerArgumentType.getInteger(ctx, "kt"), false)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> nuke() {
        LiteralArgumentBuilder<CommandSourceStack> nuke = Commands.literal("nuke").requires(IncomingCommand::mayNuke);
        yieldArgs(nuke, IncomingCommand::nukeLook);
        return nuke
                .then(Commands.literal("at").then(yieldArgs(Commands.argument("pos", Vec3Argument.vec3()),
                        (ctx, n) -> nukeAt(ctx.getSource(), Vec3Argument.getVec3(ctx, "pos"), n))))
                // detonation without flight is a host tool: no payment or warning; unavailable to non-operators even with ops_only = false
                .then(yieldArgs(Commands.literal("now").requires(s -> s.hasPermission(2)), (ctx, n) -> nukeNow(ctx.getSource(), lookPoint(ctx), n))
                        .then(Commands.literal("at").then(yieldArgs(Commands.argument("pos", Vec3Argument.vec3()),
                                (ctx, n) -> nukeNow(ctx.getSource(), Vec3Argument.getVec3(ctx, "pos"), n)))));
    }

    private static boolean mayNuke(CommandSourceStack s) {
        if (!IncomingConfig.SERVER.nukeEnabled.get()) return false;
        return s.hasPermission(2) || !IncomingConfig.SERVER.nukeOpsOnly.get() && s.getEntity() instanceof Player p && ServerActions.mayUse(p);
    }

    private static int nukeLook(CommandContext<CommandSourceStack> ctx, Loadout.Nuke nuke) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerActions.Aim aim = ServerActions.fromMode(player, new Loadout(WeaponType.NUKE, 1, 0, TargetMode.LOOK, "", nuke), null);
        return aim != null && ServerActions.strike(player, WeaponType.NUKE, 1, 0, aim, nuke, Waypoints.NONE, null, rules(ctx.getSource())) ? 1 : 0;
    }

    private static int nukeAt(CommandSourceStack s, Vec3 pos, Loadout.Nuke nuke) {
        return fire(s, WeaponType.NUKE, 1, 0, new ServerActions.Aim(new Target.Point(pos), pos, null), nuke, Path.NONE);
    }

    private static Vec3 lookPoint(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerActions.Aim aim = ServerActions.fromMode(player, new Loadout(WeaponType.NUKE, 1, 0, TargetMode.LOOK, "", Loadout.Nuke.DEFAULT), null);
        if (aim == null) throw TARGET_NOT_FOUND.create();
        return aim.point();
    }

    private static int nukeNow(CommandSourceStack s, Vec3 pos, Loadout.Nuke nuke) {
        Entity e = s.getEntity();
        var d = NuclearStrikes.detonateNow(s.getLevel(), NuclearStrikes.ground(s.getLevel(), pos), nuke.yieldKt(), nuke.airBurst(), e == null ? null : e.getUUID());
        double kt = d != null ? d.yieldKt() : Math.min(nuke.yieldKt(), IncomingConfig.SERVER.nukeMaxYield.get());
        s.sendSuccess(() -> Component.translatable(d != null ? "incoming.nuke.detonated" : "incoming.nuke.detonating", Math.round(kt),
                Component.translatable(nuke.airBurst() ? "incoming.nuke.burst.air" : "incoming.nuke.burst.ground"),
                Mth.floor(pos.x), Mth.floor(pos.y), Mth.floor(pos.z)), true);
        return 1;
    }

    private static int radiation(CommandContext<CommandSourceStack> ctx, ServerPlayer p) {
        RadiationDose r = RadiationTicker.dose(p);
        ctx.getSource().sendSuccess(() -> Component.translatable("incoming.radiation.report", p.getDisplayName(),
                String.format(Locale.ROOT, "%.2f", r.doseGy()), GeigerFormat.rate(r.rate()),
                String.format(Locale.ROOT, "%.2f", r.contamination())), false);
        return Math.round(r.doseGy() * 100);
    }

    private static int radiationClear(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players) {
        for (ServerPlayer p : players) RadiationTicker.clear(p);
        ctx.getSource().sendSuccess(() -> Component.translatable("incoming.radiation.cleared", players.size()), true);
        return players.size();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> weapon(String name, WeaponType w) {
        return Commands.literal(name)
                .executes(ctx -> look(ctx, w, 1, 0, Path.NONE))
                .then(Commands.literal("at")
                        .then(Commands.argument("pos", Vec3Argument.vec3()).executes(ctx -> at(ctx, w, 1, 0, IncomingCommand::point, Path.NONE)))
                        .then(Commands.argument("place", Vec2Argument.vec2()).executes(ctx -> at(ctx, w, 1, 0, IncomingCommand::ground, Path.NONE))))
                .then(Commands.argument("target", EntityArgument.entity())
                        .executes(ctx -> entity(ctx, w, 1, 0, EntityArgument.getEntity(ctx, "target"), Path.NONE)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> salvo(String name, WeaponType w) {
        return Commands.literal(name).then(Commands.argument("count", IntegerArgumentType.integer(1, Loadout.MAX_COUNT))
                .then(pathArgs(Commands.argument("spread", IntegerArgumentType.integer(0, Loadout.MAX_SPREAD)), w,
                        (ctx, path) -> me(ctx, w, count(ctx), spread(ctx), path))
                        .then(pathArgs(Commands.literal("me"), w, (ctx, path) -> me(ctx, w, count(ctx), spread(ctx), path)))
                        .then(pathArgs(Commands.literal("look"), w, (ctx, path) -> look(ctx, w, count(ctx), spread(ctx), path)))
                        .then(Commands.literal("at")
                                .then(pathArgs(Commands.argument("pos", Vec3Argument.vec3()), w,
                                        (ctx, path) -> at(ctx, w, count(ctx), spread(ctx), IncomingCommand::point, path)))
                                .then(pathArgs(Commands.argument("place", Vec2Argument.vec2()), w,
                                        (ctx, path) -> at(ctx, w, count(ctx), spread(ctx), IncomingCommand::ground, path))))
                        .then(pathArgs(Commands.argument("target", EntityArgument.entity()), w,
                                (ctx, path) -> entity(ctx, w, count(ctx), spread(ctx), EntityArgument.getEntity(ctx, "target"), path)))));
    }

    // ---------------------------------------------------------------- origin and waypoints

    /** Where the order flies from and through: launch position (null — near the shooter or from afar) and waypoints. */
    private record Path(@Nullable LaunchOrigin from, Waypoints via) {
        static final Path NONE = new Path(null, Waypoints.NONE);
    }

    private interface Order {
        int run(CommandContext<CommandSourceStack> ctx, Path path) throws CommandSyntaxException;
    }

    /**
     * “[from x z] [via x z [x z …]]” after the salvo target: launch position — where the launcher is placed (operators,
     * console and command blocks only) — and waypoints, as on the designator map (only for weapons that fly through them:
     * the spec, {@link WeaponSpec.Route#waypoints}).
     */
    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T pathArgs(T node, WeaponType w, Order order) {
        RequiredArgumentBuilder<CommandSourceStack, Coordinates> from = Commands.argument("site", Vec2Argument.vec2())
                .executes(ctx -> order.run(ctx, path(ctx, true, 0)));
        if (w.spec().route().waypoints()) {
            node.then(via(order, false));
            from.then(via(order, true));
        }
        return node.executes(ctx -> order.run(ctx, Path.NONE))
                .then(Commands.literal("from").requires(s -> s.hasPermission(2)).then(from));
    }

    /** “via x z [x z …]”: up to {@link Waypoints#MAX} points in flight order; {@code from} comes after the launch position. */
    private static LiteralArgumentBuilder<CommandSourceStack> via(Order order, boolean from) {
        ArgumentBuilder<CommandSourceStack, ?> next = null;
        for (int i = Waypoints.MAX; i >= 1; i--) {
            int n = i;
            RequiredArgumentBuilder<CommandSourceStack, Coordinates> point = Commands.argument("point" + i, Vec2Argument.vec2())
                    .executes(ctx -> order.run(ctx, path(ctx, from, n)));
            if (next != null) point.then(next);
            next = point;
        }
        return Commands.literal("via").then(next);
    }

    /** Launch position (if {@code from}) and the first {@code points} waypoints from the command. */
    private static Path path(CommandContext<CommandSourceStack> ctx, boolean from, int points) {
        List<Vec3> via = new ArrayList<>(points);
        for (int i = 1; i <= points; i++) via.add(column(ctx, "point" + i));
        return new Path(from ? new LaunchOrigin.Place(column(ctx, "site")) : null, new Waypoints(via));
    }

    /** Horizontal “x z” position; “~” is relative to the caller (using doubles: {@code Vec2Argument.getVec2} uses floats). */
    private static Vec3 column(CommandContext<CommandSourceStack> ctx, String name) {
        Vec3 p = ctx.getArgument(name, Coordinates.class).getPosition(ctx.getSource());
        return new Vec3(p.x, 0, p.z);
    }

    /** Launch clamps the salvo count and spread ({@link ServerActions#clamp}). */
    private static int count(CommandContext<CommandSourceStack> ctx) {
        return IntegerArgumentType.getInteger(ctx, "count");
    }

    private static int spread(CommandContext<CommandSourceStack> ctx) {
        return IntegerArgumentType.getInteger(ctx, "spread");
    }

    private static int look(CommandContext<CommandSourceStack> ctx, WeaponType w, int count, int spread, Path path) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerActions.Aim aim = ServerActions.fromMode(player, new Loadout(w, count, spread, TargetMode.LOOK, "", Loadout.Nuke.DEFAULT), null);
        return aim != null ? fire(ctx.getSource(), w, count, spread, aim, Loadout.Nuke.DEFAULT, path) : 0;
    }

    private static int me(CommandContext<CommandSourceStack> ctx, WeaponType w, int count, int spread, Path path) {
        CommandSourceStack s = ctx.getSource();
        return fire(s, w, count, spread, new ServerActions.Aim(new Target.Point(s.getPosition()), s.getPosition(), null), Loadout.Nuke.DEFAULT, path);
    }

    private static int at(CommandContext<CommandSourceStack> ctx, WeaponType w, int count, int spread, Place place, Path path) {
        return fire(ctx.getSource(), w, count, spread, place.aim(ctx), Loadout.Nuke.DEFAULT, path);
    }

    /** “at …” target from a command. */
    private interface Place {
        ServerActions.Aim aim(CommandContext<CommandSourceStack> ctx);
    }

    /**
     * “x y z” is the point itself, at that height: in the air the projectile explodes in the air (a bomb falls to the ground
     * below it); underground, a bomb penetrates toward it.
     */
    private static ServerActions.Aim point(CommandContext<CommandSourceStack> ctx) {
        Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
        return new ServerActions.Aim(new Target.Point(pos), pos, null);
    }

    /**
     * “x z” is a ground position, as on the designator map ({@link Target.Ground}): the server finds the height when the chunk
     * there is ready (the projectile loads its target area itself). The caller does not know a distant position's height,
     * and a point above ground means an airburst.
     */
    private static ServerActions.Aim ground(CommandContext<CommandSourceStack> ctx) {
        Vec3 p = column(ctx, "place");
        return ServerActions.groundAim(ctx.getSource().getLevel(), p.x, p.z, Optional.empty());
    }

    private static int entity(CommandContext<CommandSourceStack> ctx, WeaponType w, int count, int spread, Entity target, Path path) {
        CommandSourceStack s = ctx.getSource();
        if (target.level() != s.getLevel()) {
            s.sendFailure(Component.translatable("incoming.player_other_world", target.getDisplayName()));
            return 0;
        }
        return fire(s, w, count, spread, ServerActions.atPlayer(target), Loadout.Nuke.DEFAULT, path);
    }

    /**
     * Launch on behalf of a player (approaching from behind them) or the console/command block; the launch position and
     * waypoints in {@code path} are checked first ({@link ServerActions#routeProblem}).
     */
    private static int fire(CommandSourceStack s, WeaponType w, int count, int spread, ServerActions.Aim aim, Loadout.Nuke nuke, Path path) {
        ServerPlayer player = s.getEntity() instanceof ServerPlayer p ? p : null;
        ServerLevel level = player != null ? player.serverLevel() : s.getLevel();
        MutableComponent problem = ServerActions.routeProblem(level, player, rules(s), w, path.from(), path.via(), aim.point());
        if (problem != null) {
            s.sendFailure(problem);
            return 0;
        }
        boolean ok = player != null
                ? ServerActions.strike(player, w, count, spread, aim, nuke, path.via(), path.from(), rules(s))
                : ServerActions.dispatch(level, s.getTextName(), s.getRotation().y, w, count, spread, aim, nuke, path.via(), path.from());
        return ok ? 1 : 0;
    }

    // ---------------------------------------------------------------- fixed launcher

    /**
     * “launcher x y z …” — fixed launcher for the game master and scripts (operator only): status; fire its mission,
     * as with a redstone signal; mission as from the designator, without player rules (target is a position); clear mission;
     * stock munitions for the mission's weapon; owner (projectiles act on their behalf, allies by {@code /team}) or unowned
     * (without owner rules).
     */
    private static LiteralArgumentBuilder<CommandSourceStack> launcher() {
        RequiredArgumentBuilder<CommandSourceStack, Coordinates> at = Commands.argument("launcher", BlockPosArgument.blockPos())
                .executes(ctx -> {
                    FixedLauncherBlockEntity be = launcherAt(ctx);
                    ctx.getSource().sendSuccess(be::status, false);
                    return 1;
                });
        at.then(Commands.literal("fire").executes(ctx -> {
            FixedLauncherBlockEntity be = launcherAt(ctx);
            Component problem = be.fire(ctx.getSource().getLevel());
            if (problem != null) {
                ctx.getSource().sendFailure(problem);
                return 0;
            }
            ctx.getSource().sendSuccess(be::status, true);
            return 1;
        }));
        at.then(Commands.literal("clear").executes(ctx -> {
            FixedLauncherBlockEntity be = idleLauncherAt(ctx);
            be.setMission(null);
            ctx.getSource().sendSuccess(be::status, true);
            return 1;
        }));
        at.then(Commands.literal("load").then(Commands.argument("items", IntegerArgumentType.integer(1, FixedLauncherBlockEntity.SLOTS * 64))
                .executes(ctx -> loadLauncher(ctx, IntegerArgumentType.getInteger(ctx, "items")))));
        at.then(Commands.literal("owner")
                .then(Commands.literal("none").executes(ctx -> launcherOwner(ctx, null)))
                .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> launcherOwner(ctx, EntityArgument.getPlayer(ctx, "player")))));
        LiteralArgumentBuilder<CommandSourceStack> mission = Commands.literal("mission");
        for (WeaponType w : WeaponType.values()) {
            if (!Mission.accepts(w)) continue;
            for (String name : names(w)) {
                mission.then(Commands.literal(name).then(Commands.argument("count", IntegerArgumentType.integer(1, Loadout.MAX_COUNT))
                        .then(Commands.argument("spread", IntegerArgumentType.integer(0, Loadout.MAX_SPREAD))
                                .then(missionTarget(Commands.argument("pos", Vec3Argument.vec3()), w, IncomingCommand::point))
                                .then(missionTarget(Commands.argument("place", Vec2Argument.vec2()), w, IncomingCommand::ground)))));
            }
        }
        return Commands.literal("launcher").requires(s -> s.hasPermission(2)).then(at.then(mission));
    }

    /** Mission target ({@code place} — a point or ground position) and waypoints, if the weapon flies through them. */
    private static RequiredArgumentBuilder<CommandSourceStack, Coordinates> missionTarget(RequiredArgumentBuilder<CommandSourceStack, Coordinates> node,
                                                                                         WeaponType w, Place place) {
        node.executes(ctx -> launcherMission(ctx, w, place, Waypoints.NONE));
        if (w.spec().route().waypoints()) node.then(via((ctx, path) -> launcherMission(ctx, w, place, path.via()), false));
        return node;
    }

    /** Launcher at the command's position: only in a ready chunk (do not load it for a command). */
    private static FixedLauncherBlockEntity launcherAt(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos pos = BlockPosArgument.getBlockPos(ctx, "launcher");
        if (!Terrain.ready(level, pos)) throw LAUNCHER_NOT_LOADED.create();
        if (level.getBlockEntity(pos) instanceof FixedLauncherBlockEntity be) return be;
        throw NO_LAUNCHER.create();
    }

    /** Launcher whose mission can be changed: its previous order is not running ({@link LauncherOrders#busy}). */
    private static FixedLauncherBlockEntity idleLauncherAt(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        FixedLauncherBlockEntity be = launcherAt(ctx);
        if (LauncherOrders.busy(ctx.getSource().getLevel(), be)) throw LAUNCHER_BUSY.create();
        return be;
    }

    private static int launcherMission(CommandContext<CommandSourceStack> ctx, WeaponType w, Place place, Waypoints via) throws CommandSyntaxException {
        FixedLauncherBlockEntity be = idleLauncherAt(ctx);
        ServerActions.Aim aim = place.aim(ctx);
        CommandSourceStack s = ctx.getSource();
        MutableComponent problem = ServerActions.routeProblem(s.getLevel(), null, false, w, new LaunchOrigin.Fixed(be.getBlockPos()), via, aim.point());
        if (problem != null) {
            s.sendFailure(problem);
            return 0;
        }
        be.setMission(new Mission(w, count(ctx), spread(ctx), aim.target(), aim.point(), via));
        s.sendSuccess(be::status, true);
        return 1;
    }

    private static int loadLauncher(CommandContext<CommandSourceStack> ctx, int items) throws CommandSyntaxException {
        FixedLauncherBlockEntity be = launcherAt(ctx);
        Mission m = be.mission();
        if (m == null) {
            ctx.getSource().sendFailure(Component.translatable("incoming.fixed_launcher.no_mission.short"));
            return 0;
        }
        ItemStack rest = be.load(new ItemStack(m.weapon().spec().munition().item().get(), items));
        int loaded = items - rest.getCount();
        ctx.getSource().sendSuccess(() -> Component.translatable("incoming.fixed_launcher.loaded", loaded).append(" ").append(be.status()), true);
        return loaded;
    }

    private static int launcherOwner(CommandContext<CommandSourceStack> ctx, @Nullable ServerPlayer owner) throws CommandSyntaxException {
        FixedLauncherBlockEntity be = launcherAt(ctx);
        be.setOwner(owner == null ? null : owner.getUUID());
        ctx.getSource().sendSuccess(() -> owner == null ? Component.translatable("incoming.fixed_launcher.owner.none")
                : Component.translatable("incoming.fixed_launcher.owner", owner.getDisplayName()), true);
        return 1;
    }

    /**
     * A command order follows designator rules ({@link ServerActions#sighted}) and pays munitions, except for server
     * operators (and {@code /execute as} from the console or a command block — their permissions apply): the host and game
     * master stage events without rules, as do the console and command blocks.
     */
    private static boolean rules(CommandSourceStack s) {
        return !s.hasPermission(2);
    }

    private static int give(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players) {
        for (ServerPlayer p : players) {
            if (!p.getInventory().add(ModItems.DESIGNATOR.get().getDefaultInstance())) p.drop(ModItems.DESIGNATOR.get().getDefaultInstance(), false);
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("incoming.given", players.size()), true);
        return players.size();
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack s = ctx.getSource();
        s.sendSystemMessage(Component.translatable("incoming.help.title").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
        for (int i = 1; i <= 14; i++) {
            s.sendSystemMessage(Component.translatable("incoming.help." + i).withStyle(ChatFormatting.GRAY));
        }
        return 1;
    }
}
