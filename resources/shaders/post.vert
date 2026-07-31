#version 330 core

layout (location = 0) in vec2 inPosition;

out vec2 fragUv;

void main() {
    fragUv = inPosition * 0.5 + 0.5;
    gl_Position = vec4(inPosition, 0.0, 1.0);
}
