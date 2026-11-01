package com.thysummer.coopcraft.civilization;

import java.util.List;

import com.thysummer.coopcraft.civilization.simulation.ResourceBatch;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;

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

public final class SettlementDistributionGameTest implements FabricGameTest {
	private static final BlockPos BELL = new BlockPos(2, 0, 2);
	private static final BlockPos CHEST = new BlockPos(1, 0, 1);
	private static final BlockPos VILLAGER = new BlockPos(2, 0, 3);

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void dailyFoodCapSurvivesReloadAndResetsNextDay(GameTestHelper helper) {
		helper.setBlock(CHEST, Blocks.CHEST);
		Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		BlockPos bell = helper.absolutePos(BELL);
		Settlement settlement = data.getOrCreate(bell, "Distribution Settlement", 0);
		data.setStockpile(settlement, helper.absolutePos(CHEST));
		Villager villager = helper.spawn(EntityType.VILLAGER, VILLAGER);
		data.registerLoadedResidents(helper.getLevel(), settlement, List.of(villager));
		SettlementSimulationState state = data.simulationOf(settlement).orElseThrow();
		String personId = state.residentId(villager.getUUID()).orElseThrow();
		int inserted = SettlementStockpile.insert(chest, new ItemStack(Items.COD, 10));
		helper.assertValueEqual(10, inserted, "The test must insert ten real fish");
		int imported = SettlementSimulationService.importFromStockpile(data, settlement, chest, ResourceType.FISH, 10);
		helper.assertValueEqual(10, imported, "The test must import ten real fish");
		helper.assertValueEqual(0, chest.countItem(Items.COD), "The import must empty the real chest");
		SettlementSimulationService.setSurvivalEnabled(helper.getLevel(), data, settlement, true);
		state.world().person(personId).setHunger(100);
		ResourceBatch stock = state.world().batchesAt(state.campOwner()).stream()
			.filter(batch -> batch.resourceType() == ResourceType.FISH)
			.findFirst().orElseThrow();
		helper.assertValueEqual(10L, stock.quantity(), "The test must begin with ten abstract fish");

		helper.assertTrue(
			SettlementSimulationService.hasFoodFor(data, settlement, villager.getUUID()),
			"A hungry resident with food and allowance must be able to eat"
		);
		helper.assertValueEqual(60, SettlementSimulationService.eat(data, settlement, villager.getUUID()), "The first meal must consume three fish");
		helper.assertValueEqual(40, state.world().person(personId).hunger(), "Three fish must restore sixty nutrition");
		helper.assertValueEqual(7L, stock.quantity(), "Only three abstract fish may leave the camp");
		helper.assertValueEqual(0, state.remainingFoodUnits(personId), "The daily allowance must be exhausted");
		helper.assertFalse(
			SettlementSimulationService.hasFoodFor(data, settlement, villager.getUUID()),
			"Remaining camp food must not bypass the resident's daily cap"
		);
		helper.assertValueEqual(0, SettlementSimulationService.eat(data, settlement, villager.getUUID()), "A second same-day meal must be rejected");
		helper.assertValueEqual(7L, stock.quantity(), "A rejected meal must not consume stock");

		CompoundTag saved = data.save(new CompoundTag(), helper.getLevel().registryAccess());
		SettlementSavedData reloaded = SettlementSavedData.load(saved, helper.getLevel().registryAccess());
		Settlement restoredSettlement = reloaded.find(bell).orElseThrow();
		SettlementSimulationState restored = reloaded.simulationOf(restoredSettlement).orElseThrow();
		helper.assertValueEqual(0, restored.remainingFoodUnits(personId), "The used allowance must survive reload");
		helper.assertValueEqual(0, SettlementSimulationService.eat(reloaded, restoredSettlement, villager.getUUID()), "Reload must not reset the daily cap");
		helper.assertValueEqual(40, restored.world().person(personId).hunger(), "Rejected reload meals must not change hunger");
		helper.assertValueEqual(7L, restored.world().batch(stock.batchId()).quantity(), "Rejected reload meals must not consume stock");
		restored.world().assertConservation();

		helper.getLevel().setDayTime(helper.getLevel().getDayTime() + Level.TICKS_PER_DAY);
		SettlementSimulationService.tick(helper.getLevel(), data, settlement);
		helper.assertValueEqual(75, state.world().person(personId).hunger(), "The next survival day must apply hunger once");
		helper.assertValueEqual(3, state.remainingFoodUnits(personId), "The next core day must restore the allowance");
		helper.assertValueEqual(60, SettlementSimulationService.eat(data, settlement, villager.getUUID()), "The next-day meal must use the restored allowance");
		helper.assertValueEqual(15, state.world().person(personId).hunger(), "The next-day meal must consume exactly three fish");
		helper.assertValueEqual(4L, stock.quantity(), "Seven remaining fish minus three must leave four");
		state.world().assertConservation();
		helper.assertValueEqual(0, chest.countItem(Items.COD), "Abstract meals must never consume the real chest twice");
		villager.kill();
		helper.succeed();
	}
}
