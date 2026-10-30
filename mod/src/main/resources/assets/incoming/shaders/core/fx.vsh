#version 330

#moj_import <incoming:pall.glsl>
#moj_import <incoming:mushroom.glsl>
#moj_import <incoming:smoke.glsl>
#moj_import <incoming:pall_front.glsl>
#moj_import <incoming:fx_balls.glsl>
#moj_import <incoming:bloom.glsl>

// Effects layer (client/fx/layer): smoke, flames, sparks, flashes and everything distant in one pass after the world.
in vec3 Position;
in vec2 UV0;
// light already multiplied by opacity; alpha — how much of what is behind the particle is covered
in vec4 Color;
// x: softness of the edge at terrain, blocks × 16 (below zero — light at the eye from a source: minus the number of its
// probe,
// SourceProbes); y: how many times the point is moved nearer (beyond the far plane), × 32767
in ivec2 UV1;
// World lightmap; baked puffs store their geometry basis (puffBasis) here and use FULL_BRIGHT lighting.
in ivec2 UV2;
// x: Minecraft fog 0..1; y: 1 — particle (by the nearest pixel, as from the Minecraft atlas), −1 — a far-model atlas
// tile
// (the light is already in it), ½ — halo far/glow (large — by formula, fx.fsh), −¼ — a baked puff with
// six-sided light (FxAtlas.puff6), ⅝ — fire frames; z: opacity of a particle without texture, for a puff or fire — a
// mix of frames
in vec3 Normal;
// glow (FxBloom, FxQuads.emission): byte Emit — bloom.glsl
in float Emit;
// x: 1 — glow pass (FxBloom); y — how many times the pass pixel is larger than the frame pixel
uniform vec2 FxGlowPass;
// Half-resolution split (HalfPass): x — 1 clips each quad to its rectangle for this pass (an empty one drops it), HalfRect
// texel (quad % 1024, quad / 1024 + y) — NDC x0, y0, x1, y1; quad = vertex / 4, the sorted place. HalfRect is on its own
// texture unit (bound by the layer: the 12 named samplers are taken)
uniform vec4 FxHalfClip;
uniform sampler2D HalfRect;
// sorted place of the quad (fx.fsh: which side of its tile's cut)
flat out float quadPlace;

uniform sampler2D Sampler2;
// sky light on the substance in the air against the light map in the open sky (FarView.sky): the map paints blue
// already at sunset, Minecraft clouds — only when the light has gone; per channel, on screen
uniform vec3 FxSkyFix;
// visible share of the frame's sources (SourceProbes): probe i — pixel (i % width, i / width)
uniform sampler2D Sampler4;
// 1 — probes exist
uniform float FxProbes;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
// pall, dust wall and mushrooms of the frame — pall_front.glsl; fire storm: the fire maps of the slots and fire slots
// (pall.glsl) — light
// of fires from below on the layer's smoke and dust
uniform sampler2D Sampler7;
uniform sampler2D Sampler8;
uniform mat4 PallFire0;
uniform mat4 PallFire1;

out vec2 texCoord0;
out vec4 vertexColor;
out float viewDistance;
out float softness;
out float fog;
out float nearest;
out float opacity;
// how much the pall between the eye and the square passes (its light — in fx.fsh, by the pall on the frame)
out float pallFront;
// Baked puff: direct sun/moon share of skylight, fireball light relative to light at the eye (rgb),
// and directions toward direct light and fireballs in the puff's actual geometry and UV axes.
out float sixKey;
out vec3 sixBall;
out vec3 sixKeyDir;
out vec3 sixBallDir;
// the vertex's place from the camera (true, without moving nearer): light of the balls on a lying puff — in each pixel
// (fx.fsh)
out vec3 worldPos;
// glow: how many times the light is brighter than shown; 0 — does not glow
out float emitGain;
// 1 — spot of a small glowing thing (FxQuads, FxBloom.spots): halo by formula, light linear
out float emitSpot;
// in the sun (by day) or the moon (by night) in the world; w — share of direct light in the sky light (FxLayer.sun)
uniform vec4 FxSun;

