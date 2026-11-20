// See-through surfaces in front of the world (client/fx/layer/SeeThrough, SceneDepth): where a translucent surface lies in
// front of the opaque world, the frame is X + T·world — the surface's own light X (premultiplied, its colour times its
// coverage) and the share T of what lies behind that it lets through. FxGlass holds X (rgb) and T (a); (0, 0, 0, 1) — no
// surface, or one the layer must not look through (hidden: the hardware depth already stops there). FxSurface — distance
// along the view axis to that surface (to the world where there is none). FxGlass is 16-bit: its alpha carries T and the
// share of the ray to the world that lies in front of the surface, 8 bits each (seeGlass, seeFront).

// FxGlass texel → X (rgb), T (a)
vec4 seeGlass(vec4 raw) {
    float v = floor(raw.a * 65535.0 + 0.5);
    return vec4(raw.rgb, floor(v / 256.0) / 255.0);
}

// FxGlass texel → share of the ray to the world in front of the surface (surface distance / world distance)
float seeFront(vec4 raw) {
    float v = floor(raw.a * 65535.0 + 0.5);
    return mod(v, 256.0) / 255.0;
}

// FxGlass alpha for transmittance t and front share f
float seePack(float t, float f) {
    return (floor(clamp(t, 0.0, 1.0) * 255.0 + 0.5) * 256.0 + floor(clamp(f, 0.0, 1.0) * 255.0 + 0.5)) / 65535.0;
}

// A premultiplied layer fragment (light, opacity) behind the surface, blended ONE, ONE_MINUS_SRC_ALPHA (or ONE, SRC_ALPHA
// with alpha = 1 − opacity): the light it must carry so that the frame becomes X + T·(the fragment over the world). With the
// frame V = X + T·W: X + T·(light + (1 − opacity)·W) = T·light + opacity·X + (1 − opacity)·V. Exact for any number of
// fragments behind and in front, in sorted order (SeeThroughGlslTest).
float seeLight(float light, float opacity, float glassLight, float through) {
    return through * light + opacity * glassLight;
}

vec3 seeThrough(vec3 light, float opacity, vec4 glass) {
    return vec3(seeLight(light.r, opacity, glass.r, glass.a), seeLight(light.g, opacity, glass.g, glass.a),
            seeLight(light.b, opacity, glass.b, glass.a));
}

// A point this far along the view axis lies behind the surface at distance surface: a hair beyond it (depth noise of
// the same plane in two depth copies, a fragment touching the glass)
bool seeBehind(float distance, float surface) {
    return distance > surface * 1.0005 + 0.01;
}

// A medium along the whole ray to the world (the pall: light, transmittance) with a see-through surface at share f of the
// ray: the part in front of the surface reaches the eye as it is, the part behind through the surface. The medium is
// taken uniform along the ray (transmittance to the surface tau^f, light in proportion to its extinction): a window a few
// blocks away puts nearly all of the dust behind it, a glass facade with its room behind puts the dust in front of it.
// Laid with ONE, SRC_ALPHA over the frame X + T·W. Exact for the uniform medium (SeeThroughGlslTest).
float seeMediumLight(float light, float tau, float lightFront, float tauFront, float glassLight, float through) {
    return through * light + (1.0 - through) * lightFront + (tauFront - tau) * glassLight;
}
