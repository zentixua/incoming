package ua.zentix.incoming.client.far;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.layer.FxAtlas;
import ua.zentix.incoming.client.fx.layer.FxQuads;
import ua.zentix.incoming.client.fx.layer.SourceProbes;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Effects-layer quad parts (GPU timing by part, {@code LayerTimes}): each distant drawing writes quads with
 * its own part and restores previous part; next quad is “other” again. Otherwise clump time would be charged to ribbons,
 * fire time to glare, and measurement would identify the wrong cause.
 */
class LayerPartsTest {
    private final FxQuads quads = new FxQuads();
    private final FarSprites sprites = new FarSprites();

    @Test
    void everyDrawingWritesItsOwnPartAndHandsBackThePrevious() {
        FarView view = view();
        quads.begin(view.left(), view.up());
        SourceProbes probes = new SourceProbes();
        sprites.begin(view, quads, probes);
        double[] light = new double[5];
        expect(EnumSet.of(FxQuads.Part.CLUBS), () -> sprites.puff6(0, 2, -40, 4, 3, FxAtlas.puff6(0, false), 0, 1, 0.5f, 0.5f, 0.5f, 0.8f));
        expect(EnumSet.of(FxQuads.Part.CLUBS), () -> sprites.puff6Flat(0, -3, -30, 6, 6, 2, 0, FxAtlas.puff6(1, false), 0, 1, 0.5f, 0.5f, 0.5f, 0.6f));
        expect(EnumSet.of(FxQuads.Part.CLUBS), () -> sprites.puff(3, 1, -60, 5, 0, 0, 0.4f, 0.4f, 0.4f, 0.7f));
        expect(EnumSet.of(FxQuads.Part.VEIL), () -> sprites.veil(0, 0, -1, 4, 0.5f, 0.5f, 0.5f, 0.5f));
        expect(EnumSet.of(FxQuads.Part.RIBBONS), () -> sprites.ribbon(-30, 5, -80, 1.5, 0.6f, LightTexture.FULL_BRIGHT, 30, 8, -90, 1.5, 0.6f,
                LightTexture.FULL_BRIGHT, 0.7f, 0.7f, 0.7f, new float[]{Float.NaN, 0, 0}));
        expect(EnumSet.of(FxQuads.Part.FIRE), () -> sprites.glowDisc(0, 0, -20, 0.3, 1, 0.6f, 0.2f, 0, 2));
        expect(EnumSet.of(FxQuads.Part.FIRE), () -> sprites.glowPlate(new Vector3f(0, 0, -15), new Vector3f(0.3f, 0, 0), new Vector3f(0, 0.3f, 0),
                1, 0.6f, 0.2f, 15, 2));
        expect(EnumSet.of(FxQuads.Part.GLARE), () -> sprites.glow(10, 4, -200, 8, 20, 1, 0.5f, 0.2f, 0.5f));
        // fireball: body is fire, eye glare and veil are glare
        expect(EnumSet.of(FxQuads.Part.FIRE, FxQuads.Part.GLARE), () -> sprites.shaped(0, 5, -120, 6, FxAtlas.ball(0.2f), 7, 0, 3, 1, view.pixel(),
                1, 0.7f, 0.3f, 1, 1, 1, 2, light));
        expect(EnumSet.of(FxQuads.Part.OTHER), () -> sprites.disc(-5, 3, -300, 1, 0.2f, 0.2f, 0.2f, 1));
    }

    /** Drawing writes quads only of want parts (each at least once), next quad uses previous part again. */
    private void expect(Set<FxQuads.Part> want, Runnable draw) {
        int from = quads.size();
        draw.run();
        Set<FxQuads.Part> got = EnumSet.noneOf(FxQuads.Part.class);
        for (int i = from; i < quads.size(); i++) got.add(quads.partOf(i));
        assertEquals(want, got, "drawing quad parts");
        quads.quad(1);
        for (int k = 0; k < 4; k++) quads.vertex(k % 2, k / 2, -1, 0, 0, 0, 0, 0, 0, 0, 1, LightTexture.FULL_BRIGHT, 0);
        assertEquals(FxQuads.Part.OTHER, quads.partOf(quads.size() - 1), "previous part restored after drawing");
        assertTrue(quads.size() > from + 1, "drawing wrote quads");
    }

    private static FarView view() {
        Vec3 eye = new Vec3(0, 80, 0);
        Vector3f f = new Vector3f(0, 0, -1), left = new Vector3f(-1, 0, 0), up = new Vector3f(0, 1, 0);
        return new FarView(eye, f, left, up, 0, 1000, 2 * Math.tan(Math.toRadians(35)) / 720, Math.toRadians(55), 1, 1, new Vector3f(1, 1, 1),
                8000, 192, 192, 0, Sight.Air.at(0, 20000));
    }
}
