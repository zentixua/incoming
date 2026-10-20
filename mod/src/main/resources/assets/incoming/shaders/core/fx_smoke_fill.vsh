#version 150

// Сетка дыма (client/fx/layer/SmokePass): прямоугольник клеток одного клуба в слое текстуры — x, y в NDC слоя, z —
// номер клуба в таблице.
in vec3 Position;

out float blob;

void main() {
    gl_Position = vec4(Position.xy, 0.0, 1.0);
    blob = Position.z;
}
