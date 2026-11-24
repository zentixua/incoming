// Nuclear pall before a point: noiseless mean field (pall.glsl: import before this file), shared code/numbers for layer quads
// (fx.vsh: quad behind pall) and source probes (fx_probe: glare/veil behind dust), supplied
// by PallPass.bindFront. Frame mushrooms (mushroom.glsl) use own field separately; probes exclude own cap.

// pall (pall.glsl, PallPass): field table, detonation slots, bounds, mean light (w: 1 if pall in frame)
uniform sampler2D Sampler5;
uniform mat4 PallSlot0;
uniform mat4 PallSlot1;
uniform vec3 PallMin;
uniform vec3 PallMax;
uniform vec4 PallAmbient;
// frame air (pall.glsl: pallAir)
uniform vec4 PallAir;
// terrain/water under slot pall and their parameters: pall matter before point
uniform sampler2D Sampler9;
uniform sampler2D Sampler10;
uniform vec4 PallGround0;
uniform vec4 PallGround1;
// slot Wilson cloud (client/nuclear/BlastWall)
uniform vec4 PallWilson0;
uniform vec4 PallWilson1;
// frame mushrooms (mushroom.glsl, MushroomField): mushroom occlusion before point
uniform sampler2D Sampler11;
// eye-local outdoor air (pall.glsl: pallOpen, client/fx/layer/OpenAir); own texture unit
uniform sampler3D PallOpen;
uniform vec4 PallOpenAt;
uniform vec3 PallOpenWrap;

// the dust pass's resolved atlas (DustWallPass: Sampler10 in place of the second slot's relief map, which is empty then), its
// divisor (0 — no dust pass this frame) and view
#moj_import <incoming:wall_layer.glsl>
uniform mat4 DwViewProj;
uniform float DwDiv;

// Share of light from eye-relative p that the dust pass's ground dust in front of it passes (wallTo at p's depth); outside the
// atlas, 1
float dustWallThrough(sampler2D scene, vec3 p) {
    if (DwDiv <= 0.0) return 1.0;
    vec4 clip = DwViewProj * vec4(p, 1.0);
    if (clip.w <= 0.0) return 1.0;
    vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThanEqual(uv, vec2(1.0)))) return 1.0;
    ivec2 size = textureSize(scene, 0);
    vec2 pixel = uv * vec2(size);
    float here = pallScene(texelFetch(scene, ivec2(pixel), 0).r);
    return wallTo(wallLayer(Sampler10, scene, pixel, here, DwDiv), clip.w).a;
}

// outdoor-air share at p (pall.glsl: pallOpen), outside-window terrain from first available slot map
float pallOpenHere(vec3 p) {
    if (PallSlot0[1].w > 0.5) return pallOpen(PallOpen, PallOpenAt, PallOpenWrap, Sampler9, PallSlot0, PallGround0, p);
    return pallOpen(PallOpen, PallOpenAt, PallOpenWrap, Sampler10, PallSlot1, PallGround1, p);
}

// Slot Wilson cloud before point (up to end): transmittance of both ray crossings
float pallWilsonThrough(mat4 S, vec4 L, vec3 dir, float end) {
    vec4 w = pallWilson(S, L, dir, end);
    return exp(-w.y - w.w);
}

// Pall transmittance between eye and point dir·len (without mushrooms): six noiseless-field steps of the haze (noise mean
// equals field), the Wilson cloud over its ray crossings, and the dust pass's ground dust (dustWallThrough). Real
// transmittance: haze in front of pall shows the air's own light in the pall frame (fx_pall: passMatter), it never makes the
// pall transparent to what lies behind it.
float pallThrough(sampler2D scene, vec3 dir, float len) {
    if (PallAmbient.w < 0.5) return 1.0;
    vec2 span = pallBox(dir, PallMin, PallMax);
    float t0 = span.x, t1 = min(span.y, len);
    float through = dustWallThrough(scene, dir * len);
    if (t1 <= t0) return through;
    float ds = (t1 - t0) / 6.0;
    for (int i = 0; i < 6; i++) {
        float t = t0 + (float(i) + 0.5) * ds, tau = 0.0;
        vec3 q = dir * t;
        if (PallSlot0[1].w > 0.5) {
            PallAt f = pallField(Sampler5, Sampler9, PallSlot0, PallGround0, q, pallRelief(Sampler9, PallSlot0, PallGround0, q.xz));
            tau += f.sigma * ds;
        }
        if (PallSlot1[1].w > 0.5) {
            PallAt f = pallField(Sampler5, Sampler10, PallSlot1, PallGround1, q, pallRelief(Sampler10, PallSlot1, PallGround1, q.xz));
            tau += f.sigma * ds;
        }
        through *= exp(-tau * pallOpenHere(q));
    }
    return through * pallWilsonThrough(PallSlot0, PallWilson0, dir, t1) * pallWilsonThrough(PallSlot1, PallWilson1, dir, t1);
}
