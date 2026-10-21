package ua.zentix.incoming.client.compat;

import dev.lambdaurora.lambdynlights.api.DynamicLightsContext;
import dev.lambdaurora.lambdynlights.api.DynamicLightsInitializer;
import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehavior;
import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehaviorManager;
import dev.lambdaurora.lambdynlights.api.item.ItemLightSourceManager;
import net.minecraft.core.BlockPos;
import ua.zentix.incoming.Incoming;

/**
 * Проба гибрида, вариант E: огненный шар и пламя в воронке — источники LambDynamicLights (точка входа
 * {@code lambdynlights:initializer} в mods.toml; грузится только самим LDL). Уровень света — по яркости модели: 15 у
 * поверхности шара, пока он ярче белой стены днём, тусклее — на 4 уровня за порядок яркости (⚠ соответствие уровней света
 * Minecraft и яркости — оценка); дальше от поверхности — минус уровень на блок, как у ванильных источников. Пламя в воронке —
 * 14 (как костёр, 15 у края) на радиусе воронки, пока горит лужа.
 */
public final class LdlLights implements DynamicLightsInitializer {
    private static DynamicLightBehaviorManager manager;

    @Override
    public void onInitializeDynamicLights(DynamicLightsContext context) {
        manager = context.dynamicLightBehaviorManager();
        Incoming.LOG.info("Probe lights: LambDynamicLights ready");
    }

    @Override
    @SuppressWarnings("removal")
    public void onInitializeDynamicLights(ItemLightSourceManager items) {
    }

    static void add(ProbeLights.Fire f) {
        if (manager == null) return;
        manager.add(new FireLight(f));
    }

    private static final class FireLight implements DynamicLightBehavior {
        private final ProbeLights.Fire fire;
        private final double[] bb = new double[4];
        private int last = -1;

        FireLight(ProbeLights.Fire fire) {
            this.fire = fire;
        }

        /** Уровень у поверхности и радиус источника сейчас. */
        private double[] now() {
            double t = fire.seconds(0);
            double r = fire.model().ballRadius(t), h = fire.model().ballHeight(t);
            if (r > 0) {
                fire.model().ballLight(t, bb);
                double level = Math.min(15, Math.max(0, 15 + 4 * Math.log10(Math.max(bb[3], 1e-6))));
                return new double[]{level, r, h};
            }
            if (t < fire.model().poolFireSeconds()) return new double[]{14, 0.5 * ua.zentix.incoming.client.fx.smoke.CloudModel.CRATER_D
                    * Math.cbrt(fire.model().source.chargeKg()), 0.5};
            return new double[]{0, 0, 0};
        }

        @Override
        public double lightAtPos(BlockPos pos, double falloff) {
            double[] n = now();
            if (n[0] <= 0) return 0;
            double dx = pos.getX() + 0.5 - fire.pos().x, dy = pos.getY() + 0.5 - fire.pos().y - n[2], dz = pos.getZ() + 0.5 - fire.pos().z;
            double d = Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - n[1]);
            return n[0] - d * falloff;
        }

        @Override
        public BoundingBox getBoundingBox() {
            double[] n = now();
            int r = (int) Math.ceil(n[1] + 16);
            BlockPos c = BlockPos.containing(fire.pos());
            return new BoundingBox(c.getX() - r, c.getY() - r, c.getZ() - r, c.getX() + r, c.getY() + (int) n[2] + r, c.getZ() + r);
        }

        @Override
        public boolean hasChanged() {
            int level = (int) Math.round(now()[0] * 4);
            boolean changed = level != last;
            last = level;
            return changed;
        }

        @Override
        public boolean isRemoved() {
            return fire.seconds(0) > fire.life() + 1;
        }
    }
}
