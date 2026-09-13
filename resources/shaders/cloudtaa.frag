#version 330 core

// Temporal accumulation for the cloud march (TAA step reduction).
//
// The sky pass this frame was marched with only a fraction of its usual step
// budget, with the march phase rotated per frame, so this frame alone is
// grainy -- but its error is uncorrelated with last frame's, and the pair
// averages toward the full-march image. RGB carries the (tonemapped) sky and
// cloud composite, A carries cloudAlpha, so the god-ray occlusion read from
// this buffer stabilises with everything else.
//
// Anti-ghosting is deliberately blunt: the history is hard-clipped per-channel
// to the min/max box of the current frame's 3x3 neighbourhood, so a stale
// edge cannot survive carrying a colour the current frame disagrees with, and
// the motion scalar drops the history wholesale while the camera moves fast
// enough for screen-space history to be the wrong buffer to trust. No
// reprojection, no velocity buffer -- that's the accepted trade.

in vec2 fragUv;

out vec4 outColor;

uniform sampler2D curTex;     // this frame's sky pass, RGBA16F
uniform sampler2D histTex;    // previous accumulated frame, RGBA16F
uniform vec2  texel;          // 1 / sky-buffer size
uniform float staticAlpha;    // base blend toward current at rest
uniform float motion;         // 0..1 history-rejection scalar
uniform float reset;          // 1 discards the history entirely

void main() {
    vec4 cur = texture(curTex, fragUv);
    if (reset > 0.5) {
        outColor = cur;
        return;
    }

    // Neighbourhood extent of the current frame, centre pixel included.
    vec3 lo = cur.rgb, hi = cur.rgb;
    float alo = cur.a, ahi = cur.a;
    for (int oy = -1; oy <= 1; oy++) {
        for (int ox = -1; ox <= 1; ox++) {
            vec4 s = texture(curTex, fragUv + vec2(ox, oy) * texel);
            lo = min(lo, s.rgb);
            hi = max(hi, s.rgb);
            alo = min(alo, s.a);
            ahi = max(ahi, s.a);
        }
    }

    vec4 hist = texture(histTex, fragUv);
    vec3 clipped   = clamp(hist.rgb, lo, hi);
    float clipA    = clamp(hist.a, alo, ahi);

    float alpha = min(staticAlpha + motion, 1.0);
    outColor = vec4(mix(clipped, cur.rgb, alpha), mix(clipA, cur.a, alpha));
}
