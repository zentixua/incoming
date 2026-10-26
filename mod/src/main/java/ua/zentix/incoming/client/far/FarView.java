package ua.zentix.incoming.client.far;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import ua.zentix.incoming.entity.StrikeProjectile;

/**
 * Frame data for all distant visuals: camera position and direction, world render range, pixel angle, skylight,
 * weather visibility range. No haze colour: haze is in opacity ({@link Sight}).
 *
 * @param camera  eye
 * @param forward view direction
 * @param left    screen left
 * @param up      screen up
 * @param partial tick fraction
 * @param far     beyond this, fold closer preserving angular size ({@link ua.zentix.incoming.client.render.FarDraw#fold})
 * @param pixel   one pixel's angle, rad
 * @param edge    screen-centre-to-corner angle, rad: beyond it, outside frame
 * @param ambient skylight 0.12..1 ({@link #ambient(ClientLevel, float)}): night darkens smoke/body, brightens flashes
 * @param range   visibility range, blocks ({@link Sight#range})
 * @param world   world render range (render distance, blocks): nearer, distant visuals yield to particles
 * @param clouds  dimension cloud height, blocks ({@code NaN} — no clouds: Nether, End)
 * @param rain    rain 0..1: overcast clouds
 * @param air     air from eye ({@link Sight.Air}: haze thins with altitude), from visibility range and eye height
 */
