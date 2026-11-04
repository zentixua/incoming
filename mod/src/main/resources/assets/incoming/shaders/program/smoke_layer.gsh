#version 150
layout(triangles) in;
layout(triangle_strip, max_vertices=3) out;
in float encodedBlob[];
out float blob;
void main() {
    for (int i=0; i<3; i++) {
        gl_Layer = int(encodedBlob[i] + 0.5) / 512;
        blob = encodedBlob[i];
        gl_Position = gl_in[i].gl_Position;
        EmitVertex();
    }
    EndPrimitive();
}
