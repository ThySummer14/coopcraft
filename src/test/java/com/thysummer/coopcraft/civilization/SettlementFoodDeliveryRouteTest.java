package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementFoodDeliveryRouteTest {
	private static final BlockPos START = new BlockPos(0, 64, 0);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	private static class Probe implements SettlementMaterialScouting.Probe {
		final Map<BlockPos, BlockState> terrain = new HashMap<>();
		final Set<BlockPos> unavailable = new HashSet<>(), notTicking = new HashSet<>(), protectedCells = new HashSet<>();
		boolean bounds = true;
		int reads, accessChecks;
		public boolean inBounds(BlockPos pos) { accessChecks++; return bounds; }
		public boolean loaded(BlockPos pos) { return !unavailable.contains(pos); }
		public boolean ticking(BlockPos pos) { return !notTicking.contains(pos); }
		public boolean blocked(BlockPos pos) { return protectedCells.contains(pos); }
		public BlockState state(BlockPos pos) {
			assertTrue(bounds && loaded(pos) && ticking(pos) && !blocked(pos), "State read without accessibility");
			reads++;
			return terrain.getOrDefault(pos, (pos.getY() == START.getY() - 1 ? Blocks.STONE : Blocks.AIR).defaultBlockState());
		}
	}

	@Test void straightRouteIncludesStartAndStopsAtCardinalNeighbor() {
		Probe probe = new Probe(); BlockPos recipient = START.offset(4, 0, 0);
		List<BlockPos> route = SettlementFoodDeliveryRoute.find(START, recipient, probe);
		assertEquals(List.of(START, START.offset(1, 0, 0), START.offset(2, 0, 0), START.offset(3, 0, 0)), route);
		assertFalse(route.contains(recipient));
		assertTrue(SettlementFoodDeliveryRoute.valid(route, START, recipient, probe));
		assertThrows(UnsupportedOperationException.class, () -> route.add(recipient));
		assertEquals(List.of(START), SettlementFoodDeliveryRoute.find(START, START.offset(1, 0, 0), probe));
		assertTrue(SettlementFoodDeliveryRoute.valid(List.of(START), START, START.offset(1, 0, 0), probe));
	}

	@Test void detoursAroundObstacleWithClearShoulders() {
		Probe probe = new Probe(); probe.terrain.put(START.offset(2, 0, 0), Blocks.STONE.defaultBlockState());
		BlockPos recipient = START.offset(4, 0, 0);
		List<BlockPos> route = SettlementFoodDeliveryRoute.find(START, recipient, probe);
		assertFalse(route.isEmpty());
		assertTrue(route.stream().anyMatch(node -> Math.abs(node.getZ()) >= 2));
		assertTrue(SettlementFoodDeliveryRoute.valid(route, START, recipient, probe));
		assertEquals(1, route.getLast().distManhattan(recipient));
	}

	@Test void sealedBarrierHasNoPartialRoute() {
		Probe probe = new Probe();
		for (int z = -9; z <= 9; z++) probe.terrain.put(START.offset(2, 0, z), Blocks.STONE.defaultBlockState());
		assertTrue(SettlementFoodDeliveryRoute.find(START, START.offset(4, 0, 0), probe).isEmpty());
	}

	@Test void endpointsMustBeDistinctSameHeightAndWithinCircularRadiusWithoutReadingTerrain() {
		for (BlockPos recipient : List.of(START, START.above(), START.offset(9, 0, 0), START.offset(8, 0, 1),
			new BlockPos(Integer.MAX_VALUE, 64, Integer.MIN_VALUE))) {
			Probe probe = new Probe();
			assertTrue(SettlementFoodDeliveryRoute.find(START, recipient, probe).isEmpty());
			assertFalse(SettlementFoodDeliveryRoute.valid(List.of(START), START, recipient, probe));
			assertEquals(0, probe.reads); assertEquals(0, probe.accessChecks);
		}
		Probe probe = new Probe();
		assertFalse(SettlementFoodDeliveryRoute.find(START, START.offset(8, 0, 0), probe).isEmpty());
		assertTrue(SettlementFoodDeliveryRoute.find(null, START, probe).isEmpty());
		assertTrue(SettlementFoodDeliveryRoute.find(START, null, probe).isEmpty());
	}

	@Test void explorationBudgetCountsEveryTerrainCandidateAndFailsClosed() {
		Probe probe = new Probe();
		// Inside the radius, but a breadth-first search reaches its 128-cell cap before Manhattan distance ten.
		assertTrue(SettlementFoodDeliveryRoute.find(START, START.offset(5, 0, 6), probe).isEmpty());
		assertEquals(SettlementFoodDeliveryRoute.MAX_EXPLORED * 27, probe.reads);
		assertEquals(SettlementFoodDeliveryRoute.MAX_EXPLORED * 27, probe.accessChecks);
	}

	@Test void unloadedUntickedProtectedAndOutOfBoundsShouldersRejectBeforeAnyStateRead() {
		for (int failure = 0; failure < 4; failure++) {
			Probe probe = new Probe(); BlockPos corner = START.offset(1, 1, 1);
			if (failure == 0) probe.unavailable.add(corner);
			if (failure == 1) probe.notTicking.add(corner);
			if (failure == 2) probe.protectedCells.add(corner);
			if (failure == 3) probe.bounds = false;
			assertTrue(SettlementFoodDeliveryRoute.find(START, START.offset(4, 0, 0), probe).isEmpty());
			assertEquals(0, probe.reads);
		}
	}

	@Test void unsafeSupportAndNonAirShouldersCannotBeUsedEvenWhenAlreadyAdjacent() {
		for (var block : List.of(Blocks.FARMLAND, Blocks.MAGMA_BLOCK, Blocks.SAND, Blocks.ICE, Blocks.LAVA, Blocks.CACTUS)) {
			Probe probe = new Probe(); probe.terrain.put(START.offset(1, -1, 1), block.defaultBlockState());
			assertTrue(SettlementFoodDeliveryRoute.find(START, START.offset(1, 0, 0), probe).isEmpty(), block.toString());
		}
		for (var block : List.of(Blocks.OAK_DOOR, Blocks.WATER, Blocks.FIRE, Blocks.SWEET_BERRY_BUSH)) {
			Probe probe = new Probe(); probe.terrain.put(START.offset(-1, 1, -1), block.defaultBlockState());
			assertTrue(SettlementFoodDeliveryRoute.find(START, START.offset(1, 0, 0), probe).isEmpty(), block.toString());
		}
	}

	@Test void validatorRejectsJumpsDiagonalsLoopsWrongStartHeightRadiusAndRecipientCell() {
		BlockPos recipient = START.offset(3, 0, 0); Probe probe = new Probe();
		for (List<BlockPos> route : List.of(List.<BlockPos>of(), List.of(START.offset(1, 0, 0), START.offset(2, 0, 0)),
			List.of(START, START.offset(2, 0, 0)), List.of(START, START.offset(1, 0, 1), START.offset(2, 0, 0)),
			List.of(START, START.above(), START.offset(2, 0, 0)),
			List.of(START, START.offset(1, 0, 0), START, START.offset(1, 0, 0), START.offset(2, 0, 0)),
			List.of(START, START.offset(9, 0, 0), START.offset(2, 0, 0)),
			List.of(START, START.offset(1, 0, 0), START.offset(2, 0, 0), recipient, START.offset(2, 0, 0)))) {
			assertFalse(SettlementFoodDeliveryRoute.valid(route, START, recipient, probe));
		}
		assertFalse(SettlementFoodDeliveryRoute.valid(List.of(START, START.offset(1, 0, 0)), START, recipient, probe));
		List<BlockPos> overBudget = new ArrayList<>();
		for (int i = 0; i < SettlementFoodDeliveryRoute.MAX_STEPS + 2; i++) overBudget.add(START);
		overBudget.set(overBudget.size() - 1, START.offset(2, 0, 0));
		assertFalse(SettlementFoodDeliveryRoute.valid(overBudget, START, recipient, probe));
	}

	@Test void changedTerrainAccessibilityAndRecipientInvalidateExistingRoute() {
		BlockPos recipient = START.offset(4, 0, 0); Probe probe = new Probe();
		List<BlockPos> route = SettlementFoodDeliveryRoute.find(START, recipient, probe);
		assertTrue(SettlementFoodDeliveryRoute.valid(route, START, recipient, probe));
		assertFalse(SettlementFoodDeliveryRoute.valid(route, START, recipient.offset(1, 0, 0), probe));
		BlockPos corner = START.offset(3, 1, 1);
		probe.unavailable.add(corner);
		assertFalse(SettlementFoodDeliveryRoute.valid(route, START, recipient, probe));
		probe.unavailable.clear(); probe.terrain.put(corner, Blocks.STONE.defaultBlockState());
		assertFalse(SettlementFoodDeliveryRoute.valid(route, START, recipient, probe));
	}
}
