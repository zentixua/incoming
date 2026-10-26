package ua.zentix.incoming.client.render;

import net.minecraft.world.level.block.Blocks;

/**
 * Launcher models built from blocks ({@link PartModel}): dimensions in blocks,
 * nose (launch direction) along +Z, +X port side. Trailer and rack drawn separately: rack pivots around
 * the transverse axis (see {@link ua.zentix.incoming.entity.LauncherMount}), extending along +Z in its own axes.
 */
public final class LaunchModels {
    private LaunchModels() {}

    /**
     * Trailer 2.5 × 7.6 m: platform, longitudinal beams, four axles (two pairs, front and rear), drawbar, four outriggers
     * with feet (launcher rests on them). Origin at trailer centre on ground.
     */
    public static final PartModel TRAILER = trailer();

    private static PartModel trailer() {
        PartModel.Builder b = PartModel.builder()
                .block(Blocks.GREEN_TERRACOTTA, -1.25f, 0.8f, -3.8f, 2.5f, 0.3f, 7.6f)
                .block(Blocks.GRAY_CONCRETE, -0.95f, 0.55f, -3.7f, 0.22f, 0.25f, 7.4f)
                .block(Blocks.GRAY_CONCRETE, 0.73f, 0.55f, -3.7f, 0.22f, 0.25f, 7.4f)
                // Drawbar and hitch.
                .block(Blocks.GRAY_CONCRETE, -0.08f, 0.6f, 3.8f, 0.16f, 0.14f, 1.6f)
                .block(Blocks.BLACK_CONCRETE, -0.2f, 0.52f, 5.3f, 0.4f, 0.22f, 0.25f)
                // Rack pivot-axis supports.
                .block(Blocks.GRAY_CONCRETE, -1.15f, 1.1f, -2.55f, 0.25f, 0.55f, 0.7f)
                .block(Blocks.GRAY_CONCRETE, 0.9f, 1.1f, -2.55f, 0.25f, 0.55f, 0.7f)
                // Elevation hydraulic cylinder.
                .block(Blocks.IRON_BLOCK, -0.12f, 1.1f, -0.9f, 0.24f, 0.24f, 1.6f);
        for (float z : new float[]{-2.75f, -1.65f, 1.65f, 2.75f}) {
            // Side wheels Ø 1 m and axle.
            b.block(Blocks.BLACK_CONCRETE, 0.95f, 0f, z - 0.5f, 0.35f, 1.0f, 1.0f);
            b.block(Blocks.BLACK_CONCRETE, -1.3f, 0f, z - 0.5f, 0.35f, 1.0f, 1.0f);
            b.block(Blocks.GRAY_CONCRETE, 1.02f, 0.35f, z - 0.15f, 0.2f, 0.3f, 0.3f);
            b.block(Blocks.GRAY_CONCRETE, -1.22f, 0.35f, z - 0.15f, 0.2f, 0.3f, 0.3f);
            b.block(Blocks.GRAY_CONCRETE, -0.95f, 0.42f, z - 0.08f, 1.9f, 0.16f, 0.16f);
        }
        for (float z : new float[]{-3.55f, 3.35f}) {
            for (int side = -1; side <= 1; side += 2) {
                // Outrigger beam and jack with foot.
                b.block(Blocks.GRAY_CONCRETE, side > 0 ? 1.25f : -1.75f, 0.85f, z, 0.5f, 0.16f, 0.2f);
                b.block(Blocks.IRON_BLOCK, side > 0 ? 1.6f : -1.72f, 0.1f, z + 0.02f, 0.12f, 0.75f, 0.16f);
                b.block(Blocks.GRAY_CONCRETE, side > 0 ? 1.5f : -1.82f, 0f, z - 0.1f, 0.32f, 0.1f, 0.4f);
            }
        }
        return b.build();
    }

    /**
     * Fixed launcher turntable ({@link ua.zentix.incoming.entity.LauncherMount#PAD}) on top of its block: table plate
     * and two cheeks with rack pivot trunnions (axis 0.25 above block top, over its centre). Origin at block bottom
     * centre; turns with the rack.
     */
    public static final PartModel PAD = pad();

    private static PartModel pad() {
        return PartModel.builder()
                .block(Blocks.GRAY_CONCRETE, -0.7f, 1.0f, -0.7f, 1.4f, 0.08f, 1.4f)
                .block(Blocks.GREEN_TERRACOTTA, -0.62f, 1.08f, -0.25f, 0.12f, 0.3f, 0.5f)
                .block(Blocks.GREEN_TERRACOTTA, 0.5f, 1.08f, -0.25f, 0.12f, 0.3f, 0.5f)
                .block(Blocks.IRON_BLOCK, -0.66f, 1.2f, -0.06f, 1.32f, 0.1f, 0.12f)
                .build();
    }

    /**
     * Five-Shahed rack (as on real trucks: 2.9 × 4.1 × 4 m): open frame, stacked cells, each with two rails under
     * the fuselage (booster hangs between them). Cell heights:
     * {@link ua.zentix.incoming.entity.LauncherEntity#railPoint}: Shahed centre at {@code 0.45 + 0.8·k}, 2 m from axis.
     */
    public static final PartModel DRONE_RACK = droneRack();

