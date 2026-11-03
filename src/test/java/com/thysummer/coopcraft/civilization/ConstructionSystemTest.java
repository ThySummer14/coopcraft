package com.thysummer.coopcraft.civilization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructionSystemTest {
	@Test
	void noMembersNeedNoHousing() {
		assertFalse(ConstructionSystem.needsHousing(0, 0));
	}

	@Test
	void membersWithoutBedsNeedHousing() {
		assertTrue(ConstructionSystem.needsHousing(0, 1));
		assertTrue(ConstructionSystem.needsHousing(1, 2));
		assertTrue(ConstructionSystem.needsHousing(2, 3));
	}

	@Test
	void enoughBedsStopHousing() {
		assertFalse(ConstructionSystem.needsHousing(1, 1));
		assertFalse(ConstructionSystem.needsHousing(2, 2));
		assertFalse(ConstructionSystem.needsHousing(3, 2));
	}
}
