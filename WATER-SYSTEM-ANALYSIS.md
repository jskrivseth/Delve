# Water system analysis and implementation handoff

## Scope and baseline

Repository: `jskrivseth/Delve`  
Branch: `jskrivseth-water-flat-ledge-seam-fix`  
Reviewed HEAD: `43a848504c7d1c68befd9f095253e6e003225494`  
Related PR: #13, open at the time of this analysis.

This document replaces further speculative implementation. It describes the
current code, the user's intended result, confirmed defects, and a recommended
repair sequence for other agents. No engine code was changed to produce it.
Source locations below refer to this HEAD; use method names if lines move.

Evidence is static source inspection, the user's playtest reports and latest
screenshot, and existing test reports. The recorded baseline has 128 tests,
zero failures and zero errors. These tests do **not** prove the reported visual
defects are fixed. No new engine playtest or test run was performed for this
document.

## Desired result

The user is broadly satisfied with water behavior and wants coherent rendering,
not a new fluid solver. Their latest, most specific requirement is:

> When water spills over a ledge, the back edge of the lower water block should
> snap to the top of the solid block forming the wall behind it.

The screenshot shows lower water beside an exposed vertical strip of bank.
That is a surface-to-terrain contact requirement, not merely a request to
increase a stored water level.

Earlier feedback adds these requirements:

- Decorative slanted or curved waterfall geometry should bridge upper water,
  the ledge, and lower water. A simple slanted transition is an acceptable
  starting point; a curved profile can follow once connections are correct.
- No missing drop coverage, internal pipe-like shapes, or wall z-fighting.
- Same-height water tiles and chunk-boundary corners must remain continuous.
- Preserve source/reservoir identity, drainage, saves, and intended translucent
  face culling. Do not globally raise lakes against every dry shoreline.

For a concrete one-block ledge, place solid terrain at `(4, 5, 4)`, upper water
at `(4, 6, 4)`, and lower surface water at `(5, 5, 4)`. The solid wall's top is
world Y `6`. The lower water's two back-edge vertices at X `5` must reach world
Y `6`, while its far edge may retain its normal lower surface height. The
upper water surface is above Y `6`; it is **not** the same target as the wall
top. The transition must connect the upper surface/side to this contact edge.
Do not promise that an entire lower pool becomes coplanar with the upper river.

## 1. How the current system works

### State and persistence

`WorldChunk` stores a flat `blocks` array and a parallel `waterLevels` byte
array. `setWaterLevel()` updates both, maintains a water-cell bitset, marks the
owning chunk's mesh stale, and marks the chunk modified.

| Level | Meaning | Exposed surface height relative to cell floor |
|---|---|---|
| 0 | Dry | Not a water surface |
| 1 | Generated, anchored reservoir water | 0.55 |
| 2 through 7 | Reclaimable flow | 0.45 through 0.95 |
| 8 | Explicit independent source | 1.0 |

The level is a gameplay strength/category, not a conserved physical volume.
This is a discrete, rule-based voxel simulation, not a pressure or
Navier-Stokes solver. Level 1 is semantically special, not simply the weakest
flow. Ordinary flow must not convert it into disposable water.

Generation assigns level 1. `BlockFinder.setBlockType()` assigns level 8 for
manual water placement. Saves retain the existing block format plus a
`.water` sidecar. Old saves without that sidecar restore water blocks as
level 1. Rendering-only changes should not alter these formats or arrays.

Sources: `WorldChunk.java:203-279`, `:561-637`, `:3285-3326`;
`BlockFinder.java:25-76`, `:134-177`.

### Two different simulation paths

There are currently two substantially different evaluators:

| Path | Caller and scheduling | Important behavior |
|---|---|---|
| `processWaterUpdates()` | Immediate edit handling and explicit queue processing | FIFO; reservoir outlet checks; support/decay before falling; bounded downward propagation; lateral spreading at the current cell |
| `processGlobalWaterUpdates()` / `processGlobalWaterCell()` | Normal world update, approximately every 180 ms | Snapshot of active water cells; default 256-cell slice; scans a column down to terrain; spreads at its bottom; weak-flow cadence |

The global pass reads current levels from stored cell coordinates; the snapshot
is not an immutable copy of levels. Newly created cells enter a later snapshot.
At the end of a slice it clears `waterQueue`, rather than executing those
notifications through the queued evaluator.

Drainage is a separate bounded wavefront. `processWaterDrains()` removes
connected levels 2 through 7 and preserves levels 1 and 8. Source removal in
`BlockFinder` initiates it; ordinary flow removal does not. Both evaluators
also use `hasStrongerSupport()` for local decay.

Sources: `World.java:580-585`, `:648-776`, `:779-1184`.

### Surface construction

`buildMesh()` performs a face-count pass and an emission pass while holding
the world read lock. Opaque geometry precedes translucent geometry in the
pending buffers.

Exposed water at levels 1 through 7 with no water immediately above uses
`fillWaterCornerHeights()` and `Block.writeWaterCube()`. Sources and covered
water cells use ordinary full cubes. Four-column lattice sampling allows
adjacent surface cells to calculate the same shared corner.

`waterCornerHeight()` generally averages exposed wet samples. A level-8
sample forces full height. A special one-row overhang case also forces full
height. Covered wet samples are skipped. Dry solid-bank samples are intended
to contribute nothing.

