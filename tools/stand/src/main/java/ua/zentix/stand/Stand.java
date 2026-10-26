package ua.zentix.stand;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Scene bench (tools/stand/README.md): windowless client executes plan steps in a fresh flat singleplayer
 * world ({@code stand-plan.json} in game directory, written by stand.py from scenes.json), logging to
 * {@code stand-out/}: per-frame CPU/GPU stages ({@link FrameClock}), integrated-server ticks/hits
 * ({@link ServerWatch}), frames and probe lines. No Incoming dependency: commands, entity types and probe
 * method names live in the plan, so one bench captures any Incoming jar. Without a plan the mod does nothing.
 */
@Mod(value = Stand.ID, dist = Dist.CLIENT)
public final class Stand {
    public static final String ID = "stand";
    public static final Logger LOG = LogUtils.getLogger();
    /** Origin of all bench times (client/integrated server share the JVM System.nanoTime clock). */
    public static final long T0 = System.nanoTime();

    public Stand() {
        Path game = FMLPaths.GAMEDIR.get();
        Path plan = game.resolve("stand-plan.json");
        if (!Files.isRegularFile(plan)) {
            LOG.info("STAND: no stand-plan.json in {} — idle", game);
            return;
        }
        try {
            Out out = new Out(game.resolve("stand-out"));
            new Director(Plan.read(plan), out);
        } catch (IOException | RuntimeException e) {
            LOG.error("STAND: plan {} not started", plan, e);
        }
    }

    /** Milliseconds since {@link #T0}. */
    static double ms(long nanos) {
        return (nanos - T0) / 1e6;
    }
}
