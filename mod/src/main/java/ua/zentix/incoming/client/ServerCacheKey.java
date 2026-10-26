package ua.zentix.incoming.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Один ключ серверных кэшей клиента: адрес Minecraft без DNS/SRV-поиска, а у одиночной игры — папка мира. */
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
        String key = safe(host) + (port == DEFAULT_PORT ? "" : "_" + port);
        // Прежняя папка с явным портом нужна и при входе без него; исходное написание — для старых HOST и IPv6.
        String explicit = (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
        return new ServerCacheKey(key, List.of(safe(explicit), safe(address)));
    }

    /**
     * Новая папка имеет приоритет. Если есть только прежняя, читаем и сохраняем в ней: создание новой после записи
     * одного измерения спрятало бы остальные измерения прежней папки при следующем входе. Файлы не переносим.
     * Вызывается потоком ввода-вывода, результат хранится до конца работы с кэшем.
     */
    public Path directory(Path root) {
        Path canonical = root.resolve(key);
        if (Files.exists(canonical)) return canonical;
        for (String legacy : legacyKeys) {
            Path old = root.resolve(legacy);
            if (Files.isDirectory(old)) return old;
        }
        return canonical;
    }

    private static String safe(String value) {
        return UNSAFE.matcher(value).replaceAll("_");
    }
}