Separate decorative geometry is added:

- `collectStepCurtains()` detects supported cells facing a dry, non-solid
  neighbor with a landing within a bounded downward scan.
- `Block.writeWaterfallCurtain()` adds two quads: an outward-sloping lip
  beginning at the upper cell's **floor**, then a vertical sheet.
- `springOverFall()` / `waterfallFallRelY()` identify deeper falls containing
  transit water; `Block.writeWaterfallConnector()` emits 13 quads containing
  a collar, nested stream skin/core, and a cap.

Water-water faces are culled as if water occupies whole cells. Terrain-facing
water faces are also suppressed by the ordinary exposure rule.

Sources: `WorldChunk.java:2260-2770`; `Block.java:524-628`, `:630-839`.

### Publication and rendering

`seamPaved` is selected before sampling. It suppresses the contribution of
unknown columns to the corner-height denominator. `hasIncompleteNeighbor()`
checks all eight horizontal neighbors for missing/generated/build/pending
states.

At the end of buffer construction, a paved chunk calls
`replayBoundaryColumns()`, which **enqueues** wet rim cells and their facing
neighbors. It does not run propagation or rewrite the pending vertices.
Upload uses bounded rebuild retries and notifications to other paved chunks.

The renderer blends water at an alpha override of 0.62 with depth writes
disabled and back-face culling enabled. It reverses the chunk submission list;
it does not sort individual translucent surfaces. The vertex shader does not
apply water-specific displacement that could repair CPU mesh gaps.

Sources: `WorldChunk.java:2410-2430`, `:2770-2834`, `:2865-2925`;
`Renderer.java:1620-1700`; `resources\shaders\chunk.vert`.

## 2. Findings and fixes

### A. Missing ledge-contact rule is the immediate rendering defect

**Confirmed code gap.** `waterCornerHeight()` has no explicit rule that raises
a lower surface's back edge to a water-fed solid ledge's top. Solid banks are
skipped, and the overhang case searches an open shaft rather than a solid bank.
Increasing stored flow to 7 gives only 0.95 before averaging; neighboring weak
cells can lower the result further. It cannot guarantee exact terrain contact.

**Recommended fix:** add a deterministic, render-only ledge-contact constraint
to the shared lattice sampler or a shared surface-description helper:

- Identify a solid bank at the lower surface's elevation with a credible
  water feed over its lip, initially water immediately above that bank.
- Pin the shared contact vertices to that bank block's top, exactly 1.0
  relative to the adjacent lower cell's floor for a one-block step.
- Preserve the far edge's normal level-based height.
- Evaluate the constraint from the same world-space lattice neighborhood for
  every incident cell, including cells in other chunks. Do not privately
  adjust only one block's corners after shared sampling.
- Do not lift arbitrary dry banks, distant diagonals, entire lakes, or corners
  multiple cells upward. Taller falls need dedicated transition geometry.

The local neighborhood does not encode historical flow direction. A wet ledge
is a proposed geometry-based classifier, not proof that a particular molecule
or simulated update crossed that edge. Document that policy and test it.

**Acceptance:** in the coordinate fixture above, both back vertices are at
world Y `6` within `0.0001`, the far edge stays lower, and all level/block
bytes are unchanged after meshing. Repeat in all four directions and at
chunk edges/corners. Remove the upper feed: an ordinary dry-bank contact must
not acquire the spill constraint.

### B. The corner sampler swaps Y and Z in its solid-bank lookup

**Confirmed defect.** In `waterCornerHeight()`, the dry-column branch calls
`solidHere(cx, cz, y)` although the signature is `solidHere(int x, int y, int z)`.
It queries an unrelated location and can classify banks incorrectly.

**Recommended fix:** correct the argument order before designing bank contact.
Test with deliberately unequal Y and Z values and solid blocks at the real
and transposed locations. Equal-coordinate fixtures can hide this defect.

Source: `WorldChunk.java:2537-2540`, `:2595-2598`.

### C. The last "falls recharge" change missed the normal gameplay evaluator

**Confirmed divergence.** HEAD changes queued propagation to
`downwardLevel = 7`. The global evaluator still computes
`level == 1 || level == 8 ? 7 : level`.
Its lateral strength is also derived from the original evaluated cell.
The new `waterDroppingOverALedgeLandsWithFullHeightAgain` test invokes only
`processWaterUpdates()`, so it cannot establish the claimed normal-gameplay
behavior.

There are older differences too: global spreading requires terrain beneath
each lateral target and does not call the queued reservoir outlet predicate.
Generation and seam notifications can be cleared without their queued rules
ever running. Cave/ledge behavior therefore depends on which path reached it.

**Recommended fix:** keep the immediate geometry repair separate. If the
latest full-strength-after-fall gameplay rule is retained, implement one
shared cell-transition policy used by both schedulers. Keep scheduling,
budgets, and cadence explicit rather than maintaining two copies of the
physics. Do not copy `7` into one more location and call the system unified.

Preserve reservoir behavior and test both schedulers, including an immediate
edit followed by many normal global ticks. Decide recharge semantics at the
landing as well as in the falling column. Exact rendered contact still
requires finding A regardless of that decision.

Sources: `World.java:867-889`, `:982-1058`;
`WaterSimulationTest.java`, final recharge test.

