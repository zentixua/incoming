#version 150

#moj_import <incoming:pall.glsl>
#moj_import <incoming:mushroom.glsl>
#moj_import <incoming:smoke.glsl>
#moj_import <incoming:scene.glsl>

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
// terrain/water under slot pall (pall.glsl: pallFloor, pallWater), slot water as in fx_pall
uniform sampler2D Sampler5;
uniform sampler2D Sampler6;
uniform vec4 PallGround0;
uniform vec4 PallGround1;
uniform float PallFloorFrom;
uniform vec4 PallWater0;
uniform vec4 PallWater1;
// slot dust-wall head (client/nuclear/BlastWall): field-based pall remainder for pixels without own ray (pallTau)
uniform mat4 PallWall0;
uniform mat4 PallWall1;
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

// as in fx_pall: per-channel brightness; above white (by luma), add this share of excess as white to all channels
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const float CROSS = 0.15;

out vec4 fragColor;

// Pall above eye-relative point p as a flat slab: green-channel optical depth (x), albedo (y), asymmetry (z),
// ground albedo below (w: land/water). Matter weighted by optical depths of dust (with smoke), haze and mist
vec4 pallOver(vec3 p) {
    float tau = 0.0, w = 0.0, g = 0.0, water = 0.0;
    if (PallSlot0[1].w > 0.5) {
        PallAt f = pallField(Sampler2, Sampler5, PallSlot0, PallGround0, PallWater0, p, pallRelief(Sampler5, PallSlot0, PallGround0, p.xz), 1.0);
        tau += f.column.x + f.column.y + f.column.z;
        w += f.column.x * mix(PALL_DUST_W.g, PALL_SMOKE_W.g, f.smoke) + f.column.y * PALL_HAZE_W.g + f.column.z * PALL_MIST_W.g;
        g += (f.column.x + f.column.y) * PALL_DUST_G + f.column.z * PALL_MIST_G;
        water += (f.column.x + f.column.y) * f.wet + f.column.z;
    }
    if (PallSlot1[1].w > 0.5) {
        PallAt f = pallField(Sampler2, Sampler6, PallSlot1, PallGround1, PallWater1, p, pallRelief(Sampler6, PallSlot1, PallGround1, p.xz), 1.0);
        tau += f.column.x + f.column.y + f.column.z;
        w += f.column.x * mix(PALL_DUST_W.g, PALL_SMOKE_W.g, f.smoke) + f.column.y * PALL_HAZE_W.g + f.column.z * PALL_MIST_W.g;
        g += (f.column.x + f.column.y) * PALL_DUST_G + f.column.z * PALL_MIST_G;
        water += (f.column.x + f.column.y) * f.wet + f.column.z;
    }
    float n = max(tau, 1e-6);
    return vec4(tau, w / n, g / n, mix(PALL_LAND, PALL_SEA, clamp(water / n, 0.0, 1.0)));
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
        float face = mushBallFace(dot(n, v), v.y, n.y, PallBallLight[i].w * d, over.w, through, diffuse);
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
    return pallOpen(PallOpen, PallOpenAt, PallOpenWrap, Sampler6, PallSlot1, PallGround1, p);
}

// Noiseless pall depth on eye ray dir over [ta, tb]: six steps through both slot fields plus wall head (like pall
// before layer quads, pall_front.glsl: pallThrough), scaled by outdoor-air share (pallOpen); no Wilson cloud
// (thin shell lasting seconds)
float pallTau(vec3 dir, float ta, float tb) {
    float tau = 0.0, ds = (tb - ta) / 6.0;
    for (int i = 0; i < 6; i++) {
        vec3 q = dir * (ta + (float(i) + 0.5) * ds);
        float s = 0.0;
        if (PallSlot0[1].w > 0.5) {
            PallAt f = pallField(Sampler2, Sampler5, PallSlot0, PallGround0, PallWater0, q, pallRelief(Sampler5, PallSlot0, PallGround0, q.xz), 1.0);
            s += f.sigma.x + f.sigma.y + f.sigma.z;
        }
        if (PallSlot1[1].w > 0.5) {
            PallAt f = pallField(Sampler2, Sampler6, PallSlot1, PallGround1, PallWater1, q, pallRelief(Sampler6, PallSlot1, PallGround1, q.xz), 1.0);
            s += f.sigma.x + f.sigma.y + f.sigma.z;
        }
        tau += s * pallOpenHere(q) * ds;
    }
    // dust-wall head over its ray spans (like pall before layer quads, pall_front.glsl: pallWallThrough)
    for (int k = 0; k < 2; k++) {
        mat4 S = k == 0 ? PallSlot0 : PallSlot1, V = k == 0 ? PallWall0 : PallWall1;
        vec4 G = k == 0 ? PallGround0 : PallGround1, W = k == 0 ? PallWater0 : PallWater1;
        vec4 spans = pallWallSpans(S, V, G, dir, ta, tb);
        for (int j = 0; j < 2; j++) {
            vec2 sp = j == 0 ? spans.xy : spans.zw;
            if (sp.y <= sp.x) continue;
            float wall = k == 0 ? pallWallTau(Sampler5, S, V, G, W, dir, sp.x, sp.y, 4) : pallWallTau(Sampler6, S, V, G, W, dir, sp.x, sp.y, 4);
            tau += wall * pallOpenHere(dir * (0.5 * (sp.x + sp.y)));
        }
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
vec4 upsample(ivec2 full, ivec2 fullSize, ivec2 lowSize, float depth, vec3 dir) {
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
            clear = clear && 1.0 - v.a < 0.02;
            sum += v * w;
            total += w;
        }
    }
    // at least one ray crossed same place (similarity over half): use it; if four samples have almost no pall and pixel no
    // farther (cannot recover light per opacity, like fx.fsh), pixel has none either, no further search. Farther pixel
    // (background between bars) searches own ray: nearby bars' pall is not its pall
    if (same > 0.5 || (clear && depth <= farRay * 1.05)) return sum / total;
    ivec2 best = clamp(base, ivec2(0), lowSize - 1);
    float bestAlike = -1.0, bestDepth = depth;
    for (int j = -1; j <= 2; j++) {
        for (int i = -1; i <= 2; i++) {
            ivec2 t = clamp(base + ivec2(i, j), ivec2(0), lowSize - 1);
            float d = sampleDepth(t, fullSize), a = pallAlike(d, pallAhead(depth, slope, vec2(rayPixel(t, fullSize) - full)));
            if (a > bestAlike) {
                bestAlike = a;
                best = t;
                bestDepth = d;
            }
        }
    }
    vec4 v = texelFetch(Sampler0, best, 0);
    // sky/void behind pixel is beyond pall bounds, noiseless field cannot correct: unchanged. Unknown light per
    // opacity for nearly pall-free ray (like fx.fsh): unchanged too
    if (bestAlike > 0.5 || max(depth, bestDepth) >= 1e8 || 1.0 - v.a < 0.02 || depth == bestDepth) return v;
    float axis = max(dot(dir, PallForward), 1e-3);
    float a;
    if (depth > bestDepth) {
        a = v.a * exp(-pallTau(dir, bestDepth / axis, depth / axis));
    } else if (v.a >= 0.02) {
        // pixel closer than ray: its pall is a subset of ray pall
        a = v.a * exp(min(pallTau(dir, depth / axis, bestDepth / axis), 80.0));
    } else {
        // ray nearly opaque (RGBA16F transmittance becomes zero beyond depth ~17; e^τ cannot restore it from zero):
        // transmittance to pixel from noiseless field
        a = exp(-pallTau(dir, 0.0, depth / axis));
    }
    a = clamp(a, 0.0, 1.0);
    return vec4(v.rgb * ((1.0 - a) / (1.0 - v.a)), a);
}

