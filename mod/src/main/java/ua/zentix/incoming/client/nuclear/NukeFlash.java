package ua.zentix.incoming.client.nuclear;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.fx.Flash;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.client.render.ScreenProjection;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.EyeInjury;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.ThermalModel;
import ua.zentix.incoming.nuclear.radiation.FlashEyes;
import ua.zentix.incoming.nuclear.radiation.RetinaScars;

import java.util.List;

/**
 * Nuclear explosion flash on screen (DESIGN-nuke §6): a frame set for what the camera saw before the flash is overexposed by
 * its light and catches up ({@link NukeExposure#overexposure}, presentation): white before and at the second maximum, then
 * dark silhouettes emerge ever more brightly; for 15 kt at 1.4 km after half a second the frame is overexposed ~3 times, after a second
 * it is as before ({@link #overexposed}: frame gain and light scattered in the lens). Pulse light and timing follow physics;
 * previously the screen stayed white through the full pulse (~10·t_max: 1.2 s for 15 kt), then faded for another 2–4 s. Next comes a dark-orange
 * afterimage "spot" where the ball was on the retina, if the player looked at it (moves with gaze). Meanwhile the entire world is lit like lightning: night
 * briefly becomes day. Strength follows the light pulse at the player: full with direct visibility and the ball in view, otherwise
 * one third (sky glare).
 *
 * <p>Eyes (G&amp;D §12.83–12.88): full-field dazzle is a veil from {@link EyeInjury#dazzleSeconds}, based on the pulse at the eye
 * (ball seen — full; unseen but under open sky — sky-scattered; under a roof — none) and dark adaptation:
 * a bright afterimage at first, turning gray by day or black at night after seconds, fading by the deadline; shorter
 * dazzle means a weaker veil. Retinal burns are permanent dark spots where the ball was on the retina (from server,
 * {@link FlashEyes}); they move with gaze. Creative and spectator use a camera rather than eyes (they cannot be injured): no
 * dazzle or spots in these modes (spots remain attached to the player), with the same white screen and afterimage; the Flashback
 * viewer is a spectator. A sleeping player has closed eyes. Death clears dazzle.
 */
public final class NukeFlash {
    @Nullable
    private static ClientNuclear.Active current;
    /** Flash strength 0..1 for this player. */
    private static float strength;
    /**
     * Ratio of flash light at the camera at the second maximum to the light the frame is set for (≥ 1): controls how the frame
     * catches up with the flash ({@link NukeExposure#overexposure}).
     */
    private static double peak = 1;
    /** Afterimage: remaining ticks and original total. */
    private static int afterLeft, afterTotal;
    /**
     * Afterimage location on the retina: screen position at flash time (fractions of width and height), spot half-size (fraction of height).
     * The spot is a retinal burn, so it moves with gaze rather than staying in the world.
     */
    private static float afterX, afterY, afterSize;
    private static long ticks, endTick;
    /**
     * Frame overexposure at which lens-scattered light whitens the entire frame (white share = overexposure / VEIL_X: the lens
     * scatters about a percent of frame light) (est.), and maximum on-screen frame gain (above this the frame
     * is already white). Presentation.
     */
    private static final double VEIL_X = 100, GAIN_MAX = 16;
    /** Overexposure below which the frame stays unchanged: gain is smaller than a screen step. */
    private static final double SETTLED = 1.004;
    /**
     * Sky glare at full air transmittance, fraction of a white screen, and glare threshold for flash lighting the world
     * ({@link #lightUpWorld}). (est.), not derived: both are screen fractions, not light; glare has no yield or
     * 1/d² term, limited only by air (Glasstone, fig. 7.104; beyond half the visibility range — extrapolated tuning). Thus
     * the world flashes out to ~5.3 visibility ranges at any yield, while ball light at the second maximum there ({@code MushroomModel.light}
     * through the same air) is 0.5 % of noon light for 1 kt, 4.5 % for 15 kt, 90 % for 1 Mt: at night (dark-adapted eye,
     * {@code Sight.adapted}) this is a flash; by day, glare from small yields at distance exceeds physics.
     */
    private static final double SKY_GLARE = 0.6, WORLD_FLASH = 0.05;
    /** Fraction of the pulse scattered by the sky toward someone who cannot see the ball (est.). */
    private static final double SCATTERED = 0.15;
    /** Initial dazzle veil (almost nothing discernible in the field) and time for the bright afterimage to darken, s. */
    private static final float VEIL = 0.9f, AFTERGLOW_S = 2;
    /** Veil after the afterimage: gray by day (dazzled cones), black at night. */
    private static final float DAY_GREY = 0.45f;
    /** Field dazzle: start (local client ticks, pulse end), duration in s, strength 0..1, degree of dark adaptation. */
    private static double veilStart, veilSeconds;
    private static float veilStrength, veilDark;
    /** Retinal burns — spots in the visual field ({@link RetinaScars.Scar}). */
    private static List<RetinaScars.Scar> scars = List.of();

