#version 330 core

out vec4 outColor;

uniform vec4 lightScreenPos;
uniform vec3 lightColor;
uniform float intensity;
uniform float decay;
uniform float density;
uniform bool cloudsEnabled;

const int SAMPLES = 48;
uniform int raySamples;

in vec2 fragUv;

uniform sampler2D depthTexture;

// Cloud opacity resolved by the sky pass and published in its alpha channel.
// This used to be a second volumetric march evaluated once per shaft sample,
// which meant the cloud field was sampled SAMPLES times per pixel and cost more
// than every other pass in the frame combined.
uniform sampler2D skyTexture;

float cloudOpacityAtUv(vec2 uv) {
    return cloudsEnabled ? clamp(texture(skyTexture, uv).a, 0.0, 1.0) : 0.0;
}

/**
 * Screen-space volumetric light shafts.
 *
 * Marches from the fragment toward the light, accumulating only where the depth
 * buffer says nothing occludes the sky. That occlusion test is what makes the
 * shafts break around terrain instead of glowing straight through it.
 */
void main() {
    vec2 lightUv = lightScreenPos.xy;
    int samples = clamp(raySamples, 1, SAMPLES);
    vec2 delta = fragUv - lightUv;
    delta *= density / float(samples);

    vec2 uv = fragUv;
    float illumination = 1.0;
    float accum = 0.0;

    for (int i = 0; i < SAMPLES; i++) {
        if (i >= samples) {
            break;
        }
        uv -= delta;
        vec2 clamped = clamp(uv, 0.0, 1.0);
        if (clamped != uv) {
            break;
        }
        // Depth of 1 means nothing was drawn, so this sample sees open sky.
        float depth = texture(depthTexture, uv).r;
        float sky = step(0.9999, depth);
        float cloud = cloudOpacityAtUv(uv);
        float transmission = 1.0 - cloud;
        accum += sky * transmission * illumination;
        illumination *= decay;
    }

    accum /= float(samples);
    outColor = vec4(lightColor * accum * intensity, 1.0);
}
