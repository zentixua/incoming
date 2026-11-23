package ua.zentix.incoming.progress;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.api.GridRestoreEvent;
import ua.zentix.incoming.api.NuclearDetonationEvent;
import ua.zentix.incoming.api.StrikeRoundEvent;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Sides;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Advancements and statistics ({@link ModTriggers}, {@link ModStats}) from Incoming's own order and round events
 * ({@code ua.zentix.incoming.api}), as they are delivered at the end of the tick they happened in. The round carries its
 * owner across off-world flight and restarts, so credit follows the round, never the world entity. Credit goes only to a
 * player online at that moment and not a fake player (an automation mod's); an offline owner, an ownerless round
 * (console, command block, ownerless launcher, game master) gets nothing, now or later.
 */
public final class Progress {
    /**
     * Ticks after the wave has passed a body until it is judged to have survived: a thrown body has landed by then. The throw
     * is at most 6 blocks/tick ({@code BlastBody.MAX_THROW}) and loses ×0.546 per tick on the ground (vanilla block friction
     * 0.6 × 0.91), under 0.1 block/tick in 7 ticks; its hop (at most a jump, {@code BlastBody.HOP}) lands within 12 ticks.
     */
    static final int LANDING_TICKS = 20;
    /** A body is watched for survival from this overpressure at its position: the wave hits bodies from here on. */
    static final double WATCH_PSI = BlastModel.psi(Detonation.HIT_KPA);

    private Progress() {}

    /** Stats and criterion triggers into their registries; stat objects at common setup ({@link ModStats#create}). */
    public static void register(IEventBus modBus) {
        ModStats.REGISTER.register(modBus);
        ModTriggers.REGISTER.register(modBus);
        modBus.addListener((FMLCommonSetupEvent e) -> e.enqueueWork(ModStats::create));
    }

    // ---------------------------------------------------------------- rounds

    public static void onReleased(StrikeRoundEvent.Released e) {
        ServerPlayer owner = credited(e.getOwnerId());
        WeaponType weapon = weapon(e.getWeapon());
        if (owner == null || weapon == null) return;
        owner.awardStat(ModStats.ROUNDS_LAUNCHED);
        owner.awardStat(ModStats.launched(weapon));
        ModTriggers.ROUND_RELEASED.get().trigger(owner, weapon);
    }

    public static void onEnded(StrikeRoundEvent.Ended e) {
        WeaponType weapon = weapon(e.getWeapon());
        if (weapon == null) return;
        switch (e.getOutcome()) {
            case IMPACT -> {
                ServerPlayer owner = e.isCameraGuided() ? credited(e.getOwnerId()) : null;
                if (owner != null) ModTriggers.CAMERA_GUIDED_HIT.get().trigger(owner, weapon);
            }
            case INTERCEPTED -> {
                ServerPlayer by = credited(e.getInterceptorOwnerId());
                // the SAM radar's own rule: friendly rounds are not targets; an ownerless round is hostile to everyone
                if (by == null || Sides.friendly(by.server, e.getOwnerId(), by.getUUID())) return;
                by.awardStat(ModStats.INTERCEPTIONS);
                ModTriggers.ROUND_INTERCEPTED.get().trigger(by, weapon, e.getInterceptorPos() != null);
            }
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------- nuclear

    /**
     * Every online player in the burst's dimension: within its damage reach ({@link #witnessed}) counts a detonation
     * witnessed; one the wave reaches ({@link #WATCH_PSI}) and who can be hurt is watched until the wave has passed them
     * ({@link #survivalDue}).
     */
    public static void onDetonation(NuclearDetonationEvent e) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerLevel level = server == null ? null : server.getLevel(e.getDimension());
        Detonation d = level == null ? null : detonation(level, e.getDetonationId());
        if (d == null) return;
        for (ServerPlayer p : online(level)) {
            Vec3 at = p.position();
            if (witnessed(d, at)) p.awardStat(ModStats.NUCLEAR_DETONATIONS_WITNESSED);
            double psi = d.psi(at);
            if (psi < WATCH_PSI || !p.isAlive() || p.isSpectator() || p.getAbilities().invulnerable) continue;
            p.getData(ModAttachments.SURVIVAL).add(new Watch(survivalDue(d, at), psi, d.yieldKt()));
        }
    }

    /**
     * Within a detonation's damage reach: horizontal range from the burst no farther than {@link Detonation#radiusMax} (glass
     * breaks at 0.5 psi, or the flash gives first-degree burns).
     */
    static boolean witnessed(Detonation d, Vec3 at) {
        return Math.hypot(at.x - d.burst().x, at.z - d.burst().z) <= d.radiusMax();
    }

    /**
     * Game time when a body at {@code at} has outlived the blast: front arrival at its slant range, the positive phase there,
     * then {@link #LANDING_TICKS}.
     */
    public static long survivalDue(Detonation d, Vec3 at) {
        double slant = at.distanceTo(d.burst());
        return d.gameTime() + (long) Math.ceil(d.arrivalTicks(slant) + d.positivePhaseTicks(slant)) + LANDING_TICKS;
    }

    /** A watched player's due survivals; dead or logged out, the watch is gone with the player object. */
    public static void onPlayerTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !p.hasData(ModAttachments.SURVIVAL)) return;
        List<Watch> watches = p.getData(ModAttachments.SURVIVAL);
        if (watches.isEmpty()) return;
        long now = p.serverLevel().getGameTime();
        for (var it = watches.iterator(); it.hasNext(); ) {
            Watch w = it.next();
            if (now < w.due) continue;
            it.remove();
            if (p.isAlive()) ModTriggers.NUCLEAR_SURVIVED.get().trigger(p, w.psi, w.yieldKt);
        }
    }

    /** A death ends every survival watch: the respawned player object starts without them. */
    public static void onDeath(LivingDeathEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && p.hasData(ModAttachments.SURVIVAL)) p.getData(ModAttachments.SURVIVAL).clear();
    }

    // ---------------------------------------------------------------- grid

    /** Everyone standing in the outage's area (horizontal radius from its centre) when its power is all back. */
    public static void onRestore(GridRestoreEvent e) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerLevel level = server == null ? null : server.getLevel(e.getDimension());
        if (level == null) return;
        for (ServerPlayer p : online(level)) {
            if (Math.hypot(p.getX() - e.getCentreX(), p.getZ() - e.getCentreZ()) > e.getRadius()) continue;
            ModTriggers.BLACKOUT_RESTORED.get().trigger(p, e.isNuclear());
        }
    }

    // ---------------------------------------------------------------- helpers

    /** The player {@code id} names, online and not a fake player; null otherwise. */
    @Nullable
    static ServerPlayer credited(@Nullable UUID id) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (id == null || server == null) return null;
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        return p == null || p.isFakePlayer() ? null : p;
    }

    /** Online players in {@code level}, fake players left out: the player list is who is online. */
    private static List<ServerPlayer> online(ServerLevel level) {
        List<ServerPlayer> in = new ArrayList<>();
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) if (p.serverLevel() == level && !p.isFakePlayer()) in.add(p);
        return in;
    }

    @Nullable
    private static WeaponType weapon(String id) {
        for (WeaponType w : WeaponType.values()) if (w.getSerializedName().equals(id)) return w;
        return null;
    }

    @Nullable
    private static Detonation detonation(ServerLevel level, int id) {
        for (Detonation d : NuclearEvents.get(level).detonations()) if (d.id() == id) return d;
        return null;
    }

    /** A pending survival: game time it is due, overpressure at the body, yield. */
    public record Watch(long due, double psi, double yieldKt) {}

    /** A player's pending survivals ({@link ModAttachments#SURVIVAL}): unsaved, not copied on death. */
    public static final class Watches extends ArrayList<Watch> {}
}
