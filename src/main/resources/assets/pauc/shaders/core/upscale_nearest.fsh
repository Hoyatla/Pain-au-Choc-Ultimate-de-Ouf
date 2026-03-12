#version 150

uniform sampler2D DiffuseSampler;
uniform vec4 ColorModulator;
uniform vec2 SourceSize;

in vec2 texCoord;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec2 texel = vec2(1.0) / SourceSize;
    vec2 snappedUv = (floor(texCoord * SourceSize) + vec2(0.5)) * texel;
    vec4 color = texture(DiffuseSampler, snappedUv) * vertexColor;
    fragColor = color * ColorModulator;
}
