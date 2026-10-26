package ua.zentix.incoming.legacy;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.incoming.Incoming;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Worlds with the {@code airstrike} ID. Blocks, items, entities, block entities, effects, attachments and components are
 * stored under {@code airstrike:*} names: registries find them via {@code airstrike:*} → {@code incoming:*} aliases
 * ({@link DeferredRegister#addAlias}, NeoForge's mechanism for renamed entries). Saved world data
 * ({@code data/airstrike_*.dat}: nuclear detonations, power grid, salvos, flights outside the world) moves to the new name
 * before worlds load: detonation numbers used by chunks to remember applied damage continue instead of restarting.
 * NeoForge reads settings ({@code serverconfig/airstrike-server.toml}) before any mod event — rename them manually
 * (3.0.0 release notes).
 */
public final class OldNamespace {
    public static final String ID = "airstrike";
    private static final String DATA_PREFIX = ID + "_";

    private OldNamespace() {}

    /** Before {@code register(modBus)}: registry entries have already been created by their static fields. */
    public static <T> void alias(DeferredRegister<T> register) {
        for (DeferredHolder<T, ? extends T> entry : register.getEntries()) {
            register.addAlias(ResourceLocation.fromNamespaceAndPath(ID, entry.getId().getPath()), entry.getId());
        }
    }

    /** {@link ServerAboutToStartEvent} precedes world load: their data is read under the new name. */
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        MinecraftServer server = event.getServer();
        try {
            for (Path data : dataFolders(server)) adoptSavedData(data);
        } catch (IOException e) {
            Incoming.LOG.error("Could not rename the world data of the Airstrike mod in {}", server.getWorldPath(LevelResource.ROOT), e);
        }
    }

    /**
     * Dimension {@code data} directories where the game keeps them ({@link DimensionType#getStorageFolder}): Overworld —
     * root, Nether — {@code DIM-1}, End — {@code DIM1}, modded dimensions — {@code dimensions/<ns>/<path>}.
     */
    public static List<Path> dataFolders(MinecraftServer server) {
        Path root = server.getWorldPath(LevelResource.ROOT);
        return server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).registryKeySet().stream()
                .map(stem -> DimensionType.getStorageFolder(Registries.levelStemToLevel(stem), root).resolve("data")).toList();
    }

    /** {@code airstrike_<name>.dat} → {@code incoming_<name>.dat}, if the new file does not exist yet. */
    static void adoptSavedData(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return;
        List<Path> old;
        try (Stream<Path> files = Files.list(folder)) {
            old = files.filter(p -> p.getFileName().toString().startsWith(DATA_PREFIX) && p.getFileName().toString().endsWith(".dat")).toList();
        }
        for (Path file : old) {
            Path target = folder.resolve(Incoming.MOD_ID + "_" + file.getFileName().toString().substring(DATA_PREFIX.length()));
            if (Files.exists(target)) continue;
            Files.move(file, target);
            Incoming.LOG.info("World data of the Airstrike mod renamed: {} -> {}", file.getFileName(), target.getFileName());
        }
    }
}
