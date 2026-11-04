package ua.zentix.incoming.nuclear.world;

import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Content identity for prepared pages across clean restart and data-pack changes. Runs off tick. */
final class RuinPolicy {
    private RuinPolicy() {}

    static String fingerprint(ResourceManager resources, String mods) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("incoming-preparation/2\n".getBytes(StandardCharsets.UTF_8));
            digest.update(mods.getBytes(StandardCharsets.UTF_8));
            // Tags, data maps and biome data can alter an exact plan without changing geometry or mod version.
            // A pack root is not a valid PathPackResources path. Hash the inputs used by ruin properties.
            var files = new java.util.HashMap<net.minecraft.resources.ResourceLocation, java.util.List<net.minecraft.server.packs.resources.Resource>>();
            for (String path : new String[]{"tags", "data_maps", "worldgen/biome"})
                files.putAll(resources.listResourceStacks(path, id -> id.getPath().endsWith(".json")));
            var keys = new java.util.ArrayList<>(files.keySet());
            keys.sort(java.util.Comparator.comparing(Object::toString));
            byte[] buffer = new byte[8192];
            for (var key : keys) {
                digest.update(key.toString().getBytes(StandardCharsets.UTF_8));
                for (var resource : files.get(key)) {
                    digest.update(resource.sourcePackId().getBytes(StandardCharsets.UTF_8));
                    try (var in = resource.open()) {
                        for (int n; (n = in.read(buffer)) >= 0; ) digest.update(buffer, 0, n);
                    }
                    digest.update((byte) 0);
                }
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
