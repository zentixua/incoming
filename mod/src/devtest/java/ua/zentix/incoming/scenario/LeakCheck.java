package ua.zentix.incoming.scenario;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import ua.zentix.incoming.Incoming;

import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * World memory leak (scenario {@code leak}: {@code tools/prod_client.py leak --world …}, laptop job
 * {@code tools/laptop-jobs/leak-heap.md}). Symptoms: after leaving the world, the menu has more live objects
 * than the menu before a world; a second world in the same run starts higher; teleports alone also cause
 * growth, but the holder is unknown. A world should leave memory completely after exit, so measure in the
 * menu: the holder is visible in one run.
 * <p>Rounds ({@code incoming.leak.rounds}, default 2): open the world, then {@link #START} ticks after entry
 * run round commands ({@code incoming.leak.commands}, separated by ";" as in {@code commands}; as the player
 * with operator permissions, since the world copy may have cheats disabled), spaced by {@code incoming.leak.gap}
 * ticks (200), and the same delay after the last command; exit to the main menu as with "Save and Quit"
 * ({@code PauseScreen.onDisconnect}); wait {@code incoming.leak.settle} seconds (20) in the menu, perform two
 * collections {@link #GC_PAUSE_S} s apart, then measure. Measurement 0 is the menu before first entry (same-run
 * baseline). World: a copy of the player world ({@code incoming.world}) or the scenario world; first entry
 * creates it anew, subsequent rounds open the same world.
 * <p>Menu measurement: {@code SCENARIO leak menu N: live heap …}; live-class histogram (MBean
 * {@code DiagnosticCommand}, operation {@code gcClassHistogram}, same as {@code jcmd GC.class_histogram})
 * in {@code <game directory>/leak/histo-N.txt}; log key world classes ({@link #KEYS}: exact and with subclasses)
 * and the first {@link #TOP} by bytes. Dump JFR old-object samples ({@code jdk.OldObjectSample}) with paths to
 * GC roots in {@code leak/old-N.jfr}, and samples whose path crosses a world ({@link #WORLD_MARKS}) in
 * {@code leak/old-N.txt}. After the last measurement, if {@code incoming.leak.heapdump=true}, dump live heap
 * objects to {@code leak/heap.hprof}. Before each exit: {@code SCENARIO leak world N: live heap …} (world still
 * in memory). A measurement error is WARN; the scenario continues to {@code SCENARIO done}.
 */
final class LeakCheck {
    /** Key classes of the world: after exiting to the menu, their instances should not exist. */
    private static final List<String> KEYS = List.of(
            "net.minecraft.server.level.ServerLevel",
            "net.minecraft.client.multiplayer.ClientLevel",
            "net.minecraft.client.server.IntegratedServer",
            "net.minecraft.world.level.chunk.LevelChunk",
            "net.minecraft.world.level.chunk.ProtoChunk",
            "net.minecraft.world.level.chunk.LevelChunkSection",
            "net.minecraft.server.level.ChunkHolder",
            "net.minecraft.world.entity.Entity");
    /** sample samples JFR "through the world": object type, path link to root, or root contains one of these names. */
    static final List<String> WORLD_MARKS = List.of("LevelChunk", "ServerLevel", "ClientLevel", "ChunkHolder", "LevelChunkSection",
            "PalettedContainer", "IntegratedServer");
    private static final int TOP = 15;
    /** tick in the world to the first command. */
    private static final int START = 100;
    /** A pause between two builds in the menu and the longest opening of the world, p. */
    private static final long GC_PAUSE_S = 5, OPEN_LIMIT_S = 900;
    /**
     * GC searches for paths to the roots of thread VM by depth-first traversal (JDK 21): on its default stack (1 MB) a deep chain of
     *  links overflows the stack, and JVM crashes SIGSEGV without hs_err (the game is on the first reset; a list of 2 million
     *  links: with 1 MB drops, with 1,5 MB and more - no). With a stack less than this (KB)
     * paths are not searched.
     */
    private static final long PATH_STACK_KB = 4096;
    /** How long to parse histogram names into classes (key subclasses), no longer, ms. */
    private static final long RESOLVE_LIMIT_MS = 10_000;
    private static final long NANOS = 1_000_000_000L;
    private static final Pattern ROW = Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)");
    private static final Pattern TOTAL = Pattern.compile("^Total\\s+(\\d+)\\s+(\\d+)");

    private enum Phase { TITLE, MENU, OPENING, WORLD, LEAVING, DONE }

    private final int rounds = Math.max(1, Integer.getInteger("incoming.leak.rounds", 2));
    private final int gap = Math.max(1, Integer.getInteger("incoming.leak.gap", 200));
    private final long settleNanos = Math.max(0, Integer.getInteger("incoming.leak.settle", 20)) * NANOS;
    private final boolean heapDump = Boolean.getBoolean("incoming.leak.heapdump");
    private final List<String> commands = ScenarioCommands.split(System.getProperty("incoming.leak.commands", ""));
    /** A copy of the player's world; without her - the world of the script. */
    private final String world = System.getProperty("incoming.world");

    private Phase phase = Phase.TITLE;
    /** Entry number: 0 - menu before the first entry, N - menu after exiting entry N. */
    private int round;
    /** Start waiting in the menu, opening the world or exiting ({@link System#nanoTime()}). */
    private long since;
    private boolean firstGc;
    private int worldTicks;
    private Path dir;
    private OldObjects jfr;

    LeakCheck() {
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    private void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        switch (phase) {
            case TITLE -> {
                // main menu after loading resources: selection JFR - from this point, loading the game does not go into it
                if (mc.screen instanceof TitleScreen && mc.getOverlay() == null) begin(mc);
            }
            case MENU -> menuTick(mc);
            case OPENING -> openingTick(mc);
            case WORLD -> worldTick(mc);
            case LEAVING, DONE -> {}
        }
    }

    private void begin(Minecraft mc) {
        phase = Phase.MENU;
        since = System.nanoTime();
        dir = mc.gameDirectory.toPath().resolve("leak");
        try {
            Files.createDirectories(dir);
        } catch (IOException | RuntimeException ex) {
            Incoming.LOG.warn("SCENARIO leak: folder {} was not created - there will be no measurement files", dir, ex);
        }
        long stack = vmThreadStackKb();
        boolean paths = stack >= PATH_STACK_KB;
        if (!paths) {
            Incoming.LOG.warn("SCENARIO leak: thread stack VM {} KB - paths to the roots of GC are disabled (search on the stack by default drops JVM to SIGSEGV), "
                    + "need -XX:VMThreadStackSize={} or more", stack, PATH_STACK_KB);
        }
        try {
            jfr = new OldObjects(paths);
        } catch (Exception | LinkageError ex) {
            Incoming.LOG.warn("SCENARIO leak: recording JFR has not started - there will be no sampling of old objects", ex);
        }
        Incoming.LOG.info("SCENARIO leak start: visits {}, world {}, commands {} via {} tick, in menu {} s, snapshot of heap {}; Java {}, collector {}, heap up to {} MB, JFR {}",
                rounds, world == null ? ClientScenario.WORLD + " (script world)" : world, commands.size(), gap, settleNanos / NANOS, heapDump,
                Runtime.version(), ManagementFactory.getGarbageCollectorMXBeans().stream().map(GarbageCollectorMXBean::getName).collect(Collectors.joining(", ")),
                Runtime.getRuntime().maxMemory() >> 20, jfr == null ? "no" : paths ? "writes, with paths to the roots" : "writes, without paths to the roots");
    }

    /** {@code -XX:VMThreadStackSize} in KB (by default on Linux x64 - 1024), −1 - not recognized. */
    private static long vmThreadStackKb() {
        try {
            CompositeData d = (CompositeData) ManagementFactory.getPlatformMBeanServer().invoke(new ObjectName("com.sun.management:type=HotSpotDiagnostic"),
                    "getVMOption", new Object[] {"VMThreadStackSize"}, new String[] {String.class.getName()});
            return Long.parseLong(String.valueOf(d.get("value")));
        } catch (Exception | LinkageError ex) {
            Incoming.LOG.warn("SCENARIO leak: VMThreadStackSize is unrecognizable ({})", ex.toString());
            return -1;
        }
    }

    // --- menu ---

    private void menuTick(Minecraft mc) {
        long now = System.nanoTime();
        if (mc.level != null || mc.getSingleplayerServer() != null) {
            since = now; // the exit is not over yet
            return;
        }
        if (!firstGc) {
            if (now - since < settleNanos) return;
            gc();
            firstGc = true;
            since = now;
            return;
        }
        if (now - since < GC_PAUSE_S * NANOS) return;
        gc();
        firstGc = false;
        measure();
        if (round < rounds) {
            open(mc, ++round);
        } else {
            if (heapDump) dumpHeap();
            finish();
        }
    }

    private void measure() {
        int r = round;
        Incoming.LOG.info("SCENARIO leak menu {}: a bunch of live {} MB{}", r, heapUsedMb(), pools());
        try {
            histogram(r);
        } catch (Exception | LinkageError ex) {
            Incoming.LOG.warn("SCENARIO leak menu {}: no histogram", r, ex);
        }
        if (jfr != null) {
            try {
                jfr.dump(r, dir);
            } catch (Exception | LinkageError ex) {
                Incoming.LOG.warn("SCENARIO leak menu {}: sample JFR not reset", r, ex);
            }
        }
    }

    // --- entering the world ---

    private void open(Minecraft mc, int r) {
        phase = Phase.OPENING;
        since = System.nanoTime();
        String name = world != null ? world : ClientScenario.WORLD;
        Incoming.LOG.info("SCENARIO leak round {}: opening the world {}{}", r, name, world == null && r == 1 ? " (I’m creating again)" : "");
        // outside tick: world loading spins its frame loop like a menu button
        mc.tell(() -> {
            try {
                if (world == null && r == 1) ClientScenario.createWorld(mc.screen != null ? mc.screen : new TitleScreen());
                else mc.createWorldOpenFlows().openWorld(name, () -> fail("peace " + name + " did not open (entrance " + r + ")"));
            } catch (RuntimeException ex) {
                Incoming.LOG.error("SCENARIO leak round {}: the world has not opened", r, ex);
                fail("the world has not opened");
            }
        });
    }

    private void openingTick(Minecraft mc) {
        long now = System.nanoTime();
        if (mc.level != null && mc.player != null && mc.getSingleplayerServer() != null) {
            phase = Phase.WORLD;
            worldTicks = 0;
            Incoming.LOG.info("SCENARIO leak round {} entered after {} s: {} at {}", round, (now - since) / NANOS,
                    mc.level.dimension().location(), mc.player.blockPosition().toShortString());
        } else if (now - since > OPEN_LIMIT_S * NANOS) {
            fail("the world has not opened for " + OPEN_LIMIT_S + " s, screen " + (mc.screen == null ? "-" : mc.screen.getClass().getName()));
        }
    }

    private void worldTick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            fail("the world closed itself in the middle of sunset " + round + ", screen " + (mc.screen == null ? "-" : mc.screen.getClass().getName()));
            return;
        }
        worldTicks++;
        for (int i = 0; i < commands.size(); i++) {
            if (worldTicks == START + i * gap) run(mc, commands.get(i));
        }
        if (worldTicks < START + Math.max(1, commands.size()) * gap) return;
        phase = Phase.LEAVING;
        gc();
        Incoming.LOG.info("SCENARIO leak world {}: a bunch of live {} MB{}, chunks from the client {}", round, heapUsedMb(), pools(),
                mc.level.getChunkSource().getLoadedChunksCount());
        // outside tick: exiting in the middle of a tick event would leave the rest of the handlers in the world null
        mc.tell(() -> {
            leave(mc);
            since = System.nanoTime();
            phase = Phase.MENU;
        });
    }

    /** Round command on the server on behalf of a player with operator rights ({@code @s}, {@code ~} - player). */
    private void run(Minecraft mc, String c) {
        IntegratedServer server = mc.getSingleplayerServer();
        UUID id = mc.player.getUUID();
        int r = round;
        Incoming.LOG.info("SCENARIO leak round {} /{}", r, c);
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) {
                Incoming.LOG.warn("SCENARIO leak round {}: the player is not on the server - “{}” is skipped", r, c);
                return;
            }
            server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(4), c);
        });
    }

    /** Like {@code PauseScreen.onDisconnect} in single player: {@code disconnect} waits for the built-in server to stop. */
    private void leave(Minecraft mc) {
        if (mc.level == null) return;
        long t = System.nanoTime();
        mc.level.disconnect();
        mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
        mc.setScreen(new TitleScreen());
        Incoming.LOG.info("SCENARIO leak round {} left world: output {} ms", round, (System.nanoTime() - t) / 1_000_000);
    }

    private void fail(String why) {
        if (phase == Phase.DONE) return;
        Incoming.LOG.error("SCENARIO leak failed: {}", why);
        finish();
    }

    private void finish() {
        phase = Phase.DONE;
        if (jfr != null) {
            try {
                jfr.close();
            } catch (Exception | LinkageError ex) {
                Incoming.LOG.warn("SCENARIO leak: record JFR did not close", ex);
            }
        }
        Incoming.LOG.info("SCENARIO done");
        Minecraft.getInstance().stop();
    }

    // --- memory ---

    private static void gc() {
        try {
            System.gc();
        } catch (RuntimeException ex) {
            Incoming.LOG.warn("SCENARIO leak: System.gc() failed", ex);
        }
    }

    private static long heapUsedMb() {
        try {
            return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20;
        } catch (RuntimeException ex) {
            Incoming.LOG.warn("SCENARIO leak: MemoryMXBean did not respond", ex);
            return -1;
        }
    }

    /** Heap pools after the last build of each (for the generational ZGC there is an old and a young generation). */
    private static String pools() {
        try {
            List<String> out = new ArrayList<>();
            for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
                MemoryUsage u = p.getType() == MemoryType.HEAP ? p.getCollectionUsage() : null;
                if (u != null) out.add(p.getName() + " " + (u.getUsed() >> 20) + " MB");
            }
            return out.isEmpty() ? "" : " (after assembling by pools: " + String.join(", ", out) + ")";
        } catch (RuntimeException ex) {
            return " (pools: " + ex + ")";
        }
    }

    private record Row(String name, long count, long bytes) {}

    /** Histogram of living objects (it does a complete assembly itself): the entire file, the key classes in the log, the first bytes and the total. */
    private void histogram(int r) throws Exception {
        long t = System.nanoTime();
        String text = (String) ManagementFactory.getPlatformMBeanServer().invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"),
                "gcClassHistogram", new Object[] {new String[0]}, new String[] {String[].class.getName()});
        long took = (System.nanoTime() - t) / 1_000_000;
        Path file = dir.resolve("histo-" + r + ".txt");
        try {
            Files.writeString(file, text);
        } catch (IOException ex) {
            Incoming.LOG.warn("SCENARIO leak menu {}: {} not recorded", r, file, ex);
        }
        List<Row> rows = new ArrayList<>();
        long total = -1, totalBytes = -1;
        for (String line : text.split("\n")) {
            Matcher m = ROW.matcher(line);
            if (m.find()) {
                rows.add(new Row(m.group(3), Long.parseLong(m.group(1)), Long.parseLong(m.group(2))));
                continue;
            }
            m = TOTAL.matcher(line);
            if (m.find()) {
                total = Long.parseLong(m.group(1));
                totalBytes = Long.parseLong(m.group(2));
            }
        }
        Incoming.LOG.info("SCENARIO leak menu {} histogram: total {} pcs., {} MB, rows {} for {} ms → {}", r, total, totalBytes >> 20, rows.size(), took,
                dir.getParent().relativize(file));
        Map<String, long[]> exact = new LinkedHashMap<>();
        for (String k : KEYS) exact.put(k, new long[2]);
        for (Row row : rows) {
            long[] a = exact.get(row.name);
            if (a != null) {
                a[0] += row.count;
                a[1] += row.bytes;
            }
        }
        Map<String, Map<String, long[]>> sub = subclasses(r, rows);
        for (String k : KEYS) {
            long[] a = exact.get(k);
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%d pcs., %d B", a[0], a[1]));
            Map<String, long[]> s = sub.get(k);
            if (s != null && !s.isEmpty()) {
                long n = a[0], b = a[1];
                for (long[] v : s.values()) {
                    n += v[0];
                    b += v[1];
                }
                line.append(String.format(Locale.ROOT, "; with subclasses %d pcs., %d B: ", n, b));
                line.append(s.entrySet().stream().sorted(Comparator.comparingLong((Map.Entry<String, long[]> en) -> en.getValue()[1]).reversed()).limit(6)
                        .map(en -> en.getKey() + " " + en.getValue()[0]).collect(Collectors.joining(", ")));
                if (s.size() > 6) line.append(", ... more ").append(s.size() - 6);
            }
            Incoming.LOG.info("SCENARIO leak menu {} class {}: {}", r, k, line);
        }
        for (int i = 0; i < Math.min(TOP, rows.size()); i++) {
            Row row = rows.get(i);
            Incoming.LOG.info("SCENARIO leak menu {} top {}: {} {} pcs., {} B ({} MB)", r, i + 1, row.name, row.count, row.bytes,
                    String.format(Locale.ROOT, "%.1f", row.bytes / 1048576.0));
        }
    }

    /**
     * Subclasses of key classes among the histogram lines: {@code Entity} (abstract) does not have its own instances,
     * {@code LevelChunk} and {@code ProtoChunk} have subclasses ({@code EmptyLevelChunk}, {@code ImposterProtoChunk}),
     * , {@code IntegratedServer} - server replay Flashback. Name - to the class by the game loader without initialization (for the lines
     *  of the histogram, the classes are already loaded); classes JDK, arrays and hidden classes are not parsed.
     */
    private static Map<String, Map<String, long[]>> subclasses(int r, List<Row> rows) {
        Map<String, Map<String, long[]>> out = new HashMap<>();
        ClassLoader loader = Entity.class.getClassLoader();
        Map<String, Class<?>> keys = new LinkedHashMap<>();
        for (String k : KEYS) {
            try {
                keys.put(k, Class.forName(k, false, loader));
            } catch (ClassNotFoundException | LinkageError ex) {
                Incoming.LOG.warn("SCENARIO leak menu {}: no class {} ({})", r, k, ex.toString());
            }
        }
        long deadline = System.nanoTime() + RESOLVE_LIMIT_MS * 1_000_000;
        int resolved = 0, skipped = 0;
        for (Row row : rows) {
            String n = row.name;
            if (n.startsWith("[") || n.indexOf('/') >= 0 || n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jdk.")
                    || n.startsWith("sun.") || n.startsWith("com.sun.")) continue;
            if (System.nanoTime() > deadline) {
                skipped++;
                continue;
            }
            Class<?> c;
            try {
                c = Class.forName(n, false, loader);
            } catch (VirtualMachineError ex) {
                throw ex;
            } catch (Throwable ex) {
                continue; // not visible to the game loader (the library has its own loader) - not a subclass of game classes
            }
            resolved++;
            for (Map.Entry<String, Class<?>> k : keys.entrySet()) {
                if (c != k.getValue() && k.getValue().isAssignableFrom(c)) {
                    long[] a = out.computeIfAbsent(k.getKey(), x -> new LinkedHashMap<>()).computeIfAbsent(n, x -> new long[2]);
                    a[0] += row.count;
                    a[1] += row.bytes;
                }
            }
        }
        if (skipped > 0) Incoming.LOG.warn("SCENARIO leak menu {}: subclasses - {} names sorted, {} did not have time in {} ms", r, resolved, skipped, RESOLVE_LIMIT_MS);
        return out;
    }

    private void dumpHeap() {
        Path file = dir.resolve("heap.hprof");
        if (Files.exists(file)) file = dir.resolve("heap-" + System.currentTimeMillis() + ".hprof");
        long t = System.nanoTime();
        try {
            ManagementFactory.getPlatformMBeanServer().invoke(new ObjectName("com.sun.management:type=HotSpotDiagnostic"), "dumpHeap",
                    new Object[] {file.toString(), true}, new String[] {String.class.getName(), boolean.class.getName()});
            Incoming.LOG.info("SCENARIO leak heap dump: {} MB for {} with → {}", Files.size(file) >> 20, (System.nanoTime() - t) / NANOS, dir.getParent().relativize(file));
        } catch (Exception | LinkageError ex) {
            Incoming.LOG.warn("SCENARIO leak heap dump: Heap snapshot not taken ({})", file, ex);
        }
    }

    /**
     * JFR old-object sampling: recording in this process with {@code jdk.OldObjectSample} (allocation stack and
     * {@code cutoff} "infinity"). Samples are allocations since recording start that remain alive; GC-root paths are
     * computed when the event is written, at recording stop and each {@link jdk.jfr.Recording#dump dump} (recording copy
     * inherits its settings), as with {@code jcmd JFR.dump path-to-gc-roots=true}; default {@code cutoff} ("0 ns") gives
     * only type and stack (checked on JDK 21 with generational ZGC: "infinity" gives the field chain and root).
     * VM thread searches for them on its own stack, requiring {@code -XX:VMThreadStackSize} (see {@link #PATH_STACK_KB}).
     * A dump writes to the shared recording stream, so the next dump file includes earlier events; the extract takes
     * events from the latest dump (all events of one dump share a start time).
     */
    private static final class OldObjects {
        private static final int DETAILS = 40, PER_HOLDER = 2, HOLDERS = 25, LOG_HOLDERS = 5, HOLDER_LINKS = 4, STACK = 8, CHAIN_HEAD = 8, CHAIN_TAIL = 14;
        private final jdk.jfr.Recording recording = new jdk.jfr.Recording();

        /** {@code paths} - search for paths to the roots during reset (you need a thread stack VM not less than {@link #PATH_STACK_KB}). */
        OldObjects(boolean paths) {
            recording.setName("incoming-leak");
            recording.enable("jdk.OldObjectSample").withStackTrace().with("cutoff", paths ? "infinity" : "0 ns");
            recording.setToDisk(true);
            recording.start();
        }

        void close() {
            recording.close();
        }

        private record Sample(String object, long size, long ageS, String root, List<String> chain, List<String> stack) {
            boolean world() {
                for (String m : WORLD_MARKS) {
                    if (object.contains(m) || root.contains(m)) return true;
                    for (String l : chain) if (l.contains(m)) return true;
                }
                return false;
            }

            /**
             * Who holds: the static field closest to the object (link “... : java.lang.Class Class Name: X” - class field X;
             *  further to the root it always has a class loader) and the type to which it refers; without a static field -
             * the last links of the path at the root and the root.
             */
            String holder() {
                for (int i = 1; i < chain.size(); i++) {
                    if (chain.get(i).contains("java.lang.Class Class Name: ")) return typeOf(chain.get(i - 1)) + " ← " + chain.get(i) + " (static field)";
                }
                List<String> tail = new ArrayList<>();
                for (String l : chain.subList(Math.max(0, chain.size() - HOLDER_LINKS), chain.size())) tail.add(plain(l));
                return String.join(" ← ", tail) + " | root " + root.replaceAll("\\d+", "#");
            }

            /** Numbers in arrays and sizes - without numbers, otherwise each sample has its own holder. */
            private static String plain(String link) {
                return link.replaceAll("\\[\\d+]", "[]").replaceAll("\\(\\d+\\)", "()").replaceAll("Size: \\d+", "Size: N");
            }

            /** The object type in the “field: type description” link. */
            private static String typeOf(String link) {
                int k = link.indexOf(" : ");
                String t = k < 0 ? link : link.substring(k + 3);
                int s = t.indexOf(' ');
                return plain(s < 0 ? t : t.substring(0, s));
            }
        }

        void dump(int r, Path dir) throws IOException {
            Path file = dir.resolve("old-" + r + ".jfr");
            long t = System.nanoTime();
            recording.dump(file);
            long took = (System.nanoTime() - t) / 1_000_000;
            List<jdk.jfr.consumer.RecordedEvent> all = new ArrayList<>();
            Instant last = Instant.MIN;
            for (jdk.jfr.consumer.RecordedEvent e : jdk.jfr.consumer.RecordingFile.readAllEvents(file)) {
                if (!e.getEventType().getName().equals("jdk.OldObjectSample")) continue;
                all.add(e);
                if (e.getStartTime().isAfter(last)) last = e.getStartTime();
            }
            List<Sample> samples = new ArrayList<>();
            int broken = 0;
            for (jdk.jfr.consumer.RecordedEvent e : all) {
                if (!e.getStartTime().equals(last)) continue;
                try {
                    samples.add(sample(e));
                } catch (RuntimeException ex) {
                    broken++;
                }
            }
            List<Sample> world = samples.stream().filter(Sample::world).toList();
            long rooted = samples.stream().filter(s -> !s.root.equals("N/A")).count();
            Map<String, List<Sample>> holders = new LinkedHashMap<>();
            for (Sample s : world) holders.computeIfAbsent(s.holder(), k -> new ArrayList<>()).add(s);
            List<Map.Entry<String, List<Sample>>> byCount = holders.entrySet().stream()
                    .sorted(Comparator.comparingInt((Map.Entry<String, List<Sample>> en) -> en.getValue().size()).reversed()).toList();
            Map<String, Integer> roots = new LinkedHashMap<>();
            for (Sample s : samples) roots.merge(s.root, 1, Integer::sum);

            List<String> out = new ArrayList<>();
            out.add(String.format(Locale.ROOT, "Sampling of old objects JFR, measurement %d: sample %d (reset %d ms), with a path to the root %d, through the world %d (%s)%s",
                    r, samples.size(), took, rooted, world.size(), String.join(", ", WORLD_MARKS), broken > 0 ? ", not disassembled " + broken : ""));
            out.add("");
            out.add("Roots, all sample:");
            roots.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(en -> out.add(String.format(Locale.ROOT, "  %4d × %s", en.getValue(), en.getKey())));
            out.add("");
            out.add("Holders sample through the world (nearest static field and the type it refers to, otherwise the latest " + HOLDER_LINKS
                    + " path links and root), by sample number, total " + byCount.size() + ":");
            for (int i = 0; i < Math.min(HOLDERS, byCount.size()); i++) {
                out.add(String.format(Locale.ROOT, "  %4d × %s", byCount.get(i).getValue().size(), byCount.get(i).getKey()));
            }
            out.add("");
            out.add("sample through the world - by " + PER_HOLDER + " per holder, up to " + DETAILS + " (the path is from the object to the root, like jfr print):");
            int shown = 0;
            for (Map.Entry<String, List<Sample>> en : byCount) {
                for (Sample s : en.getValue().subList(0, Math.min(PER_HOLDER, en.getValue().size()))) {
                    if (shown == DETAILS) break;
                    out.add(String.format(Locale.ROOT, "#%d %s, %d B, age %d s, root %s", ++shown, s.object, s.size, s.ageS, s.root));
                    List<String> chain = s.chain;
                    if (chain.size() > CHAIN_HEAD + CHAIN_TAIL) {
                        List<String> cut = new ArrayList<>(chain.subList(0, CHAIN_HEAD));
                        cut.add("... more " + (chain.size() - CHAIN_HEAD - CHAIN_TAIL) + " links");
                        cut.addAll(chain.subList(chain.size() - CHAIN_TAIL, chain.size()));
                        chain = cut;
                    }
                    for (String l : chain) out.add("    " + l);
                    if (!s.stack.isEmpty()) out.add("    allocation stack: " + String.join(" ← ", s.stack));
                }
            }
            if (shown < world.size()) out.add("... shown " + shown + " from " + world.size());
            Path txt = dir.resolve("old-" + r + ".txt");
            Files.write(txt, out);
            Incoming.LOG.info("SCENARIO leak menu {} jfr: sample {}, with a path to the root {}, through the world {}, holders {} (reset {} ms) → {}, {}", r, samples.size(),
                    rooted, world.size(), byCount.size(), took, dir.getParent().relativize(file), dir.getParent().relativize(txt));
            for (int i = 0; i < Math.min(LOG_HOLDERS, byCount.size()); i++) {
                Incoming.LOG.info("SCENARIO leak menu {} holder {} × {}", r, byCount.get(i).getValue().size(), byCount.get(i).getKey());
            }
        }

        private static Sample sample(jdk.jfr.consumer.RecordedEvent e) {
            jdk.jfr.consumer.RecordedObject object = e.getValue("object");
            Number elements = e.getValue("arrayElements");
            List<String> chain = new ArrayList<>();
            String type = object == null ? "?" : name(object, elements == null ? -1 : elements.longValue());
            chain.add(type);
            jdk.jfr.consumer.RecordedObject ref = object == null ? null : object.getValue("referrer");
            while (ref != null) {
                Number skip = ref.getValue("skip");
                if (skip != null && skip.longValue() > 0) chain.add("… (" + skip + " links missing)");
                String via = "?";
                long size = -1;
                jdk.jfr.consumer.RecordedObject array = ref.getValue("array");
                if (array != null) {
                    via = "[" + array.getValue("index") + "]";
                    Number n = array.getValue("size");
                    size = n == null ? -1 : n.longValue();
                }
                jdk.jfr.consumer.RecordedObject field = ref.getValue("field");
                if (field != null) via = field.getString("name");
                jdk.jfr.consumer.RecordedObject holder = ref.getValue("object");
                chain.add(via + " : " + (holder == null ? "?" : name(holder, size)));
                ref = holder == null ? null : holder.getValue("referrer");
            }
            jdk.jfr.consumer.RecordedObject root = e.getValue("root");
            String rootText = "N/A";
            if (root != null) {
                String d = root.getString("description");
                rootText = root.getValue("system") + " / " + root.getValue("type") + (d == null ? "" : " «" + d + "»");
            }
            List<String> stack = new ArrayList<>();
            jdk.jfr.consumer.RecordedStackTrace st = e.getStackTrace();
            if (st != null) {
                for (jdk.jfr.consumer.RecordedFrame f : st.getFrames()) {
                    if (stack.size() == STACK) break;
                    jdk.jfr.consumer.RecordedMethod m = f.getMethod();
                    stack.add(m.getType().getName() + "." + m.getName() + ":" + f.getLineNumber());
                }
            }
            return new Sample(type, e.hasField("objectSize") ? e.getLong("objectSize") : -1, e.getDuration("objectAge").toSeconds(), rootText, chain, stack);
        }

        /** Path object type and description (for a class - the class name: holds a static field; for a thread - the name of the thread). */
        private static String name(jdk.jfr.consumer.RecordedObject o, long arraySize) {
            jdk.jfr.consumer.RecordedClass c = o.getClass("type");
            String n = c == null ? "?" : c.getName();
            if (arraySize >= 0 && n.startsWith("[")) n = n + "(" + arraySize + ")";
            String d = o.getString("description");
            return d == null ? n : n + " " + d;
        }
    }
}