### D. Boundary replay does not reconcile the mesh being published

**Confirmed mechanism mismatch.** Current replay queues future simulation
after the vertex buffer has already been written. Normal global ticks clear
those notifications. It is not an immediate boundary-only geometry repair.
It scans every rim whenever any neighbor is incomplete, rather than just the
affected edges; corner cells can be enqueued repeatedly.

Also, `waterLevelAtOrUnknown()` reads generated CPU water data even when the
neighbor is building or has a pending GPU mesh. GPU publication itself does
not publish water levels. Missing data, different data revisions, and
unuploaded geometry are distinct states that earlier descriptions conflated.

**Recommended fix:**

- Keep propagation scheduling separate from mesh reconciliation.
- Use a consistent read-locked data view for the geometry calculation.
- Track relevant local/border water and terrain revisions with pending
  geometry so a later edit cannot silently leave a permanently stale seam.
- For late data, use a deterministic temporary boundary policy and explicitly
  invalidate affected neighbors when data becomes available.
- If a final boundary repair changes geometry, apply it before finalizing the
  pending buffers, or schedule a real rebuild whose dependencies are tracked.
  Merely appending simulation notifications cannot repair existing vertices.
- Target only affected border columns, including diagonal lattice dependencies.
  Never synchronously call write-locking simulation while holding the mesh
  builder's read lock: that would introduce a lock-upgrade/deadlock hazard.

A boundary correction can still be lightweight; version tracking does not
require a global remesh or a new fluid solver. GPU publication can briefly
show different revisions unless uploads are coordinated. Do not claim
zero transient cracks from eventual invalidation alone.

Sources: `WorldChunk.java:2270-2275`, `:2410-2418`, `:2754-2834`,
`:2865-2925`; `World.java:940`.

### E. Ordinary water changes do not reliably dirty dependent neighbor meshes

**Confirmed missing invalidation path.** `setWaterLevel()` marks only the
owning chunk stale. `spreadWater()` relies on that method. Drain clearing
separately dirties cardinal neighbors, and manual edits do so through
`BlockFinder`, but these paths do not cover all shared-corner diagonal
dependencies. Lighting invalidation may incidentally rebuild another chunk;
it is not a water-geometry correctness guarantee.

**Recommended fix:** centralize dependency invalidation for water/terrain
edits. A boundary change must invalidate the owning mesh and every neighbor
whose lattice samples or transition geometry depend on that cell. Include the
diagonal at a chunk corner and feed/landing changes that affect connectors.
Deduplicate dirty notifications and preserve edits arriving during build/upload.

**Acceptance:** after both neighboring meshes are settled, change a border
level without changing the block type. Both rebuild to the same edge heights.
Repeat with a diagonal corner, terrain removal, and an edit between build and
upload. Do not rely on `seamPaved` still being true.

### F. Transition detection and geometry do not share one boundary contract

**Confirmed design inconsistencies; exact visual contribution needs playtesting.**

- `collectStepCurtains()` rejects wet neighbors on the assumption that their
  surfaces already tile across. `waterCornerHeight()` skips covered wet
  columns, while those columns render as full cubes. Consequently a partial
  exposed surface can meet a full covered column below its top, with the
  shared side culled and no contact correction.
- `curtainLanding()` uses one raw level height, not the final smoothed or
  contact-constrained lower surface. A sloped pool can therefore disagree
  with the sheet's endpoint.
- The 13-quad connector uses symmetric positive/negative offsets around the
  cell origin, but `vertex()` adds no half-cell centering translation.
  Geometry lies around the lattice corner instead of the cell center.
- That connector still emits nested skin/core surfaces and a throat cap in
  addition to ordinary water geometry. Earlier statements that all pipe-like
  internals were removed are not supported by this implementation.
- Face counting includes connectors for exposed water generally, but emission
  occurs only inside the partial-surface branch. Buffer counts and actual
  emission therefore do not follow identical eligibility rules.

**Recommended fix:** define one small, render-only transition description
containing edge direction, top endpoints, contact/landing endpoints, and
surface ownership. Generate both allocation counts and emitted geometry from
it. Reuse shared lattice heights at all contacts. Give each transition one
owner; do not lay multiple decorative surfaces over an already-rendered face.

For the first version, prefer one simple edge-aligned ramp or lip-and-sheet
profile over collars, nested cores, and caps. A complementary lower ramp and
upper lip must use exactly the same endpoint coordinates. Clip or terminate
geometry at actual solid terrain; do not send it through walls or floors.
Only use a small deliberate normal offset where a separate layer is required;
the current 0.42-block projection is a visible shape, not a precision epsilon.

Do not disable all water-water culling to hide holes. Resolve covered-column
contacts and transition ownership explicitly, preserving ordinary interior
face suppression.

### G. Z-facing curtain stream triangles have reversed winding

**Confirmed geometric defect.** In `Block.writeWaterfallCurtain()` the vertical
stream for `+Z` is emitted with a geometric normal pointing toward `-Z`;
the `-Z` stream points toward `+Z`. Their supplied normals claim the opposite.
The X-facing streams are oriented consistently with their stated direction.
With back-face culling enabled, this makes visibility direction-dependent.
Supplying a different normal cannot change triangle culling.

