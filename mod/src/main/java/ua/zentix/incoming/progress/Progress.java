package ua.zentix.incoming.progress;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.api.GridRestoreEvent;
import ua.zentix.incoming.api.StrikeRoundEvent;
import ua.zentix.incoming.grid.Blackouts;
import ua.zentix.incoming.grid.Districts;
import ua.zentix.incoming.grid.Outage;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Sides;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Advancements and statistics ({@link ModTriggers}, {@link ModStats}). Rounds and outages: from Incoming's own order,
 * round and grid events ({@code ua.zentix.incoming.api}), as they are delivered at the end of the tick they happened in.
 * Nuclear: from the burst ({@link #witness}) and the wave's hit on a body ({@link #blasted}). The round carries its
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
    public static final int LANDING_TICKS = 20;

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
     * A detonation in {@code level}: every online player there within its damage reach ({@link #witnessed}) counts one
     * detonation witnessed. Called by the burst itself ({@code NuclearWarhead.detonate}), so a strike cleared in the same
     * tick still counts.
     */
    public static void witness(ServerLevel level, Detonation d) {
        for (ServerPlayer p : online(level)) if (witnessed(d, p.position())) p.awardStat(ModStats.NUCLEAR_DETONATIONS_WITNESSED);
    }

    /**
     * Within a detonation's damage reach: horizontal range from the burst no farther than {@link Detonation#radiusMax} (glass
     * breaks at 0.5 psi, or the flash gives first-degree burns).
     */
    static boolean witnessed(Detonation d, Vec3 at) {
        return Math.hypot(at.x - d.burst().x, at.z - d.burst().z) <= d.radiusMax();
    }

    /**
     * The blast wave of {@code d} has just hit {@code p} ({@code BlastFront}: the front reached them, from 0.3 psi, and its
     * hit was applied; the wave's own injury law decided what it did to them). A burial alone is no hit: a body that left
     * its collapsed house before the rubble job ran met no wave. An online, real player who can be hurt is
     * watched until the wave has passed them ({@link #survivalDue}), with the peak overpressure at their position, shelter
     * or not. A strike cleared before its front arrives hits no one, so it watches no one.
     */
    public static void blasted(ServerLevel level, Detonation d, ServerPlayer p) {
        if (p.isFakePlayer() || !p.isAlive() || p.isSpectator() || p.getAbilities().invulnerable) return;
        // the front's tick ring may catch a body just beyond the pressure the wave hits with
        if (d.overpressureKpa(p.position()) < Detonation.HIT_KPA) return;
        if (level.getServer().getPlayerList().getPlayer(p.getUUID()) != p) return;
        p.getData(ModAttachments.SURVIVAL).add(new Watch(level.dimension(), survivalDue(d, p.position(), level.getGameTime()), d.psi(p.position()), d.yieldKt()));
    }

    /**
     * Game time when a body at {@code at}, hit at {@code now}, has outlived the blast: the positive phase at its place
     * ({@link Detonation#positivePhaseSeconds}, in game ticks at the strike's scale as the front's own phase), then
     * {@link #LANDING_TICKS}. The hit comes at front arrival or later (it waits for the light pulse and the work budget),
     * so this is never before the wave has passed.
     */
    public static long survivalDue(Detonation d, Vec3 at, long now) {
        return now + (long) Math.ceil(d.positivePhaseSeconds(at) * 20 * d.scale()) + LANDING_TICKS;
    }

    /**
     * A watched player's due survivals: still alive, in the burst's dimension and able to be hurt. Due watches are taken
     * off the list before any criterion runs: a reward may kill the player, whose death clears the list.
     */
    public static void onPlayerTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !p.hasData(ModAttachments.SURVIVAL)) return;
        List<Watch> watches = p.getData(ModAttachments.SURVIVAL);
        if (watches.isEmpty()) return;
        long now = p.serverLevel().getGameTime();
        List<Watch> due = new ArrayList<>();
        watches.removeIf(w -> now >= w.due && due.add(w));
        for (Watch w : due) {
            if (p.isAlive() && !p.isSpectator() && !p.getAbilities().invulnerable && p.level().dimension() == w.dimension) {
                ModTriggers.NUCLEAR_SURVIVED.get().trigger(p, w.psi, w.yieldKt);
            }
        }
    }

    /** Leaving the burst's dimension ends every survival watch, even with a quick return. */
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && p.hasData(ModAttachments.SURVIVAL)) p.getData(ModAttachments.SURVIVAL).clear();
    }

    /** A death ends every survival watch: the respawned player object starts without them. */
    public static void onDeath(LivingDeathEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && p.hasData(ModAttachments.SURVIVAL)) p.getData(ModAttachments.SURVIVAL).clear();
    }

    // ---------------------------------------------------------------- grid

    /**
     * Outage {@code o} is over ({@code Blackouts.restored}, queued after its {@link GridRestoreEvent} so it is delivered at
     * the end of the tick, outside the grid's loops). Internal to Incoming: it carries the outage's own record, which the
     * public event does not.
     */
    public static final class Relit extends Event {
        private final ResourceKey<Level> dimension;
        private final Outage outage;

        public Relit(ResourceKey<Level> dimension, Outage outage) {
            this.dimension = dimension;
            this.outage = outage;
        }
    }

    /**
     * Everyone standing in a district the outage really darkened (the grid's own coverage by district seed, and its cascade
     * reached the district no later than its light came back) when the outage is over, unless another outage still keeps that
     * district dark.
     */
    public static void onRelit(Relit e) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerLevel level = server == null ? null : server.getLevel(e.dimension);
        if (level == null) return;
        PowerGrid grid = PowerGrid.get(level);
        long now = Blackouts.now(level);
        Outage o = e.outage;
        for (ServerPlayer p : online(level)) {
            int cx = p.getBlockX() >> 4, cz = p.getBlockZ() >> 4;
            long district = Districts.of(cx, cz);
            // a cascade due at the very grid instant of the restore did darken it: the grid clock advances slower than
            // game ticks (a stopped day), so the district stayed dark for real ticks
            if (!o.covers(district) || o.darkAt(district) > o.lightAt(district) || grid.dark(cx, cz, now)) continue;
            ModTriggers.BLACKOUT_RESTORED.get().trigger(p, o.byDamage());
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

    /** A pending survival: the burst's dimension, game time it is due, overpressure at the body, yield. */
    public record Watch(ResourceKey<Level> dimension, long due, double psi, double yieldKt) {}

    /** A player's pending survivals ({@link ModAttachments#SURVIVAL}): unsaved, not copied on death. */
    public static final class Watches extends ArrayList<Watch> {}
}
