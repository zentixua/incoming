package ua.zentix.incoming.launcher;

import com.mojang.authlib.GameProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.strike.LaunchOrigin;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.Munitions;
import ua.zentix.incoming.strike.SalvoData;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fixed launcher orders ({@link FixedLauncherBlockEntity}): mission from linked designator ({@link #assign}) and
 * launch on redstone signal ({@link #fire}). Launch follows player order path ({@link
 * ServerActions#fromLauncher}): single projectile or salvo ({@link SalvoData}) from this launcher's firing
 * position ({@link LaunchOrigin.Fixed}); projectiles belong to launcher owner, so {@code /team} friend/foe, their
 * “Abort”, and active-projectile limit match their designator orders. Launcher inventory pays: must cover the
 * whole order immediately (never truncate it), but deduct one projectile as it takes the rail; canceled salvos
 * and failed launches remain in storage.
 * <p>
 * Owned launcher follows owner's rules ({@code rules} in {@link ServerActions#strike}): designator permission,
 * target within {@code map_range} of the launcher itself, {@code max_active_per_player} limit. Unowned (placed by
 * a game-master command) bypasses them like console.
 */
public final class LauncherOrders {
    private LauncherOrders() {}

    /** Signal reached launcher {@code be}: issue mission order. Null means ordered; otherwise failure reason. */
    @Nullable
    public static Component fire(ServerLevel level, FixedLauncherBlockEntity be) {
        Mission m = be.mission();
        if (m == null) return Component.translatable("incoming.fixed_launcher.no_mission.short");
        BlockPos pos = be.getBlockPos();
        // In Sable plot grid, block is not at its visible position: launch would leave from the distant plot.
        if (SubLevels.inPlotGrid(level, new ChunkPos(pos))) return Component.translatable("incoming.fixed_launcher.on_craft");
        UUID owner = be.owner();
        if (owner != null) {
            MutableComponent rule = rules(level.getServer(), owner, be.position(), m.point());
            if (rule != null) return rule;
        }
        if (busy(level, be)) return Component.translatable("incoming.fixed_launcher.busy");
        LaunchOrigin.Fixed origin = new LaunchOrigin.Fixed(pos);
        Loadout l = ServerActions.clamp(new Loadout(m.weapon(), m.count(), m.spread(), Loadout.DEFAULT.mode(), "", Loadout.Nuke.DEFAULT));
        MutableComponent route = ServerActions.routeProblem(level, null, false, m.weapon(), origin, m.via(), m.point());
        if (route != null) return route;
        MutableComponent missing = Munitions.shortage(be.store().items(), Munitions.Bill.of(l));
        if (missing != null) return missing;
        String who = "launcher " + pos.toShortString();
        Component before = be.lastReport();
        boolean ok = ServerActions.fromLauncher(level, who, owner, l, new ServerActions.Aim(m.target(), m.point(), null), m.via(), origin);
        if (ok) return null;
        // Launch reported its own issue (blocked sector, inventory): this reason is more specific than the general one.
        return be.lastReport() != before ? be.lastReport() : Component.translatable("incoming.fixed_launcher.failed");
    }

    /**
     * Previous launcher order still running: salvo unfinished or projectiles on rails. No new order or mission:
     * mission changes the rack, while salvo projectiles launch from their weapon's rack.
     */
    public static boolean busy(ServerLevel level, FixedLauncherBlockEntity be) {
        return SalvoData.busy(level, new LaunchOrigin.Fixed(be.getBlockPos())) || !be.queue().silent(level.getGameTime());
    }

    /**
     * Rules for owner {@code owner}'s launcher order at {@code at} aimed at {@code aim}; null means allowed.
     * Owner may be offline: permissions use their profile.
     */
    @Nullable
    private static MutableComponent rules(MinecraftServer server, UUID owner, Vec3 at, Vec3 aim) {
        int permission = permission(server, owner);
        if (!IncomingConfig.SERVER.designatorForEveryone.get() && permission < 2) return Component.translatable("incoming.fixed_launcher.no_rights");
        int limit = IncomingConfig.SERVER.maxActivePerPlayer.get();
        if (limit > 0 && permission < 2) {
            int active = StrikeWorld.active(server, owner);
            if (active >= limit) return Component.translatable("incoming.too_many_active", active, limit);
        }
        return inRange(at, aim) ? null : Component.translatable("incoming.fixed_launcher.out_of_range", IncomingConfig.SERVER.mapRange.get());
    }

    /** Target within {@code map_range} horizontally from launcher: same reach as the designator. */
    private static boolean inRange(Vec3 at, Vec3 aim) {
        double range = IncomingConfig.SERVER.mapRange.get(), dx = aim.x - at.x, dz = aim.z - at.z;
        return dx * dx + dz * dz <= range * range;
    }

    /**
     * Permission level of player {@code id}: online value, otherwise server-cached profile (operators,
     * singleplayer host).
     */
    private static int permission(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        GameProfileCache cache = server.getProfileCache();
        GameProfile profile = online != null ? online.getGameProfile() : cache == null ? null : cache.get(id).orElse(null);
        return profile == null ? 0 : server.getProfilePermissions(profile);
    }

    /**
     * “Fire” on a designator with launcher {@code links}: ordered {@code aim}, subject to player rules as for
     * their own launch ({@link ServerActions#ruled}), becomes each reachable linked launcher's mission ({@link
     * Mission}): same world, ready chunk, own or team, target within launcher range, {@code via} route within
     * weapon reach from it, and not busy with a previous order. Missing launcher (ready chunk, no block) is
     * unlinked from {@code designator}. Player gets counts assigned and reasons for others.
     */
    public static void assign(ServerPlayer player, ItemStack designator, Loadout loadout, ServerActions.Aim aim, Waypoints via) {
        List<GlobalPos> links = LauncherLinks.of(designator);
        Loadout l = ServerActions.clamp(loadout);
        if (!Mission.accepts(l.weapon())) {
            player.displayClientMessage(Component.translatable("incoming.fixed_launcher.unsupported", l.weapon().displayName())
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        if (l.nuclear()) {
            player.displayClientMessage(Component.translatable("incoming.fixed_launcher.no_nuke").withStyle(ChatFormatting.RED), true);
            return;
        }
        ServerActions.Aim ruled = ServerActions.ruled(player, aim);
        if (ruled == null) return;
        Mission mission = new Mission(l.weapon(), l.count(), l.spread(), ruled.target(), ruled.point(), via);
        int given = 0;
        MutableComponent why = null;
        List<GlobalPos> gone = new ArrayList<>();
        for (GlobalPos at : links) {
            MutableComponent problem = assign(player, at, mission);
            if (problem == null) given++;
            else if (why == null) why = problem;
            if (missing(player.serverLevel(), at)) gone.add(at);
        }
        // Broken or exploded launcher: its link would keep the designator in mission mode forever.
        if (!gone.isEmpty()) LauncherLinks.forget(designator, gone);
        Incoming.LOG.info("Launcher mission: {} ×{} at {} {} {} — {} of {}, {}", l.weapon().getSerializedName(), l.count(), Math.round(ruled.point().x),
                Math.round(ruled.point().y), Math.round(ruled.point().z), given, links.size(), player.getGameProfile().getName());
        MutableComponent line = given > 0 ? Component.translatable("incoming.fixed_launcher.assigned", given, links.size()).withStyle(ChatFormatting.GOLD)
                : Component.translatable("incoming.fixed_launcher.assigned.none").withStyle(ChatFormatting.RED);
        if (why != null && given < links.size()) line.append(Component.literal(" — ").append(why).withStyle(ChatFormatting.RED));
        player.displayClientMessage(line, true);
    }

    /** Launcher at {@code at} is definitely absent: same world, ready chunk, no block. */
    private static boolean missing(ServerLevel level, GlobalPos at) {
        return at.dimension().equals(level.dimension()) && Terrain.ready(level, at.pos())
                && !(level.getBlockEntity(at.pos()) instanceof FixedLauncherBlockEntity);
    }

    /** Assign {@code mission} to launcher at {@code at}; null means assigned, otherwise failure reason. */
    @Nullable
    private static MutableComponent assign(ServerPlayer player, GlobalPos at, Mission mission) {
        ServerLevel level = player.serverLevel();
        if (!at.dimension().equals(level.dimension())) return Component.translatable("incoming.fixed_launcher.elsewhere");
        // Do not load the launcher chunk to assign a mission: far from players it is not in memory.
        if (!Terrain.ready(level, at.pos())) return Component.translatable("incoming.fixed_launcher.not_loaded");
        if (!(level.getBlockEntity(at.pos()) instanceof FixedLauncherBlockEntity be)) return Component.translatable("incoming.fixed_launcher.gone");
        if (!LauncherLinks.mayCommand(player, be)) return Component.translatable("incoming.fixed_launcher.not_yours");
        if (busy(level, be)) return Component.translatable("incoming.fixed_launcher.busy");
        if (!inRange(be.position(), mission.point())) {
            return Component.translatable("incoming.fixed_launcher.out_of_range", IncomingConfig.SERVER.mapRange.get());
        }
        // Waypoints are on the player's map; weapon reach is measured from the launcher.
        MutableComponent route = ServerActions.routeProblem(level, player, true, mission.weapon(), new LaunchOrigin.Fixed(at.pos()), mission.via(),
                mission.point());
        if (route != null) return route;
        be.setMission(mission);
        return null;
    }
}
