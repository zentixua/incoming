#version 150
uniform sampler2D DustSources;
uniform mat4 SmokeCorner;
#moj_import <incoming:dust_profile.glsl>
out vec4 fragColor;
void main() {
    int row = int(gl_FragCoord.y), i = row / 3, axis = row % 3;
    vec4 b0 = texelFetch(DustSources, ivec2(i,0),0), b1 = texelFetch(DustSources, ivec2(i,1),0);
    vec2 phase = texelFetch(DustSources, ivec2(i,3),0).xy;
    float cell = SmokeCorner[0].w, sigma = b0.w;
    if (axis == 1) { cell = SmokeCorner[3].y; sigma = b1.x; }
    float from = (SmokeCorner[0][axis] + floor(gl_FragCoord.x)*cell - b0[axis]) / sigma;
    float to = from + cell / sigma;
    float p0 = phase.x + float(axis)*1.7, p1 = phase.y + 2.1;
    if (axis == 1) p1 = phase.y;
    if (axis == 2) p1 = phase.y + 4.2;
    float f0 = axis == 2 ? 2.0 : 1.0;
    float f1 = axis == 2 ? 1.0 : 2.0;
    fragColor = vec4(dustIntegral(from,to,p0,f0)/cell, dustIntegral(from,to,p1,f1)/cell, 0.0, 0.0);
}
