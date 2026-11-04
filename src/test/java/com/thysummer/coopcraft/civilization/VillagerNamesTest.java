package com.thysummer.coopcraft.civilization;

import java.util.UUID;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillagerNamesTest {
	@Test
	void namesAreDeterministicAndStructured() {
		UUID villagerId = UUID.fromString("d1feb217-a277-4a74-9a4d-b159d633a681");

		assertEquals(VillagerNames.fullName(villagerId), VillagerNames.fullName(villagerId));
		assertTrue(VillagerNames.fullName(villagerId).startsWith(VillagerNames.givenName(villagerId) + " "));
		assertTrue(VillagerNames.fullName(villagerId).endsWith(VillagerNames.familyName(villagerId)));
		assertNotEquals(VillagerNames.fullName(villagerId), VillagerNames.fullName(UUID.randomUUID()));
	}

	@Test
	void childrenInheritTheFirstParentsFamilyName() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(new BlockPos(4, 64, 8), "Testhaven", 0);
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		UUID firstParent = UUID.randomUUID();
		UUID secondParent = UUID.randomUUID();
		UUID child = UUID.randomUUID();
		state.mapResident(firstParent, false, 80);
		state.mapResident(secondParent, false, 80);
		state.mapResidentWithParents(child, 80, firstParent, secondParent);

		String name = VillagerNames.nameFor(data, settlement, child);

		assertTrue(name.startsWith(VillagerNames.givenName(child) + " "));
		assertTrue(name.endsWith(VillagerNames.familyName(firstParent)));
	}
}
