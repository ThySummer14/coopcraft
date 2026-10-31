package com.thysummer.coopcraft.civilization;

import com.thysummer.coopcraft.civilization.simulation.Camp;
import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.ResourceBatch;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.civilization.simulation.SimulationWorld;
import com.thysummer.coopcraft.civilization.simulation.StorageOwner;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public final class MinecraftStockpileAdapterGameTest implements FabricGameTest {
	private static final BlockPos CHEST = new BlockPos(1, 0, 1);

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void handsOffStockWithoutDuplication(GameTestHelper helper) {
		helper.setBlock(CHEST, Blocks.CHEST);
		Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));
		SettlementStockpile.insert(chest, new ItemStack(Items.POTATO, 10));
		SimulationWorld world = new SimulationWorld();
		world.registerCamp(new Camp("settlement", "Test Settlement"));
		StorageOwner camp = StorageOwner.camp("settlement");

		int imported = MinecraftStockpileAdapter.importToSimulation(chest, world, camp, ResourceType.TUBERS, 6);
		helper.assertValueEqual(6, imported, "Import must move six real potatoes");
		helper.assertValueEqual(4, chest.countItem(Items.POTATO), "Imported items must leave the real container");
		ResourceBatch batch = world.batchesAt(camp).getFirst();
		helper.assertValueEqual(6L, batch.quantity(), "The simulation must hold the same six items");
		helper.assertValueEqual(10L, chest.countItem(Items.POTATO) + batch.quantity(), "Import must conserve total quantity");

		int exported = MinecraftStockpileAdapter.exportToContainer(world, camp, batch.batchId(), 2, chest);
		helper.assertValueEqual(2, exported, "Export must return two potatoes");
		helper.assertValueEqual(6, chest.countItem(Items.POTATO), "Exported items must return to the real container");
		helper.assertValueEqual(4L, batch.quantity(), "Only exported items may leave the simulation");
		helper.assertValueEqual(10L, chest.countItem(Items.POTATO) + batch.quantity(), "Export must conserve total quantity");
		SimulationEvent export = world.ledger().events().stream()
			.filter(event -> event.type() == EventType.EXTERNAL_EXPORT)
			.findFirst().orElseThrow();
		helper.assertTrue(
			world.ledger().traceOrigins(export.eventId()).stream().anyMatch(event -> event.type() == EventType.EXTERNAL_IMPORT),
			"Export must retain the external import origin"
		);

		SimpleContainer full = new SimpleContainer(1);
		full.setItem(0, new ItemStack(Items.DIRT, 64));
		int blocked = MinecraftStockpileAdapter.exportToContainer(world, camp, batch.batchId(), 4, full);
		helper.assertValueEqual(0, blocked, "A full container must reject the handoff");
		helper.assertValueEqual(4L, batch.quantity(), "A blocked handoff must not consume the abstract batch");

		ItemStack namedCod = new ItemStack(Items.COD, 2);
		namedCod.set(DataComponents.CUSTOM_NAME, Component.literal("keep"));
		SettlementStockpile.insert(chest, namedCod);
		int unsupported = MinecraftStockpileAdapter.importToSimulation(chest, world, camp, ResourceType.FISH, 2);
		helper.assertValueEqual(0, unsupported, "Custom-component items must not be converted");
		helper.assertValueEqual(2, chest.countItem(Items.COD), "Custom-component items must remain in the container");

		SimpleContainer receiving = new SimpleContainer(1);
		int remainder = MinecraftStockpileAdapter.exportToContainer(world, camp, batch.batchId(), 4, receiving);
		helper.assertValueEqual(4, remainder, "The remaining abstract stock must be exportable");
		helper.assertTrue(world.batchesAt(camp).isEmpty(), "The simulation must not retain exported stock");
		helper.assertValueEqual(
			10, chest.countItem(Items.POTATO) + receiving.countItem(Items.POTATO),
			"Round trips must neither create nor destroy items"
		);
		world.assertConservation();
		helper.succeed();
	}
}
