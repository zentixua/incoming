package ua.zentix.incoming.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** TEST ONLY: telemetry must discard excess evidence rather than grow memory/disk or overwrite a run. */
class MtB7LogTest {
    @TempDir Path directory;

    @Test void overflowIsAccountedAndUtf8DiskOutputIsBounded() throws Exception {
        Path path = directory.resolve("bounded.csv");
        var log = new MtB7Log(path, "header", 256);
        for (int i = 0; i < 10000; i++) log.add("measurement,é," + i);
        log.close();
        var rows = Files.readAllLines(path);
        long saved = rows.stream().filter(row -> row.startsWith("measurement,")).count();
        assertEquals(10000, saved + log.dropped.get());
        assertTrue(log.dropped.get() > 0);
        assertTrue(Files.size(path) <= 256 + 32, "fixed final drop footer is the only allowance");
        assertEquals("# dropped=" + log.dropped.get(), rows.getLast());
    }

    @Test void oversizeAndClosedRowsAreDroppedAndExistingEvidenceCannotBeOverwritten() throws Exception {
        Path path = directory.resolve("existing.csv");
        var log = new MtB7Log(path, "header");
        log.add("x".repeat(8193));
        log.add("kept");
        log.close();
        log.add("late");
        assertEquals(2, log.dropped.get());
        assertTrue(Files.readString(path).contains("kept\n"));
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> new MtB7Log(path, "replacement"));
        assertTrue(Files.readString(path).startsWith("header\n"));
    }
}
