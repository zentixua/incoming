#version 150

// World distance behind a pixel (client/fx/layer/SceneDepth): fullscreen quad.
in vec3 Position;

void main() {
    gl_Position = vec4(Position.xy, 0.0, 1.0);
}
