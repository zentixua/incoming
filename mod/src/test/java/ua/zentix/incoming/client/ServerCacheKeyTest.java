package ua.zentix.incoming.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerCacheKeyTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @CsvSource({
            "host, host",
            "host:25565, host",
            "HOST:25565, host",
            "host:25571, host_25571",
            "192.0.2.1:25565, 192.0.2.1",
            "[2001:DB8::1], 2001_db8__1",
            "[2001:db8::1]:25565, 2001_db8__1",
            "[2001:db8::1]:25571, 2001_db8__1_25571",
            "play.example.org, play.example.org"
    })
    void parsedAddressKey(String address, String expected) {
        assertEquals(expected, ServerCacheKey.fromAddress(address).key());
    }

    @Test
    void hostCaseDoesNotDependOnClientLocale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("incoming.example", ServerCacheKey.fromAddress("INCOMING.EXAMPLE:25565").key());
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void newCacheUsesCanonicalDirectoryWithoutCreatingIt() {
        assertEquals(root.resolve("host"), ServerCacheKey.fromAddress("HOST:25565").directory(root));
        assertFalse(Files.exists(root.resolve("host")));
    }

    @Test
    void canonicalDirectoryWinsOverLegacyEvenWhenEmpty() throws IOException {
        Path canonical = Files.createDirectory(root.resolve("host"));
        Path legacy = Files.createDirectory(root.resolve("host_25565"));
        Files.writeString(legacy.resolve("terrain.bin"), "old terrain");
        assertEquals(canonical, ServerCacheKey.fromAddress("host:25565").directory(root));
        assertEquals("old terrain", Files.readString(legacy.resolve("terrain.bin")));
    }

    @Test
    void legacyDefaultPortCacheIsSharedByEverySpellingAndStaysInPlace() throws IOException {
        Path legacy = Files.createDirectory(root.resolve("host_25565"));
        Path terrain = Files.writeString(legacy.resolve("terrain.bin"), "old terrain");
        for (String address : new String[]{"host", "host:25565", "HOST:25565"}) {
            assertEquals(legacy, ServerCacheKey.fromAddress(address).directory(root));
        }
        assertEquals("old terrain", Files.readString(terrain));
        assertFalse(Files.exists(root.resolve("host")));
    }

    @Test
    void legacyCacheDoesNotLeakAcrossPorts() throws IOException {
        Files.createDirectory(root.resolve("host_25565"));
        assertEquals(root.resolve("host_25571"), ServerCacheKey.fromAddress("host:25571").directory(root));
        assertFalse(Files.exists(root.resolve("host_25571")));
    }

    @Test
    void reusesOriginalHostSpellingWhenItIsTheOnlyCache() throws IOException {
        Path legacy = Files.createDirectory(root.resolve("HOST_25565"));
        assertEquals(legacy, ServerCacheKey.fromAddress("HOST:25565").directory(root));
        assertTrue(Files.isDirectory(legacy));
    }

    @Test
    void legacyBracketedIpv6WithDefaultPortIsFoundWithoutAnExplicitPort() throws IOException {
        Path legacy = Files.createDirectory(root.resolve("_2001_db8__1__25565"));
        assertEquals(legacy, ServerCacheKey.fromAddress("[2001:DB8::1]").directory(root));
        assertEquals(legacy, ServerCacheKey.fromAddress("[2001:db8::1]:25565").directory(root));
    }
}
