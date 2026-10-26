#version 150

#moj_import <incoming:pall.glsl>
#moj_import <incoming:mushroom.glsl>

// Mushroom sunlight shadow map (client/fx/layer/PallPass, before pall rays; mushroom.glsl: shadow map): pixel is texel
// in mushroom tile, a line along sunlight through its position in the sun-perpendicular plane. Depth uses noiseless mushroom field
// (mushMean, billow mean: ground shadow follows cloud mass, edge blurred by billow extent), stepping down from sun through
// line segment within mushroom bounds; empty space jumps to matter (mushGap). At layer heights, record matter above them
// (optical depth, its line centroid, crosswise cloud width/albedo); below last step, all matter on line.
// Light reaching layer is direct beam through this depth plus forward-scattered light: two streams in a sun-aligned column (like
// light on mushroom: each part has own albedo/asymmetry, water column uses drops); ground receives the share with which a
// cloud this wide at this distance is visible from ground (mushSeen; part width from shadow area, mushAcross).

// mushroom table (MushroomField)
uniform sampler2D Sampler0;
// sun direction (xyz), daytime sun strength (w)
uniform vec4 PallSun;

out vec4 fragColor;

// Sunlight reaching layer below mushroom matter (MushroomField.shadowThrough): optical depth tau, matter centroid on line d from
// layer, crosswise cloud half-width width, albedo w, asymmetry g (after peak truncation); mu is beam cosine to ground
// at frame output
float through(float tau, float d, float width, float w, float g, float mu) {
    if (tau < 1e-4) return 1.0;
    float scattered = max(pallDiffuse(w, g, tau, tau, 0.0, 1.0, 1.0, 1.0, 0.0, 0.0, 0.0, -0.6666667), 0.0);
    return exp(-tau) + scattered * mushSeen(width, d, mu);
}

void main() {
    ivec2 at = ivec2(gl_FragCoord.xy);
    int k = at.y / MUSH_SHADOW;
    if (k >= MUSHROOMS || !mushOn(Sampler0, k)) {
        fragColor = vec4(1.0);
        return;
    }
    Mush m = mushLoad(Sampler0, k);
    vec4 rect = texelFetch(Sampler0, ivec2(MUSH_SHADOW_RECT, k), 0);
    float top = texelFetch(Sampler0, ivec2(MUSH_SHADOW_TOP, k), 0).x;
    vec3 sun = mushShadowSun(PallSun.xyz), a, b;
    mushShadowAxes(sun, a, b);
    // cap/skirt/water-column half-width across sun from shadow area (stem at step height); cap spans from ring
    // bottom to dome top
    float capH = max(m.capY + m.ringB, m.domeY + m.domeH) - (m.capY - m.ringB);
    float capW = mushAcross(m.capR, capH, sun.y), skirtW = mushAcross(m.skirtR + m.skirtW, m.skirtH, sun.y);
    float colW = mushAcross(max(m.colMid + m.colHalf, m.crownR), m.colTop + m.crownH, sun.y);
    vec2 uv = (vec2(float(at.x), float(at.y - k * MUSH_SHADOW)) + 0.5) / float(MUSH_SHADOW);
    vec2 w = rect.xy + uv * rect.zw;
    // line: point in sun-perpendicular plane (eye-relative), step along sun; height above epicenter ground is h0 + sun.y·s
    vec3 o = m.base + a * w.x + b * w.y;
    vec2 span = mushLine(m, o, sun);
    float h0 = o.y - m.base.y;
    // layer-height positions on line: epicenter ground, midpoint to world top, top
    vec3 layer = (vec3(0.0, 0.5 * top, top) - h0) / sun.y;
    // matter above layers: optical depth and its shares of line position, cloud width, albedo/asymmetry (divide by depth)
    vec3 tauL = vec3(0.0), atL = vec3(0.0), wideL = vec3(0.0), albL = vec3(0.0), asymL = vec3(0.0);
    float tau = 0.0, atS = 0.0, wide = 0.0, alb = 0.0, asym = 0.0, s = span.y;
    if (span.y > span.x) {
        float ds = (span.y - span.x) / float(MUSH_SHADOW_STEPS);
        for (int i = 0; i <= MUSH_SHADOW_STEPS; i++) {
            if (s <= span.x || tau >= MUSH_SHADOW_DARK) break;
            float gap = mushGap(m, mushLocal(m, o + sun * s));
            // downward from s by step (or empty-space jump, no matter), sample at step midpoint
            float from = max(s - max(gap, ds), span.x);
            float sigma = 0.0, width = 0.0, albedo = 0.0, g = 0.0;
            if (gap <= ds) {
                vec3 q = mushLocal(m, o + sun * (0.5 * (s + from)));
                vec4 matter = mushMean(m, q);
                sigma = dot(matter, vec4(1.0));
                if (sigma > 0.0) {
                    width = (matter.x * capW + matter.y * mushAcross(mushStemAt(m, q.y), m.stemTop, sun.y) + matter.z * skirtW + matter.w * colW) / sigma;
                    albedo = (matter.x * m.capMat.g + matter.y * m.stemMat.g + matter.z * m.skirtMat.g + matter.w * m.colMat.g) / sigma;
                    g = (matter.x * m.capMat.a + matter.y * m.stemMat.a + matter.z * m.skirtMat.a + matter.w * m.colMat.a) / sigma;
                }
            }
            // layers crossed by line this step: matter above them counts only up to them
            vec3 crossed = step(vec3(from), layer) * (vec3(1.0) - step(vec3(s), layer));
            vec3 part = sigma * max(vec3(s) - layer, vec3(0.0));
            tauL = mix(tauL, vec3(tau) + part, crossed);
            atL = mix(atL, vec3(atS) + part * 0.5 * (vec3(s) + layer), crossed);
            wideL = mix(wideL, vec3(wide) + part * width, crossed);
            albL = mix(albL, vec3(alb) + part * albedo, crossed);
            asymL = mix(asymL, vec3(asym) + part * g, crossed);
            float dt = sigma * (s - from);
            tau += dt;
            atS += dt * 0.5 * (s + from);
            wide += dt * width;
            alb += dt * albedo;
            asym += dt * g;
            s = from;
        }
    }
    // layers below last step lie beneath all line matter
    vec3 below = step(layer, vec3(s));
    tauL = mix(tauL, vec3(tau), below);
    atL = mix(atL, vec3(atS), below);
    wideL = mix(wideL, vec3(wide), below);
    albL = mix(albL, vec3(alb), below);
    asymL = mix(asymL, vec3(asym), below);
    vec3 n = max(tauL, vec3(1e-6));
    float mu = max(PallSun.y, 0.12);
    fragColor = vec4(through(tauL.x, atL.x / n.x - layer.x, wideL.x / n.x, albL.x / n.x, asymL.x / n.x, mu),
            through(tauL.y, atL.y / n.y - layer.y, wideL.y / n.y, albL.y / n.y, asymL.y / n.y, mu),
            through(tauL.z, atL.z / n.z - layer.z, wideL.z / n.z, albL.z / n.z, asymL.z / n.z, mu), 1.0);
}