    private static PartModel droneRack() {
        float w = 2.9f, h = 4.1f, len = 4.0f, x0 = -w / 2, t = 0.1f;
        PartModel.Builder b = PartModel.builder();
        // Open frame: corner posts, longitudinal beams, lattice roof; Shaheds visible through it.
        for (float x : new float[]{x0 - t, x0 + w}) {
            for (float z : new float[]{-0.1f, 1.9f, len - 0.1f}) b.block(Blocks.GREEN_TERRACOTTA, x, -t, z, t, h + 2 * t, t);
            for (float y : new float[]{-t, h}) b.block(Blocks.GREEN_TERRACOTTA, x, y, -0.1f, t, t, len);
            // Side braces.
            for (int k = 0; k < 5; k++) b.block(Blocks.GRAY_CONCRETE, x + 0.02f, 0.45f + 0.8f * k - 0.28f, -0.1f, t - 0.04f, 0.05f, len);
        }
        for (float z : new float[]{-0.1f, 1.9f, len - 0.1f}) {
            b.block(Blocks.GREEN_TERRACOTTA, x0, h, z, w, t, t);
            b.block(Blocks.GREEN_TERRACOTTA, x0, -t, z, w, t, t);
        }
        // Rear wall: booster jet deflector.
        b.block(Blocks.GRAY_CONCRETE, x0, -t, -0.22f, w, h + 2 * t, 0.12f);
        for (int k = 0; k < 5; k++) {
            float y = 0.45f + 0.8f * k - 0.25f;
            b.block(Blocks.IRON_BLOCK, -0.19f, y, 0f, 0.05f, 0.05f, 3.8f);
            b.block(Blocks.IRON_BLOCK, 0.14f, y, 0f, 0.05f, 0.05f, 3.8f);
            // Crossmembers under rails.
            b.block(Blocks.GRAY_CONCRETE, x0, y - 0.05f, 0.3f, w, 0.05f, 0.08f);
            b.block(Blocks.GRAY_CONCRETE, x0, y - 0.05f, 3.3f, w, 0.05f, 0.08f);
        }
        return b.build();
    }

    /**
     * Two cruise missile transport-launch containers (missile with booster 6.4 m, Ø 0.52, tail fins up to 0.5 from
     * axis): square tubes 1.14 × 1.14 × 7 m, open front, with bands.
     * Axes: {@link ua.zentix.incoming.entity.LauncherEntity#railPoint}: ±0.62 from centre, 0.64 above rack bottom.
     */
    public static final PartModel MISSILE_RACK = missileRack();

    private static PartModel missileRack() {
        PartModel.Builder b = PartModel.builder();
        for (float cx : new float[]{0.62f, -0.62f}) {
            float x0 = cx - 0.57f, w = 1.14f, y0 = 0.07f, h = 1.14f, z0 = -0.3f, len = 7.0f, t = 0.06f;
            b.block(Blocks.GREEN_TERRACOTTA, x0, y0, z0, w, t, len);
            b.block(Blocks.GREEN_TERRACOTTA, x0, y0 + h - t, z0, w, t, len);
            b.block(Blocks.GREEN_TERRACOTTA, x0, y0, z0, t, h, len);
            b.block(Blocks.GREEN_TERRACOTTA, x0 + w - t, y0, z0, t, h, len);
            b.block(Blocks.GRAY_CONCRETE, x0, y0, z0 - 0.12f, w, h, 0.12f);
            for (float z : new float[]{0.9f, 3.3f, 5.7f}) b.block(Blocks.BLACK_CONCRETE, x0 - 0.03f, y0 - 0.03f, z, w + 0.06f, h + 0.06f, 0.18f);
        }
        return b.build();
    }

    /**
     * Loitering munition catapult (Lancet, 1.65 m, folded wings; rail 3.1 m run): five open rails, three below, two
     * above, each showing a projectile on a pusher carriage. Axes: {@link ua.zentix.incoming.entity.LauncherEntity#railPoint}.
     */
    public static final PartModel LOITER_RACK = loiterRack();

    private static PartModel loiterRack() {
        PartModel.Builder b = PartModel.builder();
        for (int slot = 0; slot < 5; slot++) {
            float cx = ua.zentix.incoming.entity.LauncherEntity.loiterLeft(slot), cy = ua.zentix.incoming.entity.LauncherEntity.loiterUp(slot);
            float y = cy - 0.17f;
            // Rail beam and two rails along its edges.
            b.block(Blocks.GREEN_TERRACOTTA, cx - 0.09f, y - 0.14f, -0.1f, 0.18f, 0.14f, 3.1f);
            b.block(Blocks.IRON_BLOCK, cx - 0.1f, y, -0.1f, 0.04f, 0.04f, 3.1f);
            b.block(Blocks.IRON_BLOCK, cx + 0.06f, y, -0.1f, 0.04f, 0.04f, 3.1f);
            // Catapult carriage under tail and stop.
            b.block(Blocks.GRAY_CONCRETE, cx - 0.12f, y, 0.15f, 0.24f, 0.08f, 0.35f);
            b.block(Blocks.BLACK_CONCRETE, cx - 0.06f, y + 0.02f, -0.05f, 0.12f, 0.12f, 0.2f);
        }
        // Frame: crossmembers under rails and upper-tier posts.
        float yLow = ua.zentix.incoming.entity.LauncherEntity.loiterUp(0) - 0.31f, yHigh = ua.zentix.incoming.entity.LauncherEntity.loiterUp(3) - 0.31f;
        for (float z : new float[]{0.1f, 1.4f, 2.7f}) {
            b.block(Blocks.GRAY_CONCRETE, -1.2f, yLow - 0.08f, z, 2.4f, 0.08f, 0.14f);
            b.block(Blocks.GRAY_CONCRETE, -0.8f, yHigh - 0.08f, z, 1.6f, 0.08f, 0.14f);
            b.block(Blocks.GREEN_TERRACOTTA, -0.8f, yLow, z, 0.1f, yHigh - yLow - 0.08f, 0.14f);
            b.block(Blocks.GREEN_TERRACOTTA, 0.7f, yLow, z, 0.1f, yHigh - yLow - 0.08f, 0.14f);
        }
        return b.build();
    }
}
