#version 150

// Source visibility probes (client/fx/layer/SourceProbes): quad exactly one probe pixel.
// source center relative to eye
in vec3 Position;
// x: source radius, blocks; y: probe index (from 0)
in vec2 UV0;
// quad corner: ±1, ±1
in vec3 Normal;

// probe texture pixels: per row, rows
uniform vec2 ProbeSize;

out vec3 center;
out float radius;

void main() {
    int i = int(UV0.y + 0.5), w = int(ProbeSize.x);
    vec2 cell = vec2(float(i % w), float(i / w)) + 0.5 + 0.5 * Normal.xy;
    gl_Position = vec4(cell / ProbeSize * 2.0 - 1.0, 0.0, 1.0);
    center = Position;
    radius = UV0.x;
}
