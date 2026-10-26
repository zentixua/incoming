package ua.zentix.incoming.client;

import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.client.fx.plume.Flame;
import ua.zentix.incoming.client.fx.plume.FlameModel;
import ua.zentix.incoming.client.fx.plume.Motor;
import ua.zentix.incoming.client.fx.plume.NozzleGlow;
import ua.zentix.incoming.client.fx.plume.Smoke;
import ua.zentix.incoming.client.fx.plume.TrailModel.Trail;
import ua.zentix.incoming.client.render.WeaponModels;
import ua.zentix.incoming.client.sound.EngineSound;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.defense.InterceptorSpec;
import ua.zentix.incoming.strike.Article;
import ua.zentix.incoming.strike.ThrustCurve;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.StandardAtmosphere;

import java.util.List;
import java.util.function.Supplier;

/**
 * Client part of the weapon spec ({@link ua.zentix.incoming.strike.WeaponSpec}): sound layers, booster voice
 * and one-shot launch sounds, nozzles and plumes, spent booster, distant appearance ({@link FarLook}). Separate
 * from the shared spec: the server does not load client classes.
 * <p>
 * Nozzles and plume bases use projectile axes (nose along +Z, origin at the model centre) and follow model dimensions
 * ({@code tools/gen_models.py}): when changing a model, update them here along with the pivots in {@code WeaponModels}
 * and {@code noseLength} in the shared spec.
 *
 * @param airframe   entity that carries the weapon in flight
 * @param payload    second entity (B-2 bomb); null if absent
 * @param booster    launch booster voice while burning ({@code BOOSTER} layer)
 * @param launch     one-shot ignition sound at the launcher
 * @param separates  audible booster separation (pyrotechnic crack and latch clank)
 * @param spent      spent booster; null if there is no booster
 */