public record FarView(Vec3 camera, Vector3f forward, Vector3f left, Vector3f up, float partial, double far, double pixel, double edge,
                      float ambient, double exposure, Vector3f sky, double range, double world, double clouds, float rain, Sight.Air air) {
    /**
     * Skylight in noon-white-wall units (day 1, night ~8·10⁻⁴): eye adapts to it without fire
     * ({@link Sight#adapted}: ambient^{@value Sight#ADAPT}).
     */
    public double skyLuminance() {
        return Math.pow(ambient, Sight.ADAPT);
    }

    /** {@link #skyLuminance()} for {@code level} without frame view (no world — day). */
    public static double skyLuminance(@org.jetbrains.annotations.Nullable ClientLevel level, float partial) {
        return level == null ? 1 : Math.pow(ambient(level, partial), Sight.ADAPT);
    }

    /** Same view with frame eye adaptation exposure ({@link Sight#exposure}: fire at eye in this frame). */
    public FarView exposed(double exposure) {
        return new FarView(camera, forward, left, up, partial, far, pixel, edge, ambient, exposure, sky, range, world, clouds, rain, air);
    }

    /**
     * Screen skylight on airborne matter under open sky (rgb, {@link #sky}). Brightness matches Minecraft light
     * map on open-sky blocks and particles (block 0, sky 15): effects-layer smoke billows use it rather than
     * {@link #ambient}, which made white smoke darker at night than nearby moonlit stone despite higher reflectance.
     * Tint matches Minecraft clouds ({@link ClientLevel#getCloudColor}: white matter in sky), not light map:
     * map blues linearly with sky darkness ({@code (s, s, 1)} 35 % toward white); at sunset (time 13000, sun 3.5°
     * below horizon, sky still pink) blue/red ratio is 1.47, nearly night (1.58); clouds blue only after light
     * goes (sunset 1.07, night 1.5). Smoke and clouds are same matter under same light: shader-pack sunset frame
     * has grey clouds (blue/red 1.0–1.1), while light-map trail was dark blue.
     */
    public static Vector3f sky(net.minecraft.client.renderer.LightTexture light, ClientLevel level, float partial, Vector3f into) {
        Vec3 cloud = level.getCloudColor(partial);
        return open(lightmap(light, into), cloud.x, cloud.y, cloud.z, into);
    }

    /** Open-sky light map (block 0, sky 15), screen rgb. */
    public static Vector3f lightmap(net.minecraft.client.renderer.LightTexture light, Vector3f into) {
        int c = light.lightPixels.getPixelRGBA(0, 15);
        return into.set((c & 0xFF) / 255f, (c >> 8 & 0xFF) / 255f, (c >> 16 & 0xFF) / 255f);
    }

    /**
     * Open-sky light on airborne matter: {@code map} light-map brightness with cloud tint (r, g, b), written to into
     * (may be same vector as map). Clouds without light (zero brightness) — map unchanged.
     */
    static Vector3f open(Vector3f map, double r, double g, double b, Vector3f into) {
        double luma = luma(map.x(), map.y(), map.z()), cloud = luma(r, g, b);
        if (cloud <= 1e-6) return into.set(map);
        double k = luma / cloud;
        return into.set((float) Math.min(1, r * k), (float) Math.min(1, g * k), (float) Math.min(1, b * k));
    }

    /**
     * Per-channel ratio of airborne-matter light {@code sky} to open-sky light map {@code map}
     * ({@code FxSkyFix}: layer particles and ribbons take world light from map, applying this to sky share).
     */
    public static Vector3f fix(Vector3f sky, Vector3f map, Vector3f into) {
        return into.set(ratio(sky.x(), map.x()), ratio(sky.y(), map.y()), ratio(sky.z(), map.z()));
    }

    private static float ratio(float a, float b) {
        return b <= 1e-4f ? 1 : a / b;
    }

    private static double luma(double r, double g, double b) {
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /** {@link #sky} brightness (screen): normalizes fireball light on billows ({@code fx.vsh}). */
    public float skyLight() {
        return 0.2126f * sky.x() + 0.7152f * sky.y() + 0.0722f * sky.z();
    }

    /**
     * Eye adaptation ({@link Sight#exposure}) from skylight and fire light at eye in previous effects-layer frame
     * ({@code HeatPass.atEye}), for things drawn before layer (entity plume jet) and as layer-frame starting
     * value (layer takes this frame's fire itself, {@link #exposed}).
     */
    public static double exposure(ClientLevel level, float partial) {
        return Sight.exposure(ambient(level, partial), ua.zentix.incoming.client.fx.layer.HeatPass.atEye());
    }

    /** World skylight 0.12..1: day 1, night 0.17, night thunderstorm 0.12. */
    public static float ambient(ClientLevel level, float partial) {
        return Mth.clamp(level.getSkyDarken(partial) * 1.1f - 0.05f, 0.12f, 1f);
    }

    /**
     * Light eye adapted to, 0.12..1: open sky uses skylight ({@link #ambient}); cave, house and bunker cavity use
     * darkness based on skylight at eye; dimension without sky uses darkness.
     */
    public static float adaptation(ClientLevel level, Vec3 eye, float partial) {
        float sky = level.getBrightness(LightLayer.SKY, BlockPos.containing(eye)) / 15f;
        return Mth.lerp(sky, 0.12f, ambient(level, partial));
    }

    /** Light fraction from point d blocks from eye, dy blocks above, surviving air ({@link Sight.Air}). */
    public double air(double d, double dy) {
        return air.transmittance(d, dy);
    }

    /**
     * Distant blast visual share (fireball and fire; column at any range) at distance d: absent closer than
     * 0.8 render distance (particles there); full at render edge where fog consumes particles.
     */
    public double farShare(double d) {
        return farShare(d, world);
    }

    /** Minecraft frame far plane with margin, blocks: farther objects fold closer ({@link #far}). */
    public static double depthFar() {
        return Minecraft.getInstance().gameRenderer.getDepthFar() * 0.97;
    }

    /**
     * Range at which projectile entity draws its own model, blocks: up to {@link StrikeProjectile#DRAW_RANGE} and
     * frame far plane {@code far}; beyond, far view draws body ({@code FarFlightView}). Server sends entities by
     * horizontal distance, so high above player entity exists on client but its model clips beyond far plane
     * (4 render distances): B-2 overhead at real 12 km altitude (flight scale 1) would otherwise be wholly invisible.
     */
    public static double entityReach(double far) {
        return Math.min(StrikeProjectile.DRAW_RANGE, far);
    }

    /** {@link #farShare(double)} for render distance world blocks. */
    public static double farShare(double d, double world) {
        double a = 0.8 * world, t = (d - a) / Math.max(1, world - a);
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }
}
