package ua.zentix.incoming.gametest;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import ua.zentix.incoming.IncomingConfig;

/**
 * The scale of the nuke in tests: preset {@code CUSTOM} with wave 1,0 - just like in life. By default, the world plays with the
 * {@code GAME} preset (wave 0,20), and GameTest, flight and client scenarios were considered with the 1,0 wave: they set it explicitly, and their
 *  numbers and frames from the preset do not move. A test that needs a different wave or other radii puts its numbers
 * {@code nuclear.*} - with {@code CUSTOM} they act as is.
 */
public final class TestScale {
    private TestScale() {}

    /** With the launch of the server in this JVM - preset {@code CUSTOM}, wave 1,0 (server settings have already been read). */
    public static void realWaveOnStart() {
        NeoForge.EVENT_BUS.addListener((ServerStartingEvent e) -> realWave());
    }

    public static void realWave() {
        IncomingConfig.SERVER.scalePreset.set(IncomingConfig.ScalePreset.CUSTOM);
        IncomingConfig.SERVER.nukeWaveSpeed.set(1.0);
    }
}
