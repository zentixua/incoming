package ua.zentix.incoming.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A region without data reads as absent, so reads never create region files ({@link RegionFiles}). */
class RegionFilesTest {
    @Test
    void onlyRegionFilesWithDataArePresent(@TempDir Path folder) throws IOException {
        assertFalse(RegionFiles.present(folder, 0, 0), "no file");
        Files.createFile(folder.resolve("r.0.0.mca"));
        assertFalse(RegionFiles.present(folder, 31, 31), "empty file left by a read of a never-saved region");
        Files.write(folder.resolve("r.0.0.mca"), new byte[8192]);
        assertTrue(RegionFiles.present(folder, 5, 7), "saved region");
        assertFalse(RegionFiles.present(folder, 32, 0), "next region");
        Files.write(folder.resolve("r.-1.0.mca"), new byte[8192]);
        assertTrue(RegionFiles.present(folder, -1, 0), "chunk -1 is in region -1, as vanilla floors");
    }

    @Test
    void regionSavedLaterIsSeen(@TempDir Path folder) throws IOException {
        assertFalse(RegionFiles.present(folder, -40, 70));
        Files.write(folder.resolve("r.-2.2.mca"), new byte[8192]);
        assertTrue(RegionFiles.present(folder, -40, 70), "an absent answer is not remembered");
    }
}
