#version 330 core
#define SHADER_FEATURE_COMPUTE 1
#define SHADER_FEATURE_TESSELLATION 1
#define SHADER_FEATURE_VERTEX_ATTRIBUTE64 1
#define SHADER_FEATURE_BINDLESS_TEXTURE 1
#define SHADER_FEATURE_CUBE_MAP_ARRAY 1
#define SHADER_FEATURE_FLOAT64 1
#define SHADER_FEATURE_SHADER_STORAGE 1
#define SHADER_FEATURE_INT64 1
#define SHADER_FEATURE_ATOMIC_COUNTER 1
uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
in vec2 texCoord0;
in vec4 vertexColor;
out vec4 fragColor;
void main() {
	vec4 color = (texture(Sampler0, texCoord0) * vertexColor);
	if(color.a < 0.1) {
		discard;
	}
	fragColor = (color * ColorModulator);
}