public record ClientWeaponSpec(ClientAirframe airframe, @Nullable ClientAirframe payload, BoosterVoice booster,
                               LaunchCue launch, boolean separates, @Nullable SpentBooster spent) {

    /** Point in projectile axes: right-left, up, along (nose along +Z), in blocks. */
    public record At(double x, double y, double z) {}

    /**
     * Model, nozzles and sound of one flight entity.
     *
     * @param model         model ({@link WeaponModels}): drawn by the entity renderer nearby, by {@code FarModels} at distance
     * @param layers        engine sound layers
     * @param engines       cruise engine nozzles (B-2: four contrails; bomb: flow separation at the tail)
     * @param boosterNozzle booster nozzle exit; null if there is no booster
     * @param boosterSmoke  booster ignition flash size (Shahed smaller than missile; launch cloud is a volume, {@code LaunchCloud})
     * @param far           fire and smoke by flight phase ({@link Flame}: plume nearby and at distance) and distant appearance
     *                      where the client has no entity
     */
    public record ClientAirframe(WeaponModels.Look model, List<EngineSound.Layer> layers, List<At> engines, @Nullable At boosterNozzle, float boosterSmoke,
                                 FarLook far) {
        /** Cruise engine nozzle: only for weapons with nozzles ({@link #engines} is nonempty; Lancet has none). */
        public At engine() {
            return engines.getFirst();
        }

        /** Trail origins: at the booster exit while it burns (if present), otherwise at each nozzle. */
        public List<At> trailNozzles(boolean booster) {
            return booster && boosterNozzle != null ? List.of(boosterNozzle) : engines;
        }

        /** Nozzle exit emitting the phase's plume: booster or first cruise nozzle. */
        public At nozzle(Stage stage) {
            return stage.booster() && boosterNozzle != null ? boosterNozzle : engine();
        }
    }

    /** Sound of the launch booster (or motor) while burning. */
    public enum BoosterVoice {
        /** Catapult, without fire: only a one-shot thump and whistle ({@link LaunchCue#CATAPULT}). */
        NONE,
        /** Shahed booster: over the one-shot launch roar at the launcher, moves away with the projectile. */
        SOLID,
        /** Cruise missile booster: the same, at a lower pitch. */
        SOLID_LOW,
        /** Small rocket motor: sharp hiss, at a higher pitch. */
        ROCKET,
        /** Minuteman: low roar of a solid-fuel stage, audible a kilometre away. */
        ICBM
    }

    /** One-shot ignition sound when its front reaches the ear. */
    public enum LaunchCue {
        /** None (the ICBM has its own launch sound, {@code NukeSounds}). */
        NONE,
        /** Shahed booster roar at the launcher. */
        BOOSTER,
        /** Missile booster roar, at a lower pitch. */
        BOOSTER_LOW,
        /** Rocket launch rail exit from the tube: a sharp, crackling whoosh. */
        ROCKET_TUBE,
        /** Loitering munition catapult: piston thump and rail whistle. */
        CATAPULT
    }

    /**
     * Spent booster: mesh (supplier: models load only in game, sound unit tests also read the spec) and its offset.
     *
     * @param lift height above the ground (the mesh lies on its side)
     * @param dy   mesh centre in projectile axes: up
     * @param dz   and along
     */
    public record SpentBooster(Supplier<WeaponModels.Mesh> mesh, float lift, float dy, float dz) {}

    /**
     * Fire and smoke of a flight entity by phase and its appearance at distance, where the client has no entity
     * ({@code client.far.FarFlightView}): body uses the same model as nearby ({@code client.render.FarModels});
     * smaller than a couple of pixels, a soft dark point. The plume ({@link Flame}) is shared nearby and at distance:
     * jet ({@code PlumeRenderer}) and light. The trail ({@link Trail}) is a ribbon through track points nearby and
     * at distance, shared from launch to the end of the trail.
     *
     * @param size     largest body dimension (span or model length), blocks: model length on screen (point-to-model
     *                 transition, simplified copies) and its tile sphere ({@code FarModels.TILE_RADIUS} × size contains
     *                 the model in any pose, {@code FarModelsTest}); when larger than a pixel, the point is at least that size
     * @param area     mean silhouette area, m² (Cauchy: a quarter of the surface; body πDL/4, wing half its planform area):
     *                 how much sky the body covers when smaller than a pixel
     * @param color    body colour in light (mixed with haze at distance)
     * @param ignition during ignition ({@link FlightPhase#IGNITION}); null means {@code boost}
     * @param boost    while the launch booster burns ({@link FlightPhase#boosterLit}; ICBM and MLRS use the motor itself)
     * @param cruise   the rest of the flight
     * @param terminal during the dive ({@link FlightPhase#TERMINAL}); null means {@code cruise}
     */
    public record FarLook(double size, double area, int color, @Nullable Stage ignition, Stage boost, Stage cruise, @Nullable Stage terminal) {
        /** Fire and smoke in a phase: none on the launcher before ignition or underground (bomb penetrating). */
        public Stage stage(FlightPhase phase) {
            if (phase == FlightPhase.READY || phase == FlightPhase.DRILL) return Stage.NONE;
            if (phase == FlightPhase.IGNITION && ignition != null) return ignition;
            if (phase.boosterLit()) return boost;
            return phase == FlightPhase.TERMINAL && terminal != null ? terminal : cruise;
        }

        /**
         * The same at age ticks into the phase (including partial tick), with booster thrust from the thrust curve
         * ({@code Airframe.thrust}: ramp-up, plateau, decay to separation): flame, light and launch cloud follow the
         * thrust share; null means the phase's motor burns steadily.
         */
        public Stage stage(FlightPhase phase, double age, @Nullable ThrustCurve thrust) {
            Stage s = stage(phase);
            return thrust == null || !phase.boosterLit() || s.motor == null ? s : s.at(thrust.share(phase, age));
        }
    }

    /**
     * Fire and smoke in one phase: which motor burns and from which nozzle. Plume ({@link FlameModel}) and trail
     * ({@code TrailModel}) follow the laws from motor data, shared nearby and at distance.
     *
     * @param motor   motor; null if nothing burns
     * @param booster launch booster burning (nozzle: {@link ClientAirframe#boosterNozzle}), otherwise cruise nozzles
     * @param trail   trail; null if absent (MLRS ignition in the tube: trail starts at the front opening)
     */
    public record Stage(@Nullable Motor motor, boolean booster, @Nullable Trail trail, double share) {
        public static final Stage NONE = new Stage(null, false, null);

        /** Motor burning at full thrust. */
        public Stage(@Nullable Motor motor, boolean booster, @Nullable Trail trail) {
            this(motor, booster, trail, 1);
        }

        /**
         * The same phase at share of plateau thrust ({@code ThrustCurve}): plume is {@link FlameModel#at(Motor, double, double)},
         * flow is {@link #flow()}; trail is unchanged (matter per metre is that of the plateau: ⚠ no thinner during decay).
         */
        public Stage at(double share) {
            return share >= 1 && this.share >= 1 ? this : new Stage(motor, booster, trail, share);
        }

        /** Motor with flow at the current thrust share (launch cloud); null if not burning. */
        @Nullable
        public Motor flow() {
            return motor == null || share >= 1 ? motor : share <= 0 ? null : motor.throttled(share);
        }

        /** Motor burning and leaving a trail (one ribbon point every step ticks). */
        static Stage burning(Motor motor, boolean booster, int step) {
            return new Stage(motor, booster, Trail.of(motor, step));
        }

        /** The same phase with a trail laid along the article's real burn path ({@link Trail#alongBurn}). */
        Stage alongBurn(Article article) {
            return trail == null || article.booster() == null ? this
                    : new Stage(motor, booster, trail.alongBurn(article.burnPath(), article.booster().burn()), share);
        }

        /** Plume at world height y; null if not burning or not visible. */
        @Nullable
        public Flame flame(double y) {
            return motor == null ? null : FlameModel.at(motor, y - Air.SEA, share);
        }
    }

    // ---------------------------------------------------------------- motors
    //
    // Thrust, flow and exhaust velocity come from the article spec ({@link Article}: one value for server and client,
    // source there); only visual needs live here: temperature, product composition, smoke. Values use open data where
    // available (source alongside); missing data are estimates marked (est.) with ranges. A nozzle whose dimensions are not
    // published is calculated: exit pressure equals ambient pressure at its design altitude (ground level for
    // boosters, a few kilometres for first stages). Rocket fuel heat in air is
    // c_p·(T₀ − 288 K); rocket efficiency at launch (thrust × speed against heat) is below 0.1, taken as 0.

    /**
     * Combustible products at the exit (afterburn in air), mass fractions: aluminized composite CO ~0.22, H₂ ~0.02;
     * double-base CO ~0.40, H₂ ~0.01 (est.) (typical equilibrium exit compositions, not published for the articles).
     */
    private static final double COMPOSITE_CO = 0.22, COMPOSITE_H2 = 0.02, DOUBLE_BASE_CO = 0.40, DOUBLE_BASE_H2 = 0.01;

    /** Al₂O₃ fraction in products of fuel with aluminium fraction al: 2 Al → Al₂O₃ (102/54 = 1.89). */
    private static double alumina(double al) {
        return al * 102.0 / 54.0;
    }

    /** Solid-fuel motor from the spec: flow and exhaust velocity from {@link Article.SolidMotor}. */
    private static Motor solid(String name, Article.SolidMotor m, double temperature, double gamma, double molarMass,
                               double design, Smoke smoke, double smokeIndex, double co, double h2, double water) {
        return solid(name, 1, m.flow(), m.exhaust(), temperature, gamma, molarMass, design, smoke, smokeIndex, co, h2, water);
    }

    /** Solid-fuel motor with nozzle designed for design metres altitude (standard atmosphere pressure). */
    private static Motor solid(String name, int nozzles, double flow, double velocity, double temperature, double gamma, double molarMass,
                               double design, Smoke smoke, double smokeIndex, double co, double h2, double water) {
        double r = Motor.matchedRadius(nozzles, flow, velocity, temperature, gamma, molarMass, StandardAtmosphere.pressure(design));
        return solidWithExit(name, nozzles, flow, velocity, temperature, gamma, molarMass, r, smoke, smokeIndex, co, h2, water);
    }

    /** Solid-fuel motor with known nozzle exit radius exitRadius, m. */
    private static Motor solidWithExit(String name, int nozzles, double flow, double velocity, double temperature, double gamma, double molarMass,
                                       double exitRadius, Smoke smoke, double smokeIndex, double co, double h2, double water) {
        double cp = gamma * Motor.R / ((gamma - 1) * molarMass);
        return new Motor(name, nozzles, flow, flow, velocity, temperature, gamma, molarMass, exitRadius, smoke, smokeIndex, co, h2, water,
                cp * (temperature - StandardAtmosphere.T0), 0);
    }

    /** Air-breathing or piston engine: subsonic exhaust, exit at ground-level ambient pressure; molar mass is air's. */
    private static Motor breathing(String name, double exhaust, double fuel, double velocity, double temperature, double gamma, double soot,
                                   double water, double heat, double efficiency) {
        double r = Motor.matchedRadius(1, exhaust, velocity, temperature, gamma, AIR_MOLAR, StandardAtmosphere.P0);
        return new Motor(name, 1, exhaust, fuel, velocity, temperature, gamma, AIR_MOLAR, r, Smoke.SOOT, soot, 0, 0, water, heat, efficiency);
    }

    /** Air molar mass, kg/mol. */
    private static final double AIR_MOLAR = 0.028_96;

    /**
     * Shahed booster ({@link WeaponSpec#DRONE_BOOSTER}): composite fuel with ~10 % aluminium (est.), 3 000 K, γ 1.15,
     * 27 g/mol; nozzle designed for ground level.
     */
    static final Motor DRONE_BOOSTER = solid("drone booster", WeaponSpec.DRONE_BOOSTER, 3_000, 1.15, 0.027, 0, Smoke.ALUMINA, alumina(0.10), COMPOSITE_CO, COMPOSITE_H2, 0.09);
    /**
     * Tomahawk booster ({@link WeaponSpec#MISSILE_BOOSTER}): composite with ~16 % aluminium (est.), 3 200 K, γ 1.14
     * (two-phase; aluminized fuels have 1.13–1.18), 28 g/mol; nozzle designed for ground level.
     */
    static final Motor MISSILE_BOOSTER = solid("missile booster", WeaponSpec.MISSILE_BOOSTER, 3_200, 1.14, 0.028, 0, Smoke.ALUMINA, alumina(0.16), COMPOSITE_CO, COMPOSITE_H2, 0.09);
    /**
     * Tomahawk cruise turbofan ({@link WeaponSpec#MISSILE_TURBOFAN}: air flow, jet, fuel): mixed exhaust ~700 K (est.);
     * JP-10 (C₁₀H₁₆): 1.06 kg water per kg and 42.1 MJ/kg; soot ~0.03 g/kg (est.); efficiency 0.33 (thrust × 245 m/s
     * against fuel heat).
     */
    static final Motor MISSILE_SUSTAINER = breathing("missile turbofan", WeaponSpec.MISSILE_TURBOFAN.airflow(),
            WeaponSpec.MISSILE_TURBOFAN.fuelFlow(), WeaponSpec.MISSILE_TURBOFAN.jet(), 700, 1.38, 3e-5, 1.06, 42.1e6, 0.33);
    /**
     * Shahed two-stroke MD-550 (petrol flow: {@link WeaponSpec#DRONE_PISTON}): exhaust ~70 g/s at ~1 000 K and
     * ~50 m/s (est.); two-stroke soot and oil ~1 g/kg (est.); water ~1.35 kg/kg petrol, 43.5 MJ/kg; efficiency ~0.13
     * (propeller 0.7 × 37 kW against fuel heat).
     */
    static final Motor DRONE_ENGINE = breathing("drone piston", 0.07, WeaponSpec.DRONE_PISTON.fuelFlow(), 50, 1_000, 1.35, 1e-3, 1.35, 43.5e6, 0.13);
    /**
     * Each of the B-2's four F118-GE-100 engines ({@link WeaponSpec#BOMBER_TURBOFANS}: static thrust, cruise fuel flow):
     * at cruise altitude ~60 kg/s exhaust at ~450 m/s and ~700 K (est.) (air is four times less dense than at
     * ground level, so flow and jet speed are below the static spec); kerosene: 1.25 kg water per kg and 43.2 MJ/kg
     * (Schumann 1996), efficiency 0.3 (same source, engines of that generation), soot ~0.04 g/kg (est.).
     */
    static final Motor BOMBER_ENGINE = breathing("bomber turbofan", 60, WeaponSpec.BOMBER_TURBOFANS.fuelFlow(), 450, 700, 1.38, 4e-5, 1.25, 43.2e6, 0.3);
    /**
     * 9M22U rocket motor (flow and exhaust velocity: {@link WeaponSpec#ROCKET_MOTOR}): double-base (ballistite, (est.) Soviet
     * practice), ~2 800 K, γ 1.22, 24 g/mol; smoke is stabilizer salts ~2 % (est.); nozzle fits the 122 mm calibre,
     * exit radius 5.5 cm (est.): a ground-pressure nozzle would not fit (~10 cm needed), so the jet is underexpanded.
     */
    static final Motor ROCKET_MOTOR = solidWithExit("grad motor", 1, WeaponSpec.ROCKET_MOTOR.flow(), WeaponSpec.ROCKET_MOTOR.exhaust(), 2_800, 1.22, 0.024, 0.055, Smoke.SALTS, 0.02, DOUBLE_BASE_CO, DOUBLE_BASE_H2, 0.12);
    /**
     * Minuteman III first stage (M55, composite: ammonium perchlorate, aluminium, PBAA binder; thrust, fuel and burn
     * time: ICBM {@link WeaponSpec.Ascent}): 340.7 kg/s at 2 652 m/s; four nozzles, (est.) designed for ~3 km
     * (overexpanded at ground level); ~3 300 K, γ 1.14, 28 g/mol, ~16 % aluminium (est.).
     */
    static final Motor ICBM_STAGE = solid("icbm stage", 4, WeaponSpec.NUKE.ascent().flow(), WeaponSpec.NUKE.ascent().exhaust(), 3_300, 1.14, 0.028, 3_000,
            Smoke.ALUMINA, alumina(0.16), COMPOSITE_CO, COMPOSITE_H2, 0.09);

    /**
     * 9M96 SAM solid-fuel motor (flow and exhaust velocity: {@link InterceptorSpec.Passport}; the second, cruise mode
     * is not physical: no open data show it for the 9M96): composite with ~16 % aluminium (est.), nozzle designed for
     * ground level; cruise mode uses the same charge and nozzle at lower chamber pressure, so the jet is overexpanded.
     */
    public static final Motor SAM_BOOST = solid("sam boost", InterceptorSpec.SAM.passport().boost(), 3_200, 1.14, 0.028, 0, Smoke.ALUMINA, alumina(0.16), COMPOSITE_CO, COMPOSITE_H2, 0.09);
    public static final Motor SAM_SUSTAIN = solidWithExit("sam sustain", 1, InterceptorSpec.SAM.passport().sustain().flow(),
            InterceptorSpec.SAM.passport().sustain().exhaust(), 3_200, 1.14, 0.028, SAM_BOOST.exitRadius(), Smoke.ALUMINA,
            alumina(0.16), COMPOSITE_CO, COMPOSITE_H2, 0.09);

    /**
     * 9M96 SAM: length and calibre from the spec, silhouette πDL/4. Fire and trail use the boost motor during boost,
     * then cruise mode (one ribbon point every tick during boost: the launch rail exit arc is steep). Beyond entity
     * range, FarFlights packets supply the track: without them, no cruise smoke segment was born at all.
     */
    private static final FarLook SAM_FAR = new FarLook(InterceptorSpec.SAM.passport().length(),
            Math.PI * InterceptorSpec.SAM.passport().diameter() * InterceptorSpec.SAM.passport().length() / 4, 0xC8C8BE, null,
            Stage.burning(SAM_BOOST, false, 1), Stage.burning(SAM_SUSTAIN, false, 2), null);
    /** SAM: nozzle at the model's tail; engine sound is {@code EngineSound.Layer.SAM} ({@code InterceptorSound}). */
    public static final ClientAirframe INTERCEPTOR = new ClientAirframe(WeaponModels::interceptor, List.of(),
            List.of(new At(0, 0, -InterceptorSpec.SAM.noseLength())), null, 0, SAM_FAR);

    /** Shahed: 3.6 m, span 2.6; delta wing ~4.7 m² and body Ø 0.45. */
    private static final FarLook DRONE_FAR = new FarLook(3.6, 3.6, 0x3C3E42, null, Stage.burning(DRONE_BOOSTER, true, 2),
            Stage.burning(DRONE_ENGINE, false, 4), null);
    /** Tomahawk: 5.9 m, with booster 6.2, Ø 0.52, span 2.7. */
    private static final FarLook MISSILE_FAR = new FarLook(6.2, 3.1, 0x6E7276, null, Stage.burning(MISSILE_BOOSTER, true, 2),
            Stage.burning(MISSILE_SUSTAINER, false, 4), null);
    /** B-2: span 52 m, wing planform 478 m²; each of four engines leaves a contrail when conditions allow. */
    private static final FarLook BOMBER_FAR = new FarLook(52, 250, 0x2E3034, null, Stage.NONE, Stage.burning(BOMBER_ENGINE, false, 4), null);
    /** GBU-57: 6.2 m, Ø 0.8; no motor, so no fire or trail. */
    private static final FarLook BOMB_FAR = new FarLook(6.2, 3.9, 0x55595E, null, Stage.NONE, Stage.NONE, null);
    /** Minuteman III: 18.3 m, Ø 1.7; stage burns throughout the flight. */
    private static final Stage ICBM_BURN = Stage.burning(ICBM_STAGE, false, 4);
    private static final FarLook ICBM_FAR = new FarLook(18.3, 24, 0xD8D6D0, null, ICBM_BURN, ICBM_BURN, null);
    /**
     * Grad rocket: 2.9 m, Ø 0.12; ignition in the tube, trail from the front opening; no fire after burnout. Trail
     * follows the real burn path (~650 m, {@code Article.burnPath}) at any flight speed: at game speed it is laid
     * even after the plume ends, not physical.
     */
    private static final FarLook ROCKET_FAR = new FarLook(2.9, 0.28, 0x4E5446, new Stage(ROCKET_MOTOR, false, null),
            Stage.burning(ROCKET_MOTOR, false, 2).alongBurn(WeaponType.ROCKET.spec().striker().article()), Stage.NONE, null);
    /** Lancet: 1.65 m, Ø 0.2, two cruciform wings; catapult and electric motor have no fire or smoke. */
    private static final FarLook LOITER_FAR = new FarLook(1.65, 0.62, 0x6A7066, null, Stage.NONE, Stage.NONE, null);

    public static final ClientWeaponSpec DRONE = new ClientWeaponSpec(
            new ClientAirframe(WeaponModels::drone, List.of(EngineSound.Layer.DRONE_NEAR, EngineSound.Layer.DRONE_FAR, EngineSound.Layer.BOOSTER),
                    List.of(new At(0, 0.03, -1.95)), new At(0, -0.29, -1.95), 0.55f, DRONE_FAR),
            null, BoosterVoice.SOLID, LaunchCue.BOOSTER, true,
            new SpentBooster(() -> WeaponModels.Mesh.DRONE_BOOSTER, 0.1f, 0.29f, 1.22f));

    public static final ClientWeaponSpec MISSILE = new ClientWeaponSpec(
            new ClientAirframe(WeaponModels::missile, List.of(EngineSound.Layer.MISSILE_FRONT, EngineSound.Layer.MISSILE_REAR, EngineSound.Layer.MISSILE_DIVE,
                    EngineSound.Layer.MISSILE_FAR, EngineSound.Layer.MISSILE_WHISTLE, EngineSound.Layer.BOOSTER),
                    List.of(new At(0, 0, -3.2)), new At(0, 0, -3.45), 0.8f, MISSILE_FAR),
            null, BoosterVoice.SOLID_LOW, LaunchCue.BOOSTER_LOW, true,
            new SpentBooster(() -> WeaponModels.Mesh.MISSILE_BOOSTER, 0.24f, 0, 3.15f));

    public static final ClientWeaponSpec BUNKER = new ClientWeaponSpec(
            new ClientAirframe(WeaponModels::bomber, List.of(EngineSound.Layer.BOMBER_NEAR, EngineSound.Layer.BOMBER_FAR),
                    List.of(new At(3.2, 0.3, -9.5), new At(1.9, 0.3, -9.3), new At(-1.9, 0.3, -9.3), new At(-3.2, 0.3, -9.5)),
                    null, 0, BOMBER_FAR),
            new ClientAirframe(WeaponModels::bomb, List.of(EngineSound.Layer.BOMB_NEAR, EngineSound.Layer.BOMB_FAR, EngineSound.Layer.BOMB_DRILL),
                    List.of(new At(0, 0, -5.2)), null, 0, BOMB_FAR),
            BoosterVoice.SOLID, LaunchCue.BOOSTER, true, null);

    public static final ClientWeaponSpec NUKE = new ClientWeaponSpec(
            new ClientAirframe(WeaponModels::icbm, List.of(EngineSound.Layer.BOOSTER), List.of(new At(0, 0, -10)), null, 0, ICBM_FAR),
            null, BoosterVoice.ICBM, LaunchCue.NONE, false, null);

    /** MLRS: motor roar while burning; then the rocket coasts and howls through the air. */
    public static final ClientWeaponSpec ROCKET = new ClientWeaponSpec(
            new ClientAirframe(WeaponModels::rocket, List.of(EngineSound.Layer.BOOSTER, EngineSound.Layer.ROCKET_AIR), List.of(new At(0, 0, -1.5)),
                    null, 0, ROCKET_FAR),
            null, BoosterVoice.ROCKET, LaunchCue.ROCKET_TUBE, true, null);

    /** Loitering munition: same propeller, but a small electric motor, higher and quieter (see {@code EngineSound}). */
    public static final ClientWeaponSpec LOITER = new ClientWeaponSpec(
            new ClientAirframe(WeaponModels::loiter, List.of(EngineSound.Layer.LOITER_NEAR, EngineSound.Layer.LOITER_FAR, EngineSound.Layer.LOITER_DIVE,
                    EngineSound.Layer.BOOSTER), List.of(), null, 0, LOITER_FAR),
            null, BoosterVoice.NONE, LaunchCue.CATAPULT, false, null);

    /** Client weapon spec. */
    public static ClientWeaponSpec of(WeaponType weapon) {
        return switch (weapon) {
            case DRONE -> DRONE;
            case MISSILE -> MISSILE;
            case BUNKER -> BUNKER;
            case NUKE -> NUKE;
            case ROCKET -> ROCKET;
            case LOITER -> LOITER;
        };
    }

    /**
     * Hot booster nozzles by weapon: cooling table (tens of milliseconds) computed once in the background at the
     * first booster; no nozzle until ready (first fractions of a second of ignition, walls still cold).
     */
    private static final java.util.Map<WeaponType, java.util.concurrent.CompletableFuture<java.util.Optional<NozzleGlow>>> NOZZLES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Hot booster nozzle for weapon weapon ({@link #buildNozzleGlow}), if already computed; otherwise null. */
    @Nullable
    public static NozzleGlow nozzleGlow(WeaponType weapon) {
        var f = NOZZLES.computeIfAbsent(weapon, w -> java.util.concurrent.CompletableFuture.supplyAsync(() -> buildNozzleGlow(w),
                net.minecraft.Util.backgroundExecutor()).whenComplete((g, ex) -> {
                    if (ex != null) ua.zentix.incoming.Incoming.LOG.error("Booster nozzle glow of {} failed: no hot nozzle", w, ex);
                }));
        return f.isDone() && !f.isCompletedExceptionally() ? f.join().orElse(null) : null;
    }

    /**
     * Hot nozzle of weapon weapon's discarded launch booster ({@link NozzleGlow}: booster motor, in-game thrust curve
     * and real burn time); empty if there is no such booster.
     */
    public static java.util.Optional<NozzleGlow> buildNozzleGlow(WeaponType weapon) {
        Stage boost = of(weapon).airframe(false).far().boost();
        ThrustCurve curve = weapon.spec().airframe().thrust();
        Article.SolidMotor real = weapon.spec().airframe().article().booster();
        if (of(weapon).spent() == null || boost.motor() == null || !boost.booster() || curve == null || real == null) return java.util.Optional.empty();
        return java.util.Optional.of(new NozzleGlow(boost.motor(), curve, real.burn()));
    }

    /** Flight entity: carrier or second entity (bomb). */
    public ClientAirframe airframe(boolean payload) {
        return payload && this.payload != null ? this.payload : airframe;
    }
}
