#version 330 core

in vec2 fragOffset;

uniform sampler2D bodyTexture;
uniform bool useTexture;
/** Sub-rectangle of the texture to sample, for the moon's phase grid. */
uniform vec4 uvRect;

uniform vec3 bodyColor;
uniform float bodyAlpha;
/** Half extent of the disc within the quad; the rest carries the glow. */
uniform float discHalf;
uniform float glowStrength;
uniform bool showRays;
uniform float rayTime;
/** Round bodies use a radial edge; square ones use a box edge. */
uniform bool roundBody;

out vec4 outColor;

void main() {
    // The sun stays square to match the blocky world; the moon is round.
    vec2 a = abs(fragOffset);
    float edge = roundBody ? length(fragOffset) : max(a.x, a.y);

    vec4 disc = vec4(0.0);
    if (edge <= discHalf) {
        vec2 local = (fragOffset / discHalf) * 0.5 + 0.5;
        local = clamp(local, 0.0, 1.0);
        if (useTexture) {
            vec2 uv = uvRect.xy + local * uvRect.zw;
            disc = texture(bodyTexture, uv);
        } else {
            disc = vec4(1.0);
        }
        disc.rgb *= bodyColor;
        // Soften the rim so a round moon does not show stair-stepped edges.
        if (roundBody) {
            disc *= 1.0 - smoothstep(discHalf * 0.88, discHalf, edge);
        }
    }

    // Glow measured from the body's own edge outward, so it hugs the shape
    // rather than fading in a circle around a square.
    float glowSpan = max(1.0 - discHalf, 0.001);
    float t = clamp((edge - discHalf) / glowSpan, 0.0, 1.0);
    float glow = pow(1.0 - t, 3.0) * glowStrength;

    float rays = 0.0;
    if (showRays && edge > discHalf) {
        float angle = atan(fragOffset.y, fragOffset.x);
        // Two overlapping frequencies keep the spokes from looking mechanical.
        float spokes = 0.55 + 0.45 * sin(angle * 8.0 + rayTime * 0.30);
        spokes *= 0.65 + 0.35 * sin(angle * 13.0 - rayTime * 0.19);
        rays = pow(1.0 - t, 4.0) * spokes * glowStrength * 0.7;
    }

    float halo = glow + rays;
    vec3 rgb = disc.rgb + bodyColor * halo;
    float alpha = max(disc.a, clamp(halo, 0.0, 1.0));
    if (alpha <= 0.003) {
        discard;
    }

    outColor = vec4(rgb, alpha * bodyAlpha);
}
