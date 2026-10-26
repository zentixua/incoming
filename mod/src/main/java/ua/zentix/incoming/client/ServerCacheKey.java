package ua.zentix.incoming.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** One client server-cache key: Minecraft address without DNS/SRV lookup, or the world folder in singleplayer. */
public record ServerCacheKey(String key, List<String> legacyKeys) {
    private static final int DEFAULT_PORT = 25565;
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._-]");

    public static ServerCacheKey current() {
        Minecraft mc = Minecraft.getInstance();
        ServerData data = mc.getCurrentServer();
        if (data != null && data.ip != null) return fromAddress(data.ip);
        IntegratedServer sp = mc.getSingleplayerServer();
        Path root = sp == null ? null : sp.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
        return new ServerCacheKey("local_" + (root == null ? "world" : safe(root.toString())), List.of());
    }

    public static ServerCacheKey fromAddress(String address) {
        ServerAddress parsed = ServerAddress.parseString(address);
        String host = parsed.getHost().toLowerCase(Locale.ROOT);
        int port = parsed.getPort();
        boolean ipv6 = host.contains(":");
        // Brackets separate the entire IPv6 host from the port; reversible encoding keeps ':' distinct from '_' and zone '%'.
        String keyHost = ipv6 ? "[" + URLEncoder.encode(host, StandardCharsets.UTF_8) + "]" : safe(host);
        String key = keyHost + (port == DEFAULT_PORT ? "" : "_" + port);
        String oldHost = ipv6 ? "[" + host + "]" : host;
        String explicit = safe(oldHost + ":" + port);
        // Parsed variants take priority; the original old name also preserves caches for IDNs and other spellings.
        String original = safe(address);
        List<String> legacy = port != DEFAULT_PORT ? List.of(explicit, original)
                : ipv6 ? List.of(safe(oldHost), explicit, safe(host), original) : List.of(safe(host), explicit, original);
        return new ServerCacheKey(key, legacy);
    }

    /**
     * The new folder takes priority. If only the old one exists, read and save there: creating a new one after writing
     * one dimension would hide the old folder's other dimensions on next login. Do not move files.
     * Find old names case-insensitively. No port before explicit 25565; within a variant, exact name before case
     * variants, then alphabetically. After parsed variants, use the old name derived from the original string.
     * Called by the I/O thread; result retained for the rest of the cache operation.
     */
    public Path directory(Path root) throws IOException {
        Path canonical = root.resolve(key);
        if (Files.exists(canonical) || legacyKeys.isEmpty()) return canonical;
        try (var children = Files.list(root)) {
            List<Path> directories = children.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
            for (String legacy : legacyKeys) {
                Path exact = root.resolve(legacy);
                if (directories.contains(exact)) return exact;
                for (Path old : directories) {
                    if (old.getFileName().toString().equalsIgnoreCase(legacy)) return old;
                }
            }
        } catch (NoSuchFileException e) {
            // First login: cache root does not yet exist. Do not hide other read errors by creating a new folder.
        }
        return canonical;
    }

    private static String safe(String value) {
        return UNSAFE.matcher(value).replaceAll("_");
    }
}
