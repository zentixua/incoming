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

    private ShaderPack() {}

    /** An Iris shader pack currently renders the world. */
    public static boolean inUse() {
        if (!checked) {
            checked = true;
            inUse = find();
        }
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
