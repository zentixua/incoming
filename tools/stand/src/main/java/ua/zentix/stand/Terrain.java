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
 * Земля у камеры дорисована: камера стоит, а кадр и окно замера не берут ещё не собранную землю (сквозь неё небо, кадр
 * дешевле и светлее: #468, 25.10 — B ярче A1 и A2 на 0,17, а на другой голове — наоборот).
 * <p>
 * Готово — когда (1) каждый чанк, который клиент нарисует, пришёл: вид игрока на сервере — тот же
 * {@code ChunkTrackingView.of(центр, дальность)}, что строит {@code ChunkMap.updateChunkTracking} (у встроенного сервера
 * дальность — клиентская), а Sodium берёт в рендер чанк, только когда данные и свет есть у всех девяти чанков 3×3
 * ({@code ChunkTracker.updateMerged}), — значит, нужны все чанки вида, у которых и соседи в виде; (2) каждая секция,
 * которую последний обход видимости Sodium взял в кадр, собрана и загружена ({@code isSectionReady}: первая сборка
 * дошла до видеокарты; пересборки от ударов её не снимают). Взял в кадр — обход до неё дошёл ({@code isBoxVisible} по
 * одной секции: отметку обход ставит и соседям, которых потом отбросит по дальности и пирамиде видимости) и она в
 * пирамиде кадра и в дальности — проверки строже, чем у Sodium ({@code OcclusionCuller.isSectionVisible}: секция
 * без запаса в полблока против рамки с запасом, дальность на блок меньше), чтобы не ждать секцию, которую Sodium не
 * строит. Пустая очередь сборщика ({@code hasRenderedAllSections}) этого не говорит: задание уходит из неё до
 * выполнения, а ещё не найденные секции в неё не попадают. Обход видимости секции за новой землёй находит кадром
 * позже её загрузки, поэтому {@link Director} ждёт, чтобы готово держалось всю паузу шага подряд.
 * <p>
 * Без Sodium — запасной путь ванили: чанки (1) и пустая очередь {@code hasRenderedAllSections}.
 */
final class Terrain {
    private static final MethodHandle INSTANCE, VISIBLE, READY, TRACKER, READY_CHUNKS;
    /** Sodium есть, а пути нет (другая версия): готово не наступит, прогон уйдёт в tp-timeout с этой причиной. */
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

    /** Дальность обхода Sodium в последнем кадре, блоки ({@link #onStage}); NaN — кадра ещё не было. */
    private static float sodiumFar = Float.NaN;

    private Terrain() {}

    /**
     * Туман земли этого кадра — тот, что Sodium прочёл в {@code setupTerrain} ({@code getEffectiveRenderDistance}: при
     * непрозрачном тумане — его конец + 0,5, не дальше прорисовки): его ставит {@code FogRenderer.setupFog(FOG_TERRAIN)}
     * перед {@code setupRender}, и до сплошного слоя земли его никто не меняет (моды и шейдерпак — тоже через него).
     */
    static void onStage(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) return;
        float far = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16f;
        sodiumFar = Mth.equal(RenderSystem.getShaderFogColor()[3], 1f) ? Math.min(far, RenderSystem.getShaderFogEnd() + 0.5f) : far;
    }

    /** Чанк нарисуется, когда придёт: он и его соседи 3×3 в виде игрока с центром {@code cx, cz} и дальностью {@code r}. */
    static boolean drawn(int cx, int cz, int r, int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!ChunkTrackingView.isWithinDistance(cx, cz, r, x + dx, z + dz, true)) return false;
            }
        }
        return true;
    }

    /**
     * Секция ({@code x, y, z}) ближе {@code max} блоков к камере по ближней точке, как дальность Sodium (по горизонтали
     * и по высоте отдельно — цилиндр тумана ванили).
     */
    static boolean near(double camX, double camY, double camZ, int x, int y, int z, double max) {
        double dx = Mth.clamp(camX, x << 4, (x << 4) + 16) - camX, dy = Mth.clamp(camY, y << 4, (y << 4) + 16) - camY,
                dz = Mth.clamp(camZ, z << 4, (z << 4) + 16) - camZ;
        return dx * dx + dz * dz < max * max && Math.abs(dy) < max;
    }

    /** {@code null} — готово; иначе — чего ждём (в событие {@code tp-timeout}). */
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
            // круг вида вписан в квадрат r + 1 (isWithinDistance с крайними чанками)
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
                        // рамка внутри одной секции: isBoxVisible расширяет её на полблока
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
