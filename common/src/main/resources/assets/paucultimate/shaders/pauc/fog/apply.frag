#version 330 core

in vec2 TexCoord;
out vec4 fragColor;

uniform sampler2D uFogMap;
uniform sampler2D uColorMap;

void main() {
    vec4 color = texture(uColorMap, TexCoord);
    if (color.a <= 0.0) { discard; }
    vec4 fog = texture(uFogMap, TexCoord);
    fragColor = vec4(mix(color.rgb, fog.rgb, fog.a), color.a);
}
