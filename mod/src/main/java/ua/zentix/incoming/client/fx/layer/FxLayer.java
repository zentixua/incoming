package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.far.FarBlasts;
import ua.zentix.incoming.client.far.FarRenderer;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.fx.particle.FxPool;
import ua.zentix.incoming.client.fx.smoke.SmokeFrame;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.client.nuclear.NukePall;
import ua.zentix.incoming.client.nuclear.NukeWater;
import ua.zentix.incoming.client.render.LodDepth;
import ua.zentix.incoming.client.render.FarDraw;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Locale;

/**
 * Effects layer: smoke, dust, fire, sparks, flashes ({@link FxPool}) and all far rendering ({@link FarRenderer}, with
 * tiles of far models) — in one pass after the world ({@code AFTER_LEVEL}), as quads sorted from far to near by true
 * distance ({@link FxQuads}). The vanilla particle engine cannot do this: the layer has one queue without sorting, and
 * whichever was born later is on top (a launch smoke 10 blocks away would otherwise sit behind strike columns hundreds
 * of blocks away), and the far picture after the world would lie over the near smoke. Under Iris shaders this stage
 * runs after the shader pack passes, like the nuclear picture: its fog does not consume the far part.
 * <p>
 * Depth: a hardware test (terrain nearer closes it, writes are off — transparent), and also the distance to the world
 * behind the pixel ({@link SceneDepth}, with Distant Horizons depth): a puff melts softly entering the ground and
 * vanishes behind DH LODs. Glare and veil at the eye — by the share of its source visible in the frame ({@link
 * SourceProbes}, by the same distance to the world). Blending is one — with premultiplied alpha ({@code ONE,
 * ONE_MINUS_SRC_ALPHA}): smoke occludes and glows, sparks and halos only glow. World light — by the light map and the
 * light of fireballs ({@link FxLights}), Minecraft's fog — captured until the end of the frame (vanilla fog is turned
 * off for it). Glow of fire, fireball, flash and torch — {@link FxBloom}.
 */
public final class FxLayer {
    /** Light map at open sky and the tint correction of its sky share ({@code FxSkyFix}) — per frame. */
    private static final Vector3f SKY_MAP = new Vector3f(), SKY_FIX = new Vector3f();
    private static final FxQuads QUADS = new FxQuads();
    private static final FxLights LIGHTS = new FxLights();
    private static final SourceProbes PROBES = new SourceProbes();
    private static final FxFrame.Fog FOG = new FxFrame.Fog();
    private static final Vector4f HAZE = new Vector4f();
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f(), VIEW = new Matrix4f();
    private static final FrustumIntersection FRUSTUM = new FrustumIntersection();
    @Nullable
    private static ShaderInstance shader;
    /**
     * The frame path has run at least once with this shader: the first draw links the program and sets up driver
     * buffers — on a laptop this is 35 ms in the frame of the first distant blast.
     */
    private static boolean warmed;

    /**
     * Layer frame time on the render thread (collection, sorting, submission) per second: the sum, the longest; the
     * longest of the frames without a garbage collection pause, and the thread's CPU time in it; frames with a
     * collection; last second, ms.
     */
    private static long frameNanos, frameMax, cleanMax, cleanCpu;
    private static int frames, ticks, collected, lastQuads;
    private static double lastMean, lastMax, lastClean, lastCleanCpu;
    private static int lastCollected;
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    /**
     * Collectors that stop the JVM (in ZGC and Shenandoah — “… Pauses”; “… Cycles” run alongside the program).
     */
    private static final GarbageCollectorMXBean[] PAUSES = ManagementFactory.getGarbageCollectorMXBeans().stream()
            .filter(b -> !b.getName().contains("Cycles")).toArray(GarbageCollectorMXBean[]::new);

    private FxLayer() {}

