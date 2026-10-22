package ua.zentix.incoming.gametest;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import ua.zentix.incoming.IncomingConfig;

/**
 * Масштаб ядерки в проверках: пресет {@code CUSTOM} с волной 1,0 — как в жизни. По умолчанию мир играет пресетом
 * {@code GAME} (волна 0,20), а GameTest, сценарии полёта и клиента считались при волне 1,0: они задают её явно, и их
 * числа и кадры от пресета не двигаются. Тест, которому нужна другая волна или другие радиусы, ставит свои числа
 * {@code nuclear.*} — при {@code CUSTOM} они действуют как есть.
 */
public final class TestScale {
    private TestScale() {}

    /** С запуском сервера в этой JVM — пресет {@code CUSTOM}, волна 1,0 (настройки сервера уже прочитаны). */
    public static void realWaveOnStart() {
        NeoForge.EVENT_BUS.addListener((ServerStartingEvent e) -> realWave());
    }

    public static void realWave() {
        IncomingConfig.SERVER.scalePreset.set(IncomingConfig.ScalePreset.CUSTOM);
        IncomingConfig.SERVER.nukeWaveSpeed.set(1.0);
    }
}
