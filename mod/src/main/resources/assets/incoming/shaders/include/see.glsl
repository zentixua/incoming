// See-through surfaces in front of the world (client/fx/layer/SeeThrough, SceneDepth): where a translucent surface lies in
// front of the opaque world, the frame is X + T·world — the surface's own light X (premultiplied, its colour times its
// coverage) and the share T of what lies behind that it lets through. FxGlass holds X (rgb) and T (a); (0, 0, 0, 1) — no
// surface, or one the layer must not look through (hidden: the hardware depth already stops there). FxSurface — distance
// along the view axis to that surface (to the world where there is none).

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

// A medium along the ray to the world (the pall: light, transmittance tau as laid) with a see-through surface on the
// ray, and the part of it in front of the surface (lightFront, tauFront; fx_pall_composite: mediumTo): the part in front
// reaches the eye as it is, the rest through the surface. Laid with ONE, SRC_ALPHA over the frame X + T·W. Exact for any
// split (SeeThroughGlslTest).
float seeMediumLight(float light, float tau, float lightFront, float tauFront, float glassLight, float through) {
    return through * light + (1.0 - through) * lightFront + (tauFront - tau) * glassLight;
}
