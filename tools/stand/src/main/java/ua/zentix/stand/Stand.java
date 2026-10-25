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
 * Стенд сцен (tools/stand/README.md): в свежем плоском мире одиночной игры клиент без окна играет шаги плана
 * ({@code stand-plan.json} в каталоге игры — его пишет stand.py из scenes.json) и пишет в {@code stand-out/} время
 * каждого кадра на процессоре и на видеокарте по стадиям ({@link FrameClock}), тик встроенного сервера и попадания
 * ({@link ServerWatch}), кадры и строки проб. От Incoming не зависит: всё, что про него, — в плане (команды, типы
 * сущностей, имена методов проб), поэтому один стенд снимает любой jar Incoming. Без плана мод ничего не делает.
 */
@Mod(value = Stand.ID, dist = Dist.CLIENT)
public final class Stand {
    public static final String ID = "stand";
    public static final Logger LOG = LogUtils.getLogger();
    /** Начало отсчёта всех времён стенда (System.nanoTime у клиента и у встроенного сервера — одни часы JVM). */
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

    /** Миллисекунды от {@link #T0}. */
    static double ms(long nanos) {
        return (nanos - T0) / 1e6;
    }
}
