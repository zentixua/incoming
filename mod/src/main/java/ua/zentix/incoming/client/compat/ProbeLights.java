package ua.zentix.incoming.client.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.fx.smoke.CloudModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Проба гибрида (handoff P, варианты D и E): свет огненного шара и пламени в воронке на мир чужими модами — только если
 * мод стоит (мягкая зависимость): LambDynamicLights (свет блоков, шейдерпак освещает им мир сам; {@link LdlLights}) и
 * Photon (точечный свет с тенями, {@code PhotonLights.flash} отражением). Свой свет мода (FxLights, шары на мире в проходе
 * пелены) остаётся как есть — сравниваются кадры.
 */
public final class ProbeLights {
    private ProbeLights() {}

    /** Огонь взрыва: где, когда родился (тик клиента), модель. */
    public record Fire(Vec3 pos, long born, CloudModel model) {
        public double seconds(float partial) {
            var level = Minecraft.getInstance().level;
            return level == null ? 1e9 : (level.getGameTime() - born + partial) / 20.0;
        }

        /** Сколько светит вообще (шар и лужа), с. */
        public double life() {
            return Math.max(model.fireSeconds, model.poolFireSeconds());
        }
    }

    static final List<Fire> FIRES = new ArrayList<>();
    private static final boolean LDL = ModList.get().isLoaded("lambdynlights");
    private static final boolean PHOTON = ModList.get().isLoaded("photon");

    @SuppressWarnings("unchecked")
    public static void blast(Vec3 pos, CloudModel model) {
        var level = Minecraft.getInstance().level;
        if (level == null || !(LDL || PHOTON)) return;
        Fire f = new Fire(pos, level.getGameTime(), model);
        if (LDL) LdlLights.add(f);
        if (PHOTON) {
            photon(f);
            if (!irisReported) {
                irisReported = true;
                try {
                    Object target = Class.forName("com.lowdragmc.photon.client.compat.iris.IrisCompat").getMethod("diagnosticsTarget").invoke(null);
                    Class<?> diag = Class.forName("com.lowdragmc.photon.client.compat.iris.IrisDiagnostics");
                    Object report = java.util.Arrays.stream(diag.getMethods()).filter(m -> m.getName().equals("report")).findFirst().orElseThrow()
                            .invoke(null, target);
                    Incoming.LOG.info("Probe lights: Photon Iris report:\n{}", String.join("\n", (List<String>) report));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    Incoming.LOG.warn("Probe lights: Photon Iris report failed: {}", e.toString());
                }
            }
        }
    }

    private static boolean irisReported;

    /** Свет шара: Photon — точечный источник радиуса шара на время его свечения (цвет — чёрное тело модели в миг взрыва). */
    private static void photon(Fire f) {
        try {
            double[] bb = new double[4];
            f.model().ballLight(0.05, bb);
            double m = Math.max(bb[0], Math.max(bb[1], bb[2]));
            Class<?> dl = Class.forName("com.lowdragmc.photon.client.light.DynamicLight");
            Object light = dl.getConstructor().newInstance();
            double r = f.model().ballRadius(0.3);
            double h = f.model().ballHeight(0.3);
            dl.getMethod("at", double.class, double.class, double.class).invoke(light, f.pos().x, f.pos().y + h, f.pos().z);
            dl.getMethod("color", float.class, float.class, float.class).invoke(light, (float) (bb[0] / m), (float) (bb[1] / m), (float) (bb[2] / m));
            dl.getMethod("intensity", float.class).invoke(light, 8f);
            dl.getMethod("range", float.class).invoke(light, (float) (12 * r));
            dl.getMethod("sourceRadius", float.class).invoke(light, (float) r);
            dl.getMethod("shadows", boolean.class).invoke(light, true);
            dl.getMethod("point").invoke(light);
            int ticks = (int) Math.ceil(20 * f.model().fireSeconds);
            Class.forName("com.lowdragmc.photon.client.light.PhotonLights").getMethod("flash", dl, int.class).invoke(null, light, ticks);
            Incoming.LOG.info("Probe lights: Photon flash r={} ticks={}", Math.round(r), ticks);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Incoming.LOG.warn("Probe lights: Photon flash failed: {}", e.toString());
        }
    }
}
