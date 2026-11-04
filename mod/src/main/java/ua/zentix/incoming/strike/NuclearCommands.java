package ua.zentix.incoming.strike;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.world.RuinPreparation;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.target.Target;

/** Unpaid command orders: the explicit command authorizes one launch after actual preparation. */
public final class NuclearCommands {
    private static final int CONSOLE_LIMIT = 16;
    private static final int EXPIRE_TICKS = 20 * 60 * 30;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    private record Policy(int cap, int flight, boolean carriers, boolean ops, int keyWindow, int keyDistance,
                          double scale, double wave, int mapRange, boolean sight) {
        static Policy current() {
            var c = IncomingConfig.SERVER;
            return new Policy(c.nukeMaxYield.get(), c.nukeFlightTime.get(), c.carrierNukes.get(), c.nukeOpsOnly.get(),
                    c.nukeSecondKeyWindow.get(), c.nukeSecondKeyDistance.get(), c.effectsScale(), c.waveSpeed(),
                    c.mapRange.get(), c.sightRules.get());
        }
    }

    private record Entry(UUID id, UUID owner, @Nullable ServerPlayer player, String who, float yaw, Loadout loadout,
                         ServerActions.Aim aim, Waypoints via, @Nullable LaunchOrigin from, boolean rules,
                         long expires, Policy policy) {}

    public NuclearCommands() {}

    /** Replace any older unaccepted command by this player, including another dimension. No payment here. */
    public static boolean request(ServerPlayer player, Loadout loadout, ServerActions.Aim aim, Waypoints via,
                                  @Nullable LaunchOrigin from, boolean rules) {
        if (NuclearKeys.preparingOrder(player)) {
            player.sendSystemMessage(Component.translatable("incoming.nuke.prepare.pending")); return false;
        }
        cancelPlayer(player);
        NuclearReview.cancel(player);
        if (!player.isAlive() || player.isRemoved() || !ServerActions.mayUse(player) || !ServerActions.mayUseNuke(player)
                || !allowed(loadout)) return false;
        if (rules && (aim = ServerActions.ruled(player, aim)) == null) return false;
        if (ServerActions.routeProblem(player.serverLevel(), player, rules, loadout.weapon(), from, via, aim.point()) != null) return false;
        return add(player.serverLevel(), new Entry(UUID.randomUUID(), player.getUUID(), player,
                player.getGameProfile().getName(), player.getYRot(), ServerActions.clamp(loadout), aim, via, from, rules,
                player.serverLevel().getGameTime() + EXPIRE_TICKS, Policy.current()));
    }

    /** Console/command-block requests have independent handles, bounded across all dimensions. */
    public static boolean request(ServerLevel level, String who, float yaw, Loadout loadout, ServerActions.Aim aim,
                                  Waypoints via, @Nullable LaunchOrigin from) {
        if (!allowed(loadout) || !Float.isFinite(yaw)
                || ServerActions.routeProblem(level, null, false, loadout.weapon(), from, via, aim.point()) != null) return false;
        int consoles = 0;
        for (ServerLevel world : level.getServer().getAllLevels()) {
            if (world.hasData(ModAttachments.STRIKE_WORLD))
                consoles += (int) StrikeWorld.get(world).commands().entries.values().stream().filter(e -> e.player == null).count();
        }
        if (consoles >= CONSOLE_LIMIT) return false;
        UUID handle = UUID.randomUUID();
        return add(level, new Entry(handle, handle, null, who, yaw, ServerActions.clamp(loadout), aim, via, from, false,
                level.getGameTime() + EXPIRE_TICKS, Policy.current()));
    }

    private static boolean allowed(Loadout loadout) {
        var c = IncomingConfig.SERVER;
        return c.nukeEnabled.get() && loadout.nuclear() && loadout.nuke().yieldKt() <= c.nukeMaxYield.get()
                && (!loadout.nuke().onCarrier() || loadout.weapon().spec().warhead().always() || c.carrierNukes.get());
    }

    private static boolean add(ServerLevel level, Entry e) {
        StrikeWorld.get(level).commands().entries.put(e.id, e);
        RuinPreparation.prepare(level, e.id, e.owner, e.aim.point(), e.loadout.nuke().yieldKt(),
                e.aim.target() instanceof Target.Ground,
                e.loadout.weapon().spec().warhead().chooseBurst() && e.loadout.nuke().airBurst());
        RuinPreparation.Status status = RuinPreparation.status(level, e.id);
        if (status.stage() == RuinPreparation.Stage.FAILED || status.stage() == RuinPreparation.Stage.ABSENT) {
            StrikeWorld.get(level).commands().entries.remove(e.id);
            RuinPreparation.cancel(level, e.id);
            return false;
        }
        tell(e, "incoming.nuke.prepare.command");
        return true;
    }

