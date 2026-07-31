#version 330 core

in vec3 fragNormal;
in vec4 fragColor;
in vec2 fragTexCoord;
in float fragViewDistance;

uniform sampler2D textureSampler;
uniform bool useTexture;
uniform bool useVertexColor;

uniform vec3 sunDirection;
uniform vec3 sunColor;
uniform vec3 skyAmbient;
uniform vec3 groundAmbient;

uniform bool fogEnabled;
uniform vec3 fogColor;
uniform float fogStart;
uniform float fogEnd;
uniform float alphaOverride;

out vec4 outColor;

void main() {
    // The vertex color's alpha channel carries ambient occlusion, not opacity.
    float ao = fragColor.a;
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
    vec3 toSun = -normalize(sunDirection);

    // Hemispheric ambient: skylight from above, bounced light from the ground
    // below. Much more natural than a single flat ambient term.
    float hemi = 0.5 + 0.5 * normal.y;
    vec3 ambient = mix(groundAmbient, skyAmbient, hemi);

    // Half-lambert wrap softens the terminator so vertical faces don't fall off
    // a cliff into pure ambient.
    float ndl = dot(normal, toSun);
    float wrapped = max((ndl + 0.3) / 1.3, 0.0);
    vec3 direct = sunColor * mix(max(ndl, 0.0), wrapped, 0.5);

    vec3 lit = albedo * (ambient + direct) * ao;

    if (fogEnabled) {
        float fogFactor = clamp((fogEnd - fragViewDistance) / max(fogEnd - fogStart, 0.0001), 0.0, 1.0);
        lit = mix(fogColor, lit, fogFactor);
    }

    outColor = vec4(lit, alphaOverride);
}
