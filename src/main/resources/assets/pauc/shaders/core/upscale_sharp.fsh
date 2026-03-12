#version 150

uniform sampler2D DiffuseSampler;
uniform vec4 ColorModulator;
uniform vec2 SourceSize;
uniform float RcasStrength;

in vec2 texCoord;
in vec4 vertexColor;

out vec4 fragColor;

vec3 safe_div(vec3 numerator, vec3 denominator) {
    return numerator / max(denominator, vec3(1.0e-5));
}

void main() {
    vec2 texel = vec2(1.0) / SourceSize;
    vec3 b = texture(DiffuseSampler, texCoord + vec2(0.0, -texel.y)).rgb;
    vec3 d = texture(DiffuseSampler, texCoord + vec2(-texel.x, 0.0)).rgb;
    vec4 e_sample = texture(DiffuseSampler, texCoord);
    vec3 e = e_sample.rgb;
    vec3 f = texture(DiffuseSampler, texCoord + vec2(texel.x, 0.0)).rgb;
    vec3 h = texture(DiffuseSampler, texCoord + vec2(0.0, texel.y)).rgb;

    vec3 min_ring = min(min(b, d), min(f, h));
    vec3 max_ring = max(max(b, d), max(f, h));
    vec3 min_luma = min(min_ring, e);
    vec3 max_luma = max(max_ring, e);
    vec3 amplitude = clamp(min(min_luma, 1.0 - max_luma) * safe_div(vec3(1.0), max_luma), 0.0, 1.0);

    float sharpness = clamp(RcasStrength, 0.0, 1.0);
    vec3 weight = -amplitude * (0.20 + sharpness * 0.60);
    vec3 sharpened = ((b + d + f + h) * weight + e) / (1.0 + 4.0 * weight);
    sharpened = clamp(sharpened, 0.0, 1.0);
    fragColor = vec4(sharpened, e_sample.a) * vertexColor * ColorModulator;
}
