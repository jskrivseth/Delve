#version 330 core

in vec2 fragTexCoord;

uniform sampler2D textureSampler;
uniform bool useTexture;
uniform vec4 tint;

out vec4 outColor;

void main() {
    vec4 color = tint;

    if (useTexture) {
        vec4 texel = texture(textureSampler, fragTexCoord);
        if (texel.a < 0.1) {
            discard;
        }
        color = vec4(texel.rgb * tint.rgb, tint.a);
    }

    outColor = color;
}
