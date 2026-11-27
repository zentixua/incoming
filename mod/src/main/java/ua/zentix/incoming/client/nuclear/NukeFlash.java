package ua.zentix.incoming.client.nuclear;

import com.google.gson.JsonSyntaxException;
import com.mojang.blaze3d.pipeline.RenderTarget;
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
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.TickTime;
import ua.zentix.incoming.client.fx.Flash;
import ua.zentix.incoming.client.render.ScreenProjection;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.EyeInjury;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.FlashLight;
import ua.zentix.incoming.nuclear.radiation.FlashEyes;
import ua.zentix.incoming.nuclear.radiation.RetinaScars;

import java.io.IOException;
import java.util.List;

/**
 * Nuclear explosion flash on screen (DESIGN-nuke §6): a frame set for what the camera saw before the flash is overexposed by
 * its light and catches up ({@link NukeExposure#overexposure}, presentation): white before and at the second maximum, then
 * dark silhouettes emerge ever more brightly; for 15 kt at 1.4 km after half a second the frame is overexposed ~3 times, after a second
 * it is as before ({@link #overexposed}: frame gain and light scattered in the lens). Pulse light and timing follow physics;
 * previously the screen stayed white through the full pulse (~10·t_max: 1.2 s for 15 kt), then faded for another 2–4 s. Next comes a dark-orange
 * afterimage "spot" where the ball was on the retina, if the player looked at it (moves with gaze). Meanwhile the entire world is lit like lightning: night
 * briefly becomes day. Strength follows the shared admitted pulse: direct visibility or scattered light through openings.
 *
 * <p>Eyes (G&amp;D §12.83–12.88): full-field dazzle is a veil from {@link EyeInjury#dazzleSeconds}, based on the pulse at the eye
 * (ball seen — full; unseen — sky-scattered through openings; sealed — none) and dark adaptation:
 * a bright afterimage at first, turning gray by day or black at night after seconds, fading by the deadline; shorter
 * dazzle means a weaker veil. Retinal burns are permanent dark spots where the ball was on the retina (from server,
 * {@link FlashEyes}); they move with gaze. A creative player has eyes too: flash blindness is perception, not injury, so
 * creative gets the dazzle (DESIGN fireball-light §4.2; it ended in 0.6 s there, Artem 27.11: "the blindness ends abruptly");
 * retinal burns stay survival-only (the server injures no creative player). A spectator, and the Flashback viewer, is a
 * camera: white screen and afterimage only. A sleeping player has closed eyes. Death clears dazzle.
 */
public final class NukeFlash {
    @Nullable
    private static ClientNuclear.Active current;
    /** Flash strength 0..1 for this player. */
    private static float strength, afterStrength;
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
    private static long ticks;
    /**
     * Frame overexposure at which lens-scattered light whitens the entire frame (white share = overexposure / VEIL_X: the lens
     * scatters about a percent of frame light) (est.), and maximum on-screen frame gain (above this the frame
     * is already white). Presentation.
     */
    private static final double VEIL_X = 100, GAIN_MAX = 16;
    /** Overexposure below which the frame stays unchanged: gain is smaller than a screen step. */
    private static final double SETTLED = 1.004;
    /**
     * Flash blindness over the picture at full bleach (presentation, bounded so the world always stays visible; DESIGN
     * fireball-light §4.2): colour lost toward grey by day, the bleached eye's glow lifting the blacks (warm white, fading over
     * {@link #AFTERGLOW_S}, then {@link #DAY_LIFT} by day). At night only the glow and the afterimage: the orange-lit night
     * keeps its colour (a desaturation of 0.15 and a dimming of 0.25 turned it brownish grey in the sandbox takes). It replaced
     * a uniform fill at 0.9 opacity, grey by day and black at night, that hid the whole picture.
     */
    static final float GREY_MAX = 0.15f, LIFT_MAX = 0.6f, DAY_LIFT = 0.04f, AFTERGLOW_S = 0.7f;
    /**
     * The near-white first phase lasts this share of the dazzle, at most {@link #HOLD_MAX} s: about 0.3 s for 15 kt at 2 km
     * facing the ball by day, a tenth of a second at 15 km. Presentation of the bleached eye's own glow; its long tail is the
     * afterimage spot, which leaves the rest of the view to the light the ball casts. A longer, stronger full-view glow (2 s
     * hold, desaturation 0.75), and still a 0.6 s hold with desaturation 0.35 and a night dimming of 0.4, turned the frames of
     * the second sun into a pale card by day and killed the orange-lit night (sandbox takes, 27.11).
     */
    static final float HOLD_SHARE = 0.005f, HOLD_MAX = 0.3f;
    /** Gaze factor of the bleached eye's glow: the ball's image on the retina (facing it) against scattered light alone. */
    static final float TURNED_AWAY = 0.35f;
    private static final net.minecraft.resources.ResourceLocation EYE_CHAIN = Incoming.id("shaders/post/flash_eye.json");
    @Nullable
    private static PostChain eyeChain;
    private static boolean eyeBroken;
    private static int eyeWidth, eyeHeight;
    /** Field dazzle: start (local client ticks, pulse end), duration in s, strength 0..1, degree of dark adaptation. */
    private static double veilStart, veilSeconds;
    private static float veilStrength, veilDark, veilGaze = 1;
    /** Retinal burns — spots in the visual field ({@link RetinaScars.Scar}). */
    private static List<RetinaScars.Scar> scars = List.of();

