package ua.zentix.incoming.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.nuclear.model.BlastLitter;
import ua.zentix.incoming.nuclear.model.ChemicalFront;
import ua.zentix.incoming.nuclear.model.DustModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Подача пыли, осыпающейся с сорванного дёрна: светлые короткие клубы подчёркивают бегущий край, пока клоки ещё у земли.
 * Это детали срыва FrontLitter, а не второй физический слой GroundDust (его масса и оседание не меняются).
 * Место и момент срыва — тот же DustModel и ChemicalFront; контраст, размер клубов и подчёркнутый подъём — подача.
 * В отличие от задержки частиц в целых тиках, источник проверяет приход с долей тика: кольцо не возникает целиком.
 */
final class FrontDust implements SmokeGrid.Source {
    private record Patch(double x, double y, double z, double slant, double arrival, double size, double contrast) {}

    /** Подача: дискретизация кольца, не число частиц грунта; не более 24 × 64 клубов на взрыв. */
    private static final int RINGS = 24, SPOKES = 64;
    private static final double LIFE = 0.32;
    private final ClientLevel level;
    private final double born, end;
    private final ChemicalFront front;
    private final List<Patch> patches;

    private FrontDust(ClientLevel level, ChemicalFront front, List<Patch> patches) {
        this.front = front;
        this.level = level;
        this.patches = patches;
        born = SmokeGrid.seconds(0);
        double last = 0;
        for (Patch p : patches) last = Math.max(last, p.arrival);
        end = born + last + LIFE;
    }

    static void spawn(ClientLevel level, Vec3 pos, ChemicalFront front, double inner, double outer, double hob) {
        List<Patch> patches = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int j = 0; j < RINGS; j++) {
            double r = inner + (outer - inner) * (j + 0.5) / RINGS;
            int spokes = Math.max(12, (int) Math.ceil(SPOKES * r / outer));
            for (int i = 0; i < spokes; i++) {
                double a = 2 * Math.PI * (i + 0.5 * (j % 2)) / spokes;
                double x = pos.x + r * Math.cos(a), z = pos.z + r * Math.sin(a);
                int bx = Mth.floor(x), bz = Mth.floor(z);
                LevelChunk chunk = level.getChunkSource().getChunk(bx >> 4, bz >> 4, false);
                if (chunk == null) continue;
                int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, bx & 15, bz & 15);
                var cover = chunk.getBlockState(m.set(bx, top, bz));
                // Только сорванный покров; голый грунт, вода, камень и песок остаются у GroundDust.
                if (!cover.is(Blocks.GRASS_BLOCK) && !cover.is(BlockTags.LEAVES)) continue;
                double y = top + 1.05, slant = Math.sqrt(r * r + (y - pos.y) * (y - pos.y));
                double along = cover.is(BlockTags.LEAVES) || y > pos.y - Math.max(0, hob) + 2
                        ? 1 : FrontLitter.along(r, Math.hypot(r, hob));
                double friction = BlastLitter.friction(front.kpa(slant), along);
                if (!BlastLitter.tearsCover(front.kpa(slant), along)) continue;
                double size = Math.max(0.45, Math.min(1.2, Math.PI * r / spokes));
                double contrast = Math.min(1, friction / DustModel.Surface.COVERED.threshold() - 1);
                patches.add(new Patch(x, y, z, slant, front.arrivalSeconds(slant), size, contrast));
            }
        }
        if (!patches.isEmpty()) SmokeGrid.add(new FrontDust(level, front, patches));
    }

    /** Подача: быстрый подъём и исчезновение мелких светлых деталей; до прихода фронта строго ноль. */
    static double envelope(double age) {
        if (age <= 0 || age >= LIFE) return 0;
        double u = age / LIFE;
        return (1 - Math.exp(-age / 0.008)) * (1 - u) * (1 - u);
    }

    @Override
    public boolean emit(double seconds, SmokeGrid.Sink out) {
        if (ended() || seconds >= end) return false;
        for (Patch p : patches) {
            double age = seconds - born - p.arrival, fade = envelope(age);
            if (fade <= 0) {
                // Empty calls retain the baked-puff seed while neighbouring patches start or expire.
                out.blob(p.x, p.y, p.z, 0, 0, 0, 0, 0, 0.82f, 0.76f, 0.62f, CloudModel.T_AIR_K, 0);
                continue;
            }
            double s = Math.min(p.size * (0.7 + 2 * age), Math.max(0, front.radiusAt(seconds - born) - p.slant) / 4);
            if (s <= 0) continue;
            double v = 0.3 + age;
            // Подача: оптическая толща детали 0,8 при полном срыве; это не масса, добавленная к расчёту разрушений.
            double dust = 0.8 * p.contrast * fade * 2 * Math.PI * s * v / SmokeGrid.dustK(0.82f, 0.76f, 0.62f);
            out.blob(p.x, p.y + 3 * age, p.z, s, v, 0, dust, 0, 0.82f, 0.76f, 0.62f, CloudModel.T_AIR_K, 0);
        }
        return true;
    }

    @Override
    public boolean ended() {
        return level != Minecraft.getInstance().level || SmokeGrid.seconds(0) >= end;
    }
}
