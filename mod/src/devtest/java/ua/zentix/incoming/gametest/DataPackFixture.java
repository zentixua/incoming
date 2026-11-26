package ua.zentix.incoming.gametest;

import net.minecraft.SharedConstants;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * A datapack as a pack maker ships one: files written into the world's {@code datapacks/} folder and enabled the way
 * {@code /datapack enable} does (rescan, then reload with the pack on top), then disabled and deleted again. Called on
 * the server thread, {@code MinecraftServer.reloadResources} finishes inside the call ({@code managedBlock}), holding
 * the thread 2–4 s with Create; it replaces tags, data maps, recipes and advancements server-wide, so each test that
 * uses it has its own batch, and leaves the server's data as it found it, also when it fails. Every error becomes a
 * {@link GameTestAssertException}: anything else thrown in a test sequence crashes the whole GameTest server.
 */
public final class DataPackFixture {
    private DataPackFixture() {}

    /**
     * Runs {@code before} on the server's own data, loads pack {@code name} with {@code files} (path under the pack
     * root → content), runs {@code with}, unloads the pack and runs {@code after} (the effect is gone again), then
     * succeeds.
     */
    public static void run(GameTestHelper h, String name, Map<String, String> files, Runnable before, Runnable with, Runnable after) {
        MinecraftServer server = h.getLevel().getServer();
        List<CompletableFuture<Void>> reloads = new ArrayList<>();
        StrikeGameTests.afterTest(h, () -> {
            // a failure before the pack was taken off leaves it on: take it off for the next batches
            if (reloads.size() == 1) disable(server, name);
        });
        h.startSequence()
                .thenExecute(() -> {
                    before.run();
                    reloads.add(enable(server, name, files));
                })
                .thenWaitUntil(() -> done(reloads.get(0), "loading datapack " + name))
                .thenExecute(with)
                .thenExecute(() -> reloads.add(disable(server, name)))
                .thenWaitUntil(() -> done(reloads.get(1), "unloading datapack " + name))
                .thenExecute(after)
                .thenSucceed();
    }

    /** The reload is over (it completes within the call; the check guards a caller off the server thread). */
    private static void done(CompletableFuture<Void> reload, String what) {
        if (!reload.isDone()) throw new GameTestAssertException(what + ": not finished");
        if (reload.isCompletedExceptionally()) {
            try {
                reload.join();
            } catch (RuntimeException e) {
                throw new GameTestAssertException(what + " failed: " + e);
            }
        }
    }

    private static String id(String name) {
        return "file/" + name;
    }

    private static Path dir(MinecraftServer server, String name) {
        return server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(name);
    }

    static CompletableFuture<Void> enable(MinecraftServer server, String name, Map<String, String> files) {
        Path root = dir(server, name);
        try {
            delete(root);
            write(root.resolve("pack.mcmeta"), "{\"pack\": {\"pack_format\": " + SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA)
                    + ", \"description\": \"Incoming GameTest " + name + "\"}}");
            for (var f : files.entrySet()) write(root.resolve(f.getKey()), f.getValue());
        } catch (IOException e) {
            throw new GameTestAssertException("writing datapack " + name + ": " + e);
        }
        PackRepository repo = server.getPackRepository();
        repo.reload();
        if (!repo.isAvailable(id(name))) throw new GameTestAssertException("datapack " + id(name) + " not found in " + repo.getAvailableIds());
        List<String> selected = new ArrayList<>(repo.getSelectedIds());
        selected.remove(id(name));
        selected.add(id(name));
        return server.reloadResources(selected);
    }

    static CompletableFuture<Void> disable(MinecraftServer server, String name) {
        PackRepository repo = server.getPackRepository();
        List<String> selected = new ArrayList<>(repo.getSelectedIds());
        selected.remove(id(name));
        return server.reloadResources(selected).thenRun(() -> {
            try {
                delete(dir(server, name));
            } catch (IOException e) {
                // completes the future exceptionally: done() reports it as a test failure
                throw new UncheckedIOException(e);
            }
            repo.reload();
        });
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void delete(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> all = Files.walk(root)) {
            for (Path p : all.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
