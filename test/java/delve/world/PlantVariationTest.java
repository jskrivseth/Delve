package delve.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlantVariationTest {
    @Test
    void grassShapesStayInsideUsefulRanges() {
        for (int variation = 0; variation < 256; variation++) {
            float height = Block.plantHeight(Block.TALL_GRASS, variation);
            float width = Block.plantHalfWidth(Block.TALL_GRASS, variation);
            assertTrue(height >= 0.48f && height <= 0.96f);
            assertTrue(width >= 0.24f && width <= 0.42f);
        }
    }

    @Test
    void reedsAreTallerAndThinnerThanFernsForSameVariation() {
        int variation = 173;
        assertTrue(Block.plantHeight(Block.REED_GRASS, variation)
                > Block.plantHeight(Block.FERN, variation));
        assertTrue(Block.plantHalfWidth(Block.REED_GRASS, variation)
                < Block.plantHalfWidth(Block.FERN, variation));
    }

    @Test
    void colorVariantsRemainDeterministicAndDistinct() {
        float terrain = Block.packTint(0.86f, 0.92f, 0.75f);
        assertEquals(Block.grassTint(terrain, 0), Block.grassTint(terrain, 0));
        assertNotEquals(Block.grassTint(terrain, 0), Block.grassTint(terrain, 1 << 24));
    }
}