    /** Mod bus: layer shaders and scene depth (again on resource reload). */
    public static void registerShaders(RegisterShadersEvent e) {
        try {
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("fx"), FxQuads.FORMAT), s -> {
                shader = s;
                warmed = false;
            });
        } catch (IOException ex) {
            shader = null;
            Incoming.LOG.error("Effects layer shader failed to load: no smoke, fire, mushroom cloud or far rendering", ex);
        }
        try {
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("fx_depth"), DefaultVertexFormat.POSITION), s -> SceneDepth.shader = s);
        } catch (IOException ex) {
            SceneDepth.shader = null;
            Incoming.LOG.error("Scene depth shader failed to load: smoke has no soft edges and is not hidden by Distant Horizons LODs", ex);
        }
        try {
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("fx_probe"), SourceProbes.FORMAT), s -> SourceProbes.shader = s);
        } catch (IOException ex) {
            SourceProbes.shader = null;
            Incoming.LOG.error("Source probe shader failed to load: glare of hidden distant fire is not dimmed", ex);
        }
        try {
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("fx_water"), DefaultVertexFormat.POSITION), s -> WaterDecal.shader = s);
        } catch (IOException ex) {
            WaterDecal.shader = null;
            Incoming.LOG.error("Water decal shader failed to load: no dark blast ring on water", ex);
        }
        try {
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("fx_pall"), DefaultVertexFormat.POSITION), s -> PallPass.march = s);
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("fx_pall_resolve"), DefaultVertexFormat.POSITION), s -> PallPass.resolve = s);
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("fx_pall_composite"), DefaultVertexFormat.POSITION), s -> PallPass.composite = s);
        } catch (IOException ex) {
            PallPass.march = PallPass.resolve = PallPass.composite = null;
            Incoming.LOG.error("Nuclear dust pall shaders failed to load: no dust pall", ex);
        }
        try {
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("fx_pall_foot"), DefaultVertexFormat.POSITION), s -> PallPass.footLighting = s);
        } catch (IOException ex) {
            PallPass.footLighting = null;
            Incoming.LOG.error("Stem foot lighting shader failed to load: using stem lighting at the foot", ex);
        }
        try {
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("fx_mush_shadow"), DefaultVertexFormat.POSITION), s -> PallPass.shadow = s);
        } catch (IOException ex) {
            PallPass.shadow = null;
            Incoming.LOG.error("Mushroom shadow shader failed to load: no mushroom shadow on the ground", ex);
        }
        try {
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("fx_heat"), DefaultVertexFormat.POSITION), s -> HeatPass.shader = s);
        } catch (IOException ex) {
            HeatPass.shader = null;
            Incoming.LOG.error("Heat pass shader failed to load: no light of blasts and fire on the world, no heat shimmer", ex);
        }
        try {
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("fx_heat_shadow"), DefaultVertexFormat.POSITION), s -> HeatPass.shadowShader = s);
        } catch (IOException ex) {
            HeatPass.shadowShader = null;
            Incoming.LOG.error("Heat pass shadow shader failed to load: fire light on the world without shadows", ex);
        }
        try {
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("fx_bloom_down"), DefaultVertexFormat.POSITION), s -> FxBloom.down = s);
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("fx_bloom_up"), DefaultVertexFormat.POSITION), s -> FxBloom.up = s);
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("fx_bloom_gather"), DefaultVertexFormat.POSITION), s -> FxBloom.gather = s);
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("fx_bloom"), DefaultVertexFormat.POSITION), s -> FxBloom.out = s);
        } catch (IOException ex) {
            FxBloom.down = FxBloom.up = FxBloom.gather = FxBloom.out = null;
            Incoming.LOG.error("Effects bloom shaders failed to load: fire, flashes and rocket flames without bloom", ex);
        }
        try {
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("fx_half_depth"), DefaultVertexFormat.POSITION), s -> HalfPass.depthShader = s);
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("fx_half_composite"), DefaultVertexFormat.POSITION), s -> HalfPass.composite = s);
        } catch (IOException ex) {
            HalfPass.depthShader = HalfPass.composite = null;
            Incoming.LOG.error("Half-resolution smoke shaders failed to load: smoke at full resolution", ex);
        }
        try {
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("fx_smoke_fill"), DefaultVertexFormat.POSITION), s -> SmokePass.fill = s);
        } catch (IOException ex) {
            SmokePass.fill = null;
            Incoming.LOG.error("Blast smoke grid shader failed to load: blast smoke is drawn as flat puffs", ex);
        }
    }

    /** GPU time of the whole layer: water decal, pall, probes, quads. */
    private static final GpuTimer GPU = new GpuTimer();
    /** Heat shimmer and fire light pass ({@link HeatPass}) on the GPU — inside {@link #GPU}. */
    private static final GpuTimer HEAT = new GpuTimer();

    /** Once per client tick (not while paused): the per-second frame time summary. */
    public static void tick() {
        if (++ticks >= 20) {
            lastMean = frames > 0 ? frameNanos / 1e6 / frames : 0;
            lastMax = frameMax / 1e6;
            lastClean = cleanMax / 1e6;
            lastCleanCpu = cleanCpu / 1e6;
            lastCollected = collected;
            PallPass.second();
            GPU.second();
            HEAT.second();
            FxBloom.second();
            LayerTimes.second();
            frameNanos = frameMax = cleanMax = cleanCpu = 0;
            frames = ticks = collected = 0;
        }
    }

    public static void render(RenderLevelStageEvent e) {
        // Minecraft itself calls this stage (not the section rendering that Sodium replaces); the fog is already the
        // world's fog
        if (e.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            captureFog();
            return;
        }
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        try {
            draw(e);
        } finally {
            LodDepth.endFrame();
            ua.zentix.incoming.client.render.PlumeRenderer.endFrame();
        }
    }

    /**
     * World fog as set by the frame (water, lava, Nether, blindness, nuke ash); vanilla clears it by the frame's end.
     */
    private static void captureFog() {
        FOG.start = RenderSystem.getShaderFogStart();
        FOG.end = RenderSystem.getShaderFogEnd();
        FOG.shape = RenderSystem.getShaderFogShape();
        System.arraycopy(RenderSystem.getShaderFogColor(), 0, FOG.color, 0, 4);
    }

    private static void draw(RenderLevelStageEvent e) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        ShaderInstance s = shader;
        int atlas = FxAtlas.INSTANCE.id();
        if (level == null || s == null || atlas < 0) return;
        long start = System.nanoTime(), cpu = cpuNanos(), pauses = pauses();
        FarView sky = FarRenderer.view(e, mc, level);
        // Light of flashes and ordinary-weapon fire on the world, air shimmer over fire and a fireball, and the blast
        // wave front of the nuke (HeatPass, NukeHeat): air is the frame's haze. Fire at the eye, before the frame is
        // collected: the exposure of the whole frame adapts to it (fireballs, puddles, fires, debris — this frame's;
        // projectile torches — the previous one's: their places are counted by collection, and they cannot flare up at
        // once, as a fireball does).
        HeatPass.Frame heat = HeatPass.begin(level, sky.exposure(), sky.pixel());
        double air = Sight.KOSCHMIEDER / Sight.range(level.getRainLevel(sky.partial()), level.getThunderLevel(sky.partial()));
        FarBlasts.heat(sky.camera(), sky.partial(), heat, air);
        ua.zentix.incoming.client.nuclear.NukeHeat.heat(sky.camera(), sky.partial(), heat);
        ua.zentix.incoming.client.fx.FireSmoke.heat(sky.camera(), heat, air);
        ua.zentix.incoming.client.fx.Exhaust.heat(sky.camera(), SmokeGrid.seconds(sky.partial()), heat, air);
        heat.eye(FarRenderer.flamesAtEye());
        FarView view = sky.exposed(Sight.exposure(sky.ambient(), heat.atEye()));
        heat.exposure = view.exposure();
        boolean warmup = !warmed;
        // far-model atlas with all meshes in video memory, before the frame is collected (warm-up restarts the atlas)
        if (warmup) FarRenderer.warmup();
        FRUSTUM.set(FarDraw.viewProjection(e, view.camera(), VIEW_PROJECTION));
        ua.zentix.incoming.client.far.SmokeVisibility.begin(VIEW_PROJECTION);
        QUADS.begin(view.left(), view.up());
        // glow: small glowing things — as spots in the pixels of the glow pass (FxBloom.spots); 0 — no glow
        QUADS.glowTexel(FxBloom.texel(view.pixel(), mc.getMainRenderTarget().height));
        WaterDecal.begin(level, e.getCamera());
        NukeWater.decals(view.partial());
        LIGHTS.begin();
        PROBES.begin();
        QUADS.nearestTexels(true);
        FxPool.INSTANCE.collect(new FxFrame(view, FRUSTUM, FOG), QUADS, view.partial());
        QUADS.nearestTexels(false);
        SmokeFrame.build(view.camera(), view.partial());
        FarRenderer.collect(view, QUADS, LIGHTS, PROBES);
        // flames of fires and burning debris light the smoke and particles around
        ua.zentix.incoming.client.fx.FireSmoke.lights(view, LIGHTS);
        ua.zentix.incoming.client.fx.Exhaust.lights(view, LIGHTS);
        // projectile torches of the frame — light on the world (to the eye — from the next frame)
        FarRenderer.heat(heat, air);
        heat.done();
        FarDraw.view(e, view.camera(), VIEW);
        // only where a source's light or shift reaches the screen (HeatArea); none in view — no pass
        boolean hot = HeatPass.needed(heat, VIEW, e.getProjectionMatrix());
        // occluders at the eye — for the shadow of direct light (world blocks, not frame depth): while any source
        // lights the world, on screen or not, so turning toward a fire finds its shadows ready
        HeatPass.solids(heat, level, view.camera());
        if (warmup) {
            // invisible quad: no light, no occluder — the frame goes the whole path up to the first real one, with the
            // source's probe in front of the eye (the probe is computed, the quad takes it)
            QUADS.seenBy(PROBES.add(view.forward().x() * 16, view.forward().y() * 16, view.forward().z() * 16, 1));
            QUADS.quad(16);
            for (int i = 0; i < 4; i++) QUADS.vertex(i % 2, i / 2, -16, 0, 0, 0, 0, 0, 0, 1, 1, LightTexture.FULL_BRIGHT, 0);
            QUADS.seenBy(0);
        }
        PallPass.Frame pall = NukePall.frame(level, view.camera(), view.partial(), FRUSTUM);
        // ordinary blast smoke — in the same pass: with the nuke or without it
        pall = SmokeFrame.frame(level, view.camera(), view.partial(), pall);
        lastQuads = QUADS.size();
        if (QUADS.size() == 0 && pall == null && !WaterDecal.active() && !hot) {
            PallPass.reset();
            return;
        }
        // probe (LayerTimes): time marks at pass boundaries; a drain frame — with a drain for each and with the quad
        // parts
        boolean drained = LayerTimes.begin();
        int models = FarRenderer.renderModels();
        LayerTimes.mark(LayerTimes.Pass.MODELS);
        int scene = SceneDepth.update(e.getProjectionMatrix());
        LayerTimes.mark(LayerTimes.Pass.DEPTH);
        LightTexture light = mc.gameRenderer.lightTexture();
        FarDraw.begin(e, view.camera());
        // a drain frame costs one more pass of quads and stalls: it does not run during the layer
        int timed = drained ? -1 : GPU.begin();
        try {
            // water decal — under the pall and the quads: dust, spray and smoke over water lie on it
            if (WaterDecal.active()) WaterDecal.draw(e, view, scene, FOG);
            LayerTimes.mark(LayerTimes.Pass.WATER);
            // pall — before the quads: they lie on it with their own share of pall in front of them (fx.vsh)
            boolean pallDrawn = pall != null && scene >= 0 && PallPass.draw(pall, scene, VIEW, e.getProjectionMatrix(), view.camera(),
                    FOG.haze(view.range(), HAZE));
            if (!pallDrawn) PallPass.reset();
            // clouds whose volume did not fit (pall frame not drawn) are drawn as quads from the next frame
            SmokeGrid.drawn(pall == null || pall.smoke == null || pallDrawn && SmokePass.filled());
            PallPass.Frame front = pallDrawn ? pall : PallPass.empty();
            LayerTimes.mark(LayerTimes.Pass.PALL);
            // light on the world and shimmer — on the pall frame, before the quads: the layer's smoke and fire lie over
            // it
            if (hot && scene >= 0) {
                int timedHeat = HEAT.begin();
                HeatPass.draw(heat, scene, VIEW, e.getProjectionMatrix(), FOG.haze(view.range(), HAZE), view.pixel());
                HEAT.end(timedHeat);
            }
            LayerTimes.mark(LayerTimes.Pass.HEAT);
            // probes — after the pall: its pass lays down the frame's maps and mushroom table, and the probe takes pall
            // and mushroom transmittance
            int seen = PROBES.update(scene, front, pallDrawn, VIEW, e.getProjectionMatrix(), view.pixel());
            LayerTimes.mark(LayerTimes.Pass.PROBES);
            RenderSystem.setShader(() -> s);
            RenderSystem.setShaderTexture(0, atlas);
            RenderSystem.setShaderTexture(1, scene >= 0 ? scene : atlas);
            RenderSystem.setShaderTexture(3, models > 0 ? models : atlas);
            RenderSystem.setShaderTexture(4, seen >= 0 ? seen : atlas);
            RenderSystem.setShaderTexture(6, pallDrawn ? PallPass.frame() : atlas);
            // firestorm maps: fire light from below on the layer's quads
            int fire0 = pallDrawn ? PallPass.fireMap(0) : -1, fire1 = pallDrawn ? PallPass.fireMap(1) : -1;
            RenderSystem.setShaderTexture(7, fire0 >= 0 ? fire0 : atlas);
            RenderSystem.setShaderTexture(8, fire1 >= 0 ? fire1 : atlas);
            // pall, dust wall and mushrooms in front of the quad (pall_front.glsl): table, terrain and water maps,
            // frame mushrooms
            PallPass.bindFront(s, front, pallDrawn, atlas);
            light.turnOnLightLayer();
            s.safeGetUniform("FxFogColor").set(FOG.color[0], FOG.color[1], FOG.color[2], FOG.color[3]);
            s.safeGetUniform("FxScene").set(scene >= 0 ? 1f : 0f);
            s.safeGetUniform("FxProbes").set(seen >= 0 ? 1f : 0f);
            FxAtlas.Sprite glow = FxAtlas.glow();
            s.safeGetUniform("FxGlow").set(glow.u0(), glow.v0(), glow.u1(), glow.v1());
            s.safeGetUniform("FxBalls").set(LIGHTS.balls);
            sun(s, level, view.partial());
            s.safeGetUniform("FxAmbient").set(view.skyLight());
            // tint of sky light at the puffs: channel against brightness, in linear
            float skyLuma = Math.max(view.skyLight(), 1e-3f);
            s.safeGetUniform("FxSkyTint").set((float) Math.pow(view.sky().x() / skyLuma, 2.2), (float) Math.pow(view.sky().y() / skyLuma, 2.2),
                    (float) Math.pow(view.sky().z() / skyLuma, 2.2));
            // particles and ribbons take world light from the light map: at its sky share — the substance's tint in air
            // (FarView.sky)
            Vector3f fix = FarView.fix(view.sky(), FarView.lightmap(light, SKY_MAP), SKY_FIX);
            s.safeGetUniform("FxSkyFix").set(fix.x(), fix.y(), fix.z());
            float[] six = FxAtlas.puff6Light();
            s.safeGetUniform("FxSix").set(FxAtlas.PUFF6_STEP, FxAtlas.PUFF6_TOP, FxAtlas.PUFF6_U, FxAtlas.PUFF6_V);
            s.safeGetUniform("FxSixLight").set(six[0], six[1], six[2]);
            s.safeGetUniform("FxFireball").set(FxAtlas.FIREBALL_U, FxAtlas.FIREBALL_V, FxAtlas.FIREBALL_TOP, (float) FxAtlas.FIREBALL_FRAMES);
            s.safeGetUniform("FxFireFlow").set(FxAtlas.FIREBALL_FLOW_U, FxAtlas.FIREBALL_FLOW_V, FxAtlas.FIREBALL_FLOW_TOP,
                    FxAtlas.FIREBALL_FLOW / FxAtlas.FIREBALL_SIZE);
            s.safeGetUniform("FxBallLight").set(LIGHTS.light);
            s.safeGetUniform("PallFire0").set(fire0 >= 0 ? front.fire0.params : PallPass.empty().fire0.params);
            s.safeGetUniform("PallFire1").set(fire1 >= 0 ? front.fire1.params : PallPass.empty().fire1.params);
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            RenderSystem.depthMask(false);
            RenderSystem.enableDepthTest();
            RenderSystem.disableCull();
            s.safeGetUniform("FxGlowPass").set(0f, 1f);
            // soft smoke with no detail finer than two pixels at half resolution, the rest in order at full (HalfPass)
            if (QUADS.prepare()) HalfPass.draw(QUADS, s, scene, VIEW_PROJECTION, GlStateManager.Viewport.width(), GlStateManager.Viewport.height());
            s.safeGetUniform("FxGlowPass").set(0f, 1f);
            LayerTimes.mark(LayerTimes.Pass.QUADS);
            if (drained) LayerTimes.parts(QUADS, s);
            LayerTimes.mark(LayerTimes.Pass.PROBE);
            // glow of fire, fireball, flash and torch: emission — another pass of the quad parts, halo — per frame
            // (FxBloom)
            FxBloom.draw(QUADS, s, VIEW_PROJECTION);
            LayerTimes.mark(LayerTimes.Pass.BLOOM);
            GPU.end(timed);
            LayerTimes.end();
        } finally {
            light.turnOffLightLayer();
            SmokePass.unbind();
            FarDraw.end();
        }
        long spent = System.nanoTime() - start;
        if (warmup) {
            warmed = true;
            Incoming.LOG.info("Effects layer: warm-up {} ms", String.format(Locale.ROOT, "%.1f", spent / 1e6));
            return;
        }
        frameNanos += spent;
        frameMax = Math.max(frameMax, spent);
        frames++;
        if (pauses() != pauses) {
            collected++;
        } else if (spent > cleanMax) {
            cleanMax = spent;
            cleanCpu = cpuNanos() - cpu;
        }
    }

    /**
     * Direct light on the baked puffs ({@code FxSun}): the direction to the sun by day or the moon by night in the
     * world, and the share of direct light in sky light — 0.75 on a clear day (direct against diffuse sky on a
     * horizontal patch with the sun high, ⚠ order-of-magnitude estimate; less toward the horizon), at night from the
     * moon — 0.5 (⚠ estimate), in rain — a fifth (clouds scatter).
     */
    private static void sun(ShaderInstance s, ClientLevel level, float partial) {
        float a = level.getTimeOfDay(partial) * net.minecraft.util.Mth.TWO_PI;
        float x = -net.minecraft.util.Mth.sin(a), y = net.minecraft.util.Mth.cos(a);
        float key = y >= 0 ? 0.75f : 0.5f;
        if (y < 0) {
            x = -x;
            y = -y;
        }
        key *= net.minecraft.util.Mth.clamp(y / 0.1f, 0, 1) * (1 - 0.8f * level.getRainLevel(partial));
        s.safeGetUniform("FxSun").set(x, y, 0f, key);
    }

    /**
     * Layer frame time over the last second — for the scenario and bench line; GPU time by pass and part — {@link
     * LayerTimes} (reading the line keeps the probe on).
     */
    public static String describeTiming() {
        return String.format(Locale.ROOT, "quads %d, %.3f ms (longest in the second %.3f; without GC %.3f, CPU %.3f; frames with GC %d), "
                + "GPU %.3f ms (longest %.3f, %d frames; heat pass %.3f, longest %.3f, %d frames — %s; %s), depth: %s; %s", lastQuads, lastMean, lastMax,
                lastClean, Math.max(0, lastCleanCpu), lastCollected, GPU.lastMean, GPU.lastMax, GPU.lastFrames, HEAT.lastMean, HEAT.lastMax,
                HEAT.lastFrames, HeatPass.describe(), FxBloom.describe(), SceneDepth.describe(), LayerTimes.describe() + "; " + HalfPass.describe());
    }

    /** F3: layer GPU time by pass and part (while F3 is open, the probe runs). */
    public static void debugText(net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent.DebugText e) {
        LayerTimes.debugLines(e.getRight());
    }

    /** Leaving the world or changing dimension. */
    public static void reset() {
        frameNanos = frameMax = cleanMax = cleanCpu = 0;
        frames = ticks = collected = lastCollected = lastQuads = 0;
        lastMean = lastMax = lastClean = lastCleanCpu = 0;
        LayerTimes.reset();
    }

    /** CPU time of the render thread, ns (−1 — the JVM does not support it: then 0 in the line). */
    private static long cpuNanos() {
        return THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() : -1;
    }

    /** How many times garbage collection has stopped the JVM since start. */
    private static long pauses() {
        long n = 0;
        for (GarbageCollectorMXBean b : PAUSES) n += Math.max(0, b.getCollectionCount());
        return n;
    }
}
