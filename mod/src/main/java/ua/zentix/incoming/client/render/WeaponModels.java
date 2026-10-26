package ua.zentix.incoming.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.entity.BomberEntity;
import ua.zentix.incoming.entity.FlightPhase;

import java.util.List;

/**
 * Projectile models: smooth OBJ meshes with textures from {@code tools/gen_models.py} (models/weapon, textures/block/weapon).
 * Loaded as additional block models (block atlas), decoded to {@link QuadMesh}, drawn through entity render type:
 * world light and shadows, under Iris shaders as ordinary entities. Moving parts are separate OBJs, rotated here
 * by flight phase: Shahed and Lancet propellers, missile and Lancet wings, missile intake, B-2 bomb bay doors,
 * launch boosters before separation. MLRS rack ({@link Mesh#ROCKET_RACK}) drawn by {@link LauncherRenderer};
 * SAM container rack ({@link Mesh#SAM_PACK}) by {@link SamRenderer}.
 * Pivots use the same numbers as gen_models.py.
 * <p>
 * Draw from projectile pose ({@link ProjectilePose}), rather than entity, supplying parts to {@link Parts}: shared
 * code draws nearby projectiles (vertices into entity render-type buffer, {@link Buffers}) and distant ones absent
 * on client ({@link FarModels}: parts in video memory, only their positions per frame). Parts have simplified copies
 * ({@code <part>_lod1}, gen_models.py) for models small on screen.
 */
public final class WeaponModels {
    private WeaponModels() {}

    /**
     * Model part: one OBJ and, if {@code lod}, its simplified {@code <name>_lod1} copy; {@code translucent} means
     * translucent (blurred propeller disc), drawn over opaque parts without depth writes.
     */
    public enum Mesh {
        DRONE_BODY("drone_body", true), DRONE_PROP("drone_prop", true), DRONE_DISC("drone_disc", false, true),
        DRONE_BOOSTER("drone_booster", true),
        MISSILE_BODY("missile_body", true), MISSILE_WING_L("missile_wing_l", true), MISSILE_WING_R("missile_wing_r", true),
        MISSILE_INTAKE("missile_intake", true), MISSILE_BOOSTER("missile_booster", true),
        BOMBER_BODY("bomber_body", true), BOMBER_DOOR_L_IN("bomber_door_l_in", false), BOMBER_DOOR_L_OUT("bomber_door_l_out", false),
        BOMBER_DOOR_R_IN("bomber_door_r_in", false), BOMBER_DOOR_R_OUT("bomber_door_r_out", false),
        BOMB_BODY("bomb_body", true), ICBM_BODY("icbm_body", true), ROCKET_BODY("rocket_body", true), ROCKET_RACK("rocket_rack_body", false),
        LOITER_BODY("loiter_body", true), LOITER_WINGS("loiter_wings", true), LOITER_PROP("loiter_prop", true), LOITER_DISC("loiter_disc", false, true),
        /** SAM missile: not drawn at distance (short flight, visible only in world), no simplified copy. */
        INTERCEPTOR_BODY("interceptor_body", false),
        /** SAM container rack above its block: stationary, not drawn at distance, no simplified copy. */
        SAM_PACK("sam_pack", false);

        /** OBJ name (models/weapon/&lt;name&gt;.obj). */
        public final String file;
        /** Simplified copy exists. */
        public final boolean lod;
        public final boolean translucent;
        private final ModelResourceLocation location, lodLocation;
        private final QuadMesh.Cached<QuadMesh> mesh, coarse;

        Mesh(String file, boolean lod) {
            this(file, lod, false);
        }

        Mesh(String file, boolean lod, boolean translucent) {
            this.file = file;
            this.lod = lod;
            this.translucent = translucent;
            this.location = location(file);
            this.lodLocation = lod ? location(file + "_lod1") : location;
            this.mesh = new QuadMesh.Cached<>(() -> compile(location));
            this.coarse = lod ? new QuadMesh.Cached<>(() -> compile(lodLocation)) : mesh;
        }

        private static ModelResourceLocation location(String file) {
            return ModelResourceLocation.standalone(Incoming.id("weapon/" + file));
        }

        private static QuadMesh compile(ModelResourceLocation location) {
            List<BakedQuad> quads = Minecraft.getInstance().getModelManager().getModel(location)
                    .getQuads(null, null, RandomSource.create(0), ModelData.EMPTY, null);
            return QuadMesh.builder().add(quads, new Matrix4f()).build();
        }

        /** Part mesh; coarse selects simplified copy if available (otherwise same full mesh). */
        QuadMesh mesh(boolean coarse) {
            return (coarse ? this.coarse : mesh).get();
        }

        /** Draw part in current coordinate system (nose +Z); coarse selects simplified copy if available. */
        public void draw(PoseStack pose, VertexConsumer vc, int light, float alpha, boolean coarse) {
            mesh(coarse).draw(pose.last(), vc, QuadMesh.white(alpha), light, OverlayTexture.NO_OVERLAY);
        }

