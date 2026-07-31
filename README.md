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
| `T` | Cycle textures |
| `B` | Vertex colours |
| `F` | Fog |
| `F3` | Wireframe |
| `F4` / `F5` | Draw distance |
| `F7` | Frustum culling |
| `F8` | VSync |
| `F11` | Fullscreen |
| `Esc` | Quit |

## Architecture

| Area | Notes |
| --- | --- |
| `Game` | Window title shows FPS, face count and selected block |
| `Window` | GLFW window and OpenGL 3.3 core context |
| `Renderer` | Chunk, debug-line and HUD passes; VAO based |
| `ShaderProgram` | Compile/link with cached uniform locations |
| `Texture` | STB-backed atlas loading |
| `WorldChunk` | 16x128x16 voxels, single-pass meshing with face culling |
| `Block` | Palette, atlas tiles, per-vertex ambient occlusion |
| `BlockFinder` | Voxel raycasting for selection and placement |
| `FirstPersonCamera` | AABB-vs-voxel collision, JOML matrices |

### Rendering

- One VAO/VBO per chunk, 12 floats per vertex
  (position, normal, colour + AO, texcoord)
- Face culling against neighbours, including across chunk borders
- Per-vertex ambient occlusion, carried in the vertex alpha channel
- Hemispheric sky/ground ambient plus a directional sun
- Day/night cycle driving sun direction, sky colour and fog
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

Chunks edited by the player are flagged and written to `saves/`, then reloaded
instead of being regenerated from noise.

> Note: the world seed is currently randomised per launch, so saved chunks
> reload against different surrounding terrain. See the backlog.

## Licence

No licence chosen yet.

## Naming

The Java package is still `cydi` after the rename to Delve. The package rename
is deliberately deferred: it touches every source file and carries no functional
benefit.
