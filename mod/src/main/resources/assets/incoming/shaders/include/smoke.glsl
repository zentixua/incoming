// Camera smoke grid (client/fx/smoke/SmokeGrid, client/fx/layer/SmokePass): conventional blast smoke, dust, fire (and other
// sources), 3D extinction texture in eye-centered cascades. Shared by pall rays (fx_pall), frame composite (smoke world
// shadow, fx_pall_composite), layer quads and source probes (pall_front.glsl: foreground smoke occlusion).
// Material constants copied from SmokeGrid (SmokeGlslTest checks).
//
// Texture: s world x (two N-cell pages), t world z, r height (N layers per cascade, cascades consecutive).
// Page 0: soot/dust/droplet extinction (1/block), Σ extinction × ΔT (kK); page 1: Σ dust extinction × dust albedo ω'
// (rgb), Σ extinction × rise (blocks/s). Cascade c (SmokeCascade column c): eye-relative corner (xyz), cell (w);
// each has its own billows, local density sums contributing cascades. SmokeCascade[3].x is 1 when grid filled this frame.
// Cascade c box (SmokeLo[c], SmokeHi[c], eye-relative; SmokeLo.w is 1 if cascade has billows) bounds its billows (three σ)
// inside cascade: outside, empty, no samples or cell steps.

uniform sampler3D SmokeGrid;
uniform mat4 SmokeCascade;
// eye-relative whole-smoke bounds
uniform vec3 SmokeMin;
uniform vec3 SmokeMax;
// eye-relative cascade boxes (column c)
uniform mat4 SmokeLo;
uniform mat4 SmokeHi;

const int SMOKE_N = 64;
const int SMOKE_CASCADES = 3;
// delta-Eddington albedo ω', asymmetry g': soot, dust (cell albedo), droplets (SmokeGrid)
const float SMOKE_SOOT_W = 0.2;
const float SMOKE_SOOT_G = 0.33333334;
const float SMOKE_DUST_G = 0.375;
const float SMOKE_DROP_W = 0.99963975;
const float SMOKE_DROP_G = 0.45945945;
// ambient air, K (SmokeGrid.AIR_K)
const float SMOKE_AIR_K = 288.15;

bool smokeOn() {
    return SmokeCascade[3].x > 0.5;
}

// Eye-relative p within cascade c billow box
bool smokeIn(vec3 p, int c) {
    return SmokeLo[c].w > 0.5 && all(greaterThanEqual(p, SmokeLo[c].xyz)) && all(lessThanEqual(p, SmokeHi[c].xyz));
}

// Grid page (0 or 1) at eye-relative p: sum cascades whose boxes contain point; cascade edge clamps to outer cell
// (billows never reach edge: SmokeGrid assigns a billow only if entirely inside)
vec4 smokeFetch(vec3 p, float page) {
    vec4 sum = vec4(0.0);
    float n = float(SMOKE_N);
    for (int c = 0; c < SMOKE_CASCADES; c++) {
        if (!smokeIn(p, c)) continue;
        vec4 C = SmokeCascade[c];
        vec3 l = clamp((p - C.xyz) / C.w, vec3(0.5), vec3(n - 0.5));
        sum += textureLod(SmokeGrid, vec3((page * n + l.x) / (2.0 * n), l.z / n, (float(c) * n + l.y) / (float(SMOKE_CASCADES) * n)), 0.0);
    }
    return sum;
}

// Smoke extinction at eye-relative p, 1/block: soot/dust/droplets combined (mean field without noise billows)
float smokeSigma(vec3 p) {
    vec4 s = smokeFetch(p, 0.0);
    return s.x + s.y + s.z;
}

// Finest cascade cell whose box contains p, blocks (outside boxes, last cascade cell)
float smokeCell(vec3 p) {
    for (int c = 0; c < SMOKE_CASCADES; c++) {
        if (smokeIn(p, c)) return SmokeCascade[c].w;
    }
    return SmokeCascade[SMOKE_CASCADES - 1].w;
}

// Empty space near p along dir: inside box, its cascade cell step (gap = cell); outside, distance to nearest box entry
// ahead (gap; cell its cascade cell); no boxes ahead — 1e9
float smokeGap(vec3 p, vec3 dir, out float cell) {
    for (int c = 0; c < SMOKE_CASCADES; c++) {
        if (smokeIn(p, c)) {
            cell = SmokeCascade[c].w;
            return cell;
        }
    }
    float best = 1e9;
    cell = SmokeCascade[SMOKE_CASCADES - 1].w;
    for (int c = 0; c < SMOKE_CASCADES; c++) {
        if (SmokeLo[c].w < 0.5) continue;
        vec2 s = pallBox(dir, SmokeLo[c].xyz - p, SmokeHi[c].xyz - p);
        if (s.y > s.x && s.x < best) {
            best = s.x;
            cell = SmokeCascade[c].w;
        }
    }
    return best;
}

// Smoke depth from a along dir over len: steps mean-field samples at equal-segment midpoints
float smokeTau(vec3 a, vec3 dir, float len, int steps) {
    if (len <= 0.0) return 0.0;
    float ds = len / float(steps), tau = 0.0;
    for (int i = 0; i < steps; i++) tau += smokeSigma(a + dir * ((float(i) + 0.5) * ds));
    return tau * ds;
}

// Smoke transmittance between eye and dir·len (six samples over ray bounds): layer quads/source probes behind smoke
float smokeThrough(vec3 dir, float len) {
    if (!smokeOn()) return 1.0;
    vec2 span = pallBox(dir, SmokeMin, SmokeMax);
    float t0 = max(span.x, 0.0), t1 = min(span.y, len);
    if (t1 <= t0) return 1.0;
    return exp(-smokeTau(dir * t0, dir, t1 - t0, 6));
}

// Sunlight transmitted by smoke to eye-relative p: sunward depth sampled at 2, 8, 24, 64, 160 blocks
// (smoke above point spans meters to hundreds)
float smokeSun(vec3 p, vec3 sun) {
    if (!smokeOn()) return 1.0;
    // sun ray misses smoke bounds — no shadow
    vec2 span = pallBox(sun, SmokeMin - p, SmokeMax - p);
    if (span.y <= max(span.x, 0.0)) return 1.0;
    float tau = smokeSigma(p + sun * 2.0) * 4.0 + smokeSigma(p + sun * 8.0) * 9.0 + smokeSigma(p + sun * 24.0) * 26.0
            + smokeSigma(p + sun * 64.0) * 60.0 + smokeSigma(p + sun * 160.0) * 130.0;
    return exp(-tau);
}
