package com.thysummer.coopcraft.civilization;

import org.junit.jupiter.api.Test;

import static com.thysummer.coopcraft.civilization.VillagerToolDemand.HandState.EMPTY;
import static com.thysummer.coopcraft.civilization.VillagerToolDemand.HandState.OCCUPIED;
import static com.thysummer.coopcraft.civilization.VillagerToolDemand.HandState.POLISHED_STONE_PICKAXE;
import static com.thysummer.coopcraft.civilization.VillagerToolDemand.HandState.STONE_PICKAXE;
import static com.thysummer.coopcraft.civilization.VillagerToolDemand.needsSticks;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillagerToolDemandTest {
	@Test
	void emptyHandNeedsOnlyMissingInitialToolSticksRegardlessOfGrindingStation() {
		for (boolean station : new boolean[]{false, true}) {
			assertTrue(needsSticks(EMPTY, station, 0));
			assertTrue(needsSticks(EMPTY, station, 1));
			assertFalse(needsSticks(EMPTY, station, 2));
		}
	}

	@Test
	void unpolishedPickaxeNeedsOnlyMissingUpgradeSticksAtAValidGrindingStation() {
		assertTrue(needsSticks(STONE_PICKAXE, true, 0));
		assertTrue(needsSticks(STONE_PICKAXE, true, 1));
		assertFalse(needsSticks(STONE_PICKAXE, true, 2));
	}

	@Test
	void absentOrInvalidGrindingStationDoesNotCreateUpgradeDemand() {
		for (long sticks : new long[]{0, 1, 2}) {
			assertFalse(needsSticks(STONE_PICKAXE, false, sticks));
		}
	}

	@Test
	void removedGrindingStationImmediatelyClearsUpgradeDemand() {
		assertTrue(needsSticks(STONE_PICKAXE, true, 0));
		assertFalse(needsSticks(STONE_PICKAXE, false, 0));
		assertTrue(needsSticks(STONE_PICKAXE, true, 0));
	}

	@Test
	void polishedPickaxeNeverCreatesToolStickDemand() {
		for (boolean station : new boolean[]{false, true}) {
			for (long sticks : new long[]{0, 1, 2}) {
				assertFalse(needsSticks(POLISHED_STONE_PICKAXE, station, sticks));
			}
		}
	}

	@Test
	void occupiedNonPickaxeHandCannotStartInitialToolOrUpgradeDemand() {
		// A pickaxe in inventory cannot be auto-equipped while another item occupies the hand.
		for (boolean station : new boolean[]{false, true}) {
			for (long sticks : new long[]{0, 1, 2}) {
				assertFalse(needsSticks(OCCUPIED, station, sticks));
			}
		}
	}

	@Test
	void sufficientTotalSupplyNeverCreatesMoreToolStickDemand() {
		for (var hand : VillagerToolDemand.HandState.values()) {
			for (boolean station : new boolean[]{false, true}) {
				for (long sticks : new long[]{2, 8, 64, (long) Integer.MAX_VALUE + 1, Long.MAX_VALUE}) {
					assertFalse(needsSticks(hand, station, sticks));
				}
			}
		}
	}

	@Test
	void oneEightStickBatchSatisfiesEveryMissingToolDemand() {
		for (var hand : new VillagerToolDemand.HandState[]{EMPTY, STONE_PICKAXE}) {
			for (long sticks : new long[]{0, 1}) {
				assertTrue(needsSticks(hand, true, sticks));
				assertFalse(needsSticks(hand, true, sticks + 8));
				assertFalse(needsSticks(hand, true, sticks + 8 - 2));
			}
		}
	}

	@Test
	void exactInitialIngredientsLeaveARecoverableUpgradeShortage() {
		assertFalse(needsSticks(EMPTY, true, 2));
		// Crafting the first pickaxe uses both sticks; upgrading must now request another batch.
		assertTrue(needsSticks(STONE_PICKAXE, true, 0));
		assertFalse(needsSticks(STONE_PICKAXE, true, 8));
		assertFalse(needsSticks(POLISHED_STONE_PICKAXE, true, 6));
		assertFalse(needsSticks(POLISHED_STONE_PICKAXE, true, 0));
	}
}
