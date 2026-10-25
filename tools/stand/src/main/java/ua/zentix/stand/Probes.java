package ua.zentix.stand;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Строки чужих модов отражением: статический метод без аргументов, «класс#метод» из плана (у Incoming —
 * {@code FxLayer.describeTiming}: время слоя на видеокарте, проход жара, заливка; у Iris — имя шейдерпака). Метода нет
 * в этой версии — строка «n/a» один раз, и дальше он не зовётся. Методы ищутся в первом тике мира, а не при загрузке
 * мода: иначе классы чужого клиента загружались бы раньше, чем их загрузил бы он сам.
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

    /** Раз в секунду. */
    void tick(long now) {
        if (each == null) each = resolve(eachSpecs);
        for (Probe p : each) out.event("probe", now, "name", p.spec, "value", call(p));
    }

    /** Один раз после входа в мир. */
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
