package ua.zentix.hotswap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Hot swap agent: changes are given to one batch when compilation is finished, with class names. */
class HotswapAgentTest {
    @Test
    void namesClassesByPath() {
        assertEquals("a.b.C$D", HotswapAgent.className("a/b/C$D.class"));
    }

    @Test
    void batchWaitsUntilWritingStops(@TempDir Path dir) throws Exception {
        Path a = write(dir.resolve("x/A.class"), 1000);
        HotswapAgent.Watcher w = new HotswapAgent.Watcher(List.of(dir));
        assertNull(w.poll(), "nothing has changed");
        assertTrue(w.idle(), "waits for nothing");
        write(a, 2000);
        assertNull(w.poll(), "is written");
        assertFalse(w.idle(), "change awaits replacement");
        Path b = write(dir.resolve("x/B$1.class"), 3000);
        assertNull(w.poll(), "still being written");
        assertNull(w.poll(), "quietly one poll");
        Map<String, Path> batch = w.poll();
        assertEquals(Map.of("x.A", a, "x.B$1", b), batch);
        assertNull(w.poll(), "batch given");
        assertTrue(w.idle(), "caught up");
    }

    private static Path write(Path p, long time) throws Exception {
        Files.createDirectories(p.getParent());
        Files.write(p, new byte[]{1, 2, 3});
        Files.setLastModifiedTime(p, FileTime.fromMillis(time));
        return p;
    }
}
