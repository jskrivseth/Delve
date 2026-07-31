#version 330 core

layout (location = 0) in vec2 inPosition;

uniform mat4 invViewProjection;

out vec3 fragViewRay;

void main() {
    // Reconstruct a world-space ray through this pixel so the sky can be shaded
    // by direction rather than by screen position.
    vec4 near = invViewProjection * vec4(inPosition, -1.0, 1.0);
    vec4 far = invViewProjection * vec4(inPosition, 1.0, 1.0);
    fragViewRay = (far.xyz / far.w) - (near.xyz / near.w);

    gl_Position = vec4(inPosition, 0.0, 1.0);
}
