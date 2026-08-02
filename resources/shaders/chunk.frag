#version 330 core

#include "/shaders/lib/clouds.glsl"

out vec4 outColor;

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
uniform float cloudShadowStrength;

in vec3 fragNormal;
in vec4 fragColor;
in vec2 fragTexCoord;
in float fragViewDistance;
in float fragSkyLight;
in vec3 fragViewPos;
in vec3 fragViewNormal;
in vec3 fragWorldPos;
in vec3 fragTint;

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

    // Biome tint is a separate channel from the palette colour so it survives
    // the vertex-colour toggle and multiplies the texture rather than replacing it.
    albedo *= fragTint;

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
    // A cloud shadow is only ever visible on a surface that receives direct sun.
    // Faces angled away, and anything underground where skylight is occluded,
    // cannot show one -- and this shader uses discard for cutout foliage, which
    // disables early-Z, so hidden fragments would otherwise pay for the march too.
    float sunLitAmount = mix(max(ndlSun, 0.0), sunWrap, 0.5) * exposure;
    if (cloudsEnabled && cloudShadowStrength > 0.001 && toSun.y > 0.001
            && sunLitAmount > 0.015 && !ablated(AB_SHADOWS)) {
        vec2 windDir  = vec2(cos(cloudWindAngle), sin(cloudWindAngle));
        vec2 baseWind = windDir * (cloudDayTime * cloudSpeed * cloudWindSpeed * 3200.0);
        vec2 windL0   = windRotate(baseWind * 0.78,  0.11);
        vec2 windL1   = baseWind;
        vec2 evolL0   = layerEvolve(317.4);
        vec2 evolL1   = layerEvolve(0.0);

        float ct   = cloudDayTime;
        float turb = cloudTurbulence;
        float bL0  = cloudBaseHeight - 320.0 + sin(ct * 1.618) * (85.0 + 130.0 * turb) - sin(ct * 2.414 + 1.8) * 50.0 * turb;
        float bL1  = cloudBaseHeight          - sin(ct * 1.414) * (90.0 + 150.0 * turb) + sin(ct * 0.618 + 0.7) * 45.0 * turb;
        float dL0  = cloudLayerDepth * 0.80;
        float dL1  = cloudLayerDepth;

        float depth = 0.0;

        // Helper macro: march through one layer toward the sun
        #define SHADOW_LAYER(BH, LD, WW, EV) { \
            float sBot_ = cloudSlabBottom(BH); \
            float sTop_ = BH + LD; \
            float tE_   = (sBot_ - fragWorldPos.y) / toSun.y; \
            float tX_   = (sTop_ - fragWorldPos.y) / toSun.y; \
            float t0_   = max(min(tE_, tX_), 0.0); \
            float t1_   = min(max(tE_, tX_), t0_ + LD * 10.0); \
            if (t1_ > t0_) { \
                vec3 mid_ = fragWorldPos + toSun * mix(t0_, t1_, 0.5); \
                float reg_ = fbm((mid_.xz + EV * 0.35 + WW * 0.08) * 0.00075); \
                float ec_  = cloudCoverageAt(mid_.xz, WW, EV); \
                float dt_  = (t1_ - t0_) / 3.0; \
                for (int si = 0; si < 3; si++) { \
                    vec3 sp_ = fragWorldPos + toSun * (t0_ + dt_ * (float(si) + 0.5)); \
                    depth += cloudDensityCoarse(sp_, WW, EV, reg_, BH, LD, ec_) * dt_; \
                } \
            } \
        }

        // Only the two substantial decks cast shadow. The high cirrus layer is
        // thin enough that its contribution is not worth a third of the cost.
        SHADOW_LAYER(bL0, dL0, windL0, evolL0)
        if (atmospherePreset != 1) {
            SHADOW_LAYER(bL1, dL1, windL1, evolL1)
        }

        #undef SHADOW_LAYER

        float thickness = 1.0 - exp(-depth * 0.038);
        sunCloudAttenuation = 1.0 - thickness * cloudShadowStrength;
        sunCloudAttenuation = clamp(sunCloudAttenuation, 0.04, 1.0);
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
        // Capped short of fully opaque: this term is the ambient haze that
        // should read as atmosphere, not a wall. Only edgeFog below, anchored
        // to the actual draw-distance boundary, is allowed to reach fully
        // opaque -- otherwise the haze alone whited out well inside the
        // visible range on long draw distances, before terrain ever reached
        // the real edge.
        distanceFog = min(distanceFog, 0.82);

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
        vec3 fogTarget = fogColor;
        if (atmospherePreset == 0) {
            // Keep long-range Earth terrain from flattening to solid white/gray.
            float preserve = smoothstep(0.55, 0.95, fogAmount) * fogDayFactor;
            fogTarget = mix(fogColor, lit * 0.90 + fogColor * 0.10, preserve * 0.35);
        }
        lit = mix(lit, fogTarget, fogAmount);
    }

    outColor = vec4(lit, alphaOverride);
}
