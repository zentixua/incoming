#version 330

#moj_import <incoming:pall.glsl>
#moj_import <incoming:fx_balls.glsl>
#moj_import <incoming:bloom.glsl>

// Effects layer (client/fx/layer). Blending ONE, ONE_MINUS_SRC_ALPHA: colour — light already multiplied by
// opacity, alpha — how much of what lies behind the particle is covered (sparks and halos: 0 — light adds up).
// The sheet texture is also premultiplied by alpha. Faint (alpha below 0.1) is not discarded, as in vanilla shaders:
// distant smoke and glow are exactly the faint ones.
uniform sampler2D Sampler0;
// distance to the world behind the pixel (SceneDepth)
uniform sampler2D Sampler1;
// atlas of distant models (client/render/FarModels), premultiplied by alpha, with downscaled copies
uniform sampler2D Sampler3;
// pall on the frame (PallPass, smaller than the screen): its light up to the world behind the pixel (rgb) and how much it passes (a)
uniform sampler2D Sampler6;
// average pall light (rgb); w — 1 if a pall is on the frame
uniform vec4 PallAmbient;
// place of the halo far/glow on the sheet (u0, v0, u1, v1; FxAtlas.glow)
uniform vec4 FxGlow;
// Ball: frame size (u, v), start (v), number of frames. Fire maps are mixed to alpha-over: without loss of opacity.
uniform vec4 FxFireball;
// motion vectors of the ball's frames: map size (u, v), start (v), displacement limit — share of a frame (FxAtlas.FIREBALL_FLOW / 256)
uniform vec4 FxFireFlow;

uniform vec4 ColorModulator;
uniform vec2 ScreenSize;
uniform vec4 FxFogColor;
// 1 — distance to the world is present
uniform float FxScene;
// 1 — half-resolution smoke target (HalfPass): it has no hardware depth, so the world hides smoke by its distance; the
// composite adds the 8-bit noise
uniform float FxHalf;
// Split of the layer between frame and half-resolution target by tile (HalfPass): x — 0 none, 1 draw only where the quad
// lies behind (or is) its tile's last full-resolution quad, 2 only where it lies in front; z — tile side, frame pixels.
// HalfCut — per tile, sorted place of its last full-resolution quad (−1 — none), on its own texture unit (bound by the
// layer, as PallOpen: the 12 named samplers are taken)
uniform vec4 FxHalfSplit;
uniform sampler2D HalfCut;
flat in float quadPlace;

in vec2 texCoord0;
in vec4 vertexColor;
in float viewDistance;
in float softness;
in float fog;
in float nearest;
in float opacity;
in float pallFront;
in float sixKey;
in vec3 sixBall;
in vec3 sixKeyDir;
in vec3 sixBallDir;
in vec3 worldPos;
// glow (FxBloom): how many times brighter than shown the light is; 0 — does not glow
in float emitGain;
// 1 — a spot of small glow (FxBloom.spots): halo far/glow by the formula at any size, colour — linear light
in float emitSpot;

// baked puff (FxAtlas.puff6): x — step of the maps across the sheet (v), y — start of the frames (v), z and w — frame size (u, v)
uniform vec4 FxSix;
// x — largest displacement of the motion vectors, share of a frame; y and z — the puff's average light from the front and the average over the six
// sides in linear (puff6.json); the maps are brought to the six-side average
uniform vec3 FxSixLight;
// tint of sky light at puffs (FarView.sky against its brightness, linear): the puff's vertex colour carries only the brightness
uniform vec3 FxSkyTint;

// Layer pass — colour; glow pass (FxBloom, FxGlowPass.x = 1) — emission that reaches the eye: rgb — linear light
// (white screen — 1) with premultiplied alpha, alpha — the same as the colour's. Blending is the same: smoke in front of fire dims the emission
// by the same share
layout(location = 0) out vec4 fragColor;
// Glow pass: the share of the pixel occupied by the glowing thing itself. Fire is shown at its own exposure, and its halo,
// computed with the model's overexposure, would brighten the folds and soot on it — the halo lies around
layout(location = 1) out vec4 fragCover;
// x: 1 — glow pass (at the level of the FxBloom.glowLevel pyramid: emission and the glowing share, without light on smoke; what is behind
// the world — by distance to the world, it has no hardware depth); y — how many times larger the glow pass pixel is than the frame pixel
uniform vec2 FxGlowPass;

