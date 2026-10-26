// World behind frame pixel for world-lighting passes (fx_pall_composite — nuclear ball light; fx_heat — conventional
// flash/fire light): position, neighbor-derived normal, Minecraft face shade, lit pixel channel. World distance map
// (SceneDepth), inverse view/projection, gaze and face shade passed as parameters; requires pall.glsl (pallScene,
// pallLinear) before this file.
// World position behind pixel at (eye-relative): Sampler1 is distance along view
vec3 sceneAt(sampler2D depth, mat4 invViewProj, vec3 forward, ivec2 at, ivec2 size) {
    ivec2 c = clamp(at, ivec2(0), size - 1);
    vec2 ndc = (vec2(c) + 0.5) / vec2(size) * 2.0 - 1.0;
    vec4 farPoint = invViewProj * vec4(ndc, 1.0, 1.0);
    vec3 dir = normalize(farPoint.xyz / farPoint.w);
    return dir * (pallScene(texelFetch(depth, c, 0).r) / max(dot(dir, forward), 1e-3));
}

// Eye-facing world surface normal at p (pixel at) from neighboring pixels: per screen axis choose nearest position
// (at object edge the other is background or another object); at screen edge choose inward (no outward neighbor:
// sceneAt would return same pixel, zero difference and zero normal)
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

// Minecraft face shade for axis nearest normal n (ClientLevel.getShade): already in world pixel, while ball face light
// follows its incidence cosine; exclude shade from block color
float faceShade(vec4 shade, vec3 n) {
    vec3 a = abs(n);
    if (a.y >= a.x && a.y >= a.z) return n.y > 0.0 ? shade.x : shade.y;
    return a.z >= a.x ? shade.z : shade.w;
}

// One world pixel channel under ball light (MushroomField.relit): display world, remaining display-light share kept
// (shadow), open-sky light on white block open (display, face-shaded), ball light on white wall added (linear).
// Block color is pixel / open-sky light (weaker Minecraft light indoors/in caves also reduces ball light, which cannot
// reach there), capped at white. Linear result
float relit(float world, float kept, float open, float added) {
    float lin = pallLinear(world);
    return lin * pallLinear(kept) + min(lin / max(pallLinear(open), 1e-4), 1.0) * added;
}
