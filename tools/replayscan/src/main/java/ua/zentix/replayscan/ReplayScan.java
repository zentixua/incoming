package ua.zentix.replayscan;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import ua.zentix.replayscan.film.Film;

import java.nio.file.Path;

/**
 * Analysis of a Flashback replay without a window (README.md): after loading, the client opens a replay, starts it with
 * {@link Settings#speed} and does not draw; the replay server at the end of each tick gives the world {@link Scan}; reached the end -
 *  tables are closed, the game exits. With {@code -Dreplayscan.film} - shooting according to plan ({@link Film}); without these properties, the
 *  mod does nothing.
 */
@Mod(value = "replayscan", dist = Dist.CLIENT)
public final class ReplayScan {
    static final Logger LOG = LogUtils.getLogger();
    /** How many seconds without a replay progress - end with an error (meta.json: complete - false). */
    private static final int STALL_SECONDS = 300;

    private enum Phase { WAIT, OPENING, PLAYING, CLOSING }

    private final Settings settings;
    private FlashbackLink link;
    private volatile Phase phase = Phase.WAIT;
    private volatile Scan scan;
    private volatile Throwable failure;
    private long lastProgress = System.nanoTime(), lastLog = System.nanoTime();
    private int lastTick = -1;

    public ReplayScan(IEventBus modBus) {
        // shooting according to plan (README.md, “Shooting”) - instead of analysis
        String film = System.getProperty("replayscan.film");
        if (film != null && !film.isBlank()) {
            settings = null;
            Film.start(Path.of(film), modBus);
            return;
        }
        if (System.getProperty("replayscan.replay", "").isBlank()) {
            settings = null;
            return;
        }
        Settings read = null;
        try {
            read = Settings.read(FMLPaths.GAMEDIR.get());
            link = FlashbackLink.find();
        } catch (ReflectiveOperationException | LinkageError e) {
            LOG.error("Replay scan: Flashback 0.39 is required ({})", e.toString());
            read = null;
        } catch (RuntimeException e) {
            LOG.error("Replay scan: bad setting ({})", e.toString());
            read = null;
        }
        settings = read;
        if (settings == null) {
            // a client without a window will not exit on its own: parsing will not begin - exit as soon as the game has loaded
            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
                if (Minecraft.getInstance().getOverlay() == null) Minecraft.getInstance().stop();
            });
            return;
        }
        LOG.info("Replay scan: {} -> {} at {} ticks/s", settings.replay(), settings.out(), settings.speed());
        NeoForge.EVENT_BUS.addListener(this::clientTick);
        NeoForge.EVENT_BUS.addListener(this::serverTick);
        // a game crash (your own or any mod) comes out via System.exit: tables and meta.json are added to the output
        Runtime.getRuntime().addShutdownHook(new Thread(this::closeScan, "replayscan-exit"));
    }

    private void clientTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        try {
            switch (phase) {
                case WAIT -> {
                    // loading has ended: the title or the screen on top of it (the first launch is a screen of special
                    //  capabilities, mods have their own), a replay will replace them
                    if (mc.getOverlay() != null || mc.screen == null || mc.level != null) {
                        stall(mc, "title screen did not appear");
                        if (failure != null) {
                            phase = Phase.CLOSING;
                            finish();
                        }
                        return;
                    }
                    // without a pause when focus is lost (there is no window under the xvfb), rendering is the same as the recording and no less
                    mc.options.onboardAccessibility = false;
                    mc.options.pauseOnLostFocus = false;
                    mc.options.renderDistance().set(32);
                    mc.options.save();
                    link.open(settings.replay());
                    phase = Phase.OPENING;
                    lastProgress = System.nanoTime();
                }
                case OPENING -> {
                    MinecraftServer s = link.server();
                    if (s == null || mc.level == null || !link.isReplayServer(s)) {
                        stall(mc, "replay did not open");
                        return;
                    }
                    link.setSpeed(s, settings.speed());
                    if (settings.from() > 0) link.goTo(s, Math.max(0, settings.from() - 1));
                    link.setPaused(s, false);
                    phase = Phase.PLAYING;
                    lastProgress = System.nanoTime();
                }
                case PLAYING -> {
                    mc.noRender = true;
                    if (mc.screen instanceof PauseScreen) mc.setScreen(null);
                    MinecraftServer s = link.server();
                    Scan sc = scan;
                    if (failure != null || (sc != null && sc.done()) || s == null) {
                        if (failure != null) LOG.error("Replay scan failed", failure);
                        phase = Phase.CLOSING;
                        if (s != null) s.execute(this::finish);
                        else finish();
                        return;
                    }
                    if (link.paused(s)) link.setPaused(s, false);
                    int tick = sc == null ? -1 : sc.last();
                    long now = System.nanoTime();
                    if (tick != lastTick) {
                        lastTick = tick;
                        lastProgress = now;
                    } else {
                        stall(mc, "replay stopped at tick " + tick);
                    }
                    if (now - lastLog > 30_000_000_000L && sc != null) {
                        lastLog = now;
                        LOG.info("Replay scan: tick {} of {}", tick, sc.total());
                    }
                }
                case CLOSING -> {
                    if (scan == null) mc.stop();
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            failure = ex;
            LOG.error("Replay scan failed", ex);
            phase = Phase.CLOSING;
            finish();
        }
    }

    private void stall(Minecraft mc, String what) {
        if (System.nanoTime() - lastProgress > STALL_SECONDS * 1_000_000_000L) {
            failure = new IllegalStateException(what + " for " + STALL_SECONDS + " s");
            LOG.error("Replay scan: {}", failure.getMessage());
        }
    }

    private void serverTick(ServerTickEvent.Post e) {
        MinecraftServer s = e.getServer();
        if (phase != Phase.PLAYING || !link.isReplayServer(s) || failure != null) return;
        // under the same lock as closing: closed tables will not be parsed again (it would erase them)
        synchronized (this) {
            if (phase != Phase.PLAYING) return;
            try {
                if (scan == null) scan = new Scan(settings, link, LOG);
                if (!scan.done()) scan.tick(s, link.tick(s));
            } catch (Throwable ex) {
                // any parsing error (and lack of memory) means the end of parsing with the tables recorded, not the game crashing
                failure = ex;
            }
        }
    }

    /** Tables and meta.json - in the replay server thread (the analysis wrote them there); then the client leaves. */
    private void finish() {
        closeScan();
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
    }

    private synchronized void closeScan() {
        phase = Phase.CLOSING;
        Scan sc = scan;
        if (sc != null) {
            try {
                sc.close();
            } catch (Exception ex) {
                LOG.error("Replay scan: cannot write tables", ex);
            }
        }
        scan = null;
    }
}
