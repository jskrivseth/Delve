#version 330 core

in vec2 fragUv;

uniform sampler2D sceneTexture;
uniform sampler2D raysTexture;
uniform bool raysEnabled;

out vec4 outColor;

void main() {
    vec3 scene = texture(sceneTexture, fragUv).rgb;

    if (raysEnabled) {
        // Additive, so shafts brighten the scene without dimming anything.
        scene += texture(raysTexture, fragUv).rgb;
    }

    outColor = vec4(scene, 1.0);
}
