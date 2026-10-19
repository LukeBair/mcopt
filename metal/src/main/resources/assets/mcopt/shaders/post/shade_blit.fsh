#version 330
#extension GL_ARB_separate_shader_objects : require

// Copies mcopt shade's result back onto the main target.
uniform sampler2D ColorSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texture(ColorSampler, texCoord);
}
