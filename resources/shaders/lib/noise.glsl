// Shared value-noise primitives.
//
// Included by every shader that samples the cloud field. Keeping a single copy
// matters because the sky, the god-ray shafts and the terrain cloud shadows must
// all evaluate the *same* field -- if these drift apart, shadows land in places
// where there is no visible cloud.
//
// The lattice itself is baked once at startup (see CloudNoise.java) into a
// tileable texture rather than hashed per sample: every fbm/fbm3/billow3
// octave used to cost a handful of fract/dot ALU ops per call, and there are
// upwards of a dozen such calls per density sample. A texture fetch replaces
// that with hardware-filtered lookup, and -- unlike the old procedural hash,
// which had no mip chain -- the baked texture's mipmaps give the noise proper
// minification filtering at distance, which is most of what read as grain.

uniform sampler2D cloudNoiseTex2D;
uniform sampler3D cloudNoiseTex3D;

const float NOISE_TEX_2D_SIZE = 256.0;
const float NOISE_TEX_3D_SIZE = 48.0;

float valueNoise(vec2 p) {
    return texture(cloudNoiseTex2D, p / NOISE_TEX_2D_SIZE).r;
}

/** Four octave variant, used for the large-scale coverage fields. */
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

float valueNoise3(vec3 p) {
    return texture(cloudNoiseTex3D, p / NOISE_TEX_3D_SIZE).r;
}

/**
 * Billow noise: folding the signed noise about zero turns smooth undulation into
 * packed rounded lobes. This is what gives cumulus its cauliflower surface --
 * ordinary fbm can only ever produce soft blobs, however it is scaled.
 */
float billow3(vec3 p, int octaves) {
    float f = 0.0;
    float a = 0.55;
    float norm = 0.0;
    float freq = 1.0;
    for (int i = 0; i < octaves; i++) {
        f += a * abs(valueNoise3(p * freq) * 2.0 - 1.0);
        norm += a;
        freq *= 2.13;
        a *= 0.5;
    }
    return f / max(norm, 0.0001);
}

