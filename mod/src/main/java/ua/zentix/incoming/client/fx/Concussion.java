package ua.zentix.incoming.client.fx;

import com.google.gson.JsonSyntaxException;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.model.BodySway;
import ua.zentix.incoming.nuclear.model.ConcussionDose;
import ua.zentix.incoming.nuclear.model.ShockAtBody;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Screen concussion — <b>presentation</b> (visual principle: the image is presentation, facts stay unchanged): a deafening wave
 * muddles the image for a second or two — blur, lost colour, dark edges. It starts, grows and fades by the model
 * ({@link ConcussionDose}: dose by the front at the head, hearing threshold shift and head motion; below threshold — 0; time
 * response from front arrival and its duration); the appearance — blur, greyness, edges — is presentation here.
 * <p>
 * Blur follows the response cubed, so it leaves earlier (by one second) than greyness and dark edges; greyness preserves bright
 * colour (fire, sky): the surroundings dim, rather than the blast itself. Salvo hits add as fractions:
 * 1 − Π(1 − response), capped at full strength. Strength — {@code screen_effects} setting × vanilla Distortion Effects
 * (Distortion Effects).
 * <p>
 * Drawn by a GUI layer below the HUD ({@link #render}) — a vanilla post-processing pass ({@link PostChain}, like menu
 * background blur) with one custom program {@code incoming:concussion}: two Gaussian passes along the axes, the second with colour loss
 * and dark edges. Like vanilla overlays (pumpkin, portal), hidden with F1.
 */
public final class Concussion {
    private static final Logger LOG = LogUtils.getLogger();
    /** Post-processing pass. */
    private static final net.minecraft.resources.ResourceLocation CHAIN = Incoming.id("shaders/post/concussion.json");
    /** Full-level blur — Gaussian σ, pixels at frame height 1080 (scales with frame height). Presentation. */
    static final double BLUR_PX = 5;
    /** Full-level colour loss: fraction toward grey of the same luminance (less for bright colours, shader). Presentation. */
    static final double GREY = 0.7;
    /** Full-level dark edges: fraction of light lost at frame corners. Presentation. */
    static final double EDGE = 0.55;
    /** Below this level the screen stays unchanged: blur below 0.01 pixel, greyness and edges below a display step. */
    static final double FLOOR = 2e-3;
    /** Beyond this simultaneous hit count the oldest is removed (level is a sum of fractions; its cap stays unchanged). */
    private static final int HITS_MAX = 16;

    /** One hit: from {@code from} (fractional client ticks) — front arrival at the body; dose 0..1, response duration, s. */
    private record Hit(double from, double dose, double seconds) {}

    private static final List<Hit> HITS = new ArrayList<>();
    private static long ticks;
    /** Last hit (client scenario line). */
    private static String last = "-";
    @Nullable
    private static PostChain chain;
    /** Pass creation failed (missing shader): do not retry every frame until a resource reload. */
    private static boolean broken;
    private static int width, height;

    private Concussion() {}

    /**
     * Front reached the body: concussion dose {@code dose} (0..1, {@link ConcussionDose#dose}), {@code late} — ticks
     * since it passed the body (0..1). Returns response duration, s (0 — no dose).
     */
    public static double hit(double dose, double late) {
        double d = Mth.clamp(dose, 0, 1);
        if (!(d > 0)) return 0;
        double seconds = ConcussionDose.durationSeconds(d);
        if (!(seconds > 0)) return 0;
        if (HITS.size() >= HITS_MAX) HITS.remove(0);
        HITS.add(new Hit(ticks - Mth.clamp(late, 0, 1), d, seconds));
        last = String.format(Locale.ROOT, "dose %.3f for %.2f s", d, seconds);
        return seconds;
    }

    /**
     * Conventional blast front at the body ({@link BlastHit}): dose from head front {@code shock}, hearing threshold shift
     * {@code shiftDb} and head motion {@code head} (body and neck, {@link BodySway}; null — no body sway), {@code late} —
     * ticks since it passed the body. Returns the dose.
     */
    static double front(ShockAtBody shock, double shiftDb, @Nullable BodySway.Motion head, double late) {
        double dose = ConcussionDose.dose(shock, shiftDb, head == null ? 0 : headPeak(head));
        hit(dose, late);
        return dose;
    }

    /** Maximum head rotation, rad: body lean and neck-mounted head motion added at the same samples. */
    static double headPeak(BodySway.Motion m) {
        double peak = 0;
        double[] body = m.body(), neck = m.neck();
        for (int i = 0; i < body.length; i++) peak = Math.max(peak, Math.abs(body[i] + (neck != null && i < neck.length ? neck[i] : 0)));
        return peak;
    }

    /** Concussion level 0..1 at time {@code t} ticks: combined response of all hits, 1 − Π(1 − response). */
    static double level(double t) {
        double clear = 1;
        for (Hit h : HITS) clear *= 1 - Mth.clamp(ConcussionDose.strength(h.dose, (t - h.from) / 20), 0, 1);
        return 1 - clear;
    }

    /**
     * Screen effects at {@code level} (already multiplied by settings) and frame height {@code frameHeight} px:
     * {blur σ, px; grey fraction; corner darkness fraction}.
     */
    static double[] look(double level, int frameHeight) {
        double l = Mth.clamp(level, 0, 1);
        return new double[]{BLUR_PX * l * l * l * frameHeight / 1080.0, GREY * l, EDGE * l};
    }

    /** Settings multiplier: {@code screen_effects} × vanilla Distortion Effects. */
    static double strength(double screenEffects, double distortion) {
        return Mth.clamp(screenEffects, 0, 1) * Mth.clamp(distortion, 0, 1);
    }

    /** Last hit and current level (client scenario line). */
    public static String describe() {
        return String.format(Locale.ROOT, "%s level %.3f", last, level(ticks));
    }

    public static void tick() {
        ticks++;
        HITS.removeIf(h -> (ticks - h.from) / 20 >= h.seconds);
    }

    public static void reset() {
        HITS.clear();
        ticks = 0;
        last = "-";
    }

    /** GUI layer below the HUD. */
    public static void render(GuiGraphics g, DeltaTracker delta) {
        if (HITS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        double k = strength(IncomingConfig.CLIENT.screenEffects.get(), mc.options.screenEffectScale().get());
        double level = k * level(ticks + delta.getGameTimeDeltaPartialTick(false));
        if (level < FLOOR) return;
        RenderTarget main = mc.getMainRenderTarget();
        PostChain post = chain(mc, main);
        if (post == null) return;
        double[] look = look(level, main.height);
        // flush everything the GUI already drew below this layer (camera overlays) into the frame before the pass: blur it too
        g.flush();
        post.setUniform("Radius", (float) look[0]);
        post.setUniform("Grey", (float) look[1]);
        post.setUniform("Edge", (float) look[2]);
        post.process(delta.getGameTimeDeltaTicks());
        main.bindWrite(false);
    }

    /** Post-processing pass for frame {@code main} (created on the first hit, grows with the frame); null — creation failed. */
    @Nullable
    private static PostChain chain(Minecraft mc, RenderTarget main) {
        if (chain == null && !broken) {
            try {
                chain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), main, CHAIN);
                width = height = -1;
            } catch (IOException | JsonSyntaxException e) {
                broken = true;
                LOG.warn("Concussion on screen is off: {} did not load", CHAIN, e);
            }
        }
        if (chain != null && (main.width != width || main.height != height)) {
            width = main.width;
            height = main.height;
            chain.resize(width, height);
        }
        return chain;
    }

    /** Resource reload (F3+T, pack change): rebuild the pass from new shaders. */
    public static final ResourceManagerReloadListener RELOAD = (ResourceManager manager) -> {
        if (chain != null) chain.close();
        chain = null;
        broken = false;
    };
}
