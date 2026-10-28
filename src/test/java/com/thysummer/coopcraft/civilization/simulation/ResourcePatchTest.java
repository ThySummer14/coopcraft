package com.thysummer.coopcraft.civilization.simulation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePatchTest {
	@Test
	void harvestCannotExceedStock() {
		ResourcePatch patch = patch(7, 100, 0.1);

		assertEquals(7, patch.harvest(20, 3));
		assertEquals(0, patch.stock());
		assertEquals(0, patch.harvest(4, 4));
		assertEquals(3, patch.lastHarvestedDay());
		assertThrows(IllegalArgumentException.class, () -> patch.harvest(-1, 4));
	}

	@Test
	void regenerationIsBoundedByCapacityAndRemainingStock() {
		ResourcePatch patch = patch(50, 100, 0.1);

		assertEquals(3, patch.regenerate(1.0));
		assertEquals(53, patch.stock());
		patch.harvest(53, 0);
		assertEquals(0, patch.regenerate(1.0));
		assertEquals(0, patch.stock());

		ResourcePatch full = patch(100, 100, 0.1);
		assertEquals(0, full.regenerate(1.0));
		assertEquals(100, full.stock());
		assertTrue(full.regenerate(0.5) <= full.carryingCapacity());
	}

	private static ResourcePatch patch(long stock, long capacity, double growthRate) {
		return new ResourcePatch(
			"patch", "camp", new ResourcePatch.Position(0, 64, 0), "test", ResourceType.WILD_FRUIT,
			stock, capacity, growthRate
		);
	}
}
