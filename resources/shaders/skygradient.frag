#version 330 core

#include "/shaders/lib/clouds.glsl"

out vec4 outColor;

uniform float dayFactor;
uniform float duskFactor;
uniform float moonGlow;
uniform vec3 cameraWorldPos;
uniform bool cloudsEnabled;

in vec3 fragViewRay;

uniform vec3 sunDirection;
uniform vec3 moonDirection;
uniform float cloudShadowStrength;
// Continuous storm intensity [0,1], derived from Weather's coverage noise --
// 0 across every normal condition (sunny through overcast), ramping up only
// within the dedicated STORM band. Gates how dark L1 is allowed to get: that
// layer's own extra self-darkening otherwise made every heavy-cloud day look
// like a storm was rolling in, even when the weather was merely overcast.
uniform float stormFactor;
uniform int cloudMarchSteps;

// Distance along the ray, in world units, over which the visible march fades
// out its 3D erosion detail (see cloudDensity's lodFade). Chosen so a cloud
// passing close overhead keeps its cauliflower detail while the same detail
// stops being paid for once clouds are far enough away, or far enough around
// toward the horizon, that a screen pixel spans many lattice cells of it.
const float CLOUD_DETAIL_NEAR = 1600.0;
const float CLOUD_DETAIL_FAR  = 4200.0;

/**
 * Coarse transmittance of a single layer along the sun direction, sampled
 * from a point below it. Each layer's own in-cloud light march only ever
 * samples its own density, so a thick deck overhead previously never dimmed
 * the sunlight reaching a deck beneath it -- every layer looked equally
 * sunlit regardless of what was stacked above it. This gives a cheap (one
 * sample, evaluated once per pixel rather than per march step) correction
 * factor to fold into a lower layer's sunPower.
 */
float overheadShadow(vec3 origin, vec3 toSun, float baseH, float layerD,
                      vec2 windWorld, vec2 evolve, float regime, float ec, float sigma) {
    if (toSun.y <= 0.02) return 1.0;
    float tEnter = (baseH - origin.y) / toSun.y;
    float tExit  = (baseH + layerD - origin.y) / toSun.y;
    float t0 = max(min(tEnter, tExit), 0.0);
    float t1 = max(tEnter, tExit);
    if (t1 <= t0) return 1.0;
    vec3 mid = origin + toSun * ((t0 + t1) * 0.5);
    float depth = cloudDensityCoarse(mid, windWorld, evolve, regime, baseH, layerD, ec) * (t1 - t0);
    return exp(-depth * sigma * 1.35);
}

