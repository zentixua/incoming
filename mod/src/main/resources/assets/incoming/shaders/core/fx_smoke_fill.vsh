#version 150

// Smoke grid (client/fx/layer/SmokePass): rectangle of one billow's cells in a texture layer — x, y in layer NDC,
// z is billow table index.
in vec3 Position;

out float blob;

void main() {
    gl_Position = vec4(Position.xy, 0.0, 1.0);
    blob = Position.z;
}
