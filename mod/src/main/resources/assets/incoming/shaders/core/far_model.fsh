#version 150

// Distant projectile models into atlas tiles (client/render/FarModels): cutout as vanilla entities; premultiplied alpha
// in atlas so mip levels average coverage and light without a dark fringe along the silhouette.
uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    if (color.a < 0.1) {
        discard;
    }
    color *= vertexColor;
    fragColor = vec4(color.rgb * color.a, color.a);
}