**Recommended fix:** correct vertex/index winding and verify all four
orientations by comparing each triangle's geometric normal with its intended
outward normal. Handle intended two-sided thin sheets explicitly, without
globally disabling culling or double-rendering every water volume.

Sources: `Block.java:742-782`, `:811`; `Renderer.java:1671-1677`.

### H. Sloped surfaces retain axis-aligned cube normals

**Confirmed shading mismatch.** `writeWaterCube()` changes corner heights but
`writeCube()` continues supplying `FACE_NORMALS`. A sloped top still receives
an upward cube normal. This does not cause missing positions, but makes
lighting unreliable when judging the new geometry.

**Recommended fix:** derive appropriate normals from the emitted surface,
with a documented policy for non-planar quads. Preserve shared positions and
deterministic triangulation. Address this after topology/contact is correct,
not as an alternative to closing actual gaps.

Sources: `Block.java:578-579`, `:615-625`;
`resources\shaders\chunk.vert`.

### I. Drainage and transparency need targeted regression coverage

**Risks requiring focused verification, not claims of a reproduced failure:**

- The relaxed support rule allows any wet cell above to support stronger flow
  below. Combined with recharge and lateral feeding, the comment that drying
  is guaranteed top-down is not a proof. Test unsupported cascades and paths
  that could feed back around terrain.
- Drain waves clear connected flow without checking whether another source
  still feeds it. Test connected sources and replenishment, especially while
  plugging a reservoir outlet.
- Global evaluation can write downward before checking support, unlike queued
  evaluation. Drain/replenish ordering and budgets may affect convergence.
- Nested transparent layers exacerbate order-dependent blending. Chunk-level
  reverse submission is not per-surface sorting. First eliminate redundant
  internals; assess sorting only if artifacts remain.
- Fixed scans of 4 or 8 rows and hard-coded landing offsets are heuristics.
  Test a fall beyond the scan limit and avoid hidden unbounded work when
  revising them. A 256-cell budget does not imply only 256 voxel operations:
  global evaluation can scan a column for each evaluated cell.

## 3. Recommended implementation sequence

1. **Establish reproducible fixtures.** Use explicit terrain/levels, all four
   orientations, and seam variants. Record exact expected contact vertices.
   Add global-path versions of the important queue-only behavior tests.
2. **Fix coordinate lookup and ledge contact.** Implement findings B and A,
   including the adjacent full-column contact case from F. Keep water state
   unchanged. This directly addresses the latest request.
3. **Replace inconsistent connector assembly.** Implement a shared transition
   description, exact endpoint welding, winding checks, and count/emission
   agreement. Remove superseded geometry in the same change rather than
   layering another patch over it. Correct normals.
4. **Repair mesh dependency/publication behavior.** Address D and E with
   deterministic late-neighbor and revision tests. This is distinct from
   propagation behavior; do not couple rendering to simulation writes.
5. **Unify simulation policy in a separate change.** Resolve C and verify I
   while preserving the chosen source, reservoir, cadence, and drainage rules.
   A simulation overhaul is not a prerequisite for fixing the back-edge mesh.

Separate changes should have explicit acceptance evidence. Do not claim the
rendering problem solved merely because a simulation test passes.

## 4. Acceptance test matrix

| Fixture | Required assertion |
|---|---|
| Water-fed one-block ledge | Both lower back vertices meet the solid top exactly; far edge remains level-derived |
| Same ledge rotated four ways | Identical translated/rotated geometry; no axis-specific visibility |
| Dry bank and ordinary shoreline | No spill-induced lifting without a qualifying feed |
| Lower sheet beside covered water | No missing strip between partial surface and full column |
| Unequal Y/Z coordinates | Bank lookup uses the intended world cell, not the transposed position |
| Different levels across a chunk edge | Shared world-space vertices agree, including true non-flat slopes |
| Four chunks meeting at a corner | Every incident mesh uses the same lattice constraint |
| Missing, generating, building, pending, uploaded neighbor | Distinct lifecycle fixtures; correction after availability without deadlock or infinite rebuilding |
| Edit after build, before upload | No permanently stale mesh; dependent border/diagonal meshes update |
| One-, two-, and many-block waterfalls | Continuous contacts, no terrain penetration, no arbitrary tall surface spikes |
| All connector triangles | Finite coordinates, nondegenerate areas, correct winding, valid indices and exact allocation/emission accounting |
| Static mesh rebuild | Block/level arrays unchanged; no source creation, drainage, or persistence side effects |
| Queued versus global simulation | Same documented transition policy; scheduling differences limited to intended cadence/budget |
| Weak stream crossing a ledge | Verify chosen recharge semantics under actual global ticks, not just the edit queue |
| Breach below lake; horizontal cave; plugged outlet | Reservoir remains level 1; emitted flow starts, persists while fed, and drains appropriately |
| Remove one of two connected sources | Remaining source survives and its supported flow recovers/stabilizes |
| Unsupported cascade or feedback-shaped terrain | Flow eventually drains; no immortal recharge loop |
| Save/reload and old save without sidecar | Existing level identities and compatibility retained |

Use emitted CPU mesh vertices/indices for geometry assertions, not only calls
to a height helper. Exercise the actual chunk build path at least once for
each important surface category. Retain existing ordinary-water face-culling
tests and assert no duplicate coplanar faces are introduced.

