package ua.zentix.incoming.strike;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.registry.ModSounds;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Nuclear strike by a non-operator player ({@code nuclear.ops_only = false}; player-rule order, {@code rules} in
 * {@link ServerActions#strike}):
 * <ul>
 * <li><b>second key</b>: paid order waits for another player within {@code second_key_distance} blocks of
 * launcher player to press “Second key” ({@link C2S.NuclearKey}) within {@code second_key_window} seconds. Nearby
 * players get who, weapon, target, and key. Timeout means no launch and payment refunded. Alone online: no key
 * needed. Operator, host, console, command block bypass key ({@link #trusted});
 * <li><b>at least 90 s warning</b> ({@link #MIN_WARNING}): ICBM flies at least 90 s (launch starts target
 * warning/countdown); carrier (cruise missile, B-2) launches 90 s after target warning, siren and line within
 * warning radius; non-operator cannot retarget nuclear projectile from camera;
 * <li><b>launch detected</b>: all players receive “ICBM launch detected from area X, Z” ({@link #AREA_CELL}-block
 * cell) and air-raid siren.
 * </ul>
 * Orders unsaved (last seconds): nuclear abort (operator/host), server stop, or launching player logout before
 * second key cancel with refund. Launching player cannot cancel their own nuclear strike: nuclear abort is only
 * for operator/host. Separate per world (in {@link StrikeWorld}); online players passed as parameter: GameTest
 * {@code FakePlayer} is absent from player list.
 */
public final class NuclearKeys {
    /** Target warning at least this many ticks before impact (90 s). */
    public static final int MIN_WARNING = 90 * 20;
    /** Launch-area cell for “Launch detected” line, blocks. */
    public static final int AREA_CELL = 256;
    /** “Second key” key (client, {@code client.Keys}); player lines render its client binding. */
    public static final String KEY = "key.incoming.second_key";
    /** Ticks between inviting second keys from players arriving near launcher player after order. */
    private static final int PROMPT_EVERY = 20;

    private final List<Order> orders = new ArrayList<>();

    /** For {@link StrikeWorld}: per-world, unsaved. */
    NuclearKeys() {}

    public static NuclearKeys get(ServerLevel level) {
        return StrikeWorld.get(level).keys();
    }

    /** Order awaiting second key or, for carrier, pre-launch warning end. */
    private static final class Order {
        final ServerLevel level;
        final UUID ownerId;
        final String name;
        final Loadout loadout;
        final ServerActions.Aim aim;
        final Waypoints via;
        @Nullable
        final Munitions.Bill paid;
        final long created;
        final long deadline;
        /** Players already invited to turn the key. */
        final Set<UUID> prompted = new HashSet<>();
        /** Last known launching-player object (different after respawn, found by UUID among online players). */
        ServerPlayer owner;
        Vec3 lastPos;
        float lastYaw;
        /** Carrier launch tick after warning; -1 waits for second key. */
        long launchAt = -1;
        @Nullable
        String confirmer;

        Order(ServerPlayer owner, Loadout loadout, ServerActions.Aim aim, Waypoints via, @Nullable Munitions.Bill paid, long deadline) {
            this.level = owner.serverLevel();
            this.ownerId = owner.getUUID();
            this.name = owner.getGameProfile().getName();
            this.loadout = loadout;
            this.aim = aim;
            this.via = via;
            this.paid = paid;
            this.created = level.getGameTime();
            this.deadline = deadline;
            this.owner = owner;
            this.lastPos = owner.position();
            this.lastYaw = owner.getYRot();
        }

        boolean icbm() {
            return loadout.weapon().spec().launch() == WeaponSpec.Launch.ICBM;
        }

        /** Launching player in game: online UUID lookup, otherwise previous object if still in world (GameTest). */
        @Nullable
        ServerPlayer owner(Collection<? extends ServerPlayer> online) {
            ServerPlayer found = null;
            for (ServerPlayer p : online) {
                if (p.getUUID().equals(ownerId)) found = p;
            }
            if (found == null && !owner.isRemoved() && !owner.hasDisconnected()) found = owner;
            if (found != null) {
                owner = found;
                lastPos = found.position();
                lastYaw = found.getYRot();
            }
            return found;
        }
    }

    /** Bypass second key and advance warning: operator and singleplayer/LAN host. Also allowed nuclear abort. */
    public static boolean trusted(ServerPlayer player) {
        return player.hasPermissions(2) || player.server.isSingleplayerOwner(player.getGameProfile());
    }

    /**
     * Non-operator nuclear order (permissions/payment checked): others online means second-key wait; alone,
     * immediate, but at least 90 s warning.
     *
     * @param online online players (server list; GameTest its own)
     * @param paid launching player's payment ({@link Munitions}); null if unpaid (creative)
     * @return order accepted: launched, awaiting second key, or carrier pre-launch warning running
     */
    public static boolean order(ServerPlayer owner, Collection<? extends ServerPlayer> online, Loadout loadout, ServerActions.Aim aim, Waypoints via,
                                @Nullable Munitions.Bill paid) {
        ServerLevel level = owner.serverLevel();
        NuclearKeys keys = get(level);
        Order o = new Order(owner, loadout, aim, via, paid, level.getGameTime() + window());
        boolean alone = true;
        for (ServerPlayer p : online) {
            if (!p.getUUID().equals(owner.getUUID())) alone = false;
        }
        if (alone) return keys.arm(o, online);
        keys.orders.add(o);
        owner.sendSystemMessage(Component.translatable("incoming.nuke.key.wait", what(loadout), distance(), Component.keybind(KEY),
                window() / 20).withStyle(ChatFormatting.GOLD));
        keys.prompt(o, online, owner);
        Incoming.LOG.info("Nuclear launch waits {} s for a second key: {} at {} {} {} — {}", window() / 20, loadout.weapon().getSerializedName(),
                Mth.floor(aim.point().x), Mth.floor(aim.point().y), Mth.floor(aim.point().z), o.name);
        return true;
    }

    /** “Second key” packet. */
    public static void onKey(C2S.NuclearKey p, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) confirm(player, player.server.getPlayerList().getPlayers());
    }

    /**
     * {@code by} turned second key: earliest order by a launching player within {@code second_key_distance}
     * (cannot confirm own order).
     *
     * @return order confirmed
     */
    public static boolean confirm(ServerPlayer by, Collection<? extends ServerPlayer> online) {
        double d = distance();
        Order best = null;
        NuclearKeys where = null;
        for (ServerLevel level : by.server.getAllLevels()) {
            if (!level.hasData(ModAttachments.STRIKE_WORLD)) continue;
            NuclearKeys keys = get(level);
            for (Order o : keys.orders) {
                if (o.launchAt >= 0 || o.ownerId.equals(by.getUUID())) continue;
                ServerPlayer owner = o.owner(online);
                if (owner == null || owner.level() != by.level() || owner.distanceToSqr(by) > d * d) continue;
                if (best == null || o.created < best.created) {
                    best = o;
                    where = keys;
                }
            }
        }
        if (best == null) {
            by.displayClientMessage(Component.translatable("incoming.nuke.key.none", distance()).withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        best.confirmer = by.getGameProfile().getName();
        Incoming.LOG.info("Second key: {} confirmed nuclear launch {} — {}", best.confirmer, best.loadout.weapon().getSerializedName(), best.name);
        Component done = Component.translatable("incoming.nuke.key.confirmed", by.getDisplayName(), what(best.loadout)).withStyle(ChatFormatting.GOLD);
        by.sendSystemMessage(done);
        ServerPlayer owner = best.owner(online);
        if (owner != null) owner.sendSystemMessage(done);
        where.arm(best, online);
        return true;
    }

    /**
     * Keys turned (or launching player alone online): ICBM immediately, at least 90 s flight; carrier warns
     * target now, launches in 90 s.
     */
    private boolean arm(Order o, Collection<? extends ServerPlayer> online) {
        if (o.icbm()) {
            orders.remove(o);
            return launch(o, online).ok();
        }
        o.launchAt = o.level.getGameTime() + MIN_WARNING;
        if (!orders.contains(o)) orders.add(o);
        alarm(o, online);
        ServerPlayer owner = o.owner(online);
        if (owner != null) {
            owner.sendSystemMessage(Component.translatable("incoming.nuke.alarm.armed", MIN_WARNING / 20).withStyle(ChatFormatting.GOLD));
        }
        Incoming.LOG.info("Nuclear alarm at target {} {} {}: {} — launch in {} s — {}", Mth.floor(o.aim.point().x), Mth.floor(o.aim.point().y),
                Mth.floor(o.aim.point().z), o.loadout.weapon().getSerializedName(), MIN_WARNING / 20, o.name);
        return true;
    }

    /**
     * Target warning before carrier launch: siren (as for approaching projectile), line to all within nuclear
     * warning radius.
     */
    private static void alarm(Order o, Collection<? extends ServerPlayer> online) {
        Vec3 at = o.aim.point();
        StrikeService.siren(o.level, o.loadout.weapon(), at);
        double r = IncomingConfig.SERVER.nukeWarningRadius.get();
        Component line = Component.translatable("incoming.nuke.alarm.carrier", what(o.loadout), cell(at.x), cell(at.z), MIN_WARNING / 20)
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
        for (ServerPlayer p : online) {
            if (p.level() == o.level && p.position().distanceToSqr(at) <= r * r) p.sendSystemMessage(line);
        }
    }

    /**
     * Execute order: log, launch, report to launcher player; failure refunds, success announces “launch detected”
     * to everyone.
     */
    private static StrikeService.Result launch(Order o, Collection<? extends ServerPlayer> online) {
        ServerPlayer owner = o.owner(online);
        Loadout l = o.loadout;
        ServerActions.Aim aim = o.aim;
        String who = o.name + (o.confirmer == null ? "" : " (second key — " + o.confirmer + ")");
        StrikeService.log(o.level, who, l.weapon(), 1, 0, aim.target(), aim.point(), o.via, null);
        StrikeService.Result r;
        String why = "nuclear launch failed";
        try {
            if (o.icbm()) {
                r = StrikeService.launchIcbm(o.level, aim.target(), aim.point(), l.nuke(), owner, flightTicks());
            } else if (owner != null) {
                r = StrikeService.launchBy(o.level, l.weapon(), aim.target(), aim.point(), owner.getYRot(), owner, true, l.nuke(), o.via, null);
            } else {
                // Carrier after warning, launching player offline: launch from afar, like salvo with offline owner.
                r = StrikeService.launch(o.level, l.weapon(), aim.target(), aim.point(), o.lastYaw, o.ownerId, true, l.nuke(), o.via, null);
            }
        } catch (RuntimeException e) {
            Incoming.LOG.error("Nuclear launch {} failed with an exception — {}", l.weapon().getSerializedName(), who, e);
            r = StrikeService.Result.FAILED;
            why = "nuclear launch failed with an exception";
        }
        if (owner != null) {
            if (r.ok()) StrikeService.confirm(owner, l.weapon(), r.eta());
            else owner.displayClientMessage(Component.translatable("incoming.launch_failed").withStyle(ChatFormatting.RED), true);
        }
        if (!r.ok()) {
            if (o.paid != null) Munitions.refund(owner != null ? List.of(owner) : online, o.ownerId, o.paid, why);
        } else {
            detected(o, online);
        }
        return r;
    }

    /** Non-operator ICBM flies at least 90 s: target warning duration. */
    static int flightTicks() {
        return Math.max(IncomingConfig.SERVER.nukeFlightTime.get(), MIN_WARNING);
    }

    /**
     * Launch detected: all online get launch area ({@link #AREA_CELL} cell) and air-raid siren; those already
     * warned by ICBM launch (same world, within target warning radius) get no second siren.
     */
    private static void detected(Order o, Collection<? extends ServerPlayer> online) {
        Vec3 from = o.lastPos;
        int x = cell(from.x), z = cell(from.z);
        Component line = (o.icbm() ? Component.translatable("incoming.nuke.launch_detected", x, z)
                : Component.translatable("incoming.nuke.launch_detected.carrier", item(o.loadout).getDescription(), x, z)).withStyle(ChatFormatting.RED);
        double r = IncomingConfig.SERVER.nukeWarningRadius.get();
        List<ServerPlayer> all = new ArrayList<>(online);
        ServerPlayer owner = o.owner(online);
        if (owner != null && !all.contains(owner)) all.add(owner);
        for (ServerPlayer p : all) {
            p.sendSystemMessage(line);
            boolean alarmed = o.icbm() && p.level() == o.level && p.position().distanceToSqr(o.aim.point()) <= r * r;
            if (!alarmed) p.playNotifySound(ModSounds.SIREN.get(), SoundSource.MASTER, 1.0f, 1.0f);
        }
        Incoming.LOG.info("Nuclear launch detected: {} from area {} {} — {}", o.loadout.weapon().getSerializedName(), x, z, o.name);
    }

    /** Area coordinate: nearest multiple of {@link #AREA_CELL}. */
    public static int cell(double v) {
        return (int) (Math.round(v / AREA_CELL) * AREA_CELL);
    }

    /** Invite nearby launching-player companions not yet asked for second key. */
    private void prompt(Order o, Collection<? extends ServerPlayer> online, ServerPlayer owner) {
        double d = distance();
        long left = Math.max(1, (o.deadline - o.level.getGameTime() + 19) / 20);
        Vec3 at = o.aim.point();
        for (ServerPlayer p : online) {
            if (p.getUUID().equals(o.ownerId) || p.level() != owner.level() || p.distanceToSqr(owner) > d * d || !o.prompted.add(p.getUUID())) continue;
            p.sendSystemMessage(Component.translatable("incoming.nuke.key.prompt", owner.getDisplayName(), what(o.loadout), Mth.floor(at.x), Mth.floor(at.z),
                    Component.keybind(KEY), left).withStyle(ChatFormatting.GOLD));
            p.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.MASTER, 1.0f, 1.2f);
        }
    }

    /** World tick end: second-key expiry, carrier launch after warning, invite arrivals. */
    void tick(ServerLevel level, Collection<? extends ServerPlayer> online) {
        if (orders.isEmpty()) return;
        long now = level.getGameTime();
        for (Order o : List.copyOf(orders)) {
            if (o.launchAt >= 0) {
                if (now < o.launchAt) continue;
                orders.remove(o);
                launch(o, online);
            } else if (now >= o.deadline) {
                orders.remove(o);
                ServerPlayer owner = o.owner(online);
                if (owner != null) {
                    owner.sendSystemMessage(Component.translatable("incoming.nuke.key.expired", window() / 20).withStyle(ChatFormatting.RED));
                }
                if (o.paid != null) Munitions.refund(owner != null ? List.of(owner) : online, o.ownerId, o.paid, "no second key");
                Incoming.LOG.info("No second key within {} s: nuclear launch {} cancelled — {}", window() / 20, o.loadout.weapon().getSerializedName(), o.name);
            } else if ((now - o.created) % PROMPT_EVERY == 0) {
                ServerPlayer owner = o.owner(online);
                if (owner != null) prompt(o, online, owner);
            }
        }
    }

    /** Orders awaiting second key or post-warning launch. */
    public int pending() {
        return orders.size();
    }

    /**
     * Projectiles in player's pending orders: count as active for {@code max_active_per_player} ({@link
     * StrikeWorld#active}).
     */
    public int waiting(UUID owner) {
        int n = 0;
        for (Order o : orders) {
            if (o.ownerId.equals(owner)) n += o.loadout.count();
        }
        return n;
    }

    /**
     * Cancel all pending nuclear orders, refund online owners: operator/host nuclear abort, server stop.
     *
     * @param notice owner message, or null
     * @return canceled count
     */
    public static int cancelAll(MinecraftServer server, Collection<? extends ServerPlayer> online, @Nullable Component notice, String why) {
        int n = 0;
        for (ServerLevel level : server.getAllLevels()) {
            if (!level.hasData(ModAttachments.STRIKE_WORLD)) continue;
            NuclearKeys keys = get(level);
            for (Order o : List.copyOf(keys.orders)) {
                keys.orders.remove(o);
                n++;
                ServerPlayer owner = o.owner(online);
                if (owner != null && notice != null) owner.sendSystemMessage(notice);
                if (o.paid != null) Munitions.refund(owner != null ? List.of(owner) : online, o.ownerId, o.paid, why);
                Incoming.LOG.info("Nuclear launch {} cancelled ({}) — {}", o.loadout.weapon().getSerializedName(), why, o.name);
            }
        }
        return n;
    }

    /**
     * Launching player logged out before second key: cancel, refund them (inventory saved after event). Carrier
     * already warning target launches without them.
     */
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer player)) return;
        for (ServerLevel level : player.server.getAllLevels()) {
            if (!level.hasData(ModAttachments.STRIKE_WORLD)) continue;
            NuclearKeys keys = get(level);
            for (Order o : List.copyOf(keys.orders)) {
                if (o.launchAt >= 0 || !o.ownerId.equals(player.getUUID())) continue;
                keys.orders.remove(o);
                if (o.paid != null) Munitions.refund(List.of(player), o.ownerId, o.paid, "launching player left before the second key");
                Incoming.LOG.info("Nuclear launch {} cancelled: {} left before the second key", o.loadout.weapon().getSerializedName(), o.name);
            }
        }
    }

    /** Order weapon for messages: “ICBM, 15 kt”, “Cruise missile with nuclear warhead, 15 kt”. */
    private static Component what(Loadout l) {
        return Component.translatable(l.weapon().spec().warhead().always() ? "incoming.nuke.key.what" : "incoming.nuke.key.what.carrier",
                item(l).getDescription(), l.nuke().yieldKt());
    }

    private static Item item(Loadout l) {
        return l.weapon().spec().munition().item().get();
    }

    private static int window() {
        return IncomingConfig.SERVER.nukeSecondKeyWindow.get() * 20;
    }

    private static int distance() {
        return IncomingConfig.SERVER.nukeSecondKeyDistance.get();
    }
}
