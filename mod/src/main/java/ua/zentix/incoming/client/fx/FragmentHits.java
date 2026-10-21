package ua.zentix.incoming.client.fx;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.fx.particle.Fx;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.FragmentModel;
import ua.zentix.incoming.warhead.FragmentTrace;

import java.util.ArrayList;
import java.util.List;

/**
 * Куда попали осколки взрыва ({@link FragmentModel}): первые лучи того же зерна и оси, что у сервера, тем же путём по
 * блокам ({@link FragmentTrace}) — там, где на стене появилась выбоина или выбито стекло, клиент и показывает удар.
 * Сам осколок в полёте не виден: стальной, ~500–700 K — ниже точки Дрейпера, мельче сантиметра и быстрее звука.
 * Видно удар: о камень и металл быстрый осколок высекает искры (горящие частицы стали), из грунта и дерева выбивает
 * облачко пыли и щепы, из стекла — осколки стекла. Сколько лучей показывать — по доле частиц ({@link Fx#density}),
 * не больше {@link #SHOWN}, и не дальше {@link #RANGE}.
 */
final class FragmentHits {
    private FragmentHits() {}

    /** Больше ударов осколков на взрыв клиент не показывает (из тысяч). */
    static final int SHOWN = 256;
    /** Дальше клиент осколок не ведёт, блоков: облачко и искры там мельче пикселя, а лучи стоили бы кадра. */
    static final double RANGE = 96;
    /**
     * Искры о твёрдое — от этой скорости удара, м/с (оценка: сталь о камень высекает искры и при десятках м/с, яркие
     * вспышки удара — от сотен).
     */
    static final double SPARK_SPEED = 300;

    /** Удар осколка: через {@code tick} тиков после взрыва в {@code point}. */
    record Hit(int tick, Vec3 point, BlockPos pos, BlockState state, @Nullable Direction face, double speed, double joules) {}

    /** Удары первых лучей взрыва по сегодняшним блокам клиента, по времени. */
    static List<Hit> of(ClientLevel level, @Nullable WeaponType weapon, Vec3 pos, Vec3 axis, long seed) {
        List<Hit> out = new ArrayList<>();
        FragmentModel model = weapon == null ? null : FragmentModel.of(weapon.spec());
        if (model == null) return out;
        int n = Math.min(model.traced(), Fx.count(SHOWN, pos));
        for (int i = 0; i < n; i++) {
            FragmentModel.Fragment f = model.fragment(seed, i, axis);
            double range = Math.min(RANGE, f.range());
            if (range <= 0) continue;
            FragmentTrace.trace(level, pos, f.dir(), range, new FragmentTrace.Visitor() {
                @Override
                public void glass(BlockPos p, BlockState state, double s) {
                    if (f.energyAt(s) >= FragmentModel.GLASS_J) out.add(hit(f, s, pos.add(f.dir().scale(s)), p, state, null));
                }

                @Override
                public void stop(BlockPos p, BlockState state, double s, Vec3 point, Direction entry) {
                    out.add(hit(f, s, point, p, state, entry));
                }
            });
        }
        out.sort((a, b) -> Integer.compare(a.tick, b.tick));
        return out;
    }

    private static Hit hit(FragmentModel.Fragment f, double s, Vec3 point, BlockPos pos, BlockState state, @Nullable Direction face) {
        int tick = (int) Math.floor(20 * FragmentModel.timeTo(f.speed(), f.mass(), s));
        double v = f.speedAt(s);
        return new Hit(tick, point, pos, state, face, v, FragmentModel.energy(f.mass(), v));
    }

    /** Показать удар {@code h}. */
    static void show(ClientLevel level, Hit h, RandomSource rnd) {
        Vec3 n = h.face == null ? Vec3.ZERO : Vec3.atLowerCornerOf(h.face.getNormal());
        Vec3 at = h.point.add(n.scale(0.05));
        BlockParticleOption dust = new BlockParticleOption(ParticleTypes.BLOCK, h.state);
        // кусков блока — по энергии удара (оценка: облачко на каждые ~200 Дж, от 1 до 6)
        int bits = Mth.clamp((int) (h.joules / 200), 1, 6);
        for (int i = 0; i < bits; i++) {
            Vec3 d = n.scale(0.05 + 0.1 * rnd.nextDouble()).add(rnd.nextGaussian() * 0.04, 0.03 + 0.06 * rnd.nextDouble(), rnd.nextGaussian() * 0.04);
            level.addParticle(dust, at.x, at.y, at.z, d.x, d.y, d.z);
        }
        if (h.face != null && h.speed >= SPARK_SPEED && hard(level, h)) {
            // искры — горящие частицы стали, сколотые о твёрдое: летят от грани веером
            int sparks = 2 + rnd.nextInt(3);
            for (int i = 0; i < sparks; i++) {
                Vec3 d = n.scale(0.15 + 0.25 * rnd.nextDouble()).add(rnd.nextGaussian() * 0.2, rnd.nextGaussian() * 0.2, rnd.nextGaussian() * 0.2);
                Fx.spark().vel(d).size(0.05f, 0.025f).life(4 + rnd.nextInt(6)).gravity(0.04f).drag(0.9f).streak(1)
                        .color(0xFFE6A0, 0xFF7A20).spawn(level, at);
            }
        }
    }

    /** Камень, кирпич, металл: о них осколок высекает искры (грунт, дерево, листва — нет). */
    static boolean hard(ClientLevel level, Hit h) {
        SoundType s = h.state.getSoundType(level, h.pos, null);
        return s == SoundType.STONE || s == SoundType.DEEPSLATE || s == SoundType.DEEPSLATE_BRICKS || s == SoundType.DEEPSLATE_TILES
                || s == SoundType.POLISHED_DEEPSLATE || s == SoundType.METAL || s == SoundType.NETHER_BRICKS || s == SoundType.TUFF
                || s == SoundType.TUFF_BRICKS || s == SoundType.POLISHED_TUFF || s == SoundType.CALCITE || s == SoundType.BASALT
                || s == SoundType.COPPER || s == SoundType.ANVIL || s == SoundType.CHAIN || s == SoundType.LANTERN || s == SoundType.NETHERRACK;
    }
}
