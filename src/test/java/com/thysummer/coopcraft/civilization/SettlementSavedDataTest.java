package com.thysummer.coopcraft.civilization;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementSavedDataTest {
	@Test
	void defaultsNewSettlementsToPrimitiveCommune() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(new BlockPos(10, 64, 10), "Testhaven", 4);

		assertEquals(SocialOrganization.PRIMITIVE_COMMUNE, data.socialOrganizationOf(settlement));
		assertEquals(SocialOrganization.OwnershipRegime.COMMUNAL, data.socialOrganizationOf(settlement).ownership());
		assertEquals(SocialOrganization.LaborRegime.COMMON_OBLIGATION, data.socialOrganizationOf(settlement).labor());
		assertEquals(SocialOrganization.DistributionPolicy.BASIC_NEEDS, data.socialOrganizationOf(settlement).distribution());
	}

	@Test
	void migratesOldSettlementWithoutSocialOrganization() {
		SettlementSavedData data = new SettlementSavedData();
		BlockPos center = new BlockPos(10, 64, 10);
		Settlement settlement = data.getOrCreate(center, "Testhaven", 4);
		CompoundTag tag = data.save(new CompoundTag(), null);
		tag.putInt("DataVersion", 3);
		tag.remove("SocialOrganizations");

		SettlementSavedData restored = SettlementSavedData.load(tag, null);

		assertEquals(settlement, restored.find(center).orElseThrow());
		assertEquals(SocialOrganization.PRIMITIVE_COMMUNE, restored.socialOrganizationOf(settlement));
		assertTrue(restored.simulationOf(settlement).isEmpty());
		assertEquals(9, restored.save(new CompoundTag(), null).getInt("DataVersion"));
	}

	@Test
	void migratesSimulationWithoutSurvivalFlag() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(new BlockPos(10, 64, 10), "Testhaven", 4);
		data.getOrCreateSimulation(settlement);
		CompoundTag tag = data.save(new CompoundTag(), null);
		CompoundTag simulation = tag.getList("Simulations", net.minecraft.nbt.ListTag.TAG_COMPOUND).getCompound(0);
		simulation.remove("SurvivalEnabled");
		simulation.remove("LastSurvivalDay");

		SettlementSavedData restored = SettlementSavedData.load(tag, null);

		SettlementSimulationState state = restored.simulationOf(settlement).orElseThrow();
		assertFalse(state.survivalEnabled());
		assertEquals(Long.MIN_VALUE, state.lastSurvivalDay());
	}

	@Test
	void reusesNearbySettlement() {
		SettlementSavedData data = new SettlementSavedData();
		BlockPos center = new BlockPos(10, 64, 10);
		Settlement first = data.getOrCreate(center, "Testhaven", 4);

		Settlement nearby = data.getOrCreate(center.offset(24, 0, 0), "Otherwick", 5);

		assertSame(first, nearby);
		assertEquals(1, data.size());
	}

	@Test
	void keepsDistantSettlementsSeparate() {
		SettlementSavedData data = new SettlementSavedData();
		BlockPos center = new BlockPos(10, 64, 10);
		Settlement first = data.getOrCreate(center, "Testhaven", 4);

		Settlement distant = data.getOrCreate(center.offset(96, 0, 0), "Otherwick", 5);

		assertNotEquals(first, distant);
		assertEquals(2, data.size());
	}

	@Test
	void preservesSettlementThroughSaveRoundTrip() {
		SettlementSavedData data = new SettlementSavedData();
		BlockPos center = new BlockPos(-20, 70, 35);
		Settlement expected = data.getOrCreate(center, "Testhaven", 12);

		CompoundTag tag = data.save(new CompoundTag(), null);
		SettlementSavedData restored = SettlementSavedData.load(tag, null);

		assertEquals(1, restored.size());
		assertEquals(expected, restored.find(center).orElseThrow());
	}

	@Test
	void preservesMembersAndStockpileThroughSaveRoundTrip() {
		SettlementSavedData data = new SettlementSavedData();
		BlockPos center = new BlockPos(4, 65, 8);
		Settlement settlement = data.getOrCreate(center, "Testhaven", 3);
		UUID villagerId = UUID.randomUUID();
		BlockPos stockpile = center.offset(2, 1, 0);
		data.registerMember(settlement, villagerId, 9);
		data.setStockpile(settlement, stockpile);
		data.setSatiety(settlement, villagerId, 42);

		CompoundTag tag = data.save(new CompoundTag(), null);
		SettlementSavedData restored = SettlementSavedData.load(tag, null);

		assertTrue(restored.isMember(settlement, villagerId));
		assertEquals(1, restored.registeredCount(settlement));
		assertEquals(9, restored.membersOf(settlement).getFirst().joinedDay());
		assertEquals(VillagerPersonality.fromVillager(villagerId), restored.personalityOf(settlement, villagerId));
		assertEquals(42, restored.needsOf(settlement, villagerId).satiety());
		assertEquals(stockpile, restored.getStockpile(settlement).orElseThrow());
		tag.remove("MemberNeeds");
		assertEquals(80, SettlementSavedData.load(tag, null).needsOf(settlement, villagerId).satiety());
		restored.removeMember(villagerId);
		assertEquals(MemberNeeds.DEFAULT, restored.needsOf(settlement, villagerId));
	}

	@Test
	void migratesMemberWithoutPersonality() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(new BlockPos(4, 65, 8), "Testhaven", 3);
		UUID villagerId = UUID.randomUUID();
		data.registerMember(settlement, villagerId, 9);
		CompoundTag tag = data.save(new CompoundTag(), null);
		tag.getList("Members", net.minecraft.nbt.ListTag.TAG_COMPOUND).getCompound(0).remove("Personality");

		SettlementSavedData restored = SettlementSavedData.load(tag, null);

		assertEquals(VillagerPersonality.fromVillager(villagerId), restored.personalityOf(settlement, villagerId));
	}

	@Test
	void preservesAndClearsLeader() {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(new BlockPos(4, 65, 8), "Testhaven", 3);
		UUID leaderId = UUID.randomUUID();
		data.registerMember(settlement, leaderId, 9);
		data.setLeader(settlement, leaderId);

		SettlementSavedData restored = SettlementSavedData.load(data.save(new CompoundTag(), null), null);

		assertEquals(leaderId, restored.leaderOf(settlement).orElseThrow());
		restored.removeMember(leaderId);
		assertTrue(restored.leaderOf(settlement).isEmpty());
	}

	@Test
	void preservesResourceSitesAndCraftingStationThroughSaveRoundTrip() {
		SettlementSavedData data = new SettlementSavedData();
		BlockPos center = new BlockPos(7, 64, 9);
		Settlement settlement = data.getOrCreate(center, "Testhaven", 2);
		BlockPos resource = center.offset(3, 0, 1);
		BlockPos removedResource = center.offset(4, 0, 1);
		BlockPos station = center.offset(1, 0, 3);
		data.toggleResource(settlement, resource);
		data.toggleResource(settlement, removedResource);
		data.toggleResource(settlement, removedResource);
		data.setCraftingStation(settlement, station);

		CompoundTag tag = data.save(new CompoundTag(), null);
		SettlementSavedData restored = SettlementSavedData.load(tag, null);

		assertTrue(restored.resourcesOf(settlement).contains(resource));
		assertFalse(restored.resourcesOf(settlement).contains(removedResource));
		assertEquals(station, restored.getCraftingStation(settlement).orElseThrow());
	}

	@Test
	void preservesConstructionHousingAndGrindingThroughSaveRoundTrip() {
		SettlementSavedData data = new SettlementSavedData();
		BlockPos center = new BlockPos(12, 65, -7);
		Settlement settlement = data.getOrCreate(center, "Testhaven", 2);
		ConstructionJob job = new ConstructionJob(ConstructionSystem.STONE_COTTAGE, center.north(6), 23);
		BlockPos bed = center.north(3);
		BlockPos grindingStation = center.east(2);
		data.setConstructionJob(settlement, job);
		data.advanceConstruction(settlement);
		data.addHousingBed(settlement, bed);
		data.addHousingBed(settlement, bed);
		data.setGrindingStation(settlement, grindingStation);

		CompoundTag tag = data.save(new CompoundTag(), null);
		SettlementSavedData restored = SettlementSavedData.load(tag, null);

		assertEquals(new ConstructionJob(job.blueprintId(), job.origin(), 24), restored.getConstructionJob(settlement).orElseThrow());
		assertEquals(1, restored.housingCapacity(settlement));
		assertEquals(grindingStation, restored.getGrindingStation(settlement).orElseThrow());
		restored.completeConstruction(settlement);
		assertTrue(restored.getConstructionJob(settlement).isEmpty());
		assertEquals(1, restored.housingCapacity(settlement));
	}
}
