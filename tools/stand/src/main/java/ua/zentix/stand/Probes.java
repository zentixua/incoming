package ua.zentix.stand;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Reflect strings from other mods: static no-argument "class#method" from plan (Incoming
 * {@code FxLayer.describeTiming}: GPU layer time, heat pass, fill; Iris: shader pack name). Absent in this
 * version: log "n/a" once, stop calling it. Resolve methods in first world tick, not mod loading, to avoid
 * loading other client classes before their mod would.
 */
final class Probes {
    private record Probe(String spec, Method method) {}

    private final Out out;
    private final List<String> eachSpecs, onceSpecs;
    private List<Probe> each;

    Probes(Out out, List<String> each, List<String> once) {
        this.out = out;
        this.eachSpecs = each;
        this.onceSpecs = once;
    }

    /** Once a second. */
    void tick(long now) {
        if (each == null) each = resolve(eachSpecs);
        for (Probe p : each) out.event("probe", now, "name", p.spec, "value", call(p));
    }

    /** Once after world entry. */
    void once(long now) {
        for (Probe p : resolve(onceSpecs)) out.event("once", now, "name", p.spec, "value", call(p));
    }

    private List<Probe> resolve(List<String> specs) {
        List<Probe> found = new ArrayList<>();
        for (String spec : specs) {
            int hash = spec.indexOf('#');
            try {
                Class<?> c = Class.forName(spec.substring(0, hash), false, Probes.class.getClassLoader());
                Method m = c.getMethod(spec.substring(hash + 1));
                if (!Modifier.isStatic(m.getModifiers())) throw new NoSuchMethodException("not static");
                found.add(new Probe(spec, m));
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                out.event("probe", System.nanoTime(), "name", spec, "value", "n/a", "why", e.toString());
            }
        }
        return List.copyOf(found);
    }

    private static String call(Probe p) {
        try {
            return String.valueOf(p.method.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "error " + e;
        }
    }
}
