package ua.zentix.incoming.warhead;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.nuclear.world.BlockResponse;
import ua.zentix.incoming.registry.ModDataMaps;
import ua.zentix.incoming.util.Terrain;

/**
 * Пробой преграды зарядом, приложенным к ней ({@link BreachModel}), на блоках мира. Из точки, где заряд лежит на грани
 * ({@link #locate}), луч взрыва идёт внутрь преграды; путь в каждом блоке делится на радиус пробоя его материала
 * (группа FM — карта данных {@code incoming:penetration}, {@link ModDataMaps.Penetrability#breaching}). Дошёл до
 * пустоты (воздух, жидкость, блок без формы) раньше, чем сумма превысила 1, — всё пройденное снесено: плита пробита
 * по этому лучу, и волна идёт дальше в пустоту. Не дошёл — преграда держит, а грунт роет закон воронки. Одно правило
 * для любых блоков: держит заряд толща и материал до пустоты, а не то, природный блок или постройка.
 */
final class SlabBreach {
    /**
     * Шаг луча пробоя, блоков: мельче шага волны (0,3): путь в плите сравнивается с радиусом пробоя, и шаг 0,3 у косых
     * лучей к краю дыры прибавлял до 0,3 м пути — край выходил на блок уже закона. Шагов в одном блоке всё равно читается
     * один блок.
     */
    static final double STEP = 0.1;
    /** Блок, которому ванильный взрыв не страшен (обсидиан 1200 и защищённые блоки модов): заряд его не смещает. */
    static final float BLAST_PROOF = 1200;

    private SlabBreach() {}

    /** Как заряд приложен к преграде. */
    enum Placement {
        /** Не приложен (взрыв в воздухе, доля заряда, вышедшая наружу, проверки): пробоя нет — только волна. */
        NONE,
        /** На грани, в которую ударила боевая часть, без забивки ({@link BreachModel#UNTAMPED}). */
        SURFACE,
        /** В толще (бомба, вошедшая в грунт), забит ({@link BreachModel#TAMPED}). */
        BURIED
    }

    /** Что для пробоя блок на пути луча. */
    enum Cell {
        /** Пустота за преградой: воздух, жидкость, блок без формы столкновений (трава, факел). */
        VOID,
        /** Заряд его не смещает: неразрушимое, сверхпрочное, непробиваемое по карте данных, защищённое приватом. */
        STOP,
        /** Преграда. */
        SOLID
    }

    /**
     * Откуда идут лучи пробоя.
     *
     * @param at        точка заряда на грани преграды (или центр заряда в толще)
     * @param open      наружу от преграды — откуда пришла боевая часть; лучи пробоя — только внутрь (скалярное
     *                  произведение с ним меньше нуля); null — во все стороны (заряд в толще или в плите, открытой с
     *                  обеих сторон)
     * @param tolerance сколько пустоты луч может пройти до преграды, оставаясь приложенным к ней, блоков
     * @param radius    радиус пробоя каждой группы материала ({@link BreachModel.Material#ordinal}), м
     */
    record Origin(Vec3 at, @Nullable Vec3 open, double tolerance, double[] radius) {
        /** Наибольший радиус пробоя: дальше него луч пробоя не идёт. */
        double reach() {
            double r = 0;
            for (double v : radius) r = Math.max(r, v);
            return r + tolerance;
        }

        /** Луч {@code (dx, dy, dz)} идёт внутрь преграды. */
        boolean inward(double dx, double dy, double dz) {
            return open == null || dx * open.x + dy * open.y + dz * open.z < 0;
        }
    }

    /** Блок {@code s} на месте {@code p} для пробоя заряда взрыва {@code e}. */
    static Cell cell(ServerLevel level, BlockPos p, BlockState s, Explosion e, ExplosionDamageCalculator damage) {
        if (s.isAir() || s.getBlock() instanceof LiquidBlock || s.getCollisionShape(level, p).isEmpty()) return Cell.VOID;
        if (s.getExplosionResistance(level, p, e) >= BLAST_PROOF || BlockResponse.of(s).kind() == BlockResponse.Kind.NONE
                || ModDataMaps.Penetrability.of(s).breaching() == null || !damage.shouldBlockExplode(e, level, p, s, 1f)) return Cell.STOP;
        return Cell.SOLID;
    }

    /** Группа материала твёрдого блока ({@link Cell#SOLID}). */
    static BreachModel.Material material(BlockState s) {
        BreachModel.Material m = ModDataMaps.Penetrability.of(s).breaching();
        return m != null ? m : BreachModel.Material.REINFORCED;
    }

