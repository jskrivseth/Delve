#version 330 core

in vec3 fragViewRay;

uniform vec3 sunDirection;
uniform vec3 moonDirection;
/** 0 at night, 1 in full day. */
uniform float dayFactor;
/** Peaks while the sun sits on the horizon. */
uniform float duskFactor;
uniform float moonGlow;
uniform int atmospherePreset; // 0=Earth, 1=Mars, 2=Venus, 3=Triton
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

/** Cheaper three octave variant used inside the volumetric march. */
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

/** Horizontal coverage of the cloud field, i.e. how much sky a column fills. */
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

/**
 * Density of the cloud volume at a world point.
 *
 * The slab is a real body: a vertical profile gives flat lit bases and billowed
 * tops, wind shear leans the column with altitude so it is not a straight
 * extrusion of the 2D mask, and higher frequency noise erodes the upper half
 * into separate lumps.
 */
float cloudDensityCoarse(vec3 p, vec2 windWorld, vec2 evolve, float regime) {
    float hN = (p.y - cloudBaseHeight) / max(cloudLayerDepth, 1.0);
    if (hN < 0.0 || hN > 1.0) {
        return 0.0;
    }

    // Venus keeps a deep uniform deck; Earth rounds off toward the top.
    float profile = atmospherePreset == 2
            ? smoothstep(0.0, 0.10, hN) * (1.0 - smoothstep(0.72, 1.0, hN))
            : smoothstep(0.0, 0.13, hN) * (1.0 - smoothstep(0.42, 1.0, hN));
    if (profile <= 0.002) {
        return 0.0;
    }

    vec2 shear = vec2(hN * 34.0, -hN * 22.0);
    return cloudMaskAt(p.xz + shear, windWorld, evolve, regime) * profile;
}

float cloudDensity(vec3 p, vec2 windWorld, vec2 evolve, float regime) {
    float coarse = cloudDensityCoarse(p, windWorld, evolve, regime);
    if (coarse <= 0.005) {
        return 0.0;
    }

    // Erosion grows with altitude so bases stay solid and tops break into billows.
    float hN = clamp((p.y - cloudBaseHeight) / max(cloudLayerDepth, 1.0), 0.0, 1.0);
    vec2 xz = p.xz + vec2(hN * 34.0, -hN * 22.0);
    float detail = valueNoise((xz + windWorld * 0.30) * 0.0125 + vec2(hN * 4.7, -hN * 3.3))
            + 0.5 * valueNoise((xz - windWorld * 0.20) * 0.0290 + vec2(-hN * 6.1, hN * 5.2));
    float erode = mix(0.05, 0.46, hN) * detail;
    return clamp(coarse - erode, 0.0, 1.0);
}

