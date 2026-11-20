#version 150

// The shader pack's sky by direction (client/fx/layer/SkyBand, SkyBandPass): one pixel per bin of AZ azimuth × EL
// elevation bins over the upper hemisphere. BAND_SAMPLES² directions per bin, jittered per frame; each that lands on
// screen on a sky pixel (world distance = sky, SceneDepth: 1e9) whose neighbours BAND_EDGE pixels away are sky too (a
// pack's TAA and blur carry a silhouette's colour a few pixels into the sky beside it), outside the cones around the
// sun and the moon (skyBandTakes, the pack's sun path), adds the frame's colour there. Output: mean colour, share of the
// samples taken.
uniform sampler2D Sampler0;
// world distance behind each pixel (SceneDepth)
uniform sampler2D Sampler1;
uniform mat4 BandViewProj;
// sun direction (unit, eye-relative world axes), cosine of the cone left out around it and around the moon
uniform vec4 BandSun;
// per-frame offset of the samples inside the bin, 0..1
uniform vec2 BandJitter;

out vec4 fragColor;

const int BAND_AZ = 16, BAND_EL = 6, BAND_SAMPLES = 8, BAND_EDGE = 4;

bool skyAt(ivec2 p, ivec2 size) {
    return texelFetch(Sampler1, clamp(p, ivec2(0), size - 1), 0).r >= 1e8;
}

// Copy — SkyBand.takes
bool skyBandTakes(vec3 dir, vec3 sun, float cosOut) {
    return abs(dot(dir, sun)) < cosOut;
}

// Copy — SkyBand.direction
vec3 bandDir(ivec2 bin, vec2 at) {
    float az = (float(bin.x) + at.x) / float(BAND_AZ) * 6.28318530718, el = (float(bin.y) + at.y) / float(BAND_EL) * 1.57079632679;
    return vec3(cos(el) * cos(az), sin(el), cos(el) * sin(az));
}

void main() {
    ivec2 bin = ivec2(gl_FragCoord.xy);
    ivec2 frame = textureSize(Sampler0, 0), depth = textureSize(Sampler1, 0);
    vec3 sum = vec3(0.0);
    float n = 0.0;
    for (int i = 0; i < BAND_SAMPLES; i++) {
        for (int j = 0; j < BAND_SAMPLES; j++) {
            vec3 dir = bandDir(bin, (vec2(float(i), float(j)) + BandJitter) / float(BAND_SAMPLES));
            if (!skyBandTakes(dir, BandSun.xyz, BandSun.w)) continue;
            // a direction is a point at infinity: w = 0
            vec4 clip = BandViewProj * vec4(dir, 0.0);
            if (clip.w <= 0.0) continue;
            vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
            if (uv.x < 0.0 || uv.y < 0.0 || uv.x >= 1.0 || uv.y >= 1.0) continue;
            ivec2 at = ivec2(uv * vec2(depth));
            if (!skyAt(at, depth) || !skyAt(at + ivec2(BAND_EDGE, 0), depth) || !skyAt(at - ivec2(BAND_EDGE, 0), depth)
                    || !skyAt(at + ivec2(0, BAND_EDGE), depth) || !skyAt(at - ivec2(0, BAND_EDGE), depth)) continue;
            sum += texelFetch(Sampler0, ivec2(uv * vec2(frame)), 0).rgb;
            n += 1.0;
        }
    }
    fragColor = vec4(n > 0.0 ? sum / n : vec3(0.0), n / float(BAND_SAMPLES * BAND_SAMPLES));
}
