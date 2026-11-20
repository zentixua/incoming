package ua.zentix.incoming.client.fx.layer;

import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.compat.HandleFailures;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Whether an Iris shader pack renders the world ({@code IrisApi.isShaderPackInUse}, by reflection: the mod does not depend on Iris). */
public final class ShaderPack {
    private static boolean checked;
    /** null — no Iris or no API. */
    @Nullable
    private static MethodHandle inUse;
    /** {@code IrisApi.getSunPathRotation} (Iris 1.6+); null — no Iris or an older API. */
    @Nullable
    private static MethodHandle sunPath;

    private ShaderPack() {}

    /** An Iris shader pack currently renders the world. */
    public static boolean inUse() {
        check();
        if (inUse == null) return false;
        try {
            return (boolean) inUse.invokeExact();
        } catch (Exception | LinkageError t) {
            inUse = null;
            Incoming.LOG.warn("Iris: shader pack check failed, effects assume no shader pack", t);
            return false;
        } catch (Throwable t) {
            throw HandleFailures.propagate(t);
        }
    }

    private static void check() {
        if (checked) return;
        checked = true;
        inUse = find();
        sunPath = inUse == null ? null : sunPathHandle();
    }

    /**
     * The pack's sun path rotation, degrees ({@code sunPathRotation}: Iris turns the sun's and moon's orbit about the
     * east–west axis by it); 0 — no pack, no Iris, or an Iris without this API.
     */
    public static float sunPathRotation() {
        if (!inUse()) return 0;
        MethodHandle h = sunPath;
        if (h == null) return 0;
        try {
            return (float) h.invokeExact();
        } catch (Exception | LinkageError t) {
            sunPath = null;
            Incoming.LOG.warn("Iris: sun path rotation query failed, effects assume the vanilla sun path", t);
            return 0;
        } catch (Throwable t) {
            throw HandleFailures.propagate(t);
        }
    }

    @Nullable
    private static MethodHandle sunPathHandle() {
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object iris = api.getMethod("getInstance").invoke(null);
            return MethodHandles.publicLookup().findVirtual(api, "getSunPathRotation", MethodType.methodType(float.class)).bindTo(iris);
        } catch (ReflectiveOperationException | LinkageError e) {
            Incoming.LOG.info("Iris: no sun path rotation API, effects assume the vanilla sun path");
            return null;
        }
    }

    @Nullable
    private static MethodHandle find() {
        if (!ModList.get().isLoaded("iris")) return null;
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object iris = api.getMethod("getInstance").invoke(null);
            return MethodHandles.publicLookup().findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class)).bindTo(iris);
        } catch (ReflectiveOperationException | LinkageError e) {
            Incoming.LOG.warn("Iris: no shader pack API, effects assume no shader pack", e);
            return null;
        }
    }
}
