package ua.zentix.incoming.strike;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.StringUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.entity.DebrisEntity;
import ua.zentix.incoming.entity.LauncherEntity;
import ua.zentix.incoming.entity.SpentBoosterEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.guidance.FlightController;
import ua.zentix.incoming.item.DesignatorItem;
import ua.zentix.incoming.launcher.LauncherLinks;
import ua.zentix.incoming.launcher.LauncherOrders;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.registry.ModDataComponents;
import ua.zentix.incoming.target.Sides;
import ua.zentix.incoming.target.Sightings;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.target.TargetPicker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Player actions from designator packets and commands: launch, designator settings, abort. Everything validated
 * here.
 */
public final class ServerActions {
    /** Launch and abort at most once per 4 ticks per player: button debounce and packet-spam protection. */
    private static final int FIRE_INTERVAL = 4;
    /** Client craft hint: its world point must be within this many blocks of aim point. */
    private static final double AIRCRAFT_HINT_SLACK = 32;

    private ServerActions() {}

    public static boolean mayUse(Player player) {
        return IncomingConfig.SERVER.designatorForEveryone.get() || player.hasPermissions(2);
    }

    // ---------------------------------------------------------------- packets

    public static void fire(C2S.Fire p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        if (!mayUse(player)) {
            player.displayClientMessage(Component.translatable("incoming.no_permission").withStyle(ChatFormatting.RED), true);
            return;
        }
        if (tooSoon(player, ModAttachments.LAST_FIRE.get())) return;

        Loadout l = p.loadout();
        Aim aim = p.aim().isPresent() ? fromHint(player, p.aim().get()) : fromMode(player, l, p.aircraft().orElse(null));
        if (aim == null) return;
        // Designator linked to fixed launchers assigns missions instead of launching itself: they launch from their sites.
        ItemStack designator = designator(player);
        if (!LauncherLinks.of(designator).isEmpty()) {
            LauncherOrders.assign(player, designator, l, aim, p.via());
            return;
        }
        LaunchOrigin from = null;
        if (p.from().isPresent()) {
            Vec3 at = new Vec3(p.from().get().x, 0, p.from().get().z);
            MutableComponent bad = launchSiteProblem(player, at);
            if (bad != null) {
                player.displayClientMessage(bad.withStyle(ChatFormatting.RED), true);
                return;
            }
            from = new LaunchOrigin.Place(at);
        }
        MutableComponent problem = routeProblem(player.serverLevel(), player, true, l.weapon(), from, p.via(), aim.point());
        if (problem != null) {
            player.displayClientMessage(problem.withStyle(ChatFormatting.RED), true);
            return;
        }
        // Designator follows player rules for everyone, operators included: order pays munitions.
        strike(player, l.weapon(), l.count(), l.spread(), aim, l.nuke(), p.via(), from, true);
    }

    /**
     * Problem with launch site {@code at} (horizontal) chosen on designator map; null if valid. Only
     * operator/host may choose ({@link NuclearKeys#trusted}: nuclear orders also bypass second key), only where a
     * map target is valid ({@link #groundInRange}: no world ceiling, within {@code map_range} of player and world
     * bounds). Like {@code from x z}, launcher placed there ({@link LaunchSite.Post}).
     */
    @Nullable
    public static MutableComponent launchSiteProblem(ServerPlayer player, Vec3 at) {
        if (!NuclearKeys.trusted(player)) return Component.translatable("incoming.map.launch.ops_only");
        if (!inWorld(player.serverLevel(), at)) return outsideWorld(at);
        if (player.level().dimensionType().hasCeiling()) return Component.translatable("incoming.map.no_ceiling");
        if (!groundInRange(player, at.x, at.z)) {
            return Component.translatable("incoming.map.launch.out_of_range", IncomingConfig.SERVER.mapRange.get());
        }
        return null;
    }

