package ua.zentix.incoming.compat;

import net.minecraft.core.BlockPos;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * LambDynamicLights (client): light carried by entities — a held torch, a burning mob, a dropped glowstone — which the
 * Minecraft light engine does not hold. That mod lights blocks by the larger of the engine's block light and its own level
 * ({@code getLightmapWithDynamicLight}), so the nuclear pall takes its level the same way ({@code client/fx/layer/MovingLight}):
 * the pall's dust scatters it and its shadow on the world leaves it.
 * <p>
 * Its public runtime class, through reflection: {@code LambDynLights.get()}, {@code getLightSourcesCount()} and
 * {@code getDynamicLightLevel(BlockPos)} (0..15; 0 with dynamic lighting off in its settings). The {@code api} package has
 * no query of the level (it registers light sources). Without the mod, or without those members, there is no moving light.
 */
public final class DynamicLights {
    /** Its NeoForge mod id ({@code lambdynlights_runtime}; the API is a separate mod). */
    static final String MOD = "lambdynlights_runtime";
    private static boolean looked;
    @Nullable
    private static MethodHandle get, count, level;

    private DynamicLights() {}

    /** Whether there are light sources to ask about this frame (the mod present and tracking any). */
    public static boolean active() {
        if (!looked) find();
        MethodHandle c = count;
        if (c == null) return false;
        try {
            return (int) c.invoke(get.invoke()) > 0;
        } catch (Throwable t) {
            off(t);
            return false;
        }
    }

    /** Dynamic light level at block {@code pos}, 0..15; 0 without the mod. */
    public static double level(BlockPos pos) {
        MethodHandle l = level;
        if (l == null) return 0;
        try {
            return (double) l.invoke(get.invoke(), pos);
        } catch (Throwable t) {
            off(t);
            return 0;
        }
    }

    /** Debug: light sources the mod tracks, −1 without it. */
    public static int sources() {
        if (!looked) find();
        MethodHandle c = count;
        if (c == null) return -1;
        try {
            return (int) c.invoke(get.invoke());
        } catch (Throwable t) {
            off(t);
            return -1;
        }
    }

    private static void find() {
        looked = true;
        if (!ModList.get().isLoaded(MOD)) return;
        try {
            Class<?> c = Class.forName("dev.lambdaurora.lambdynlights.LambDynLights");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            get = lookup.findStatic(c, "get", MethodType.methodType(c));
            count = lookup.findVirtual(c, "getLightSourcesCount", MethodType.methodType(int.class));
            level = lookup.findVirtual(c, "getDynamicLightLevel", MethodType.methodType(double.class, BlockPos.class));
            Incoming.LOG.info("LambDynamicLights: light carried by entities lights the nuclear pall's dust");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            off(ex);
        }
    }

    private static void off(Throwable t) {
        if (count != null || level != null) Incoming.LOG.warn("LambDynamicLights: moving light in the pall off ({})", t.toString());
        get = count = level = null;
    }
}
