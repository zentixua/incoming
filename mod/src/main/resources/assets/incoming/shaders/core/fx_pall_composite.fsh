#version 150

#moj_import <incoming:pall.glsl>
#moj_import <incoming:mushroom.glsl>
#moj_import <incoming:smoke.glsl>
#moj_import <incoming:scene.glsl>
#moj_import <incoming:see.glsl>
#moj_import <incoming:wall_layer.glsl>
// the dust pass's resolve divisor (DustWallPass); 0 — no dust pass this frame (Sampler6, its atlas, is then a placeholder)
uniform float DwDiv;
// 1 — only the dust wave's haze level is left this frame (DustWallPass.hazeOnly). Where its first occupied depth is at the
// eye (under DW_HAZE_AT_EYE blocks) the haze surrounds the eye and is laid in front of the pall's other air in one step: no
// smoke or mushroom stands in front of it, and the per-pixel split against them costs 1.8–2.1 ms at 1600 × 900. An eye above
// the layer (a hill, a tower, a craft) sees the haze start further down the ray, behind whatever stands in front of it: the
// split orders them as for the young wall
uniform float DwHazeOnly;
const float DW_HAZE_AT_EYE = 8.0;
uniform int PallLayerFrame;

// Pall onto frame (client/fx/layer/PallPass): ONE, SRC_ALPHA blending, pall light plus world behind, attenuated
// and shadowed by pall/mushrooms. Pall frame is downsampled: four nearest pixels weighted by world-distance
// similarity (at house edge, sky pall does not cover wall or vice versa). Shadows here per pixel: pall from noiseless
// field, mushrooms from shadow map (mushroom.glsl, fx_mush_shadow); pall-frame alpha is attenuation only (used by
// layer quads, fx.fsh); ground shadow does not belong to them. Burning-city surface flame (firestorm,
// client/nuclear/FireStorm) here too: from pixel world distance (including Distant Horizons LOD), attenuated
// behind pall. Also fireball world light (first seconds by day, longer at night): brightens world, while blending
// can only attenuate; while a ball emits, pass takes frame copy (Sampler9) and writes entire world pixel.

// accumulated pall (fx_pall_resolve)
uniform sampler2D Sampler0;
// pixel world distance (full resolution)
uniform sampler2D Sampler1;
// near pall seen to each slice (fx_pall_froxel_sum; pall.glsl: froxelNear), and the volume's tile, slices and far boundary:
// the rays (Sampler0) hold the pall only beyond it
uniform sampler2D Sampler11;
uniform vec4 PallFroxel;
// field table (PallField.row)
uniform sampler2D Sampler2;

uniform mat4 InvViewProj;
uniform vec3 PallForward;
uniform mat4 PallSlot0;
uniform mat4 PallSlot1;
// sun direction (xyz), daytime sun strength 0..1 (w); linear sun/sky light (rgb), as in fx_pall
uniform vec4 PallSun;
uniform vec3 PallSunLight;
uniform vec3 PallSkyLight;
// z: pall-frame downsample factor
uniform vec4 PallFrame;
// PROBE fireball-light: x = eye exposure under the flash relative to the exposure without it r (linear, <= 1), y = share p the
// frame follows: the frame's exposure is r^p of its unflashed one; the ball's light (in eye units, × r) is shown × r^(p−1)
uniform vec4 PallFlash;
// firestorm: slot fire maps, fire slots (pall.glsl), patch/flicker noise (own texture unit, PallPass)
uniform sampler2D Sampler3;
uniform sampler2D Sampler4;
uniform mat4 PallFire0;
uniform mat4 PallFire1;
uniform sampler3D PallNoise;
// eye-local outdoor air (pall.glsl: pallOpen, client/fx/layer/OpenAir); own texture unit
uniform sampler3D PallOpen;
uniform vec4 PallOpenAt;
uniform vec3 PallOpenWrap;
// Minecraft's lightmap (pall.glsl: pallLightmap); PallLamps 1 — the window holds block light (OpenAir, MovingLight)
uniform sampler2D Sampler10;
uniform float PallLamps;
// terrain/water under both slots' pall, one texture (pall.glsl: pallGroundUv, pallFloor, pallWater)
uniform sampler2D Sampler5;
// the dust pass's resolved atlas (DustWallPass, wall_layer.glsl)
uniform sampler2D Sampler6;
uniform vec4 PallGround0;
uniform vec4 PallGround1;
uniform float PallFloorFrom;
// frame mushrooms (MushroomField), their sunlight shadow map (fx_mush_shadow)
uniform sampler2D Sampler7;
uniform sampler2D Sampler8;
// x: 3.912 / visibility range, 1/block; y/z: Minecraft fog start/end; w: 1 for cylindrical fog (as in fx_water)
uniform vec4 Haze;
// Fireballs lighting world (MushroomField.balls): column i is eye-relative cap center, radius (w; 0 absent),
// surface light on white wall with eye adaptation (rgb, linear), air extinction 1/block (w); frame copy
// before this pass while any ball emits; open-sky light on white block as Minecraft draws it (screen);
// Minecraft block-face shading (ClientLevel.getShade): up, down, north/south, west/east
uniform mat4 PallBalls;
uniform mat4 PallBallLight;
uniform sampler2D Sampler9;
uniform vec3 PallOpenSky;
uniform vec4 PallFaceShade;
// see-through surface in front of the world (see.glsl): its own light and transmittance (SceneDepth.bindSee: own texture unit),
// its distance in Sampler1 (g); FxSee 1 — present this frame
uniform sampler2D FxGlass;
uniform float FxSee;

