package ua.zentix.stand;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.longs.LongCollection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Ready ground at camera: camera stays still; shots/windows exclude unbuilt terrain (sky through it,
 * cheaper/brighter frame: #468, 25.10, B brighter than A1/A2 by 0,17, reversed on another head).
 * <p>Ready means (1) all chunks the client will render arrived: server player view is the same
 * {@code ChunkTrackingView.of(centre, distance)} as {@code ChunkMap.updateChunkTracking} (integrated
 * server uses client distance); Sodium renders a chunk only with data/light for all nine 3×3 chunks
 * ({@code ChunkTracker.updateMerged}), so require view chunks whose neighbours are also in view; (2)
 * every section included by the last Sodium visibility traversal is built/uploaded ({@code isSectionReady}:
 * first build reached GPU, impact rebuilds do not clear it). Included means traversal visited it
 * ({@code isBoxVisible} alone is insufficient: neighbours later culled by distance/frustum are also marked),
 * and it is in frame frustum and range. Checks are stricter than Sodium
 * ({@code OcclusionCuller.isSectionVisible}: section without half-block padding versus padded bounds,
 * range one block shorter), avoiding waits for sections Sodium does not build. Empty builder queue
 * ({@code hasRenderedAllSections}) proves neither: jobs leave before execution; undiscovered sections
 * never entered. Visibility discovers sections behind new ground one frame after upload, so
 * {@link Director} requires readiness throughout the step pause.
 * <p>Without Sodium, vanilla fallback: chunks (1) and empty {@code hasRenderedAllSections} queue.
 */
final class Terrain {
    private static final MethodHandle INSTANCE, VISIBLE, READY, TRACKER, READY_CHUNKS;
    /** Sodium present but unsupported path/version: readiness never occurs; run reaches tp-timeout with this reason. */
    private static final String BROKEN;

    static {
        MethodHandle instance = null, visible = null, ready = null, tracker = null, readyChunks = null;
        String broken = null;
        MethodHandles.Lookup l = MethodHandles.publicLookup();
        try {
            ClassLoader loader = Terrain.class.getClassLoader();
            Class<?> renderer = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", false, loader);
            Class<?> holder = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder", false, loader);
            Class<?> chunkTracker = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker", false, loader);
            instance = l.findStatic(renderer, "instanceNullable", MethodType.methodType(renderer));
            visible = l.findVirtual(renderer, "isBoxVisible", MethodType.methodType(boolean.class,
                    double.class, double.class, double.class, double.class, double.class, double.class));
            ready = l.findVirtual(renderer, "isSectionReady", MethodType.methodType(boolean.class, int.class, int.class, int.class));
            tracker = l.findStatic(holder, "get", MethodType.methodType(chunkTracker, ClientLevel.class));
            readyChunks = l.findVirtual(chunkTracker, "getReadyChunks", MethodType.methodType(LongCollection.class));
        } catch (ClassNotFoundException e) {
            instance = null;
        } catch (ReflectiveOperationException e) {
            broken = "sodium without the terrain readiness API: " + e;
        }
        INSTANCE = instance;
        VISIBLE = visible;
        READY = ready;
        TRACKER = tracker;
        READY_CHUNKS = readyChunks;
        BROKEN = broken;
    }

    /** Sodium traversal range in last frame, blocks ({@link #onStage}); NaN before first frame. */
    private static float sodiumFar = Float.NaN;

    private Terrain() {}

    /**
     * This frame terrain fog is what Sodium read in {@code setupTerrain} ({@code getEffectiveRenderDistance}:
     * opaque fog end + 0,5, capped at render distance). {@code FogRenderer.setupFog(FOG_TERRAIN)} sets it before
     * {@code setupRender}; unchanged through solid terrain, including mods/shader pack using the same path.
     */
    static void onStage(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) return;
        float far = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16f;
        sodiumFar = Mth.equal(RenderSystem.getShaderFogColor()[3], 1f) ? Math.min(far, RenderSystem.getShaderFogEnd() + 0.5f) : far;
    }

    /** Chunk can render on arrival when it and 3×3 neighbours are in player view centred at {@code cx, cz}, radius {@code r}. */
    static boolean drawn(int cx, int cz, int r, int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!ChunkTrackingView.isWithinDistance(cx, cz, r, x + dx, z + dz, true)) return false;
            }
        }
        return true;
    }

    /**
     * Section ({@code x, y, z}) nearest point is within {@code max} blocks of camera, like Sodium distance
     * (horizontal and vertical separately, vanilla fog cylinder).
     */
    static boolean near(double camX, double camY, double camZ, int x, int y, int z, double max) {
        double dx = Mth.clamp(camX, x << 4, (x << 4) + 16) - camX, dy = Mth.clamp(camY, y << 4, (y << 4) + 16) - camY,
                dz = Mth.clamp(camZ, z << 4, (z << 4) + 16) - camZ;
        return dx * dx + dz * dz < max * max && Math.abs(dy) < max;
    }

    /** {@code null} means ready; otherwise the awaited requirement for {@code tp-timeout}. */
    static String waiting(Minecraft mc, int eyeX, int eyeZ) {
        if (BROKEN != null) return BROKEN;
        ClientLevel level = mc.level;
        int r = mc.options.getEffectiveRenderDistance(), cx = eyeX >> 4, cz = eyeZ >> 4;
        try {
            Object sodium = INSTANCE == null ? null : INSTANCE.invoke();
            LongCollection merged = sodium == null ? null : (LongCollection) READY_CHUNKS.invoke(TRACKER.invoke(level));
            int min = level.getMinSection(), max = level.getMaxSection();
            Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
            Frustum frustum = mc.levelRenderer.getFrustum();
            if (sodium != null && (frustum == null || Float.isNaN(sodiumFar))) return "first frame";
            double far = sodiumFar - 1;
            // View circle fits square r + 1 (isWithinDistance includes edge chunks)
            for (int x = cx - r - 1; x <= cx + r + 1; x++) {
                for (int z = cz - r - 1; z <= cz + r + 1; z++) {
                    if (!drawn(cx, cz, r, x, z)) continue;
                    if (sodium == null) {
                        if (!level.getChunkSource().hasChunk(x, z)) return "chunk " + x + " " + z;
                        continue;
                    }
                    if (!merged.contains(ChunkPos.asLong(x, z))) return "chunk " + x + " " + z;
                    for (int y = min; y < max; y++) {
                        double x0 = x << 4, y0 = y << 4, z0 = z << 4;
                        if (!near(cam.x, cam.y, cam.z, x, y, z, far)) continue;
                        // Bounds inside one section: isBoxVisible expands by half a block
                        if (!(boolean) VISIBLE.invoke(sodium, x0 + 1, y0 + 1, z0 + 1, x0 + 15, y0 + 15, z0 + 15)) continue;
                        if (!frustum.isVisible(new AABB(x0 + 0.5, y0 + 0.5, z0 + 0.5, x0 + 15.5, y0 + 15.5, z0 + 15.5))) continue;
                        if (!(boolean) READY.invoke(sodium, x, y, z)) return "section " + x + " " + y + " " + z;
                    }
                }
            }
            if (sodium == null && !mc.levelRenderer.hasRenderedAllSections()) return "build queue";
            return null;
        } catch (Throwable t) {
            return "sodium " + t;
        }
    }
}
