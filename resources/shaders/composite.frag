#version 330 core

in vec2 fragUv;

uniform sampler2D sceneTexture;
uniform sampler2D raysTexture;
uniform sampler2D depthTexture;
uniform bool raysEnabled;
uniform bool skyBlurEnabled;

out vec4 outColor;

void main() {
    vec3  scene = texture(sceneTexture, fragUv).rgb;
    float depth = texture(depthTexture, fragUv).r;

    // Light 3x3 blur on sky pixels only, to clean up residual march dither.
    // Neighbours occupied by geometry are skipped so clouds never bleed onto
    // terrain silhouettes. Skipped when the sky is marched at reduced
    // resolution, since the bilinear upscale already smooths it and a second
    // blur only erases cloud detail.
    if (skyBlurEnabled && depth >= 1.0) {
        vec2  texel  = 1.0 / vec2(textureSize(sceneTexture, 0));
        vec3  sum    = vec3(0.0);
        float wTotal = 0.0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                vec2 uv2 = fragUv + vec2(dx, dy) * texel;
                if (texture(depthTexture, uv2).r < 1.0) continue;
                float w = (dx == 0 && dy == 0) ? 4.0 : ((dx == 0 || dy == 0) ? 2.0 : 1.0);
                sum    += texture(sceneTexture, uv2).rgb * w;
                wTotal += w;
            }
        }
        if (wTotal > 0.0) scene = sum / wTotal;
    }

    if (raysEnabled) {
        vec3 rays = texture(raysTexture, fragUv).rgb;
        // A flat additive blend blows fully-lit sky/cloud pixels straight to
        // white, since they already sit near 1.0 before any ray is added.
        // Scaling by the scene's remaining headroom (screen-blend style) lets
        // shafts read clearly against terrain and shadowed cloud gaps while
        // fading out over pixels that have no room left to brighten.
        float luma = dot(scene, vec3(0.299, 0.587, 0.114));
        float headroom = clamp(1.0 - luma, 0.0, 1.0);
        scene += rays * headroom;
    }

    outColor = vec4(clamp(scene, 0.0, 1.0), 1.0);
}
