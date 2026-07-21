#version 330 core

in vec2 TexCoord;
out vec4 fragColor;

uniform sampler2D uDepthMap;
uniform mat4 uInvMvpProj;

uniform vec4 uFogColor;
uniform float uFogScale;
uniform float uFogVerticalScale;

// Far fog
uniform float uFarFogStart;
uniform float uFarFogLength;
uniform float uFarFogMin;
uniform float uFarFogRange;
uniform float uFarFogDensity;
uniform int uFarFogFalloffType; // 0=LINEAR, 1=EXP, 2=EXP2

// Height fog
uniform bool uHeightFogEnabled;
uniform float uHeightFogStart;
uniform float uHeightFogLength;
uniform float uHeightFogMin;
uniform float uHeightFogRange;
uniform float uHeightFogDensity;
uniform int uHeightFogFalloffType;
uniform bool uHeightBasedOnCamera;
uniform float uHeightFogBaseHeight;
uniform bool uHeightFogAppliesUp;
uniform bool uHeightFogAppliesDown;
uniform int uHeightFogMixingMode;
uniform float uCameraBlockYPos;
uniform bool uUseSphericalFog;

vec3 calcViewPosition(float fragmentDepth) {
    vec4 ndc = vec4(TexCoord.xy, fragmentDepth, 1.0);
    ndc.xyz = ndc.xyz * 2.0 - 1.0;
    vec4 eyeCoord = uInvMvpProj * ndc;
    return eyeCoord.xyz / eyeCoord.w;
}

float linearFog(float dist, float start, float len, float min, float range) {
    float t = clamp((dist - start) / len, 0.0, 1.0);
    return min + range * t;
}

float expFog(float x, float start, float len, float min, float range, float density) {
    x = max((x - start) / len, 0.0) * density;
    return min + range - range / exp(x);
}

float exp2Fog(float x, float start, float len, float min, float range, float density) {
    x = max((x - start) / len, 0.0) * density;
    return min + range - range / exp(x * x);
}

float farFogThickness(float dist) {
    if (uFarFogFalloffType == 0) return linearFog(dist, uFarFogStart, uFarFogLength, uFarFogMin, uFarFogRange);
    if (uFarFogFalloffType == 1) return expFog(dist, uFarFogStart, uFarFogLength, uFarFogMin, uFarFogRange, uFarFogDensity);
    return exp2Fog(dist, uFarFogStart, uFarFogLength, uFarFogMin, uFarFogRange, uFarFogDensity);
}

float heightFogThickness(float depth) {
    if (uHeightFogFalloffType == 0) return linearFog(depth, uHeightFogStart, uHeightFogLength, uHeightFogMin, uHeightFogRange);
    if (uHeightFogFalloffType == 1) return expFog(depth, uHeightFogStart, uHeightFogLength, uHeightFogMin, uHeightFogRange, uHeightFogDensity);
    return exp2Fog(depth, uHeightFogStart, uHeightFogLength, uHeightFogMin, uHeightFogRange, uHeightFogDensity);
}

float calculateHeightFogDepth(float worldYPos) {
    if (!uHeightFogEnabled) return 0.0;
    if (!uHeightBasedOnCamera) worldYPos -= (uHeightFogBaseHeight - uCameraBlockYPos);
    if (uHeightFogAppliesDown && uHeightFogAppliesUp) return abs(worldYPos) * uFogVerticalScale;
    else if (uHeightFogAppliesDown) return -worldYPos * uFogVerticalScale;
    else if (uHeightFogAppliesUp) return worldYPos * uFogVerticalScale;
    return 0.0;
}

float mixFogThickness(float far, float height) {
    switch (uHeightFogMixingMode) {
        case 0: case 1: return far;
        case 2: return max(far, height);
        case 3: return far + height;
        case 4: return far * height;
        case 5: return 1.0 - (1.0 - far) * (1.0 - height);
        case 6: return far + max(far, height);
        case 7: return far + far * height;
        case 8: return far + 1.0 - (1.0 - far) * (1.0 - height);
        case 9: return far * 0.5 + height * 0.5;
    }
    return far;
}

void main() {
    float fragmentDepth = texture(uDepthMap, TexCoord).r;
    fragColor = vec4(uFogColor.rgb, 0.0);

    if (fragmentDepth < 1.0) {
        vec3 vertexWorldPos = calcViewPosition(fragmentDepth);
        float horizontalDist = length(vertexWorldPos.xz) * uFogScale;
        float worldDist = length(vertexWorldPos.xyz) * uFogScale;
        float activeDist = uUseSphericalFog ? worldDist : horizontalDist;

        float farThickness = farFogThickness(activeDist);
        float heightDepth = calculateHeightFogDepth(vertexWorldPos.y);
        float heightThickness = heightFogThickness(heightDepth);
        float mixed = mixFogThickness(farThickness, heightThickness);
        fragColor.a = clamp(mixed, 0.0, 1.0);
    }
}
