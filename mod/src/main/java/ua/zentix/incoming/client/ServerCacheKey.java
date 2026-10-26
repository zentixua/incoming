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
        boolean ipv6 = host.contains(":");
        // Скобки отделяют весь IPv6-хост от порта; обратимое кодирование не смешивает ':' с '_' и '%' в зоне.
        String keyHost = ipv6 ? "[" + URLEncoder.encode(host, StandardCharsets.UTF_8) + "]" : safe(host);
        String key = keyHost + (port == DEFAULT_PORT ? "" : "_" + port);
        String oldHost = ipv6 ? "[" + host + "]" : host;
        String explicit = safe(oldHost + ":" + port);
        // Разобранные варианты имеют приоритет; исходное старое имя сохраняет также кэш IDN и иных написаний.
        String original = safe(address);
        List<String> legacy = port != DEFAULT_PORT ? List.of(explicit, original)
                : ipv6 ? List.of(safe(oldHost), explicit, safe(host), original) : List.of(safe(host), explicit, original);
        return new ServerCacheKey(key, legacy);
    }

    /**
     * Новая папка имеет приоритет. Если есть только прежняя, читаем и сохраняем в ней: создание новой после записи
     * одного измерения спрятало бы остальные измерения прежней папки при следующем входе. Файлы не переносим.
     * Прежние имена ищем без учёта регистра. Без порта — раньше явного 25565; внутри варианта точное имя — раньше
     * отличающихся регистром, затем имена по алфавиту. После разобранных вариантов — старое имя от исходной строки.
     * Вызывается потоком ввода-вывода, результат хранится до конца работы с кэшем.
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
            // Первый вход: корневой папки кэша ещё нет. Прочие ошибки чтения не прячем созданием новой папки.
        }
        return canonical;
    }

    private static String safe(String value) {
        return UNSAFE.matcher(value).replaceAll("_");
    }
}
