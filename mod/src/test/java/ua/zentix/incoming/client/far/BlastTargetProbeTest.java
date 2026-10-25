package ua.zentix.incoming.client.far;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.smoke.BlastTarget;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.warhead.BreachModel;
import ua.zentix.incoming.warhead.GroundMaterial;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Опора по оси удара ({@link FarBlasts#target}): плита над пустотой — по закону пробоя её материала (путь / радиус ≤ 1 до
 * пустоты под перекрытием), а не по имени блока; толща и грунт — опора; пустота под открытым небом — грань воронки.
 */
class BlastTargetProbeTest {
    private static final double SHAHED = 50;

    /** Мир из блоков: твёрдое — группа пробоя, остальное — воздух; первый воздух над колонкой — по блокам. */
    static final class World implements FarBlasts.Probe {
        final Map<Long, BreachModel.Material> blocks = new HashMap<>();

        World fill(int x0, int y0, int z0, int x1, int y1, int z1, BreachModel.Material m) {
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) blocks.put(BlockPos.asLong(x, y, z), m);
            return this;
        }

        @Override
        public boolean loaded(int x, int z) {
            return true;
        }

        @Override
        public double radius(BlockPos p, double charge) {
            BreachModel.Material m = blocks.get(p.asLong());
            return m == null ? 0 : m.radius(charge, BreachModel.UNTAMPED);
        }

        @Override
        public boolean earth(BlockPos p) {
            return blocks.get(p.asLong()) == BreachModel.Material.EARTH;
        }

        @Override
        public int top(int x, int z) {
            for (int y = 320; y > -64; y--) if (blocks.containsKey(BlockPos.asLong(x, y, z))) return y + 1;
            return -64;
        }
    }

    /** Земля до глубины: грунт до 64, ниже — камень. */
    static World ground() {
        return new World().fill(-30, 40, -30, 30, 60, 30, BreachModel.Material.ROCK).fill(-30, 61, -30, 30, 64, 30, BreachModel.Material.EARTH);
    }

    @Test
    void roofOverARoomIsASlab() {
        // помещение 21×21: стены 65–68, крыша из досок в метр на 69, удар — о её верх (70)
        World w = ground().fill(-10, 65, -10, 10, 68, -10, BreachModel.Material.ROCK).fill(-10, 69, -10, 10, 69, 10, BreachModel.Material.POOR);
        BlastTarget t = FarBlasts.target(w, new Vec3(0.5, 70, 0.5), S2C.Blast.DOWN, GroundMaterial.WOOD, SHAHED, 0);
        assertEquals(BlastTarget.Family.SLAB, t.family());
        assertEquals(1, t.slab(), 0.11);
        double r = BreachModel.Material.POOR.radius(SHAHED, BreachModel.UNTAMPED);
        assertEquals(Math.sqrt(r * r - t.slab() * t.slab()), t.hole(), 0.05);
    }

    @Test
    void thickOrBackedTargetIsSupport() {
        // грунт — рыхлое на опоре
        assertEquals(BlastTarget.Family.SOIL, FarBlasts.target(ground(), new Vec3(0.5, 65, 0.5), S2C.Blast.DOWN, GroundMaterial.DIRT, SHAHED, 0).family());
        // метр камня на грунте — твёрдое
        World paved = ground().fill(-30, 64, -30, 30, 64, 30, BreachModel.Material.ROCK);
        assertEquals(BlastTarget.Family.HARD, FarBlasts.target(paved, new Vec3(0.5, 65, 0.5), S2C.Blast.DOWN, GroundMaterial.STONE, SHAHED, 0).family());
        // крыша в 3 м досок — шахед её не пробивает (R ≈ 1,8 м): опора
        World thick = ground().fill(-10, 69, -10, 10, 71, 10, BreachModel.Material.POOR);
        assertEquals(BlastTarget.Family.HARD, FarBlasts.target(thick, new Vec3(0.5, 72, 0.5), S2C.Blast.DOWN, GroundMaterial.WOOD, SHAHED, 0).family());
        // «Град» метр досок не пробивает (R ≈ 0,9 м)
        World roof = ground().fill(-10, 69, -10, 10, 69, 10, BreachModel.Material.POOR);
        assertEquals(BlastTarget.Family.HARD, FarBlasts.target(roof, new Vec3(0.5, 70, 0.5), S2C.Blast.DOWN, GroundMaterial.WOOD, 6.4, 0).family());
    }

    /** Пустота под открытым небом (уступ рельефа по косой оси) — грань воронки, а не плита (как у пробоя сервера). */
    @Test
    void openVoidIsACraterFaceNotASlab() {
        // уступ в блок: верх 65 до x = 0, дальше — 64; ось — вниз и вбок через угол уступа
        World ledge = ground().fill(-30, 65, -30, 0, 65, 30, BreachModel.Material.EARTH);
        Vec3 axis = new Vec3(1, -0.4, 0);
        assertEquals(BlastTarget.Family.SOIL, FarBlasts.target(ledge, new Vec3(0.3, 66, 0.5), axis, GroundMaterial.DIRT, SHAHED, 0).family());
    }

    /** Каменный настил моста в метр над водой (пустота для пробоя) — плита у ракеты и шахеда. */
    @Test
    void bridgeDeckIsASlab() {
        World deck = new World().fill(-6, 69, -20, 6, 69, 20, BreachModel.Material.ROCK);
        BlastTarget t = FarBlasts.target(deck, new Vec3(0.5, 70, 0.5), S2C.Blast.DOWN, GroundMaterial.STONE, SHAHED, 0);
        assertEquals(BlastTarget.Family.SLAB, t.family());
    }
}