// as in fx_pall: per-channel brightness; above white (by luma), add this share of excess as white to all channels
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const float CROSS = 0.15;

out vec4 fragColor;

// Pall above eye-relative point p as a flat slab: green-channel optical depth (x), albedo (y), asymmetry (z), ground albedo
// below (w: land/water). The pall's matter is the haze of both slots
vec4 pallOver(vec3 p) {
    float tau = 0.0, water = 0.0;
    if (PallSlot0[1].w > 0.5) {
        PallAt f = pallField(Sampler2, Sampler5, PallSlot0, PallGround0, p, pallRelief(Sampler5, PallSlot0, PallGround0, p.xz));
        tau += f.column;
        water += f.column * f.wet;
    }
    if (PallSlot1[1].w > 0.5) {
        PallAt f = pallField(Sampler2, Sampler5, PallSlot1, PallGround1, p, pallRelief(Sampler5, PallSlot1, PallGround1, p.xz));
        tau += f.column;
        water += f.column * f.wet;
    }
    float n = max(tau, 1e-6);
    return vec4(tau, PALL_HAZE_W.g, PALL_DUST_G, mix(PALL_LAND, PALL_SEA, clamp(water / n, 0.0, 1.0)));
}

// Pall/mushroom/explosion-smoke shadow (smokeSun: grid depth toward sun) on sunlit world at eye-relative p: overhead light multiplier
// relative to unobstructed sun/sky. Mushrooms high above pall come first: shadow map gives sunlight share
// transmitted to point, direct beam through their depth plus forward-scattered light, scaled by cloud visibility
// from ground (fx_mush_shadow; forward scattering comes from sky near sun, then travels as a beam). Pall at
// layer bottom: depth above point is whole layer, direct sun through column plus sun/sky scattered downward
// by pall (two streams, as in fx_pall: pallDiffuse, including ground/pall reflections). Sky hidden by cap is not
// subtracted: even beneath cap it covers less than a tenth of ground skylight (15 kt R²/(R² + H²) ≈ 0.07).
// One channel (green): shadow multiplies alpha. Pall overhead is over (pallOver). Light is linear, frame world is in
// screen values: screen shadow is power 1/γ (pallDisplay; world darkens by k, screen by k^(1/γ)).
float shade(vec3 p, vec4 over) {
    if (PallSun.w <= 0.0) return 1.0;
    float mu = max(PallSun.y, 0.12), sun = PallSunLight.g, sky = PallSkyLight.g;
    float beam = sun * mushShadow(Sampler7, Sampler8, mushShadowSun(PallSun.xyz), p) * smokeSun(p, PallSun.xyz), skyIn = sky;
    float down = mu * beam + skyIn;
    if (over.x > 1e-5) {
        down = mu * beam * exp(-over.x / mu) + pallDiffuse(over.y, over.z, over.x, over.x, over.w, mu, beam, 1.0, 0.0, skyIn, 0.0, -0.6666667);
    }
    return mix(1.0, clamp(down / max(mu * sun + sky, 1e-4), 0.0, 1.0), PallSun.w);
}

// Fireball world light at eye-relative p, normal n, linear, on white wall (like PallSunLight): Lambertian sphere
// (MushroomModel.light) emits L·R²/max(d², R²), direct face-cosine beam, air-scattered light from surrounding sky,
// and reflection from illuminated ground (mushBallFace); through pall overhead (over), direct beam e^(−τ/μ) plus
// downward scattering (two streams, like sun in shade), scaled by face sky share (1 + n.y)/2. No terrain/building
// shadows: ball lights behind hills/houses equally (only slopes facing away are dark).
// PROBE fireball-light: screen-space shadow toward the ball (contact shadows of houses, trees, the player within the frame)
float ballShadow(vec3 p, vec3 v) {
    mat4 vp = inverse(InvViewProj);
    ivec2 size = textureSize(Sampler1, 0);
    float t = 0.4;
    for (int i = 0; i < 28; i++) {
        t *= 1.22;
        vec3 q = p + v * t;
        vec4 c = vp * vec4(q, 1.0);
        if (c.w <= 0.0) break;
        vec2 uv = c.xy / c.w * 0.5 + 0.5;
        if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) break;
        float scene = pallScene(texelFetch(Sampler1, ivec2(uv * vec2(size)), 0).r);
        float behind = dot(q, PallForward) - scene;
        if (behind > 0.25 + 0.03 * t && behind < 6.0 + 0.3 * t) return 0.0;
    }
    return 1.0;
}

