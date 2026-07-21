#version 330 core

in vec2 TexCoord;
out vec4 fragColor;

uniform mat4 uDhInvMvpProj;
uniform mat4 uMcInvMvpProj;
uniform sampler2D uMcDepthTexture;
uniform sampler2D uDhDepthTexture;
uniform sampler2D uCombinedMcDhColorTexture;
uniform sampler2D uDhColorTexture;
uniform float uStartFadeBlockDistance;
uniform float uEndFadeBlockDistance;
uniform float uMaxLevelHeight;
uniform bool uOnlyRenderLods;

vec3 calcViewPosition(float fragmentDepth, mat4 invMvpProj) {
    vec4 ndc = vec4(TexCoord.xy, fragmentDepth, 1.0);
    ndc.xyz = ndc.xyz * 2.0 - 1.0;
    vec4 eyeCoord = invMvpProj * ndc;
    return eyeCoord.xyz / eyeCoord.w;
}

void main() {
    vec4 combinedMcDhColor = texture(uCombinedMcDhColorTexture, TexCoord);
    vec4 dhColor = texture(uDhColorTexture, TexCoord);

    if (uOnlyRenderLods) { fragColor = dhColor; return; }

    if (dhColor.a == 0.0) dhColor = combinedMcDhColor;

    float mcFragmentDepth = texture(uMcDepthTexture, TexCoord).r;
    float dhFragmentDepth = texture(uDhDepthTexture, TexCoord).r;
    vec3 dhVertexWorldPos = calcViewPosition(dhFragmentDepth, uDhInvMvpProj);

    if (dhVertexWorldPos.y > uMaxLevelHeight) {
        fragColor = vec4(combinedMcDhColor.rgb, 0.0);
    } else if (mcFragmentDepth < 1.0) {
        vec3 mcVertexWorldPos = calcViewPosition(mcFragmentDepth, uMcInvMvpProj);
        float mcFragmentDistance = length(mcVertexWorldPos.xzy);
        float fadeStep = smoothstep(uStartFadeBlockDistance, uEndFadeBlockDistance, mcFragmentDistance);
        fragColor = mix(combinedMcDhColor, dhColor, fadeStep);
        fragColor.a = 1.0;
    } else {
        fragColor = vec4(combinedMcDhColor.rgb, 0.0);
    }
}
