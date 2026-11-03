package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaborAllocatorTest {
	private static final UUID SOCIAL = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID MINER = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final UUID BUILDER = UUID.fromString("00000000-0000-0000-0000-000000000003");
	private static final UUID WOODCUTTER = UUID.fromString("00000000-0000-0000-0000-000000000004");
	private static final UUID CRAFTER = UUID.fromString("00000000-0000-0000-0000-000000000005");
	private static final UUID EXTRA = UUID.fromString("00000000-0000-0000-0000-000000000006");

	private static LaborAllocator.Worker worker(UUID id, int diligence, int selfishness, int sociability) {
		return new LaborAllocator.Worker(id, new VillagerPersonality(diligence, selfishness, sociability));
	}

	@Test
	void smallSettlementsStayGeneralist() {
		Map<UUID, WorkRole> roles = LaborAllocator.allocate(List.of(
			worker(SOCIAL, 50, 0, 100),
			worker(MINER, 100, 50, 0)
		));

		assertEquals(WorkRole.GENERALIST, roles.get(SOCIAL));
		assertEquals(WorkRole.GENERALIST, roles.get(MINER));
	}

	@Test
	void threeAdultsSplitForagingMiningAndBuilding() {
		Map<UUID, WorkRole> roles = LaborAllocator.allocate(List.of(
			worker(SOCIAL, 50, 0, 100),
			worker(MINER, 100, 50, 0),
			worker(BUILDER, 90, 20, 80)
		));

		assertEquals(WorkRole.FORAGER, roles.get(SOCIAL));
		assertEquals(WorkRole.MINER, roles.get(MINER));
		assertEquals(WorkRole.BUILDER, roles.get(BUILDER));
	}

	@Test
	void largerGroupsAddWoodcuttingAndCrafting() {
		List<LaborAllocator.Worker> workers = List.of(
			worker(SOCIAL, 50, 0, 100),
			worker(MINER, 100, 50, 0),
			worker(BUILDER, 90, 20, 80),
			worker(WOODCUTTER, 80, 10, 10),
			worker(CRAFTER, 60, 10, 60),
			worker(EXTRA, 10, 90, 10)
		);

		Map<UUID, WorkRole> roles = LaborAllocator.allocate(workers);

		assertTrue(roles.containsValue(WorkRole.FORAGER));
		assertTrue(roles.containsValue(WorkRole.MINER));
		assertTrue(roles.containsValue(WorkRole.BUILDER));
		assertTrue(roles.containsValue(WorkRole.WOODCUTTER));
		assertTrue(roles.containsValue(WorkRole.CRAFTER));
		assertEquals(1, roles.values().stream().filter(role -> role == WorkRole.GENERALIST).count());
	}

	@Test
	void assignmentIsStableForTheSameWorkers() {
		List<LaborAllocator.Worker> workers = List.of(
			worker(SOCIAL, 50, 0, 100),
			worker(MINER, 100, 50, 0),
			worker(BUILDER, 90, 20, 80)
		);

		assertEquals(LaborAllocator.allocate(workers), LaborAllocator.allocate(workers));
	}
}