vec3 ballLight(vec3 p, vec3 n, vec4 over) {
    vec3 sum = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        vec4 b = PallBalls[i];
        if (b.w <= 0.0) continue;
        vec3 l = b.xyz - p;
        float d2 = dot(l, l), d = sqrt(d2);
        vec3 v = l / max(d, 1e-3);
        float mu = max(v.y, 0.12), through = 1.0, diffuse = 0.0;
        if (over.x > 1e-5) {
            through = exp(-over.x / mu);
            diffuse = max(pallDiffuse(over.y, over.z, over.x, over.x, over.w, mu, 1.0, 1.0, 0.0, 0.0, 0.0, -0.6666667), 0.0);
        }
        float face = mushBallFace(dot(n, v), v.y, n.y, PallBallLight[i].w * d, over.w, through * ballShadow(p, v), diffuse);
        sum += PallBallLight[i].rgb * (mushBallFalloff(d2, b.w * b.w) * face);
    }
    return sum;
}

// Burning-city surface flame at eye-relative p, dist to it, pixel width there (blocks): where burning,
// noise patches (houses/yards) at map-cell strength, flickering; subpixel uses mean patch share (no distant
// shimmer). Absent closer than FLAME_NEAR (F[2].w), fades in to 1.5 times it: nearer looks like paint on blocks, where real
// vanilla fire burns. Also uniform fire glow on surroundings (burning city orange at night).
vec3 flames(sampler2D map, mat4 S, mat4 F, vec3 p, float dist, float pixel) {
    if (F[0].x <= 0.0) return vec3(0.0);
    float h = p.y - S[0].z;
    // world distance includes clouds: they do not burn
    if (h < -80.0 || h > 200.0 || p.y > F[3].w - 6.0) return vec3(0.0);
    vec2 uv = fireUv(S, F, p.xz);
    float r = textureLod(map, uv, 0.0).r, around = textureLod(map, uv, 2.5).r;
    if (r + around <= 1e-3) return vec3(0.0);
    vec2 rel = p.xz - S[0].xy;
    float t = F[2].z;
    float spots = smoothstep(0.52, 0.72, texture(PallNoise, vec3(rel.x / 70.0, p.y / 90.0, rel.y / 70.0)).r);
    spots = mix(spots, 0.3, smoothstep(4.0, 16.0, pixel));
    float flicker = 0.55 + 0.9 * texture(PallNoise, vec3(rel.x / 25.0, t * 0.4, rel.y / 25.0)).g;
    float near = smoothstep(F[2].w, 1.5 * F[2].w, dist);
    float e = F[2].x * min(r, 1.5) * spots * flicker;
    vec3 hot = mix(F[3].rgb, vec3(1.0, 0.72, 0.36), clamp(e - 0.6, 0.0, 0.5));
    return near * (hot * e + F[3].rgb * (0.12 * F[1].w * around));
}

// outdoor-air share at p (pall.glsl: pallOpen), beyond-window terrain from first available slot map
float pallOpenHere(vec3 p) {
    if (PallSlot0[1].w > 0.5) return pallOpen(PallOpen, PallOpenAt, PallOpenWrap, Sampler5, PallSlot0, PallGround0, p);
    return pallOpen(PallOpen, PallOpenAt, PallOpenWrap, Sampler5, PallSlot1, PallGround1, p);
}

// Noiseless pall depth on eye ray dir over [ta, tb]: six steps through both slots' haze (like pall before layer quads,
// pall_front.glsl: pallThrough), scaled by outdoor-air share (pallOpen); no Wilson cloud (thin shell lasting seconds)
float pallTau(vec3 dir, float ta, float tb) {
    float tau = 0.0;
    float ds = (tb - ta) / 6.0;
    for (int i = 0; i < 6; i++) {
        vec3 q = dir * (ta + (float(i) + 0.5) * ds);
        float s = 0.0;
        if (PallSlot0[1].w > 0.5) {
            PallAt f = pallField(Sampler2, Sampler5, PallSlot0, PallGround0, q, pallRelief(Sampler5, PallSlot0, PallGround0, q.xz));
            s += f.sigma;
        }
        if (PallSlot1[1].w > 0.5) {
            PallAt f = pallField(Sampler2, Sampler5, PallSlot1, PallGround1, q, pallRelief(Sampler5, PallSlot1, PallGround1, q.xz));
            s += f.sigma;
        }
        tau += s * pallOpenHere(q) * ds;
    }
    return tau;
}

// Full-frame pixel whose center pall-frame pixel t ray crossed (fx_pall: floor((t + 0.5)·factor)),
// and world distance there
ivec2 rayPixel(ivec2 t, ivec2 fullSize) {
    return clamp(ivec2((vec2(t) + 0.5) * PallFrame.z), ivec2(0), fullSize - 1);
}

