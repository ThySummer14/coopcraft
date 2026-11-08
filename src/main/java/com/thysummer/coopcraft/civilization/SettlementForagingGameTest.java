package com.thysummer.coopcraft.civilization;

import java.util.List;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;

/** Integration regressions for the real AI entry point, not only the foraging predicate. */
public final class SettlementForagingGameTest implements FabricGameTest {
	private static final BlockPos CHEST = new BlockPos(1, 1, 1);
	private static final BlockPos RESIDENT = new BlockPos(2, 1, 2);
	private static final BlockPos BUSH = new BlockPos(3, 1, 2);

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void hungryResidentForagesAndEatsRealBerries(GameTestHelper helper) {
		runForagingLoop(helper, false);
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void hungryResidentForagesIntoOneAbstractInventory(GameTestHelper helper) {
		runForagingLoop(helper, true);
	}

	private static void runForagingLoop(GameTestHelper helper, boolean simulation) {
		helper.setBlock(CHEST.below(), Blocks.STONE);
		helper.setBlock(RESIDENT.below(), Blocks.STONE);
		helper.setBlock(BUSH.below(), Blocks.DIRT);
		helper.setBlock(CHEST, Blocks.CHEST);
		helper.setBlock(BUSH, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3));
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		Settlement settlement = data.getOrCreate(helper.absolutePos(RESIDENT), "Foraging", 0);
		data.setStockpile(settlement, helper.absolutePos(CHEST));
		Villager villager = helper.spawn(EntityType.VILLAGER, RESIDENT);
		villager.setNoAi(true);
		data.registerLoadedResidents(helper.getLevel(), settlement, List.of(villager));
		data.setSatiety(settlement, villager.getUUID(), 20);
		if (simulation) {
			SettlementSimulationService.setSurvivalEnabled(helper.getLevel(), data, settlement, true);
			SettlementSimulationService.setProductionEnabled(data, settlement, true);
		}
		VillagerWorkGoal goal = new VillagerWorkGoal(villager);
		helper.assertTrue(goal.canUse(), "A hungry resident with no food must choose foraging");
		helper.assertValueEqual("forage", goal.status().action(), "The real caller must not invert the shortage condition");
		goal.start();
		for (int tick = 0; tick < 300 && goal.canContinueToUse(); tick++) {
			goal.tick();
		}
		helper.assertValueEqual(1, helper.getBlockState(BUSH).getValue(SweetBerryBushBlock.AGE), "Harvest resets growth without destroying the bush");
		Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));
		int realBefore = chest.countItem(Items.SWEET_BERRIES) + villager.getInventory().countItem(Items.SWEET_BERRIES);
		SettlementSimulationState state = data.simulationOf(settlement).orElseThrow();
		long abstractBefore = state.world().inventoryTotals().get(ResourceType.WILD_FRUIT);
		helper.assertTrue(simulation ? abstractBefore > 0 && realBefore == 0 : realBefore > 0 && abstractBefore == 0,
			"Harvested berries must have exactly one inventory authority");

		helper.assertTrue(goal.canUse(), "The same resident must now choose a real meal request");
		helper.assertValueEqual("eat", goal.status().action(), "Foraged food must be edible in both modes");
		goal.start();
		for (int tick = 0; tick < 300 && goal.canContinueToUse(); tick++) {
			goal.tick();
		}
		helper.assertTrue(data.needsOf(settlement, villager.getUUID()).satiety() > 20, "Eating harvested food must restore satiety");
		if (simulation) {
			helper.assertTrue(state.world().ledger().events().stream().anyMatch(event -> event.type() == EventType.CONSUME),
				"Abstract meals must leave a real consumption event");
			state.world().assertConservation();
		} else {
			int realAfter = chest.countItem(Items.SWEET_BERRIES) + villager.getInventory().countItem(Items.SWEET_BERRIES);
			helper.assertTrue(realAfter < realBefore, "A real meal must consume berries");
		}
		villager.kill();
		helper.succeed();
	}
}
