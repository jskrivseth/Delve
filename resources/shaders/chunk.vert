#version 330 core

layout (location = 0) in vec3 inPosition;
layout (location = 1) in vec3 inNormal;
layout (location = 2) in vec4 inColor;
layout (location = 3) in vec2 inTexCoord;

uniform mat4 projection;
uniform mat4 view;
uniform mat4 model;

out vec3 fragNormal;
out vec4 fragColor;
out vec2 fragTexCoord;
out float fragViewDistance;

void main() {
    vec4 worldPos = model * vec4(inPosition, 1.0);
    vec4 viewPos = view * worldPos;

    // Normals are only ever rotated/translated here, so the model matrix upper
    // 3x3 is orthonormal and a full normal matrix is unnecessary.
    fragNormal = mat3(model) * inNormal;
    fragColor = inColor;
    fragTexCoord = inTexCoord;
    fragViewDistance = length(viewPos.xyz);

    gl_Position = projection * viewPos;
}
