package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.shaders.ProgramManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;
import ua.zentix.incoming.Incoming;

import java.nio.charset.StandardCharsets;

/** One layered draw instead of rebinding the framebuffer for every occupied height slice (OpenGL 3.2). */
final class SmokeLayers {
    private static int program, parent = -1;
    private static ShaderInstance source;

    private SmokeLayers() {}

    static int program(ShaderInstance shader) {
        if (source == shader && parent == shader.getId()) return program;
        if (program != 0) GL20.glDeleteProgram(program);
        program = 0; source = shader; parent = shader.getId();
        int vertex = 0, geometry = 0, fragment = 0, candidate = 0;
        try {
            vertex = compile("program/smoke_layer.vsh", GL20.GL_VERTEX_SHADER);
            geometry = compile("program/smoke_layer.gsh", GL32.GL_GEOMETRY_SHADER);
            candidate = GL20.glCreateProgram();
            GL20.glAttachShader(candidate, vertex);
            GL20.glAttachShader(candidate, geometry);
            // Veil may detach the compiled stages after linking. Read the same resource as ShaderInstance;
            // a vertex/geometry-only GL program can link successfully yet write no colour at all.
            fragment = compile("core/fx_smoke_fill.fsh", GL20.GL_FRAGMENT_SHADER);
            GL20.glAttachShader(candidate, fragment);
            GL20.glBindAttribLocation(candidate, 0, "Position");
            org.lwjgl.opengl.GL30.glBindFragDataLocation(candidate, 0, "fragColor");
            GL20.glLinkProgram(candidate);
            if (GL20.glGetProgrami(candidate, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
                throw new IllegalStateException(GL20.glGetProgramInfoLog(candidate));
            program = candidate;
            candidate = 0;
        } catch (Exception ex) {
            Incoming.LOG.warn("Layered smoke upload unavailable; using slice draws", ex);
        } finally {
            if (candidate != 0) GL20.glDeleteProgram(candidate);
            if (vertex != 0) GL20.glDeleteShader(vertex);
            if (geometry != 0) GL20.glDeleteShader(geometry);
            if (fragment != 0) GL20.glDeleteShader(fragment);
        }
        return program;
    }

    private static int compile(String file, int kind) throws Exception {
        String source;
        try (var in = Minecraft.getInstance().getResourceManager().open(Incoming.id("shaders/" + file))) {
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int shader = GL20.glCreateShader(kind);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String error = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException(error);
        }
        return shader;
    }

    static void bind(int id, org.joml.Matrix4f corner) {
        ProgramManager.glUseProgram(id);
        GL20.glUniform1i(GL20.glGetUniformLocation(id, "Sampler0"), 0);
        GL20.glUniform1i(GL20.glGetUniformLocation(id, "Sampler1"), 1);
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(id, "SmokeCorner"), false, corner.get(stack.mallocFloat(16)));
        }
    }

}
