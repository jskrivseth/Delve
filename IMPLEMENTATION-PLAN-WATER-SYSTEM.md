# Water System Implementation Plan

## Executive Summary
Fix 9 confirmed defects across 4 priority phases. **Phase 1** (coordinate + ledge contact) is the immediate request to address the visible back-edge gap in playtests. Phases 2-4 are separate changes.

---

## Priority Order & Dependencies

### Phase 1: Coordinate Bug + Ledge Contact Fix (IMMEDIATE)
| Finding | Severity | Location | Fix Required |
|---------|----------|----------|--------------|
| **B** — `solidHere` Y/Z swap | 🔴 CRITICAL | `WorldChunk.java:2537` | Correct argument order: `cx, y, cz` |
| **A** — Missing ledge contact | 🔴 CRITICAL | `WorldChunk.java:waterCornerHeight()` | Add render-only back-edge constraint |

**Acceptance:** Lower water's back edge meets solid wall top exactly (world Y=6 in fixture); far edge remains level-derived. Tests all 4 orientations + unequal coordinates.

---

### Phase 2: Connector Assembly Fix (SEPARATE CHANGE)
| Finding | Severity | Location | Fix Required |
|---------|----------|----------|--------------|
| **F** — Transition contract gaps | 🟠 HIGH | `Block.java:630-839` | Welded endpoints, single ownership, correct normals |
| **G** — ±Z winding reversal | 🟠 HIGH | `Block.java:742-782` | Correct vertex/index order for +Z/-Z streams |

---

### Phase 3: Mesh Dependency Repair (SEPARATE CHANGE)
| Finding | Severity | Location | Fix Required |
|---------|----------|----------|--------------|
| **D** — Boundary replay mechanism mismatch | 🟡 MEDIUM | `WorldChunk.java:2754-2834` | Track revisions, diagonal invalidation, avoid deadlock |
| **E** — Missing neighbor invalidation | 🟡 MEDIUM | `World.java`, `WorldChunk.java` | Centralize dependency tracking for water edits |

---

### Phase 4: Simulation Unification (DEFERRED)
| Finding | Severity | Location | Status |
|---------|----------|----------|--------|
| **C** — Queue/global divergence | 🟡 MEDIUM | `World.java:867-1058` | Defer until after Phases 1-3 complete |

---

## Acceptance Test Matrix (Key Items)

| Fixture | Required Assertion |
|---------|-------------------|
| Water-fed one-block ledge (4 orientations) | Both back vertices at world Y=6 within ±0.0001 |
| Unequal Y/Z coordinates | Bank lookup uses intended cell, not transposed position |
| Different levels across chunk edge | Shared world-space vertices agree |
| Chunk corner (4 chunks meeting) | Every incident mesh uses same lattice constraint |
| Connector triangles | Correct winding per cross-product test for all 4 orientations |

---

## Guardrails (Critical Constraints)

- **Do NOT change `waterLevels`/`blocks` arrays** — rendering-only changes must preserve these
- **Do NOT globally raise water levels** — don't hide geometry gaps by increasing flow
- **DO NOT disable water-water culling** — resolve contacts explicitly instead
- **USE emitted vertices for assertions**, not helper methods
- **Test all 4 orientations** — axis-specific bugs will resurface otherwise

---

## Implementation Sequence

### Step 1: Fix coordinate bug in `solidHere` (Finding B) — DONE
File: `src/delve/world/WorldChunk.java`, line ~2537

```java
// Current code (BUG):
if (solidHere(cx, cz, y)) {
    continue;
}

// Fixed code:
if (solidHere(cx, y, cz)) {  // Correct argument order
    continue;
}
```

### Step 2: Spill contact weld (Finding A) — DONE
File: `src/delve/world/WorldChunk.java`, inside `waterCornerHeight()`

Shipped design: a `bankContact` flag set inside the dry-solid branch. If a
sampled column is solid **and** carries water above its brim
(`waterLevelAt(cx, y + 1, cz) > 0`), the corner returns `1.0f` alongside the
existing `hasFullWater` / `fallDistance == 1` overrides. Because every cell
sharing a lattice corner samples the same four world-space columns, the weld
is automatically symmetric: **any edge can be a back edge**, so ledge lips,
pit rims, and one-block holes all weld identically without a direction
classifier. Corners with no wet voter stay 0; the far edge of a lower sheet
does not touch the bank column and keeps its level-derived height. Purely
render-only: `blocks`/`waterLevels` untouched. Conservative on seams: an
unreadable neighbor column reads no feed, so no phantom weld; boundary replay
settles the seam afterwards.

The earlier sketch (directional feed probe + atlas-unit arithmetic using
`ATLAS_TILES`/`BLOCK_TILES`/`out[]`) referenced locals that do not exist in
`waterCornerHeight` and is superseded by the flag design.

### Step 3: Acceptance tests — DONE
`brimmingBankWeldsEveryTouchingCornerOnAllFourSides` — bank with feed welds
all four of its lattice corners (1.0); far sheet edge stays 0.65; removing the
feed releases the weld (dry banks vote nothing).
`brimmingBankProbeUsesRealHeightsNotTransposedCoordinates` — bank at z=10,
sheet at y=6: a transposed probe would interrogate the wrong elevation and
fail; passes only with the fixed argument order.
Suite: 130 tests, 0 failures (`mvn clean package` exit 0). Also repaired an
accidentally commented-out `assertFalse` in the cadence test (unrelated).

---

## Guardrail Checks

After each phase, verify:
1. Existing tests still pass (`mvn test`)
2. Build succeeds (`mvn clean package`)
3. Visual inspection of fixed ledge from multiple angles
4. No new artifacts in `target/surefire-reports/`

---

## Maven Build Commands

```bash
# After Phase 1:
mvn test "-Dtest=WaterSimulationTest"
mvn clean package

# Full build if needed:
mvn clean verify -q
```
