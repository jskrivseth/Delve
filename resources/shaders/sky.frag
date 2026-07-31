#version 330 core

in vec2 fragOffset;

uniform vec3 bodyColor;
uniform float bodyAlpha;
/** 0 and TAU are a new moon, PI a full moon. Unused for the sun. */
uniform float phaseAngle;
uniform bool showPhase;
/** Soft halo width beyond the disc edge. */
uniform float glow;

out vec4 outColor;

void main() {
    float d = length(fragOffset);
    if (d > 1.0) {
        discard;
    }

    float disc = 1.0 - smoothstep(0.72, 0.80, d);
    float halo = (1.0 - smoothstep(0.0, 1.0, d)) * glow;

    float lit = 1.0;
    if (showPhase) {
        // The terminator is an ellipse whose width tracks the phase, so the lit
        // limb sweeps across the disc over the cycle.
        float ca = cos(phaseAngle);
        float terminator = ca * sqrt(max(0.0, 1.0 - fragOffset.y * fragOffset.y));
        float side = phaseAngle < 3.14159265 ? 1.0 : -1.0;
        lit = smoothstep(-0.05, 0.05, side * (fragOffset.x - terminator));
        // Keep the dark limb faintly visible as earthshine.
        lit = mix(0.10, 1.0, lit);
    }

    float intensity = disc * lit + halo;
    if (intensity <= 0.001) {
        discard;
    }

    outColor = vec4(bodyColor * intensity, clamp(intensity, 0.0, 1.0) * bodyAlpha);
}
