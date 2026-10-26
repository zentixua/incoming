package ua.zentix.incoming.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.client.far.FarBlasts;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.client.fx.plume.Flame;
import ua.zentix.incoming.client.fx.plume.FlameModel;
import ua.zentix.incoming.client.fx.plume.NozzleGlow;
import ua.zentix.incoming.client.fx.plume.TrailModel;
import ua.zentix.incoming.client.fx.plume.TrailModel.Trail;
import ua.zentix.incoming.client.fx.particle.Fx;
import ua.zentix.incoming.client.fx.particle.FxBudget;
import ua.zentix.incoming.client.fx.layer.FxLights;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.FirePlume;
import ua.zentix.incoming.client.fx.smoke.Puffs;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.defense.InterceptorSpec;
import ua.zentix.incoming.entity.BomberEntity;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.entity.CruiseMissileEntity;
import ua.zentix.incoming.entity.DebrisEntity;
import ua.zentix.incoming.entity.DroneEntity;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.IcbmEntity;
import ua.zentix.incoming.entity.InterceptorEntity;
import ua.zentix.incoming.entity.LauncherEntity;
import ua.zentix.incoming.entity.RocketEntity;
import ua.zentix.incoming.entity.SpentBoosterEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.util.Local;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Engines in flight: plume (drawn by {@link ua.zentix.incoming.client.render.StrikeProjectileRenderer} from
 * {@link #plume}), fire and flashes on ignition and launch rail exit, sparks. Engine smoke is not drawn as particles: the trail is a ribbon along path
 * points and smoke grid volume near the eye ({@code client.far.FarFlightView}, law — {@link TrailModel}); the launch cloud is a volume
 * ({@code fx/smoke/LaunchCloud}: products while the jet strikes the ground, and dust it lifts).
 */
public final class Exhaust {
    /**
     * Plume in projectile coordinates: nozzle exit at (0, {@code y}, {@code z}) (nose along +Z), plume at projectile altitude, jet fraction
     * {@code near} (from 0.8 render distance, distant light from the same plume replaces the jet). Eye adaptation belongs to the effects layer
     * that draws it ({@code PlumeRenderer}): the same as for distant plume light.
     */
    public record Plume(float y, float z, Flame flame, float near) {
        /** Same jet, dimmed by factor k (distant plume light replaces it at the render boundary). */
        public Plume dimmed(float k) {
            return new Plume(y, z, flame, near * k);
        }
    }

    private static final class State {
        /** Player camera is on this projectile (on-board video). */
        boolean onboard;
        Vec3 pad;
        /** Front end of the MLRS tube the rocket exited. */
        Vec3 muzzle;
    }

    private static final Map<StrikeProjectile, State> STATES = new WeakHashMap<>();

    private Exhaust() {}

    /** Once per client tick for each flying projectile. */
    public static void tick(StrikeProjectile e) {
        if (!(e.level() instanceof ClientLevel level)) return;
        State s = STATES.computeIfAbsent(e, k -> new State());
        s.onboard = Minecraft.getInstance().getCameraEntity() == e;
        switch (e) {
            case IcbmEntity icbm -> icbm(level, icbm, s);
            case CruiseMissileEntity m -> missile(level, m, s);
            case DroneEntity d -> drone(level, d, s);
            case RocketEntity rk -> rocket(level, rk, s);
            case BunkerBusterEntity b -> bomb(level, b, s);
            default -> {}
        }
    }

    /**
     * Current engine plume, or null — unlit: spec phase plume by the laws ({@link FlameModel}: same as distant plume
     * light) at projectile altitude. No pulsation: jet turbulence is thousands of hertz and averages over a frame.
     */
    @Nullable
    public static Plume plume(StrikeProjectile e, float partial) {
        if (!e.isActive() || !(e.level() instanceof ClientLevel level)) return null;
        ClientWeaponSpec.ClientAirframe a = nozzles(e);
        // booster thrust share follows the curve (rise, decay toward separation), with fractional ticks: plume fades without steps
        ClientWeaponSpec.Stage stage = a.far().stage(e.flightPhase(), e.phaseAge() + partial, e.airframe().thrust());
        Flame f = stage.flame(e.getY());
        if (f == null) return null;
        ClientWeaponSpec.At n = a.nozzle(stage);
        return new Plume((float) n.y(), (float) n.z(), f, 1);
    }

    /** Hot booster nozzle on the projectile: appearance at this instant and exit in projectile coordinates (nose along +Z). */
    public record Hot(NozzleGlow.Shine shine, ClientWeaponSpec.At at) {}

    /**
     * Hot launch-booster nozzle while attached to the projectile ({@link NozzleGlow}: real time, the in-game acceleration plateau
     * compressed into the real one); null — no booster, separated, or nozzle not yet computed.
     */
    @Nullable
    public static Hot hot(StrikeProjectile e, float partial) {
        if (!e.isActive() || !e.flightPhase().boosterLit()) return null;
        ClientWeaponSpec.At at = nozzles(e).boosterNozzle();
        NozzleGlow g = at == null ? null : ClientWeaponSpec.nozzleGlow(e.weapon());
        if (g == null) return null;
        return new Hot(g.at(g.seconds(e.flightPhase(), e.phaseAge() + partial)), at);
    }

    /** Entity nozzles from the client spec (B-2 and its bomb have their own). */
    private static ClientWeaponSpec.ClientAirframe nozzles(StrikeProjectile e) {
        return ClientWeaponSpec.of(e.weapon()).airframe(!(e instanceof BomberEntity));
    }

    /** Point in projectile axes to world coordinates. */
    private static Vec3 at(StrikeProjectile e, ClientWeaponSpec.At p) {
        return Local.at(e.position(), e.getYRot(), e.getXRot(), p.x(), p.y(), p.z());
    }

    // ---------------------------------------------------------------- Shahed

    /** Booster launch; afterwards, thin bluish piston-engine exhaust as a ribbon only. */
    private static void drone(ClientLevel level, DroneEntity e, State s) {
        booster(level, e, s);
    }

    // ---------------------------------------------------------------- MLRS

    /**
     * Grad rocket. Tube ignition (3 ticks): jet exits the rear of the tube — fire and flash (smoke
     * and dust from its ground impingement below the pack form the launch cloud). Launch rail exit: flash and sparks at the front end. Trail —
     * ribbon and volume ({@code FarFlightView}).
     */
    private static void rocket(ClientLevel level, RocketEntity e, State s) {
        FlightPhase ph = e.flightPhase();
        if (!e.isActive() || !ph.boosterLit()) return;
        RandomSource r = level.random;
        float yaw = e.getYRot(), pitch = e.getXRot();
        Vec3 back = Local.offset(yaw, pitch, 0, 0, -1);
        if (s.pad == null) {
            // first ignition tick: rocket still in the tube, its centre at the tube midpoint
            s.pad = e.position().add(back.scale(LauncherEntity.TUBE_LENGTH / 2));
            s.muzzle = e.position().subtract(back.scale(LauncherEntity.TUBE_LENGTH / 2));
        }
        if (ph == FlightPhase.IGNITION) {
            // jet from the tube's rear end
            for (int i = 0; i < 4; i++) {
                Fx.fire().vel(back.scale(0.5 + r.nextDouble() * 0.7).add(r.nextGaussian() * 0.08, r.nextGaussian() * 0.08, r.nextGaussian() * 0.08))
                        .size(0.4f, 1.5f).life(4 + r.nextInt(4)).alpha(0.9f).drag(0.75f).spawn(level, s.pad);
            }
            Fx.flash().size(2.5f, 3.5f).life(2).alpha(0.7f).spawn(level, s.pad);
            return;
        }
        int age = e.phaseAge();
        if (age < 2) {
            // launch rail exit: fire bursts from the front end behind the rocket, sparks
            Fx.flash().size(2, 3).life(2).alpha(0.6f).spawn(level, s.muzzle);
            for (int i = 0; i < 6; i++) {
                Fx.spark().vel(back.scale(-0.3 - r.nextDouble() * 0.5).add(r.nextGaussian() * 0.2, r.nextGaussian() * 0.2, r.nextGaussian() * 0.2))
                        .life(6 + r.nextInt(8)).size(0.05f, 0.02f).spawn(level, s.muzzle);
            }
        }
    }

    // ---------------------------------------------------------------- cruise missile

    /** Turbofan produces no fire or smoke (its trail follows the law, {@code TrailModel}); in a dive — body vapour (transonic condensation). */
    private static void missile(ClientLevel level, CruiseMissileEntity e, State s) {
        if (!e.isActive() || booster(level, e, s)) return;
        RandomSource r = level.random;
        // the vapour collar around the body is visible only externally; on board it is right at the lens
        if (e.flightPhase() == FlightPhase.TERMINAL && !s.onboard) {
            for (int i = 0; i < 3; i++) {
                double a = r.nextDouble() * Mth.TWO_PI;
                Vec3 p = Local.at(e.position(), e.getYRot(), e.getXRot(), Math.cos(a) * 0.35, Math.sin(a) * 0.35, 0.8 - r.nextDouble() * 1.6);
                Fx.smoke().size(0.3f, 1.2f).life(8).color(0xF4F6F8, 0xFFFFFF).alpha(0.45f).fadeIn(1).fadeFrom(0.2f).drag(0.8f)
                        .wind(0).spawn(level, p);
            }
        }
    }

    // ---------------------------------------------------------------- launcher launch

    /**
     * Shahed and missile on the launcher and booster: nothing on the rail; ignition — flashes at the booster nozzle (launcher
     * cloud and white booster trail as a volume, {@code FarFlightView}). After separation — normal exhaust (returns
     * false).
     */
    private static boolean booster(ClientLevel level, StrikeProjectile e, State s) {
        FlightPhase ph = e.flightPhase();
        if (ph == FlightPhase.READY) return true;
        if (!ph.boosterLit()) return false;
        Vec3 nozzle = at(e, nozzles(e).boosterNozzle());
        if (ph == FlightPhase.IGNITION && e.phaseAge() % 2 == 0) {
            float scale = nozzles(e).boosterSmoke();
            Fx.flash().size(3 * scale, 4 * scale).life(3).alpha(0.5f).spawn(level, nozzle);
        }
        return true;
    }

    // ---------------------------------------------------------------- ICBM

    /**
     * Solid-fuel first stage: on launch from the silo — fire at the silo head and flashes (launch cloud and white trail —
     * as a volume, {@code FarFlightView}).
     */
    private static void icbm(ClientLevel level, IcbmEntity e, State s) {
        RandomSource r = level.random;
        if (s.pad == null) s.pad = e.position().add(0, -9, 0);
        int age = e.age();
        if (age < 50) {
            Vec3 pad = s.pad;
            for (int i = 0; i < 4; i++) {
                Fx.fire().vel(r.nextGaussian() * 0.15, 0.3 + r.nextDouble() * 0.5, r.nextGaussian() * 0.15).size(1.5f, 3.5f)
                        .life(10 + r.nextInt(8)).spawn(level, pad.x + r.nextGaussian(), pad.y + 1, pad.z + r.nextGaussian());
            }
            if (age % 2 == 0) Fx.flash().size(10, 12).life(3).alpha(0.5f).spawn(level, pad.add(0, 3, 0));
        }
    }

    // ---------------------------------------------------------------- bomb (B-2 contrails as ribbons)

    /**
     * Bomb: flow separation at the tail (ribbon); near sound speed in humid air — vapour collar ({@link VapourCollar});
     * ground entry dust — model cloud ({@link BlastEffects#bunkerImpact}).
     */
    private static void bomb(ClientLevel level, BunkerBusterEntity e, State s) {
        RandomSource r = level.random;
        // underground — nothing: entry ejecta dust is the model cloud (BlastEffects.bunkerImpact)
        if (e.isDrilling()) return;
        Vec3 at = e.position();
        // humidity from the world atmosphere (weather), as for trails and clouds
        double mach = VapourCollar.mach(e.speed() * 20, at.y - ua.zentix.incoming.util.Air.SEA);
        if (VapourCollar.forms(mach, ua.zentix.incoming.util.Air.of(level).humidity())) {
            for (int i = 0; i < 4; i++) {
                double a = r.nextDouble() * Mth.TWO_PI;
                Vec3 p = Local.at(e.position(), e.getYRot(), e.getXRot(), Math.cos(a) * 1.1, Math.sin(a) * 1.1, 2 - r.nextDouble());
                Fx.smoke().size(0.5f, 1.6f).life(7).color(0xF6F8FA, 0xFFFFFF).alpha(0.5f).fadeIn(1).fadeFrom(0.15f).drag(0.7f)
                        .wind(0).spawn(level, p);
            }
        }
    }

    // ---------------------------------------------------------------- anti-aircraft missile

    /**
     * Anti-aircraft missile: ignition flash at the pack and fire at the nozzle. Smoke follows the law, as for projectiles: launch cloud while
     * the jet strikes the ground, and ribbon trail ({@code FarFlightView} along the missile path, {@code ClientWeaponSpec.INTERCEPTOR}).
     */
    public static void interceptor(InterceptorEntity e) {
        if (!(e.level() instanceof ClientLevel level)) return;
        RandomSource r = level.random;
        Vec3 back = Local.offset(e.getYRot(), e.getXRot(), 0, 0, -1);
        Vec3 nozzle = e.position().add(back.scale(InterceptorSpec.SAM.noseLength()));
        if (e.age() <= 2 && e.tickCount <= 1) Fx.flash().size(2.5f, 3.5f).life(3).alpha(0.6f).spawn(level, nozzle);
        for (int i = 0; i < 2; i++) {
            Fx.fire().vel(back.scale(0.3 + r.nextDouble() * 0.3).add(r.nextGaussian() * 0.04, r.nextGaussian() * 0.04, r.nextGaussian() * 0.04))
                    .size(0.25f, 0.6f).life(2 + r.nextInt(2)).alpha(0.85f).drag(0.9f).spawn(level, nozzle);
        }
    }

    // ---------------------------------------------------------------- debris

    private static final Map<DebrisEntity, Vec3> DEBRIS = new WeakHashMap<>();
    /** Burning debris smoke as cloud model emissions ({@link Puffs}), per fragment. */
    private static final Map<DebrisEntity, Burning> BURNING = new WeakHashMap<>();

    /**
     * Burning fragment: wood burns with a face of ~1 m² ({@link FirePlume#BLOCK_WATTS}, like a fire block for fire smoke); every
     * {@link #PUFF_S} s of flight — wood heat and soot emission ({@link FirePlume#SOOT_PER_JOULE}) as a thermal from the fragment's position.
     * Gas above the flame — Heskestad axial excess at the flame tip ({@link FirePlume#excess}).
     */
    private static final class Burning {
        final Puffs smoke;
        double fed;
        Vec3 at = Vec3.ZERO;

        Burning(Puffs smoke, double seconds) {
            this.smoke = smoke;
            this.fed = seconds;
        }
    }

    private static final double[] BB = new double[4];

    /**
     * Burning debris flame lights surrounding smoke, particles and ribbons ({@link FxLights}): visible flame sphere
     * ({@link FirePlume#flameLight}) with radius half the Heskestad flame height, using frame eye adaptation.
     */
    public static void lights(FarView view, FxLights lights) {
        if (BURNING.isEmpty()) return;
        double now = SmokeGrid.seconds(view.partial()), r = 0.5 * DEBRIS_FLAME.flameHeight();
        FirePlume.flameLight(BB);
        double m = Math.max(BB[0], Math.max(BB[1], BB[2]));
        Vec3 cam = view.camera();
        for (Burning b : BURNING.values()) {
            if (now - b.fed > 2 * PUFF_S + 0.1) continue;
            lights.add(b.at.x - cam.x, b.at.y + r - cam.y, b.at.z - cam.z, r, BB[3] * view.exposure(), FireSmoke.FLAME_ON_SCREEN,
                    (float) (BB[0] / m), (float) (BB[1] / m), (float) (BB[2] / m));
        }
    }

    /** Burning debris flame lights the frame world ({@code HeatPass}): the same flame sphere as {@link #lights}. */
    public static void heat(Vec3 eye, double seconds, ua.zentix.incoming.client.fx.layer.HeatPass.Frame f, double air) {
        if (BURNING.isEmpty()) return;
        double r = 0.5 * DEBRIS_FLAME.flameHeight();
        FirePlume.flameLight(BB);
        for (Burning b : BURNING.values()) {
            if (seconds - b.fed > 2 * PUFF_S + 0.1) continue;
            f.light(b.at.x - eye.x, b.at.y + r - eye.y, b.at.z - eye.z, r, BB[0], BB[1], BB[2], air, Math.max(r, 1));
        }
    }

    /** Burning fragment smoke emission every this many flight seconds (puff at the fragment ~0.5 m, at ~10 m/s — about every σ). */
    static final double PUFF_S = 0.1;
    /** Fragment flame: wood face, source ~1 m² ({@link FirePlume}). */
    static final FirePlume DEBRIS_FLAME = new FirePlume(1, FirePlume.CALM, 3);

    /**
     * Burning fragment: flame tongues as particles (luminous body) and smoke as cloud model emissions ({@link Burning}), flame light —
     * on surrounding smoke and particles ({@link #lights}); cold clod of loose soil (shovel-diggable: dirt, sand, gravel) —
     * dusty trail while flying, shed by the oncoming flow. A cold casing, concrete or stone fragment leaves no trail:
     * no detachable dust, and smoke comes only from combustion (BLASTSEQ D12).
     */
    public static void debris(DebrisEntity e) {
        if (!(e.level() instanceof ClientLevel level)) return;
        Vec3 p = e.position().add(0, 0.5, 0);
        Vec3 from = DEBRIS.put(e, p);
        boolean moving = e.getDeltaMovement().lengthSqr() > 0.01;
        RandomSource r = level.random;
        if (e.isHot()) {
            Fx.fire().vel(r.nextGaussian() * 0.02, 0.04, r.nextGaussian() * 0.02).size(0.35f, 0.6f).life(8 + r.nextInt(6)).spawn(level, p);
            double now = SmokeGrid.seconds(0);
            Burning b = BURNING.get(e);
            if (b == null) {
                b = new Burning(new Puffs(FarBlasts.air(level, p), 0.2f, 0.19f, 0.18f, e.getUUID().getLeastSignificantBits()), now);
                BURNING.put(e, b);
                SmokeGrid.add(b.smoke);
            }
            b.at = p;
            // debris may have left client tracking: emit at most two fire steps, rather than all missed emissions at once
            double dt = Math.min(now - b.fed, 2 * PUFF_S);
            if (dt >= PUFF_S) {
                double watts = FirePlume.BLOCK_WATTS * dt, top = DEBRIS_FLAME.flameHeight();
                b.smoke.add(p.x, p.y + top, p.z, now, FirePlume.CONVECTIVE * watts, CloudModel.T_AIR_K + DEBRIS_FLAME.excess(top), 0.5,
                        FirePlume.SOOT_PER_JOULE * watts, 0, 0, Double.NaN);
                b.fed = now;
            }
        } else if (moving && e.blockState().is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            segment(level, from, p, Fx.smoke().size(0.2f, 0.8f).life(40).color(0x8A8278, 0xA8A098).alpha(0.35f).fadeFrom(0.3f).budget(FxBudget.DEBRIS), 1.2, r);
        }
    }

    /** Previous position of a spent booster: smoke along the path segment. */
    private static final Map<SpentBoosterEntity, Vec3> SPENT = new WeakHashMap<>();
    /** Booster smokes for this many falling ticks (as before). */
    static final int SPENT_SMOKE_TICKS = 70;

    /**
     * Falling spent booster: thin smoke from hot nozzle insulation smouldering (⚠ quantity is not computed: previous
     * appearance, first 3.5 s of falling), via the effects layer rather than vanilla particles: vanilla writes depth, so the hot nozzle
     * ({@code NozzleGlow}) behind the smoke vanished completely. No fire: thrust decayed to zero before separation ({@code ThrustCurve}).
     */
    public static void spent(SpentBoosterEntity e) {
        if (!(e.level() instanceof ClientLevel level)) return;
        Vec3 p = e.position();
        Vec3 from = SPENT.put(e, p);
        if (e.age() >= SPENT_SMOKE_TICKS || e.landed()) return;
        segment(level, from, p, Fx.smoke().size(0.3f, 1.2f).life(50).color(0x9A948C, 0xB4AEA6).alpha(0.55f).fadeFrom(0.3f).budget(FxBudget.DEBRIS), 1.0,
                level.random);
    }

    // ---------------------------------------------------------------- shared

    /** Billows along the segment from the previous nozzle position to the current one every {@code step} blocks (with random offset). */
    private static Vec3 segment(ClientLevel level, @Nullable Vec3 from, Vec3 to, Fx.Spec puff, double step, RandomSource r) {
        if (from == null || from.distanceToSqr(to) > 80 * 80) from = to;
        double len = from.distanceTo(to);
        // do not thin the trail even at distance: gaps are more noticeable than extra billows
        int n = Math.max(1, (int) Math.round(len / step));
        for (int i = 0; i < n; i++) {
            double k = (i + r.nextDouble()) / n;
            puff.spawn(level, Mth.lerp(k, from.x, to.x), Mth.lerp(k, from.y, to.y), Mth.lerp(k, from.z, to.z));
        }
        return to;
    }
}