float sampleDepth(ivec2 t, ivec2 fullSize) {
    return pallScene(texelFetch(Sampler1, rayPixel(t, fullSize), 0).r);
}

// Pall for pixel full with view-axis world distance depth, direction dir, from pall frame downsampled by factor:
// four nearest rays (lattice pallLattice), weighted by similarity of their world distance to depth; at house
// edge sky pall does not cover wall or vice versa. An object narrower than ray spacing (bars, fences, leaves, distant block
// edge, 1–3 pixels at 4× downsampling) often lacks its own ray: none of four samples hit same place, so
// pixel took background pall (bars 5 blocks away drowned in pall behind, colored rim at edge), while background between
// bars took bars' pall (no own pall: bright spots). Then use nearest-distance ray within surrounding 4×4
// (gap background finds its ray there), remainder from noiseless field: transmittance × e^(∓depth between sample/point),
// light same ratio to opacity (pall matter color unchanged, as layer quads take foreground pall in fx.fsh).
vec4 upsample(ivec2 full, ivec2 fullSize, ivec2 lowSize, float depth, vec3 dir, float nearEnd) {
    vec2 at = vec2(pallLattice(float(full.x) + 0.5, PallFrame.z), pallLattice(float(full.y) + 0.5, PallFrame.z));
    ivec2 base = ivec2(floor(at));
    vec2 f = at - vec2(base), slope = pallSlope(Sampler1, full, fullSize, depth);
    vec4 sum = vec4(0.0);
    float total = 0.0, same = 0.0;
    bool clear = true;
    float farRay = 0.0;
    for (int j = 0; j <= 1; j++) {
        for (int i = 0; i <= 1; i++) {
            ivec2 t = clamp(base + ivec2(i, j), ivec2(0), lowSize - 1);
            float d = sampleDepth(t, fullSize), a = pallAlike(d, pallAhead(depth, slope, vec2(rayPixel(t, fullSize) - full)));
            farRay = max(farRay, d);
            // bilinear weight without nested ?: expression: Veil drops its parentheses (ShaderSourceTest)
            float w = mix(1.0 - f.x, f.x, float(i)) * mix(1.0 - f.y, f.y, float(j));
            same = max(same, a);
            w *= a + 1e-4;
            vec4 v = texelFetch(Sampler0, t, 0);
            // the hand's pixels hold no pall (fx_pall_resolve: invalid)
            if (!pallValid(v)) continue;
            clear = clear && 1.0 - v.a < 0.02;
            sum += v * w;
            total += w;
        }
    }
    // at least one ray crossed same place (similarity over half): use it; if four samples have almost no pall and pixel no
    // farther (cannot recover light per opacity, like fx.fsh), pixel has none either, no further search. Farther pixel
    // (background between bars) searches own ray: nearby bars' pall is not its pall
    if (total > 0.0 && (same > 0.5 || (clear && depth <= farRay * 1.05))) return sum / total;
    ivec2 best = clamp(base, ivec2(0), lowSize - 1);
    float bestAlike = -1.0, bestDepth = depth;
    for (int j = -1; j <= 2; j++) {
        for (int i = -1; i <= 2; i++) {
            ivec2 t = clamp(base + ivec2(i, j), ivec2(0), lowSize - 1);
            // the hand's pixels hold no pall (fx_pall_resolve: invalid): never the best
            if (!pallValid(texelFetch(Sampler0, t, 0))) continue;
            float d = sampleDepth(t, fullSize), a = pallAlike(d, pallAhead(depth, slope, vec2(rayPixel(t, fullSize) - full)));
            if (a > bestAlike) {
                bestAlike = a;
                best = t;
                bestDepth = d;
            }
        }
    }
    vec4 v = texelFetch(Sampler0, best, 0);
    if (!pallValid(v)) return vec4(0.0, 0.0, 0.0, 1.0);
    // sky/void behind pixel is beyond pall bounds, noiseless field cannot correct: unchanged. Unknown light per
    // opacity for nearly pall-free ray (like fx.fsh): unchanged too
    if (bestAlike > 0.5 || max(depth, bestDepth) >= 1e8 || 1.0 - v.a < 0.02 || depth == bestDepth) return v;
    float axis = max(dot(dir, PallForward), 1e-3);
    // rays hold the pall beyond the near volume's end only: the difference lies beyond it
    float near = nearEnd / axis, ta = max(bestDepth / axis, near), tb = max(depth / axis, near);
    float a;
    if (depth > bestDepth) {
        a = v.a * exp(-pallTau(dir, ta, tb));
    } else if (v.a >= 0.02) {
        // pixel closer than ray: its pall is a subset of ray pall
        a = v.a * exp(min(pallTau(dir, tb, ta), 80.0));
    } else {
        // ray nearly opaque (RGBA16F transmittance becomes zero beyond depth ~17; e^τ cannot restore it from zero):
        // transmittance to pixel from noiseless field
        a = exp(-pallTau(dir, near, tb));
    }
    a = clamp(a, 0.0, 1.0);
    return vec4(v.rgb * ((1.0 - a) / (1.0 - v.a)), a);
}

