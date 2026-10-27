package ua.zentix.incoming.nuclear;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.grid.Blackouts;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.model.BurnInjury;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.PromptRadiationModel;
import ua.zentix.incoming.nuclear.model.ThermalModel;
import ua.zentix.incoming.nuclear.model.Yield;
import ua.zentix.incoming.nuclear.radiation.FlashBurns;
import ua.zentix.incoming.nuclear.radiation.FlashEyes;
import ua.zentix.incoming.nuclear.radiation.RadiationTicker;
import ua.zentix.incoming.nuclear.radiation.Shielding;
import ua.zentix.incoming.nuclear.world.NuclearWorld;
import ua.zentix.incoming.nuclear.world.ThermalShadow;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.registry.ModDamageTypes;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.util.Wind;

import java.util.UUID;

/**
 * Nuclear warhead: detonation (DESIGN-nuke §0, §4). Server records event and sends it to everyone in dimension
 * in one packet. Everything else runs by ticks under the budget ({@link NuclearWorld}): light pulse and prompt
 * radiation on entities (nearest first), shock wave, destruction and crater.
 */
public final class NuclearWarhead {
    /**
     * Client scenario ({@code tools/client_scenario.sh}, server in same JVM): wind and detonation seed derive from its number rather than
     * {@code level.random}, so pall and mushroom frames compare across runs. In game, unchanged.
     */
    private static final boolean SCENARIO = System.getProperty("incoming.scenario") != null;
    /** Distance from eye (blocks) where terrain between it and fireball is densely checked by heightmap, and step size. */
    private static final double NEAR_TERRAIN = 512, TERRAIN_STEP = 8;
    private static final long SCENARIO_SEED = 0x5CE7A510L;

    private NuclearWarhead() {}

    /**
     * @param target   aim point (ground beneath epicenter from heightmap)
     * @param airBurst airburst at optimal height; otherwise surface burst
     */
    public static Detonation detonate(ServerLevel level, Vec3 target, double yieldKt, boolean airBurst, @Nullable UUID owner) {
        return detonate(level, target, yieldKt, airBurst, owner, (float) IncomingConfig.SERVER.effectsScale());
    }

    /** @param scale radius scale (1 is real life); usually from effects_scale setting, GameTest supplies its own */
    public static Detonation detonate(ServerLevel level, Vec3 target, double yieldKt, boolean airBurst, @Nullable UUID owner, float scale) {
        long started = System.nanoTime();
        // yield limit also applies to strikes from old saves and other mods' commands
        yieldKt = Math.min(yieldKt, ua.zentix.incoming.strike.Loadout.Nuke.MAX_YIELD);
        NuclearEvents events = NuclearEvents.get(level);
        // prepared for this detonation: location, ground and visibility match the prebuilt ruins
        Detonation g = NuclearWorld.get(level).planned(geometry(level, target, yieldKt, airBurst, scale));
        int id = events.nextId();
        RandomSource random = SCENARIO ? RandomSource.create(SCENARIO_SEED + id) : level.random;
        // wind is world wind (same as client smoke and trails)
        Wind wind = Wind.at(level.getGameTime());
        Detonation d = new Detonation(id, g.burst(), g.groundY(), yieldKt, g.surface(),
                level.getGameTime(), (float) wind.direction(), (float) wind.speed(),
                g.visibility(), random.nextLong(), scale, IncomingConfig.SERVER.nukeFallout.get(),
                (float) IncomingConfig.SERVER.waveSpeed(), level.getDayTime(),
                IncomingConfig.SERVER.nukeFires.get() && IncomingConfig.SERVER.nukeMaxFires.get() > 0);
        events.add(d);
        PacketDistributor.sendToPlayersInDimension(level, new S2C.NukeDetonation(d, (float) Blackouts.nukeRadius(d)));

        NuclearWorld.get(level).onDetonation(level, d, owner);
        Blackouts.nuke(level, d);
        Incoming.LOG.info("Nuclear detonation #{}: {} kt, {}, {} {} {}, scale {}, {} ms", d.id(), Math.round(yieldKt), d.surface() ? "ground burst" : "air burst",
                Mth.floor(d.burst().x), Mth.floor(d.burst().y), Mth.floor(d.burst().z), scale, (System.nanoTime() - started) / 1_000_000);
        return d;
    }

