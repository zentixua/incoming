package ua.zentix.incoming.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.far.FarTerrain;
import ua.zentix.incoming.client.far.Sightline;
import ua.zentix.incoming.client.nuclear.Deafness;
import ua.zentix.incoming.client.sound.BlastSounds;
import ua.zentix.incoming.client.sound.Outdoor;
import ua.zentix.incoming.client.sound.SoundFilters;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.BodySway;
import ua.zentix.incoming.nuclear.model.HearingModel;
import ua.zentix.incoming.nuclear.model.ShockAtBody;
import ua.zentix.incoming.warhead.GroundMaterial;

import java.util.Locale;

/**
 * Conventional blast front at the player's body — the same laws as the nuclear wave ({@code NukeSounds.Schedule.hit}): hearing by
 * wave energy at the ear ({@link HearingModel#exposure(double, double)}: a conventional blast phase lasts milliseconds,
 * the entire wave is audible), and body impulse ({@link CameraShake#sway}: diffraction loading and wind behind the front over the positive phase,
 * {@code BodySway}) — with the head on the neck: the camera sways with the head as well as body lean. Pressure and phase are Kinney–Graham ({@link BlastModel}) at the body, rather than the camera: the projectile camera orbits
 * the blast, the player stands aside.
 */
public final class BlastHit {
    /** Last front at the body (client scenario line). */
    private static String last = "-";

    private BlastHit() {}

    /** Last conventional blast front at the body: pressure, phase, ear energy, threshold shift, lean. */
    public static String describe() {
        return last;
    }

    /**
     * @param late ticks since the front passed the body (0..1)
     */
    static void hit(BlastSource s, Vec3 pos, GroundMaterial ground, long seed, LocalPlayer player, Vec3 body, double late) {
        double d = Math.max(0.5, body.distanceTo(pos));
        ShockAtBody shock = ShockAtBody.chemical(s.chargeKg(), s.surface(), d);
        double pa = shock.pressurePa(), tPlus = shock.positiveSeconds();
        double exposure = HearingModel.exposure(pa, tPlus);
        if (IncomingConfig.CLIENT.nukeTinnitus.get()) hurtEars(pos, ground, body, d, pa, tPlus, seed);
        // camera tremor at impact — presentation, by wave pressure and impulse at the body (also when riding or swimming: the hit is felt)
        double tremor = CameraShake.tremor(pa, shock.impulsePaSeconds(), late, seed);
        double lean = 0, neck = 0;
        float open = 0;
        BodySway.Motion m = null;
        if (!CameraShake.carried(player)) {
            // behind a wall, house or hill toward the blast — in its wind shadow (rays at the body)
            open = SoundFilters.openRays(body, Math.atan2(pos.z - body.z, pos.x - body.x));
            m = CameraShake.sway(pa, shock.dynamicPressurePa(), open, tPlus, tPlus, late,
                    body.subtract(pos), player.getYRot(), true);
            lean = Math.toDegrees(BodySway.peak(m.body(), 0));
            neck = Math.toDegrees(BodySway.peak(m.neck(), 0));
        }
        // screen concussion and litter near the camera — from the same front at the body (model-based presentation: ConcussionDose, LocalLitter)
        double dose = feel(IncomingConfig.CLIENT.blastEffects.get(), shock, HearingModel.shift(exposure), m, late,
                () -> NearLitter.front(s, pos, body, shock.arrivalSeconds(), seed));
        last = String.format(Locale.ROOT, "body d %.0f p %.0f Pa phase %.1f ms LAE %.1f dB shift %.1f dB open %.2f lean %.2f deg neck %.2f deg tremor %.2f dose %.3f",
                d, pa, 1000 * tPlus, exposure, HearingModel.shift(exposure), open, lean, neck, tremor, dose);
    }

    /**
     * Screen concussion and litter near the camera from the front at the body — impact visuals: only with custom blast effects
     * ({@code own} — {@code blast_effects}). An air-defense burst also comes here when those are disabled (its visuals and sound are always custom), while
     * these two are part of what the setting delegates to another explosion mod. Returns concussion dose (0 — none).
     */
    static double feel(boolean own, ShockAtBody shock, double shiftDb, @Nullable BodySway.Motion head, double late, Runnable litter) {
        if (!own) return 0;
        litter.run();
        return Concussion.front(shock, shiftDb, head, late);
    }

    /**
     * Hearing: wave energy at the ear minus path losses (hills and the shadow zone attenuate pressure, with stronger high-frequency losses,
     * while A-weighted energy is at 1–3 kHz); eardrums — pressure after path losses, as for the nuclear wave. Path — {@link Outdoor}:
     * terrain edge along the ray from the blast ({@link Sightline}).
     */
    private static void hurtEars(Vec3 pos, GroundMaterial ground, Vec3 body, double d, double pa, double tPlus, long seed) {
        ClientLevel level = Minecraft.getInstance().level;
        double lost = 0, highs = 1;
        if (level != null) {
            double z = Sightline.trace(FarTerrain.of(level), pos.x, pos.y + 1, pos.z, body.x, body.y, body.z).pathDifference();
            Outdoor.Path path = BlastSounds.path(d, z, 1, ground.porosity);
            if (path != null) {
                lost = Outdoor.pressureLoss(path);
                highs = Outdoor.pressureHighs(path);
            }
        }
        Deafness.blast(HearingModel.exposure(pa, tPlus) - lost + 20 * Math.log10(Math.max(1e-3, highs)), pa / 1000 * Math.pow(10, -lost / 20), seed);
    }
}
