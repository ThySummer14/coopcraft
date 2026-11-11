package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettlementCommandsTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private CommandDispatcher<Integer> dispatcher() {
		var dispatcher = new CommandDispatcher<Integer>();
		dispatcher.register(SettlementCommands.commandTree(level -> level >= 2, (context, action, option) -> 1));
		return dispatcher;
	}

	@Test
	void ordinaryPlayersOnlyHaveReadCommands() throws Exception {
		var dispatcher = dispatcher();
		assertEquals(1, dispatcher.execute("coop settlement status", 0));
		assertEquals(1, dispatcher.execute("coop settlement materials", 0));
		for (int level : List.of(0, 1)) {
			for (String command : List.of("simulation survival true", "import stone 1", "export stone 1")) {
				assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement " + command, level));
			}
		}
	}

	@Test
	void operatorGrammarAllowsOnlyExplicitFeaturesResourcesAndBounds() throws Exception {
		var dispatcher = dispatcher();
		for (String feature : SettlementCommands.FEATURES) {
			assertEquals(1, dispatcher.execute("coop settlement simulation " + feature + " true", 2));
			assertEquals(1, dispatcher.execute("coop settlement simulation " + feature + " false", 2));
		}
		for (String action : List.of("import", "export")) {
			for (var type : SettlementCommands.RESOURCES) {
				String command = "coop settlement " + action + " " + type.name().toLowerCase(java.util.Locale.ROOT);
				assertEquals(1, dispatcher.execute(command + " 1", 2));
				assertEquals(1, dispatcher.execute(command + " 4096", 2));
				for (String count : List.of("0", "-1", "4097", "2147483648", "1.5")) {
					assertThrows(CommandSyntaxException.class, () -> dispatcher.execute(command + " " + count, 2));
				}
			}
			assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement " + action + " diamond 1", 2));
		}
		assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement simulation unknown true", 2));
		assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("coop settlement simulation survival yes", 2));
	}

	@Test
	void resourceLiteralsProvideWhitelistCompletion() throws Exception {
		var dispatcher = dispatcher();
		var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse("coop settlement import ", 2)).get();
		assertEquals(SettlementCommands.RESOURCES.stream().map(type -> type.name().toLowerCase(java.util.Locale.ROOT)).sorted().toList(),
			suggestions.getList().stream().map(suggestion -> suggestion.getText()).toList());
	}

	@Test
	void choosesNearestRegisteredCenterWithInclusive64BlockBoundary() {
		var near = new Settlement(UUID.randomUUID(), new BlockPos(4, 0, 0), "Near", 0);
		var far = new Settlement(UUID.randomUUID(), new BlockPos(64, 0, 0), "Far", 0);
		Vec3 origin = BlockPos.ZERO.getCenter();
		assertEquals(near, SettlementCommands.nearest(List.of(far, near), origin).orElseThrow());
		assertEquals(far, SettlementCommands.nearest(List.of(far), origin).orElseThrow());
		assertTrue(SettlementCommands.nearest(List.of(far), origin.add(-0.01, 0, 0)).isEmpty());
		assertTrue(SettlementCommands.nearest(List.of(), origin).isEmpty());
	}

	@Test
	void gapsUseRemainingRequirementsAndDoNotModifyInputs() {
		Map<String, Integer> required = Map.of("stone", 10, "wood", 2, "bed", 1);
		Map<String, Long> available = Map.of("stone", 7L, "wood", 30L);
		assertEquals(Map.of("stone", 3L, "bed", 1L), SettlementCommands.materialGaps(required, available));
		assertEquals(10, required.get("stone"));
		assertEquals(7L, available.get("stone"));
	}

	@Test
	void exportUsesMatchingBatchOrderAndReportsPartialCapacityExactly() {
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(BlockPos.ZERO, "Test", 0);
		var state = data.getOrCreateSimulation(settlement);
		state.world().importExternalBatch(ResourceType.STONE, 2, state.campOwner());
		state.world().importExternalBatch(ResourceType.WOOD, 10, state.campOwner());
		state.world().importExternalBatch(ResourceType.STONE, 8, state.campOwner());
		var batches = List.copyOf(state.world().batchesAt(state.campOwner()));
		var chest = new SimpleContainer(new ItemStack(Items.COBBLESTONE, 60));
		assertEquals(4, SettlementCommands.export(data, settlement, chest, ResourceType.STONE, 9));
		assertEquals(64, chest.countItem(Items.COBBLESTONE));
		assertFalse(state.world().batchesAt(state.campOwner()).stream()
			.anyMatch(batch -> batch.batchId().equals(batches.get(0).batchId())));
		assertEquals(10, state.world().batch(batches.get(1).batchId()).quantity());
		assertEquals(6, state.world().batch(batches.get(2).batchId()).quantity());
		assertEquals(0, SettlementCommands.export(data, settlement, chest, ResourceType.STONE, 1));
		state.world().assertConservation();
	}

	@Test
	void serviceRejectsProductionWithoutSurvivalAndPreservesInventory() {
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(BlockPos.ZERO, "Test", 0);
		var state = data.getOrCreateSimulation(settlement);
		state.world().importExternalBatch(ResourceType.TUBERS, 8, state.campOwner());
		assertThrows(IllegalStateException.class,
			() -> SettlementSimulationService.setProductionEnabled(data, settlement, true));
		assertFalse(state.productionEnabled());
		assertFalse(state.survivalEnabled());
		assertEquals(8, state.world().batchesAt(state.campOwner()).getFirst().quantity());
		state.world().assertConservation();
	}

	@Test
	void emptyExportDoesNotCreateSimulation() {
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(BlockPos.ZERO, "Test", 0);
		assertEquals(0, SettlementCommands.export(data, settlement, new SimpleContainer(1), ResourceType.STONE, 2));
		assertTrue(data.simulationOf(settlement).isEmpty());
	}
}
