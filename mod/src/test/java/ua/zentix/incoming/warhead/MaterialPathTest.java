package ua.zentix.incoming.warhead;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MaterialPathTest {
    @Test
    void halfSlabAndDoorCountTheirShapeOnlyOnce() {
        var slab = Shapes.box(0, 0, 0, 1, 0.5, 1);
        assertEquals(0.5, MaterialPath.inside(slab, BlockPos.ZERO, new Vec3(0.5, 1, 0.5), new Vec3(0, -1, 0), 1), 1e-9);
        var door = Shapes.box(0, 0, 0, 3 / 16.0, 1, 1);
        assertEquals(0.0375, 0.2 * MaterialPath.inside(door, BlockPos.ZERO, new Vec3(0, 0.5, 0.5), new Vec3(1, 0, 0), 1), 1e-9);
    }

    @Test
    void obliquePathUsesTheActualChord() {
        var direction = new Vec3(1, 0, 1).normalize();
        assertEquals(Math.sqrt(2), MaterialPath.inside(Shapes.block(), BlockPos.ZERO, new Vec3(0, 0.5, 0), direction, 2), 1e-9);
        assertEquals(0, MaterialPath.inside(Shapes.box(0, 0, 0, 1, 0.5, 1), BlockPos.ZERO,
                new Vec3(0, 0.75, 0.5), new Vec3(1, 0, 0), 1), 1e-9);
    }
}