// World pixel at full in the fireball light added on normal n, display share kept under the pall shadow: per channel relit,
// above white toward white (CROSS, like matter light in fx_pall), no brighter than 1 with the same hue
vec3 ballLitWorld(ivec2 full, vec3 n, float kept, vec3 added) {
    vec3 world = texelFetch(Sampler9, full, 0).rgb, open = PallOpenSky * faceShade(PallFaceShade, n);
    // a see-through surface in front of the lit world: its own light X stays, the world behind it W = (frame − X) / T is
    // relit and seen through it (see.glsl)
    vec4 glass = vec4(0.0, 0.0, 0.0, 1.0);
    if (FxSee > 0.5 && seeBehind(pallScene(texelFetch(Sampler1, full, 0).r), pallScene(texelFetch(Sampler1, full, 0).g))) {
        glass = texelFetch(FxGlass, full, 0);
        world = clamp((world - glass.rgb) / max(glass.a, 1.0 / 255.0), 0.0, 1.0);
    }
    kept = pallDisplay(pallLinear(kept) * pow(PallFlash.x, PallFlash.y));
    added *= pow(max(PallFlash.x, 1e-9), PallFlash.y - 1.0);
    vec3 lit = vec3(relit(world.r, kept, open.r, added.r), relit(world.g, kept, open.g, added.g),
            relit(world.b, kept, open.b, added.b));
    lit += vec3(CROSS * max(dot(lit, LUMA) - 1.0, 0.0));
    vec3 shown = vec3(pallDisplay(lit.r), pallDisplay(lit.g), pallDisplay(lit.b));
    return glass.rgb + glass.a * (shown / max(max(max(shown.r, shown.g), shown.b), 1.0));
}

// Intersect each body once per ray. The entry, wall interior and glass can then
// reject a whole interval before fetching mushroom materials or the smoke grid.
struct MediumSpans {
    vec4 starts;
    vec4 ends;
    vec2 smoke;
};

MediumSpans mediumSpans(vec3 dir) {
    MediumSpans spans = MediumSpans(vec4(1e30), vec4(-1e30), vec2(1e30, -1e30));
    for (int k = 0; k < MUSHROOMS; k++) {
        if (!mushOn(Sampler7, k)) break; // MushroomField packs active rows first.
        vec2 body = mushSpan(mushLoad(Sampler7, k), dir);
        if (body.y <= body.x) continue;
        spans.starts[k] = body.x;
        spans.ends[k] = body.y;
    }
    if (smokeOn()) spans.smoke = pallBox(dir, SmokeMin, SmokeMax);
    return spans;
}

bool mushOverlaps(MediumSpans spans, float start, float end) {
    return any(lessThan(max(spans.starts, vec4(start)), min(spans.ends, vec4(end))));
}

float smokeDepth(vec3 dir, vec2 span, float start, float end) {
    float a = max(span.x, start), b = min(span.y, end);
    if (b <= a) return 0.0;
    return smokeTau(dir * a, dir, b - a, 6);
}

// Noiseless optical depth of the pall, the mushrooms and the conventional smoke on [start, end] of the eye ray dir
float mediumDepth(vec3 dir, MediumSpans spans, float start, float end) {
    float depth = 0.0;
    if (mushOverlaps(spans, start, end)) {
        for (int k = 0; k < MUSHROOMS; k++) {
            float a = max(start, spans.starts[k]), b = min(end, spans.ends[k]);
            if (b <= a) continue;
            mushAddDepth(depth, mushLoad(Sampler7, k), dir, vec2(a, b), 8);
        }
    }
    return depth + pallTau(dir, start, end) + smokeDepth(dir, spans.smoke, start, end);
}

// The pall on this pixel's ray up to a see-through surface at view-axis distance surface: the near volume up to it
// (froxelNear, exact as the composite takes it to the world), and beyond the volume's end the rays' pall from there to the
// world (rays) cut at the surface by the noiseless field and the conventional smoke's mean field, light in the same ratio
// to opacity (as upsample does). The mushroom contributes extinction on the same interval. Light and
// transmittance never exceed the whole ray's (laid): the part in front of the surface is a part of it.
vec4 mediumTo(float surface, ivec2 full, vec3 dir, float nearEnd, float nearDepth, vec4 nearWorld,
              vec4 rays, vec4 laid, MediumSpans spans) {
    float axis = max(dot(dir, PallForward), 1e-3), end = surface / axis;
    vec4 beyond = vec4(0.0, 0.0, 0.0, 1.0);
    vec4 near = nearWorld;
    float boundary = min(surface, nearEnd), split = boundary / axis;
    // The opaque surface, wall and pane often end at the same froxel boundary.
    if (PallFroxel.z > 0.0 && boundary != nearDepth) near = froxelNear(Sampler11, PallFroxel, vec2(full) + 0.5, boundary);
    if (surface > nearEnd && 1.0 - rays.a > 0.0) {
        float tau = max(exp(-mediumDepth(dir, spans, split, end)), rays.a);
        beyond = vec4(rays.rgb * clamp((1.0 - tau) / max(1.0 - rays.a, 1e-4), 0.0, 1.0), tau);
    }
    vec4 front = beyond;
    if (PallFroxel.z > 0.0) front = froxelOver(near, beyond);
    return vec4(min(front.rgb, max(laid.rgb, vec3(0.0))), max(front.a, laid.a));
}