// Two circles from UV2 (PuffGeometry.faceLight/flatLight), each storing sin/(|cos| + |sin|) in signed 15 bits.
// The sign of short x is the width-cosine sign; the sign of short y is UV mirroring. Elevation −16384 marks a horizontal plane.
// Returns (cos, sin) for width and elevation; for a horizontal plane z = −1 and w is the eye-facing normal sign.
// Two normalizations replace four sin/cos calls; four values replace a matrix. Geometry and the 36-byte vertex stay unchanged.
vec4 puffAxes(ivec2 code, float height) {
    float q = float((code.x & 16383) - (code.x & 16384)) / 16383.0;
    vec2 yaw = vec2(1.0 - abs(q), q);
    if (code.x < 0) yaw.x = -yaw.x;
    yaw = normalize(yaw);
    int p = (code.y & 16383) - (code.y & 16384);
    if (p == -16384) return vec4(yaw, -1.0, height > 0.0 ? -1.0 : 1.0);
    q = float(p) / 16383.0;
    return vec4(yaw, normalize(vec2(1.0 - abs(q), q)));
}

// World direction in UV axes: first the horizontal component toward the puff, then elevation. Mirroring changes
// only left/right; the normal still faces the eye. For a horizontal puff, map "up" runs across its width in the horizontal plane.
vec3 puffDirection(vec3 l, vec4 axes, bool mirror) {
    float right = -axes.x * l.x - axes.y * l.z;
    if (mirror) right = -right;
    float h = -axes.y * l.x + axes.x * l.z;
    if (axes.z < 0.0) return vec3(right, h, axes.w * l.y);
    return vec3(right, axes.z * l.y - axes.w * h, -axes.z * h - axes.w * l.y);
}

// light at point p (from the camera) by fireballs on the screen (share of white, eye adaptation — already in
// FxBallLight): at the surface
// of the ball — its irradiance, farther — as (r/d)²; from the knee on — not brighter than the ball's own fire on
// screen: a lit thing is never brighter than
// its source. Sky light on screen is not multiplied in here: the night is already in eye adaptation (Sight.exposure); a
// second time it dimmed
// fire on smoke and particles at night 3 times
vec3 balls(vec3 p) {
    vec3 sum = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        float r = FxBalls[i].w;
        if (r <= 0.0) continue;
        vec3 v = FxBalls[i].xyz - p;
        float d2 = max(dot(v, v), r * r), reach = BALL_REACH * r * BALL_REACH * r;
        vec3 add = FxBallLight[i].rgb * (r * r / d2) * (1.0 - smoothstep(0.25 * reach, reach, d2));
        sum += add / (1.0 + dot(add, LUMA) / max(FxBallLight[i].w, 1e-4));
    }
    return sum;
}

// light of a burning city from below at point p (from the camera): flames from the whole area ~2h wide under it, as
// glow on the pall
// (fx_pall), by half: a particle puff is lit from below only on one side; near the ground — stronger, higher up the
// smoke between them dims it
vec3 firelight(sampler2D map, mat4 S, mat4 F, vec3 p) {
    if (F[0].x <= 0.0) return vec3(0.0);
    float h = p.y - S[0].z;
    float flame = textureLod(map, fireUv(S, F, p.xz), fireGlowLod(h, F[0].y)).r;
    return F[3].rgb * (0.5 * F[1].w * flame * exp(-max(h, 0.0) / F[0].z));
}

// Pall, mushrooms and explosion smoke in front of point p (the real one, from the eye): the pall's volume is already on
// the frame (PallPass) — up to the world behind
// the pixel; a square nearer than the world must be behind that part of them which is between it and the eye
// (pallThrough; mushrooms — likewise, by their own
// noise-free field, smoke — by grid, smokeThrough)
float pall(vec3 p) {
    float len = length(p);
    if (PallAmbient.w < 0.5 || len < 1e-3) return 1.0;
    vec3 dir = p / len;
    return pallThrough(dir, len) * mushTransmit(Sampler11, dir, len, 6, PallAir) * smokeThrough(dir, len);
}

