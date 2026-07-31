#version 330 core

layout (location = 0) in vec2 inOffset;

uniform mat4 projection;
uniform mat4 viewRotation;
uniform vec3 bodyDirection;
uniform float bodySize;

out vec2 fragOffset;

void main() {
    fragOffset = inOffset;

    // Billboard in view space so the disc stays circular anywhere on screen.
    // Building the quad perpendicular to the body direction instead leaves it
    // subject to perspective stretch toward the screen edges.
    vec3 viewDir = normalize(mat3(viewRotation) * normalize(bodyDirection));
    vec3 viewPos = viewDir + vec3(inOffset.x, inOffset.y, 0.0) * bodySize;

    vec4 pos = projection * vec4(viewPos, 1.0);
    // Force to the far plane so terrain always draws in front.
    gl_Position = pos.xyww;
}
