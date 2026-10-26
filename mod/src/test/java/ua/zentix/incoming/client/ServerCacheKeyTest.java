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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
            "[2001:DB8::1], [2001%3Adb8%3A%3A1]",
            "[2001:db8::1]:25565, [2001%3Adb8%3A%3A1]",
            "[2001:db8::1]:25571, [2001%3Adb8%3A%3A1]_25571",
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
    void newCacheUsesCanonicalDirectoryWithoutCreatingIt() throws IOException {
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

    @Test
    void ipv6HostAndPortDoNotShareTerrainEvenWithTheSameSeed() throws IOException {
        var withPort = ServerCacheKey.fromAddress("[2001:db8::1]:1234");
        var longerHost = ServerCacheKey.fromAddress("[2001:db8::1:1234]");
        assertNotEquals(withPort.key(), longerHost.key());
        Path first = withPort.directory(root).resolve("world-1234/minecraft_overworld.bin");
        Files.createDirectories(first.getParent());
        Files.writeString(first, "first server");
        Path second = longerHost.directory(root).resolve("world-1234/minecraft_overworld.bin");
        assertFalse(Files.exists(second), "другой хост не должен читать файл сервера с явным портом");
        Files.createDirectories(second.getParent());
        Files.writeString(second, "second server");
        assertEquals("first server", Files.readString(first));
        assertEquals("second server", Files.readString(second));
    }

    @ParameterizedTest
    @CsvSource({
            "HOST_25565, HOST:25565, host",
            "HOST, host:25565, HOST",
            "hOsT_25571, host:25571, HOST:25571",
            "_2001_db8__1_, [2001:db8::1], [2001:db8::1]:25565",
            "_2001_DB8__1_, [2001:db8::1], [2001:db8::1]:25565",
            "_2001_DB8__1__25565, [2001:db8::1]:25565, [2001:db8::1]",
            "_2001_DB8__1__1234, [2001:db8::1]:1234, [2001:DB8::1]:1234",
            "2001_DB8__1, 2001:db8::1, [2001:db8::1]:25565"
    })
    void legacyFolderSurvivesReconnectsAndSaves(String folder, String first, String second) throws IOException {
        Path legacy = Files.createDirectory(root.resolve(folder));
        Path terrain = legacy.resolve("world-1234/minecraft_overworld.bin");
        Files.createDirectories(terrain.getParent());
        String saved = "old terrain";
        Files.writeString(terrain, saved);
        Path otherDimension = Files.writeString(legacy.resolve("world-1234/minecraft_the_end.bin"), "other dimension");
        String[] addresses = {first, second, first.toUpperCase(Locale.ROOT), second.toLowerCase(Locale.ROOT), first};
        for (int i = 0; i < addresses.length; i++) {
            Path selected = ServerCacheKey.fromAddress(addresses[i]).directory(root);
            assertEquals(legacy, selected, addresses[i]);
            Path file = selected.resolve("world-1234/minecraft_overworld.bin");
            assertEquals(saved, Files.readString(file), "при новом входе читается результат предыдущего сохранения");
            saved = "saved after join " + i;
            Files.writeString(file, saved);
        }
        assertEquals(saved, Files.readString(terrain));
        assertEquals("other dimension", Files.readString(otherDimension));
        assertFalse(Files.exists(root.resolve(ServerCacheKey.fromAddress(first).key())), "новая пустая папка не появляется");
    }

    @Test
    void multipleLegacyFoldersHaveTheSamePriorityForEveryAddressSpelling() throws IOException {
        Files.createDirectory(root.resolve("hOsT_25565"));
        Path upper = Files.createDirectory(root.resolve("HOST_25565"));
        String[] addresses = {"host", "HOST:25565", "hOsT:25565", "HOST"};
        for (String address : addresses) {
            assertEquals(upper, ServerCacheKey.fromAddress(address).directory(root));
        }
        Path exact = Files.createDirectory(root.resolve("host_25565"));
        for (String address : addresses) {
            assertEquals(exact, ServerCacheKey.fromAddress(address).directory(root));
        }
        Path unported = Files.createDirectory(root.resolve("HOST"));
        for (String address : addresses) {
            assertEquals(unported, ServerCacheKey.fromAddress(address).directory(root));
        }
    }

    @Test
    void missingCacheRootCanBeCreatedByALaterSave() throws IOException {
        Path missing = root.resolve("not-created-yet");
        assertEquals(missing.resolve("host"), ServerCacheKey.fromAddress("host").directory(missing));
        assertFalse(Files.exists(missing));
    }

    @Test
    void failedDirectoryLookupDoesNotSilentlyStartANewCache() throws IOException {
        Path notDirectory = Files.writeString(root.resolve("not-a-directory"), "keep");
        assertThrows(IOException.class, () -> ServerCacheKey.fromAddress("host").directory(notDirectory));
        assertEquals("keep", Files.readString(notDirectory));
    }
}
