// The single definition of the cloud field.
//
// The sky (skygradient.frag), the god-ray shafts (godrays.frag) and the terrain
// cloud shadows (chunk.frag) all describe the same clouds from different vantage
// points, so they must agree exactly on where cloud is. These functions used to be
// copied into all three files, and they had already drifted apart -- the sky
// sampled the clear-sky patch field with fbm() while the shadow and shaft shaders
// used fbm3(), so shadows were cast from a different sky than the one on screen.
//
// Anything describing cloud *shape* belongs here. Anything describing how a
// particular pass integrates or lights that shape stays in that pass's shader.

#include "/shaders/lib/noise.glsl"

uniform int   atmospherePreset;   // 0=Earth, 1=Mars, 2=Venus, 3=Triton
uniform float cloudCoverage;      // noise threshold: LOWER means MORE cloud
uniform float cloudSharpness;
uniform float cloudOpacity;
uniform float cloudBaseHeight;
uniform float cloudLayerDepth;
uniform float cloudTime;
uniform float cloudSpeed;
uniform float cloudDayTime;
uniform float cloudWindAngle;
uniform float cloudWindSpeed;
uniform float cloudTurbulence;
uniform int   cloudDetailLevel;   // 0 flat, 1 low, 2 med, 3 high

/**
 * Profiling ablation mask. Zero during normal play. The performance overlay
 * switches one bit on at a time and measures the drop in GPU pass time, which is
 * the only way to attribute cost to an individual function: GLSL has no way to
 * time a call, and a shader cannot report anything but its pixel.
 *
 * These must stay uniform-driven rather than #ifdef'd. A compile-time constant
 * would let the driver delete the surrounding code, so the measurement would
 * include dead-code elimination and register-pressure changes that never happen
 * in the real shader.
 */
uniform int profileAblate;

#define AB_COVERAGE  1    // large-scale coverage fbm (4 octaves)
#define AB_MASK_HI   2    // second detail fbm3 in the coverage mask
#define AB_DRAPE     4    // per-column drape base height
#define AB_LUMPS     8    // underside bump noise
#define AB_BILLOW   16    // 3D billow erosion
#define AB_LIGHT    32    // in-cloud light march
#define AB_SHADOWS  64    // terrain cloud shadows
#define AB_STEPS   128    // half the march steps

bool ablated(int flag) {
    return (profileAblate & flag) != 0;
}

/**
 * Regional coverage threshold. This is a very large-scale field (~7.7 km), so it
 * is evaluated once per ray at the march midpoint and passed down, rather than
 * recomputed at every sample -- it was four of the ten noise octaves in every
 * single density evaluation. `regime` is already approximated the same way at an
 * even finer scale.
 */
float cloudCoverageAt(vec2 cloudXZ, vec2 windWorld, vec2 evolve) {
    if (ablated(AB_COVERAGE)) return clamp(cloudCoverage, 0.06, 0.92);
    vec2 patchXZ = (cloudXZ + windWorld * 0.30 + evolve * 0.12) * 0.000130;
    return clamp(cloudCoverage + (fbm(patchXZ) - 0.5) * 0.72, 0.06, 0.92);
}

/** Horizontal coverage of the cloud field, i.e. how much sky a column fills. */
float cloudMaskAt(vec2 cloudXZ, vec2 windWorld, vec2 evolve, float regime, float ec) {
    float n0 = fbm3((cloudXZ + windWorld + evolve) * 0.0022);
    float n1 = ablated(AB_MASK_HI) ? n0
             : fbm3((cloudXZ - windWorld * 0.65 + evolve.yx * 0.7) * 0.0041);
    float n  = mix(n0, n1, 0.40);
    if (atmospherePreset == 2) {
        float deck   = smoothstep(ec - 0.18, ec + cloudSharpness * 2.8, mix(n0, n1, 0.25));
        float billow = smoothstep(ec - 0.06, ec + cloudSharpness * 1.6, n0);
        return clamp(mix(deck, billow, 0.35), 0.0, 1.0);
    }

    float cumulusBase  = smoothstep(ec - cloudSharpness, ec + cloudSharpness, n);
    float cumulusCrisp = smoothstep(ec - cloudSharpness * 0.30,
            ec + cloudSharpness * 0.20, n + (n1 - 0.5) * 0.14);
    float cumulus = mix(cumulusBase, cumulusCrisp, 0.55) * (1.0 - smoothstep(0.55, 0.88, regime));
    float stratus = smoothstep(ec - 0.12, ec + 0.18, n0) * smoothstep(0.42, 0.95, regime);
    float cirrus  = smoothstep(ec - 0.30, ec - 0.08, n1)
            * (1.0 - smoothstep(0.28, 0.70, regime)) * 0.35;
    return clamp(max(cumulus, stratus * 0.92) + cirrus, 0.0, 1.0);
}