    /**
     * Designator issuing “Fire”: the one viewed through (binoculars), otherwise first in hands as on designator
     * screen; empty means absent.
     */
    private static ItemStack designator(ServerPlayer player) {
        if (player.isUsingItem() && player.getUseItem().getItem() instanceof DesignatorItem) return player.getUseItem();
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof DesignatorItem) return stack;
        }
        return ItemStack.EMPTY;
    }

    /**
     * Problem with launch {@code from} and operator route {@code via} ({@link Waypoints}) for order aimed at
     * {@code aim}; null if valid (also if absent). Site/points must be finite within world bounds; under {@code
     * rules} (designator, non-operator player command), points must be valid map sites for shooter ({@link
     * #groundInRange}). Launch site is specified by unrestricted order (operator command, console) or
     * operator/host designator map ({@link #launchSiteProblem}); fixed launcher also by designator mission. Not
     * all weapons accept waypoints ({@link WeaponSpec.Route#waypoints}); path through them to target must fit
     * route reach (spec, {@link WeaponSpec.Route#reach}), from launch site, otherwise shooter, or console's first
     * point (projectile appears before it from afar).
     *
     * @param shooter player issuing order; null for console/command block
     */
    @Nullable
    public static MutableComponent routeProblem(ServerLevel level, @Nullable ServerPlayer shooter, boolean rules, WeaponType weapon,
                                                @Nullable LaunchOrigin from, Waypoints via, Vec3 aim) {
        if (from != null && !inWorld(level, from.at())) return outsideWorld(from.at());
        if (via.isEmpty()) return null;
        WeaponSpec.Route route = weapon.spec().route();
        if (!route.waypoints()) return Component.translatable("incoming.route.unsupported", weapon.displayName());
        for (Vec3 q : via.points()) {
            if (!inWorld(level, q)) return outsideWorld(q);
            if (rules && shooter != null && !groundInRange(shooter, q.x, q.z)) {
                return Component.translatable("incoming.route.out_of_range", IncomingConfig.SERVER.mapRange.get());
            }
        }
        Vec3 start = from != null ? from.at() : shooter != null ? shooter.position() : via.points().getFirst();
        if (!via.within(weapon, start, aim)) {
            return Component.translatable("incoming.route.too_long", weapon.displayName(), kilometres(via.length(start, aim)), kilometres(route.reach()));
        }
        return null;
    }

    /** Finite coordinates within world bounds (NaN passes range comparisons; infinity breaks chunks). */
    private static boolean inWorld(ServerLevel level, Vec3 q) {
        return Double.isFinite(q.x) && Double.isFinite(q.z) && level.getWorldBorder().isWithinBounds(q.x, q.z);
    }

    private static MutableComponent outsideWorld(Vec3 q) {
        return Component.translatable("incoming.route.outside_world", Mth.floor(q.x), Mth.floor(q.z));
    }

    /** Kilometers with one decimal for player messages (dot, not Russian locale comma: translated line). */
    private static String kilometres(double blocks) {
        return String.format(Locale.ROOT, "%.1f", blocks / 1000);
    }

    /** Retarget own projectile from camera: server finds the same crosshair target and validates. */
    public static void retarget(C2S.Retarget p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player) || !mayUse(player)) return;
        ServerLevel level = player.serverLevel();
        if (!(level.getEntity(p.projectile()) instanceof StrikeProjectile proj) || !player.getUUID().equals(proj.ownerId())) return;
        // Non-operator nuclear projectile goes where warning was announced and second key turned.
        if (proj.isNuclear() && !NuclearKeys.trusted(player)) {
            player.displayClientMessage(Component.translatable("incoming.nuke.no_retarget").withStyle(ChatFormatting.RED), true);
            return;
        }
        C2S.AimHint h = p.aim();
        // Camera sees no farther than projectile render distance.
        if (!valid(h) || h.point().distanceToSqr(proj.position()) > 1024 * 1024) return;
        if (tooSoon(player, ModAttachments.LAST_RETARGET.get())) return;
        Aim hinted = resolveHint(level, player, h);
        Aim aim = hinted == null ? null : sighted(player, hinted, subject -> CameraLink.holds(level, proj, subject, hinted.point()));
        if (aim == null || !proj.retarget(aim.target(), aim.point())) return;
        Component what = aim.label() != null ? aim.label() : Component.translatable("incoming.target.point");
        player.displayClientMessage(Component.translatable("incoming.retargeted", what).withStyle(ChatFormatting.GOLD), true);
        Incoming.LOG.info("Retarget: {} → {} {} {} — {}", proj.getType().getDescriptionId(), Mth.floor(aim.point().x),
                Mth.floor(aim.point().y), Mth.floor(aim.point().z), player.getGameProfile().getName());
    }

    /** Projectile camera views aboard an owned projectile ({@link CameraLink}). */
    public static void cameraView(C2S.CameraView p, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) CameraLink.receive(player, p.projectile(), p.yaw(), p.pitch());
    }

    public static void setLoadout(C2S.SetLoadout p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        ItemStack stack = player.getItemInHand(p.hand());
        if (!(stack.getItem() instanceof DesignatorItem)) return;
        stack.set(ModDataComponents.LOADOUT.get(), clamp(p.loadout()));
    }

    /**
     * Players and sightings for designator map, to players allowed designator ({@link #mapPlayers(ServerPlayer,
     * Collection)}).
     */
    public static void mapPlayers(C2S.MapPlayers p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        S2C.MapPlayers marks = mapPlayers(player, player.server.getPlayerList().getPlayers());
        if (marks != null) PacketDistributor.sendToPlayer(player, marks);
    }

    /**
     * Map markers for {@code viewer}: same dimension, within {@code map_range} (farther strikes would be
     * rejected), excluding self, spectators, invisible players. Team friendlies ({@link Sides}) from {@code
     * players} at current positions; hostile players from {@code players} and other side sightings ({@link
     * Sightings}) at last-seen positions with age; without {@code sight_rules}, all players at current positions.
     * Same markers supply designator “player” target list: {@code map_players} only disables map drawing. Null
     * means no response: no designator permission or request faster than once per {@link #FIRE_INTERVAL} ticks.
     */
    @Nullable
    public static S2C.MapPlayers mapPlayers(ServerPlayer viewer, Collection<? extends ServerPlayer> players) {
        if (!mayUse(viewer) || tooSoon(viewer, ModAttachments.LAST_MAP_PLAYERS.get())) return null;
        return new S2C.MapPlayers(marks(viewer, players), IncomingConfig.SERVER.mapPlayers.get());
    }

    private static List<S2C.MapPlayer> marks(ServerPlayer viewer, Collection<? extends ServerPlayer> players) {
        boolean rules = IncomingConfig.SERVER.sightRules.get();
        ServerLevel level = viewer.serverLevel();
        List<S2C.MapPlayer> marks = new ArrayList<>();
        Map<UUID, ServerPlayer> others = new HashMap<>();
        for (ServerPlayer other : players) {
            if (other == viewer || other.level() != level || other.isSpectator() || other.isInvisible()) continue;
            boolean friendly = Sides.friendly(viewer, other);
            if (!friendly && rules) {
                others.put(other.getUUID(), other);
            } else if (withinMapRange(viewer, other.getX(), other.getZ())) {
                marks.add(new S2C.MapPlayer(other.getUUID(), mapName(other.getGameProfile().getName()), other.getX(), other.getZ(),
                        Sightings.Kind.PLAYER, friendly, 0));
            }
        }
        if (!rules) return marks;
        long now = level.getGameTime();
        for (Sightings.Contact c : Sightings.contacts(level, Sides.side(viewer))) {
            // Player who left world, became friendly, or became spectator is not a target.
            if (c.kind() == Sightings.Kind.PLAYER && !others.containsKey(c.id())) continue;
            if (!withinMapRange(viewer, c.pos().x, c.pos().z)) continue;
            int age = (int) (Math.max(0, c.age(now) - Sightings.CURRENT) / 20);
            marks.add(new S2C.MapPlayer(c.id(), mapName(c.name()), c.pos().x, c.pos().z, c.kind(), false, age));
        }
        return marks;
    }

    /** Codec refuses over-limit names (exception on send): mods can have long names. */
    private static String mapName(String name) {
        return StringUtil.truncateStringIfNecessary(name, S2C.MapPlayer.MAX_NAME, false);
    }

    /**
     * Designator “Abort” recalls own strikes for everyone, operators included ({@link #recall}); operator/host
     * command clears all.
     */
    public static void clear(C2S.Clear p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        if (!mayUse(player) || tooSoon(player, ModAttachments.LAST_FIRE.get())) return;
        player.sendSystemMessage(recall(player).withStyle(ChatFormatting.GRAY));
    }

    /** Same kind of action occurred within {@link #FIRE_INTERVAL} ticks; otherwise record now. */
    private static boolean tooSoon(ServerPlayer player, AttachmentType<Long> last) {
        long now = player.serverLevel().getGameTime();
        if (player.hasData(last) && now - player.getData(last) < FIRE_INTERVAL) return true;
        player.setData(last, now);
        return false;
    }

    /** Hint coordinates finite: NaN passes range comparisons; infinity breaks chunks. */
    private static boolean valid(C2S.AimHint h) {
        return finite(h.point()) && finite(h.plotPos());
    }

    private static boolean finite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    // ---------------------------------------------------------------- shared logic

    /** Launch target: what to pursue, current position, display name. */
    public record Aim(Target target, Vec3 point, @Nullable Component label) {}

    public static Loadout clamp(Loadout l) {
        boolean onCarrier = l.nuke().onCarrier() && IncomingConfig.SERVER.carrierNukes.get();
        Loadout.Nuke n = new Loadout.Nuke(Math.min(l.nuke().yieldKt(), IncomingConfig.SERVER.nukeMaxYield.get()), l.nuke().airBurst(), onCarrier);
        Loadout clamped = new Loadout(l.weapon(), l.count(), l.spread(), l.mode(), l.player(), n);
        // No nuclear salvos: one ICBM, one missile, one bomb.
        if (clamped.nuclear()) return new Loadout(l.weapon(), 1, 0, l.mode(), l.player(), n);
        return new Loadout(l.weapon(), Math.min(l.count(), IncomingConfig.SERVER.maxSalvo.get()),
                Math.min(l.spread(), IncomingConfig.SERVER.maxSpread.get()), l.mode(), l.player(), n);
    }

    /** Nuclear weapons: enabled and allowed for this player (default operators only). */
    public static boolean mayUseNuke(ServerPlayer player) {
        return IncomingConfig.SERVER.nukeEnabled.get() && (!IncomingConfig.SERVER.nukeOpsOnly.get() || player.hasPermissions(2));
    }

    /**
     * Launch for player (approach behind them or via their {@code via}, from {@code from} or near them, already
     * checked by {@link #routeProblem}): one projectile exactly on target or a salvo, after permissions.
     *
     * @param rules player-rule order: designator always (operators too), command for non-operator player; target
     * within {@code map_range}, hostile players/craft only sighted ({@link #sighted}); order pays inventory
     * munitions ({@link Munitions}; creative does not pay). Server operator command and console ({@link
     * #dispatch}) strike without these rules
     * @return true if launched
     */
    public static boolean strike(ServerPlayer player, WeaponType weapon, int count, int spread, Aim aim, Loadout.Nuke nuke, Waypoints via,
                                 @Nullable LaunchOrigin from, boolean rules) {
        if (rules) {
            aim = ruled(player, aim);
            if (aim == null) return false;
        }
        Loadout l = order(weapon, count, spread, nuke);
        if (l.nuclear() && !mayUseNuke(player)) {
            player.displayClientMessage(Component.translatable(IncomingConfig.SERVER.nukeEnabled.get()
                    ? "incoming.nuke.ops_only" : "incoming.nuke.disabled").withStyle(ChatFormatting.RED), true);
            return false;
        }
        int limit = IncomingConfig.SERVER.maxActivePerPlayer.get();
        if (limit > 0 && !player.hasPermissions(2)) {
            int active = StrikeWorld.active(player.server, player.getUUID());
            if (active >= limit) {
                player.displayClientMessage(Component.translatable("incoming.too_many_active", active, limit).withStyle(ChatFormatting.RED), true);
                return false;
            }
        }
        // Whole order at once: do not shrink salvo to inventory contents.
        Munitions.Bill bill = rules && !player.hasInfiniteMaterials() ? Munitions.Bill.of(l) : null;
        if (bill != null && !Munitions.pay(player, bill)) return false;
        if (aim.label() != null) player.sendSystemMessage(locked(weapon, aim).withStyle(ChatFormatting.GOLD));
        // Non-operator nuclear strike: second key and at least 90 s target warning (such order has no launch site:
        // only operator/host specify it by command or designator map, and they need no second key).
        if (l.nuclear() && rules && !NuclearKeys.trusted(player)) {
            return NuclearKeys.order(player, player.server.getPlayerList().getPlayers(), l, aim, via, bill);
        }
        return launch(player.serverLevel(), player.getGameProfile().getName(), player, player.getYRot(), l, aim, via, from, bill);
    }

    /**
     * Player {@code player}'s ruled order target: within {@code map_range}, hostiles sighted only ({@link
     * #sighted}); null if rejected (player receives line).
     */
    @Nullable
    public static Aim ruled(ServerPlayer player, Aim aim) {
        if (!withinMapRange(player, aim.point().x, aim.point().z)) {
            player.displayClientMessage(Component.translatable("incoming.map.out_of_range", IncomingConfig.SERVER.mapRange.get())
                    .withStyle(ChatFormatting.RED), true);
            return null;
        }
        return sighted(player, aim, subject -> seesNow(player, subject));
    }

    /**
     * Player order rules. Hostile player (and entity carrying them) or Sable craft must be sighted ({@code
     * sight_rules}, {@link Sightings}): shooter sees now ({@code seen}: designator via eyes or own projectile
     * camera, {@link #seesNow}; camera retarget within frame, {@link CameraLink#holds}), or side saw just now:
     * strike target; earlier but within {@code sight_memory}: strike last-seen position; otherwise reject. Moving
     * entity/craft becomes sighted ({@link Target.Sighted}): strike where seen, then only camera weapons follow
     * while operator holds frame ({@link WeaponSpec.Tracking}). Otherwise names would permit map-wide strikes and
     * continuous tracking (hundreds of Shaheds at one player).
     *
     * @param seen shooter currently sees ordered {@link Target.OfEntity} or {@link Target.OfSubLevel}
     * @return order target; null if rejected (player receives line)
     */
    @Nullable
    public static Aim sighted(ServerPlayer player, Aim aim, Predicate<Target> seen) {
        Target target = aim.target();
        if (!(target instanceof Target.OfEntity) && !(target instanceof Target.OfSubLevel)) return aim;
        Aim sighted = new Aim(new Target.Sighted(target, aim.point()), aim.point(), aim.label());
        if (!IncomingConfig.SERVER.sightRules.get()) return sighted;
        ServerLevel level = player.serverLevel();
        UUID id;
        Component name;
        if (target instanceof Target.OfEntity e) {
            Player rider = level.getEntity(e.uuid()) instanceof Entity victim ? rider(victim) : null;
            if (rider == null || Sides.friendly(player, rider)) return sighted;
            id = rider.getUUID();
            name = rider.getDisplayName();
        } else {
            SubLevelAccess craft = SubLevels.containing(level, ((Target.OfSubLevel) target).plotPos());
            if (craft == null) return sighted;
            id = craft.getUniqueId();
            name = SubLevels.describe(craft);
        }
        if (seen.test(target)) return sighted;
        Sightings.Contact c = Sightings.contact(level, Sides.side(player), id);
        if (c == null) {
            player.displayClientMessage(Component.translatable("incoming.target.unseen", name).withStyle(ChatFormatting.RED), true);
            return null;
        }
        long age = c.age(level.getGameTime());
        if (age <= Sightings.CURRENT) return sighted;
        Vec3 at = c.kind() == Sightings.Kind.PLAYER ? c.pos().add(0, 1, 0) : c.pos();
        player.sendSystemMessage(Component.translatable("incoming.target.last_seen", name, Math.max(1, age / 20)).withStyle(ChatFormatting.GOLD));
        return new Aim(new Target.Point(at), at, null);
    }

    /** Player currently sees ordered {@code subject} through eyes or own projectile camera ({@link Sightings}). */
    private static boolean seesNow(ServerPlayer player, Target subject) {
        ServerLevel level = player.serverLevel();
        return switch (subject) {
            case Target.OfEntity e -> level.getEntity(e.uuid()) instanceof Entity victim
                    && (Sightings.sees(player, victim) || rider(victim) instanceof Player rider && rider != victim && Sightings.sees(player, rider));
            case Target.OfSubLevel s -> SubLevels.containing(level, s.plotPos()) instanceof SubLevelAccess craft && Sightings.seesCraft(player, craft);
            default -> true;
        };
    }

    /** “Target acquired” line: whether and how projectile follows it. */
    private static MutableComponent locked(WeaponType weapon, Aim aim) {
        return switch (aim.target()) {
            case Target.Sighted s -> weapon.spec().tracking() == WeaponSpec.Tracking.CAMERA
                    ? Component.translatable("incoming.target.locked.camera", aim.label(), Component.keybind(Incoming.CAMERA_KEY))
                    : Component.translatable("incoming.target.locked.point", aim.label());
            // Unrestricted order: projectile follows target.
            case Target.OfEntity e -> Component.translatable("incoming.target.locked", aim.label());
            case Target.OfSubLevel s -> Component.translatable("incoming.target.locked", aim.label());
            case Target.Point p -> Component.translatable("incoming.target.locked.place", aim.label());
            case Target.Ground g -> Component.translatable("incoming.target.locked.place", aim.label());
        };
    }

    /**
     * Launch without player, from console/command block ({@code who} for log): approach at {@code yaw}, via
     * {@code via}, from {@code from}, already checked by {@link #routeProblem}. Command checks permissions
     * itself.
     *
     * @return true if launched
     */
    public static boolean dispatch(ServerLevel level, String who, float yaw, WeaponType weapon, int count, int spread, Aim aim, Loadout.Nuke nuke,
                                   Waypoints via, @Nullable LaunchOrigin from) {
        return launch(level, who, null, null, yaw, order(weapon, count, spread, nuke), aim, via, from, null);
    }

    /**
     * Fixed launcher {@code at}'s order for {@code owner} (may be offline; null unowned): log and launch from it,
     * one projectile or salvo like player order; launcher checks owner permissions/inventory ({@code
     * launcher.LauncherOrders}) and pays one projectile at a time ({@link StrikeService#launch}).
     *
     * @return true if launched (salvo started)
     */
    public static boolean fromLauncher(ServerLevel level, String who, @Nullable UUID owner, Loadout l, Aim aim, Waypoints via, LaunchOrigin.Fixed at) {
        return launch(level, who, null, owner, 0, clamp(l), aim, via, at, null);
    }

    /** Order within server limits ({@link #clamp}). */
    private static Loadout order(WeaponType weapon, int count, int spread, Loadout.Nuke nuke) {
        return clamp(new Loadout(weapon, count, spread, TargetMode.LOOK, "", nuke));
    }

    /**
     * Log and launch, single or salvo; report to shooter ({@code owner}, if any). With launch {@code from},
     * approach from its side: site-to-target heading replaces {@code yaw}.
     *
     * @param paid shooter payment ({@link Munitions}); null if unpaid. Failed launch refunds it; salvo refunds
     * unlaunched rounds
     */
    private static boolean launch(ServerLevel level, String who, @Nullable ServerPlayer owner, float yaw, Loadout l, Aim aim, Waypoints via,
                                  @Nullable LaunchOrigin from, @Nullable Munitions.Bill paid) {
        return launch(level, who, owner, owner == null ? null : owner.getUUID(), yaw, l, aim, via, from, paid);
    }

    /** {@link #launch} for {@code ownerId}: {@code owner} is shooter when player ordered, not launcher/console. */
    private static boolean launch(ServerLevel level, String who, @Nullable ServerPlayer owner, @Nullable UUID ownerId, float yaw, Loadout l, Aim aim,
                                  Waypoints via, @Nullable LaunchOrigin from, @Nullable Munitions.Bill paid) {
        StrikeService.log(level, who, l.weapon(), l.count(), l.spread(), aim.target(), aim.point(), via, from);
        float course = from != null ? FlightController.anglesTo(from.at(), aim.point())[0] : yaw;
        // Unready launch site: even one projectile uses a salvo, which loads/waits for site (LaunchPlaces), rather than starting
        // outside the loaded world, already cruising, where launch is invisible.
        if (l.count() > 1 || l.spread() > 0 || LaunchPlaces.waits(level, l.weapon(), from)) {
            SalvoData.start(level, l.weapon(), l.count(), l.spread(), aim.target(), aim.point(), course, ownerId, l.nuke(), via, from, paid != null);
            return true;
        }
        StrikeService.Result r;
        String why = "launch failed";
        try {
            r = owner == null ? StrikeService.launch(level, l.weapon(), aim.target(), aim.point(), course, ownerId, true, l.nuke(), via, from)
                    : StrikeService.launchBy(level, l.weapon(), aim.target(), aim.point(), course, owner, true, l.nuke(), via, from);
        } catch (RuntimeException e) {
            // Like salvo (SalvoData.tick): exception during launch counts as failed, refund payment.
            Incoming.LOG.error("Launch {} failed with an exception — {}", l.weapon().getSerializedName(), who, e);
            r = StrikeService.Result.FAILED;
            why = "launch failed with an exception";
        }
        if (owner != null) {
            if (r.ok()) StrikeService.confirm(owner, l.weapon(), r.eta());
            else owner.displayClientMessage(Component.translatable("incoming.launch_failed").withStyle(ChatFormatting.RED), true);
            if (!r.ok() && paid != null) Munitions.refund(List.of(owner), owner.getUUID(), paid, why);
        }
        return r.ok();
    }

    /**
     * Target from binocular/map hint: server resolves what client sees and validates range, aim ({@code
     * aim_range} binocular reach) or map ({@code map_range} horizontally, within world bounds).
     */
    @Nullable
    private static Aim fromHint(ServerPlayer player, C2S.AimHint h) {
        if (!valid(h)) {
            notFound(player);
            return null;
        }
        if (h.kind() == C2S.AimHint.GROUND) {
            // Ceiling world (Nether): column top is bedrock roof, so no map “ground” site.
            if (player.level().dimensionType().hasCeiling()) {
                player.displayClientMessage(Component.translatable("incoming.map.no_ceiling").withStyle(ChatFormatting.RED), true);
                return null;
            }
            if (!groundInRange(player, h.point().x, h.point().z)) {
                player.displayClientMessage(Component.translatable("incoming.map.out_of_range", IncomingConfig.SERVER.mapRange.get())
                        .withStyle(ChatFormatting.RED), true);
                return null;
            }
        } else {
            double range = IncomingConfig.SERVER.aimRange.get() + 32;
            if (h.point().distanceToSqr(player.getEyePosition()) > range * range) {
                notFound(player);
                return null;
            }
        }
        return resolveHint(player.serverLevel(), player, h);
    }

    /** Map site valid: no world ceiling, within horizontal {@code map_range} of player and world bounds. */
    public static boolean groundInRange(ServerPlayer player, double x, double z) {
        if (player.level().dimensionType().hasCeiling()) return false;
        return withinMapRange(player, x, z) && player.level().getWorldBorder().isWithinBounds(x, z);
    }

    /** Within horizontal {@code map_range} of player: designator reach without crosshair (map, player). */
    public static boolean withinMapRange(ServerPlayer player, double x, double z) {
        double range = IncomingConfig.SERVER.mapRange.get();
        double dx = x - player.getX(), dz = z - player.getZ();
        return dx * dx + dz * dz <= range * range;
    }

    /**
     * Client-hinted target: entity/craft if server finds it at the crosshair (and entity aimable as {@link
     * TargetPicker#aimable}), otherwise aim point.
     */
    @Nullable
    private static Aim resolveHint(ServerLevel level, ServerPlayer player, C2S.AimHint h) {
        switch (h.kind()) {
            case C2S.AimHint.ENTITY -> {
                Entity e = level.getEntity(h.entityId());
                if (e != null && TargetPicker.aimable(player).test(e) && e.getBoundingBox().inflate(8).contains(h.point())) {
                    return new Aim(Target.OfEntity.of(e, h.point()), h.point(), e.getDisplayName());
                }
            }
            case C2S.AimHint.AIRCRAFT -> {
                SubLevelAccess sub = SubLevels.containing(level, h.plotPos());
                Vec3 world = sub == null ? null : SubLevels.toWorld(level, h.plotPos());
                // Otherwise aim-point range check would fail to constrain it: point nearby, craft anywhere.
                if (world != null && world.distanceToSqr(h.point()) <= AIRCRAFT_HINT_SLACK * AIRCRAFT_HINT_SLACK) {
                    return new Aim(new Target.OfSubLevel(h.plotPos()), world, SubLevels.describe(sub));
                }
            }
            case C2S.AimHint.GROUND -> {
                return groundAim(level, h);
            }
            default -> {}
        }
        return new Aim(new Target.Point(h.point()), h.point(), null);
    }

    /** Map site: hint x/z, height via {@link Target.Ground#at} (client map top is estimate). */
    public static Aim groundAim(ServerLevel level, C2S.AimHint h) {
        return groundAim(level, h.point().x, h.point().z, h.mapSurface());
    }

    /**
     * Ground site (x, z), from designator map or command: height via {@link Target.Ground#at}, estimate until
     * chunk ready ({@code mapSurface} is client map top).
     */
    public static Aim groundAim(ServerLevel level, double x, double z, Optional<Integer> mapSurface) {
        Target.Ground ground = Target.Ground.at(level, x, z, mapSurface);
        return new Aim(ground, ground.pos(), Component.translatable("incoming.target.map_point", Mth.floor(x), Mth.floor(z)));
    }

    /** Target from designator screen mode. */
    @Nullable
    public static Aim fromMode(ServerPlayer player, Loadout l, @Nullable UUID aircraft) {
        ServerLevel level = player.serverLevel();
        switch (l.mode()) {
            case AROUND_ME -> {
                return new Aim(new Target.Point(player.position()), player.position(), null);
            }
            case PLAYER -> {
                ServerPlayer victim = findPlayer(player.server, l.player());
                if (victim == null) {
                    player.displayClientMessage(Component.translatable("incoming.player_not_found", l.player()).withStyle(ChatFormatting.RED), false);
                    return null;
                }
                if (victim.level() != level) {
                    player.displayClientMessage(Component.translatable("incoming.player_other_world", victim.getDisplayName()).withStyle(ChatFormatting.RED), false);
                    return null;
                }
                // Like map site: server loads/generates target area; world range limits it.
                if (!withinMapRange(player, victim.getX(), victim.getZ())) {
                    player.displayClientMessage(Component.translatable("incoming.player_out_of_range", victim.getDisplayName(),
                            IncomingConfig.SERVER.mapRange.get()).withStyle(ChatFormatting.RED), false);
                    return null;
                }
                return atPlayer(victim);
            }
            case MAP -> {
                // Client sends map site (GROUND hint); without it, no target.
                notFound(player);
                return null;
            }
            case AIRCRAFT -> {
                SubLevelAccess sub = aircraft == null ? null : SubLevels.byId(level, player.position(), aircraft);
                if (sub == null) {
                    notFound(player);
                    return null;
                }
                Vec3 c = SubLevels.center(sub);
                return new Aim(new Target.OfSubLevel(SubLevels.toPlot(sub, c)), c, SubLevels.describe(sub));
            }
            default -> {
                TargetPicker.Pick pick = TargetPicker.pick(level, player, player.getEyePosition(), player.getLookAngle(), IncomingConfig.SERVER.aimRange.get());
                if (pick == null) {
                    notFound(player);
                    return null;
                }
                Component label = pick.kind() == TargetPicker.Kind.ENTITY || pick.kind() == TargetPicker.Kind.PLAYER
                        || pick.kind() == TargetPicker.Kind.AIRCRAFT ? pick.label() : null;
                return new Aim(pick.target(), pick.point(), label);
            }
        }
    }

    /** Player affected by an entity strike: entity itself or someone riding it/with it; null if nobody. */
    @Nullable
    private static Player rider(Entity e) {
        if (e instanceof Player p) return p;
        Entity root = e.getRootVehicle();
        if (root instanceof Player p) return p;
        for (Entity passenger : root.getIndirectPassengers()) {
            if (passenger instanceof Player p) return p;
        }
        return null;
    }

    /** Player/entity strike: body center (one block above feet); unrestricted order follows it. */
    public static Aim atPlayer(Entity victim) {
        return new Aim(new Target.OfEntity(victim.getUUID(), new Vec3(0, 1, 0)), victim.position().add(0, 1, 0), null);
    }

    @Nullable
    public static ServerPlayer findPlayer(MinecraftServer server, String name) {
        if (name == null || name.isBlank()) return null;
        return server.getPlayerList().getPlayerByName(name); // Case-insensitive, like vanilla.
    }

    private static void notFound(ServerPlayer player) {
        player.displayClientMessage(Component.translatable("incoming.target_not_found").withStyle(ChatFormatting.RED), true);
    }

    /**
     * Clear all (operator/host command, console): remove all players' projectiles, debris, launchers in all
     * worlds without explosion; cancel salvos and refund online owners' paid unlaunched rounds ({@link
     * Munitions}; launched rounds, including on launcher, are not refunded). Nuclear strikes (ICBM, nuclear
     * missile/B-2) canceled only by nuclear abort; launcher carrying such missile remains until launch.
     *
     * Log what was removed and by whom: abort removes others' strikes too, but issuer sees only chat totals;
     * without log, another player's “Abort” would erase a strike without trace.
     *
     * @param nuclear cancel nuclear strikes and orders awaiting second key/post-warning launch too (operator/host
     * only, {@link NuclearKeys#trusted})
     * @param who abort issuer for log
     */
    public static int clearAll(MinecraftServer server, boolean nuclear, String who) {
        Predicate<StrikeProjectile> cancelled = p -> nuclear || !p.isNuclear();
        // Canceled projectiles: clients stop sound/camera; remaining nuclear projectiles keep theirs.
        List<UUID> projectiles = new ArrayList<>();
        int n = 0, virtual = 0, launchersRemoved = 0, salvos = 0, detonations = 0;
        List<String> strikes = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            List<UUID> outside = VirtualFlights.get(level).clear(level, cancelled);
            virtual += outside.size();
            projectiles.addAll(outside);
            List<Entity> kill = new ArrayList<>();
            List<LauncherEntity> launchers = new ArrayList<>();
            // Remaining projectiles on rails (in-world/virtual): their launchers remain until launch.
            List<StrikeProjectile> onRail = new ArrayList<>();
            for (StrikeProjectile p : VirtualFlights.get(level).flights()) {
                if (p.flightPhase().onLauncher()) onRail.add(p);
            }
            for (Entity e : level.getAllEntities()) {
                if (e instanceof StrikeProjectile p) {
                    if (cancelled.test(p)) kill.add(p);
                    else if (p.flightPhase().onLauncher()) onRail.add(p);
                } else if (e instanceof LauncherEntity l) {
                    launchers.add(l);
                } else if (e instanceof DebrisEntity || e instanceof SpentBoosterEntity) {
                    kill.add(e);
                }
            }
            for (LauncherEntity l : launchers) {
                if (onRail.stream().noneMatch(l::serves)) kill.add(l);
            }
            for (Entity e : kill) {
                if (e instanceof StrikeProjectile) projectiles.add(e.getUUID());
                else if (e instanceof LauncherEntity) launchersRemoved++;
                e.discard();
            }
            // Nuclear salvo is only one projectile awaiting launch site (ServerActions.clamp, LaunchPlaces); like nuclear projectile,
            // only nuclear abort removes it; refund unlaunched rounds to owners.
            salvos += StrikeWorld.clearSalvos(level, nuclear);
            if (nuclear) {
                NuclearEvents events = NuclearEvents.get(level);
                for (NuclearEvents.ScheduledStrike s : events.scheduled()) {
                    strikes.add("ICBM #" + s.id() + " (" + Math.round(s.yieldKt()) + " kt at " + Mth.floor(s.target().x) + " "
                            + Mth.floor(s.target().y) + " " + Mth.floor(s.target().z) + ")");
                }
                detonations += events.detonations().size();
                n += NuclearStrikes.clear(level);
            }
        }
        if (nuclear) {
            n += NuclearKeys.cancelAll(server, server.getPlayerList().getPlayers(),
                    Component.translatable("incoming.nuke.key.cancelled").withStyle(ChatFormatting.GRAY), "nuclear clear — " + who);
        }
        PacketDistributor.sendToAllPlayers(new S2C.Cleared(Optional.empty(), nuclear, projectiles));
        Incoming.LOG.info("Clear{} — {}: projectiles {} (off-world {}), launchers {}, salvos {}{}{}", nuclear ? " with nukes" : "", who,
                projectiles.size(), virtual, launchersRemoved, salvos, strikes.isEmpty() ? "" : "; " + String.join(", ", strikes),
                detonations == 0 ? "" : "; detonations forgotten " + detonations);
        return n + projectiles.size();
    }

    /**
     * Owner recall: stop {@code owner}'s strikes, leave others. Airborne projectiles self-destruct in air without
     * warhead, pre-release B-2 turns away ({@link StrikeProjectile#recall}); outside loaded world they simply
     * vanish (nobody sees); remove own on-launcher projectiles with launchers; cancel salvos, refund paid
     * unlaunched rounds ({@link SalvoData#cancel}). Cannot recall nuclear ICBM/carriers (operator only, {@link
     * #clearAll}) or unguided airborne projectiles: MLRS rockets and released bombs complete flight ({@link
     * StrikeProjectile#recallable}). Debris and dropped boosters remain.
     *
     * @param who abort issuer for log
     * @return projectiles that stopped attacking and canceled salvos
     */
    public static Recalled recall(MinecraftServer server, UUID owner, String who) {
        Predicate<StrikeProjectile> mine = p -> owner.equals(p.ownerId()) && !p.isNuclear();
        // Projectiles now gone: clients stop sound/camera (turned-away B-2 sounds while departing).
        List<UUID> gone = new ArrayList<>();
        int destroyed = 0, turned = 0, unlaunched = 0, virtual = 0, launchersRemoved = 0, salvos = 0;
        for (ServerLevel level : server.getAllLevels()) {
            List<UUID> outside = VirtualFlights.get(level).clear(level, p -> mine.test(p) && (p.flightPhase().onLauncher() || p.recallable()));
            virtual += outside.size();
            gone.addAll(outside);
            List<StrikeProjectile> flying = new ArrayList<>(), mineOnRail = new ArrayList<>();
            List<LauncherEntity> launchers = new ArrayList<>();
            // Remaining projectiles on rails (in-world/virtual): their launchers remain until launch.
            List<StrikeProjectile> onRail = new ArrayList<>();
            for (StrikeProjectile p : VirtualFlights.get(level).flights()) {
                if (p.flightPhase().onLauncher()) onRail.add(p);
            }
            // Collect only: discard immediately removes from the map traversed by getAllEntities.
            for (Entity e : level.getAllEntities()) {
                if (e instanceof StrikeProjectile p) {
                    if (!p.flightPhase().onLauncher()) {
                        if (mine.test(p)) flying.add(p);
                    } else if (mine.test(p)) {
                        mineOnRail.add(p);
                    } else {
                        onRail.add(p);
                    }
                } else if (e instanceof LauncherEntity l && owner.equals(l.ownerId())) {
                    launchers.add(l);
                }
            }
            for (StrikeProjectile p : mineOnRail) {
                p.discard();
                gone.add(p.getUUID());
                unlaunched++;
            }
            for (LauncherEntity l : launchers) {
                if (onRail.stream().noneMatch(l::serves)) {
                    l.discard();
                    launchersRemoved++;
                }
            }
            // Self-destruct after traversal: explosion mutates world entities.
            for (StrikeProjectile p : flying) {
                if (!p.recall(level)) continue;
                if (p.isRemoved()) {
                    gone.add(p.getUUID());
                    destroyed++;
                } else {
                    turned++;
                }
            }
            // Player abort leaves nuclear salvo (projectile awaiting launch site), like nuclear projectile.
            salvos += SalvoData.get(level).cancel(level, owner, server.getPlayerList().getPlayers(), false);
        }
        PacketDistributor.sendToAllPlayers(new S2C.Cleared(Optional.of(owner), false, gone));
        Incoming.LOG.info("Stand down — {}: self-destructed {}, turned away {}, taken off launchers {} (launchers {}), off-world {}, salvos {}",
                who, destroyed, turned, unlaunched, launchersRemoved, virtual, salvos);
        return new Recalled(destroyed + turned + unlaunched + virtual, salvos);
    }

    /** {@link #recall(MinecraftServer, UUID, String)} for {@code player}: report to them. */
    public static MutableComponent recall(ServerPlayer player) {
        Recalled r = recall(player.server, player.getUUID(), player.getGameProfile().getName());
        return Component.translatable("incoming.recalled", r.projectiles(), r.salvos());
    }

    /** Own-abort result: projectiles that stopped attacking and canceled salvos. */
    public record Recalled(int projectiles, int salvos) {}

    /** Abort result to issuer: whether nuclear strikes were canceled too. */
    public static MutableComponent clearedMessage(int n, boolean nuclear) {
        return Component.translatable(nuclear ? "incoming.cleared" : "incoming.cleared.conventional", n);
    }
}
