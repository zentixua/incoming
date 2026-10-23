package ua.zentix.incoming.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.nuclear.model.BlastLitter;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.ChemicalFront;

/**
 * Фронт обычного взрыва на траве: в миг прихода он срывает травинки, дёрн и листья крон и подбрасывает их — так фронт и
 * виден на земле: кольцо взлетающих клочков бежит со скоростью фронта (жалоба 23.10: «кольца никакого»). Далеко они не
 * летят: воздух за фазой в миллисекунды сдвигается на дециметры, и клок, брошенный и на 30 м/с, за тик тормозит до
 * своей скорости падения и ложится в полуметре (законы — ниже; траектория не подача). Где срывает —
 * закон срыва пыли ({@link BlastLitter#tearsCover}: u* за фронтом выше порога дёрна); с какой скоростью бросает —
 * сопротивление клока листвы под ветром фазы ({@link BlastLitter#kick}); когда — приход фронта на место
 * ({@link ChemicalFront}). Пыль с голой земли, песка и снега — слой пыли волны ({@code GroundDust}), не здесь.
 * <p>
 * Куски — {@link Fragments} (лист с текстурой и цветом блока: трава зелёная, листва — своего дерева), вылетают уже с
 * брошенной скоростью в тихий воздух (фаза — миллисекунды, короче тика) и планируют вниз. Сколько их — выборка для
 * картинки (<b>подача</b>: {@link #PER_M2} на квадратный метр, не больше {@link #MAX} на взрыв), где и как они летят —
 * по закону. Ветра мира у кусков нет (⚠: сносит их только бросок).
 */
public final class FrontLitter {
    /** Кусков на квадратный метр сорванного (подача: травинок на деле — тысячи; столько видно кольцом). */
    static final double PER_M2 = 0.35;
    /** Кусков на взрыв не больше (подача; общий предел — {@link Fragments}). */
    static final int MAX = 500;
    /** Дальше стольких блоков от камеры кусков не рождаем: их не видно, а место в пределе кусков нужно ближним. */
    static final double REACH = 128;
    /** Дальше стольких метров срыв не ищется (у 450 кг он кончается в ~110 м). */
    static final double TEAR_MAX = 300;

    private static final Fragments.Wind CALM = (x, y, z, into) -> {
        into[0] = 0;
        into[1] = 0;
        into[2] = 0;
    };

    private FrontLitter() {}

    /**
     * Кольцо срыва травы и листьев по земле {внутренний, внешний радиус}, м: где u* за фронтом выше порога дёрна. Ищется с
     * {@code from} (край огненного шара: ближе всё сгорает и уходит с грунтом воронки) до {@link #TEAR_MAX}; {@code hob}
     * — высота разрыва над землёй: под воздушным разрывом ветер идёт вниз, и кольцо начинается дальше. {0, 0} — нигде.
     */
    static double[] tearRing(ChemicalFront front, double from, double hob) {
        double r = Math.max(from, 0.5), inner = 0, outer = 0;
        for (; r <= TEAR_MAX; r += 0.5) {
            if (!tears(front, r, hob)) continue;
            if (inner == 0) inner = r;
            outer = r;
        }
        return new double[]{inner, outer};
    }

    /** Внешний радиус кольца срыва, м ({@link #tearRing}). */
    static double tearRange(ChemicalFront front, double from, double hob) {
        return tearRing(front, from, hob)[1];
    }

    private static boolean tears(ChemicalFront front, double ground, double hob) {
        double slant = Math.hypot(ground, hob);
        return BlastLitter.tearsCover(front.kpa(slant), along(ground, slant));
    }

    /** Доля напора вдоль земли: у разрыва над землёй фронт падает наклонно (cos²). */
    static double along(double ground, double slant) {
        return slant <= 0 ? 1 : (ground / slant) * (ground / slant);
    }

    /**
     * Сорвать траву и листья вокруг взрыва в {@code pos}: фронт {@code front}, огненный шар радиусом {@code ball} м,
     * высота разрыва над землёй {@code hob} м. Кусок ждёт прихода фронта на своё место ({@link Fragments#DELAY_MAX} —
     * предел ожидания, дальше 2 с фронта срыва уже нет).
     */
    static void spawn(ClientLevel level, Vec3 pos, @Nullable ChemicalFront front, double ball, double hob, RandomSource rnd) {
        if (front == null) return;
        double[] ring = tearRing(front, Math.max(ball, 1), hob);
        double inner = ring[0], outer = ring[1];
        if (!(outer > inner)) return;
        Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        if (Math.hypot(eye.x - pos.x, eye.z - pos.z) > outer + REACH) return;
        int n = Math.min(MAX, (int) Math.round(PER_M2 * Math.PI * (outer * outer - inner * inner)));
        double terminal = Fragments.terminal(Fragments.Kind.LEAF, 0, 0);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        double groundY = pos.y - Math.max(0, hob);
        for (int i = 0; i < n; i++) {
            double r = Math.sqrt(inner * inner + rnd.nextDouble() * (outer * outer - inner * inner)), a = rnd.nextDouble() * Mth.TWO_PI;
            double cos = Math.cos(a), sin = Math.sin(a), x = pos.x + cos * r, z = pos.z + sin * r;
            if (Math.hypot(eye.x - x, eye.z - z) > REACH) continue;
            int bx = Mth.floor(x), bz = Mth.floor(z);
            LevelChunk chunk = level.getChunkSource().getChunk(bx >> 4, bz >> 4, false);
            if (chunk == null) continue;
            int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, bx & 15, bz & 15);
            BlockState plant = chunk.getBlockState(m.set(bx, top + 1, bz)), cover = chunk.getBlockState(m.set(bx, top, bz));
            boolean grows = !plant.isAir() && plant.canBeReplaced() && plant.getFluidState().isEmpty();
            boolean leaves = cover.is(BlockTags.LEAVES);
            if (!grows && !leaves && !cover.is(BlockTags.DIRT)) continue;
            BlockState torn = grows ? plant : cover;
            m.set(bx, grows ? top + 1 : top, bz);
            // крона и то, что выше места взрыва, видит волну в воздухе, а не вдоль земли
            double y = top + (grows ? 1.2 : 1.05), dy = y - pos.y, slant = Math.sqrt(r * r + dy * dy);
            double share = leaves || y > groundY + 2 ? 1 : along(r, Math.hypot(r, Math.max(0, hob)));
            double kpa = front.kpa(slant);
            if (!BlastLitter.tearsCover(kpa, share)) continue;
            double v = BlastLitter.kick(BlastModel.windSpeed(kpa) * Math.sqrt(share), BlastModel.chemicalPhaseSeconds(slant, front.tntKg, front.surface),
                    terminal) / 20;
            double lift = Fragments.LIFT_MIN + (Fragments.LIFT_MAX - Fragments.LIFT_MIN) * rnd.nextDouble();
            int delay = (int) Math.round(20 * front.arrivalSeconds(slant));
            if (delay > Fragments.DELAY_MAX) continue;
            Fragments.torn(level, torn, m.immutable(), Fragments.Kind.LEAF, x, y, z, cos * v, lift * v, sin * v, 0, CALM, delay, false);
        }
    }
}
