// Conventional-weapon ball/plume world light and shadows (client/fx/layer/HeatPass), shared by half-resolution
// visibility pass (fx_heat_shadow: shadow rays) and full-frame light pass (fx_heat: light using this visibility).
// Direct light only: inverse squares, air absorption, face cosine. Air-scattered and ground-reflected
// conventional-flash light is not modeled here (approximation): Glasstone nuclear thermal curve does not define
// conventional-source visible angular transfer and caused horizontal-ground light to grow beyond ~0.05 visibility range.

// light spheres: column i is eye-relative center (xyz), radius (w; 0 absent); linear light on facing white wall
// (rgb), air extinction 1/block (w)
uniform mat4 HeatLights;
uniform mat4 HeatLightColor;
// eye-local occluders (Solids, own texture unit, absent from JSON): eye-relative window lower corner (xyz), window present (w);
// texture position of that corner
uniform sampler3D HeatSolids;
uniform vec4 HeatSolidsAt;
uniform vec3 HeatSolidsWrap;
// plumes: column i is eye-relative nozzle exit (xyz), core end (w; 0 absent); jet axis from nozzle (xyz), flame end (w);
// jet end, exit half-width, growth per meter, air extinction; core/flame/tail light per meter (rgb)
uniform mat4 HeatJet;
uniform mat4 HeatJetAxis;
uniform mat4 HeatJetSize;
uniform mat4 HeatJetCore;
uniform mat4 HeatJetFlame;
uniform mat4 HeatJetTail;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
// Solids window, blocks; maximum shadow-ray cells (HeatPass.SHADOW_CELLS)
const vec3 SOLIDS_SIZE = vec3(192.0, 96.0, 192.0);
const int SHADOW_CELLS = 160;
// below this light (linear screen, below sRGB step near black), skip invisible shadow
const float SHADOW_SEEN = 3e-4;
// also skip below this share of total face illumination: eye contrast threshold ~1–2 % (Blackwell, JOSA 36, 1946; higher
// at night, so conservative threshold) (HeatPass.WEBER)
const float WEBER = 0.02;

// Lambertian sphere face illuminance per surface brightness unit (HeatPass.ballGain).
float heatBallGain(float d2, float r2, float sd, float nv) {
    return r2 / max(d2, r2) * exp(-sd) * max(nv, 0.0);
}

// Part [t.x, t.y] of segment from face-relative a along unit u above face normal n (HeatPass.above); empty if
// y <= x
vec2 heatAbove(vec3 a, vec3 u, vec2 t, vec3 n) {
    float na = dot(n, a), nu = dot(n, u);
    if (abs(nu) > 1e-6) {
        float ts = -na / nu;
        if (nu > 0.0) {
            t.x = max(t.x, ts);
        } else {
            t.y = min(t.y, ts);
        }
    } else if (na <= 0.0) {
        t.y = t.x;
    }
    return t;
}

// Direct uniform-segment face light, ∫ cos θ / d² (HeatPass.segment): segment from face-relative a along
// unit u over [t.x, t.y], already above face (heatAbove), normal n; w2 is squared jet half-width
float heatSegment(vec3 a, vec3 u, vec2 t, vec3 n, float w2) {
    if (t.y <= t.x) return 0.0;
    float na = dot(n, a), nu = dot(n, u);
    float b = dot(a, u);
    float h2 = max(dot(a, a) - b * b, 0.0) + w2;
    float c0 = na - b * nu, s0 = t.x + b, s1 = t.y + b;
    float q0 = 1.0 / (s0 * s0 + h2), q1 = 1.0 / (s1 * s1 + h2), r0 = sqrt(q0), r1 = sqrt(q1);
    // (s1·r1 − s0·r0)/h² and r1 − r0 without near-equal subtraction
    float k = (s1 * r1 - s0 * r0) / h2;
    if (s0 * s1 > 0.0) k = (s1 - s0) * (s1 + s0) * q0 * q1 / (s1 * r1 + s0 * r0);
    float dr = (s0 - s1) * (s0 + s1) * q0 * q1 / (r0 + r1);
    return max(c0 * k - nu * dr, 0.0);
}