    /** Called at world-tick end. Consume authority before dispatch, including failed dispatches. */
    public void tick(ServerLevel level) {
        for (Entry e : new ArrayList<>(entries.values())) {
            if (!valid(level, e) || !RuinPreparation.keepAlive(level, e.id, e.owner)) {
                remove(level, e); continue;
            }
            RuinPreparation.Status status = RuinPreparation.status(level, e.id);
            if (status.stage() == RuinPreparation.Stage.FAILED || status.stage() == RuinPreparation.Stage.ABSENT) {
                remove(level, e); continue;
            }
            if (status.stage() != RuinPreparation.Stage.READY) {
                if ((e.expires - level.getGameTime()) % 200 == 0)
                    tell(e, "incoming.nuke.prepare.progress", status.ready(), status.total());
                continue;
            }
            Vec3 resolved = status.resolvedTarget();
            if (resolved == null) { remove(level, e); continue; }
            entries.remove(e.id);
            // Freeze the accepted geometry: no entity tracking or later Ground refinement can move prepared work.
            ServerActions.Aim aim = new ServerActions.Aim(e.aim.target() instanceof Target.Ground
                    ? new Target.Ground(resolved) : new Target.Point(resolved), resolved, e.aim.label());
            boolean accepted = false;
            try {
                accepted = e.player == null
                        ? ServerActions.dispatchPrepared(level, e.who, e.yaw, e.loadout, aim, e.via, e.from, e.id)
                        : ServerActions.strikePrepared(e.player, e.loadout.weapon(), e.loadout.count(), e.loadout.spread(),
                                aim, e.loadout.nuke(), e.via, e.from, e.rules, e.id);
            } catch (RuntimeException failure) {
                Incoming.LOG.error("Prepared nuclear command {} failed", e.id, failure);
                tell(e, "incoming.nuke.prepare.cancelled");
            } finally {
                if (!accepted) RuinPreparation.cancel(level, e.id);
            }
        }
    }

    private static boolean valid(ServerLevel level, Entry e) {
        if (level.getGameTime() > e.expires || !e.policy.equals(Policy.current()) || !allowed(e.loadout)) return false;
        if (e.player != null) {
            ServerPlayer p = e.player;
            if (!p.isAlive() || p.isRemoved() || p.serverLevel() != level || !ServerActions.mayUse(p) || !ServerActions.mayUseNuke(p)) return false;
            if (e.rules) {
                ServerActions.Aim checked = ServerActions.ruled(p, e.aim);
                if (checked == null || checked.point().distanceToSqr(e.aim.point()) > 1e-8) return false;
            }
        }
        // Sighted resolves only its disclosed position. Never consult its hidden quarry to update a command.
        if (!(e.aim.target() instanceof Target.Ground)) {
            Vec3 at = e.aim.target().resolve(level).orElse(null);
            if (at == null || at.distanceToSqr(e.aim.point()) > 1) return false;
        }
        return ServerActions.routeProblem(level, e.player, e.rules, e.loadout.weapon(), e.from, e.via, e.aim.point()) == null;
    }

    private void remove(ServerLevel level, Entry e) {
        entries.remove(e.id);
        RuinPreparation.cancel(level, e.id);
        tell(e, "incoming.nuke.prepare.cancelled");
    }

    private static void tell(Entry e, String key, Object... args) {
        if (e.player != null) e.player.sendSystemMessage(Component.translatable(key, args));
        else Incoming.LOG.info("Nuclear command {} by {}: {} {}", e.id, e.who, key, java.util.Arrays.toString(args));
    }

    public static void cancelPlayer(ServerPlayer player) {
        for (ServerLevel level : player.server.getAllLevels()) {
            if (!level.hasData(ModAttachments.STRIKE_WORLD)) continue;
            NuclearCommands state = StrikeWorld.get(level).commands();
            for (Entry e : new ArrayList<>(state.entries.values())) if (e.owner.equals(player.getUUID())) state.remove(level, e);
        }
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) cancelPlayer(player);
    }

    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) cancelPlayer(player);
    }

    public static void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) cancelPlayer(player);
    }

    public static int cancelAll(MinecraftServer server) {
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            if (!level.hasData(ModAttachments.STRIKE_WORLD)) continue;
            NuclearCommands state = StrikeWorld.get(level).commands();
            count += state.entries.size();
            for (Entry e : new ArrayList<>(state.entries.values())) state.remove(level, e);
        }
        return count;
    }

    boolean entriesEmpty() { return entries.isEmpty(); }

    /** Read-only status for command feedback and lifecycle regression checks. */
    public static int pending(ServerLevel level) {
        return level.hasData(ModAttachments.STRIKE_WORLD) ? StrikeWorld.get(level).commands().entries.size() : 0;
    }
}