        /** Full copy, opaque (launchers, spent booster). */
        public void draw(PoseStack pose, MultiBufferSource buffers, int light) {
            draw(pose, buffers.getBuffer(SOLID), light, 1, false);
        }
    }

    /** Render type for opaque and translucent parts (blurred propeller discs). */
    static final RenderType SOLID = RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS);
    static final RenderType TRANSLUCENT = RenderType.entityTranslucent(InventoryMenu.BLOCK_ATLAS);

    public static void register(ModelEvent.RegisterAdditional e) {
        for (Mesh m : Mesh.values()) {
            e.register(m.location);
            if (m.lod) e.register(m.lodLocation);
        }
    }

    /** Resources reloaded (F3+T, resource pack): projectile and launcher meshes rebuild from new models. */
    public static void baked(ModelEvent.BakingCompleted e) {
        QuadMesh.Cached.invalidateAll();
    }

    /** How to draw projectile in its axes (nose +Z) from its pose: which parts and where. */
    @FunctionalInterface
    public interface Look {
        void render(ProjectilePose p, PoseStack pose, Parts parts);
    }

    /** Model part destination. */
    @FunctionalInterface
    public interface Parts {
        /** Part in current pose coordinate system; coarse selects simplified copy, alpha sets translucent opacity. */
        void part(Mesh mesh, PoseStack pose, boolean coarse, float alpha);

        default void part(Mesh mesh, PoseStack pose, boolean coarse) {
            part(mesh, pose, coarse, 1);
        }
    }

    /** Parts as vertices into entity render-type buffers with world light (nearby projectile); one per renderer, render thread. */
    public static final class Buffers implements Parts {
        private MultiBufferSource buffers;
        private int light;

        public Buffers into(MultiBufferSource buffers, int light) {
            this.buffers = buffers;
            this.light = light;
            return this;
        }

        @Override
        public void part(Mesh mesh, PoseStack pose, boolean coarse, float alpha) {
            mesh.draw(pose, buffers.getBuffer(mesh.translucent ? TRANSLUCENT : SOLID), light, alpha, coarse);
        }
    }

    /** Booster still on projectile: on launcher and while burning. */
    private static boolean boosterAttached(ProjectilePose p) {
        return p.phase.ordinal() <= FlightPhase.BOOST.ordinal();
    }

    /** Deployment share (0…1) of a part deploying in {@code ticks} ticks after booster separation. */
    private static float deployed(ProjectilePose p, float delay, float ticks) {
        FlightPhase ph = p.phase;
        if (ph.ordinal() < FlightPhase.CLIMB.ordinal()) return 0;
        if (ph != FlightPhase.CLIMB) return 1;
        float k = Mth.clamp((p.phaseAge - delay) / ticks, 0, 1);
        return k * k * (3 - 2 * k);
    }

    // ---------------------------------------------------------------- Shahed

    /** Propeller pivot: Z axis. Propeller speed, rad/tick: engine already idles on the launcher. */
    public static void drone(ProjectilePose p, PoseStack pose, Parts parts) {
        parts.part(Mesh.DRONE_BODY, pose, p.coarse);
        if (boosterAttached(p)) parts.part(Mesh.DRONE_BOOSTER, pose, p.coarse);
        FlightPhase ph = p.phase;
        float rate = ph.onLauncher() ? 0.9f : ph == FlightPhase.TERMINAL ? 2.6f : 2.0f;
        pose.pushPose();
        pose.mulPose(new Quaternionf().rotationZ(p.age * rate));
        parts.part(Mesh.DRONE_PROP, pose, p.coarse);
        pose.popPose();
        // Blurred disc: denser at higher propeller speed.
        parts.part(Mesh.DRONE_DISC, pose, p.coarse, Mth.clamp((rate - 0.6f) / 1.6f, 0, 1));
    }

    // ---------------------------------------------------------------- cruise missile

    /** Wing pivots: (±0.21, 0, 0.45), rotation around Y; folded wing lies backward along the body. */
    private static final float WING_PIVOT_X = 0.21f, WING_PIVOT_Z = 0.45f;

    public static void missile(ProjectilePose p, PoseStack pose, Parts parts) {
        parts.part(Mesh.MISSILE_BODY, pose, p.coarse);
        if (boosterAttached(p)) parts.part(Mesh.MISSILE_BOOSTER, pose, p.coarse);
        float wings = deployed(p, 2, 10);
        for (int side = -1; side <= 1; side += 2) {
            pose.pushPose();
            pose.translate(side * WING_PIVOT_X, 0, WING_PIVOT_Z);
            pose.mulPose(new Quaternionf().rotationY(side * Mth.HALF_PI * (1 - wings)));
            pose.translate(-side * WING_PIVOT_X, 0, -WING_PIVOT_Z);
            parts.part(side > 0 ? Mesh.MISSILE_WING_L : Mesh.MISSILE_WING_R, pose, p.coarse);
            pose.popPose();
        }
        // Intake extends from belly after wings.
        float intake = deployed(p, 8, 6);
        pose.pushPose();
        pose.translate(0, 0.16f * (1 - intake), 0);
        parts.part(Mesh.MISSILE_INTAKE, pose, p.coarse);
        pose.popPose();
    }

    // ---------------------------------------------------------------- B-2

    /** Door pivots along bay edges (Z axis): inner at x = ±0.4, outer at ±3.0; y is wing underside there. */
    private static final float DOOR_HINGE_IN = 0.4f, DOOR_HINGE_OUT = 3.0f, DOOR_Y_IN = -0.93f, DOOR_Y_OUT = -0.68f;
    /** Blocks before drop point when doors open (≈ 10 flight ticks). */
    private static final double DOORS_OPEN_AHEAD = 120;

    public static void bomber(ProjectilePose p, PoseStack pose, Parts parts) {
        parts.part(Mesh.BOMBER_BODY, pose, p.coarse);
        float open = bayOpen(p);
        door(pose, parts, Mesh.BOMBER_DOOR_L_IN, DOOR_HINGE_IN, DOOR_Y_IN, -open);
        door(pose, parts, Mesh.BOMBER_DOOR_L_OUT, DOOR_HINGE_OUT, DOOR_Y_OUT, open);
        door(pose, parts, Mesh.BOMBER_DOOR_R_IN, -DOOR_HINGE_IN, DOOR_Y_IN, open);
        door(pose, parts, Mesh.BOMBER_DOOR_R_OUT, -DOOR_HINGE_OUT, DOOR_Y_OUT, -open);
    }

    /** Doors open approaching drop point, close one second after drop. */
    private static float bayOpen(ProjectilePose p) {
        if (p.phase == FlightPhase.EGRESS) return 1 - Mth.clamp((p.phaseAge - 20) / 20f, 0, 1);
        double d = Math.hypot(p.aimX - p.x, p.aimZ - p.z) - BomberEntity.releaseLine(p.y - p.aimY);
        return (float) Mth.clamp(1 - d / DOORS_OPEN_AHEAD, 0, 1);
    }

    /** Door rotates around Z at its pivot; angle sign points outward from bay. */
    private static void door(PoseStack pose, Parts parts, Mesh mesh, float hingeX, float hingeY, float open) {
        pose.pushPose();
        pose.translate(hingeX, hingeY, 0);
        pose.mulPose(new Quaternionf().rotationZ(open * 1.45f));
        pose.translate(-hingeX, -hingeY, 0);
        parts.part(mesh, pose, false);
        pose.popPose();
    }

    // ---------------------------------------------------------------- bomb and ICBM

    public static void bomb(ProjectilePose p, PoseStack pose, Parts parts) {
        // Bomb rotates slowly while falling; grid fins maintain rotation.
        pose.pushPose();
        pose.mulPose(new Quaternionf().rotationZ(p.age * 0.05f));
        parts.part(Mesh.BOMB_BODY, pose, p.coarse);
        pose.popPose();
    }

    /** MLRS rocket spins in flight, driven by canted stabilizer fins. */
    public static void rocket(ProjectilePose p, PoseStack pose, Parts parts) {
        pose.pushPose();
        if (!p.phase.onLauncher()) pose.mulPose(new Quaternionf().rotationZ(p.age * 0.5f));
        parts.part(Mesh.ROCKET_BODY, pose, p.coarse);
        pose.popPose();
    }

    /**
     * Lancet: wings folded along body on catapult, deploy immediately after push (draw cruciform wings growing out
     * from body), pusher propeller with blurred disc; steady throttle in orbit, full in dive.
     */
    public static void loiter(ProjectilePose p, PoseStack pose, Parts parts) {
        parts.part(Mesh.LOITER_BODY, pose, p.coarse);
        FlightPhase ph = p.phase;
        float open = ph.onLauncher() ? 0 : ph == FlightPhase.BOOST ? Mth.clamp((p.phaseAge - 2) / 6f, 0, 1) : 1;
        float span = 0.15f + 0.85f * open * open * (3 - 2 * open);
        pose.pushPose();
        pose.scale(span, span, 1);
        parts.part(Mesh.LOITER_WINGS, pose, p.coarse);
        pose.popPose();
        float rate = ph == FlightPhase.READY ? 0 : ph == FlightPhase.TERMINAL ? 3.0f : 2.2f;
        pose.pushPose();
        pose.mulPose(new Quaternionf().rotationZ(p.age * rate));
        parts.part(Mesh.LOITER_PROP, pose, p.coarse);
        pose.popPose();
        if (rate > 0) parts.part(Mesh.LOITER_DISC, pose, p.coarse, Mth.clamp((rate - 0.6f) / 1.6f, 0, 1));
    }

    /** SAM missile: body. */
    public static void interceptor(ProjectilePose p, PoseStack pose, Parts parts) {
        parts.part(Mesh.INTERCEPTOR_BODY, pose, p.coarse);
    }

    public static void icbm(ProjectilePose p, PoseStack pose, Parts parts) {
        parts.part(Mesh.ICBM_BODY, pose, p.coarse);
    }
}