void main() {
    vec3 dir = normalize(fragViewRay);
    vec3 toSun = -normalize(sunDirection);

    // Height in the sky, 0 at the horizon and 1 at the zenith.
    float h = clamp(dir.y, 0.0, 1.0);
    // Angular proximity to the sun, used to warm the sky around it.
    float sunAmount = max(dot(dir, toSun), 0.0);

    // --- Daytime -----------------------------------------------------------
    vec3 dayZenith = vec3(0.24, 0.47, 0.88);
    vec3 dayHorizon = vec3(0.71, 0.85, 0.96);
    vec3 nightZenith = vec3(0.015, 0.025, 0.075);
    vec3 nightHorizon = vec3(0.055, 0.075, 0.15);
    vec3 emberColor  = vec3(0.95, 0.32, 0.10);
    vec3 orangeColor = vec3(0.98, 0.55, 0.22);
    vec3 pinkColor   = vec3(0.93, 0.62, 0.66);
    vec3 violetColor = vec3(0.45, 0.38, 0.62);
    vec3 haloDay = vec3(1.0, 0.96, 0.85);
    vec3 haloDusk = vec3(1.0, 0.55, 0.25);
    float duskStrength = duskFactor;
    float moonLift = moonGlow;

    if (atmospherePreset == 1) { // Mars: rusty day, blue twilight.
        dayZenith = vec3(0.56, 0.36, 0.30);
        dayHorizon = vec3(0.76, 0.55, 0.44);
        nightZenith = vec3(0.018, 0.028, 0.060);
        nightHorizon = vec3(0.040, 0.060, 0.105);
        emberColor = vec3(0.18, 0.45, 0.95);
        orangeColor = vec3(0.26, 0.62, 0.98);
        pinkColor = vec3(0.36, 0.70, 0.98);
        violetColor = vec3(0.22, 0.42, 0.78);
        haloDay = vec3(0.85, 0.92, 1.0);
        haloDusk = vec3(0.44, 0.72, 1.0);
    } else if (atmospherePreset == 2) { // Venus: thick yellow haze.
        dayZenith = vec3(0.78, 0.66, 0.28);
        dayHorizon = vec3(0.93, 0.84, 0.50);
        nightZenith = vec3(0.12, 0.09, 0.05);
        nightHorizon = vec3(0.22, 0.16, 0.09);
        emberColor = vec3(0.98, 0.66, 0.20);
        orangeColor = vec3(0.95, 0.74, 0.30);
        pinkColor = vec3(0.84, 0.66, 0.36);
        violetColor = vec3(0.58, 0.48, 0.30);
        haloDay = vec3(1.0, 0.92, 0.60);
        haloDusk = vec3(1.0, 0.76, 0.36);
        duskStrength *= 0.70;
        moonLift *= 0.22;
    } else if (atmospherePreset == 3) { // Triton: near-vacuum.
        dayZenith = vec3(0.040, 0.070, 0.130);
        dayHorizon = vec3(0.055, 0.085, 0.145);
        nightZenith = vec3(0.005, 0.008, 0.016);
        nightHorizon = vec3(0.010, 0.014, 0.024);
        emberColor = vec3(0.14, 0.18, 0.30);
        orangeColor = vec3(0.14, 0.18, 0.30);
        pinkColor = vec3(0.12, 0.16, 0.28);
        violetColor = vec3(0.10, 0.14, 0.22);
        haloDay = vec3(0.72, 0.82, 1.0);
        haloDusk = vec3(0.58, 0.72, 0.94);
        duskStrength *= 0.10;
        moonLift *= 0.08;
    }

    vec3 dayColor = mix(dayHorizon, dayZenith, pow(h, 0.55));

    // --- Night -------------------------------------------------------------
    vec3 nightColor = mix(nightHorizon, nightZenith, pow(h, 0.6));
    // A bright moon lifts the sky around it.
    float moonAmount = max(dot(dir, -normalize(moonDirection)), 0.0);
    nightColor += vec3(0.05, 0.06, 0.10) * moonLift * pow(moonAmount, 6.0);

    vec3 base = mix(nightColor, dayColor, dayFactor);

    // --- Sunset and sunrise ------------------------------------------------
    // Real twilight runs deep orange at the sun, through pink, into blue with
    // height. A single red tint over the whole sky reads as blood rather than
    // atmosphere, so the bands are layered by height and by angle to the sun.
    // Horizontal falloff: the glow concentrates toward the sun's bearing.
    float towardSun = pow(sunAmount, 2.2);
    // Vertical falloff: warmth hugs the horizon and fades with height.
    float lowBand = exp(-h * 4.5);
    float midBand = exp(-h * 2.2);
    float highBand = exp(-h * 1.1);

    vec3 twilight = emberColor * towardSun * lowBand;
    twilight += orangeColor * mix(0.25, 1.0, towardSun) * lowBand * 0.75;
    twilight += pinkColor * mix(0.30, 1.0, towardSun) * midBand * 0.55;
    twilight += violetColor * highBand * 0.22;

    // Opposite the sun the sky keeps a cool counter-glow, as it does in life.
    float away = pow(max(-dot(dir, toSun), 0.0), 1.5);
    twilight += vec3(0.30, 0.28, 0.45) * away * midBand * 0.35;

    // Everything below the horizon is looking at the ground. Without this the
    // glow mirrors under the horizon and the sun appears to shine through the
    // earth once it has set.
    float aboveHorizon = smoothstep(-0.06, 0.03, dir.y);

    vec3 color = base + twilight * duskStrength * aboveHorizon;

    // A soft bloom right around the sun's disc, tinted by how low it sits.
    vec3 haloTint = mix(haloDay, haloDusk, duskStrength);
    float haloStrength = (0.35 + 0.45 * dayFactor);
    if (atmospherePreset == 3) {
        haloStrength *= 0.18;
    } else if (atmospherePreset == 2) {
        haloStrength *= 1.20;
    }
    color += haloTint * pow(sunAmount, 48.0) * haloStrength * aboveHorizon;

    // Ground haze below the horizon, so the sky does not cut hard against
    // terrain and no light leaks from under the world.
    float below = clamp(-dir.y * 4.0, 0.0, 1.0);
    color = mix(color, base * 0.55, below);

    // --- Volumetric clouds -------------------------------------------------
    // The layer is a real slab between cloudBaseHeight and the layer top. The
    // ray is marched through it, accumulating extinction, and a short secondary
    // march toward the sun gives self shadowing, so thick bodies darken from
    // below while thin edges stay translucent.
    if (cloudsEnabled && dir.y > 0.015) {
        float slabTop = cloudBaseHeight + cloudLayerDepth;
        float tEnter = (cloudBaseHeight - cameraWorldPos.y) / dir.y;
        float tExit = (slabTop - cameraWorldPos.y) / dir.y;
        float t0 = max(min(tEnter, tExit), 0.0);
        float t1 = max(tEnter, tExit);
        // Grazing rays would otherwise march for kilometres.
        t1 = min(t1, t0 + cloudLayerDepth * 10.0);

        if (t1 > t0) {
            vec2 windWorld = vec2(cloudTime * cloudSpeed * 6.0, -cloudTime * cloudSpeed * 2.5);
            vec2 evolve = vec2(
                    sin(cloudDayTime * 2.324 + cloudTime * 0.017),
                    cos(cloudDayTime * 1.447 - cloudTime * 0.013)) * 96.0;

            // Cloud type varies over very large distances, so one sample per ray
            // is enough and keeps the inner loop cheap.
            vec3 midPoint = cameraWorldPos + dir * mix(t0, t1, 0.5);
            float regime = fbm((midPoint.xz + evolve * 0.35 + windWorld * 0.08) * 0.00075);

            const int STEPS = 16;
            float dt = (t1 - t0) / float(STEPS);
            // Jitter the start so the steps do not band into visible shells.
            float jitter = hash12(gl_FragCoord.xy + vec2(cloudTime));
            vec3 pos = cameraWorldPos + dir * (t0 + dt * jitter);

            float sigma = mix(0.055, 0.140, cloudOpacity);
            if (atmospherePreset == 2) {
                sigma *= 1.8;
            }
            float lightStep = cloudLayerDepth * 0.30;

            // Forward scattering keeps a bright rim where the sun sits behind a cloud.
            float cosTheta = dot(dir, toSun);
            float g = 0.42;
            float hg = (1.0 - g * g) / (12.566 * pow(max(1.0 + g * g - 2.0 * g * cosTheta, 0.0001), 1.5));
            float phase = 0.55 + 9.0 * hg;

            vec3 sunTint = mix(vec3(1.02, 0.99, 0.94), vec3(1.0, 0.60, 0.30), duskStrength);
            vec3 skyTint = mix(vec3(0.10, 0.13, 0.20), vec3(0.62, 0.72, 0.88), dayFactor);
            if (atmospherePreset == 2) {
                sunTint = mix(vec3(1.0, 0.92, 0.60), vec3(0.98, 0.72, 0.34), duskStrength);
                skyTint = mix(vec3(0.16, 0.13, 0.08), vec3(0.70, 0.62, 0.36), dayFactor);
            }
            float sunPower = mix(0.22, 1.0, dayFactor);

            float transmittance = 1.0;
            vec3 scattered = vec3(0.0);

            for (int i = 0; i < STEPS; i++) {
                float d = cloudDensity(pos, windWorld, evolve, regime);
                if (d > 0.002) {
                    // Optical depth toward the sun for self shadowing.
                    float lightDepth = 0.0;
                    vec3 lp = pos;
                    for (int j = 0; j < 3; j++) {
                        lp += toSun * lightStep;
                        lightDepth += cloudDensityCoarse(lp, windWorld, evolve, regime) * lightStep;
                    }
                    float lightTrans = exp(-lightDepth * sigma * 1.35);

                    // Powder term: thin wisps scatter less than solid interiors.
                    float powder = 1.0 - exp(-d * 7.0);

                    vec3 luminance = sunTint * (lightTrans * phase * sunPower)
                            + skyTint * (0.30 + 0.55 * (1.0 - d));
                    luminance *= mix(0.65, 1.0, powder);

                    float stepTrans = exp(-d * sigma * dt);
                    scattered += transmittance * (1.0 - stepTrans) * luminance;
                    transmittance *= stepTrans;
                    if (transmittance < 0.02) {
                        break;
                    }
                }
                pos += dir * dt;
            }

            float horizonFade = smoothstep(0.015, 0.24, dir.y);
            vec3 clouded = color * transmittance + scattered;
            color = mix(color, clouded, horizonFade);
        }
    }

    outColor = vec4(color, 1.0);
}