    private NukeFlash() {}

    static void detonation(ClientNuclear.Active a) {
        Detonation d = a.d;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        Vec3 eye = p.getEyePosition();
        FlashLight.Eye light = NukeLight.sample(mc.level, a, eye, 0);
        double q = light.fluence();
        Vec3 look = p.getViewVector(1);
        double angle = Math.toDegrees(Math.acos(Mth.clamp(look.dot(d.burst().subtract(eye).normalize()), -1, 1)));
        boolean inView = !p.isSleeping() && light.seen() && angle < 60;
        if (eyes(p) && !p.isSleeping()) dazzle(mc.level, d, eye, q, gaze(angle, light.seen()));
        // spot larger than the visible ball: the eye "smears" glare
        float[] spot = inView && q >= 0.05 ? retina(mc, p, d.burst(), Math.max(d.fireballRadius(), 30) * 2.2) : null;
        if (spot != null) {
            // 5–30 s from the light pulse (cal/cm²)
            afterStrength = (float) (light.strength() * IncomingConfig.CLIENT.flash.get());
            if (Flash.calm()) afterStrength = Math.min(afterStrength, Flash.CALM);
            afterTotal = afterLeft = (int) (20 * Mth.clamp(5 + q * 2.5, 5, 30));
            afterX = spot[0];
            afterY = spot[1];
            afterSize = spot[2];
        }
    }

    /** Shared camera sample, updated before sky/fog and all light consumers; entering cover stops new glare. */
    static void frame() {
        Minecraft mc = Minecraft.getInstance();
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(false);
        current = null;
        strength = 0;
        peak = 1;
        double best = 0;
        for (ClientNuclear.Active a : ClientNuclear.detonations()) {
            if (!a.live) continue; // sync/seek reconstructs illumination without replaying a one-shot flash
            FlashLight.Eye light = NukeLight.camera(a);
            float admitted = (float) (light.strength() * IncomingConfig.CLIENT.flash.get());
            if (Flash.calm()) admitted = Math.min(admitted, Flash.CALM);
            double candidatePeak = light.peak(NukeLight.bodyExposure());
            double after = (a.seconds(partial) - FireballModel.secondMaximumSeconds(a.d.yieldKt())) * a.d.scale();
            double x = NukeExposure.overexposure(candidatePeak, after);
            double score = admitted * (x - 1);
            if (x >= SETTLED && score > best) {
                best = score;
                current = a;
                strength = admitted;
                peak = candidatePeak;
            }
        }
        // Resample even a flash first received in shelter: leaving cover during its pulse can expose the camera.
        if (mc.level != null) lightUpWorld(mc.level, cover(partial));
    }

    /** Field dazzle from pulse {@code q} cal/cm² at the eye; keep existing dazzle that ends later. */
    private static void dazzle(ClientLevel level, Detonation d, Vec3 eye, double q, float gaze) {
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
        veilGaze = gaze;
    }