// Six-way lighting: a contains right/top/back maps, b left/bottom/front (linear puff light from a sun on that side,
// tools/bake_fx). Light direction l points toward the source in map axes; weights are squared cosines and sum to 1
// for every direction. Unsquared cosines would make a sun at 45° sqrt(2) times brighter than one directly to the side.
// The map basis used to obtain l already includes horizontal mirroring.
float sixLight(vec3 a, vec3 b, vec3 l) {
    vec3 q = l * l;
    float side = l.x > 0.0 ? a.r : b.r;
    float vertical = l.y > 0.0 ? a.g : b.g;
    float depth = l.z < 0.0 ? a.b : b.b;
    return side * q.x + vertical * q.y + depth * q.z;
}

// Downscaled copies of the sheet (FxAtlas.MIPS)
const float FRAME_LEVELS = 4.0;

// Inset of the sample from the edge of the puff and fire frames (sixFrames, fireFrames), in sheet texels: half a texel of the coarsest copy the sample reads (level —
// up from log2 of the sample step span, sheet texels per pixel; that is how OpenGL chooses; ceil — through floor: that is how it is computed
// and in PuffAtlasEdgeGlslTest). With an inset of half a sheet texel, the sample reaches the neighbouring frame already on the first copy, and on
// copies 3–4 its edge shows: distant puffs and a flat puff seen edge-on came out as thin lines across the frame (dust
// of torn-up sod, WV)
float frameInset(float span) {
    float level = clamp(-floor(-0.5 * log2(max(span * span, 1.0))), 0.0, FRAME_LEVELS);
    return 0.5 * pow(2.0, level);
}

// The puff's frame k and the next, shifted along the motion vectors by the mix share m (frame k — back by m, k + 1 — forward by
// 1 − m): the puff flows instead of showing doubled. Maps a and b — in linear light, alpha — in a.w
// Places of the puff's frame k and the next on the sheet, shifted along the motion vectors by the mix share m (shared by sixFrames and
// sixAlpha)
void sixPlaces(vec2 uv, vec2 dx, vec2 dy, float m, out vec2 uv0, out vec2 uv1, out float lod) {
    vec2 tile = FxSix.zw;
    vec2 cell = floor(vec2(uv.x / tile.x, (uv.y - FxSix.y) / tile.y));
    vec2 origin = vec2(cell.x * tile.x, FxSix.y + cell.y * tile.y);
    vec2 next = origin + vec2(tile.x, 0.0);
    if (cell.x > 6.5) next = vec2(0.0, origin.y + tile.y);
    vec2 local = uv - origin;
    // Isotropic atlas: reuse the original perspective footprint for all six-way samples.
    vec2 size = vec2(textureSize(Sampler0, 0)), gx = dx * size, gy = dy * size;
    // The lower bound only avoids log2(0); retain negative LOD for sampler magnification.
    lod = 0.5 * log2(max(max(dot(gx, gx), dot(gy, gy)), 1e-20));
    vec4 v = textureLod(Sampler0, uv + vec2(0.0, 2.0 * FxSix.x), lod);
    vec2 flow = (v.rg / max(v.a, 1e-3) - 0.5) * 2.0 * FxSixLight.x * tile;
    vec2 edge = frameInset(sqrt(max(dot(gx, gx), dot(gy, gy)))) / size;
    uv0 = clamp(origin + local - flow * m, origin + edge, origin + tile - edge);
    uv1 = clamp(next + local + flow * (1.0 - m), next + edge, next + tile - edge);
}

