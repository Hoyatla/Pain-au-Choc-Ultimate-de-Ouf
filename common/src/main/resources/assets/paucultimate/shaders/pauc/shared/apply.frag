#version 330 core

in vec2 TexCoord;
out vec4 fragColor;

uniform sampler2D uColorMap;
uniform sampler2D uDepthMap;
uniform sampler2D uMcDepthMap;

void main() {
    float depth = texture(uDepthMap, TexCoord).r;
    if (depth >= 1.0) { discard; }
    float mcDepth = texture(uMcDepthMap, TexCoord).r;
    if (mcDepth < depth) { discard; }
    fragColor = texture(uColorMap, TexCoord);
}