The present tests are mostly in `test\java\delve\world\WaterSimulationTest.java`.
Its seam tests sample helpers or manually process queued replay; they do not
exercise competing mesh publications. The latest ledge test checks levels,
not geometry. Existing connector tests check sizes and absence of NaNs, not
contact, placement, winding, or terrain intersections.

Suggested validation commands after implementation:

```powershell
mvn test "-Dtest=WaterSimulationTest,BlockFinderTest,WorldRenderLifecycleTest"
mvn clean package
java -jar target\delve.jar
```

Include any newly added geometry/lifecycle suites in the targeted command.
On Windows, a running playtest can lock `target\delve.jar`; close the specific
game instance before rebuilding, without terminating unrelated Java processes.
Record the actual Maven exit code, not merely the success of a piped filter.

For visual acceptance, inspect the same fixed ledge from above, below, both
sides, and at a shallow wall angle. Include a chunk boundary and a stacked
fall. Record the build SHA and capture before/after images. Verify no missing
strip at the back edge, no pipes, no wall shimmer, and no directional
disappearance. Automated geometry checks and visual inspection are both
required for declaring the rendering issue resolved.

## 5. Corrections to earlier handoffs

- Current recharge commit is `43a8485`, not the pre-amend `b11b3e9`.
- "Full flow" level 7 renders at 0.95 before averaging, not exactly 1.0.
- The recharge change did not update `processGlobalWaterCell()`.
- Boundary replay enqueues work; it does not repair the pending mesh in place.
- No `WaterRenderingRegressionTest.concurrentPublishLeavesNoBoundaryCornerMismatch`
  exists in the reviewed tree. Do not cite it as validation.
- No current `repairEdgeSeams` or `diagonalRepairQuads` implementation was
  found in this tree. Earlier PR prose describing those repairs should be
  corrected against actual code.
- The current connector still has internal skin/core/cap geometry; the absence
  of pipe artifacts was not established.
- Passing the recorded 128 tests establishes only those tests' assertions,
  not successful visual acceptance or unchanged behavior across both
  simulation paths.

The next implementation should follow the latest visual requirement and the
source evidence above, rather than treating earlier optimistic summaries or
commit titles as verified behavior.

## 6. Independent review — GPT-5.6 Luna

### Overall assessment

I agree with the central diagnosis and the proposed implementation order. The
latest playtest symptom is a geometry/topology problem at a water-to-terrain
contact, not evidence that the stored water level should be raised globally.
The most important distinction in this document is between:

1. **simulation state** (`waterLevels`, source/reservoir identity, propagation
   and drainage), and
2. **rendered surface constraints** (shared lattice corners, ledge contact,
   waterfall transition geometry, and mesh publication dependencies).

The latest recharge patch changes only part of (1), and the reported back-edge
gap is primarily a missing rule in (2). Reusing simulation writes as a mesh
repair is the wrong abstraction and risks changing persistence or drainage
semantics to hide a visual defect.

### Points I affirm

- The explicit ledge-contact constraint is the correct first fix. The lower
  water cell should use the solid ledge top as the back-edge endpoint, while
  the far edge remains governed by its own surface level. Raising the entire
  lower cell would create a different artifact and would flatten legitimate
  pools.
- The Y/Z argument-order error in the `solidHere` call is a real, independent
  correctness bug and should be fixed before relying on bank classification.
- The queued and global simulation paths have materially different behavior.
  A test that only calls `processWaterUpdates()` cannot establish normal
  gameplay behavior, which is driven by the global snapshot/cursor path.
- `replayBoundaryColumns()` queues future work after the current corner
  vertices have already been computed. It may eventually cause another build,
  but it is not an in-place repair of the mesh currently waiting for upload.
  The lifecycle and revision distinction in finding D is important.
- Shared transition metadata should drive both face counting and emission.
  Otherwise a decorative geometry predicate can make the allocated buffer
  disagree with the geometry actually written, or can put multiple owners on
  one visual edge.
- The existing skin/core/cap connector is more complicated than the requested
  decorative waterfall and can plausibly read as an internal pipe when
  layered with ordinary water. A single welded ramp/sheet profile is a safer
  first implementation.
- Existing tests are stronger on scalar simulation and helper output than on
  actual emitted mesh topology. Geometry assertions must inspect vertices,
  indices, winding, contacts, and intersections.

### Corrections and qualifications

#### The ±Z curtain winding finding should be removed

After recomputing the triangle orientations from the actual vertex order in
`Block.writeWaterfallCurtain()`:

- The `+Z` stream is ordered from `(x=1, y=lip)` to `(x=0, y=lip)` to
  `(x=0, y=landing)` and produces a `+Z` geometric normal.
- The `-Z` stream is ordered from `(x=1, y=lip)` to `(x=0, y=lip)` with the
  negative-Z offset and produces a `-Z` geometric normal.

Those match the supplied normals. Finding G is therefore not confirmed and
should not be presented as a bug unless a mesh-level test demonstrates a
different index order after buffer construction. The four-direction test is
still worthwhile for visibility and degeneracy, but it should assert the
actual cross-product sign rather than assume ±Z is reversed.

#### Boundary replay is incomplete, but not necessarily useless

