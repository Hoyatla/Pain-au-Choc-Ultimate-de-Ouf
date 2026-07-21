#version 330 core

in vec2 TexCoord;
out vec4 fragColor;

uniform mat4 uDhInvMvpProj;
uniform sampler2D uDhDepthTexture;
uniform sampler2D uMcColorTexture;
uniform sampler2D uDhColorTexture;
uniform float uStartFadeBlockDistance;
uniform float uEndFadeBlockDistance;

vec3 calcViewPosition(float fragmentDepth, mat4 invMvpProj) {
    vec4 ndc = vec4(TexCoord.xy, fragmentDepth, 1.0);
    ndc.xyz = ndc.xyz * 2.0 - 1.0;
    vec4 eyeCoord = invMvpProj * ndc;
    return eyeCoord.xyz / eyeCoord.w;
}

void main() {
    vec4 combinedMcDhColor = texture(uMcColorTexture, TexCoord);
    vec4 dhColor = texture(uDhColorTexture, TexCoord);

    if (dhColor.a == 0.0) dhColor = combinedMcDhColor;

    float dhFragmentDepth = texture(uDhDepthTexture, TexCoord).r;
    vec3 dhVertexWorldPos = calcViewPosition(dhFragmentDepth, uDhInvMvpProj);
    float dhFragmentDistance = length(dhVertexWorldPos.xzy);

    float startFade = uEndFadeBlockDistance;
    float endFade = uStartFadeBlockDistance;
    float fadeStep = smoothstep(startFade, endFade, dhFragmentDistance);
    fragColor = mix(combinedMcDhColor, dhColor, fadeStep);
    fragColor.a = 1.0;
}