    /**
     * Detonation geometry without number, time or random detail: burst point, ground beneath, surface status, visibility.
     * Also used to prebuild ruins ({@link ua.zentix.incoming.nuclear.world.NuclearPrep}) matching the actual detonation.
     */
    public static Detonation geometry(ServerLevel level, Vec3 target, double yieldKt, boolean airBurst, float scale) {
        Terrain.Surface under = ground(level, Mth.floor(target.x), Mth.floor(target.z));
        double ground = under != null ? Math.min(under.y(), target.y) : target.y;
        if (ground <= level.getMinBuildHeight()) ground = target.y;
        double hob = airBurst ? Yield.optimalBurstHeight(yieldKt) * scale : 0;
        boolean surface = hob / scale < FireballModel.maxRadius(yieldKt, true);
        float visibility = (float) (level.isThundering() ? ThermalModel.VISIBILITY_THUNDER : level.isRaining() ? ThermalModel.VISIBILITY_RAIN : ThermalModel.VISIBILITY_CLEAR);
        return new Detonation(-1, new Vec3(target.x, ground + hob, target.z), ground, yieldKt, surface, 0, 0, 0, visibility, 0, scale, false,
                (float) IncomingConfig.SERVER.waveSpeed());
    }

    /** Surface point above target (surface strike: target is a map position). */
    public static Vec3 surfaceAt(ServerLevel level, Vec3 at) {
        BlockPos p = BlockPos.containing(at);
        Terrain.Surface under = ground(level, p.getX(), p.getZ());
        return under == null ? at : new Vec3(at.x, under.y(), at.z);
    }

    /** Ground in column from ready chunk; Nether ceiling is not ground; null for unready chunk (point remains target). */
    private static Terrain.@Nullable Surface ground(ServerLevel level, int x, int z) {
        Terrain.Surface s = Terrain.estimate(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z, Terrain.Allowed.CHUNK);
        return s.source() == Terrain.Source.CHUNK ? s : null;
    }

    /**
     * Beyond this (blocks), neither light nor prompt radiation affects entities: 1st-degree burns for the most
     * sensitive or 50 rem. Retinal burns reach farther: {@link PulseJob} collects players out to that range itself
     * ({@link FlashEyes#reach}).
     */
    public static double exposureRange(Detonation d) {
        double burnRange = d.blocks(ThermalModel.rangeForFluence(BurnInjury.lowest(d.yieldKt()), d.yieldKt(), d.surface(), d.visibility()));
        double radRange = d.blocks(radiusForDose(d.yieldKt(), 50));
        return Math.max(burnRange, radRange);
    }

    /**
     * Light and prompt radiation on one entity within {@link #exposureRange} (selected by caller).
     * Runs under budget ({@link NuclearWorld}), not on detonation tick.
     *
     * @return whether affected (alive and not in creative mode)
     */
    public static boolean expose(ServerLevel level, Detonation d, LivingEntity living, @Nullable Entity owner) {
        return expose(level, d, living, owner, false);
    }