The criticism of replay should be precise: it is insufficient to repair the
already-built pending vertices, not inherently incorrect as a propagation
notification. It can still be useful for re-driving water at a late-loaded
edge. The safe redesign should preserve that scheduling purpose while adding
an explicit mesh dependency/rebuild mechanism. Avoid replacing it with a
synchronous simulation pass under the mesh builder's read lock.

#### Connector placement is a stronger concern than the winding concern

The ordinary cube spans local X/Z `[x, x+1]` and `[z, z+1]`, so geometry made
from `x + radius*cos(angle)` and `z + radius*sin(angle)` is centered around
the lattice point `(x,z)`, not the cell center `(x+0.5,z+0.5)`. Unless the
engine intentionally defines connector coordinates in a different frame, this
can explain off-center collars or apparent internal pipes. A mesh test should
measure the connector's bounding box against the intended cell center before
changing any radii.

#### Simulation unification should remain optional for the immediate fix

The two evaluator paths should eventually share transition policy, but that is
larger and riskier than the current visual issue. The ledge-contact fix can be
implemented and validated without changing the solver. Treat recharge
unification as a separate change with its own convergence, source-removal,
and global-tick tests. This keeps the latest visual repair from accidentally
changing water persistence.

### Recommended decision

Proceed with a rendering-only ledge-contact change first:

1. correct the `solidHere` coordinate order;
2. add a shared, world-space back-edge contact constraint used by every
   incident water surface mesh;
3. add rotated, diagonal, chunk-edge, and emitted-vertex tests;
4. rebuild the waterfall transition around welded endpoints;
5. separately address mesh revisions and late-neighbor publication;
6. defer solver unification and recharge semantics until global-path tests
   define the desired behavior.

Do not implement the ±Z winding correction from finding G without a failing
cross-product or render test. Do implement the other findings only with the
scope and acceptance criteria stated above.

## 7. Independent review — qwen/qwen3.5-9b

### Agreement summary

I agree with the central diagnosis: the latest back-edge gap is a geometry/topology
issue at the water-to-terrain contact, not evidence that the stored water level
should be raised globally. The distinction between (1) simulation state and
(2) rendered surface constraints is fundamental to this task. The recharge patch
changes only part of (1), while the reported back-edge gap is primarily a missing
rule in (2). Reusing simulation writes as a mesh repair is the wrong abstraction
and risks changing persistence or drainage semantics to hide a visual defect.

### Affirmed findings

- **Finding A**: The explicit ledge-contact constraint is the correct first fix.
  The lower water cell should use the solid ledge top as the back-edge endpoint,
  while the far edge remains governed by its own surface level. Raising the entire
  lower cell would create a different artifact and would flatten legitimate pools.

- **Finding B**: The Y/Z argument-order error in the `solidHere` call is a real,
  independent correctness bug that should be fixed before relying on bank
  classification.

- **Findings C-D**: The queued and global simulation paths have materially
  different behavior. A test that only calls `processWaterUpdates()` cannot
  establish normal gameplay behavior, which is driven by the global snapshot/cursor
  path. Boundary replay queues future work after the current corner vertices have
  already been computed; it may eventually cause another build but is not an
  in-place repair of the mesh currently waiting for upload.

- **Finding E**: Ordinary water changes do not reliably dirty dependent neighbor
  meshes across diagonal dependencies and shared-corner lattice cells. Centralized
  dependency invalidation for water/terrain edits is needed.

### Findings requiring geometry validation before implementation

- **Finding F**: The proposed transition detection and geometry do not share a
  single boundary contract. This requires mesh-level tests to validate contact,
  winding, winding checks, endpoint welding, and allocation/emission agreement
  before implementation. A shared transition description should drive both face
  counting and emission.

- **Finding H**: Sloped surfaces retain axis-aligned cube normals when their top
  is modified. This makes lighting unreliable when judging the new geometry but
  does not cause missing positions. Address this after topology/contact is correct,
  not as an alternative to closing actual gaps.

- **Finding I**: Drainage and transparency need targeted regression coverage.
  Risks requiring focused verification rather than claims of reproduced failure
  include unsupported cascades, replenishment during source removal, global
  evaluation ordering, chunk-level reverse submission versus per-surface sorting,
  and fixed scan limits that can hide unbounded work when revised.

### Recommended implementation order

1. **Ledge contact and coordinate correction first**: Fix the Y/Z bank lookup error
   and add a shared, world-space back-edge contact constraint used by every
   incident water surface mesh. Add rotated, diagonal, chunk-edge, and emitted-vertex
   tests before any other changes.

2. **Transition geometry in a separate change**: Rebuild the waterfall transition
   around welded endpoints with explicit endpoint metadata that drives both face
   counting and emission. Remove superseded geometry in the same change rather than
   layering another patch over it. Correct normals.

3. **Mesh dependency/publication behavior next**: Address invalidation gaps and
   late-neighbor publication without coupling rendering to simulation writes under
   a single lock. Use version tracking or explicit rebuild dependencies.

4. **Simulation unification deferred**: Unify recharge semantics only after global-path
   tests define the desired behavior, including source removal, reservoir identity,
  cadence, and drainage rules. A simulation overhaul is not a prerequisite for
   fixing the back-edge mesh.

### Validation strategy

