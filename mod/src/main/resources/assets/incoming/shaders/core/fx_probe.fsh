#version 150

// Source share visible in frame (client/fx/layer/SourceProbes): world distance behind pixel (SceneDepth,
// including Distant Horizons LOD) at screen source center and around its upper half-circle, versus
// front-half distance. Outside frame: 1, caller rays decide visibility there. World depth misses nuclear pall,
// dust-wall and mushroom volumes (fireball behind dust, another cap/stem); their transmittance to source is handled
// here too, inside/outside frame (pall_front.glsl, as for layer quads; layer no longer clips glare/veil by them).
#moj_import <incoming:pall.glsl>
#moj_import <incoming:mushroom.glsl>
#moj_import <incoming:smoke.glsl>
#moj_import <incoming:pall_front.glsl>

uniform sampler2D Sampler0;

// eye view and frame projection
uniform mat4 SourceView;
uniform mat4 SourceProj;
// pixel angle, rad
uniform float Pixel;

in vec3 center;
in float radius;

out vec4 fragColor;

// Mushroom transmittance from eye to source centered at center, radius radius, without noise (like mushTransmit). A mushroom
// whose cap center is this source (fireball/cap emit from it) does not occlude itself: its cap counts only to source front
// with edge-billow extent; stem/skirt/water column between eye and cap count to center
float probeMush(sampler2D data, vec3 center, float radius) {
    float len = length(center), through = 1.0;
    vec3 dir = center / len;
    for (int k = 0; k < MUSHROOMS; k++) {
        if (!mushOn(data, k)) continue;
        Mush m = mushLoad(data, k);
        vec3 c = mushLocal(m, center) - vec3(0.0, m.capY, 0.0);
        float capEnd = len;
        if (dot(c, c) < 0.25 * m.capR * m.capR) capEnd = len - radius - MUSH_CAP_REACH * mushCapUnit(m);
        vec2 span = mushSpan(m, dir);
        span.y = min(span.y, len);
        if (span.y <= span.x) continue;
        float ds = (span.y - span.x) / 8.0, tau = 0.0;
        for (int i = 0; i < 8; i++) {
            float t = span.x + (float(i) + 0.5) * ds;
            vec4 s = mushMean(m, mushLocal(m, dir * t));
            tau += (step(t, capEnd) * s.x + s.y + s.z + s.w) * ds;
        }
        through *= 1.0 - pallAir(PallAir, 0.5 * (span.x + span.y), dir.y) * (1.0 - exp(-tau));
    }
    return through;
}

// probe-circle radius share (like block rays, FarBlasts.RING); center/sides/top: ground below source itself hides
// disk bottom
const float RING = 0.7;
const int TAPS = 6;
const vec2 TAP[TAPS] = vec2[TAPS](vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(0.7071, 0.7071), vec2(0.0, 1.0), vec2(-0.7071, 0.7071),
        vec2(-1.0, 0.0));
// front half/softness in radii like layer body (FarSprites.BALL_FRONT, BODY_SOFT), at least
// half a block: projectile plume radius 0.2–0.6 gives fractions of a block, finer than distant/DH LOD depth, causing glare flicker
const float FRONT = 0.5, SOFT = 0.25, MIN_BLOCKS = 0.5;

void main() {
    vec4 view = SourceView * vec4(center, 1.0);
    // distance along view axis, as in SceneDepth
    float d = -view.z, seen = 1.0;
    if (d > 0.0) {
        vec4 clip = SourceProj * view;
        vec2 size = vec2(textureSize(Sampler0, 0));
        vec2 at = (clip.xy / clip.w * 0.5 + 0.5) * size;
        float ring = RING * radius / (length(view.xyz) * Pixel);
        float front = d - max(FRONT * radius, MIN_BLOCKS), soft = max(SOFT * radius, MIN_BLOCKS), sum = 0.0;
        int n = 0;
        for (int k = 0; k < TAPS; k++) {
            vec2 p = at + TAP[k] * ring;
            if (any(lessThan(p, vec2(0.0))) || any(greaterThanEqual(p, size))) continue;
            sum += clamp((texelFetch(Sampler0, ivec2(p), 0).r - front) / soft, 0.0, 1.0);
            // not n++: Veil (bundled by Sable) rebuilds shaders through glsl-processor 0.2.3, which drops the semicolon after n++
            n += 1;
        }
        if (n > 0) seen = sum / float(n);
    }
    // pall to source front, mushrooms to center excluding own cap (no pall/mushrooms in frame:
    // PallAmbient.w = 0)
    float len = length(center);
    if (PallAmbient.w > 0.5 && len > radius) seen *= pallThrough(center / len, len - radius) * probeMush(Sampler11, center, radius)
            * smokeThrough(center / len, len - radius);
    fragColor = vec4(seen, 0.0, 0.0, 1.0);
}
