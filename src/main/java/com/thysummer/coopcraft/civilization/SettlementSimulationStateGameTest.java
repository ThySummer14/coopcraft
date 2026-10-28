package com.thysummer.coopcraft.civilization;

import java.util.List;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.ResourceBatch;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public final class SettlementSimulationStateGameTest implements FabricGameTest {
	private static final BlockPos BELL = new BlockPos(2, 0, 2);
	private static final BlockPos CHEST = new BlockPos(1, 0, 1);
	private static final BlockPos VILLAGER = new BlockPos(2, 0, 3);

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void reloadKeepsSingleInventoryAuthority(GameTestHelper helper) {
		helper.setBlock(CHEST, Blocks.CHEST);
		Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		BlockPos bell = helper.absolutePos(BELL);
		Settlement settlement = data.getOrCreate(bell, "Test Settlement", 0);
		Villager villager = helper.spawn(EntityType.VILLAGER, VILLAGER);
		data.registerLoadedResidents(helper.getLevel(), settlement, List.of(villager));
		String personId = data.simulationOf(settlement).orElseThrow().residentId(villager.getUUID()).orElseThrow();
		helper.assertValueEqual(20, data.simulationOf(settlement).orElseThrow().world().person(personId).hunger(), "Resident needs must map once");
		SettlementStockpile.insert(chest, new ItemStack(Items.POTATO, 8));

		int imported = SettlementSimulationService.importFromStockpile(data, settlement, chest, ResourceType.TUBERS, 5);
		helper.assertValueEqual(5, imported, "The first handoff must import five potatoes");
		helper.assertValueEqual(3, chest.countItem(Items.POTATO), "Imported potatoes must leave the real chest");
		ResourceBatch importedBatch = data.simulationOf(settlement).orElseThrow().world().batches().getFirst();
		String batchId = importedBatch.batchId();

		SettlementSavedData firstReload = reload(helper, data);
		Settlement firstSettlement = firstReload.find(bell).orElseThrow();
		SettlementSimulationState firstState = firstReload.simulationOf(firstSettlement).orElseThrow();
		helper.assertValueEqual(personId, firstState.residentId(villager.getUUID()).orElseThrow(), "Resident UUID mapping must survive reload");
		helper.assertValueEqual(5L, firstState.world().batch(batchId).quantity(), "Abstract balance must survive reload");
		helper.assertValueEqual(
			importedBatch.originEventId(), firstState.world().batch(batchId).originEventId(), "Batch origin must survive reload"
		);

		int firstExport = SettlementSimulationService.exportToStockpile(firstReload, firstSettlement, batchId, 2, chest);
		helper.assertValueEqual(2, firstExport, "The first reload must allow a partial export");
		helper.assertValueEqual(5, chest.countItem(Items.POTATO), "Partial export must return exactly two potatoes");
		helper.assertValueEqual(3L, firstState.world().batch(batchId).quantity(), "Three potatoes must remain abstract");

		SettlementSavedData secondReload = reload(helper, firstReload);
		Settlement secondSettlement = secondReload.find(bell).orElseThrow();
		SettlementSimulationState secondState = secondReload.simulationOf(secondSettlement).orElseThrow();
		int secondExport = SettlementSimulationService.exportToStockpile(secondReload, secondSettlement, batchId, 3, chest);
		helper.assertValueEqual(3, secondExport, "The second reload must preserve the remaining balance");
		helper.assertValueEqual(8, chest.countItem(Items.POTATO), "Two reloads must not duplicate or destroy potatoes");
		helper.assertTrue(secondState.world().batches().isEmpty(), "All abstract stock must be exported exactly once");
		List<SimulationEvent> recent = SettlementSimulationService.recentEvents(secondReload, secondSettlement, 3);
		helper.assertValueEqual(3, recent.size(), "Import and both exports must remain observable");
		helper.assertValueEqual(EventType.EXTERNAL_EXPORT, recent.getLast().type(), "The latest event must be the final export");
		secondState.world().assertConservation();
		villager.kill();
		helper.succeed();
	}

	private static SettlementSavedData reload(GameTestHelper helper, SettlementSavedData data) {
		CompoundTag tag = data.save(new CompoundTag(), helper.getLevel().registryAccess());
		return SettlementSavedData.load(tag, helper.getLevel().registryAccess());
	}
}