void sixFrames(vec2 uv, vec2 dx, vec2 dy, float m, out vec4 a, out vec3 b) {
    vec2 uv0, uv1;
    float lod;
    sixPlaces(uv, dx, dy, m, uv0, uv1, lod);
    vec4 a0 = textureLod(Sampler0, uv0, lod), a1 = textureLod(Sampler0, uv1, lod);
    // transparent edge of the frame (half of the puff's square) — without the second map and light: the layer's cost is the fill of large puffs
    if (mix(a0.a, a1.a, m) < 0.002) discard;
    vec4 b0 = textureLod(Sampler0, uv0 + vec2(0.0, FxSix.x), lod), b1 = textureLod(Sampler0, uv1 + vec2(0.0, FxSix.x), lod);
    // on the sheet — premultiplied alpha and an exponent of 1/2.2
    vec3 la0 = pow(a0.rgb / max(a0.a, 1e-3), vec3(2.2)), la1 = pow(a1.rgb / max(a1.a, 1e-3), vec3(2.2));
    vec3 lb0 = pow(b0.rgb / max(b0.a, 1e-3), vec3(2.2)), lb1 = pow(b1.rgb / max(b1.a, 1e-3), vec3(2.2));
    a = vec4(mix(la0, la1, m), mix(a0.a, a1.a, m));
    b = mix(lb0, lb1, m);
}

// Opacity of a puff frame with the mix — the same places as sixFrames, without light maps: the glow pass only dims smoke
float sixAlpha(vec2 uv, vec2 dx, vec2 dy, float m) {
    vec2 uv0, uv1;
    float lod;
    sixPlaces(uv, dx, dy, m, uv0, uv1, lod);
    return mix(textureLod(Sampler0, uv0, lod).a, textureLod(Sampler0, uv1, lod).a, m);
}

// Fire frames — as for the baked puff (sixFrames): frame k and the next, shifted along the motion vectors of frame k by
// the mix share m (k — back by m, k + 1 — forward by 1 − m), mixed in one fragment with premultiplied alpha (mixing frames
// with motion vectors is a common flipbook technique: Unity VFX Graph, Unreal Niagara). Fire flows between frames instead of
// showing doubled; two squares with weights would give 0.75 opacity halfway instead of 1, and a pulse of brightness
vec4 fireFrames(vec2 uv, vec2 dx, vec2 dy, float m) {
    vec2 tile = FxFireball.xy;
    float columns = floor(1.0 / tile.x + 0.5), frames = FxFireball.w;
    vec2 cell = clamp(floor((uv - vec2(0.0, FxFireball.z)) / tile), vec2(0.0), vec2(columns - 1.0, ceil(frames / columns) - 1.0));
    float index = cell.y * columns + cell.x;
    vec2 origin = vec2(cell.x * tile.x, FxFireball.z + cell.y * tile.y);
    float nextIndex = min(index + 1.0, frames - 1.0);
    vec2 next = vec2(mod(nextIndex, columns) * tile.x, FxFireball.z + floor(nextIndex / columns) * tile.y);
    vec2 local = uv - origin;
    // motion vectors of frame k: its own 128² map (FxFireFlow: size, start, displacement limit — share of a frame)
    float flowColumns = floor(1.0 / FxFireFlow.x + 0.5);
    vec2 flowAt = vec2(mod(index, flowColumns) * FxFireFlow.x, FxFireFlow.z + floor(index / flowColumns) * FxFireFlow.y);
    vec2 toFlow = FxFireFlow.xy / tile;
    vec4 v = textureGrad(Sampler0, flowAt + local * toFlow, dx * toFlow, dy * toFlow);
    vec2 flow = (v.rg / max(v.a, 1e-3) - 0.5) * 2.0 * FxFireFlow.w * tile;
    vec2 size = vec2(textureSize(Sampler0, 0)), gx = dx * size, gy = dy * size;
    vec2 edge = frameInset(sqrt(max(dot(gx, gx), dot(gy, gy)))) / size;
    vec2 uv0 = clamp(origin + local - flow * m, origin + edge, origin + tile - edge);
    vec2 uv1 = clamp(next + local + flow * (1.0 - m), next + edge, next + tile - edge);
    return mix(textureGrad(Sampler0, uv0, dx, dy), textureGrad(Sampler0, uv1, dx, dy), m);
}

