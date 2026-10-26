package ua.zentix.incoming.client.render;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The depth of LOD, which was recorded by the Iris shader pack program, is read by the layer as a projection: the distance is the same as that of a point.
 *  Numbers are like Artem on a laptop: rendering 12, frame 2048×1272, in the matrix DH is the closest 7,5 of the block, in the parameters ~23.
 */
class DhDepthTest {
    private static final float FOV = (float) Math.toRadians(70), ASPECT = 2048f / 1272f;
    private static final float DH_NEAR = 7.5f, PARAM_NEAR = 23.1f, FAR = 6516;

    /** Projection DH, as it is built by {@code RenderUtil.setDhProjectionMatrix} with direct depth: Minecraft and its planes. */
    private static Matrix4f dhProjection() {
        return new Matrix4f().setPerspective(FOV, ASPECT, 0.05f, 768).m22((FAR + DH_NEAR) / (DH_NEAR - FAR))
                .m32(2 * FAR * DH_NEAR / (DH_NEAR - FAR));
    }

    /** The depth in texture (0..1) of a point on the visual axis in d blocks, recorded by the p projection. */
    private static float depth(Matrix4f p, float d) {
        Vector4f clip = p.transform(new Vector4f(0, 0, -d, 1));
        return clip.z / clip.w * 0.5f + 0.5f;
    }

    /** The distance along the axis of view from depth is as in the {@code fx_depth} shader. */
    private static float along(Matrix4f inverse, float depth) {
        Vector4f view = inverse.transform(new Vector4f(0, 0, depth * 2 - 1, 1));
        return -view.z / view.w;
    }

    @Test
    void irisDepthReadsAtItsDistance() {
        // the pack program writes depth in perspective Iris: angle and sides of the frame, plane parameters DH
        Matrix4f iris = new Matrix4f().setPerspective(FOV, ASPECT, PARAM_NEAR, FAR);
        Matrix4f inverse = DhDepth.irisPlanes(dhProjection(), PARAM_NEAR, FAR).invert();
        for (float d : new float[] {200, 1500, 3000, 5000}) {
            assertEquals(d, along(inverse, depth(iris, d)), d * 1e-3f, "LOD in " + d + " blocks");
        }
    }

    /**
     * Projection of DH without shaderpack - and when Iris stands without it (DH 3.3: {@code getDepthDirection} - {@code REVERSE_Z},
     * until shaderpack is enabled): {@code setClipPlanes(far, near, true)} - reverse depth, NDC from 0 to 1 in the matrix.
     */
    private static Matrix4f dhReverse() {
        return new Matrix4f().setPerspective(FOV, ASPECT, 0.05f, 768).m22(DH_NEAR / (FAR - DH_NEAR)).m32(DH_NEAR * FAR / (FAR - DH_NEAR));
    }

    @Test
    void reverseDepthWithoutShaderPackReadsAtItsDistance() {
        // like the Artem laptop: Sodium, Iris without shaderpack, DH 3.3 - LOD draws itself DH with reverse depth: in texture
        // 0,5..1 to the far plane DH and just below 0,5 behind it (default range OpenGL), clearing 0; no frame substitution
        //  - do not touch the DH matrix
        assertFalse(DhDepth.irisDrawn(false, false, true));
        assertFalse(DhDepth.irisDrawn(true, false, true), "reverse depth perspective Iris do not read");
        assertTrue(DhDepth.irisDrawn(true, true, true));
        Matrix4f inverse = dhReverse().invert();
        for (float d : new float[] {DH_NEAR, 40, 200, 1500, 3000, 6000, FAR, 20_000}) {
            float depth = depth(dhReverse(), d);
            assertTrue(depth > 0 && depth <= 1, "depth " + depth + " in " + d + " blocks");
            assertEquals(d, DhDepth.along(inverse, depth, true), d * 2e-3f, "LOD in " + d + " blocks");
        }
        // a pixel without LOD (cleaning 0) is read “behind the eye”: it is cut off by comparison with empty depth, and if not,
        //  distance check in fx_depth (only in front of the eye and final)
        assertTrue(DhDepth.along(inverse, 0, true) < 0);
    }

    @Test
    void dhMatrixAloneMisreadsIrisDepth() {
        // the depth Iris, read by one matrix DH, puts LOD cities at 3 km closer than the pillars at 1,5 km
        Matrix4f iris = new Matrix4f().setPerspective(FOV, ASPECT, PARAM_NEAR, FAR);
        float misread = along(dhProjection().invert(), depth(iris, 3000));
        assertTrue(misread < 1500, "read " + misread);
    }
}