Use emitted CPU mesh vertices/indices for geometry assertions, not only calls
to a height helper. Exercise the actual chunk build path at least once for each
important surface category. Retain existing ordinary-water face-culling tests
and assert no duplicate coplanar faces are introduced. The present tests in
`WaterSimulationTest.java` are mostly on scalar simulation and helper output;
geometry assertions must inspect vertices, indices, winding, contacts, and
intersections.

### Conclusion

The handoff is a valid first step for fixing the water rendering issues. Its
central recommendation to address ledge contact before any global simulation
change is sound. The corrected ±Z winding finding and the qualified connector
centering concern should be validated against actual mesh output before
implementation. The implementation sequence—ledge/contact/transition/mesh dependencies
before solver unification—maximizes the chance of a small, testable change for
the most recent visual report while avoiding regressions in water persistence or
drainage semantics.

## 8. Independent review — Grok 4.6

### Overall

I agree with the document's core claim and with most of the repair order. The
latest screenshot is a missing water-to-terrain contact on the **lower**
sheet's back edge, not a request to raise stored levels or to make the whole
plunge pool coplanar with the upper river. Keep simulation state and rendered
surface constraints separate. Do not use `setWaterLevel` to hide a mesh gap.

I do **not** agree with GPT-5.6 Luna that finding G should be dropped. I also
think findings A and B must ship together: fixing the `solidHere` argument
order without a solid-bank contact rule can remove accidental one-row welds
and make the visible dirt strip worse.

### Rechecked against source

**A — missing ledge contact: agree, and this is the playtest defect.**
`waterCornerHeight()` skips solid banks (`"A solid bank votes nothing"`) and
only force-lifts when `fallDistance == 1` on an **open** shaft. The screenshot
is a **solid** wall beside lower water. Level 7 is 0.95 before averaging, so
recharge cannot pin vertices to the dirt top at world Y `6`. The decorative
curtain is also the wrong surface: `writeWaterfallCurtain` starts at the
**upper cell floor** (`y = 0` in cell space), so it cannot weld the lower
sheet's back edge to the wall top.

**B — `solidHere(cx, cz, y)`: agree it is a real bug.**
`solidHere(int x, int y, int z)` therefore reads `(cx, cz, y)` instead of
`(cx, y, cz)`. Do not confuse this with `waterfallColumnAbove(int x, int z,
int y)`, whose parameter order is already `(x, z, y)` and whose call site
`waterfallColumnAbove(cx, cz, y)` is internally consistent. Only the
`solidHere` call is transposed.

**A+B interaction (not in earlier reviews):** when the transposed
`solidHere(cx, cz, y)` misses the real bank, the dry column is treated as
open, `waterfallColumnAbove` then scans the **correct** column, and a one-block
ledge can accidentally hit `fallDistance == 1` and return `1.0`. If the
transposed coordinate is itself solid (common pit floors), that path never
runs and the corner stays a wet-surface mean — matching the screenshot. Fixing
B alone makes the bank classify as solid and **silences** that accidental
weld. Ship A and B in one rendering change.

**C — two evaluators: agree.** Queued `processWaterUpdates` now uses
`downwardLevel = 7`; `processGlobalWaterCell` still uses
`level == 1 || level == 8 ? 7 : level`. The recharge test only exercises the
queued path. Normal ticks are the global path. Unifying this is not required
to close the dirt strip.

**D — replay vs mesh: agree with Luna's qualification.** Replay is a
propagation notification, not a vertex repair. Keep it for late-loaded seams;
do not upgrade the mesh-builder read lock into a simulation write. Missing
chunk, ungenerated data, in-flight build, pending GPU mesh, and uploaded mesh
are different states.

**E — neighbor dirtying: agree.** `setWaterLevel` only marks the owning
chunk. Shared lattice corners include diagonals. Cardinal-only drain/edit
invalidation is not enough.

**F — transition contract: agree as design debt, not the current ask.**
Connector geometry is centered on the lattice origin `(x, z)`, not the cell
center `(x+0.5, z+0.5)`, and still emits nested skin/core/cap. Face-count vs
emission predicates differ. Rebuild transitions after the back-edge contact
is correct; do not layer another sheet on the current connector.

**G — ±Z winding: disagree with Luna; original finding stands.**
`quad()` indexes `0-1-2` and `2-3-0`. For the `+Z` stream the first triangle
is `(0, lip)`, `(1, lip)`, `(1, landing)` at `z = 1+lipOut`.

- `e1 = (1, 0, 0)`
- `e2 = (1, landingRel+lipDrop, 0)`
- `e1 × e2 = (0, 0, landingRel+lipDrop)`

`landingRel` is `<= -0.9` for a qualifying curtain and `lipDrop <= 0.42`, so
the Z component is negative: geometric normal **−Z**, supplied normal **+Z**.
The `-Z` stream analogously yields geometric **+Z** against a supplied **−Z**.
The `+X` stream does match `(+1,0,0)`. Culling uses winding, not the stored
normal, so ±Z sheets can disappear from the outward side. Keep a
cross-product test; do not delete finding G because of an incorrect vertex
order in section 6.

**H — cube normals on sloped tops: agree, later.** Positions first.

**I — drainage/transparency: agree these are risks, not reproduced bugs.**
Do not expand recharge support further while fixing the mesh.

### Disagreements with sections 6–7