// Only the wall opacity behind a volume sample can be obscured by it. Integrate
// visible sample opacity from front to back. A thin cloud crossing a thick
// wall then reveals its near part continuously, just as a pane does.
void wallOcclusionStep(inout vec2 sum, float tau, float z, vec2 span, float t, float logT) {
    float alpha = sum.x * (1.0 - exp(-tau));
    float u = clamp((z - span.x) / (span.y - span.x), 0.0, 1.0);
    float behind = clamp((exp(logT * u) - t) / (1.0 - t), 0.0, 1.0);
    sum += vec2(-alpha, alpha * behind);
}

// Each cloud covers a fraction of the wall. Two clouds at the same depth
// obscure that fraction only once. Their common part is the farther fraction
// times the product of their opacities.
vec2 combineWallCover(vec2 cover, vec2 sampleSum) {
    float opacity = 1.0 - sampleSum.x;
    float both = min(cover.x * sampleSum.y, opacity * cover.y);
    return vec2(cover.x + (1.0 - cover.x) * opacity, cover.y + sampleSum.y - both);
}

vec4 wallForeground(vec4 entry, vec4 total, vec3 dir, vec2 span, float t, MediumSpans spans) {
    if (entry.a <= total.a || span.y <= span.x || t >= 1.0) return entry;
    float logT = log(max(t, 1e-7));
    vec2 cover = vec2(0.0);
    if (mushOverlaps(spans, span.x, span.y)) {
        for (int k = 0; k < MUSHROOMS; k++) {
            float a = max(span.x, spans.starts[k]), b = min(span.y, spans.ends[k]);
            if (b <= a) continue;
            Mush m = mushLoad(Sampler7, k);
            float ds = (b - a) / 8.0;
            vec2 sum = vec2(1.0, 0.0);
            for (int i = 0; i < 8; i++) {
                float z = a + (float(i) + 0.5) * ds;
                wallOcclusionStep(sum, mushDepthAt(m, mushLocal(m, dir * z)) * ds, z, span, t, logT);
            }
            cover = combineWallCover(cover, sum);
        }
    }
    float a = max(span.x, spans.smoke.x), b = min(span.y, spans.smoke.y);
    if (b > a) {
        float ds = (b - a) / 6.0;
        vec2 sum = vec2(1.0, 0.0);
        for (int i = 0; i < 6; i++) {
            float z = a + (float(i) + 0.5) * ds;
            wallOcclusionStep(sum, smokeSigma(dir * z) * ds, z, span, t, logT);
        }
        cover = combineWallCover(cover, sum);
    }
    float share = clamp(entry.a * cover.y / (entry.a - total.a), 0.0, 1.0);
    return mix(entry, total, share);
}