    private NukeFlash() {}

    static void detonation(ClientNuclear.Active a) {
        Detonation d = a.d;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        Vec3 eye = p.getEyePosition();
        boolean sees = NuclearWarhead.sees(mc.level, d, p);
        double q = d.fluence(eye);
        double distM = d.metres(eye.distanceTo(d.burst()));
        // in addition to the actual light pulse — dazzled sky (SKY_GLARE): ball light scattered by air, using transmittance
        // of direct and scattered light together (ThermalModel.transmittance, as for the light pulse), not a beam: ball glare
        // (NukeRenderer.fireballs) is a beam, dazzled sky is diffuse light
        double sky = Math.min(1, q / 2 + SKY_GLARE * ThermalModel.transmittance(distM, d.visibility()));
        lightUpWorld(mc.level, d, sky);
        double s = sky;
        Vec3 look = p.getViewVector(1);
        double angle = Math.toDegrees(Math.acos(Mth.clamp(look.dot(d.burst().subtract(eye).normalize()), -1, 1)));
        boolean inView = sees && angle < 60;
        s *= inView ? 1 : sees ? 0.45 : 0.3;
        s *= IncomingConfig.CLIENT.flash.get();
        if (eyes(p) && !p.isSleeping()) dazzle(mc.level, d, eye, sees ? q : Detonation.underOpenSky(mc.level, eye) ? q * SCATTERED : 0);
        // "Hide Lightning Flashes": no world glare, partial white screen
        if (Flash.calm()) s = Math.min(s, Flash.CALM);
        if (s <= strength && current != null) return;
        current = a;
        strength = (float) s;
        peak = 1 + flashOnCamera(mc.level, d, distM) * (inView ? 1 : sees ? 0.45 : 0.3);
        endTick = ticks + (long) Math.ceil((FireballModel.secondMaximumSeconds(d.yieldKt()) * d.scale() + NukeExposure.settled(peak, SETTLED)) * 20);
        // spot larger than the visible ball: the eye "smears" glare
        float[] spot = inView && q >= 0.05 ? retina(mc, p, d.burst(), Math.max(d.fireballRadius(), 30) * 2.2) : null;
        if (spot != null) {
            // 5–30 s from the light pulse (cal/cm²)
            afterTotal = afterLeft = (int) (20 * Mth.clamp(5 + q * 2.5, 5, 30));
            afterX = spot[0];
            afterY = spot[1];
            afterSize = spot[2];
        }
    }

    /**
     * Ball light at the camera at the second maximum relative to the frame's set exposure (sky light, {@link Sight#adapted}):
     * Lambertian sphere of maximum radius, black body at {@link FireballModel#PEAK_K}, L·R²/max(d², R²) through air
     * (direct and scattered together, {@link ThermalModel#transmittance}, as for sky glare). For 15 kt at 1.4 km by day ~2 000,
     * at 5 km ~200; at night ~1 200 times more.
     */
    static double flashOnCamera(ClientLevel level, Detonation d, double distM) {
        double r = FireballModel.maxRadius(d.yieldKt(), d.surface());
        double light = BlackBody.luminance(FireballModel.PEAK_K) * r * r / Math.max(distM * distM, r * r) * ThermalModel.transmittance(distM, d.visibility());
        return Sight.adapted(light, FarView.ambient(level, 0));
    }

    /** Field dazzle from pulse {@code q} cal/cm² at the eye; keep existing dazzle that ends later. */
    private static void dazzle(ClientLevel level, Detonation d, Vec3 eye, double q) {
        double dark = FlashEyes.darkness(level, eye);
        double seconds = EyeInjury.dazzleSeconds(q, d.yieldKt(), dark);
        float k = IncomingConfig.CLIENT.flash.get().floatValue();
        if (Flash.calm()) k = Math.min(k, Flash.CALM);
        if (seconds <= 0 || k <= 0) return;
        // from pulse maximum: previously from pulse end when the white screen ended; the frame now catches up with the flash earlier
        // (overexposed), leaving a gap in which the eye would see clearly
        double start = ticks + FireballModel.secondMaximumSeconds(d.yieldKt()) * 20 * d.scale();
        if (start + seconds * 20 <= veilStart + veilSeconds * 20) return;
        veilStart = start;
        veilSeconds = seconds;
        veilStrength = k * (float) Math.min(1, seconds / EyeInjury.EDGE_S);
        veilDark = (float) dark;
    }

