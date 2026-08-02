#version 330 core

layout (location = 0) in vec3 inPosition;
layout (location = 1) in vec3 inNormal;
layout (location = 2) in vec4 inColor;
layout (location = 3) in vec2 inTexCoord;
layout (location = 4) in float inSkyLight;
layout (location = 5) in float inTint;

uniform mat4 projection;
uniform mat4 view;
uniform mat4 model;

out vec3 fragNormal;
out vec4 fragColor;
out vec2 fragTexCoord;
out float fragViewDistance;
out float fragSkyLight;
out vec3 fragViewPos;
out vec3 fragViewNormal;
out vec3 fragWorldPos;
out vec3 fragTint;

void main() {
    vec4 worldPos = model * vec4(inPosition, 1.0);
    vec4 viewPos = view * worldPos;

    // Normals are only ever rotated/translated here, so the model matrix upper
    // 3x3 is orthonormal and a full normal matrix is unnecessary.
    fragNormal = mat3(model) * inNormal;
    // The flashlight is evaluated in view space, so it needs a matching normal.
    fragViewNormal = mat3(view) * fragNormal;
    fragColor = inColor;
    fragTexCoord = inTexCoord;
    fragSkyLight = inSkyLight;
    fragViewPos = viewPos.xyz;
    fragWorldPos = worldPos.xyz;
    fragViewDistance = length(viewPos.xyz);

    // Biome tint arrives as three 8-bit channels packed into one float, each
    // covering the range [0, 2]. Unpacking here rather than in the fragment
    // shader matters: the rasteriser must interpolate the resolved colour, not
    // the packed integer, which would blend into nonsense.
    float tintBits = inTint;
    float tintR = floor(tintBits / 65536.0);
    float tintG = floor(mod(tintBits, 65536.0) / 256.0);
    float tintB = mod(tintBits, 256.0);
    fragTint = vec3(tintR, tintG, tintB) / 127.5;

    gl_Position = projection * viewPos;
}