void main() {
    ivec2 fullSize = textureSize(Sampler1, 0), lowSize = textureSize(Sampler0, 0);
    ivec2 full = ivec2(gl_FragCoord.xy);
    if (PallLayerFrame != 0) full = rayPixel(full, fullSize);
    float depth = pallScene(texelFetch(Sampler1, clamp(full, ivec2(0), fullSize - 1), 0).r);
    // first-person hand under Iris (pallHand): left as vanilla draws it, after the layer — no dust, no shadow; a fireball still
    // lights it as it lights the world around (ballLight, with the pall overhead at the eye): a dark hand in a world the flash
    // made bright looked cut out of another scene
    if (pallHand(depth)) {
        fragColor = vec4(0.0, 0.0, 0.0, pallDisplay(pow(PallFlash.x, PallFlash.y)));
        if (PallLayerFrame == 0 && PallBalls[0].w > 0.0) {
            vec4 farHand = InvViewProj * vec4((vec2(full) + 0.5) / vec2(fullSize) * 2.0 - 1.0, 1.0, 1.0);
            vec3 dirHand = normalize(farHand.xyz / farHand.w);
            vec3 p = dirHand * (depth / max(dot(dirHand, PallForward), 1e-3));
            vec3 n = sceneNormal(Sampler1, InvViewProj, PallForward, full, fullSize, p);
            // Iris compresses hand depth: sample skylight at the eye, independently of torch/held-item light.
            vec3 added = ballLight(p, n, pallOver(p)) * flashOpening(pallOpenHere(vec3(0.0)));
            if (dot(added, LUMA) > 1e-6) fragColor = vec4(ballLitWorld(full, n, 1.0, added), 0.0);
        }
        return;
    }
    vec2 ndc0 = (vec2(full) + 0.5) / vec2(fullSize) * 2.0 - 1.0;
    vec4 far0 = InvViewProj * vec4(ndc0, 1.0, 1.0);
    vec3 dir0 = normalize(far0.xyz / far0.w);
    // where the near volume ends on this pixel's ray and the rays begin (its far boundary, as in the march)
    float nearEnd = 0.0;
    if (PallFroxel.z > 0.0) nearEnd = PallFroxel.z;
    vec4 rays;
    if (PallLayerFrame != 0) rays = texelFetch(Sampler0, ivec2(gl_FragCoord.xy), 0);
    else rays = upsample(full, fullSize, lowSize, depth, dir0, nearEnd);
    vec4 pall = rays;
    // near pall in front of the rays, at this pixel's own depth: a block's edge cuts the volume at the block, with no ray of
    // another place and no history from it (the halos and trails of screen-space accumulation); world behind the volume's
    // end, sky — all of it up to there
    float nearDepth = min(depth, nearEnd);
    vec4 nearWorld = vec4(0.0, 0.0, 0.0, 1.0);
    if (PallFroxel.z > 0.0) {
        nearWorld = froxelNear(Sampler11, PallFroxel, vec2(full) + 0.5, nearDepth);
        pall = froxelOver(nearWorld, pall);
    }
    WallLayer wall;
    wall.light = vec4(0.0, 0.0, 0.0, 1.0);
    wall.depth = vec3(0.0);
    vec4 air = pall, beforeWall = vec4(0.0, 0.0, 0.0, 1.0);
    vec4 entry = beforeWall;
    float axis = max(dot(dir0, PallForward), 1e-3);
    MediumSpans spans;
    bool haveSpans = false;
    if (DwDiv > 0.0) {
        wall = wallLayer(Sampler6, Sampler1, vec2(full) + 0.5, depth, DwDiv);
        if (wall.light.a < 1.0) {
            bool aroundEye = DwHazeOnly > 0.5 && wall.depth.x < DW_HAZE_AT_EYE;
            if (!aroundEye && (air.a < 1.0 || any(greaterThan(air.rgb, vec3(0.0))))) {
                spans = mediumSpans(dir0);
                haveSpans = true;
                entry = mediumTo(wall.depth.x, full, dir0, nearEnd, nearDepth, nearWorld, rays, air, spans);
                beforeWall = wallForeground(entry, air, dir0, wall.depth.xy / axis, wall.light.a, spans);
            }
            pall = insertWall(air, beforeWall, wall.light);
        }
    }
    if (PallLayerFrame != 0) {
        fragColor = pall;
        return;
    }
    if (PallFire0[0].x > 0.0 || PallFire1[0].x > 0.0) {
        vec2 ndc = (vec2(full) + 0.5) / vec2(fullSize) * 2.0 - 1.0;
        vec4 farPoint = InvViewProj * vec4(ndc, 1.0, 1.0), nextPoint = InvViewProj * vec4(ndc + vec2(0.0, 2.0 / float(fullSize.y)), 1.0, 1.0);
        vec3 dir = normalize(farPoint.xyz / farPoint.w);
        float dist = depth / max(dot(dir, PallForward), 1e-3);
        if (depth >= 1e8) {
            // no world behind pixel (beyond render distance without Distant Horizons, beyond LOD edge): ray ends at terrain
            // under pall, as in fx_pall; burning city beyond render distance is visible as lights on it
            dist = min(pallFloor(Sampler5, PallSlot0, PallGround0, dir, PallFloorFrom, 1e6, 16, 0.5),
                    pallFloor(Sampler5, PallSlot1, PallGround1, dir, PallFloorFrom, 1e6, 16, 0.5));
            if (dist >= 1e8) dist = -1.0;
        }
        if (dist > 0.0) {
            float pixel = dist * length(normalize(nextPoint.xyz / nextPoint.w) - dir);
            vec3 p = dir * dist;
            // flame emits: attenuated behind pall, unaffected by pall shadow
            pall.rgb += (flames(Sampler3, PallSlot0, PallFire0, p, dist, pixel) + flames(Sampler4, PallSlot1, PallFire1, p, dist, pixel)) * pall.a;
        }
    }
    // world only: shadow would paint patches on void below horizon
    bool balls = PallBalls[0].w > 0.0;
    // a fireball brightens the world behind the pall: the whole pixel is written, the pall over this ball-lit world
    vec3 ballLit = vec3(0.0);
    bool whole = false;
    if (depth < 1e8 && (PallSun.w > 0.0 || balls) && pall.a > 0.005) {
        vec2 ndc = (vec2(full) + 0.5) / vec2(fullSize) * 2.0 - 1.0;
        vec4 farPoint = InvViewProj * vec4(ndc, 1.0, 1.0);
        vec3 dir = normalize(farPoint.xyz / farPoint.w);
        vec3 p = dir * (depth / max(dot(dir, PallForward), 1e-3));
        // shadows/ball light affect world's own light; air haze/Minecraft fog before it are unshadowed air light over full path
        // to edge: shadow fades with them (like water decal, fx_water), otherwise fog near render edge becomes a gray
        // shadow strip
        float fd = Haze.w > 0.5 ? max(length(p.xz), abs(p.y)) : length(p);
        float seen = exp(-Haze.x * length(p)) * (1.0 - smoothstep(Haze.y, Haze.z, fd));
        vec4 over = pallOver(p);
        // pall shadows sky/sun light: torches light mines/caves (position is air block before surface); torches, lamps and a held
        // light (LambDynamicLights) keep their share of the pixel under it outdoors as well (pallKeepLamps)
        vec3 air = p - 0.5 * dir;
        float open = pallOpenHere(air);
        float kept = mix(1.0, pallDisplay(shade(p, over)), seen * open);
        if (PallLamps > 0.5) kept = pallKeepLamps(kept, Sampler10, pallSurfaceLevel(PallOpen, PallOpenAt, PallOpenWrap, air), 15.0 * open);
        vec3 added = vec3(0.0), n = vec3(0.0, 1.0, 0.0);
        if (balls) {
            n = sceneNormal(Sampler1, InvViewProj, PallForward, full, fullSize, p);
            // OpenAir carries raw skylight separately from lamps; torches cannot admit ball light through rock.
            // Beyond its local window pallOpenHere uses coarse terrain / boundary light, not exact building shadows.
            added = ballLight(p, n, over) * (seen * flashOpening(open));
        }
        if (dot(added, LUMA) > 1e-6) {
            // brighter world: full pixel, pall plus ball-lit world behind (alpha 0, blending ignores frame)
            ballLit = ballLitWorld(full, n, kept, added);
            whole = true;
        } else {
            pall.a *= kept;
        }
    }
    // accumulated pall always valid (fx_pall_resolve), but no input may damage frame world after it
    vec4 laid = pallValid(pall) ? pall : vec4(0.0, 0.0, 0.0, 1.0);
    // a see-through surface in front of the world: the pall in front of it is laid as it is; the pall behind it, its shadow,
    // flames and the ball's light on the world behind reach the eye as that world does, under the surface (see.glsl)
    if (FxSee > 0.5 && pallValid(pall)) {
        float surface = pallScene(texelFetch(Sampler1, full, 0).g);
        if (seeBehind(depth, surface)) {
            if (!haveSpans) spans = mediumSpans(dir0);
            vec4 front = mediumTo(surface, full, dir0, nearEnd, nearDepth, nearWorld, rays, wall.light.a < 1.0 ? air : laid, spans);
            if (wall.light.a < 1.0) {
                vec4 nearWall = wallTo(wall, surface);
                vec4 before = beforeWall;
                if (surface < wall.depth.y)
                    before = wallForeground(entry, front, dir0, vec2(wall.depth.x, surface) / axis, nearWall.a, spans);
                front = insertWall(front, before, nearWall);
            }
            vec4 g = texelFetch(FxGlass, full, 0);
            laid.rgb = vec3(seeMediumLight(laid.r, laid.a, front.r, front.a, g.r, g.a),
                    seeMediumLight(laid.g, laid.a, front.g, front.a, g.g, g.a), seeMediumLight(laid.b, laid.a, front.b, front.a, g.b, g.a));
        }
    }
    // brighter world: pall plus the ball-lit world behind it (alpha 0, blending ignores frame); the lit pixel holds the
    // surface over the world, as the frame did
    if (whole && pallValid(pall)) laid = vec4(laid.rgb + laid.a * ballLit, 0.0);
    else laid.a *= pallDisplay(pow(PallFlash.x, PallFlash.y));
    // PROBE: veiling glare in the eye from each ball, Stiles–Holladay 10·E/θ² (θ in degrees) over a white wall E/π:
    // 31.4·F/θ², F the ball's light at the eye in frame units; beyond 30° falling as 1/θ³
    vec3 veil = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        vec4 b = PallBalls[i];
        if (b.w <= 0.0) continue;
        float d2 = dot(b.xyz, b.xyz);
        float theta = degrees(acos(clamp(dot(dir0, b.xyz / sqrt(d2)), -1.0, 1.0)));
        float disc = degrees(atan(b.w / sqrt(d2)));
        float th = max(theta, disc);
        float k = 31.4 / (th * th) * (th > 30.0 ? 30.0 / th : 1.0);
        veil += PallBallLight[i].rgb * (b.w * b.w / max(d2, b.w * b.w)) * pow(max(PallFlash.x, 1e-9), PallFlash.y - 1.0) * k;
    }
    veil = min(veil, vec3(1.0));
    if (dot(veil, LUMA) > 1e-4) {
        // over the composited pixel: its display value plus the veil in linear light (premultiplied: frame × a stays)
        laid.rgb = vec3(pallDisplay(pallLinear(laid.r) + veil.r), pallDisplay(pallLinear(laid.g) + veil.g), pallDisplay(pallLinear(laid.b) + veil.b));
    }
    fragColor = laid;
}
