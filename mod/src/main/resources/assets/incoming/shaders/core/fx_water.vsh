#version 150

// Water decal (client/fx/layer/WaterDecal): full-frame quad.
in vec3 Position;

void main() {
    gl_Position = vec4(Position.xy, 0.0, 1.0);
}