void marchLayer(vec3 dir, vec3 toSun, float baseH, float layerD,
                vec2 wind, vec2 evolv, float sigmaScale, int steps,
                float phase, float sunPower, vec3 sunTint, vec3 skyTint,
                vec3 underglowDayTint, vec3 underglowDuskTint,
                float duskStrength, float horizonFade, float layerDetail,
                inout float transmittance, inout vec3 scattered) {
    if (dir.y <= 0.015 || transmittance < 0.02) return;

    // The drape is a function of XZ only, so probe it along this ray rather
    // than assuming the worst case. Two probes -- at the nominal-altitude
    // crossing and well beyond it -- bracket shallow rays, which sweep across
    // many columns with very different drape.
    float tProbe  = (baseH - cameraWorldPos.y) / dir.y;
    vec2  probeA  = cameraWorldPos.xz + dir.xz * max(tProbe, 0.0);
    vec2  probeB  = probeA + dir.xz * 1800.0;
    float rayBase = min(cloudDrapeBase(probeA, wind, evolv, baseH),
                        cloudDrapeBase(probeB, wind, evolv, baseH));

    float slabTop  = baseH + layerD;
    float slabSpan = slabTop - rayBase;
    if (slabSpan <= 0.0) return;

    float tEnter = (rayBase - cameraWorldPos.y) / dir.y;
    float tExit  = (slabTop - cameraWorldPos.y) / dir.y;
    float t0 = max(min(tEnter, tExit), 0.0);
    float t1 = max(tEnter, tExit);
    if (t1 <= t0) return;
    t1 = min(t1, t0 + 9000.0);

    vec3 midPoint = cameraWorldPos + dir * mix(t0, t1, 0.5);
    float regime  = fbm((midPoint.xz + evolv * 0.35 + wind * 0.08) * 0.00075);
    float ec      = cloudCoverageAt(midPoint.xz, wind, evolv);

    // Step by world distance along the ray instead of by a fraction of the
    // vertical slab, so spacing is uniform however shallow the ray is. Steps
    // grow geometrically since distant clouds cover progressively fewer pixels.
    // The first step is solved from the growth rate so that N steps span the
    // range exactly -- a fixed starting size either overshoots (wasting samples)
    // or falls short, truncating distant cloud.
    // Near the horizon this same shallow geometry drives marchLen (and so
    // nSteps) toward its upper bound just as horizonFade is fading the result
    // toward zero -- the full step budget was being spent on a band that gets
    // multiplied down to near-nothing on the way out. Cap the ceiling by
    // horizonFade so that band gets proportionally fewer steps instead.
    float marchLen = t1 - t0;
    float stepsCeil = max(float(steps), float(steps) * mix(0.30, 5.0, horizonFade));
    int   nSteps   = int(clamp(marchLen / 12.0, float(steps), stepsCeil));
    if (ablated(AB_STEPS)) nSteps = max(nSteps / 2, 2);
    float growth   = pow(4.0, 1.0 / max(float(nSteps), 1.0));
    float dt       = marchLen * (growth - 1.0) / (pow(growth, float(nSteps)) - 1.0);

    // Interleaved gradient noise. A white-noise hash gives per-pixel speckle and
    // a smooth ramp beats against the step pattern into moire; IGN is
    // low-discrepancy, so neighbouring pixels differ enough to break banding
    // without either artefact.
    float ign = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    float t = t0 + dt * ign;

    float sigma = mix(0.055, 0.140, cloudOpacity) * sigmaScale;
    if (atmospherePreset == 2) sigma *= 1.8;
    float lightStep = max(slabSpan, layerD) * 0.30;
    bool  doShadow  = sigmaScale >= 0.5 && !ablated(AB_LIGHT);

    for (int i = 0; i < nSteps; i++) {
        if (t >= t1) break;
        float stepNoise = fract(52.9829189 * fract(dot(
                gl_FragCoord.xy + vec2(float(i) * 19.73, float(i) * 7.31),
                vec2(0.06711056, 0.00583715))));
        vec3 pos = cameraWorldPos + dir * (t + (stepNoise - 0.5) * dt * 0.85);
        float lodFade = smoothstep(CLOUD_DETAIL_NEAR, CLOUD_DETAIL_FAR, t);
        float billowAO;
        float d = cloudDensity(pos, wind, evolv, regime, baseH, layerD, ec, lodFade, layerDetail, billowAO);
        if (d > 0.002) {
            float lightTrans = 1.0;
            if (doShadow) {
                // A single sample at the path's midpoint, weighted by the full
                // two-step path length, approximates the same optical depth as
                // sampling twice -- the in-cloud light march was one of the
                // costliest per-sample calls in the whole pass for a term that
                // only needs to be roughly right.
                vec3 lp = pos + toSun * (lightStep * 1.5);
                float lightDepth = cloudDensityCoarse(lp, wind, evolv, regime, baseH, layerD, ec)
                        * (lightStep * 2.0);
                lightTrans = exp(-lightDepth * sigma * 1.35);
            }
            float translucency = 1.0 - exp(-d * 7.0);
            // The ambient term should fall off with how deep this sample sits
            // in the cloud as seen from the camera, not with this sample's own
            // local density -- that conflated opacity with position and
            // double-penalized dense cores that the sun-facing term was
            // already dimming. `transmittance` (accumulated along this ray)
            // is exactly that depth cue and costs nothing extra to read.
            // Real clouds read as grey, not pure white, wherever they are
            // self-shadowed from the sun: a self-shadowed pocket is also
            // shadowed from most of the open sky that would otherwise light it
            // ambiently, so tying skyTint to lightTrans as well as sun-facing
            // pockets is what actually produces the shade variation.
            //
            // Radiance budget. The compositing weights below,
            // transmittance * (1 - stepTrans), telescope to exactly
            // (1 - transmittance_final), so `scattered` is a weighted AVERAGE
            // of this value, not a growing sum -- whatever peak `luminance`
            // reaches is roughly what the composited cloud reads as, however
            // many steps or layers were marched. That makes its absolute
            // scale the thing that has to be tuned, and it was tuned far too
            // hot: sun (1.02 * up to 1.6 phase) + sky (0.88 * 0.85) alone
            // peaked near 2.0 before the translucency boost. Every value past
            // the 0.82 soft knee is compressed by ROLLOFF^2/(over+ROLLOFF)^2
            // -- a ~43x contrast loss by an input of 1.8 -- so the entire lit
            // range 1.5..2.3 collapsed into 0.964..0.980, three code values
            // apart, and the clouds read as flat white with only the deepest
            // shadow cores escaping as blue patches. Scaling the direct and
            // ambient terms so a normally lit face lands just under the knee
            // is what restores the grey shading; the knee then only handles
            // the genuine near-sun rim it was meant for.
            const float SUN_GAIN = 0.60;
            vec3 luminance = sunTint * (lightTrans * phase * sunPower * SUN_GAIN)
                           + skyTint * (0.14 + 0.30 * transmittance) * mix(0.45, 1.0, lightTrans);
            // A thin, translucent sample lets more of that ambient/sun light
            // pass through it than a dense, optically thick one does -- the
            // cloud's 3D volume should read as a darker core with brighter,
            // more translucent edges, not the reverse.
            luminance *= mix(1.18, 0.78, translucency);
            // Ground-bounced ambient light still reaches a little way up into
            // a cloud's underside even where the sun above is fully blocked --
            // a pure self-shadow term left the whole underside crushed toward
            // black, which real cloud bottoms never quite do. In full
            // daylight this reads as a cool blue-grey (bounced skylight, not
            // the sky's own vivid blue); near sunrise and sunset it should
            // shift to the same saturated pink/ember/orange the sky's own
            // twilight bands show. Near sunrise and sunset it also softens the
            // inter-layer shadow "racing", since it fills back in some of what
            // that (separately dampened) overhead term carves out.
            //
            // Because it is added rather than modulated by lightTrans, this is
            // a floor on the darkest samples, and at 0.42 it was lifting a
            // self-shadowed core from ~0.20 to ~0.44 -- more than doubling the
            // dark end and erasing exactly the grey the clouds are supposed to
            // show. It contributes at most `underglowTint * this` to the
            // composite (the weights telescope to 1 - transmittance), so the
            // cure is purely its scalar magnitude, not its tint or where in
            // the loop it is applied. Daylight ground bounce is a weak fill,
            // so keep it small; dusk stays strong because there the sun term
            // has faded and this IS the light on the cloud bases.
            float hUnit = clamp((pos.y - baseH) / max(layerD, 1.0), 0.0, 1.0);
            float underglow = pow(1.0 - hUnit, 2.0) * mix(0.14, 0.72, duskStrength);
            vec3 underglowTint = mix(underglowDayTint, underglowDuskTint, duskStrength);
            luminance += underglowTint * underglow;
            // Cauliflower creases -- the pockets billow erosion just carved
            // out of the lobe -- read as grey in real clouds, not the same
            // white as the lobes around them. Darken toward the sample's own
            // grey (not a colour cast) proportional to how deep it sits in a
            // crease, so the effect follows the billow shape itself rather
            // than only the coarse silhouette lighting above.
            float billowGrey = dot(luminance, vec3(0.299, 0.587, 0.114));
            luminance = mix(luminance, vec3(billowGrey) * 0.65, billowAO * 0.75);
            float stepTrans = exp(-d * sigma * dt);
            scattered   += transmittance * (1.0 - stepTrans) * luminance;
            transmittance *= stepTrans;
            if (transmittance < 0.02) return;
        }
        t  += dt;
        dt *= growth;
    }
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
    // Three independent layers: near-to-far march so lower-layer transmittance
    // correctly dims higher layers. Heights oscillate on incommensurable
    // frequencies; turbulence amplitude grows with weather severity so stormy
    // skies have layers bouncing past each other while calm days stay stable.
    // Published in alpha for the god ray pass. Marching the cloud field a second
    // time there costs more than the entire rest of the frame, so the shafts read
    // the occlusion this march already resolved.
    float cloudAlpha = 0.0;

    float horizonFade = smoothstep(0.015, 0.24, dir.y);
    if (cloudsEnabled && horizonFade > 0.01) {
        vec2 windDir  = vec2(cos(cloudWindAngle), sin(cloudWindAngle));
        vec2 baseWind = windDir * (cloudDayTime * cloudSpeed * cloudWindSpeed * 3200.0);

        float ct   = cloudDayTime;
        float turb = cloudTurbulence;

        // L0 and L1 use opposite-sign fundamentals so one rises while the other falls.
        float baseL0 = cloudBaseHeight - 320.0
                     + sin(ct * 1.618)  * (85.0 + 130.0 * turb)
                     - sin(ct * 2.414 + 1.8) * 50.0 * turb;
        float baseL1 = cloudBaseHeight
                     - sin(ct * 1.414)  * (90.0 + 150.0 * turb)
                     + sin(ct * 0.618 + 0.7) * 45.0 * turb;
        float baseL2 = cloudBaseHeight + 540.0
                     + sin(ct * 0.943 + 2.3) * (65.0 + 100.0 * turb)
                     - sin(ct * 1.732 + 0.5) * 38.0 * turb;

        float depthL0 = cloudLayerDepth * 0.80;
        float depthL1 = cloudLayerDepth;
        float depthL2 = cloudLayerDepth * 0.28;

        vec2 windL0 = windRotate(baseWind * 0.78,  0.11);
        vec2 windL1 = baseWind;
        vec2 windL2 = windRotate(baseWind * 1.55, -0.16);

        vec2 evolL0 = layerEvolve(317.4);
        vec2 evolL1 = layerEvolve(0.0);
        vec2 evolL2 = layerEvolve(183.7);

        float cosTheta = dot(dir, toSun);
        float g  = 0.42;
        float hg = (1.0 - g * g) / (12.566 * pow(max(1.0 + g * g - 2.0 * g * cosTheta, 0.0001), 1.5));
        // The forward-scattering peak is shared by every layer along this ray:
        // when the sun sits behind two or three cloud layers at once, each one
        // independently applies this same near-peak multiplier to its own
        // contribution, so their bright cores stack rather than sharing a
        // single bounded budget. Capping it keeps the silver-lining look
        // without letting stacked layers blow the sun out to solid white.
        float phase = min(0.55 + 9.0 * hg, 1.6);

        vec3 sunTint = mix(vec3(1.02, 0.99, 0.94), vec3(1.0, 0.60, 0.30), duskStrength);
        vec3 skyTint = mix(vec3(0.10, 0.13, 0.20), vec3(0.62, 0.72, 0.88), dayFactor);
        if (atmospherePreset == 2) {
            sunTint = mix(vec3(1.0, 0.92, 0.60), vec3(0.98, 0.72, 0.34), duskStrength);
            skyTint = mix(vec3(0.16, 0.13, 0.08), vec3(0.70, 0.62, 0.36), dayFactor);
        }
        float sunPower = mix(0.22, 1.0, dayFactor);
        // Richer and more saturated than sunTint alone -- reusing the same
        // ember/orange/pink palette the sky's own twilight bands are built
        // from (already atmosphere-aware) so cloud undersides at sunset pick
        // up that pink/orange/red character instead of just a warm white.
        vec3 underglowDuskTint = mix(orangeColor, pinkColor, 0.45);
        // Full daylight underglow shouldn't be the sky's own vivid, fairly
        // saturated blue -- real cloud undersides in full sun pick up
        // scattered skylight bounced back up through the surrounding air,
        // which reads as a cooler, desaturated blue-grey rather than a bright
        // blue. Blending skyTint partway toward its own luminance gives that
        // grey quality while staying recognisably cool/blue.
        float skyLuma = dot(skyTint, vec3(0.299, 0.587, 0.114));
        vec3 underglowDayTint = mix(skyTint, vec3(skyLuma), 0.45) * 0.90;



        // L0 (lowest) and L1 (mid) should see less direct sun wherever the
        // deck(s) above them are already dense -- baseL2 sits highest and
        // baseL1 sits above baseL0, so the sun reaches L2 first, then
        // whatever L2 let through reaches L1, then whatever both let through
        // reaches L0. Each layer's own light march only ever measured its own
        // density, so a thick upper deck previously left the ones beneath it
        // just as bright as if nothing were overhead at all.
        //
        // This must be sampled at a position that varies per screen ray, not
        // just at cameraWorldPos: a single shared sample meant the whole sky's
        // brightness depended on one noise value at the camera's own spot, so
        // moving even a couple of chunks and crossing that one sample across a
        // noise gradient popped every cloud in view brighter or darker at
        // once, instead of the shading varying smoothly across the sky the
        // way real light does.
        float sigmaBase = mix(0.055, 0.140, cloudOpacity);
        if (atmospherePreset == 2) sigmaBase *= 1.8;
        float rayDirY = max(dir.y, 0.05);
        vec3 approxL0Entry = cameraWorldPos + dir * clamp((baseL0 - cameraWorldPos.y) / rayDirY, 0.0, 6000.0);
        vec3 approxL1Entry = cameraWorldPos + dir * clamp((baseL1 - cameraWorldPos.y) / rayDirY, 0.0, 6000.0);
        float regimeOverhead = fbm((approxL1Entry.xz + evolL1 * 0.35 + windL1 * 0.08) * 0.00075);
        float ecL2 = cloudCoverageAt(approxL1Entry.xz, windL2, evolL2);
        float ecL1 = cloudCoverageAt(approxL0Entry.xz, windL1, evolL1);
        float shadowFromL2 = overheadShadow(approxL1Entry, toSun, baseL2, depthL2,
                windL2, evolL2, regimeOverhead, ecL2, sigmaBase * 0.30);
        float shadowFromL1 = overheadShadow(approxL0Entry, toSun, baseL1, depthL1,
                windL1, evolL1, regimeOverhead, ecL1, sigmaBase);
        // Near the horizon, toSun.y shrinks toward zero and the overhead-shadow
        // ray (which divides by it) becomes very sensitive to small changes in
        // sun angle -- the shadow position sweeps rapidly across the deck below,
        // reading as the layers' shadows "racing" each other through sunrise
        // and sunset. Fading the effect out as duskStrength climbs keeps it
        // stable exactly where it would otherwise be least stable; the
        // underglow added below fills in the brightness this leaves behind.
        shadowFromL2 = mix(shadowFromL2, 1.0, duskStrength);
        shadowFromL1 = mix(shadowFromL1, 1.0, duskStrength);

        // Ground-bounced ambient light: only L0 sits close enough to the
        // ground to pick that up "quite a bit"; L1 and L2 are progressively
        // farther from it (and partly screened by L0 itself), so their share
        // of it should be much smaller, not equal. L1 also keeps its own
        // dedicated extra darkening on top of its overhead shadow so it reads
        // as the darkest band in an overcast stack.
        // L0 sits under both decks, but should only take a fraction of that
        // combined shadow rather than the full product -- some sun still
        // scatters in sideways past the edges of whatever's overhead, plus
        // L0's own ground-bounce (boosted above) fills back in some of what
        // direct shadow removes.
        float shadowL0 = mix(shadowFromL2 * shadowFromL1, 1.0, 0.62);
        // L1 gets the same partial-shadow treatment as L0 -- straight
        // shadowFromL2 was reading as too dark on its own, before even adding
        // the dedicated extra darkening below. That extra darkening used to
        // be a flat 0.78 regardless of weather, which made L1 look like an
        // approaching storm on any heavily overcast day. It's now gated by
        // stormFactor: normal weather barely darkens L1 further (0.90), and
        // only genuine storm conditions unlock the much darker (0.55) look.
        float l1ExtraDark = mix(0.90, 0.55, stormFactor);
        float shadowL1 = mix(shadowFromL2, 1.0, 0.5) * l1ExtraDark;
        vec3 skyTintL0 = min(skyTint * 1.20, vec3(1.0));
        vec3 underglowDayTintL0 = min(underglowDayTint * 1.30, vec3(1.0));
        vec3 underglowDayTintL1 = underglowDayTint * 0.65;
        vec3 underglowDayTintL2 = underglowDayTint * 0.15;

        float transmittance = 1.0;
        vec3  scattered     = vec3(0.0);

        // Per-layer erosion detail budget: L0 is nearest and fills the most
        // of the frame, so it gets the full billow budget; L1 and L2 sit
        // further back and get progressively smoother, less-eroded surfaces
        // instead of spending the same per-sample cost on detail that reads
        // as noise at their distance.
        const float LAYER_DETAIL_L0 = 1.00;
        const float LAYER_DETAIL_L1 = 0.65;
        const float LAYER_DETAIL_L2 = 0.40;

        // L0 is the layer closest to the camera and the most likely to fill
        // the frame at grazing/overhead angles, so it's the one worth paying
        // extra steps for -- 1.5x the base budget instead of sharing L1's.
        marchLayer(dir, toSun, baseL0, depthL0, windL0, evolL0, 1.00, max(6, (cloudMarchSteps * 3) / 2),
                   phase, sunPower * shadowL0, sunTint, skyTintL0,
                   underglowDayTintL0, underglowDuskTint, duskStrength, horizonFade, LAYER_DETAIL_L0,
                   transmittance, scattered);
        marchLayer(dir, toSun, baseL1, depthL1, windL1, evolL1, 1.00, max(4, cloudMarchSteps),
                   phase, sunPower * shadowL1, sunTint, skyTint,
                   underglowDayTintL1, underglowDuskTint, duskStrength, horizonFade, LAYER_DETAIL_L1,
                   transmittance, scattered);
        marchLayer(dir, toSun, baseL2, depthL2, windL2, evolL2, 0.30, max(3, cloudMarchSteps * 3 / 5),
                   phase, sunPower, sunTint, skyTint, underglowDayTintL2, underglowDuskTint,
                   duskStrength, horizonFade, LAYER_DETAIL_L2, transmittance, scattered);

        color = mix(color, color * transmittance + scattered, horizonFade);
        cloudAlpha = clamp((1.0 - transmittance)
                * (0.75 + 0.35 * cloudShadowStrength) * horizonFade, 0.0, 1.0);
    }

    // Soft-knee highlight compression: sun halo, forward-scattered cloud
    // cores and twilight glow can all stack past 1.0 in the same patch of
    // sky, and clamping that straight to white erases every gradient that
    // would otherwise read as "bright but shaped" -- both the near-sun
    // blowout and the general "clouds should be more grey" flatness were the
    // same underlying problem. Values under KNEE are untouched so normal sky
    // and shaded cloud stay exactly as tuned; only the portion that would
    // have clipped rolls off smoothly toward white instead of slamming into
    // it.
    const float KNEE = 0.82;
    const float ROLLOFF = 1.0 - KNEE;
    vec3 over = max(color - KNEE, vec3(0.0));
    // Bug: this must keep the original color for anything under KNEE and only
    // add the compressed remainder on top of it -- using KNEE itself as the
    // floor pinned every channel up to at least 0.82 regardless of how dark
    // it started, which is why the whole sky washed toward flat white
    // instead of just the parts that were actually clipping.
    color = min(color, KNEE) + ROLLOFF * over / (over + ROLLOFF);

    outColor = vec4(color, cloudAlpha);
}