/**
 * Per-column effective base height. On high detail, a large-scale drape pulls
 * clouds down toward the terrain during turbulent weather; the ~0.001100 scale
 * gives km-wide hanging regions, and the 130 floor keeps them clear of mountain
 * tops (terrain maxes out at 124). On low detail the layer stays a flat slab,
 * which is also markedly cheaper: the ray march then spans the layer depth
 * instead of the whole drape envelope.
 */
float cloudDrapeBase(vec2 xz, vec2 windWorld, vec2 evolve, float baseH) {
    if (cloudDetailLevel <= 0 || ablated(AB_DRAPE)) {
        return baseH;
    }
    float maxDrape = 700.0 * cloudTurbulence;
    if (cloudDetailLevel == 1) {
        maxDrape *= 0.50;
    }
    vec2  drapeXZ  = (xz + windWorld * 0.22 + evolve * 0.08) * 0.001100;
    return max(baseH - valueNoise(drapeXZ) * maxDrape, 130.0);
}

/** Lower bound of the slab a ray must cover for this layer. */
float cloudSlabBottom(float baseH) {
    if (cloudDetailLevel <= 0) {
        return baseH;
    }
    float maxDrape = 700.0 * cloudTurbulence;
    if (cloudDetailLevel == 1) {
        maxDrape *= 0.50;
    }
    return max(baseH - maxDrape, 130.0);
}

float cloudDensityCoarseShared(vec3 p, vec2 windWorld, vec2 evolve, float regime,
                               float baseH, float layerD, float ec, out float hN) {
    float effectiveBase = cloudDrapeBase(p.xz, windWorld, evolve, baseH);
    float span = layerD + (baseH - effectiveBase);
    hN = (p.y - effectiveBase) / max(span, 1.0);
    if (hN < 0.0 || hN > 1.0) return 0.0;

    vec2  shear = vec2(hN * 34.0, -hN * 22.0);
    float mask  = cloudMaskAt(p.xz + shear, windWorld, evolve, regime, ec);
    if (mask <= 0.002) return 0.0;

    if (atmospherePreset == 2) {
        return mask * smoothstep(0.0, 0.10, hN) * (1.0 - smoothstep(0.72, 1.0, hN));
    }

    // The underside must vary *within* a single cloud or it reads as a flat
    // plate from below. Note this cannot be driven by `mask`: that comes out of
    // a smoothstep and saturates to 1.0 across cloud interiors, so it is
    // constant exactly where the variation needs to be. A dedicated noise at
    // roughly one-cloud wavelength (~310 blocks), sampled at the same
    // wind-sheared coordinate as the mask, gives a bumpy base that undulates
    // with the cloud's own silhouette rather than as an independent field --
    // and a second, finer octave keeps a single wavelength from reading as a
    // uniform horizontal cut once the shape it rides on gets large.
    float lumps  = ablated(AB_LUMPS) ? 0.5
                 : fbm3((p.xz + shear + windWorld * 0.12 + evolve * 0.05) * 0.0032);
    float lumpsFine = ablated(AB_LUMPS) ? 0.5
                 : valueNoise((p.xz + shear * 0.6 + windWorld * 0.18 + evolve * 0.08) * 0.0098);
    float bottom = lumps * 0.26 + lumpsFine * 0.16;
    // Dense cores tower; wispy edges stay thin sheets.
    float thick  = mix(0.26, 0.60, mask);
    float top    = min(bottom + thick, 1.0);
    float fadeIn = max(top - 0.28, bottom + 0.10);

    // A wider fade-in than a single-band cutoff: real undersides thin out into
    // wisps over a good fraction of the layer rather than snapping from clear
    // to opaque in a sliver of it, which is what reads as a flat, cut-off base.
    float profile = smoothstep(bottom, bottom + 0.18, hN)
                  * (1.0 - smoothstep(fadeIn, top, hN));
    if (profile <= 0.002) return 0.0;

    return mask * profile;
}

float cloudDensityCoarse(vec3 p, vec2 windWorld, vec2 evolve, float regime,
                         float baseH, float layerD, float ec) {
    float hN;
    return cloudDensityCoarseShared(p, windWorld, evolve, regime, baseH, layerD, ec, hN);
}

