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
// terrain/water under slot pall, their parameters and slot water: pall matter before point
uniform sampler2D Sampler9;
uniform sampler2D Sampler10;
uniform vec4 PallGround0;
uniform vec4 PallGround1;
uniform vec4 PallWater0;
uniform vec4 PallWater1;
// slot dust wall (client/nuclear/BlastWall): rolling head and Wilson cloud
uniform mat4 PallWall0;
uniform mat4 PallWall1;
uniform vec4 PallWilson0;
uniform vec4 PallWilson1;
// frame mushrooms (mushroom.glsl, MushroomField): mushroom occlusion before point
uniform sampler2D Sampler11;
// eye-local outdoor air (pall.glsl: pallOpen, client/fx/layer/OpenAir); own texture unit
uniform sampler3D PallOpen;
uniform vec4 PallOpenAt;
uniform vec3 PallOpenWrap;

// outdoor-air share at p (pall.glsl: pallOpen), outside-window terrain from first available slot map
float pallOpenHere(vec3 p) {
    if (PallSlot0[1].w > 0.5) return pallOpen(PallOpen, PallOpenAt, PallOpenWrap, Sampler9, PallSlot0, PallGround0, p);
    return pallOpen(PallOpen, PallOpenAt, PallOpenWrap, Sampler10, PallSlot1, PallGround1, p);
}

// Slot wall head before point on [t0, t1]: transmitted share per ray span
// (pallWallSpans), in air haze at span midpoint (pallAir)
float pallWallThrough(sampler2D ground, mat4 S, mat4 V, vec4 G, vec4 W, vec3 dir, float t0, float t1) {
    vec4 spans = pallWallSpans(S, V, G, dir, t0, t1);
    float through = 1.0;
    for (int k = 0; k < 2; k++) {
        vec2 sp = k == 0 ? spans.xy : spans.zw;
        if (sp.y <= sp.x) continue;
        // wall head is also outdoor-air matter (as in pall ray, fx_pall: pallStep)
        float tau = pallWallTau(ground, S, V, G, W, dir, sp.x, sp.y, 4) * pallOpenHere(dir * (0.5 * (sp.x + sp.y)));
        through *= 1.0 - pallAir(PallAir, 0.5 * (sp.x + sp.y), dir.y) * (1.0 - exp(-tau));
    }
    return through;
}

// Slot Wilson cloud before point (up to end): transmittance of both ray crossings, each in local haze
float pallWilsonThrough(mat4 S, vec4 L, vec3 dir, float end) {
    vec4 w = pallWilson(S, L, dir, end);
    return (1.0 - pallAir(PallAir, w.x, dir.y) * (1.0 - exp(-w.y))) * (1.0 - pallAir(PallAir, w.z, dir.y) * (1.0 - exp(-w.w)));
}

// Pall transmittance between eye and point dir·len (without mushrooms): six noiseless-field steps (noise mean
// equals field); wall head/Wilson cloud over own ray spans. Distant pall fades into air haze like mushroom (pallAir):
// opacity share consumed by haze does not occlude point, as in pall pass (fx_pall: passMatter).
float pallThrough(vec3 dir, float len) {
    if (PallAmbient.w < 0.5) return 1.0;
    vec2 span = pallBox(dir, PallMin, PallMax);
    float t0 = span.x, t1 = min(span.y, len);
    if (t1 <= t0) return 1.0;
    float through = 1.0, ds = (t1 - t0) / 6.0;
    for (int i = 0; i < 6; i++) {
        float t = t0 + (float(i) + 0.5) * ds, tau = 0.0;
        vec3 q = dir * t;
        // full depth of all matter, like volume ray (mist also attenuates what lies behind)
        if (PallSlot0[1].w > 0.5) {
            PallAt f = pallField(Sampler5, Sampler9, PallSlot0, PallGround0, PallWater0, q, pallRelief(Sampler9, PallSlot0, PallGround0, q.xz), 1.0);
            tau += (f.sigma.x + f.sigma.y + f.sigma.z) * ds;
        }
        if (PallSlot1[1].w > 0.5) {
            PallAt f = pallField(Sampler5, Sampler10, PallSlot1, PallGround1, PallWater1, q, pallRelief(Sampler10, PallSlot1, PallGround1, q.xz), 1.0);
            tau += (f.sigma.x + f.sigma.y + f.sigma.z) * ds;
        }
        through *= 1.0 - pallAir(PallAir, t, dir.y) * (1.0 - exp(-tau * pallOpenHere(q)));
    }
    return through * pallWallThrough(Sampler9, PallSlot0, PallWall0, PallGround0, PallWater0, dir, t0, t1)
            * pallWallThrough(Sampler10, PallSlot1, PallWall1, PallGround1, PallWater1, dir, t0, t1)
            * pallWilsonThrough(PallSlot0, PallWilson0, dir, t1) * pallWilsonThrough(PallSlot1, PallWilson1, dir, t1);
}
