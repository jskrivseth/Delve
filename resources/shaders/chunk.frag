#version 330 core

in vec3 fragNormal;
in vec4 fragColor;
in vec2 fragTexCoord;
in float fragViewDistance;
in float fragSkyLight;
in vec3 fragViewPos;
in vec3 fragViewNormal;
in vec3 fragWorldPos;

uniform sampler2D textureSampler;
uniform bool useTexture;
uniform bool useVertexColor;
uniform float aoStrength;

uniform vec3 sunDirection;
uniform vec3 sunColor;
uniform vec3 moonDirection;
uniform vec3 moonColor;
uniform vec3 skyAmbient;
uniform vec3 groundAmbient;
/** Floor so enclosed spaces stay readable rather than going pure black. */
uniform float caveMinimum;

uniform bool flashlightOn;
uniform vec3 flashlightColor;
uniform float flashlightRange;
uniform float flashlightInner;
uniform float flashlightOuter;

uniform bool fogEnabled;
uniform vec3 fogColor;
uniform float fogStart;
uniform float fogEnd;
uniform float fogDensity;
uniform float fogHeightFalloff;
uniform float fogBaseHeight;
uniform float fogNoiseScale;
uniform float fogTime;
uniform float fogTimeScale;
uniform float fogDayFactor;
uniform float fogDuskFactor;
uniform float fogValleyStrength;
uniform float fogValleyTop;
uniform float alphaOverride;
uniform bool cloudsEnabled;
uniform float cloudCoverage;
uniform float cloudSharpness;
uniform float cloudShadowStrength;
uniform float cloudBaseHeight;
uniform float cloudLayerDepth;
uniform float cloudTime;
uniform float cloudSpeed;
uniform float cloudDayTime;
uniform int atmospherePreset;

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

/** Cheaper three octave variant used inside the cloud volume lookups. */
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

/** Must mirror skygradient.frag so shadows land under the clouds that cast them. */
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

