package ua.zentix.incoming.scenario;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.nuclear.ClientNuclear;
import ua.zentix.incoming.net.ClientHooks;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.GroundMaterial;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Real Vista TV fixtures in a disposable flat world; reflection keeps Vista optional, even in devtest. */
final class ViewFxScenario {
    private final boolean vista = ModList.get().isLoaded("vista");
    private final int count = Math.clamp(Integer.getInteger("incoming.viewfx.feeds", 1), 1, 4);
    private final List<Object> feeds = new ArrayList<>();
    private final Path out = Minecraft.getInstance().gameDirectory.toPath().resolve("viewfx");
    private Camera primaryCamera;
    private RenderTarget primaryTarget;
    private int tick, secondaryFrames, primaryFrames, failures;
    private int draw, read;
    private final int[] viewport = new int[4];
    private long started, secondaryNanos;
    private List<Object> outer;
    private Vec3 feedEye;
    private float feedYaw, feedPitch;

    ViewFxScenario() {
        new Sandbox();
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::fog);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::before);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::after);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::frame);
        try { Files.createDirectories(out); } catch (Exception ex) { throw new IllegalStateException(ex); }
        Incoming.LOG.info("VIEWFX scenario vista={} feeds={}", vista, count);
    }

    private void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.isPaused()) return;
        primaryCamera = mc.gameRenderer.getMainCamera();
        primaryTarget = mc.getMainRenderTarget();
        tick++;
        if (tick == 80) {
            commands("forceload add -64 -16 32 16", "tp @a 0.5 65 1.5 180 0");
            if (vista) for (int i = 0; i < count; i++) {
                int x = i * 3;
                commands("setblock " + x + " 65 -3 minecraft:redstone_block",
                        "setblock " + x + " 66 -3 vista:television[facing=south,powered=direct]",
                        "setblock " + (10 + x) + " 65 -3 minecraft:stone",
                        "setblock " + (10 + x) + " 66 -3 vista:viewfinder{UUID:[I;1,2,3," + (4 + i) + "],yaw:90f,pitch:-15f}");
            }
            if (count == 4) commands("tp @a 4.5 65 6.5 180 0");
        }
        if (vista && tick > 100 && feeds.size() < count) connect();
        if (tick == 120) unsupportedTargets();
        if (tick == 180) nuke(false);
        if (tick == 360) {
            ClientNuclear.reset();
            nuke(true);
            ClientHooks.get().blast(new S2C.Blast(S2C.Blast.MISSILE, new Vec3(-40, 65, -8),
                    GroundMaterial.DIRT.ordinal(), 65, 1234, Optional.empty(), WeaponType.MISSILE.id(),
                    S2C.Blast.DOWN, false, Optional.empty()));
        }
        if (tick == 520) {
            // Match the real feed eye/angles, including Vista's viewfinder position offset.
            if (feedEye == null) { feedEye = new Vec3(10.5, 66.5, -2.5); feedYaw = 90; feedPitch = -15; }
            commands(String.format(Locale.ROOT, "tp @a %.8f %.8f %.8f %.8f %.8f", feedEye.x,
                    feedEye.y - mc.player.getEyeHeight(), feedEye.z, feedYaw, feedPitch));
        }
        if (tick == 500) metrics();
        if (tick == 640) {
            if (vista && (feeds.size() != count || secondaryFrames == 0)) failures++;
            String result = String.format(Locale.ROOT,
                    "vista=%s connected=%d requested=%d secondaryFrames=%d primaryFrames=%d failures=%d secondaryCpuMs=%.4f%n",
                    vista, feeds.size(), count, secondaryFrames, primaryFrames, failures,
                    secondaryFrames == 0 ? 0 : secondaryNanos / 1e6 / secondaryFrames);
            try { Files.writeString(out.resolve("result.txt"), result); }
            catch (Exception ex) { throw new IllegalStateException(ex); }
            Incoming.LOG.info("VIEWFX RESULT {}", result.strip());
            feeds.clear();
            mc.stop();
        }
    }

    private void unsupportedTargets() {
        try { Class.forName("ua.zentix.incoming.client.fx.layer.FxViews"); }
        catch (ClassNotFoundException baseline) { return; }
        int oldDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int oldRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int oldRenderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        int fbo = GL30.glGenFramebuffers(), depth = GL30.glGenRenderbuffers();
        Camera camera = new Camera();
        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            expectSkipped(camera, "default framebuffer");
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depth);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH_COMPONENT24, 16, 16);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, depth);
            expectSkipped(camera, "depth renderbuffer");
            GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER, 4, GL30.GL_DEPTH_COMPONENT24, 16, 16);
            expectSkipped(camera, "multisample depth");
        } finally {
            viewCall("outerFrame");
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, oldRenderbuffer);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, oldDraw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldRead);
            GL30.glDeleteFramebuffers(fbo);
            GL30.glDeleteRenderbuffers(depth);
        }
    }

    /** Also run the ordinary-view fixture against a pre-fix jar for the screenshot comparison. */
    private static Object viewCall(String name, Object... args) {
        try {
            Class<?> type = Class.forName("ua.zentix.incoming.client.fx.layer.FxViews");
            return type.getMethod(name, args.length == 0 ? new Class<?>[0] : new Class<?>[]{Camera.class}).invoke(null, args);
        } catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
    }

    private void expectSkipped(Camera camera, String description) {
        List<Object> before = snapshot();
        viewCall("begin", camera);
        boolean skipped = (boolean) viewCall("skipped");
        viewCall("outerFrame");
        if (!skipped || !before.equals(snapshot())) failures++;
        Incoming.LOG.info("VIEWFX unsupported {} skipped={} primaryUnchanged={}", description, skipped, before.equals(snapshot()));
    }

    private void nuke(boolean mature) {
        Minecraft mc = Minecraft.getInstance();
        Detonation d = new Detonation(9000, new Vec3(mature ? -5990 : -1490, 65, -3), 65, 15, true,
                mc.level.getGameTime() - (mature ? 2400 : 0), 0, 5, 20_000, 1234, 1, false,
                0.25f, mc.level.getDayTime(), false);
        ClientNuclear.detonation(new S2C.NukeDetonation(d, 0, 9000), !mature);
    }

    private void connect() {
        Minecraft mc = Minecraft.getInstance();
        int i = feeds.size(), x = i * 3;
        Object vf = mc.level.getBlockEntity(new BlockPos(10 + x, 66, -3));
        Object tv = mc.level.getBlockEntity(new BlockPos(x, 66, -3));
        if (vf == null || tv == null) return;
        try {
            Class<?> sourceClass = Class.forName("net.mehvahdjukaar.vista.client.video_source.LiveFeedVideoSource");
            Object source = sourceClass.getConstructor(vf.getClass()).newInstance(vf);
            Field field = tv.getClass().getDeclaredField("videoSource");
            field.setAccessible(true);
            field.set(tv, source);
            tv.getClass().getMethod("setHasEnergy", boolean.class).invoke(tv, true);
            Class<?> size = Class.forName("net.mehvahdjukaar.moonlight.api.util.math.Vec2i");
            Class<?> manager = Class.forName("net.mehvahdjukaar.vista.client.textures.perspective.LiveFeedTexturesManager");
            Object id = vf.getClass().getMethod("getBroadcastUUID").invoke(vf);
            feeds.add(manager.getMethod("createHandle", java.util.UUID.class, size)
                    .invoke(null, id, size.getConstructor(int.class, int.class).newInstance(12, 12)));
        } catch (ReflectiveOperationException ex) { throw new IllegalStateException("Vista fixture API changed", ex); }
    }

    private void frame(RenderFrameEvent.Post event) {
        if (tick >= 640 || Minecraft.getInstance().level == null) return;
        for (Object feed : feeds) try {
            Method get = Arrays.stream(feed.getClass().getMethods()).filter(m -> m.getName().equals("getTexture")
                    && m.getParameterCount() == 3).findFirst().orElseThrow();
            get.invoke(feed, null, true, false);
        } catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
        if (primaryTarget == null) return;
        if (tick == 260) shot(primaryTarget, "tv-fireball");
        if (tick == 480) shot(primaryTarget, "tv-mushroom-smoke");
        if (tick == 580) shot(primaryTarget, "direct-mushroom-smoke");
    }

    private boolean secondary(RenderLevelStageEvent event) { return event.getCamera() != primaryCamera; }

    private void fog(ViewportEvent.ComputeFogColor event) {
        if (event.getCamera() == primaryCamera) return;
        // NukeLight's HIGHEST listener is registered first: its scope already holds the suspended outer state.
        String layer = "ua.zentix.incoming.client.fx.layer.";
        Object scope = field(layer + "FxViews", null, "scope");
        if (scope == null) return;
        Object entry = field(layer + "FxViews$Scope", scope, "entry");
        if (entry == null) return;
        Object saved = field(layer + "SecondaryViews$Entry", entry, "value");
        Object light = field(layer + "FxViews$Saved", saved, "light");
        Object depth = field(layer + "FxViews$Saved", saved, "depth");
        Object pall = field(layer + "FxViews$Saved", saved, "pall");
        Object fx = field(layer + "FxViews$Saved", saved, "layer");
        String nuke = "ua.zentix.incoming.client.nuclear.NukeLight$ViewState";
        outer = List.of(field(nuke, light, "prepared"), field(nuke, light, "exposure"),
                field(nuke, light, "bodyExposure"), field(layer + "SceneDepth$ViewState", depth, "current"),
                new org.joml.Matrix4f((org.joml.Matrix4f) field(layer + "PallPass$ViewState", pall, "PREVIOUS")),
                field(layer + "FxLayer$ViewState", fx, "FOG"),
                new org.joml.Matrix4f((org.joml.Matrix4f) field("ua.zentix.incoming.client.render.ScreenProjection", null, "VIEW_PROJ")));
    }

    private static Object field(String type, Object object, String name) {
        try {
            Field f = Class.forName(type).getDeclaredField(name);
            f.setAccessible(true);
            return f.get(object);
        } catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
    }

    private static List<Object> snapshot() {
        String nuclear = "ua.zentix.incoming.client.nuclear.NukeLight";
        String layer = "ua.zentix.incoming.client.fx.layer.";
        return List.of(field(nuclear, null, "prepared"), field(nuclear, null, "exposure"),
                field(nuclear, null, "bodyExposure"), field(layer + "SceneDepth", null, "current"),
                new org.joml.Matrix4f((org.joml.Matrix4f) field(layer + "PallPass", null, "PREVIOUS")),
                field(layer + "FxLayer", null, "FOG"),
                new org.joml.Matrix4f((org.joml.Matrix4f) field("ua.zentix.incoming.client.render.ScreenProjection", null, "VIEW_PROJ")));
    }

    private void metrics() {
        try { Class.forName("ua.zentix.incoming.client.fx.layer.FxViews"); }
        catch (ClassNotFoundException baseline) { return; }
        String layer = "ua.zentix.incoming.client.fx.layer.";
        Object cache = field(layer + "FxViews", null, "views");
        if (cache == null) return;
        Incoming.LOG.info("VIEWFX metrics tick={} primaryFrames={} secondaryFrames={}", tick, primaryFrames, secondaryFrames);
        List<?> entries = (List<?>) field(layer + "SecondaryViews", cache, "entries");
        for (Object entry : entries) {
            Object saved = field(layer + "SecondaryViews$Entry", entry, "value");
            Object timing = field(layer + "FxViews$Saved", saved, "timing");
            double[] mean = ((double[]) field(layer + "LayerTimes$ViewState", timing, "SUM")).clone();
            int samples = (int) field(layer + "LayerTimes$ViewState", timing, "frames");
            if (samples > 0) for (int i = 0; i < mean.length; i++) mean[i] /= samples;
            Incoming.LOG.info("VIEWFX GPU target={} meanMs={} samples={}",
                    field(layer + "SecondaryViews$Entry", entry, "key"), Arrays.toString(mean),
                    samples);
        }
    }

    private void before(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        if (!secondary(event)) { primaryFrames++; return; }
        secondaryFrames++;
        started = System.nanoTime();
        draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
    }

    private void after(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || !secondary(event)) return;
        secondaryNanos += System.nanoTime() - started;
        if (outer != null && !outer.equals(snapshot())) {
            failures++;
            if (failures < 6) Incoming.LOG.error("VIEWFX primary history/light/fog changed: before={} after={}", outer, snapshot());
        }
        outer = null;
        int[] after = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, after);
        if (draw != GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
                || read != GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) || !Arrays.equals(viewport, after)) {
            failures++;
            Incoming.LOG.error("VIEWFX target restoration failed");
        }
        if (tick >= 260 && tick < 280) shot(Minecraft.getInstance().getMainRenderTarget(), "feed-fireball");
        if (tick >= 480 && tick < 500 && !Files.exists(out.resolve("feed-mushroom-smoke.png"))) {
            feedEye = event.getCamera().getPosition();
            feedYaw = event.getCamera().getYRot();
            feedPitch = event.getCamera().getXRot();
            shot(Minecraft.getInstance().getMainRenderTarget(), "feed-mushroom-smoke");
        }
    }

    private static void commands(String... commands) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.execute(() -> {
            var source = server.createCommandSourceStack().withPermission(4).withSuppressedOutput();
            for (String command : commands) server.getCommands().performPrefixedCommand(source, command);
        });
    }

    private void shot(RenderTarget target, String name) {
        Path file = out.resolve(name + ".png");
        if (Files.exists(file)) return;
        try (NativeImage image = Screenshot.takeScreenshot(target)) {
            image.writeToFile(file);
            Incoming.LOG.info("VIEWFX screenshot {}", file);
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