// Path p to q (eye-relative) clear of occluder blocks (HeatPass.blocked): 1 yes, 0 no. Window cells along
// segment in order (Amanatides, Woo 1987), outside-window part clear; world point's own cell is its face, not an
// occluder. No integer division/branching per step: modular window via texture repeat (cell-center sampling, no
// mipmaps), stepping axis via step at nearest face (GPU remainder by 192 and branching axis selection each
// step cost more than sampling itself)
float heatBlocked(vec3 p, vec3 q) {
    if (HeatSolidsAt.w < 0.5) return 1.0;
    vec3 a = p - HeatSolidsAt.xyz, d = q - p;
    float t0 = 0.0, t1 = 1.0;
    for (int k = 0; k < 3; k++) {
        if (abs(d[k]) < 1e-6) {
            if (a[k] < 0.0 || a[k] >= SOLIDS_SIZE[k]) return 1.0;
        } else {
            float ta = -a[k] / d[k], tb = (SOLIDS_SIZE[k] - a[k]) / d[k];
            t0 = max(t0, min(ta, tb));
            t1 = min(t1, max(ta, tb));
        }
    }
    if (t1 <= t0) return 1.0;
    vec3 cell = clamp(floor(a + d * t0), vec3(0.0), SOLIDS_SIZE - 1.0);
    vec3 dir = sign(d), inv = 1.0 / max(abs(d), vec3(1e-9));
    // path to next cell face per axis; stationary axis never steps
    vec3 next = mix(vec3(1e9), (cell + max(dir, 0.0) - a) * dir * inv, abs(dir));
    vec3 base = HeatSolidsWrap + 0.5, scale = 1.0 / SOLIDS_SIZE;
    bool own = t0 <= 0.0;
    for (int i = 0; i < SHADOW_CELLS; i++) {
        if (!own && textureLod(HeatSolids, (cell + base) * scale, 0.0).r > 0.5) return 0.0;
        own = false;
        float t = min(min(next.x, next.y), next.z);
        if (t >= t1) break;
        vec3 m = step(next, vec3(t));
        cell += m * dir;
        next += m * inv;
        if (any(lessThan(cell, vec3(0.0))) || any(greaterThanEqual(cell, SOLIDS_SIZE))) break;
    }
    return 1.0;
}

// Visible disk share of ball centered b, radius r, from from (HeatPass.ballSeen): near-ground occluder edge is almost
// horizontal; disk share above edge at height y is chord-width integral 2√(1 − y²); second-kind Gauss–Chebyshev
// quadrature along vertical diameter, nodes 0 and ±r·cos(π/4), weights ½ and ¼. Edge from below: visible center implies upper
// node visible, test lower; hidden center implies lower hidden, test upper (two rays, shares 0, ¼, ¾, 1). Ray ends
// at ball edge facing point (no occluders inside ball)
float heatBallSeen(vec3 from, vec3 b, float r) {
    vec3 v = b - from;
    float d = length(v);
    v /= max(d, 1e-3);
    float end = 1.0 - r / max(d, r);
    float mid = heatBlocked(from, from + (b - from) * end);
    // disk vertical: world up minus ray-aligned part (nearly vertical ray: any perpendicular)
    vec3 up = vec3(0.0, 1.0, 0.0) - v * v.y;
    if (dot(up, up) < 1e-4) up = vec3(1.0, 0.0, 0.0) - v * v.x;
    up = normalize(up) * (0.70710678 * r);
    if (mid > 0.5) return 0.75 + 0.25 * heatBlocked(from, from + (b - up - from) * end);
    return 0.25 * heatBlocked(from, from + (b + up - from) * end);
}

// Shadow-ray start: world point two pixel widths off face (at least 2 cm), making its cell air before face
vec3 heatLift(vec3 p, vec3 n, float pixel) {
    return p + n * max(0.02, 2.0 * pixel * length(p));
}