void main() {
    // The vertex color's alpha channel carries ambient occlusion, not opacity.
    float ao = mix(1.0, fragColor.a, aoStrength);
    vec3 albedo = useVertexColor ? fragColor.rgb : vec3(1.0);

    if (useTexture) {
        vec4 texel = texture(textureSampler, fragTexCoord);
        // Cutout instead of blending: foliage and glass have real alpha and the
        // chunks are not depth sorted, so blending would resolve out of order.
        if (texel.a < 0.5) {
            discard;
        }
        albedo *= texel.rgb;
    }

    vec3 normal = normalize(fragNormal);

    // How much of the open sky reaches this surface. Deep tunnels approach zero,
    // which is what darkens the world as you dig.
    float exposure = fragSkyLight;

    // Hemispheric ambient: skylight from above, bounced light from the ground
    // below. Much more natural than a single flat ambient term.
    float hemi = 0.5 + 0.5 * normal.y;
    vec3 ambient = mix(groundAmbient, skyAmbient, hemi);
    ambient *= max(exposure, caveMinimum);

    // Half-lambert wrap softens the terminator so vertical faces don't fall off
    // a cliff into pure ambient.
    vec3 toSun = -normalize(sunDirection);
    float ndlSun = dot(normal, toSun);
    float sunWrap = max((ndlSun + 0.3) / 1.3, 0.0);
    float sunCloudAttenuation = 1.0;
    if (cloudsEnabled && cloudShadowStrength > 0.001 && toSun.y > 0.001) {
        float slabTop = cloudBaseHeight + cloudLayerDepth;
        float tEnter = (cloudBaseHeight - fragWorldPos.y) / toSun.y;
        float tExit = (slabTop - fragWorldPos.y) / toSun.y;
        float t0 = max(min(tEnter, tExit), 0.0);
        float t1 = min(max(tEnter, tExit), t0 + cloudLayerDepth * 10.0);
        if (t1 > t0) {
            vec2 windWorld = vec2(cloudTime * cloudSpeed * 6.0, -cloudTime * cloudSpeed * 2.5);
            vec2 evolve = vec2(
                    sin(cloudDayTime * 2.324 + cloudTime * 0.017),
                    cos(cloudDayTime * 1.447 - cloudTime * 0.013)) * 96.0;
            vec3 mid = fragWorldPos + toSun * mix(t0, t1, 0.5);
            float regime = fbm((mid.xz + evolve * 0.35 + windWorld * 0.08) * 0.00075);

            // Four steps through the slab: enough to tell a thin wisp from a
            // solid tower without paying for a full march on every lit pixel.
            const int SHADOW_STEPS = 4;
            float dt = (t1 - t0) / float(SHADOW_STEPS);
            float depth = 0.0;
            for (int i = 0; i < SHADOW_STEPS; i++) {
                vec3 sp = fragWorldPos + toSun * (t0 + dt * (float(i) + 0.5));
                depth += cloudDensityCoarse(sp, windWorld, evolve, regime) * dt;
            }
            float thickness = 1.0 - exp(-depth * 0.020);
            sunCloudAttenuation = 1.0 - thickness * cloudShadowStrength;
            sunCloudAttenuation = clamp(sunCloudAttenuation, 0.12, 1.0);
        }
    }
    vec3 direct = sunColor * mix(max(ndlSun, 0.0), sunWrap, 0.5) * exposure * sunCloudAttenuation;

    vec3 toMoon = -normalize(moonDirection);
    float ndlMoon = dot(normal, toMoon);
    float moonWrap = max((ndlMoon + 0.3) / 1.3, 0.0);
    direct += moonColor * mix(max(ndlMoon, 0.0), moonWrap, 0.5) * exposure;

    vec3 lit = albedo * (ambient + direct) * ao;

    if (flashlightOn) {
        // In view space the camera sits at the origin looking down -Z, so the
        // spotlight needs no extra uniforms for position or orientation. The
        // normal must be taken in view space too, or the facing term compares
        // vectors from two different spaces and the beam breaks into patches.
        vec3 toFrag = normalize(fragViewPos);
        vec3 viewNormal = normalize(fragViewNormal);

        float cosAngle = dot(toFrag, vec3(0.0, 0.0, -1.0));
        float cone = smoothstep(flashlightOuter, flashlightInner, cosAngle);
        float falloff = clamp(1.0 - fragViewDistance / flashlightRange, 0.0, 1.0);
        float facing = max(dot(viewNormal, -toFrag), 0.0);

        // Ambient occlusion describes how much sky light reaches a corner, so
        // applying it at full strength to a handheld light cancels the beam in
        // the very crevices it is meant to reveal.
        float directAo = mix(1.0, ao, 0.3);

        // A torch is invisible against full daylight. Fading it out as the scene
        // brightens keeps caves lit without blowing out sunlit ground.
        float sceneLuma = dot(ambient + direct, vec3(0.299, 0.587, 0.114));
        float adaptation = clamp(1.0 - sceneLuma * 1.35, 0.0, 1.0);

        lit += albedo * flashlightColor * cone * falloff * falloff
             * facing * directAo * adaptation;
    }

    if (fogEnabled) {
        // Base fog from optical depth, mostly independent of render distance.
        // Render distance only contributes a final far-edge blend to mask chunk
        // pop at the horizon.
        float baseRange = max(fogEnd - fogStart, 0.0001);

        // Fog pools low and thins with altitude.
        float heightTerm = exp(-(fragWorldPos.y - fogBaseHeight) * fogHeightFalloff);
        heightTerm = clamp(heightTerm, 0.25, 2.25);

        // Slow drifting variation across terrain so haze is patchy instead of
        // one uniform wall.
        vec2 uv = fragWorldPos.xz * fogNoiseScale;
        float t = fogTime * fogTimeScale;
        float n0 = valueNoise(uv + vec2(t, -t * 0.6));
        float n1 = valueNoise(uv * 2.1 + vec2(-t * 0.45, t * 0.9));
        float drift = mix(n0, n1, 0.45);
        float pocket = mix(0.72, 1.30, drift);

        // Slightly clearer by day, thicker around dusk and at night.
        float timeDensity = mix(1.12, 0.84, fogDayFactor) * mix(1.0, 1.18, fogDuskFactor);
        float density = fogDensity * heightTerm * pocket * timeDensity;

        // Exponential-squared reads more atmospheric than a hard linear ramp.
        float opticalDepth = max(fragViewDistance - fogStart, 0.0) * density;
        float distanceFog = 1.0 - exp(-pow(max(opticalDepth, 0.0), 1.18));

        // Valley cloud bank: local fog that can stay present even at short range
        // when the fragment sits below the inversion layer.
        float valleyBand = 1.0 - smoothstep(fogValleyTop - 10.0, fogValleyTop + 8.0, fragWorldPos.y);
        float valleyFog = fogValleyStrength * valleyBand * pocket * mix(1.06, 0.90, fogDayFactor);
        valleyFog *= mix(1.0, 1.10, fogDuskFactor);

        // Distance fog is range-gated to hide chunk pop, but local valley mist
        // should not disappear when the camera moves into it.
        float localMedium = 1.0 - exp(-max(fragViewDistance, 0.0) * density * 0.08);
        float persistentValleyFog = valleyFog * mix(0.55, 1.0, localMedium);

        // Keep a final edge blend near fogEnd so far chunk pop still hides.
        float edgeFog = smoothstep(fogEnd - baseRange * 0.18, fogEnd, fragViewDistance);

        // Combine independent effects as overlapping media rather than max().
        float fogAmount = 1.0
                - (1.0 - clamp(distanceFog, 0.0, 1.0))
                * (1.0 - clamp(persistentValleyFog, 0.0, 1.0))
                * (1.0 - clamp(edgeFog, 0.0, 1.0));
        fogAmount = clamp(fogAmount, 0.0, 1.0);
        lit = mix(lit, fogColor, fogAmount);
    }

    outColor = vec4(lit, alphaOverride);
}
