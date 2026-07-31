# Delve

A Java voxel engine — LWJGL 3, OpenGL 3.3 core profile, shader-based rendering.

Originally a legacy LWJGL 2 / Slick2D project ("CYDI") using the fixed-function
pipeline; now modernised to a programmable pipeline with Maven-resolved
dependencies.

## Requirements

- JDK 17+
- Maven 3.9+ (or `mvnd`)
- A GPU supporting OpenGL 3.3 core profile

## Build and run

```sh
mvn clean package
java -jar target/delve.jar
```

All dependencies (LWJGL 3.4.2 + platform natives, JOML) resolve from Maven
Central; no manual downloads or `libs/` folder are required.

## Controls

| Input | Action |
| --- | --- |
| `W` `A` `S` `D` | Move |
| Mouse | Look |
| `Space` | Jump (double-tap toggles fly) |
| `G` | Toggle fly mode |
| `Space` / `Shift` | Fly up / down |
| `-` / `=` | Slower / faster |
| LMB / RMB | Break / place block |
| Mouse wheel | Choose block to place |
| `[` / `]` / `P` | Rewind / advance / pause time |
| `L` | Flashlight |
| `T` | Cycle textures |
| `B` | Vertex colours |
| `F` | Fog |
| `F3` | Wireframe |
| `F4` / `F5` | Draw distance |
| `F7` | Frustum culling |
| `F8` | VSync |
| `F11` | Fullscreen |
| `Esc` | Settings menu (save and exit from here) |

On the title screen, left- and right-clicking **World Preset** cycles the planet
a new world is generated on. The choice is recorded in the save, so an existing
world always reloads on the planet it was created for.

## Planets

A world is generated against one of four planet presets. Each preset is a
`PlanetProfile` subclass owning its own physics, atmosphere and sky, so adding a
planet means adding a class rather than threading another branch through the
renderer.

| Planet | Feel |
| --- | --- |
| Earth | Baseline gravity, blue sky, orange twilight, one moon, mixed cloud decks |
| Mars | Low gravity, rusty daytime sky inverting to blue twilight, two small moons on separate orbits |
| Venus | High gravity, thick yellow haze, no moon, a deep opaque cloud deck |
| Triton | Very low gravity, near-vacuum sky at under a tenth of Earth's sunlight, no moon, Neptune looming over the horizon |

Jump force scales *with* gravity — a person pushes harder against a heavier
world — while a per-planet velocity cap keeps low-gravity worlds from
slingshotting the player into orbit.

## Biomes

Earth terrain is divided into ten biomes selected from temperature and moisture
fields: hot desert, savanna, shrubland, temperate grassland, temperate forest,
boreal forest, tundra, tropical rainforest, wetland and alpine. Neighbouring
biomes are blended rather than stamped, so surface blocks, foliage and terrain
shaping cross-fade over a margin instead of changing along a hard line. The
window title reports the biome under the player.

## Texture packs

Packs are loaded at runtime from `texturepacks/`, as either a directory or a
`.zip`, and selected from the settings menu. Both Minecraft layouts work:

- **Classic** — a single `terrain.png` atlas
- **Modern** — individual files under `assets/minecraft/textures/block/`, which
  are assembled into an atlas on load

`sun.png` and `moon_phases.png` may also be overridden. Anything a pack does not
supply falls back to the default art, so partial packs are fine. Block UVs are
baked against fixed atlas slots, so switching packs only swaps GL textures and
never re-meshes chunks.

## Architecture

| Area | Notes |
| --- | --- |
| `Game` | Screen state machine, window title shows FPS, face count, selected block |
| `TitleScreen` / `Menu` | World picker and settings, drawn via `MenuPanel` |
| `Window` | GLFW window and OpenGL 3.3 core context |
| `Renderer` | Chunk, sky, post-processing, debug-line and HUD passes; VAO based |
| `PlanetProfile` / `PlanetProfiles` | Per-planet physics, atmosphere, sky bodies and cloud tuning |
| `WorldPreset` | Static facade the rest of the engine reads planet values through |
| `BiomeDefinition` / `EarthBiome` | Biome table: surface blocks, foliage, terrain shaping |
| `BiomeBlend` | Cross-fades biome parameters so borders are gradients, not seams |
| `Framebuffer` | Colour + depth target for the post-processing passes |
| `ShaderProgram` | Compile/link with cached uniform locations |
| `Texture` / `TexturePack` | STB-backed atlas loading, runtime pack swapping |
| `TextRenderer` | AWT font rasterised to a GL atlas at startup |
| `WorldChunk` | 16x128x16 voxels, single-pass meshing with face culling |
| `Block` | Palette, atlas tiles, per-vertex ambient occlusion |
| `BlockFinder` | Voxel raycasting for selection and placement |
| `FirstPersonCamera` | AABB-vs-voxel collision, JOML matrices |
| `SaveGame` / `Serializer` | Named worlds, metadata and chunk persistence |

### Rendering

- One VAO/VBO per chunk, 13 floats per vertex
  (position, normal, colour + AO, texcoord, sky light)
- Face culling against neighbours, including across chunk borders
- Per-vertex ambient occlusion, carried in the vertex alpha channel
- Per-voxel sky light, flood filled with a BFS that converges across chunk
  borders; water and leaves attenuate more than air
- Hemispheric sky/ground ambient plus directional sun and moon
- Day/night cycle driving sun direction, sky colour and fog
- Directional sky gradient, banded by height and angle to the sun, so twilight
  runs orange through pink into violet
- Screen-space god rays marched from the light and masked by scene depth, so
  shafts break around terrain
- Volumetric clouds on Earth and Venus: the layer is a real slab, raymarched
  with a secondary march toward the sun for self-shadowing, Beer-Lambert
  extinction, a Henyey-Greenstein phase for the silver lining and a powder term
  so thin edges stay wispy while cores can blot out the sun. The same density
  field shadows the ground and breaks the god rays, so a cloud, its shadow and
  the gap it cuts in the shafts all agree
- Clouds drift on world-space wind and evolve with the day cycle rather than in
  lock step with it
- Sun and moon on independent orbits, both able to share the sky, with an
  eight-day lunar phase cycle
- Opaque pass followed by a blended pass for water
- Alpha cutout for foliage and glass
- Frustum culling per chunk via JOML `FrustumIntersection`

### Threading

Voxel data is guarded by a single world-wide `ReentrantReadWriteLock`.
Mesh builders run concurrently under the read lock; terrain generation and
player edits take the write lock. A world-wide lock is used deliberately:
meshing a chunk reads its neighbours across borders, so per-chunk locks would
require a lock ordering to stay deadlock free.

### Persistence

Each world owns a directory under `saves/` holding its chunk files and a
`world.properties` recording the seed, planet preset, player position and
orientation, time of day and day count. Chunks edited by the player are flagged
and written out, then reloaded instead of being regenerated from noise.

The seed reseeds the Perlin generator itself. Offsetting the sample coordinates
is not a substitute, because the generator's permutation table is the noise
field being sampled: leaving it randomised meant terrain regenerated differently
on every launch and saved chunks no longer matched the world around them.

Chunk files are written to a temporary file and moved into place, so a crash or
a concurrent read cannot leave a half-written chunk behind.

## Licence

No licence chosen yet.

## Naming

The Java package is still `cydi` after the rename to Delve. The package rename
is deliberately deferred: it touches every source file and carries no functional
benefit.
