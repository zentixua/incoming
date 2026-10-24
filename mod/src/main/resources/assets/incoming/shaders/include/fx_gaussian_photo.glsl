// A finite photograph carries detail, not the boundary of the physical medium.
// Its light joins the neutral surrounding medium as either the aperture or the baked material ends.
vec3 gaussianPhotoLight(vec3 light, float mean, float aperture, float bakedAlpha) {
    return mix(vec3(mean), light, aperture * smoothstep(0.002, 0.02, bakedAlpha));
}
