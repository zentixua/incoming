#version 150

#moj_import <light.glsl>

// Distant projectile models into atlas tiles (client/render/FarModels). Part mesh stays in GPU memory as is:
// ModelViewMat puts it in model-view axes, ProjMat projects from eye into tile, NormalMat rotates part normals into
// world axes. Otherwise vanilla rendertype_entity_cutout_no_cull without fog or damage flash: two directional lights
// using world-space normals and skylight from the lightmap; ColorModulator is blurred propeller-disk transparency.
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 NormalMat;
uniform vec4 ColorModulator;
uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

out vec4 vertexColor;
out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vec3 normal = normalize(NormalMat * Normal);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, normal, Color) * texelFetch(Sampler2, UV2 / 16, 0) * ColorModulator;
    texCoord0 = UV0;
}
