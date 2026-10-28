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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

public final class SettlementSurvivalGameTest implements FabricGameTest {
	private static final BlockPos BELL = new BlockPos(2, 0, 2);
	private static final BlockPos CHEST = new BlockPos(1, 0, 1);
	private static final BlockPos VILLAGER = new BlockPos(2, 0, 3);

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void enabledSurvivalUsesOnlyAbstractFood(GameTestHelper helper) {
		helper.setBlock(CHEST, Blocks.CHEST);
		Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		BlockPos bell = helper.absolutePos(BELL);
		Settlement settlement = data.getOrCreate(bell, "Survival Settlement", 0);
		Villager villager = helper.spawn(EntityType.VILLAGER, VILLAGER);
		data.registerLoadedResidents(helper.getLevel(), settlement, List.of(villager));
		String personId = data.simulationOf(settlement).orElseThrow().residentId(villager.getUUID()).orElseThrow();
		SettlementStockpile.insert(chest, new ItemStack(Items.POTATO, 10));
		SettlementSimulationService.importFromStockpile(data, settlement, chest, ResourceType.TUBERS, 10);
		SettlementSimulationService.setSurvivalEnabled(helper.getLevel(), data, settlement, true);

		helper.getLevel().setDayTime(Level.TICKS_PER_DAY);
		SettlementSimulationService.tick(helper.getLevel(), data, settlement);
		helper.assertValueEqual(55, data.simulationOf(settlement).orElseThrow().world().person(personId).hunger(), "Daily hunger must advance once");
		helper.assertValueEqual(45, data.needsOf(settlement, villager.getUUID()).satiety(), "Real satiety must follow core hunger");
		for (long time = 0; time < 600; time++) {
			SettlementNeeds.tick(helper.getLevel(), data, settlement, time);
		}
		helper.assertValueEqual(45, data.needsOf(settlement, villager.getUUID()).satiety(), "Legacy hunger must not decay twice");

		int eaten = SettlementNeeds.eat(helper.getLevel(), data, settlement, villager);
		helper.assertValueEqual(72, eaten, "The resident must eat abstract nutrition");
		helper.assertValueEqual(100, data.needsOf(settlement, villager.getUUID()).satiety(), "Eating must synchronize real satiety");
		helper.assertValueEqual(0, chest.countItem(Items.POTATO), "Abstract eating must not consume real items");
		ResourceBatch remaining = data.simulationOf(settlement).orElseThrow().world().batches().getFirst();
		helper.assertValueEqual(2L, remaining.quantity(), "Only three abstract potatoes may be consumed");
		SimulationEvent meal = data.simulationOf(settlement).orElseThrow().world().ledger().events().getLast();
		helper.assertValueEqual(EventType.CONSUME, meal.type(), "The meal must remain observable");
		helper.assertTrue(
			data.simulationOf(settlement).orElseThrow().world().ledger().traceOrigins(meal.eventId()).stream()
				.anyMatch(event -> event.type() == EventType.EXTERNAL_IMPORT),
			"The meal must trace to the real container import"
		);

		CompoundTag saved = data.save(new CompoundTag(), helper.getLevel().registryAccess());
		SettlementSavedData reloaded = SettlementSavedData.load(saved, helper.getLevel().registryAccess());
		Settlement restoredSettlement = reloaded.find(bell).orElseThrow();
		SettlementSimulationState restored = reloaded.simulationOf(restoredSettlement).orElseThrow();
		helper.assertTrue(restored.survivalEnabled(), "The survival switch must survive reload");
		helper.assertValueEqual(1L, restored.lastSurvivalDay(), "The last simulated day must survive reload");
		helper.assertValueEqual(0, restored.world().person(personId).hunger(), "Hunger must survive reload");
		helper.assertValueEqual(2L, restored.world().batch(remaining.batchId()).quantity(), "Food balance must survive reload");

		helper.getLevel().setDayTime(10L * Level.TICKS_PER_DAY);
		SettlementSimulationService.tick(helper.getLevel(), reloaded, restoredSettlement);
		helper.assertValueEqual(35, restored.world().person(personId).hunger(), "Offline days must not be caught up repeatedly");
		helper.assertValueEqual(10L, restored.lastSurvivalDay(), "Only the current day may be settled");
		restored.world().assertConservation();
		villager.kill();
		helper.succeed();
	}
}
