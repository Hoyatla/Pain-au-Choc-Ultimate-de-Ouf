#version 330 core

in vec2 TexCoord;
out vec4 fragColor;

uniform sampler2D uColorMap;

void main() {
    fragColor = texture(uColorMap, TexCoord);
}
