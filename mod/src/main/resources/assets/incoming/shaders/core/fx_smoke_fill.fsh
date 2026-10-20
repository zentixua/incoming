#version 150

// Сетка дыма (client/fx/smoke/SmokeGrid, client/fx/layer/SmokePass): клетка слоя текстуры — вклад одного гауссова клуба,
// сложением. Слой — ширина 2N (страница 0 слева, страница 1 справа), высота N (z мира), номер слоя — каскад·N + высота.
// Страница 0: ослабление сажи, пыли и капель (1/м) и Σ ослабление × ΔT (кК); страница 1: Σ ослабление пыли × её альбедо
// (r, g, b) и Σ ослабление × подъём (м/с). Клуб уже в своём каскаде со своими σ (смазан на полклетки, SmokeGrid.put).

// таблица клубов (SmokeGrid.BLOB_ROWS строк по клубу): 0 — центр от глаза, σh; 1 — σv, пиковые ослабления сажи, пыли,
// капель; 2 — альбедо пыли; 3 — ΔT (кК), подъём
uniform sampler2D Sampler0;
// каскады: столбец c — угол от глаза и клетка
uniform mat4 SmokeCorner;
// x — каскад, y — слой по высоте в нём
uniform vec4 SmokeSlice;

in float blob;

out vec4 fragColor;

const float SMOKE_N = 64.0;

void main() {
    int i = int(floor(blob + 0.5));
    vec4 b0 = texelFetch(Sampler0, ivec2(i, 0), 0), b1 = texelFetch(Sampler0, ivec2(i, 1), 0);
    vec4 b2 = texelFetch(Sampler0, ivec2(i, 2), 0), b3 = texelFetch(Sampler0, ivec2(i, 3), 0);
    float px = floor(gl_FragCoord.x);
    float page = step(SMOKE_N, px);
    vec4 C = SmokeCorner[int(SmokeSlice.x + 0.5)];
    vec3 cell = vec3(px - page * SMOKE_N, SmokeSlice.y, floor(gl_FragCoord.y)) + 0.5;
    vec3 p = C.xyz + cell * C.w;
    vec3 d = p - b0.xyz;
    float e = exp(-0.5 * (dot(d.xz, d.xz) / (b0.w * b0.w) + d.y * d.y / (b1.x * b1.x)));
    vec3 ext = b1.yzw * e;
    float total = ext.x + ext.y + ext.z;
    fragColor = page < 0.5 ? vec4(ext, total * b3.x) : vec4(ext.y * b2.rgb, total * b3.y);
}