/** Coarse density with 3D erosion applied, for the visible sky pass.
 *
 * `lodFade` is 0 near the camera and 1 beyond a distance threshold marchLayer
 * decides -- billow is per-sample the single costliest term (a full 3D
 * fbm), and its detail is exactly the kind of thing that stops being visible,
 * let alone worth its cost, once a cloud is thousands of blocks away and
 * covers a handful of pixels. Skipping it there, rather than only skipping it
 * for the whole frame in low-detail mode, keeps near clouds fully detailed
 * while distant and horizon-band cloud stops paying for detail nothing can
 * see.
 *
 * `layerDetail` (0..1) is a second, per-layer knob on top of lodFade: the sky
 * pass's 3 stacked decks aren't equally worth the same erosion budget, so it
 * scales down both the billow octave count and its strength for layers asked
 * to look smoother (see the layerDetail arguments at marchLayer's 3 call
 * sites in skygradient.frag).
 */
float cloudDensity(vec3 p, vec2 windWorld, vec2 evolve, float regime,
                   float baseH, float layerD, float ec, float lodFade,
                   float layerDetail, out float billowAO) {
    billowAO = 0.0;
    float hN;
    float coarse = cloudDensityCoarseShared(
            p, windWorld, evolve, regime, baseH, layerD, ec, hN);
    if (coarse <= 0.005) return 0.0;
    // 3D erosion is the detail-tier's whole reason to cost more than the coarse
    // shape; flat/low detail should never pay for it, and neither should cloud
    // far enough away that the erosion could not read on screen.
    if (cloudDetailLevel <= 1 || ablated(AB_BILLOW) || lodFade >= 0.999) return coarse;

    hN = clamp(hN, 0.0, 1.0);

    // Billow in three dimensions is what produces cauliflower lobes. A 2D noise
    // -- even one sheared by height -- only ever yields an extruded silhouette,
    // because every column of the cloud gets the same horizontal pattern.
    vec3 ep = vec3(p.x + windWorld.x * 0.22, p.y * 1.35, p.z + windWorld.y * 0.22);
    // A third octave on the highest quality tier adds a finer wrinkle pass on
    // top of the base cauliflower lobes -- visible up close where the extra
    // cost is actually worth paying, and skipped at medium detail (and
    // already skipped entirely below it, and beyond lodFade, by the early
    // returns above). `layerDetail` lets the sky pass ask for less of this
    // per layer -- L0 (nearest, filling the most of the frame) gets the full
    // budget, while L1 and further L2 get progressively fewer octaves and a
    // softer strength so they read as smoother, less-eroded decks instead of
    // spending the same per-sample cost on detail that reads as noise at
    // their distance/scale.
    int billowOctaves = (cloudDetailLevel >= 3 && layerDetail >= 0.85) ? 3 : 2;
    if (layerDetail < 0.55) billowOctaves = 1;
    float billow = billow3(ep * 0.0125, billowOctaves);

    // Remap instead of subtracting: erosion bites hardest where the cloud is
    // already thin, so edges break up into lobes while cores stay solid. A flat
    // subtraction lowers density everywhere and just fogs the whole cloud.
    // Fading strength to zero rather than switching functions outright means
    // the near/far transition dissolves smoothly instead of popping.
    float detailScale = cloudDetailLevel == 2 ? 0.72 : 1.0;
    float strength = mix(0.34, 0.74, hN) * (0.60 + 0.40 * cloudTurbulence)
                   * (1.0 - lodFade) * detailScale * layerDetail;
    float floorV = billow * strength;
    // How deep this sample sits in one of the billow's own eroded creases,
    // normalized 0..1 -- the crevices between cauliflower lobes are where real
    // clouds show their grey self-shadowing, not just at the cloud's overall
    // silhouette edge.
    billowAO = clamp(floorV / max(strength, 0.001), 0.0, 1.0);
    return clamp((coarse - floorV) / max(1.0 - floorV, 0.05), 0.0, 1.0);
}

vec2 windRotate(vec2 w, float angle) {
    float c = cos(angle), s = sin(angle);
    return vec2(w.x * c - w.y * s, w.x * s + w.y * c);
}

vec2 layerEvolve(float phaseOffset) {
    float d = cloudDayTime + phaseOffset;
    vec2 e = vec2(
        sin(d * 1.9472) * 280.0 + sin(d * 0.6318) * 110.0 + sin(d * 3.1415) * 60.0,
        cos(d * 1.4142) * 280.0 + cos(d * 0.8090) * 110.0 - sin(d * 2.7183) * 60.0);
    return e + vec2(sin(cloudTime * 0.012), cos(cloudTime * 0.009)) * 18.0;
}
