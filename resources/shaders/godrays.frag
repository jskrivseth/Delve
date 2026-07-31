#version 330 core

in vec2 fragUv;

uniform sampler2D sceneTexture;
uniform sampler2D depthTexture;
/** Light position in screen UV space, in xy. */
uniform vec4 lightScreenPos;
uniform vec3 lightColor;
uniform float intensity;
uniform float decay;
uniform float density;

const int SAMPLES = 48;

out vec4 outColor;

/**
 * Screen-space volumetric light shafts.
 *
 * Marches from the fragment toward the light, accumulating only where the depth
 * buffer says nothing occludes the sky. That occlusion test is what makes the
 * shafts break around terrain instead of glowing straight through it.
 */
void main() {
    vec2 lightUv = lightScreenPos.xy;
    vec2 delta = fragUv - lightUv;
    delta *= density / float(SAMPLES);

    vec2 uv = fragUv;
    float illumination = 1.0;
    float accum = 0.0;

    for (int i = 0; i < SAMPLES; i++) {
        uv -= delta;
        vec2 clamped = clamp(uv, 0.0, 1.0);
        if (clamped != uv) {
            break;
        }
        // Depth of 1 means nothing was drawn, so this sample sees open sky.
        float depth = texture(depthTexture, uv).r;
        float sky = step(0.9999, depth);
        accum += sky * illumination;
        illumination *= decay;
    }

    accum /= float(SAMPLES);
    outColor = vec4(lightColor * accum * intensity, 1.0);
}
