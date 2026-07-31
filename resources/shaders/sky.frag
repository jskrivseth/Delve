#version 330 core

in vec2 fragOffset;

uniform vec3 bodyColor;
uniform float bodyAlpha;
/** Disc radius as a fraction of the quad, leaving the rest for the glow. */
uniform float discRadius;
/** 0 and TAU are a new moon, PI a full moon. Unused for the sun. */
uniform float phaseAngle;
uniform bool showPhase;
uniform float glowStrength;
/** Extra rays radiating from the disc, for the sun only. */
uniform bool showRays;
uniform float rayTime;

out vec4 outColor;

void main() {
    float d = length(fragOffset);
    if (d > 1.0) {
        discard;
    }

    // Solid core, then a wide falloff that fades into the sky rather than
    // ending at a hard quad edge.
    float core = 1.0 - smoothstep(discRadius * 0.86, discRadius, d);

    float glowFalloff = clamp((d - discRadius) / max(1.0 - discRadius, 0.001), 0.0, 1.0);
    float glow = pow(1.0 - glowFalloff, 3.0) * glowStrength;

    float rays = 0.0;
    if (showRays && d > discRadius) {
        float angle = atan(fragOffset.y, fragOffset.x);
        // Two overlapping frequencies keep the spokes from looking mechanical.
        float spokes = 0.55 + 0.45 * sin(angle * 6.0 + rayTime * 0.35);
        spokes *= 0.65 + 0.35 * sin(angle * 11.0 - rayTime * 0.21);
        rays = pow(1.0 - glowFalloff, 4.0) * spokes * glowStrength * 0.75;
    }

    float lit = 1.0;
    if (showPhase) {
        // The terminator is an ellipse whose width tracks the phase, so the lit
        // limb sweeps across the disc over the cycle.
        float ca = cos(phaseAngle);
        float terminator = ca * sqrt(max(0.0, 1.0 - fragOffset.y * fragOffset.y));
        float side = phaseAngle < 3.14159265 ? 1.0 : -1.0;
        lit = smoothstep(-0.06, 0.06, side * (fragOffset.x - terminator));
        // Keep the dark limb faintly visible as earthshine.
        lit = mix(0.08, 1.0, lit);
    }

    float intensity = core * lit + glow + rays;
    if (intensity <= 0.002) {
        discard;
    }

    outColor = vec4(bodyColor * intensity, clamp(intensity, 0.0, 1.0) * bodyAlpha);
}