    /**
     * Same; {@code late} — the body was not in memory when the pulse passed and loads now ({@code LateExposure}): it receives
     * the burns and dose of its place, its sickness runs from the detonation, but nothing that happens only at that moment
     * (fleeing the flash, clothes catching fire).
     */
    public static boolean expose(ServerLevel level, Detonation d, LivingEntity living, @Nullable Entity owner, boolean late) {
        if (!living.isAlive()) return false;
        living.setData(ModAttachments.NUKE_SEEN, living.getData(ModAttachments.NUKE_SEEN).withPulse(d.id()));
        // creative and spectator: no burns or dose (as with damage)
        if (living instanceof Player p && (p.isCreative() || p.isSpectator())) return false;
        Vec3 eye = living.getEyePosition();
        if (sees(level, d, living)) {
            if (living instanceof ServerPlayer player) FlashEyes.flash(level, d, player, owner);
            FlashBurns.burn(level, d, living, d.fluence(eye), owner, !late);
            // saw flash: flee
            if (!late) MobPanic.flash(d, living, level.getGameTime());
        }
        double rem = PromptRadiationModel.doseRem(Math.max(1, d.metres(eye.distanceTo(d.burst()))), d.yieldKt());
        // less than a rem unshielded: no shielding rays needed (players reach retinal-burn range, tens of kilometers)
        if (rem < 1) return true;
        rem *= Shielding.prompt(level, eye, d.burst());
        if (rem < 1) return true;
        float gy = (float) (rem / PromptRadiationModel.REM_PER_GY);
        if (living instanceof ServerPlayer player) {
            if (IncomingConfig.SERVER.nukeRadiation.get()) RadiationTicker.addDose(player, gy);
        } else if (IncomingConfig.SERVER.nukeMobRadiation.get()) {
            // mobs get sick like players (undead and armor stands do not)
            if (RadiationTicker.affectsMob(living)) RadiationTicker.addDose(living, gy, late ? d.gameTime() : level.getGameTime());
        } else if (rem >= 1000) {
            living.hurt(ModDamageTypes.source(level, ModDamageTypes.RADIATION, null, owner), Float.MAX_VALUE);
        } else if (rem >= 400) {
            living.hurt(ModDamageTypes.source(level, ModDamageTypes.RADIATION, null, owner), living.getMaxHealth() * 0.5f);
        }
        return true;
    }

    /**
     * Whether entity sees fireball: nearby, actual block ray (walls, roof); farther, heightmap
     * (hills, houses), densely to {@link #NEAR_TERRAIN}, sparse points beyond to avoid kilometer-long rays.
     */
    public static boolean sees(Level level, Detonation d, LivingEntity e) {
        Vec3 eye = e.getEyePosition();
        Vec3 dir = d.burst().subtract(eye);
        double len = dir.length();
        Vec3 near = eye.add(dir.scale(Math.min(1, 48 / Math.max(len, 1e-3))));
        // block ray only through loaded chunks: server must not load world for this check
        if (Terrain.readyAlong(level, eye, near) && level.clip(new ClipContext(eye, near, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, e)).getType() != HitResult.Type.MISS) return false;
        // sparse ThermalShadow points for a distant eye miss nearby terrain (ridge a hundred meters away with fireball
        // 30 km away): from ray end to NEAR_TERRAIN, heightmap every TERRAIN_STEP blocks while chunks ready
        Vec3 until = Terrain.readyUntil(level, near, eye.add(dir.scale(Math.min(1, NEAR_TERRAIN / Math.max(len, 1e-3)))));
        Vec3 span = until.subtract(near);
        for (double s = TERRAIN_STEP, n = span.length(); s < n; s += TERRAIN_STEP) {
            Vec3 p = near.add(span.scale(s / n));
            if (Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, Mth.floor(p.x), Mth.floor(p.z)) > p.y + 0.5) return false;
        }
        return ThermalShadow.visible(level, d.burst(), near);
    }

    /** Range where prompt-radiation dose falls to {@code rem}, m (bisection of monotonic model). */
    private static double radiusForDose(double yieldKt, double rem) {
        double lo = 1, hi = 100_000;
        for (int i = 0; i < 60; i++) {
            double m = Math.sqrt(lo * hi);
            if (PromptRadiationModel.doseRem(m, yieldKt) > rem) lo = m;
            else hi = m;
        }
        return lo;
    }
}
