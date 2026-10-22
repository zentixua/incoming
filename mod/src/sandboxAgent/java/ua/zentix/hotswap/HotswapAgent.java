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
 * Горячая замена кода в песочнице эффектов (tools/sandbox.sh): агент JVM ({@code -javaagent:sandbox-agent.jar=<файл
 * состояния>;<каталог классов>;…}) следит за каталогами классов Gradle и, когда компиляция кончилась (файлы перестали
 * меняться), подменяет уже загруженные классы новыми байтами ({@link Instrumentation#redefineClasses}). Новый класс,
 * которого ещё нет в памяти, загрузится сам с диска при первом обращении.
 * <p>
 * Обычная JVM меняет только тела методов (и значения констант, которые javac вписал в них). Новое поле, метод, лямбда,
 * смена сигнатуры — отказ: строка «fail» в файле состояния, нужен перезапуск клиента; JetBrains Runtime с
 * {@code -XX:+AllowEnhancedClassRedefinition} принимает и их. Статические инициализаторы заново не идут.
 * <p>
 * Файл состояния — журнал: строка на замену «&lt;номер&gt; ok|fail|none &lt;классов&gt; &lt;подробности&gt;», номер растёт. Рядом —
 * {@code <файл>.idle}: время (мс) начала обхода, в котором ничего не ждало замены, — агент догнал всё, что записано до
 * него. Скрипт ждёт, пока оно станет позже конца сборки, и смотрит все строки после своей: Gradle пишет классы main и
 * devtest двумя задачами, и замен за сборку бывает две. Без Minecraft: агент грузится до игры, в системный загрузчик.
 */
public final class HotswapAgent {
    private HotswapAgent() {}

    /** Опрос каталогов, мс; замена — когда {@link #QUIET_POLLS} опросов подряд ничего не менялось. */
    static final long POLL_MS = 150;
    static final int QUIET_POLLS = 2;

    public static void premain(String args, Instrumentation inst) {
        String[] parts = args == null ? new String[0] : args.split(";");
        if (parts.length < 2) {
            System.err.println("[hotswap] нужно: -javaagent:sandbox-agent.jar=<файл состояния>;<каталог классов>;…");
            return;
        }
        Path state = Path.of(parts[0]);
        List<Path> roots = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) roots.add(Path.of(parts[i]));
        Watcher w = new Watcher(roots);
        write(state, "0 none 0 агент готов, каталогов " + roots.size(), false);
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

    /** Подменить загруженные классы из {@code changed} (имя → файл); итог — «ok|fail|none N подробности». */
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
                return "fail 0 не прочитать " + changed.get(e.getKey()) + ": " + ex;
            }
            for (Class<?> c : e.getValue()) defs.add(new ClassDefinition(c, bytes));
        }
        String time = LocalTime.now().truncatedTo(ChronoUnit.MILLIS).toString();
        if (defs.isEmpty()) {
            log(time + " изменено " + changed.size() + ", в памяти нет ни одного — загрузятся с диска");
            return "none 0 изменено " + changed.size() + ", загруженных нет";
        }
        try {
            inst.redefineClasses(defs.toArray(new ClassDefinition[0]));
            log(time + " заменено " + defs.size() + ": " + String.join(" ", new TreeMap<>(loaded).keySet()));
            return "ok " + defs.size() + " " + String.join(" ", new TreeMap<>(loaded).keySet());
        } catch (Throwable ex) {
            // кто именно не встал: по одному (замены, что прошли, остаются — тела методов у них новые)
            List<String> bad = new ArrayList<>();
            for (ClassDefinition d : defs) {
                try {
                    inst.redefineClasses(d);
                } catch (Throwable one) {
                    bad.add(d.getDefinitionClass().getName() + " (" + one.getClass().getSimpleName() + ": " + one.getMessage() + ")");
                }
            }
            log(time + " НЕ заменено, нужен перезапуск: " + String.join("; ", bad));
            return "fail " + defs.size() + " нужен перезапуск: " + String.join("; ", bad);
        }
    }

    private static void log(String line) {
        System.out.println("[hotswap] " + line);
    }

    /** Строка в файл: дописать в журнал или заменить файл целиком (атомарно). */
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
     * Что поменялось в каталогах классов: снимок «файл → время и размер»; изменения копятся, пока идут, и отдаются,
     * когда {@link #QUIET_POLLS} опроса подряд новых нет (Gradle дописал всё).
     */
    static final class Watcher {
        private final List<Path> roots;
        private Map<Path, Long> seen;
        private final Map<String, Path> pending = new TreeMap<>();
        /** Опросов подряд без новых изменений. */
        private int quiet;

        Watcher(List<Path> roots) {
            this.roots = roots;
            this.seen = scan();
        }

        /** Последний обход ничего не нашёл и ничего не ждёт замены. */
        boolean idle() {
            return pending.isEmpty() && quiet > 0;
        }

        /** Готовый набор «имя класса → файл» или null (ничего или ещё пишется). */
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
                            // файл удалила компиляция между обходом и чтением
                        }
                    });
                } catch (IOException | UncheckedIOException e) {
                    // каталог пересоздаётся (clean) — следующий опрос
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