void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * view;
    vec4 clip = vec4(1.0);
    int quad = gl_VertexID / 4;
    quadPlace = float(quad);
    if (FxHalfClip.x > 0.5) {
        vec4 r = texelFetch(HalfRect, ivec2(quad % 1024, quad / 1024 + int(FxHalfClip.y)), 0);
        clip = vec4(gl_Position.x - r.x * gl_Position.w, r.z * gl_Position.w - gl_Position.x, gl_Position.y - r.y * gl_Position.w,
                r.w * gl_Position.w - gl_Position.y);
    }
    gl_ClipDistance[0] = clip.x;
    gl_ClipDistance[1] = clip.y;
    gl_ClipDistance[2] = clip.z;
    gl_ClipDistance[3] = clip.w;
    texCoord0 = UV0;
    float unfold = max(float(UV1.y) / 32767.0, 1e-4);
    bool six = Normal.y < -0.1 && Normal.y > -0.4;
    vec4 light;
    if (six) {
        // Puff UV2 stores a basis, not lightmap coordinates; FULL_BRIGHT bypasses the ordinary particle-lighting branch.
        light = texelFetch(Sampler2, ivec2(15), 0);
    } else {
        // Glow-spot UV2 stores its light (FxBloom.magnitude), not lightmap coordinates.
        light = Normal.y < -0.5 || Emit > 129.5 ? vec4(1.0) : texelFetch(Sampler2, UV2 / 16, 0);
        if (Normal.y > -0.5 && Emit < 129.5 && any(lessThan(UV2, ivec2(240)))) {
            // Sky share at this point (sky 0..15): tint it like material under open sky, leaving block light unchanged.
            light.rgb *= mix(vec3(1.0), FxSkyFix, float(UV2.y) / 240.0);
            // Add fireball light to the lightmap relative to open-sky light on screen, capped at daylight brightness.
            // The screen cannot show more; self-lit material (FULL_BRIGHT) does not receive it.
            vec3 add = balls(Position / unfold);
            if (PallAmbient.w > 0.5) add += firelight(Sampler7, PallSlot0, PallFire0, Position / unfold) + firelight(Sampler8, PallSlot1, PallFire1, Position / unfold);
            light.rgb += add / max(1.0, dot(add, LUMA));
        }
    }
    // glare and veil at the eye are cut by nothing in front of them: they are visible as far as the source itself is
    // visible in the frame
    float seen = 1.0;
    if (UV1.x < 0 && FxProbes > 0.5) {
        int i = -UV1.x - 1, w = textureSize(Sampler4, 0).x;
        seen = texelFetch(Sampler4, ivec2(i % w, i / w), 0).r;
    }
    vertexColor = vec4(Color.rgb * light.rgb, Color.a) * seen;
    sixKey = 0.0;
    sixBall = vec3(0.0);
    sixKeyDir = vec3(0.0, 1.0, 0.0);
    sixBallDir = vec3(0.0, 1.0, 0.0);
    if (six) {
        // Puff vertex color is material under open sky (SmokePuffs, FarView.sky); frame maps distribute its directional light (fx.fsh).
        // Evaluate fireballs first so the puff axes and sun direction do not stay live across the entire lighting loop.
        vec3 bd;
        sixBall = ballsToward(Position / unfold, bd);
        vec4 axes = puffAxes(UV2, Position.y);
        sixKey = FxSun.w;
        sixKeyDir = puffDirection(normalize(FxSun.xyz), axes, UV2.y < 0);
        sixBallDir = puffDirection(bd, axes, UV2.y < 0);
        if (dot(sixBallDir, sixBallDir) > 1e-8) sixBallDir = normalize(sixBallDir);
    }
    // true distance along the view axis — the point moved nearer is taken back
    viewDistance = -view.z / unfold;
    worldPos = Position / unfold;
    softness = float(max(UV1.x, 0)) / 16.0;
    fog = Normal.x;
    nearest = Normal.y;
    opacity = Normal.z;
    emitGain = 0.0;
    emitSpot = 0.0;
    if (Emit > 129.5) {
        emitSpot = 1.0;
        emitGain = 1.0;
        // spot light is linear: hue — in the vertex colour, magnitude — in UV2.x, covered share — in UV2.y
        // (FxBloom.magnitude)
        vertexColor = vec4(Color.rgb * bloomMag(float(UV2.x)), bloomMag(float(UV2.y)));
    } else if (Emit > 1.5) {
        emitGain = bloomGain(Emit - 2.0);
    }
    // a spot — only in the glow pass; a square that glows by spots — only in the layer: the other one is beyond the
    // frame
    bool glowPass = FxGlowPass.x > 0.5;
    if ((Emit > 129.5 && !glowPass) || (Emit > 0.5 && Emit < 1.5 && glowPass)) gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
    // glare and veil with a probe: the pall and mushrooms in front of the source — already in it (fx_probe, up to the
    // source itself); at the corners
    // of the square at the eye they must not be counted — light at the eye comes from the source, and the glare's
    // corner in its own cap would dim it above it
    pallFront = 1.0;
    if (UV1.x >= 0 || FxProbes < 0.5) pallFront = pall(Position / unfold);
}
