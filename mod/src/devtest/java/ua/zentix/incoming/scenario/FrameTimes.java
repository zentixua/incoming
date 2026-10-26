package ua.zentix.incoming.scenario;

import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import ua.zentix.incoming.Incoming;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Script client frame time ({@code -Dincoming.frametimes=true}): each frame in the game is the line “tick ms” in
 * logs/frametimes.txt (tick is the game time of the world, it shows what happened in the frame). The summary (average FPS, 1% worst,
 *  worst frame) is calculated according to the file, for example, for the salvo time.
 */
final class FrameTimes {
    private final Writer out;
    private long last;

    private FrameTimes(Writer out) {
        this.out = out;
    }

    static void startIfRequested() {
        if (!Boolean.getBoolean("incoming.frametimes")) return;
        Path file = FMLPaths.GAMEDIR.get().resolve("logs").resolve("frametimes.txt");
        try {
            Files.createDirectories(file.getParent());
            FrameTimes f = new FrameTimes(Files.newBufferedWriter(file));
            NeoForge.EVENT_BUS.addListener(f::onFrame);
            Runtime.getRuntime().addShutdownHook(new Thread(f::close));
        } catch (IOException e) {
            Incoming.LOG.warn("SCENARIO frametimes: does not open {}", file, e);
        }
    }

    private void onFrame(RenderFrameEvent.Post e) {
        long now = System.nanoTime();
        Minecraft mc = Minecraft.getInstance();
        if (last != 0 && mc.level != null) {
            try {
                out.write(mc.level.getGameTime() + " " + (now - last) / 1000 / 1000.0 + "\n");
            } catch (IOException ignored) {
                // recording by frame is a test, not a game: the missing line is not important
            }
        }
        last = now;
    }

    private void close() {
        try {
            out.close();
        } catch (IOException ignored) {
            // exit the game
        }
    }
}
