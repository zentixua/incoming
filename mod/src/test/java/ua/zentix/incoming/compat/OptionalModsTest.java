package ua.zentix.incoming.compat;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.Incoming;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Optional bridges stay unloaded without their mod: a class naming a type keeps its internal name in its constant pool,
 * so a scan of the compiled main classes finds every class that could load the foreign API. Only the bridge's own package
 * names it; outside the bridge only {@code Incoming} names the CC:Tweaked bridge, behind its {@code isLoaded} guard; nothing
 * names the Jade bridge (Jade finds it by its annotation). Without the mod the guarded line never runs, so neither
 * package's classes are ever loaded (the GameTest runtime has neither mod and loads every other class).
 */
class OptionalModsTest {
    /** Foreign API package → the only Incoming package allowed to name it. */
    private static final Map<String, String> BRIDGES = Map.of(
            "snownee/jade/", "ua/zentix/incoming/compat/jade/",
            "dan200/computercraft/", "ua/zentix/incoming/compat/computercraft/",
            "mekanism/api/", "ua/zentix/incoming/compat/mekanism/");

    @Test
    void foreignApisOnlyInTheirBridge() throws IOException, URISyntaxException {
        Path root = Path.of(Incoming.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> wrong = new ArrayList<>();
        List<String> namingBridges = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                scanned++;
                String name = root.relativize(f).toString().replace('\\', '/');
                String pool = new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);
                for (Map.Entry<String, String> b : BRIDGES.entrySet()) {
                    boolean inBridge = name.startsWith(b.getValue());
                    if (!inBridge && pool.contains(b.getKey())) wrong.add(name + " names " + b.getKey());
                    if (!inBridge && pool.contains(b.getValue())) namingBridges.add(name + " -> " + b.getValue());
                }
            }
        }
        assertTrue(scanned > 500, "main classes not found under " + root + ": " + scanned);
        assertEquals(List.of(), wrong, "a foreign API named outside its bridge");
        assertEquals(List.of("ua/zentix/incoming/Incoming.class -> ua/zentix/incoming/compat/computercraft/",
                        "ua/zentix/incoming/Incoming.class -> ua/zentix/incoming/compat/mekanism/"),
                namingBridges.stream().sorted().toList(), "a bridge named outside Incoming's guarded lines");
    }

    @Test
    void incomingNamesBridgesOnlyBehindTheirGuard() throws IOException {
        List<String> lines = Files.readAllLines(Path.of("src/main/java/ua/zentix/incoming/Incoming.java"));
        for (String bridge : List.of("compat.computercraft", "compat.mekanism")) {
            List<String> naming = lines.stream().filter(l -> l.contains(bridge)).toList();
            assertEquals(1, naming.size(), bridge + " named on " + naming);
            String mod = bridge.equals("compat.computercraft") ? "computercraft" : "mekanism";
            assertTrue(naming.getFirst().strip().startsWith("if (ModList.get().isLoaded(\"" + mod + "\")) "), "unguarded: " + naming.getFirst());
        }
        assertFalse(lines.stream().anyMatch(l -> l.contains("compat.jade")), "Incoming names the Jade bridge");
    }
}