// Ball i on face at p, normal n: total light (return), direct beam direct (0 at/inside ball, no
// shadow there)
vec3 heatBall(int i, vec3 p, vec3 n, out vec3 direct) {
    direct = vec3(0.0);
    vec4 b = HeatLights[i];
    if (b.w <= 0.0) return vec3(0.0);
    vec3 l = b.xyz - p;
    float d2 = dot(l, l), d = sqrt(d2);
    vec3 v = l / max(d, 1e-3);
    float sd = HeatLightColor[i].w * d, nv = dot(n, v);
    vec3 c = HeatLightColor[i].rgb * heatBallGain(d2, b.w * b.w, sd, nv);
    if (d > b.w) direct = c;
    return c;
}

// Plume i on face: direct light (integral over parts above face); target is its light centroid (shadow-ray target).
vec3 heatJet(int i, vec3 p, vec3 n, out vec3 target) {
    target = p;
    vec4 j = HeatJet[i];
    if (j.w <= 0.0) return vec3(0.0);
    vec4 ax = HeatJetAxis[i], sz = HeatJetSize[i];
    vec3 a = j.xyz - p, u = ax.xyz;
    vec3 direct = vec3(0.0), lit = vec3(0.0);
    float weight = 0.0;
    for (int k = 0; k < 3; k++) {
        // core/flame from exit (flame surrounds core), tail beyond flame
        vec3 q = HeatJetCore[i].rgb;
        float t0 = 0.0, t1 = j.w;
        if (k == 1) {
            q = HeatJetFlame[i].rgb;
            t1 = ax.w;
        } else if (k == 2) {
            q = HeatJetTail[i].rgb;
            t0 = ax.w;
            t1 = sz.x;
        }
        if (dot(q, LUMA) <= 0.0 || t1 <= t0) continue;
        float mid = 0.5 * (t0 + t1), w = sz.y + sz.z * mid;
        vec3 l = a + u * mid;
        float d2 = max(dot(l, l), 0.25 * (t1 - t0) * (t1 - t0) + w * w), d = sqrt(d2);
        float sd = sz.w * d;
        vec2 above = heatAbove(a, u, vec2(t0, t1), n);
        vec3 e = q * (exp(-sd) * heatSegment(a, u, above, n, w * w));
        direct += e;
        float y = dot(e, LUMA);
        lit += y * (a + u * (0.5 * (above.x + above.y)));
        weight += y;
    }
    if (weight > 0.0) target = p + lit / weight;
    return direct;
}

// Ball/plume world light at eye-relative p, normal n, linear on white wall; direct-light visibility
// from ballSeen/jetSeen (0..1, visibility pass)
vec3 heatLight(vec3 p, vec3 n, vec4 ballSeen, vec4 jetSeen) {
    vec3 sum = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        vec3 direct;
        vec3 lit = heatBall(i, p, n, direct);
        sum += lit - direct * (1.0 - ballSeen[i]);
    }
    for (int i = 0; i < 4; i++) {
        vec3 target;
        vec3 direct = heatJet(i, p, n, target);
        sum += direct * jetSeen[i];
    }
    return sum;
}

// Source direct-light visibility at p, normal n (pixel is pixel angle): shadow rays only for sources whose
// direct light is at least WEBER of total face illumination (prior is linear skylight on face plus unshadowed
// light from all sources); others get 1 (eye cannot resolve shadow below threshold)
void heatShadows(vec3 p, vec3 n, float prior, float pixel, out vec4 ballSeen, out vec4 jetSeen) {
    ballSeen = vec4(1.0);
    jetSeen = vec4(1.0);
    vec4 ballLum = vec4(0.0), jetLum = vec4(0.0);
    float total = prior;
    for (int i = 0; i < 4; i++) {
        vec3 direct;
        total += dot(heatBall(i, p, n, direct), LUMA);
        ballLum[i] = dot(direct, LUMA);
        vec3 target;
        vec3 jet = heatJet(i, p, n, target);
        total += dot(jet, LUMA);
        jetLum[i] = dot(jet, LUMA);
    }
    float seen = max(SHADOW_SEEN, WEBER * total);
    vec3 from = heatLift(p, n, pixel);
    for (int i = 0; i < 4; i++) {
        if (ballLum[i] > seen) ballSeen[i] = heatBallSeen(from, HeatLights[i].xyz, HeatLights[i].w);
        if (jetLum[i] > seen) {
            vec3 target;
            heatJet(i, p, n, target);
            jetSeen[i] = heatBlocked(from, target);
        }
    }
}
