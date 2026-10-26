package ua.zentix.replayscan;

import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;

/**
 * Flashback is a mod of Fabric (via Sinytra Connector), it does not have API for mods: public members are reflected, like
 *  in {@code compat/FlashbackReplay} Incoming. Names - Flashback 0.39 (branch 1.21). Replay server and client - in one
 * JVM; members of the replay server are called by its thread, except for {@link #goTo} and {@link #setSpeed} (the window Flashback also calls them that way).
 */
final class FlashbackLink {
    private static final String P = "com.moulberry.flashback.";
    private final Class<?> flashback, server;
    private final Method open, getServer, replayTick, goToTick, setTickRate, localPlayerId;
    private final Field paused, fastForwarding;

    private FlashbackLink() throws ReflectiveOperationException {
        ClassLoader loader = FlashbackLink.class.getClassLoader();
        flashback = Class.forName(P + "Flashback", false, loader);
        server = Class.forName(P + "playback.ReplayServer", false, loader);
        open = flashback.getMethod("openReplayWorld", Path.class);
        getServer = flashback.getMethod("getReplayServer");
        replayTick = server.getMethod("getReplayTick");
        goToTick = server.getMethod("goToReplayTick", int.class);
        setTickRate = server.getMethod("setDesiredTickRate", float.class, boolean.class);
        localPlayerId = server.getMethod("getLocalPlayerId");
        paused = server.getField("replayPaused");
        fastForwarding = server.getField("fastForwarding");
    }

    /** Flashback with the required members or an exception with the name of the missing one. */
    static FlashbackLink find() throws ReflectiveOperationException {
        return new FlashbackLink();
    }

    boolean isReplayServer(@Nullable MinecraftServer s) {
        return s != null && server.isInstance(s);
    }

    void open(Path zip) throws ReflectiveOperationException {
        open.invoke(null, zip);
    }

    @Nullable
    MinecraftServer server() throws ReflectiveOperationException {
        return (MinecraftServer) getServer.invoke(null);
    }

    int tick(MinecraftServer s) throws ReflectiveOperationException {
        return (int) replayTick.invoke(s);
    }

    void goTo(MinecraftServer s, int tick) throws ReflectiveOperationException {
        goToTick.invoke(s, tick);
    }

    /** Replay speed: replay server tick per second (manual window speed Flashback, 20 - as in the game). */
    void setSpeed(MinecraftServer s, float ticksPerSecond) throws ReflectiveOperationException {
        setTickRate.invoke(s, ticksPerSecond, true);
    }

    boolean paused(MinecraftServer s) throws ReflectiveOperationException {
        return paused.getBoolean(s);
    }

    void setPaused(MinecraftServer s, boolean value) throws ReflectiveOperationException {
        paused.setBoolean(s, value);
    }

    boolean fastForwarding(MinecraftServer s) throws ReflectiveOperationException {
        return fastForwarding.getBoolean(s);
    }

    /** id entity of the recording player in the replay world ({@code -1}, it doesn't exist yet). */
    int localPlayerId(MinecraftServer s) throws ReflectiveOperationException {
        return (int) localPlayerId.invoke(s);
    }
}