    /**
     * Where the player's own eye sees a ball of radius {@code radius} at {@code at} on screen: from their eye and gaze rather than
     * the frame camera (projectile camera and third-person view do not look through their eyes). Scale uses the frame projection if the frame is
     * their eyes (FOV with spyglass, sprinting); otherwise their configured FOV.
     *
     * @return {x, y — fractions of screen width and height; half-size — fraction of height}, or null if the ball is behind the player
     */
    @Nullable
    private static float[] retina(Minecraft mc, LocalPlayer p, Vec3 at, double radius) {
        Vec3 f = p.getViewVector(1), to = at.subtract(p.getEyePosition());
        double depth = to.dot(f);
        if (depth <= 1e-3) return null;
        double yaw = Math.toRadians(p.getYRot());
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw)), up = right.cross(f);
        double sx, sy;
        if (mc.getCameraEntity() == p && mc.options.getCameraType().isFirstPerson()) {
            sx = ScreenProjection.horizontalScale();
            sy = ScreenProjection.verticalScale();
        } else {
            sy = 0.5 / Math.tan(Math.toRadians(mc.options.fov().get()) / 2);
            sx = sy * mc.getWindow().getHeight() / Math.max(1, mc.getWindow().getWidth());
        }
        return new float[]{(float) (0.5 + to.dot(right) / depth * sx), (float) (0.5 - to.dot(up) / depth * sy), (float) (radius / depth * sy)};
    }

    /**
     * World flash lighting, like lightning: vanilla sky flash (full sky light at every block, as in daylight) for
     * 1.5–3 s × ∛(Y/15), for everyone who can see the dazzled sky (glare at least {@link #WORLD_FLASH}: for an airburst
     * at 240·∛Y m in clear weather, 1 and 15 kt out to ~107 km, 1 Mt out to ~119 km; in rain 27, 28 and 44 km; in a thunderstorm 6,
     * 7 and 17 km). Absent if flash is disabled in settings or vanilla "Hide Lightning Flashes" is on ({@link Flash#calm}).
     */
    private static void lightUpWorld(ClientLevel level, Detonation d, double sky) {
        if (sky < WORLD_FLASH || IncomingConfig.CLIENT.flash.get() <= 0 || Flash.calm()) return;
        int flash = (int) (20 * (1.5 + 1.5 * sky) * Math.cbrt(d.yieldKt() / 15));
        level.setSkyFlashTime(Math.max(level.getSkyFlashTime(), flash));
    }

    static void tick() {
        ticks++;
        if (afterLeft > 0) afterLeft--;
        if (current != null && ticks > endTick && afterLeft <= 0) {
            current = null;
            strength = 0;
        }
    }

    /** Eyes rather than a camera: neither creative nor spectator (nor a replay viewer). */
    private static boolean eyes(@Nullable LocalPlayer p) {
        return p != null && !p.isCreative() && !p.isSpectator();
    }

    /** Respawn after death: the new body does not carry the dead eyes' dazzle or afterimage (server sends retinal spots). */
    public static void respawned() {
        afterLeft = 0;
        veilSeconds = 0;
    }

    static void reset() {
        current = null;
        strength = 0;
        afterLeft = 0;
        veilSeconds = 0;
    }

    /** Retinal burns from the server — all spots in full (state, not an event). */
    public static void scars(RetinaScars s) {
        scars = s.scars();
    }

    /**
     * How white the screen is now (0..1): also for sky and fog — the fraction by which overexposure {@link #overexposure}
     * raises midtones toward white, 1 − 1/X.
     */
    public static float whiteness(float partialTick) {
        ClientNuclear.Active a = current;
        if (a == null || strength <= 0) return 0;
        return (float) (strength * (1 - 1 / overexposure(a, partialTick)));
    }

    /**
     * Frame overexposure from detonation {@code a}'s flash (≥ 1; 1 — frame caught up, or a different flash than on screen): also used for ball
     * glare ({@code NukeRenderer.fireballs}).
     */
    static double overexposure(ClientNuclear.Active a, float partialTick) {
        if (a != current || strength <= 0) return 1;
        double after = (a.seconds(partialTick) - FireballModel.secondMaximumSeconds(a.d.yieldKt())) * a.d.scale();
        return NukeExposure.overexposure(peak, after);
    }

    public static void render(GuiGraphics g, DeltaTracker delta) {
        float partial = delta.getGameTimeDeltaPartialTick(false);
        int w = g.guiWidth(), h = g.guiHeight();
        NukeDust.render(g);
        overexposed(g, partial, w, h);
        if (eyes(Minecraft.getInstance().player)) {
            if (!scars.isEmpty()) scars(g, w, h);
            veil(g, partial, w, h);
        }
        if (afterLeft > 0 && current != null) afterimage(g, partial, w, h);
    }

    /**
     * Frame overexposed by the flash ({@link #overexposure}, presentation): frame gain is overexposure X in screen values
     * (X^(1/2.2), capped at {@link #GAIN_MAX}: bright becomes white, dark stays dark — silhouettes against the flash, like
     * film), with lens-scattered light on top, white share X / {@link #VEIL_X} (blue-white at first, then
     * warm). A uniform white fill hid the whole frame; gain affects only bright content.
     * Gain uses "frame color × (1 + c)" blending in ×2 passes.
     */
    private static void overexposed(GuiGraphics g, float partial, int w, int h) {
        ClientNuclear.Active a = current;
        if (a == null || strength <= 0) return;
        double x = overexposure(a, partial);
        if (x < SETTLED) return;
        double gain = 1 + strength * (Math.min(Math.pow(x, 1 / 2.2), GAIN_MAX) - 1);
        if (Flash.calm()) gain = Math.min(gain, 1 + Flash.CALM);
        g.flush();
        if (gain > 1.002) {
            int passes = Math.max(1, (int) Math.ceil(Math.log(gain) / Math.log(2) - 1e-9));
            float c = (float) (Math.pow(gain, 1.0 / passes) - 1);
            Matrix4f m = g.pose().last().pose();
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ONE,
                    GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
            RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);
            for (int i = 0; i < passes; i++) {
                BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
                b.addVertex(m, 0, 0, 0).setColor(c, c, c, 1f);
                b.addVertex(m, 0, h, 0).setColor(c, c, c, 1f);
                b.addVertex(m, w, h, 0).setColor(c, c, c, 1f);
                b.addVertex(m, w, 0, 0).setColor(c, c, c, 1f);
                BufferUploader.drawWithShader(b.buildOrThrow());
            }
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }
        float white = (float) (strength * Math.min(1, x / VEIL_X));
        if (white > 0.004f) {
            int alpha = Math.min(255, (int) (white * 255));
            // blue-white at first, then warm
            int rgb = a.seconds(partial) < FireballModel.firstMinimumSeconds(a.d.yieldKt()) * 4 ? 0xEEF4FF : 0xFFF8E8;
            g.fill(0, 0, w, h, (alpha << 24) | rgb);
        }
    }

    /**
     * Afterimage — an orange spot where the ball was on the retina at flash time, fading slowly; moves with
     * gaze like a real one: turn your head and the spot stays at the same screen position while the ball moves away. Screen position and size
     * come from flash time: FOV changes (spyglass, sprinting) do not move it, just as they do not move the retina.
     * Additive light: normal blending would put the spot's dark core over the still-glowing ball as a dark
     * dot (the ball rises while the spot stays where the flash was).
     */
    private static void afterimage(GuiGraphics g, float partial, int w, int h) {
        float px = Mth.clamp(afterSize * h, 14, 400);
        float life = (afterLeft - partial) / afterTotal;
        float a = Mth.clamp(life * 1.3f, 0, 0.6f) * Math.min(1, strength * 1.5f);
        if (a <= 0.01f) return;
        ShaderInstance shader = NukeRenderer.flare;
        if (shader == null) return;
        float x = afterX * w, y = afterY * h;
        RenderSystem.setShader(() -> shader);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        // spot up to 800 pixels — analytic glow (an image would show texture-pixel squares)
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        Matrix4f m = g.pose().last().pose();
        spot(b, m, x, y, px, 0.55f, 0.24f, 0.08f, a);
        spot(b, m, x, y, px / 3, 0.6f, 0.4f, 0.2f, a * 0.5f);
        BufferUploader.drawWithShader(b.buildOrThrow());
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    /**
     * Dazzle veil: from pulse maximum (before it — white frame, {@link #overexposed}), fades by its deadline as
     * (1 − t/T)²; color is a bright afterimage darkening over seconds (toward gray by day, black at night: a dark-adapted
     * eye cannot see dim light).
     */
    private static void veil(GuiGraphics g, float partial, int w, int h) {
        if (veilSeconds <= 0) return;
        double t = (ticks + partial - veilStart) / 20;
        if (t < 0 || t >= veilSeconds) return;
        double life = 1 - t / veilSeconds;
        float a = (float) (VEIL * life * life * veilStrength);
        if (a <= 0.004f) return;
        float base = DAY_GREY * (1 - veilDark), glow = (float) Math.exp(-t / AFTERGLOW_S);
        float c = base + (1 - base) * glow;
        int r = (int) (255 * c), gr = (int) (255 * c * 0.96f), b = (int) (255 * c * 0.88f);
        g.fill(0, 0, w, h, (Math.min(255, (int) (a * 255)) << 24) | (r << 16) | (gr << 8) | b);
    }

    /**
     * Retinal burns — dark, soft-edged spots where the ball was on the retina, in the player's configured FOV (like
     * the retina: spyglass and sprinting do not move them). Near gaze center the spot is noticeable; to the side the brain mostly fills it in
     * (§12.85: off-center burns usually go unnoticed), so it is fainter.
     */
    private static void scars(GuiGraphics g, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        double sy = 0.5 / Math.tan(Math.toRadians(mc.options.fov().get()) / 2), sx = sy * h / Math.max(1, w);
        Matrix4f m = g.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        boolean any = false;
        for (RetinaScars.Scar s : scars) {
            float x = (float) ((0.5 + s.x() * sx) * w), y = (float) ((0.5 - s.y() * sy) * h), r = (float) Math.max(2, s.r() * sy * h);
            // 20° off-axis — three times fainter than at the center
            double off = Math.toDegrees(Math.atan(Math.hypot(s.x(), s.y())));
            float a = (float) (0.85 / (1 + Math.pow(off / 14, 2)));
            any |= disc(b, m, x, y, r, a, w, h);
        }
        if (any) BufferUploader.drawWithShader(b.buildOrThrow());
        else b.build();
        RenderSystem.disableBlend();
    }

    /** Dark circle of radius r with an extra half-radius fade to transparent; not drawn off screen. */
    private static boolean disc(BufferBuilder b, Matrix4f m, float x, float y, float r, float a, int w, int h) {
        float outer = r * 1.5f;
        if (x + outer < 0 || y + outer < 0 || x - outer > w || y - outer > h) return false;
        int n = 32;
        for (int i = 0; i < n; i++) {
            double u = 2 * Math.PI * i / n, v = 2 * Math.PI * (i + 1) / n;
            float c0 = (float) Math.cos(u), s0 = (float) Math.sin(u), c1 = (float) Math.cos(v), s1 = (float) Math.sin(v);
            b.addVertex(m, x, y, 0).setColor(0.05f, 0.05f, 0.05f, a);
            b.addVertex(m, x + c1 * r, y + s1 * r, 0).setColor(0.05f, 0.05f, 0.05f, a);
            b.addVertex(m, x + c0 * r, y + s0 * r, 0).setColor(0.05f, 0.05f, 0.05f, a);
            b.addVertex(m, x + c0 * r, y + s0 * r, 0).setColor(0.05f, 0.05f, 0.05f, a);
            b.addVertex(m, x + c1 * r, y + s1 * r, 0).setColor(0.05f, 0.05f, 0.05f, a);
            b.addVertex(m, x + c1 * outer, y + s1 * outer, 0).setColor(0.05f, 0.05f, 0.05f, 0);
            b.addVertex(m, x + c0 * r, y + s0 * r, 0).setColor(0.05f, 0.05f, 0.05f, a);
            b.addVertex(m, x + c1 * outer, y + s1 * outer, 0).setColor(0.05f, 0.05f, 0.05f, 0);
            b.addVertex(m, x + c0 * outer, y + s0 * outer, 0).setColor(0.05f, 0.05f, 0.05f, 0);
        }
        return true;
    }

    /** Glow quad centered at (x, y) with half-size half, in GUI pixels. */
    private static void spot(BufferBuilder b, Matrix4f m, float x, float y, float half, float r, float g, float bl, float a) {
        b.addVertex(m, x - half, y - half, 0).setUv(0, 0).setColor(r, g, bl, a);
        b.addVertex(m, x - half, y + half, 0).setUv(0, 1).setColor(r, g, bl, a);
        b.addVertex(m, x + half, y + half, 0).setUv(1, 1).setColor(r, g, bl, a);
        b.addVertex(m, x + half, y - half, 0).setUv(1, 0).setColor(r, g, bl, a);
    }
}
