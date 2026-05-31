#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D MainDepthSampler;
uniform samplerBuffer DataBuffer;
uniform int InstanceCount;
uniform vec2 InSize;
uniform vec2 OutSize;
uniform vec2 ScreenSize;

in vec2 texCoord;
out vec4 fragColor;

void main() {
	vec4 color = texture(DiffuseSampler, texCoord);

	if (InstanceCount < 0) {
		color += texture(MainDepthSampler, texCoord) * 0.0;
		color += texelFetch(DataBuffer, 0) * 0.0;
		color += vec4(InSize + OutSize + ScreenSize, 0.0, 0.0) * 0.0;
	}

	fragColor = color;
}