- Section 6 is wrong on ±Z winding. I recomputed from `Block.writeWaterfallCurtain`
  and the `quad()` index order; finding G is confirmed.
- Section 7 mostly restates section 6 and treats the winding “correction” as
  settled. It is not a third independent verification of G. It also contains
  a broken list marker (`-cadence`).
- Connector centering is real, but it does not explain the dirt strip in the
  latest screenshot. That strip is the lower sheet failing to meet a solid
  wall.

### What to implement first

One rendering-only change:

1. `solidHere(cx, y, cz)` (or equivalent).
2. A shared world-space contact constraint: a solid bank at the lower water
   Y with water immediately above that bank pins the two shared back-edge
   vertices to the bank top (`1.0` relative to the lower cell for a one-block
   step). Far-edge vertices stay level-derived. Same neighborhood for every
   incident cell, including other chunks.
3. Tests that read **emitted vertices**, not only `waterCornerHeight()`:
   four orientations, unequal Y/Z so the swap cannot hide, chunk edge,
   diagonal corner, dry bank without an upper feed (must not lift), and
   `waterLevels`/`blocks` unchanged.

Then, separately: welded lip/sheet owned by one edge, winding tests for all
four curtain directions, mesh revision/invalidation, then solver unification
if product still wants full-height recharge on the global path.

Do not raise lower-pool levels, disable water-water culling, or add another
connector pass to cover this gap.

## 9. Independent review — Ornith-1.5-35B-A3B-MLX (b100cac4-ce54-4171-8c6b-fdf4a0fbddc9)

I re-derived the two contested findings directly from `Block.writeWaterfallCurtain`
and `WorldChunk.waterCornerHeight`/`solidHere`, rather than trusting any prior
review. On the one point the earlier reviews disagree — **finding G** — I come down
on Grok's side, and here is the recomputation that decides it.

**Confirming Finding B (Y/Z swap) directly.** The call at
`WorldChunk.java:2537` is `solidHere(cx, cz, y)` from inside
`waterCornerHeight(cornerX, y, cornerZ)`. The signature is `solidHere(int x, int y,
int z)`, and its body evaluates
`World.isSolidGlobal(worldPosX + x, y, worldPosY + z)`. Substituting the call's
arguments yields `isSolidGlobal(worldPosX + cx, cz, worldPosY + y)` — the vertical
solidity probe lands at elevation `worldPosY + cz` (a horizontal Z offset) instead
of the intended height `y`. The bank is checked at the wrong level. This is a real,
independent correctness bug and must become `solidHere(cx, y, cz)` before any
bank-contact rule (Finding A) can be trusted. **Confirmed.**

**Recomputing Finding G for the +Z stream.** `quad()` emits triangles
`(0,1,2)` and `(2,3,0)`. The +Z falling sheet is the second quad of `case 2`; all
three sheet vertices sit at constant local `z = 1 + lipOut`:

- `v0 = (0, -lipDrop, 1+lipOut)`
- `v1 = (1, -lipDrop, 1+lipOut)`
- `v2 = (1, landingRel, 1+lipOut)`

Because all three share the same z, the triangle's plane is vertical and
`e1 × e2 = (1,0,0) × (1, landingRel+lipDrop, 0)` = `(0, 0, landingRel+lipDrop)`.
`lipDrop = min(CURTAIN_LIP, -landingRel)` forces `landingRel + lipDrop <= 0`, so the
**geometric normal points −Z**, yet the supplied vertex normal is **+Z**. Back-face
culling follows the winding (the geometric normal), not the stored normal, so this
sheet is visible only from its −Z side and disappears for the +Z viewer it was
declared to face — exactly Finding G. **G stands.**

This also shows why Luna's counter-argument was wrong: it claimed the +Z stream
produces a **+Z** geometric normal. It does not — all three vertices sit at
constant `z = 1+lipOut`, so the cross product is purely in Z and negative. Grok's
recomputation was correct; Luna mishandled the cross product. (One side note: when
`-landingRel == CURTAIN_LIP` exactly, `landingRel+lipDrop == 0`, so `v2 == v1` and
the triangle degenerates to zero area — a separate minor degenerate-quad edge case;
every non-degenerate +Z stream, strictly `v < 0`, has the clearly reversed winding.)

**On the −Z stream.** Its geometric normal is
`(lipOut*(landingRel+lipDrop), lipOut, landingRel+lipDrop)`, dominated by the Y
component, so it is a tilted sheet rather than cleanly reversed. Grok's precise
"reversed" analysis applies most rigorously to the +Z stream; the −Z sheet is merely
misaligned in a messier way. Both are worth fixing, but the +Z sheet is the
unambiguous win and the one an automated cross-product test should assert first.

**Other findings.** I affirm A (missing back-edge contact), the two-evaluator
divergence in C, and the D/E distinction between propagation notifications and actual
vertex repair. I also agree with Grok's interaction point that fixing B alone could
silence an accidental one-row weld, so A and B must ship in one rendering change.

**Recommendation.** Correct `solidHere(cx, y, cz)` and the shared contact constraint
together. Then ship the curtain winding correction (all four orientations) verified by
an emitted-vertex cross-product test that asserts the geometric normal against the
intended outward normal — and drop finding G only when such a test fails, not on any
further prose disagreement. Defer solver unification and the connector simplification
to later, well-scoped changes with their own acceptance evidence.