// Particle up close — as from the Minecraft atlas (NEAREST_MIPMAP_LINEAR): the nearest pixel of the picture and its copy half
// the size, mixed between them; smoothed, it would come out soapy. Copies half that size or smaller (a puff of 32 pixels and less) —
// smooth, like everything distant: the steps only shimmer there. The copy level — as in OpenGL, by derivatives.
vec4 particle(vec2 uv, vec2 dx, vec2 dy, out float lod) {
    vec2 size = vec2(textureSize(Sampler0, 0));
    vec2 px = dx * size, py = dy * size;
    lod = max(0.5 * log2(max(max(dot(px, px), dot(py, py)), 1e-12)), 0.0);
    if (lod >= 1.0) return textureGrad(Sampler0, uv, dx, dy);
    vec2 size1 = size * 0.5;
    vec4 a = textureLod(Sampler0, (floor(uv * size) + 0.5) / size, 0.0);
    vec4 b = textureLod(Sampler0, (floor(uv * size1) + 0.5) / size1, 1.0);
    return mix(a, b, lod);
}

// Halo far/glow (tools/gen_particles.py, far_glow) at distance r from the middle in fractions of the half-size: exp(−(r/0.42)²),
// at the edge — zero without a step; the picture's peak — at the four middle pixels (r = √2/63), it is 1
float glowAlpha(float r) {
    return exp(-(r / 0.42) * (r / 0.42)) * smoothstep(0.0, 0.25, 1.0 - r) / 0.997147;
}

// Emission of fragment c (screen values) in linear light × the overexposure gain the screen clipped, no brighter than the white point
// (BLOOM_WHITE): for particles and fire frames (cover) the colour is multiplied by opacity — the light of the colour by the same share; for cores and
// torches the colour is the light itself on top
vec3 bloomLight(vec4 c, float cover, float gain) {
    vec3 shown = c.rgb;
    float a = 1.0;
    if (cover > 0.5 && c.a > 0.004) {
        a = c.a;
        shown = c.rgb / a;
    }
    vec3 light = pow(clamp(shown, 0.0, 1.0), vec3(2.2)) * gain;
    return light * min(1.0, BLOOM_WHITE / max(max(light.r, max(light.g, light.b)), 1e-6)) * a;
}

