package ua.zentix.incoming.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.fx.particle.Fx;
import ua.zentix.incoming.client.sound.Sources;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.registry.ModSounds;

/**
 * Подстанция выходит из строя: несколько голубых вспышек дуги подряд (ночью их видно над городом издалека),
 * фонтан искр, потом горит масло трансформатора — дым и свет пламени очагом модели облака ({@link FireSmoke#substation}).
 * Звук пробоя приходит со скоростью звука, громкость — по закону источника ({@link Sources}).
 */
public final class GridEffects {
    /** Дальше вспышка дуги не освещает экран (подача). */
    private static final double FLASH_RANGE = 240;
    /** Голубовато-белый свет дуги и его синий край (подача: у дуги линейчатый спектр). */
    private static final int ARC = 0xDCE8FF, ARC_EDGE = 0x5A7CFF;

    private static final RandomSource RANDOM = RandomSource.create();

    private GridEffects() {}

    public static void failure(S2C.GridFailure p) {
        Vec3 pos = p.pos();
        RandomSource rnd = RandomSource.create(p.seed());
        // вспышки дуги: первая сразу, ещё две-три — вразнобой в первые полторы секунды (дуга перебрасывается)
        int[] arcs = new int[3 + rnd.nextInt(2)];
        for (int i = 1; i < arcs.length; i++) arcs[i] = arcs[i - 1] + 3 + rnd.nextInt(10);
        // пробой слышно со скоростью звука, громкость — мощность пробоя и путь до уха
        Sources.play(ModSounds.GRID_FAIL.get(), pos, Sources.GRID_FAIL, 0.95f + rnd.nextFloat() * 0.1f);
        Effects.add(new Effects.Effect() {
            @Override
            public boolean tick(ClientLevel level, int t) {
                Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
                double d = ear.distanceTo(pos);
                for (int a : arcs) {
                    if (a == t) arc(level, pos, rnd, d, a == 0 ? 1 : 0.6f);
                }
                if (t < 40 && t % 2 == 0) sparks(level, pos, rnd, t < 10 ? 6 : 2);
                // масло трансформатора загорается после пробоя: дым и свет пламени — очаг модели облака, пока подстанция выбита
                if (t == 4) FireSmoke.substation(level, BlockPos.containing(pos));
                return t < 40;
            }
        });
    }

    private static void arc(ClientLevel level, Vec3 pos, RandomSource rnd, double d, float strength) {
        Vec3 at = pos.add(rnd.nextGaussian() * 0.4, 1 + rnd.nextDouble(), rnd.nextGaussian() * 0.4);
        // гладко: ближайшим пикселем вспышка в десяток блоков вблизи распадается на квадраты пикселей картинки
        Fx.flash().smooth().color(ARC, ARC_EDGE).size(10 * strength, 16 * strength).life(3).spawn(level, at);
        Fx.flash().smooth().color(0xFFFFFF, ARC).size(3, 5).life(2).spawn(level, at);
        Player player = Minecraft.getInstance().player;
        if (player == null || d > FLASH_RANGE) return;
        Vec3 eye = player.getEyePosition();
        boolean visible = level.clip(new ClipContext(eye, at, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;
        // вспышка освещает небо и стены вокруг — её видно и из-за дома, только слабее
        Flash.trigger((float) (1 - d / FLASH_RANGE) * (visible ? 0.45f : 0.15f) * strength, 0.45f, ARC);
    }

    private static void sparks(ClientLevel level, Vec3 pos, RandomSource rnd, int n) {
        for (int i = 0; i < n; i++) {
            Fx.spark().color(0xFFFFFF, 0x80A0FF).vel(rnd.nextGaussian() * 0.35, 0.3 + rnd.nextDouble() * 0.6, rnd.nextGaussian() * 0.35)
                    .life(12 + rnd.nextInt(16)).gravity(0.05f).spawn(level, pos.add(0, 1, 0));
        }
    }

    /**
     * Случайный тик подстанции у игрока: работает — гул трансформатора; выбита — горит масло (дым — {@link FireSmoke#substation},
     * у вошедшего после выхода из строя — отсюда) и иногда трещит дуга. Громкость — закон источника ({@link Sources}).
     */
    public static void substation(BlockPos pos, boolean powered, boolean spark, float pitch) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        Vec3 at = new Vec3(pos.getX() + 0.5, pos.getY() + 0.9, pos.getZ() + 0.5);
        if (powered) {
            Sources.play(ModSounds.GRID_HUM.get(), at, Sources.GRID_HUM, pitch);
            return;
        }
        FireSmoke.substation(level, pos);
        if (spark) Sources.play(ModSounds.GRID_SPARK.get(), at, Sources.GRID_SPARK, pitch);
    }

    /** Квартал погас или загорелся: щелчок и гул там, где его слышно. */
    public static void district(S2C.GridDistrict p) {
        Sources.play(p.on() ? ModSounds.GRID_POWER_UP.get() : ModSounds.GRID_POWER_DOWN.get(), p.pos(), p.on() ? Sources.GRID_POWER_UP : Sources.GRID_POWER_DOWN,
                0.9f + RANDOM.nextFloat() * 0.2f);
    }
}
