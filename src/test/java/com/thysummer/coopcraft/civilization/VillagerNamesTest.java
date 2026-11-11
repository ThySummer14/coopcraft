package com.thysummer.coopcraft.civilization;

import java.util.UUID;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillagerNamesTest {
	@Test
	void namesAreDeterministicChineseFullNames() {
		UUID villagerId = UUID.fromString("d1feb217-a277-4a74-9a4d-b159d633a681");

		String name = VillagerNames.fullName(villagerId);
		assertEquals(name, VillagerNames.fullName(villagerId));
		assertTrue(name.startsWith(VillagerNames.familyName(villagerId)));
		assertTrue(name.endsWith(VillagerNames.givenName(villagerId)));
		assertTrue(name.codePoints().allMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN));
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

		assertTrue(name.startsWith(VillagerNames.familyName(firstParent)));
		assertTrue(name.endsWith(VillagerNames.givenName(child)));
		String legacyName = VillagerNames.legacyNameFor(data, settlement, child);
		assertTrue(VillagerNames.isLegacyGeneratedName(data, settlement, child, legacyName));
	}

	@Test
	void onlyLegacyGeneratedNamesAreMigrationCandidates() {
		UUID villagerId = UUID.fromString("d1feb217-a277-4a74-9a4d-b159d633a681");

		assertTrue(VillagerNames.isLegacyGeneratedName(villagerId, VillagerNames.legacyFullName(villagerId)));
		assertFalse(VillagerNames.isLegacyGeneratedName(villagerId, VillagerNames.fullName(villagerId)));
		assertFalse(VillagerNames.isLegacyGeneratedName(villagerId, "玩家起的名字"));
		assertFalse(VillagerNames.isLegacyGeneratedName(villagerId, "村民 12"));
	}
}