    /** Gaze factor at {@code angle}° between the view and the ball: 1 within 30°, {@link #TURNED_AWAY} from 90° on (or unseen). */
    static float gaze(double angle, boolean seen) {
        if (!seen) return TURNED_AWAY;
        return (float) Mth.clamp(1 - (angle - 30) / 60 * (1 - TURNED_AWAY), TURNED_AWAY, 1);
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
     * Sky flash timer renewed each frame, in ticks. Vanilla mixes the sky toward white by min(1, timer − partial tick)
     * ({@code ClientLevel.getSkyColor}): a timer of one tick runs that share from 1 to 0 within every tick, a 20 Hz
     * sawtooth over the sky and fog (replay capture, 500 kt at 3.8 km at night: frame luminance ±0.055 on 0.33, its
     * frame-to-frame change correlated 0.97 with the partial tick, for the 1.3 s the flash stayed overexposed). The
     * renewal runs in {@code ComputeFogColor}, after the fog color has already read the sky, so the first frame after a
     * tick sees the timer one lower: three ticks keep the share whole through every partial tick even then; after the
     * flash the timer runs out by itself.
     */
    static final int SKY_FLASH_TICKS = 3;
    /**
     * Share of the frame the flash's white fill must cover for the vanilla sky flash to run. Vanilla's sky flash is a switch,
     * not a level: any timer turns the sky white and the world's light map to full day ({@code LightTexture}). Renewed while
     * the screen was any whiter than a screen step, it ended ~1.85 s after a 15 kt burst at night, and the whole picture
     * jumped within three frames from a pink day-lit scene to the orange ball-lit night (sky 0.13 → 0.02, ground 0.08 →
     * 0.04 linear; Artem 27.11: "as if the colour grade is cut"). Under a full white fill the switch is hidden; past it the
     * ball's own light on the world (the pall composite) carries the scene continuously.
     */
    static final double SKY_FLASH_COVER = 0.98;

    /** Vanilla sky flash follows this frame's admitted flash, rather than an independent yield-duration clock. */
    private static void lightUpWorld(ClientLevel level, double cover) {
        if (IncomingConfig.CLIENT.flash.get() <= 0 || Flash.calm()) return;
        int timer = skyFlashTimer(cover);
        if (timer > 0) level.setSkyFlashTime(Math.max(level.getSkyFlashTime(), timer));
    }

    /** Ticks to renew the vanilla sky flash while the white fill covers {@code cover} of the frame: only near full. */
    static int skyFlashTimer(double cover) {
        return cover >= SKY_FLASH_COVER ? SKY_FLASH_TICKS : 0;
    }

    /** Share of the frame the flash's white fill covers now ({@link #overexposed}: strength × X / {@link #VEIL_X}, at most 1). */
    static float cover(float partialTick) {
        ClientNuclear.Active a = current;
        if (a == null || strength <= 0) return 0;
        return coverOf(strength, overexposure(a, partialTick));
    }

    static float coverOf(float strength, double x) {
        return (float) (strength * Math.min(1, x / VEIL_X));
    }

    static void tick() {
        ticks++;
        if (afterLeft > 0) afterLeft--;
    }

    /** Eyes rather than a camera: any player but a spectator (or a replay viewer, who is one); creative included. */
    static boolean eyes(@Nullable LocalPlayer p) {
        return p != null && !p.isSpectator();
    }

    /** Respawn after death: the new body does not carry the dead eyes' dazzle or afterimage (server sends retinal spots). */
    public static void respawned() {
        afterLeft = 0;
        veilSeconds = 0;
    }

    static void reset() {
        current = null;
        strength = 0;
        peak = 1;
        afterStrength = 0;
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
        overexposed(g, partial, w, h);
        LocalPlayer viewer = Minecraft.getInstance().player;
        if (eyes(viewer)) {
            // permanent retinal burns are an injury: survival only, as the server gives them (a burnt survivor in creative
            // sees none)
            if (!scars.isEmpty() && !viewer.isCreative()) scars(g, w, h);
            veil(g, partial, w, h);
        }
        if (afterLeft > 0) afterimage(g, partial, w, h);
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
     * Afterimage colour t s after the flash (presentation of the afterimage's "flight of colours", DESIGN fireball-light §4.2):
     * a positive white-yellow image first, then orange, then a violet one (Feynman at Trinity: "this purple splotch").
     */
    private static final float[][] AFTER_KEYS = {{0, 1f, 0.92f, 0.7f}, {1.5f, 0.55f, 0.24f, 0.08f}, {6, 0.3f, 0.1f, 0.42f}};

    static float[] afterColour(float t) {
        float[][] keys = AFTER_KEYS;
        if (t <= keys[0][0]) return new float[]{keys[0][1], keys[0][2], keys[0][3]};
        for (int i = 1; i < keys.length; i++) {
            if (t <= keys[i][0]) {
                float u = (t - keys[i - 1][0]) / (keys[i][0] - keys[i - 1][0]);
                return new float[]{Mth.lerp(u, keys[i - 1][1], keys[i][1]), Mth.lerp(u, keys[i - 1][2], keys[i][2]), Mth.lerp(u, keys[i - 1][3], keys[i][3])};
            }
        }
        float[] k = keys[keys.length - 1];
        return new float[]{k[1], k[2], k[3]};
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
        float a = Mth.clamp(life * 1.3f, 0, 0.6f) * Math.min(1, afterStrength * 1.5f);
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
        float[] c = afterColour((afterTotal - afterLeft + partial) / 20f);
        spot(b, m, x, y, px, c[0], c[1], c[2], a);
        spot(b, m, x, y, px / 3, Math.min(1, c[0] * 1.1f), Math.min(1, c[1] * 1.6f), Math.min(1, c[2] * 2.5f), a * 0.5f);
        BufferUploader.drawWithShader(b.buildOrThrow());
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    /**
     * Flash blindness (DESIGN fireball-light §4.2): from pulse maximum (before it — white frame, {@link #overexposed}), a
     * post pass over the finished picture whose strength fades by its deadline as (1 − t/T)² ({@link #look}). It never lowers
     * the frame's exposure: the world stays visible through it.
     */
    private static void veil(GuiGraphics g, float partial, int w, int h) {
        if (veilSeconds <= 0) return;
        double t = (TickTime.at(ticks, partial) - veilStart) / 20;
        if (t < 0 || t >= veilSeconds) return;
        float[] look = look(t, veilSeconds, veilStrength, veilDark, veilGaze);
        if (look[0] + look[1] <= 0.004f) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        PostChain post = eyeChain(mc, main);
        if (post == null) return;
        // what the GUI drew below this layer (the flash, camera overlays) is part of what the eye sees
        g.flush();
        post.setUniform("Grey", look[0]);
        post.setUniform("Lift", look[1]);
        post.process(0);
        main.bindWrite(false);
    }

    /**
     * Flash blindness at t s of a T s dazzle of strength k (0..1), dark adaptation dark (0 day .. 1 night) and gaze factor
     * ({@link #gaze}): {grey, lift}, each a bounded share of the bleach (1 − t/T)²·k, so the world stays visible. The lift is
     * the bleached eye's own glow: near white for the hold ({@link #HOLD_SHARE} of T, at most {@link #HOLD_MAX} s; full facing
     * the ball), then fading over {@link #AFTERGLOW_S} to {@link #DAY_LIFT} by day and to nothing at night; the grey is by day.
     */
    static float[] look(double t, double seconds, float k, float dark, float gaze) {
        if (seconds <= 0 || t < 0 || t >= seconds) return new float[2];
        double life = 1 - t / seconds, bleach = life * life * k;
        double hold = Math.min(HOLD_MAX, HOLD_SHARE * seconds);
        double glow = t <= hold ? 1 : Math.exp(-(t - hold) / AFTERGLOW_S);
        double lift = LIFT_MAX * gaze * glow + DAY_LIFT * (1 - glow) * (1 - dark);
        return new float[]{(float) (GREY_MAX * (1 - dark) * bleach), (float) (lift * bleach)};
    }

    /** Seconds the view stays near white (lift at least {@code white}) after the pulse maximum, for the report and tests. */
    static double whiteSeconds(double seconds, float k, float dark, float gaze, float white) {
        double t = 0;
        while (t < seconds && look(t, seconds, k, dark, gaze)[1] >= white) t += 0.05;
        return t;
    }

    @Nullable
    private static PostChain eyeChain(Minecraft mc, RenderTarget main) {
        if (eyeChain == null && !eyeBroken) {
            try {
                eyeChain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), main, EYE_CHAIN);
                eyeWidth = eyeHeight = -1;
            } catch (IOException | JsonSyntaxException e) {
                eyeBroken = true;
                org.slf4j.LoggerFactory.getLogger(NukeFlash.class).warn("Flash blindness on screen is off: {} did not load", EYE_CHAIN, e);
            }
        }
        if (eyeChain != null && (main.width != eyeWidth || main.height != eyeHeight)) {
            eyeWidth = main.width;
            eyeHeight = main.height;
            eyeChain.resize(eyeWidth, eyeHeight);
        }
        return eyeChain;
    }

    /** Resource reload: the flash-blindness pass is built again from the new resources. */
    public static final ResourceManagerReloadListener RELOAD = (ResourceManager manager) -> {
        if (eyeChain != null) eyeChain.close();
        eyeChain = null;
        eyeBroken = false;
    };

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
