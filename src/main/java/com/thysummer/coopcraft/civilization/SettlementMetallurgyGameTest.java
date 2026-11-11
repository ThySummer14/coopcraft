package com.thysummer.coopcraft.civilization;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

public final class SettlementMetallurgyGameTest implements FabricGameTest {
	private static final BlockPos BELL = new BlockPos(2, 0, 2);
	private static final BlockPos CHEST = new BlockPos(1, 0, 1);
	private static final BlockPos CRAFTING_TABLE = new BlockPos(3, 0, 3);
	private static final BlockPos FURNACE = new BlockPos(3, 0, 2);
	private static final BlockPos VILLAGER = new BlockPos(2, 0, 2);
	private static final BlockPos IRON_ORE = new BlockPos(2, 0, 3);
	private static final BlockPos COAL_ORE = new BlockPos(3, 0, 4);

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void oreNeedsAPickaxeAndBecomesPublicResource(GameTestHelper helper) {
		Scene scene = scene(helper, "Ore Settlement");
		helper.setBlock(IRON_ORE, Blocks.IRON_ORE);
		helper.setBlock(COAL_ORE, Blocks.DEEPSLATE_COAL_ORE);
		BlockPos iron = helper.absolutePos(IRON_ORE);
		BlockPos coal = helper.absolutePos(COAL_ORE);
		scene.data().toggleResource(scene.settlement(), iron);
		scene.data().toggleResource(scene.settlement(), coal);
		VillagerWorkGoal goal = new VillagerWorkGoal(scene.villager());

		helper.assertFalse(goal.canUse(), "Iron ore cannot be gathered without a pickaxe");
		helper.assertTrue(helper.getLevel().getBlockState(iron).is(Blocks.IRON_ORE), "The failed gather must leave the ore intact");
		scene.villager().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE_PICKAXE));
		gather(scene, new VillagerWorkGoal(scene.villager()), iron, Blocks.IRON_ORE);
		helper.assertValueEqual(1, scene.villager().getInventory().countItem(Items.RAW_IRON), "The real tool must produce raw iron");
		helper.assertValueEqual(1, SettlementStockpile.depositGatheredResources(scene.chest(), scene.villager().getInventory()),
			"Raw iron must enter the public stockpile");
		helper.assertValueEqual(1, scene.chest().countItem(Items.RAW_IRON), "The stockpile must retain the raw iron");

		VillagerWorkGoal coalGoal = new VillagerWorkGoal(scene.villager());
		gather(scene, coalGoal, coal, Blocks.DEEPSLATE_COAL_ORE);
		helper.assertValueEqual(1, scene.villager().getInventory().countItem(Items.COAL),
			"With no more iron ore nearby, the worker must gather coal for the existing raw iron");
		helper.assertValueEqual(1, SettlementStockpile.depositGatheredResources(scene.chest(), scene.villager().getInventory()),
			"Coal must enter the public stockpile");
		helper.assertValueEqual(1, scene.chest().countItem(Items.COAL), "The stockpile must retain the coal");
		helper.assertFalse(scene.data().eraMilestonesOf(scene.settlement()).contains(CivilizationEras.METAL_SMELTING),
			"Mining alone cannot complete smelting");
		helper.assertFalse(scene.data().eraMilestonesOf(scene.settlement()).contains(CivilizationEras.METAL_TOOL_PRODUCTION),
			"Mining alone cannot complete tool production");
		scene.villager().kill();
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void furnaceSlotsConserveOreAndFuel(GameTestHelper helper) {
		helper.setBlock(FURNACE, Blocks.FURNACE);
		AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(FURNACE));
		net.minecraft.world.SimpleContainer inventory = new net.minecraft.world.SimpleContainer(2);
		inventory.setItem(0, new ItemStack(Items.RAW_IRON, 2));
		inventory.setItem(1, new ItemStack(Items.COAL, 2));

		helper.assertTrue(SettlementMetallurgy.load(furnace, inventory), "The first ore and coal must enter the furnace");
		helper.assertTrue(SettlementMetallurgy.load(furnace, inventory), "Stocked fuel must allow a second ore without more coal");
		helper.assertFalse(SettlementMetallurgy.load(furnace, inventory), "Missing ore cannot start another load");
		helper.assertValueEqual(2, furnace.getItem(SettlementMetallurgy.INPUT_SLOT).getCount(), "Both ore items must enter the input slot");
		helper.assertValueEqual(1, furnace.getItem(SettlementMetallurgy.FUEL_SLOT).getCount(), "Only one coal must enter the stocked fuel slot");
		helper.assertValueEqual(0, inventory.countItem(Items.RAW_IRON), "Both ore items must leave the worker inventory");
		helper.assertValueEqual(1, inventory.countItem(Items.COAL), "The second load must not consume extra coal");

		furnace.setItem(SettlementMetallurgy.INPUT_SLOT, new ItemStack(Items.RAW_IRON, 64));
		inventory.setItem(0, new ItemStack(Items.RAW_IRON));
		inventory.setItem(1, new ItemStack(Items.COAL));
		helper.assertFalse(SettlementMetallurgy.canLoad(furnace), "A full input slot must reject another ore");
		helper.assertFalse(SettlementMetallurgy.load(furnace, inventory), "A rejected load must not consume inventory");
		helper.assertValueEqual(1, inventory.countItem(Items.RAW_IRON), "Rejected ore must remain in the worker inventory");
		helper.assertValueEqual(1, inventory.countItem(Items.COAL), "Rejected fuel must remain in the worker inventory");
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1600)
	public void realFurnaceFeedsIronPickaxeProduction(GameTestHelper helper) {
		helper.setBlock(CRAFTING_TABLE, Blocks.CRAFTING_TABLE);
		helper.setBlock(FURNACE, Blocks.FURNACE);
		Scene scene = scene(helper, "Smelting Settlement");
		scene.data().setCraftingStation(scene.settlement(), helper.absolutePos(CRAFTING_TABLE));
		scene.data().setSmeltingStation(scene.settlement(), helper.absolutePos(FURNACE));
		scene.data().setEra(scene.settlement(), CivilizationEras.byId(CivilizationEras.AGRICULTURAL));
		SettlementSimulationService.setSurvivalEnabled(helper.getLevel(), scene.data(), scene.settlement(), true);
		SettlementSimulationService.setProductionEnabled(scene.data(), scene.settlement(), true);
		SettlementStockpile.insert(scene.chest(), new ItemStack(Items.RAW_IRON, 3));
		SettlementStockpile.insert(scene.chest(), new ItemStack(Items.COAL, 3));
		SettlementStockpile.insert(scene.chest(), new ItemStack(Items.STICK, 2));
		scene.villager().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE_PICKAXE));

		helper.succeedWhen(() -> {
			CivilizationGameTestSupport.arriveAtWork(helper, scene.villager(),
				helper.absolutePos(CHEST), helper.absolutePos(FURNACE), helper.absolutePos(CRAFTING_TABLE));
			if (!scene.villager().getMainHandItem().is(Items.IRON_PICKAXE)) return;
			AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(FURNACE));
			helper.assertTrue(scene.villager().getMainHandItem().is(Items.IRON_PICKAXE),
				"The worker must smelt and equip an iron pickaxe; status=" + VillagerWorkGoal.statusOf(scene.villager()));
			helper.assertValueEqual(1, scene.villager().getInventory().countItem(Items.STONE_PICKAXE),
				"The old stone pickaxe must be preserved");
			helper.assertValueEqual(0, scene.chest().countItem(Items.RAW_IRON), "All three raw iron items must be smelted for the pickaxe");
			helper.assertValueEqual(2, scene.chest().countItem(Items.COAL), "The furnace must consume exactly one coal item");
			helper.assertValueEqual(0, scene.chest().countItem(Items.STICK), "The iron pickaxe must consume both sticks");
			helper.assertValueEqual(0, scene.chest().countItem(Items.IRON_INGOT), "The three ingots must be used by the pickaxe recipe");
			for (var item : List.of(Items.RAW_IRON, Items.COAL, Items.IRON_INGOT)) {
				helper.assertValueEqual(0L, SettlementSimulationService.craftingResourceCount(scene.data(), scene.settlement(), item),
					"Metal items must not be duplicated into abstract storage: " + item);
			}
			helper.assertTrue(furnace.getItem(SettlementMetallurgy.INPUT_SLOT).isEmpty(), "The furnace input must be consumed by real smelting");
			helper.assertTrue(furnace.getItem(SettlementMetallurgy.OUTPUT_SLOT).isEmpty(), "The worker must collect the real iron output");
			helper.assertTrue(scene.data().eraMilestonesOf(scene.settlement()).contains(CivilizationEras.METAL_SMELTING),
				"Real furnace output must complete metal smelting");
			helper.assertTrue(scene.data().eraMilestonesOf(scene.settlement()).contains(CivilizationEras.METAL_TOOL_PRODUCTION),
				"The real iron pickaxe must complete metal tool production");
			helper.assertFalse(scene.data().eraMilestonesOf(scene.settlement()).contains(CivilizationEras.MECHANICAL_POWER),
				"Metalworking must not fake mechanical power");
			helper.assertValueEqual(CivilizationEras.AGRICULTURAL, scene.data().eraOf(scene.settlement()).id(),
				"The settlement cannot enter the industrial era without mechanical power");

			CompoundTag saved = scene.data().save(new CompoundTag(), helper.getLevel().registryAccess());
			SettlementSavedData restored = SettlementSavedData.load(saved, helper.getLevel().registryAccess());
			Settlement restoredSettlement = restored.find(helper.absolutePos(BELL)).orElseThrow();
			helper.assertValueEqual(helper.absolutePos(FURNACE), restored.getSmeltingStation(restoredSettlement).orElseThrow(),
				"The smelting station must survive reload");
			helper.assertTrue(restored.eraMilestonesOf(restoredSettlement).contains(CivilizationEras.METAL_SMELTING),
				"Smelting progress must survive reload");
			helper.assertTrue(restored.eraMilestonesOf(restoredSettlement).contains(CivilizationEras.METAL_TOOL_PRODUCTION),
				"Tool progress must survive reload");
			scene.villager().kill();
			helper.succeed();
		});
	}

	private static void gather(Scene scene, VillagerWorkGoal goal, BlockPos target, net.minecraft.world.level.block.Block block) {
		scene.helper().assertTrue(goal.canUse(), "The worker must choose the marked ore");
		goal.start();
		for (int tick = 0; tick < 180 && scene.helper().getLevel().getBlockState(target).is(block); tick++) {
			goal.tick();
		}
		scene.helper().assertTrue(scene.helper().getLevel().getBlockState(target).isAir(), "The marked ore must be gathered");
	}

	private static Scene scene(GameTestHelper helper, String name) {
		helper.setBlock(CHEST, Blocks.CHEST);
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		Settlement settlement = CivilizationGameTestSupport.isolatedSettlement(helper, data, name);
		data.setStockpile(settlement, helper.absolutePos(CHEST));
		Villager villager = helper.spawn(EntityType.VILLAGER, VILLAGER);
		data.registerLoadedResidents(helper.getLevel(), settlement, List.of(villager));
		data.permitGatheringArea(settlement, villager.getUUID(), helper.absolutePos(BELL), 4, SettlementGatheringAreas.Mode.BOTH);
		Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));
		return new Scene(helper, data, settlement, villager, chest);
	}

	private static Settlement isolatedSettlement(GameTestHelper helper, SettlementSavedData data, String name) {
		Settlement settlement = new Settlement(UUID.randomUUID(), helper.absolutePos(BELL), name, 0);
		try {
			Field field = SettlementSavedData.class.getDeclaredField("settlements");
			field.setAccessible(true);
			@SuppressWarnings("unchecked")
			Map<BlockPos, Settlement> settlements = (Map<BlockPos, Settlement>) field.get(data);
			settlements.put(settlement.center(), settlement);
			return settlement;
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Could not create an isolated metallurgy test settlement", exception);
		}
	}

	private record Scene(GameTestHelper helper, SettlementSavedData data, Settlement settlement, Villager villager, Container chest) {
	}
}