    /**
     * Где заряд {@code tnt} кг ТНТ с центром {@code centre} приложен к преграде; null — не приложен (центр в воздухе дальше
     * радиуса заряда от любой грани, в неготовом чанке, в непробиваемом). У заряда на грани ({@link Placement#SURFACE}):
     * центр в твёрдом блоке (точка прицела — полблока в землю) — заряд лежит на его открытой грани (сторона, где у блока
     * пустота), лучи — от неё внутрь: толщина считается от поверхности, как у FM (R наружного заряда — вся толщина);
     * открыты две противоположные грани (настил в один блок) — лучи во все стороны из центра; центр в пустоте у грани
     * (удар носом о поверхность) — оттуда же, внутрь.
     */
    @Nullable
    static Origin locate(ServerLevel level, Vec3 centre, double tnt, Placement placement, Explosion e, ExplosionDamageCalculator damage) {
        if (placement == Placement.NONE || !(tnt > 0)) return null;
        double c = placement == Placement.BURIED ? BreachModel.TAMPED : BreachModel.UNTAMPED;
        double[] radius = new double[BreachModel.Material.values().length];
        for (BreachModel.Material m : BreachModel.Material.values()) radius[m.ordinal()] = m.radius(tnt, c);
        double rc = BreachModel.chargeRadius(tnt);
        BlockPos cell = BlockPos.containing(centre);
        if (!Terrain.ready(level, cell)) return null;
        // в толще: заряд и его ход (канал бомбы) — пустота в блоке заряда, дальше — порода
        if (placement == Placement.BURIED) return new Origin(centre, null, rc + 1, radius);
        Cell here = cell(level, cell, level.getBlockState(cell), e, damage);
        if (here == Cell.STOP) return null;
        double x = 0, y = 0, z = 0, gap = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            BlockPos n = cell.relative(d);
            if (!Terrain.ready(level, n)) return null;
            Cell k = cell(level, n, level.getBlockState(n), e, damage);
            if (here == Cell.SOLID ? k == Cell.VOID : k == Cell.SOLID) {
                x += d.getStepX();
                y += d.getStepY();
                z += d.getStepZ();
            }
            if (here == Cell.VOID && k == Cell.SOLID) gap = Math.min(gap, faceDistance(cell, centre, d));
        }
        Vec3 sum = new Vec3(x, y, z);
        if (here == Cell.VOID) {
            // в пустоте: приложен, только если до грани не дальше радиуса шара заряда; наружу — от твёрдых соседей
            if (gap > rc + 1e-9) return null;
            return new Origin(centre, sum.lengthSqr() < 1e-9 ? null : sum.scale(-1).normalize(), rc + STEP, radius);
        }
        if (sum.lengthSqr() < 1e-9) return new Origin(centre, null, rc + STEP, radius);
        Vec3 open = sum.normalize();
        return new Origin(exit(cell, centre, open), open, rc + STEP, radius);
    }

    /** Расстояние от {@code p} в блоке {@code cell} до его грани со стороны {@code d}. */
    private static double faceDistance(BlockPos cell, Vec3 p, Direction d) {
        return switch (d) {
            case DOWN -> p.y - cell.getY();
            case UP -> cell.getY() + 1 - p.y;
            case NORTH -> p.z - cell.getZ();
            case SOUTH -> cell.getZ() + 1 - p.z;
            case WEST -> p.x - cell.getX();
            case EAST -> cell.getX() + 1 - p.x;
        };
    }

    /** Где луч из {@code p} по {@code dir} выходит из блока {@code cell}. */
    static Vec3 exit(BlockPos cell, Vec3 p, Vec3 dir) {
        double t = Double.MAX_VALUE;
        t = Math.min(t, axisExit(p.x, dir.x, cell.getX()));
        t = Math.min(t, axisExit(p.y, dir.y, cell.getY()));
        t = Math.min(t, axisExit(p.z, dir.z, cell.getZ()));
        return t == Double.MAX_VALUE ? p : p.add(dir.scale(t));
    }

    private static double axisExit(double p, double d, int lo) {
        if (d > 1e-9) return (lo + 1 - p) / d;
        if (d < -1e-9) return (lo - p) / d;
        return Double.MAX_VALUE;
    }

    /**
     * Луч пробоя из {@code o} по {@code (dx, dy, dz)}: блоки преграды на пути — в {@code path}. Результат — расстояние от
     * {@code o.at()}, на котором луч вышел в пустоту за преградой (пробил); −1 — не пробил (сумма путей больше 1,
     * непробиваемое, пустота до преграды дальше допуска, неготовый чанк, край мира); {@code liquid[0]} — вышел в жидкость
     * (волна дальше не идёт).
     */
    static double march(ServerLevel level, Origin o, double dx, double dy, double dz, Explosion e, ExplosionDamageCalculator damage,
                        LongArrayList path, boolean[] liquid) {
        path.clear();
        double used = 0, r = 0;
        long last = Long.MIN_VALUE;
        double reach = o.reach();
        // точка луча — середина своего шага: шаг от d − STEP/2 до d + STEP/2 — в блоке этой точки
        for (double d = STEP / 2; d <= reach + STEP; d += STEP) {
            BlockPos pos = BlockPos.containing(o.at().x + dx * d, o.at().y + dy * d, o.at().z + dz * d);
            long key = pos.asLong();
            if (key != last) {
                last = key;
                if (!level.isInWorldBounds(pos) || !Terrain.ready(level, pos)) return -1;
                BlockState s = level.getBlockState(pos);
                switch (cell(level, pos, s, e, damage)) {
                    case STOP -> {
                        return -1;
                    }
                    case VOID -> {
                        if (path.isEmpty()) {
                            if (d > o.tolerance()) return -1;
                            continue;
                        }
                        liquid[0] = s.getBlock() instanceof LiquidBlock;
                        return d - STEP / 2;
                    }
                    case SOLID -> {
                        path.add(key);
                        r = o.radius()[material(s).ordinal()];
                    }
                }
            } else if (path.isEmpty()) {
                if (d > o.tolerance()) return -1;
                continue;
            }
            if (r <= 0) return -1;
            used += STEP / r;
            if (used > 1) return -1;
        }
        return -1;
    }
}
