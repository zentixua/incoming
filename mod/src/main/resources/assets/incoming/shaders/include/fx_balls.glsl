// Frame fireballs (FxLights), shared by fx.vsh and fx.fsh: billow light computed per vertex; for a horizontal billow
// (ground dust layer), per pixel: on a quad tens of meters across, corner light interpolated to center made a dark disk
// directly beneath the ball (F2a frames, Grad at night, 0.1 s).

// fireballs (FxLights), one column per ball: camera-relative center and radius (0 — absent); surface illuminance
// relative to light around eye, with color, and on-screen fire (w)
uniform mat4 FxBalls;
uniform mat4 FxBallLight;
// on-screen skylight for exposed material (FarView.sky, its luminance): billow vertex color already multiplied by it
uniform float FxAmbient;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
// no ball illumination beyond this many radii (smooth fade from halfway): no shadows, so at night distant ball light
// would also hit smoke behind hills
const float BALL_REACH = 16.0;

// fireball light at p relative to skylight (already multiplied into billow vertex color: FarView.sky on screen, power
// 2.2 in linear), direction averaged by light; no brighter than on-screen fire, as balls: cap in the same units.
// Night is already in eye adaptation (FxBallLight, Sight.exposure): without dividing by skylight, fire on smoke at night
// was 3–14 times weaker
vec3 ballsToward(vec3 p, out vec3 dir) {
    float sky = max(FxAmbient, 1e-3), skyLinear = pow(sky, 2.2);
    vec3 sum = vec3(0.0);
    vec3 d = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        float r = FxBalls[i].w;
        if (r <= 0.0) continue;
        vec3 v = FxBalls[i].xyz - p;
        float d2 = max(dot(v, v), r * r), reach = BALL_REACH * r * BALL_REACH * r;
        // ball illuminance (linear fraction of white) relative to linear skylight: ball-lit billow versus sky-lit billow
        vec3 add = FxBallLight[i].rgb * (r * r / d2) * (1.0 - smoothstep(0.25 * reach, reach, d2)) / skyLinear;
        // on-screen ball light is vertex color (× skylight) × power 1/2.2 of linear light: fire cap w (on screen) uses the same units
        add = add / (1.0 + dot(add, LUMA) / pow(max(FxBallLight[i].w, 1e-4) / sky, 2.2));
        sum += add;
        d += dot(add, LUMA) * v / sqrt(max(dot(v, v), 1e-6));
    }
    dir = d;
    return sum;
}

