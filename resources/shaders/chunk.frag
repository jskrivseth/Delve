#version 330 core

in vec3 fragNormal;
in vec4 fragColor;
in vec2 fragTexCoord;
in float fragViewDistance;
in float fragSkyLight;
in vec3 fragViewPos;

uniform sampler2D textureSampler;
uniform bool useTexture;
uniform bool useVertexColor;
uniform float aoStrength;

uniform vec3 sunDirection;
uniform vec3 sunColor;
uniform vec3 moonDirection;
uniform vec3 moonColor;
uniform vec3 skyAmbient;
uniform vec3 groundAmbient;
/** Floor so enclosed spaces stay readable rather than going pure black. */
uniform float caveMinimum;

uniform bool flashlightOn;
uniform vec3 flashlightColor;
uniform float flashlightRange;
uniform float flashlightInner;
uniform float flashlightOuter;

uniform bool fogEnabled;
uniform vec3 fogColor;
uniform float fogStart;
uniform float fogEnd;
uniform float alphaOverride;

out vec4 outColor;

void main() {
    // The vertex color's alpha channel carries ambient occlusion, not opacity.
    float ao = mix(1.0, fragColor.a, aoStrength);
    vec3 albedo = useVertexColor ? fragColor.rgb : vec3(1.0);

    if (useTexture) {
        vec4 texel = texture(textureSampler, fragTexCoord);
        // Cutout instead of blending: foliage and glass have real alpha and the
        // chunks are not depth sorted, so blending would resolve out of order.
        if (texel.a < 0.5) {
            discard;
        }
        albedo *= texel.rgb;
    }

    vec3 normal = normalize(fragNormal);

    // How much of the open sky reaches this surface. Deep tunnels approach zero,
    // which is what darkens the world as you dig.
    float exposure = fragSkyLight;

    // Hemispheric ambient: skylight from above, bounced light from the ground
    // below. Much more natural than a single flat ambient term.
    float hemi = 0.5 + 0.5 * normal.y;
    vec3 ambient = mix(groundAmbient, skyAmbient, hemi);
    ambient *= max(exposure, caveMinimum);

    // Half-lambert wrap softens the terminator so vertical faces don't fall off
    // a cliff into pure ambient.
    vec3 toSun = -normalize(sunDirection);
    float ndlSun = dot(normal, toSun);
    float sunWrap = max((ndlSun + 0.3) / 1.3, 0.0);
    vec3 direct = sunColor * mix(max(ndlSun, 0.0), sunWrap, 0.5) * exposure;

    vec3 toMoon = -normalize(moonDirection);
    float ndlMoon = dot(normal, toMoon);
    float moonWrap = max((ndlMoon + 0.3) / 1.3, 0.0);
    direct += moonColor * mix(max(ndlMoon, 0.0), moonWrap, 0.5) * exposure;

    vec3 lit = albedo * (ambient + direct) * ao;

    if (flashlightOn) {
        // In view space the camera sits at the origin looking down -Z, so the
        // spotlight needs no extra uniforms for position or orientation.
        vec3 toFrag = normalize(fragViewPos);
        float cosAngle = dot(toFrag, vec3(0.0, 0.0, -1.0));
        float cone = smoothstep(flashlightOuter, flashlightInner, cosAngle);
        float falloff = clamp(1.0 - fragViewDistance / flashlightRange, 0.0, 1.0);
        float facing = max(dot(normal, -toFrag), 0.0);
        lit += albedo * flashlightColor * cone * falloff * falloff * facing * ao;
    }

    if (fogEnabled) {
        float fogFactor = clamp((fogEnd - fragViewDistance) / max(fogEnd - fogStart, 0.0001), 0.0, 1.0);
        lit = mix(fogColor, lit, fogFactor);
    }

    outColor = vec4(lit, alphaOverride);
}
