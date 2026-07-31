#version 330 core

in vec3 fragViewRay;

uniform vec3 sunDirection;
uniform vec3 moonDirection;
/** 0 at night, 1 in full day. */
uniform float dayFactor;
/** Peaks while the sun sits on the horizon. */
uniform float duskFactor;
uniform float moonGlow;

out vec4 outColor;

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
    vec3 dayColor = mix(dayHorizon, dayZenith, pow(h, 0.55));

    // --- Night -------------------------------------------------------------
    vec3 nightZenith = vec3(0.015, 0.025, 0.075);
    vec3 nightHorizon = vec3(0.055, 0.075, 0.15);
    vec3 nightColor = mix(nightHorizon, nightZenith, pow(h, 0.6));
    // A bright moon lifts the sky around it.
    float moonAmount = max(dot(dir, -normalize(moonDirection)), 0.0);
    nightColor += vec3(0.05, 0.06, 0.10) * moonGlow * pow(moonAmount, 6.0);

    vec3 base = mix(nightColor, dayColor, dayFactor);

    // --- Sunset and sunrise ------------------------------------------------
    // Real twilight runs deep orange at the sun, through pink, into blue with
    // height. A single red tint over the whole sky reads as blood rather than
    // atmosphere, so the bands are layered by height and by angle to the sun.
    vec3 emberColor  = vec3(0.95, 0.32, 0.10);   // hottest, right at the sun
    vec3 orangeColor = vec3(0.98, 0.55, 0.22);
    vec3 pinkColor   = vec3(0.93, 0.62, 0.66);
    vec3 violetColor = vec3(0.45, 0.38, 0.62);

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

    vec3 color = base + twilight * duskFactor;

    // A soft bloom right around the sun's disc, tinted by how low it sits.
    vec3 haloTint = mix(vec3(1.0, 0.96, 0.85), vec3(1.0, 0.55, 0.25), duskFactor);
    color += haloTint * pow(sunAmount, 48.0) * (0.35 + 0.45 * dayFactor);

    // Ground haze so the horizon does not cut hard against terrain.
    float below = clamp(-dir.y * 3.0, 0.0, 1.0);
    color = mix(color, color * 0.72, below);

    outColor = vec4(color, 1.0);
}
