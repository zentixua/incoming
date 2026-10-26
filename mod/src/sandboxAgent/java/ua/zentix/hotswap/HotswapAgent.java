package ua.zentix.hotswap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Effects sandbox hot code swap (tools/sandbox.sh): JVM agent ({@code -javaagent:sandbox-agent.jar=<state
 * file>;<class directory>;…}) watches Gradle class directories; once compilation ends (files stop
 * changing), replaces loaded classes with new bytes ({@link Instrumentation#redefineClasses}). New classes
 * not yet in memory load from disk on first access.
 * <p>
 * Ordinary JVM replaces only method bodies (and constants javac inlined in them). New field, method, lambda,
 * signature change: refused, “fail” in state file, client restart needed; JetBrains Runtime with
 * {@code -XX:+AllowEnhancedClassRedefinition} accepts them too. Static initializers do not rerun.
 * <p>
 * State file is a journal: one line per replacement “&lt;number&gt; ok|fail|none &lt;classes&gt; &lt;details&gt;”, increasing number. Beside it,
 * {@code <file>.idle}: start time (ms) of scan with no replacements pending — agent caught up with everything written before
 * it. Script waits until this exceeds build end, checks all lines after its own: Gradle writes main and
 * devtest classes in two tasks, so one build can produce two swaps. Minecraft-free: agent loads before game in system classloader.
 */
public final class HotswapAgent {
    private HotswapAgent() {}

    /** Directory polling interval, ms; replace after {@link #QUIET_POLLS} consecutive unchanged polls. */
    static final long POLL_MS = 150;
    static final int QUIET_POLLS = 2;

    public static void premain(String args, Instrumentation inst) {
        String[] parts = args == null ? new String[0] : args.split(";");
        if (parts.length < 2) {
            System.err.println("[hotswap] usage: -javaagent:sandbox-agent.jar=<state file>;<class directory>;…");
            return;
        }
        Path state = Path.of(parts[0]);
        List<Path> roots = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) roots.add(Path.of(parts[i]));
        Watcher w = new Watcher(roots);
        write(state, "0 none 0 agent ready, directories " + roots.size(), false);
        Thread t = new Thread(() -> loop(inst, w, state), "Sandbox hotswap");
        t.setDaemon(true);
        t.start();
    }

    private static void loop(Instrumentation inst, Watcher w, Path state) {
        int n = 0;
        Path idle = state.resolveSibling(state.getFileName() + ".idle");
        while (true) {
            try {
                Thread.sleep(POLL_MS);
                long scan = System.currentTimeMillis();
                Map<String, Path> changed = w.poll();
                if (changed != null) {
                    n++;
                    write(state, n + " " + redefine(inst, changed), true);
                } else if (w.idle()) {
                    write(idle, Long.toString(scan), false);
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                write(state, ++n + " fail 0 " + e, true);
            }
        }
    }

    /** Replace loaded classes from {@code changed} (name → file); result: “ok|fail|none N details”. */
    static String redefine(Instrumentation inst, Map<String, Path> changed) {
        Map<String, List<Class<?>>> loaded = new HashMap<>();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (changed.containsKey(c.getName())) loaded.computeIfAbsent(c.getName(), k -> new ArrayList<>()).add(c);
        }
        List<ClassDefinition> defs = new ArrayList<>();
        for (var e : loaded.entrySet()) {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(changed.get(e.getKey()));
            } catch (IOException ex) {
                return "fail 0 cannot read " + changed.get(e.getKey()) + ": " + ex;
            }
            for (Class<?> c : e.getValue()) defs.add(new ClassDefinition(c, bytes));
        }
        String time = LocalTime.now().truncatedTo(ChronoUnit.MILLIS).toString();
        if (defs.isEmpty()) {
            log(time + " changed " + changed.size() + ", none loaded — will load from disk");
            return "none 0 changed " + changed.size() + ", none loaded";
        }
        try {
            inst.redefineClasses(defs.toArray(new ClassDefinition[0]));
            log(time + " replaced " + defs.size() + ": " + String.join(" ", new TreeMap<>(loaded).keySet()));
            return "ok " + defs.size() + " " + String.join(" ", new TreeMap<>(loaded).keySet());
        } catch (Throwable ex) {
            // Find failures individually (successful replacements remain — their method bodies are new).
            List<String> bad = new ArrayList<>();
            for (ClassDefinition d : defs) {
                try {
                    inst.redefineClasses(d);
                } catch (Throwable one) {
                    bad.add(d.getDefinitionClass().getName() + " (" + one.getClass().getSimpleName() + ": " + one.getMessage() + ")");
                }
            }
            log(time + " NOT replaced, restart required: " + String.join("; ", bad));
            return "fail " + defs.size() + " restart required: " + String.join("; ", bad);
        }
    }

    private static void log(String line) {
        System.out.println("[hotswap] " + line);
    }

    /** File line: append to journal or atomically replace whole file. */
    private static void write(Path file, String line, boolean append) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            if (append) {
                Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return;
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("[hotswap] " + file + ": " + e);
        }
    }

    /**
     * Class-directory changes: “file → time and size” snapshot; accumulate changes while ongoing, return them
     * after {@link #QUIET_POLLS} consecutive unchanged polls (Gradle finished writing).
     */
    static final class Watcher {
        private final List<Path> roots;
        private Map<Path, Long> seen;
        private final Map<String, Path> pending = new TreeMap<>();
        /** Consecutive polls without new changes. */
        private int quiet;

        Watcher(List<Path> roots) {
            this.roots = roots;
            this.seen = scan();
        }

        /** Last scan found nothing and no replacements pending. */
        boolean idle() {
            return pending.isEmpty() && quiet > 0;
        }

        /** Ready “class name → file” set, or null (nothing, or still writing). */
        Map<String, Path> poll() {
            Map<Path, Long> now = scan();
            boolean moved = false;
            for (var e : now.entrySet()) {
                Long before = seen.get(e.getKey());
                if (before == null || !before.equals(e.getValue())) {
                    pending.put(className(e.getKey()), e.getKey());
                    moved = true;
                }
            }
            seen = now;
            quiet = moved ? 0 : quiet + 1;
            if (pending.isEmpty() || quiet < QUIET_POLLS) return null;
            Map<String, Path> out = new TreeMap<>(pending);
            pending.clear();
            return out;
        }

        private Map<Path, Long> scan() {
            Map<Path, Long> out = new HashMap<>();
            for (Path root : roots) {
                if (!Files.isDirectory(root)) continue;
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                        try {
                            out.put(p, Files.getLastModifiedTime(p).toMillis() * 31 + Files.size(p));
                        } catch (IOException e) {
                            // Compilation removed file between traversal and read.
                        }
                    });
                } catch (IOException | UncheckedIOException e) {
                    // Directory being recreated (clean): next poll.
                }
            }
            return out;
        }

        private String className(Path file) {
            for (Path root : roots) {
                if (file.startsWith(root)) return HotswapAgent.className(root.relativize(file).toString());
            }
            return file.toString();
        }
    }

    /** «a/b/C$D.class» → «a.b.C$D». */
    static String className(String relative) {
        String s = relative.replace('\\', '/');
        if (s.endsWith(".class")) s = s.substring(0, s.length() - ".class".length());
        return s.replace('/', '.');
    }
}