void main() {
    // derivatives — before the branch (outside it they are defined for all pixels)
    vec2 dx = dFdx(texCoord0), dy = dFdy(texCoord0);
    if (FxHalfSplit.x > 0.5) {
        bool behind = quadPlace <= texelFetch(HalfCut, ivec2(gl_FragCoord.xy * FxGlowPass.y / FxHalfSplit.z), 0).r;
        if (behind != (FxHalfSplit.x < 1.5)) discard;
    }
    float lod = 0.0;
    vec4 tex;
    bool six = nearest < -0.1 && nearest > -0.4;
    vec3 sixLit = vec3(1.0);
    if (emitSpot > 0.5) {
        // the spot — always by formula: sampling at pixel middles carries the same energy at any shift (FxBloomTest)
        vec2 local = ((texCoord0 - FxGlow.xy) / (FxGlow.zw - FxGlow.xy) * 2.0 - 1.0) * (64.0 / 63.0);
        tex = vec4(glowAlpha(length(local)));
    } else if (nearest > 0.75) {
        tex = particle(texCoord0, dx, dy, lod);
    } else if (six && FxGlowPass.x > 0.5) {
        tex = vec4(sixAlpha(texCoord0, dx, dy, opacity));
    } else if (six) {
        vec4 a;
        vec3 b;
        sixFrames(texCoord0, dx, dy, opacity, a, b);
        // sky light: direct (sun, moon) — by sides, diffuse — the average over six; both — against the average over six
        // sides per puff (the vertex colour — a thick layer of matter in diffuse sky light, R∞): towards the sun the translucent
        // edge is brighter, on the sun's side the dense puff is darker (forward scattering). Fireballs — by sides, in fractions of light
        // at the eye, and not brighter than the fire on screen (FxLights). The sum — in linear, to the screen — exponent 1/2.2
        // map shadow — for baked matter; for this one — in the exponent of its light attenuation against the baked (SmokePuffs.shade,
        // byte −13…−50 in Normal.y: FxQuads.sixWay)
        // exponent not below 0.001 and the base not zero: pow(0, 0) is undefined in GLSL (NaN — a black pixel in blending)
        float shade = max((-nearest * 127.0 - 13.0) / 37.0 * 1.5, 0.001);
        float key = pow(max(sixLight(a.rgb, b, sixKeyDir) / FxSixLight.z, 1e-6), shade);
        float diffuse = pow(max((a.r + a.g + a.b + b.r + b.g + b.b) / 6.0 / FxSixLight.z, 1e-6), shade);
        float sky = sixKey * key + (1.0 - sixKey) * diffuse;
        vec3 fire = sixBall * pow(max(sixLight(a.rgb, b, sixBallDir) / FxSixLight.z, 1e-6), shade);
        // a puff lying on the horizontal (shadow exponent 0 — the top of the layer lit from above): the balls' light — in this pixel
        if (-nearest * 127.0 - 13.0 < 0.5) {
            vec3 bd;
            fire = ballsToward(worldPos, bd);
        }
        // sky tint (at night — the lilac light of the moon) — only for sky light: the vertex colour carries its brightness, and the puff's fire
        // has its own (the tint on the fire turned the dust around a ball pink at night)
        sixLit = pow(max(FxSkyTint * sky + fire, vec3(0.0)), vec3(1.0 / 2.2));
        tex = vec4(vec3(a.a), a.a);
    } else if (nearest > 0.55) {
        tex = fireFrames(texCoord0, dx, dy, opacity);
    } else if (nearest < -0.5) {
        tex = textureGrad(Sampler3, texCoord0, dx, dy);
    } else {
        tex = textureGrad(Sampler0, texCoord0, dx, dy);
        if (nearest > 0.25) {
            // a halo (FxQuads.glowShape) larger than a quarter of the picture on screen — by formula: from a 64×64 picture it would come out
            // as steps of squares by its pixel. The middles of the picture's edge pixels — r = 1 (63 steps across 64 pixels)
            vec2 size = vec2(textureSize(Sampler0, 0)), px = dx * size, py = dy * size;
            if (max(dot(px, px), dot(py, py)) < 16.0) {
                vec2 local = ((texCoord0 - FxGlow.xy) / (FxGlow.zw - FxGlow.xy) * 2.0 - 1.0) * (64.0 / 63.0);
                tex = vec4(glowAlpha(length(local)));
            }
        }
    }
    // a particle up close fainter than 0.1 is not drawn, as in the vanilla particle shader: otherwise the faint halos of puffs add up
    // into a murky haze around the cloud. Far away (a puff smaller than 16 pixels) — without the cut: distant smoke is faint anyway
    if (nearest > 0.75 && tex.a * opacity < 0.1 * clamp(2.0 - lod, 0.0, 1.0)) discard;
    vec4 color = vec4(vertexColor.rgb * tex.rgb * sixLit, vertexColor.a * tex.a);
    // frame pixel (for the glow pass — larger)
    vec2 frag = gl_FragCoord.xy * FxGlowPass.y;
    vec3 glow = vec3(0.0);
    // particles and fire frames — with premultiplied alpha
    float cover = 0.0;
    if (nearest > 0.55) cover = 1.0;
    if (FxGlowPass.x > 0.5 && emitSpot > 0.5) {
        glow = color.rgb * emitGain;
    } else if (FxGlowPass.x > 0.5 && emitGain > 0.0) {
        glow = bloomLight(color, cover, emitGain);
    }
    if (PallAmbient.w > 0.5) {
        // the pall in front of the square: its light is dimmed, and what it covers is already its light up to it (a pall on the frame — up to the world).
        // The square is nearer than the world: in front of it there is no more pall than on the frame up to the world — and its light up to it is the pall's light share
        // by the share of attenuation on the frame. The average light only fits where there is almost no pall on the frame: otherwise a puff behind a
        // dense pall would come out as a spot of another colour (far away — as light dots on a dark pall)
        vec4 frame = texture(Sampler6, frag / ScreenSize);
        // invalid pall pixel (NaN, infinity) — there is no pall in front of the square: NaN would give a black square
        if (!pallValid(frame)) frame = vec4(0.0, 0.0, 0.0, 1.0);
        float front = max(pallFront, frame.a);
        vec3 before = mix(PallAmbient.rgb, frame.rgb / max(1.0 - frame.a, 1e-3), smoothstep(0.02, 0.15, 1.0 - frame.a));
        color.rgb = color.rgb * front + color.a * before * (1.0 - front);
        glow *= front;
    }
    // visible share of the square — colour and emission have the same one
    float seen = 1.0;
    if (FxScene > 0.5 && (softness > 0.0 || FxGlowPass.x > 0.5 || FxHalf > 0.5)) {
        // the puff melts as it enters the ground and walls, and vanishes behind terrain (and behind Distant Horizons LOD)
        // invalid distance (NaN, behind the eye) — there is no world behind the pixel, as for the sky
        float scene = pallScene(texture(Sampler1, frag / ScreenSize).r);
        if (softness > 0.0) seen *= clamp((scene - viewDistance) / softness, 0.0, 1.0);
        // half-resolution target: without hardware depth, what lies behind the world is hidden by its distance (glare
        // in the eye has its probe instead, softness below zero, and is never cut by depth)
        else if (FxHalf > 0.5 && softness == 0.0 && nearest < 0.25 && scene < viewDistance) discard;
        // glow pass without hardware depth: what lies behind the world neither glows nor dims (for the layer, the frame's depth cuts it)
        if (FxGlowPass.x > 0.5 && scene < viewDistance * 0.999 - 0.05) discard;
    }
    // right at the camera the square does not cut the screen with the near-plane edge (the same numbers — SmokePuffs.NEAR_FROM, NEAR_FADE: what
    // melts here gives to the veil)
    seen *= clamp((viewDistance - 0.3) / 1.2, 0.0, 1.0);
    // Minecraft fog dims a particle rather than painting it its own colour: the world behind it is in fog anyway, and an opaque spot of the fog's
    // colour at the draw edge would cover the bands of plume trails and the distant picture of explosions, which particles replace there
    seen *= 1.0 - fog * FxFogColor.a;
    fragColor = color * seen * ColorModulator;
    // the half-resolution target is float: clamp as the 8-bit frame does before blending (NaN — nothing)
    if (FxHalf > 0.5) fragColor = any(isnan(fragColor)) ? vec4(0.0) : clamp(fragColor, 0.0, 1.0);
    // 8-bit frame: the smooth veil of matter at the eye (the ray grid, SmokePuffs.Veil) and the faint edges of puffs without mixed-in
    // noise come in steps — noise at half a step (Jimenez 2014, as for the halo), in the share of what the square lays down
    if (FxGlowPass.x < 0.5 && FxHalf < 0.5) {
        float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
        fragColor.rgb = max(fragColor.rgb + (noise - 0.5) / 255.0 * fragColor.a, vec3(0.0));
    }
    fragCover = vec4(0.0);
    if (FxGlowPass.x > 0.5) {
        // what covers the halo in the pixel (bloomCovers): the glowing thing and the smoke in front of it; the spot — by its own opacity
        float emits = 0.0;
        if (emitGain > 0.0 && emitSpot < 0.5) emits = 1.0;
        float covers = bloomCovers(fragColor.a, max(fragColor.r, max(fragColor.g, fragColor.b)), emits);
        fragCover = vec4(covers, 0.0, 0.0, fragColor.a);
        fragColor = vec4(glow * seen * ColorModulator.rgb, fragColor.a);
    }
}