void main() {
    ivec2 fullSize = textureSize(Sampler1, 0), lowSize = textureSize(Sampler0, 0);
    ivec2 full = ivec2(gl_FragCoord.xy);
    float depth = pallScene(texelFetch(Sampler1, clamp(full, ivec2(0), fullSize - 1), 0).r);
    vec2 ndc0 = (vec2(full) + 0.5) / vec2(fullSize) * 2.0 - 1.0;
    vec4 far0 = InvViewProj * vec4(ndc0, 1.0, 1.0);
    vec4 pall = upsample(full, fullSize, lowSize, depth, normalize(far0.xyz / far0.w));
    if (PallFire0[0].x > 0.0 || PallFire1[0].x > 0.0) {
        vec2 ndc = (vec2(full) + 0.5) / vec2(fullSize) * 2.0 - 1.0;
        vec4 farPoint = InvViewProj * vec4(ndc, 1.0, 1.0), nextPoint = InvViewProj * vec4(ndc + vec2(0.0, 2.0 / float(fullSize.y)), 1.0, 1.0);
        vec3 dir = normalize(farPoint.xyz / farPoint.w);
        float dist = depth / max(dot(dir, PallForward), 1e-3);
        if (depth >= 1e8) {
            // no world behind pixel (beyond render distance without Distant Horizons, beyond LOD edge): ray ends at terrain
            // under pall, as in fx_pall; burning city beyond render distance is visible as lights on it
            dist = min(pallFloor(Sampler5, PallSlot0, PallGround0, dir, PallFloorFrom, 1e6, 16, 0.5),
                    pallFloor(Sampler6, PallSlot1, PallGround1, dir, PallFloorFrom, 1e6, 16, 0.5));
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
        // pall shadows sky/sun light: torches light mines/caves (position is air block before surface)
        float kept = mix(1.0, pallDisplay(shade(p, over)), seen * pallOpenHere(p - 0.5 * dir));
        vec3 added = vec3(0.0), n = vec3(0.0, 1.0, 0.0);
        if (balls) {
            n = sceneNormal(Sampler1, InvViewProj, PallForward, full, fullSize, p);
            added = ballLight(p, n, over) * seen;
        }
        if (dot(added, LUMA) > 1e-6) {
            // brighter world: full pixel, pall plus ball-lit world behind (alpha 0, blending ignores frame); above
            // white, toward white (CROSS, like matter light in fx_pall), no brighter than 1 with same hue
            vec3 world = texelFetch(Sampler9, full, 0).rgb, open = PallOpenSky * faceShade(PallFaceShade, n);
            vec3 lit = vec3(relit(world.r, kept, open.r, added.r), relit(world.g, kept, open.g, added.g),
                    relit(world.b, kept, open.b, added.b));
            lit += vec3(CROSS * max(dot(lit, LUMA) - 1.0, 0.0));
            vec3 shown = vec3(pallDisplay(lit.r), pallDisplay(lit.g), pallDisplay(lit.b));
            shown /= max(max(max(shown.r, shown.g), shown.b), 1.0);
            pall = vec4(pall.rgb + pall.a * shown, 0.0);
        } else {
            pall.a *= kept;
        }
    }
    // accumulated pall always valid (fx_pall_resolve), but no input may damage frame world after it
    fragColor = pallValid(pall) ? pall : vec4(0.0, 0.0, 0.0, 1.0);
}
