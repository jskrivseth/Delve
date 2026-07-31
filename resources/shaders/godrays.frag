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
uniform mat4 invViewProjection;
uniform vec3 cameraWorldPos;
uniform bool cloudsEnabled;
uniform float cloudCoverage;
uniform float cloudSharpness;
uniform float cloudOpacity;
uniform float cloudBaseHeight;
uniform float cloudLayerDepth;
uniform float cloudTime;
uniform float cloudSpeed;
uniform float cloudDayTime;
uniform int atmospherePreset; // 0=Earth, 1=Mars, 2=Venus, 3=Triton

const int SAMPLES = 48;

out vec4 outColor;

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash12(i + vec2(0.0, 0.0));
    float b = hash12(i + vec2(1.0, 0.0));
    float c = hash12(i + vec2(0.0, 1.0));
    float d = hash12(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p) {
    float f = 0.0;
    float a = 0.55;
    float freq = 1.0;
    for (int i = 0; i < 4; i++) {
        f += a * valueNoise(p * freq);
        freq *= 2.0;
        a *= 0.55;
    }
    return f;
}

/** Cheaper three octave variant; god rays sample this many times per pixel. */
float fbm3(vec2 p) {
    float f = 0.0;
    float a = 0.58;
    float freq = 1.0;
    for (int i = 0; i < 3; i++) {
        f += a * valueNoise(p * freq);
        freq *= 2.0;
        a *= 0.55;
    }
    return f;
}

/** Must mirror skygradient.frag so shafts break where the clouds actually are. */
float cloudMaskAt(vec2 cloudXZ, vec2 windWorld, vec2 evolve, float regime) {
    float n0 = fbm3((cloudXZ + windWorld + evolve) * 0.0022);
    float n1 = fbm3((cloudXZ - windWorld * 0.65 + evolve.yx * 0.7) * 0.0041);
    float n = mix(n0, n1, 0.40);
    if (atmospherePreset == 2) {
        float deck = smoothstep(cloudCoverage - 0.18, cloudCoverage + cloudSharpness * 2.8, mix(n0, n1, 0.25));
        float billow = smoothstep(cloudCoverage - 0.06, cloudCoverage + cloudSharpness * 1.6, n0);
        return clamp(mix(deck, billow, 0.35), 0.0, 1.0);
    }

    float cumulusBase = smoothstep(cloudCoverage - cloudSharpness, cloudCoverage + cloudSharpness, n);
    float cumulusCrisp = smoothstep(cloudCoverage - cloudSharpness * 0.30,
            cloudCoverage + cloudSharpness * 0.20,
            n + (n1 - 0.5) * 0.14);
    float cumulus = mix(cumulusBase, cumulusCrisp, 0.55) * (1.0 - smoothstep(0.55, 0.88, regime));
    float stratus = smoothstep(cloudCoverage - 0.12, cloudCoverage + 0.18, n0)
            * smoothstep(0.42, 0.95, regime);
    float cirrus = smoothstep(cloudCoverage - 0.30, cloudCoverage - 0.08, n1)
            * (1.0 - smoothstep(0.28, 0.70, regime)) * 0.35;
    return clamp(max(cumulus, stratus * 0.92) + cirrus, 0.0, 1.0);
}

float cloudDensityCoarse(vec3 p, vec2 windWorld, vec2 evolve, float regime) {
    float hN = (p.y - cloudBaseHeight) / max(cloudLayerDepth, 1.0);
    if (hN < 0.0 || hN > 1.0) {
        return 0.0;
    }
    float profile = atmospherePreset == 2
            ? smoothstep(0.0, 0.10, hN) * (1.0 - smoothstep(0.72, 1.0, hN))
            : smoothstep(0.0, 0.13, hN) * (1.0 - smoothstep(0.42, 1.0, hN));
    if (profile <= 0.002) {
        return 0.0;
    }
    vec2 shear = vec2(hN * 34.0, -hN * 22.0);
    return cloudMaskAt(p.xz + shear, windWorld, evolve, regime) * profile;
}

float cloudOpacityAtUv(vec2 uv) {
    if (!cloudsEnabled) {
        return 0.0;
    }
    vec2 ndc = uv * 2.0 - 1.0;
    vec4 nearP = invViewProjection * vec4(ndc, -1.0, 1.0);
    vec4 farP = invViewProjection * vec4(ndc, 1.0, 1.0);
    vec3 dir = normalize((farP.xyz / farP.w) - (nearP.xyz / nearP.w));
    if (dir.y <= 0.03) {
        return 0.0;
    }

    float slabTop = cloudBaseHeight + cloudLayerDepth;
    float tEnter = (cloudBaseHeight - cameraWorldPos.y) / dir.y;
    float tExit = (slabTop - cameraWorldPos.y) / dir.y;
    float t0 = max(min(tEnter, tExit), 0.0);
    float t1 = min(max(tEnter, tExit), t0 + cloudLayerDepth * 10.0);
    if (t1 <= t0) {
        return 0.0;
    }

    vec2 windWorld = vec2(cloudTime * cloudSpeed * 6.0, -cloudTime * cloudSpeed * 2.5);
    vec2 evolve = vec2(
            sin(cloudDayTime * 2.324 + cloudTime * 0.017),
            cos(cloudDayTime * 1.447 - cloudTime * 0.013)) * 96.0;
    vec3 mid = cameraWorldPos + dir * mix(t0, t1, 0.5);
    float regime = fbm3((mid.xz + evolve * 0.35 + windWorld * 0.08) * 0.00075);

    // Two taps through the slab keep this in budget; it is sampled dozens of
    // times per pixel by the shaft loop.
    float span = t1 - t0;
    float d0 = cloudDensityCoarse(cameraWorldPos + dir * (t0 + span * 0.30), windWorld, evolve, regime);
    float d1 = cloudDensityCoarse(cameraWorldPos + dir * (t0 + span * 0.72), windWorld, evolve, regime);
    float opticalDepth = (d0 + d1) * 0.5 * span * mix(0.030, 0.075, cloudOpacity);
    return clamp(1.0 - exp(-opticalDepth), 0.0, 1.0);
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
        float cloud = cloudOpacityAtUv(uv);
        float transmission = 1.0 - cloud;
        accum += sky * transmission * illumination;
        illumination *= decay;
    }

    accum /= float(SAMPLES);
    outColor = vec4(lightColor * accum * intensity, 1.0);
}
