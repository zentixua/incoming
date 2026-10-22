// Мир кадра за пикселем, для проходов, что светят на мир (fx_pall_composite — свет огненных шаров ядерки, fx_heat — свет
// вспышки и огня обычного оружия): место, нормаль по соседям, затенение грани Minecraft, канал пикселя под светом.
// Карта расстояния до мира (SceneDepth), обратная матрица вида и проекции, взгляд и затенение граней — параметрами; нужен
// pall.glsl (pallScene, pallLinear) — до этого файла.
// Место мира за пикселем at (от глаза): Sampler1 — расстояние вдоль взгляда
vec3 sceneAt(sampler2D depth, mat4 invViewProj, vec3 forward, ivec2 at, ivec2 size) {
    ivec2 c = clamp(at, ivec2(0), size - 1);
    vec2 ndc = (vec2(c) + 0.5) / vec2(size) * 2.0 - 1.0;
    vec4 farPoint = invViewProj * vec4(ndc, 1.0, 1.0);
    vec3 dir = normalize(farPoint.xyz / farPoint.w);
    return dir * (pallScene(texelFetch(depth, c, 0).r) / max(dot(dir, forward), 1e-3));
}

// Нормаль поверхности мира в p (пиксель at) по соседним пикселям, к глазу: на каждой оси экрана — сосед, что ближе по
// месту (на краю предмета другой — фон за ним или другой предмет); у края экрана — внутренний (внешнего нет: sceneAt
// взял бы сам пиксель, разность 0 — и нормаль 0)
vec3 sceneNormal(sampler2D depth, mat4 invViewProj, vec3 forward, ivec2 at, ivec2 size, vec3 p) {
    vec3 r = sceneAt(depth, invViewProj, forward, at + ivec2(1, 0), size) - p, l = p - sceneAt(depth, invViewProj, forward, at - ivec2(1, 0), size);
    vec3 u = sceneAt(depth, invViewProj, forward, at + ivec2(0, 1), size) - p, d = p - sceneAt(depth, invViewProj, forward, at - ivec2(0, 1), size);
    if (at.x + 1 >= size.x) r = l;
    if (at.x <= 0) l = r;
    if (at.y + 1 >= size.y) u = d;
    if (at.y <= 0) d = u;
    vec3 n = cross(mix(r, l, step(dot(l, l), dot(r, r))), mix(u, d, step(dot(d, d), dot(u, u))));
    n *= inversesqrt(max(dot(n, n), 1e-20));
    return mix(n, -n, step(0.0, dot(n, p)));
}

// Затенение грани Minecraft по оси, ближайшей к нормали n (ClientLevel.getShade): оно уже в пикселе мира, а свет шара по
// грани — по косинусу к ней; в цвете блока его быть не должно
float faceShade(vec4 shade, vec3 n) {
    vec3 a = abs(n);
    if (a.y >= a.x && a.y >= a.z) return n.y > 0.0 ? shade.x : shade.y;
    return a.z >= a.x ? shade.z : shade.w;
}

// Один канал пикселя мира под светом шаров (MushroomField.relit): мир на экране world, доля его прежнего света на экране
// kept (тень), свет открытого неба на белом блоке на экране open (с затенением грани), свет шаров на белой стене added
// (линейный). Цвет блока — пиксель, делённый на свет открытого неба (в пещере и доме свет Minecraft слабее — и свет шара на нём меньше: туда он и
// не доходит), не белее белого. Линейный итог
float relit(float world, float kept, float open, float added) {
    float lin = pallLinear(world);
    return lin * pallLinear(kept) + min(lin / max(pallLinear(open), 1e-4), 1.0) * added;
}
