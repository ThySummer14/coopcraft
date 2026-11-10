package com.thysummer.coopcraft.civilization;

import java.util.List;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.civilization.simulation.WorkType;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;

public final class SettlementProductionGameTest implements FabricGameTest {
	private static final BlockPos BELL = new BlockPos(2, 0, 2);
	private static final BlockPos CHEST = new BlockPos(1, 0, 1);
	private static final BlockPos CRAFTING_TABLE = new BlockPos(3, 0, 2);
	private static final BlockPos GRINDING_STATION = new BlockPos(3, 0, 3);
	private static final BlockPos VILLAGER = new BlockPos(2, 0, 2);
	private static final BlockPos STONE = new BlockPos(2, 0, 3);

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void workGoalMakesPublicHousingSuppliesFromRealIngredients(GameTestHelper helper) {
		helper.setBlock(CHEST, Blocks.CHEST);
		helper.setBlock(CRAFTING_TABLE, Blocks.CRAFTING_TABLE);
		Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		Settlement settlement = data.getOrCreate(helper.absolutePos(BELL), "Public workshop", 0);
		data.setStockpile(settlement, helper.absolutePos(CHEST));
		data.setCraftingStation(settlement, helper.absolutePos(CRAFTING_TABLE));
		data.setStage(settlement, SettlementStage.AGRICULTURAL);
		Villager villager = helper.spawn(EntityType.VILLAGER, VILLAGER);
		villager.setNoAi(true);
		villager.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE_PICKAXE));
		data.registerLoadedResidents(helper.getLevel(), settlement, List.of(villager));
		SettlementStockpile.insert(chest, new ItemStack(Items.OAK_LOG, 10));
		SettlementStockpile.insert(chest, new ItemStack(Items.WHITE_WOOL, 3));
		SettlementStockpile.insert(chest, new ItemStack(Items.COAL));
		SettlementStockpile.insert(chest, new ItemStack(Items.STICK));
		SettlementStockpile.insert(chest, new ItemStack(Items.BREAD, 3));
		VillagerWorkGoal goal = new VillagerWorkGoal(villager);
		int batches = 0;
		for (; batches < 20 && goal.canUse(); batches++) {
			helper.assertValueEqual("craft_materials", goal.status().action(), "Only unmet housing supplies should be crafted");
			goal.start();
			for (int tick = 0; tick < 300 && goal.canContinueToUse(); tick++) {
				goal.tick();
			}
		}
		helper.assertTrue(batches > 0 && batches < 20, "Production must stop once demand is met");
		helper.assertValueEqual(31, chest.countItem(Items.OAK_PLANKS), "Ten logs yield forty planks minus six for doors and three for the bed");
		helper.assertValueEqual(3, chest.countItem(Items.OAK_DOOR), "One door batch must remain public");
		helper.assertValueEqual(1, chest.countItem(Items.WHITE_BED), "Real wool and planks must become one public bed");
		helper.assertValueEqual(4, chest.countItem(Items.TORCH), "One fuel and stick must become four public torches");
		helper.assertValueEqual(0, chest.countItem(Items.OAK_LOG), "All ten consumed logs must leave the chest");
		helper.assertValueEqual(0, chest.countItem(Items.WHITE_WOOL), "The bed must consume its wool");
		helper.assertValueEqual(0, chest.countItem(Items.COAL), "Torches must consume fuel");
		villager.kill();
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void realGatherStaysPhysicalUntilDelivery(GameTestHelper helper) {
		helper.setBlock(CHEST, Blocks.CHEST);
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		BlockPos bell = helper.absolutePos(BELL);
		BlockPos stone = helper.absolutePos(STONE);
		Settlement settlement = data.getOrCreate(bell, "Production Settlement", 0);
		data.setStockpile(settlement, helper.absolutePos(CHEST));
		Villager villager = helper.spawn(EntityType.VILLAGER, VILLAGER);
		data.registerLoadedResidents(helper.getLevel(), settlement, List.of(villager));
		SettlementSimulationState state = data.simulationOf(settlement).orElseThrow();
		String personId = state.residentId(villager.getUUID()).orElseThrow();
		SettlementSimulationService.setSurvivalEnabled(helper.getLevel(), data, settlement, true);
		SettlementSimulationService.setProductionEnabled(data, settlement, true);
		SettlementSimulationService.setSkillsEnabled(data, settlement, true);
		helper.assertValueEqual(
			100, SettlementSimulationService.gatherWorkDuration(data, settlement, villager.getUUID(), 100),
			"An unpracticed gatherer must retain the base duration"
		);
		helper.setBlock(STONE, Blocks.STONE);
		data.toggleResource(settlement, stone);
		VillagerWorkGoal goal = new VillagerWorkGoal(villager);

		helper.assertTrue(goal.canUse(), "The villager must choose the marked stone");
		goal.start();
		for (int tick = 0; tick < 240 && helper.getLevel().getBlockState(stone).is(Blocks.STONE); tick++) {
			goal.tick();
		}

		helper.assertTrue(helper.getLevel().getBlockState(stone).isAir(), "The real stone must be gathered");
		helper.assertValueEqual(1, villager.getInventory().countItem(Items.COBBLESTONE), "Gathered material must stay in the real backpack");
		helper.assertValueEqual(0L, SettlementSimulationService.craftingResourceCount(data, settlement, Items.COBBLESTONE),
			"Gathering must not fabricate an abstract stone batch");
		double gatherSkill = state.world().person(personId).skill(WorkType.GATHER);
		helper.assertTrue(Math.abs(gatherSkill - 1.01) < 0.000001, "Real gathering must practice the core gather skill");
		helper.assertValueEqual(
			99, SettlementSimulationService.gatherWorkDuration(data, settlement, villager.getUUID(), 100),
			"Improved gather skill must reduce real work duration"
		);
		SimulationEvent practice = state.world().ledger().events().stream()
			.filter(event -> event.type() == EventType.SKILL_PRACTICED)
			.filter(event -> event.actors().equals(List.of(personId)))
			.findFirst().orElseThrow();
		helper.assertValueEqual(List.of(personId), practice.actors(), "The practicing resident must be recorded");
		helper.assertValueEqual("GATHER", practice.context().get("workType"), "The practiced work type must be recorded");
		helper.assertValueEqual("1.0", practice.context().get("skillBefore"), "The initial skill must be recorded");
		helper.assertValueEqual("1.01", practice.context().get("skillAfter"), "The practiced skill must be recorded");
		helper.assertTrue(practice.causalLinks().isEmpty(), "Physical gathering practice must not invent an abstract import cause");
		Container store = SettlementStockpile.getStockpile(helper.getLevel(), data, settlement);
		var delivery = SettlementCargoDelivery.prepare(data, settlement, villager.getUUID(), villager.getInventory(),
			() -> villager.isAlive() && SettlementStockpile.getStockpile(helper.getLevel(), data, settlement) == store);
		villager.setPos(helper.absolutePos(CHEST).getX() + 0.5, helper.absolutePos(CHEST).getY(), helper.absolutePos(CHEST).getZ() + 0.5);
		helper.assertValueEqual(1, SettlementCargoDelivery.finish(delivery, data, settlement, villager.getUUID(),
			villager.getInventory(), store, () -> villager.distanceToSqr(helper.absolutePos(CHEST).getCenter()) <= 4).moved(),
			"Arrival must transfer exactly one physical stone");
		helper.assertValueEqual(0, villager.getInventory().countItem(Items.COBBLESTONE), "Delivered stone must leave the backpack");
		helper.assertValueEqual(1, SettlementStockpile.communalResourceCount(store), "The real store must receive exactly one stone");

		CompoundTag saved = data.save(new CompoundTag(), helper.getLevel().registryAccess());
		SettlementSavedData reloaded = SettlementSavedData.load(saved, helper.getLevel().registryAccess());
		Settlement restoredSettlement = reloaded.find(bell).orElseThrow();
		SettlementSimulationState restored = reloaded.simulationOf(restoredSettlement).orElseThrow();
		helper.assertTrue(restored.productionEnabled(), "The production switch must survive reload");
		helper.assertTrue(restored.skillsEnabled(), "The skill switch must survive reload");
		helper.assertTrue(
			Math.abs(restored.world().person(personId).skill(WorkType.GATHER) - 1.01) < 0.000001,
			"The practiced skill must survive reload"
		);
		helper.assertTrue(restored.world().ledger().event(practice.eventId()).isPresent(), "The practice event must survive reload");
		helper.assertValueEqual(0L, restored.world().inventoryTotals().getOrDefault(ResourceType.STONE, 0L), "Reload must not create an abstract material batch");
		restored.world().assertConservation();
		villager.kill();
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void abstractMaterialsFeedRealCrafting(GameTestHelper helper) {
		helper.setBlock(CHEST, Blocks.CHEST);
		helper.setBlock(CRAFTING_TABLE, Blocks.CRAFTING_TABLE);
		Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));
		SettlementStockpile.insert(chest, new ItemStack(Items.OAK_LOG));
		SettlementStockpile.insert(chest, new ItemStack(Items.COBBLESTONE, 3));
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		BlockPos bell = helper.absolutePos(BELL);
		Settlement settlement = data.getOrCreate(bell, "Crafting Settlement", 0);
		data.setStockpile(settlement, helper.absolutePos(CHEST));
		data.setCraftingStation(settlement, helper.absolutePos(CRAFTING_TABLE));
		Villager villager = helper.spawn(EntityType.VILLAGER, VILLAGER);
		data.registerLoadedResidents(helper.getLevel(), settlement, List.of(villager));
		SettlementSimulationState state = data.simulationOf(settlement).orElseThrow();
		String personId = state.residentId(villager.getUUID()).orElseThrow();
		SettlementSimulationService.setSurvivalEnabled(helper.getLevel(), data, settlement, true);
		SettlementSimulationService.setProductionEnabled(data, settlement, true);
		SettlementSimulationService.setSkillsEnabled(data, settlement, true);
		helper.assertValueEqual(
			100, SettlementSimulationService.craftingWorkDuration(data, settlement, villager.getUUID(), 100),
			"An unpracticed crafter must retain the base duration"
		);
		helper.assertValueEqual(
			1, SettlementSimulationService.importFromStockpile(data, settlement, chest, ResourceType.WOOD, 1),
			"The real log must enter the abstract camp stock"
		);
		helper.assertValueEqual(
			3, SettlementSimulationService.importFromStockpile(data, settlement, chest, ResourceType.STONE, 3),
			"The real stone must enter the abstract camp stock"
		);
		String woodBatchId = state.world().batches().stream()
			.filter(batch -> batch.resourceType() == ResourceType.WOOD).findFirst().orElseThrow().batchId();
		long woodOriginEventId = state.world().ledger().events().stream()
			.filter(event -> event.type() == EventType.EXTERNAL_IMPORT)
			.filter(event -> event.outputs().stream().anyMatch(flow -> woodBatchId.equals(flow.batchId())))
			.findFirst().orElseThrow().eventId();
		String stoneBatchId = state.world().batches().stream()
			.filter(batch -> batch.resourceType() == ResourceType.STONE).findFirst().orElseThrow().batchId();
		long stoneOriginEventId = state.world().ledger().events().stream()
			.filter(event -> event.type() == EventType.EXTERNAL_IMPORT)
			.filter(event -> event.outputs().stream().anyMatch(flow -> stoneBatchId.equals(flow.batchId())))
			.findFirst().orElseThrow().eventId();
		helper.assertValueEqual(0, chest.countItem(Items.OAK_LOG), "Imported logs must leave the real stockpile");
		helper.assertValueEqual(0, chest.countItem(Items.COBBLESTONE), "Imported stone must leave the real stockpile");

		VillagerWorkGoal goal = new VillagerWorkGoal(villager);
		runStickWorkbenchAction(helper, goal, villager);

		helper.assertValueEqual(8, villager.getInventory().countItem(Items.STICK), "One exported log must craft eight real sticks");
		helper.assertValueEqual(0L, SettlementSimulationService.craftingResourceCount(data, settlement, Items.STICK),
			"Physical workbench crafting must not import a new abstract stick batch");
		deliverCargoAtStore(helper, data, settlement, villager, chest, 8);
		helper.assertValueEqual(8, chest.countItem(Items.STICK), "All crafted sticks must arrive in the real store");
		helper.assertValueEqual(0, villager.getInventory().countItem(Items.OAK_LOG), "The crafting input must be consumed");
		helper.assertValueEqual(
			0L, SettlementSimulationService.craftingResourceCount(data, settlement, Items.OAK_LOG),
			"The abstract log must be consumed exactly once"
		);
		SimulationEvent sticksWithdrawal = craftingEvent(state, "material_sticks");
		helper.assertValueEqual(List.of(personId), sticksWithdrawal.actors(), "The real crafter must be recorded");
		helper.assertValueEqual(woodBatchId, sticksWithdrawal.context().get("batchId"), "The stick recipe must use the wood batch");
		helper.assertTrue(
			state.world().ledger().traceOrigins(sticksWithdrawal.eventId()).stream()
				.anyMatch(event -> event.eventId() == woodOriginEventId),
			"The stick material withdrawal must retain its import origin"
		);
		SimulationEvent sticksCompleted = craftingCompletion(state, "material_sticks");
		helper.assertValueEqual(
			List.of(sticksWithdrawal.eventId()), sticksCompleted.causalLinks(),
			"The completed craft must link to its actual material withdrawal"
		);
		helper.assertTrue(state.world().ledger().traceOrigins(sticksCompleted.eventId()).stream()
			.anyMatch(event -> event.eventId() == woodOriginEventId), "The completed stick craft retains its actual wood export ancestry");
		SimulationEvent sticksPractice = craftingPractice(state, "material_sticks");
		helper.assertValueEqual("CRAFT", sticksPractice.context().get("workType"), "Stick making must practice crafting");
		helper.assertValueEqual("1.0", sticksPractice.context().get("skillBefore"), "Crafting must start at skill one");
		helper.assertValueEqual("1.01", sticksPractice.context().get("skillAfter"), "The first craft must practice the core skill");
		helper.assertValueEqual(
			List.of(sticksCompleted.eventId()), sticksPractice.causalLinks(), "Practice must link to the completed real craft"
		);
		helper.assertTrue(Math.abs(state.world().person(personId).skill(WorkType.CRAFT) - 1.01) < 0.000001,
			"Real stick making must increase crafting skill");
		helper.assertValueEqual(
			99, SettlementSimulationService.craftingWorkDuration(data, settlement, villager.getUUID(), 100),
			"Improved crafting skill must reduce real crafting duration"
		);

		runFirstPickaxeAction(helper, goal, villager);

		helper.assertTrue(villager.getMainHandItem().is(Items.STONE_PICKAXE), "The crafted pickaxe must remain a real held tool");
		helper.assertValueEqual(0, villager.getInventory().countItem(Items.STICK),
			"Exported sticks must be consumed by the real pickaxe recipe");
		helper.assertValueEqual(6, chest.countItem(Items.STICK), "The pickaxe must consume exactly two delivered real sticks");
		helper.assertValueEqual(0L, SettlementSimulationService.craftingResourceCount(data, settlement, Items.STICK), "Sticks must remain exclusively real");
		helper.assertValueEqual(0, villager.getInventory().countItem(Items.COBBLESTONE), "The exported stone must be consumed");
		helper.assertValueEqual(
			0L, SettlementSimulationService.craftingResourceCount(data, settlement, Items.COBBLESTONE),
			"The abstract stone must be consumed exactly once"
		);
		SimulationEvent pickaxeWithdrawal = craftingEvent(state, "stone_pickaxe");
		helper.assertValueEqual(List.of(personId), pickaxeWithdrawal.actors(), "The pickaxe crafter must be recorded");
		helper.assertValueEqual(stoneBatchId, pickaxeWithdrawal.context().get("batchId"), "The pickaxe must use the stone batch");
		helper.assertTrue(
			state.world().ledger().traceOrigins(pickaxeWithdrawal.eventId()).stream()
				.anyMatch(event -> event.eventId() == stoneOriginEventId),
			"The pickaxe material withdrawal must retain its import origin"
		);
		SimulationEvent pickaxeCompleted = craftingCompletion(state, "stone_pickaxe");
		helper.assertValueEqual(List.of(pickaxeWithdrawal.eventId()), pickaxeCompleted.causalLinks(),
			"Only the actual abstract stone export supplies a direct withdrawal cause");
		helper.assertFalse(state.world().ledger().events().stream().anyMatch(event -> event.type() == EventType.EXTERNAL_EXPORT
			&& "stone_pickaxe".equals(event.context().get("recipe"))
			&& event.inputs().stream().anyMatch(flow -> flow.resourceType() == ResourceType.STICK)),
			"Physical sticks must not acquire fabricated export evidence");
		SimulationEvent pickaxePractice = craftingPractice(state, "stone_pickaxe");
		helper.assertValueEqual("CRAFT", pickaxePractice.context().get("workType"), "Pickaxe making must practice crafting");
		helper.assertValueEqual("1.01", pickaxePractice.context().get("skillBefore"), "The second craft must build on prior practice");
		helper.assertValueEqual("1.0199", pickaxePractice.context().get("skillAfter"), "The second craft must apply diminishing gains");
		helper.assertValueEqual(
			List.of(pickaxeCompleted.eventId()), pickaxePractice.causalLinks(), "Pickaxe practice must link to the real completed tool"
		);
		helper.assertTrue(Math.abs(state.world().person(personId).skill(WorkType.CRAFT) - 1.0199) < 0.000001,
			"The second real craft must further increase crafting skill");
		helper.assertValueEqual(
			98, SettlementSimulationService.craftingWorkDuration(data, settlement, villager.getUUID(), 100),
			"Continued crafting practice must further reduce real duration"
		);
		SettlementStockpile.insert(chest, new ItemStack(Items.COBBLESTONE, 3));
		helper.assertValueEqual(3, SettlementSimulationService.importFromStockpile(
			data, settlement, chest, ResourceType.STONE, 3
		), "Three more real cobblestone must enter the abstract camp stock for polishing");
		long polishStoneOrigin = state.world().ledger().events().getLast().eventId();
		helper.setBlock(GRINDING_STATION, Blocks.STONECUTTER);
		data.setGrindingStation(settlement, helper.absolutePos(GRINDING_STATION));
		var enchantments = helper.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT);
		VillagerWorkGoal polishGoal = new VillagerWorkGoal(villager);
		for (int batch = 0; batch < 3; batch++) {
			runWorkshopAction(helper, polishGoal, "grind", GRINDING_STATION);
			helper.assertValueEqual(1, villager.getInventory().countItem(Items.SMOOTH_STONE), "Each grind must leave one real product in the backpack");
			deliverCargoAtStore(helper, data, settlement, villager, chest, 1);
			helper.assertValueEqual(batch + 1, chest.countItem(Items.SMOOTH_STONE), "Each explicit service delivery adds one real smooth stone");
		}
		runWorkshopAction(helper, polishGoal, "polish", GRINDING_STATION);

		helper.assertTrue(villager.getMainHandItem().is(Items.STONE_PICKAXE), "Polishing must retain a real stone pickaxe");
		helper.assertValueEqual(1,
			villager.getMainHandItem().getEnchantments().getLevel(enchantments.getHolderOrThrow(Enchantments.EFFICIENCY)),
			"The polished pickaxe must have Efficiency I");
		helper.assertValueEqual(1,
			villager.getMainHandItem().getEnchantments().getLevel(enchantments.getHolderOrThrow(Enchantments.UNBREAKING)),
			"The polished pickaxe must have Unbreaking I");
		helper.assertValueEqual(0, villager.getInventory().countItem(Items.SMOOTH_STONE),
			"Abstract smooth stone must not be duplicated in the real inventory");
		helper.assertValueEqual(0L, SettlementSimulationService.craftingResourceCount(data, settlement, Items.SMOOTH_STONE),
			"No new smooth-stone product may appear in abstract inventory");
		helper.assertValueEqual(0L, SettlementSimulationService.craftingResourceCount(data, settlement, Items.COBBLESTONE),
			"Grinding must consume all three abstract cobblestones");
		helper.assertValueEqual(4, chest.countItem(Items.STICK), "Polishing must consume two more real sticks");
		helper.assertValueEqual(0, chest.countItem(Items.SMOOTH_STONE), "Polishing must consume all three delivered smooth stones");
		helper.assertValueEqual(0L, SettlementSimulationService.craftingResourceCount(data, settlement, Items.STICK), "No crafted sticks become abstract");
		List<SimulationEvent> grindingCompletions = state.world().ledger().events().stream()
			.filter(event -> event.type() == EventType.CRAFT_COMPLETED && "smooth_stone".equals(event.context().get("recipe"))).toList();
		helper.assertValueEqual(3, grindingCompletions.size(), "Exactly three real grinding actions must complete");
		for (SimulationEvent completed : grindingCompletions) {
			helper.assertValueEqual(1, completed.causalLinks().size(), "Each grind links its actual stone withdrawal");
			helper.assertTrue(state.world().ledger().traceOrigins(completed.eventId()).stream()
				.anyMatch(event -> event.eventId() == polishStoneOrigin), "Grinding completion retains its original stone import ancestry");
			helper.assertTrue(state.world().ledger().events().stream().anyMatch(event -> event.type() == EventType.SKILL_PRACTICED
				&& event.causalLinks().equals(List.of(completed.eventId()))), "Each grind has its own skill receipt");
		}
		helper.assertFalse(state.world().ledger().events().stream().anyMatch(event -> event.type() == EventType.EXTERNAL_IMPORT
			&& "smooth_stone".equals(event.context().get("recipe"))), "No grinding product may be implicitly imported");
		SimulationEvent polishedCompleted = craftingCompletion(state, "polished_stone_pickaxe");
		helper.assertTrue(polishedCompleted.causalLinks().isEmpty(), "Real delivered ingredients do not invent abstract export causes");
		helper.assertValueEqual(List.of(polishedCompleted.eventId()), craftingPractice(state, "polished_stone_pickaxe").causalLinks(),
			"Polishing practice links its actual completion");
		double craftSkillBeforeSave = state.world().person(personId).skill(WorkType.CRAFT);
		state.world().assertConservation();

		CompoundTag saved = data.save(new CompoundTag(), helper.getLevel().registryAccess());
		SettlementSavedData reloaded = SettlementSavedData.load(saved, helper.getLevel().registryAccess());
		SettlementSimulationState restored = reloaded.simulationOf(reloaded.find(bell).orElseThrow()).orElseThrow();
		helper.assertValueEqual(0L, restored.world().inventoryTotals().get(ResourceType.WOOD), "Reload must not restore consumed wood");
		helper.assertValueEqual(0L, restored.world().inventoryTotals().get(ResourceType.STONE), "Reload must not restore consumed stone");
		helper.assertValueEqual(0L, restored.world().inventoryTotals().getOrDefault(ResourceType.SMOOTH_STONE, 0L),
			"Reload must not restore consumed smooth stone");
		helper.assertValueEqual(0L, restored.world().inventoryTotals().getOrDefault(ResourceType.STICK, 0L), "Reload must not create an abstract stick batch");
		helper.assertValueEqual(4, chest.countItem(Items.STICK), "Settlement reload must leave all four real sticks intact");
		helper.assertValueEqual(
			state.world().ledger().events(), restored.world().ledger().events(), "Crafting withdrawals must survive reload"
		);
		helper.assertTrue(
			Math.abs(restored.world().person(personId).skill(WorkType.CRAFT) - craftSkillBeforeSave) < 0.000001,
			"The practiced crafting skill must survive reload"
		);
		restored.world().assertConservation();
		villager.kill();
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void workGoalReplenishesUpgradeSticksWithProductionEnabled(GameTestHelper helper) {
		assertWorkGoalReplenishesUpgradeSticks(helper, true);
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void workGoalReplenishesUpgradeSticksWithProductionDisabled(GameTestHelper helper) {
		assertWorkGoalReplenishesUpgradeSticks(helper, false);
	}

	private static void assertWorkGoalReplenishesUpgradeSticks(GameTestHelper helper, boolean productionEnabled) {
		helper.setBlock(CHEST, Blocks.CHEST);
		helper.setBlock(CRAFTING_TABLE, Blocks.CRAFTING_TABLE);
		helper.setBlock(GRINDING_STATION, Blocks.STONECUTTER);
		Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		BlockPos bell = helper.absolutePos(BELL);
		// Run with an isolated settlement: getOrCreate reuses any existing settlement within 48 blocks.
		// Do not reset shared SavedData here or let another test's residents, stock or jobs affect this scenario.
		helper.assertTrue(data.find(bell).isEmpty(),
			"Tool upgrade regression requires an isolated settlement with no other settlement within 48 blocks");
		Settlement settlement = data.getOrCreate(bell, "Tool upgrade workshop", 0);
		data.setStockpile(settlement, helper.absolutePos(CHEST));
		data.setCraftingStation(settlement, helper.absolutePos(CRAFTING_TABLE));
		data.setGrindingStation(settlement, helper.absolutePos(GRINDING_STATION));
		data.setStage(settlement, SettlementStage.AGRICULTURAL);
		Villager villager = helper.spawn(EntityType.VILLAGER, VILLAGER);
		villager.setNoAi(true);
		data.registerLoadedResidents(helper.getLevel(), settlement, List.of(villager));
		data.setSatiety(settlement, villager.getUUID(), 100);

		// Suppress both hearth and housing demand, rather than letting either steal the only log.
		BlockPos bed = helper.absolutePos(new BlockPos(0, 1, 0));
		var bedState = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST);
		helper.getLevel().setBlock(bed.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		helper.getLevel().setBlock(bed.east().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		helper.getLevel().setBlock(bed, bedState.setValue(BedBlock.PART, BedPart.FOOT), Block.UPDATE_CLIENTS);
		helper.getLevel().setBlock(bed.east(), bedState.setValue(BedBlock.PART, BedPart.HEAD), Block.UPDATE_ALL);
		helper.assertValueEqual(SettlementHousing.RegistrationResult.ADDED,
			SettlementHousing.registerBed(helper.getLevel(), data, settlement, bed), "The workshop resident needs a real complete bed");
		helper.assertTrue(ConstructionPlanner.materialDemands(data, settlement).isEmpty(),
			"Neither hearth nor housing production may compete with tool upgrading");
		helper.assertTrue(data.getConstructionJob(settlement).isEmpty(), "The workshop must not have an active build");

		SettlementSimulationService.setSurvivalEnabled(helper.getLevel(), data, settlement, true);
		SettlementSimulationService.setProductionEnabled(data, settlement, productionEnabled);
		// Keep skill logging active in both modes so real-only crafts also have completion receipts.
		SettlementSimulationService.setSkillsEnabled(data, settlement, true);
		SettlementSimulationState state = data.simulationOf(settlement).orElseThrow();
		String personId = state.residentId(villager.getUUID()).orElseThrow();
		SettlementStockpile.insert(chest, new ItemStack(Items.COBBLESTONE, 3));
		SettlementStockpile.insert(chest, new ItemStack(Items.STICK, 2));
		if (productionEnabled) {
			helper.assertValueEqual(3, SettlementSimulationService.importFromStockpile(
				data, settlement, chest, ResourceType.STONE, 3), "Import exactly the first pickaxe's stone");
			helper.assertValueEqual(2, SettlementSimulationService.importFromStockpile(
				data, settlement, chest, ResourceType.STICK, 2), "Import exactly the first pickaxe's sticks");
			// A full resident still forages for an empty communal reserve; keep neighboring bushes out of this test.
			SettlementStockpile.insert(chest, new ItemStack(Items.SWEET_BERRIES, 3));
			helper.assertValueEqual(3, SettlementSimulationService.importFromStockpile(
				data, settlement, chest, ResourceType.WILD_FRUIT, 3), "Import a real food reserve above one resident's target");
		}
		VillagerWorkGoal goal = new VillagerWorkGoal(villager);
		runFirstPickaxeAction(helper, goal, villager);
		helper.assertTrue(villager.getMainHandItem().is(Items.STONE_PICKAXE), "The first craft must equip a real stone pickaxe");
		helper.assertTrue(chest.isEmpty() && villager.getInventory().isEmpty(), "The first pickaxe must consume all real inputs");
		helper.assertValueEqual(0L, SettlementSimulationService.craftingResourceCount(data, settlement, Items.STICK),
			"No spare sticks may remain after making the first pickaxe");
		helper.assertValueEqual(0L, SettlementSimulationService.craftingResourceCount(data, settlement, Items.COBBLESTONE),
			"The first pickaxe must consume exactly three stone");
		var enchantments = helper.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT);
		helper.assertValueEqual(0,
			villager.getMainHandItem().getEnchantments().getLevel(enchantments.getHolderOrThrow(Enchantments.EFFICIENCY)),
			"The first pickaxe must still need polishing");
		// Legal existing-tool state: upgrading is not repair or replacement with a fresh stack.
		ItemStack originalTool = villager.getMainHandItem();
		originalTool.set(DataComponents.CUSTOM_NAME, Component.literal("Workshop pickaxe"));
		originalTool.setDamageValue(17);
		originalTool.set(DataComponents.REPAIR_COST, 4);
		CompoundTag toolMarker = new CompoundTag();
		toolMarker.putString("workshop", "preserve-original-tool");
		originalTool.set(DataComponents.CUSTOM_DATA, CustomData.of(toolMarker));

		SettlementStockpile.insert(chest, new ItemStack(Items.OAK_LOG));
		SettlementStockpile.insert(chest, new ItemStack(Items.SMOOTH_STONE, 3));
		if (productionEnabled) {
			helper.assertValueEqual(1, SettlementSimulationService.importFromStockpile(
				data, settlement, chest, ResourceType.WOOD, 1), "Import only one log for replacement sticks");
			helper.assertValueEqual(3, SettlementSimulationService.importFromStockpile(
				data, settlement, chest, ResourceType.SMOOTH_STONE, 3), "Import only three smooth stones for polishing");
		}

		// No recipe or tool is injected: the same Goal must select stick making while already holding its pickaxe.
		runStickWorkbenchAction(helper, goal, villager);
		helper.assertValueEqual(8, villager.getInventory().countItem(Items.STICK)
			+ (int) SettlementSimulationService.craftingResourceCount(data, settlement, Items.STICK),
			"An exhausted pickaxe crafter must autonomously turn one log into eight upgrade sticks");
		helper.assertValueEqual(0, chest.countItem(Items.OAK_LOG) + villager.getInventory().countItem(Items.OAK_LOG),
			"The replenishment must consume the only real log");
		helper.assertValueEqual(0L, SettlementSimulationService.craftingResourceCount(data, settlement, Items.OAK_LOG),
			"The replenishment must consume the only abstract log");
		deliverCargoAtStore(helper, data, settlement, villager, chest, 8);
		runWorkshopAction(helper, goal, "polish", GRINDING_STATION);
		helper.assertTrue(villager.getMainHandItem().is(Items.STONE_PICKAXE), "The upgraded tool must remain a real stone pickaxe");
		helper.assertValueEqual(1,
			villager.getMainHandItem().getEnchantments().getLevel(enchantments.getHolderOrThrow(Enchantments.EFFICIENCY)),
			"The autonomous upgrade must apply Efficiency I");
		helper.assertValueEqual(1,
			villager.getMainHandItem().getEnchantments().getLevel(enchantments.getHolderOrThrow(Enchantments.UNBREAKING)),
			"The autonomous upgrade must apply Unbreaking I");
		helper.assertValueEqual(Component.literal("Workshop pickaxe"), villager.getMainHandItem().get(DataComponents.CUSTOM_NAME),
			"Polishing must preserve the original tool name");
		helper.assertValueEqual(17, villager.getMainHandItem().getDamageValue(), "Polishing must not repair the old tool");
		helper.assertValueEqual(4, villager.getMainHandItem().get(DataComponents.REPAIR_COST),
			"Polishing must preserve the original repair cost");
		helper.assertValueEqual(CustomData.of(toolMarker), villager.getMainHandItem().get(DataComponents.CUSTOM_DATA),
			"Polishing must preserve arbitrary existing tool data");
		helper.assertValueEqual(0,
			originalTool.getEnchantments().getLevel(enchantments.getHolderOrThrow(Enchantments.EFFICIENCY)),
			"Preparing the upgraded copy must not mutate the old stack object");
		helper.assertValueEqual(6, chest.countItem(Items.STICK), "Exactly six delivered sticks must remain after polishing");
		helper.assertValueEqual(6, SettlementStockpile.communalResourceCount(chest), "No other real materials may remain");
		helper.assertValueEqual(0, villager.getInventory().countItem(Items.SMOOTH_STONE), "Polishing must consume three real smooth stones");
		helper.assertValueEqual(0L, SettlementSimulationService.craftingResourceCount(data, settlement, Items.SMOOTH_STONE),
			"Polishing must consume three abstract smooth stones");
		helper.assertValueEqual(0, villager.getInventory().countItem(Items.STICK), "Delivered sticks must not remain in the backpack");
		helper.assertValueEqual(0L, SettlementSimulationService.craftingResourceCount(data, settlement, Items.STICK), "New stick products remain real in both production modes");
		helper.assertTrue(data.getConstructionJob(settlement).isEmpty(), "The upgrade must not start construction");

		List<SimulationEvent> completions = state.world().ledger().events().stream()
			.filter(event -> event.type() == EventType.CRAFT_COMPLETED).toList();
		helper.assertValueEqual(List.of("stone_pickaxe", "material_sticks", "polished_stone_pickaxe"),
			completions.stream().map(event -> event.context().get("recipe")).toList(),
			"Exactly one initial tool, one replacement stick batch and one upgrade must complete, in order");
		for (SimulationEvent completed : completions) {
			String recipe = completed.context().get("recipe");
			helper.assertValueEqual(List.of(personId), completed.actors(), "Each completion must identify the actual crafter");
			helper.assertValueEqual("current_attempt_direct_exports", completed.context().get("provenanceScope"),
				"Completion provenance must be bounded to the current synchronous attempt");
			helper.assertValueEqual("unknown", completed.context().get("preexistingInventoryProvenance"),
				"The carried tool and other preexisting inventory must not acquire invented ancestry");
			List<Long> withdrawals = state.world().ledger().events().stream()
				.filter(event -> event.type() == EventType.EXTERNAL_EXPORT)
				.filter(event -> "craft".equals(event.context().get("activity")))
				.filter(event -> recipe.equals(event.context().get("recipe")))
				.map(SimulationEvent::eventId).toList();
			helper.assertValueEqual(productionEnabled ? (recipe.equals("stone_pickaxe") ? 2 : 1) : 0, withdrawals.size(),
				"Only production-backed ingredients may supply material withdrawal evidence");
			helper.assertValueEqual(withdrawals, completed.causalLinks(), "Completion must link exactly its own withdrawals");
			helper.assertValueEqual(List.of(completed.eventId()), craftingPractice(state, recipe).causalLinks(),
				"Crafting practice must link to this completed action");
		}
		if (productionEnabled) {
			helper.assertValueEqual(3L, SettlementSimulationService.craftingResourceCount(data, settlement, Items.SWEET_BERRIES),
				"The food reserve must remain untouched throughout the tool upgrade");
			var upgradeOrigins = state.world().ledger().traceOrigins(craftingCompletion(state, "polished_stone_pickaxe").eventId());
			helper.assertFalse(upgradeOrigins.stream().anyMatch(event -> event.eventId() == craftingCompletion(state, "material_sticks").eventId()),
				"Delivered real sticks do not acquire synthetic abstract ancestry");
			helper.assertFalse(upgradeOrigins.stream().anyMatch(event -> event.eventId() == completions.getFirst().eventId()),
				"Polishing must not borrow completion evidence from the earlier carried pickaxe");
		} else {
			helper.assertTrue(state.world().batches().isEmpty(), "Production off must keep all resources real");
		}

		// Add fuel for an unwanted extra craft only after verifying all exact tool costs and the six remaining real sticks.
		SettlementStockpile.insert(chest, new ItemStack(Items.OAK_LOG));
		if (productionEnabled) {
			helper.assertValueEqual(1, SettlementSimulationService.importFromStockpile(
				data, settlement, chest, ResourceType.WOOD, 1), "Import one spare log for the no-demand regression");
		}
		List<SimulationEvent> completedLedger = List.copyOf(state.world().ledger().events());
		helper.assertFalse(goal.canUse(), "A finished workshop must not make more sticks even with another log available");
		helper.assertValueEqual(productionEnabled ? 0 : 1,
			chest.countItem(Items.OAK_LOG) + villager.getInventory().countItem(Items.OAK_LOG),
			"The spare real log must remain unused when there is no tool demand");
		helper.assertValueEqual(productionEnabled ? 1L : 0L,
			SettlementSimulationService.craftingResourceCount(data, settlement, Items.OAK_LOG),
			"The spare abstract log must remain unused when there is no tool demand");
		helper.assertValueEqual(6L, chest.countItem(Items.STICK) + villager.getInventory().countItem(Items.STICK)
			+ SettlementSimulationService.craftingResourceCount(data, settlement, Items.STICK),
			"No-demand selection must not create any extra sticks");
		helper.assertValueEqual(completedLedger, state.world().ledger().events(),
			"No-demand selection must not append crafting or material events");
		state.world().assertConservation();
		villager.kill();
		helper.succeed();
	}

	/** Explicit service arrival fixture; this does not claim to exercise Goal navigation. */
	private static void deliverCargoAtStore(GameTestHelper helper, SettlementSavedData data, Settlement settlement,
		Villager villager, Container store, int expected) {
		var original = villager.position();
		BlockPos pos = data.getStockpile(settlement).orElseThrow();
		var ticket = SettlementCargoDelivery.prepare(data, settlement, villager.getUUID(), villager.getInventory(),
			() -> villager.isAlive() && SettlementStockpile.getStockpile(helper.getLevel(), data, settlement) == store);
		try {
			villager.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
			helper.assertValueEqual(expected, SettlementCargoDelivery.finish(ticket, data, settlement, villager.getUUID(),
				villager.getInventory(), store, () -> villager.distanceToSqr(pos.getCenter()) <= 4).moved(),
				"The explicit arrived service fixture must transfer exactly the real cargo");
		} finally {
			villager.setPos(original.x, original.y, original.z);
		}
	}

	/** Explicit warehouse/table arrival fixture, not a navigation or full-game acceptance test. */
	private static void runFirstPickaxeAction(GameTestHelper helper, VillagerWorkGoal goal, Villager villager) {
		var original = villager.position();
		boolean noAi = villager.isNoAi();
		try {
			villager.setNoAi(false); // The production route correctly rejects disabled workers.
			BlockPos store = helper.absolutePos(CHEST), station = helper.absolutePos(CRAFTING_TABLE);
			villager.setPos(store.getX() + 0.5, store.getY() + 1, store.getZ() + 0.5);
			helper.assertTrue(goal.canUse(), "First pickaxe must select physical warehouse pickup");
			helper.assertValueEqual("collect_pickaxe_materials", goal.status().action(), "Select the first-tool pickup action");
			helper.assertValueEqual(store, goal.status().target(), "Pick up only at the registered warehouse");
			goal.start();
			helper.assertValueEqual("workbench_pickaxe", goal.status().action(), "A complete pickup must travel to the table");
			helper.assertValueEqual(station, goal.status().target(), "Use the registered crafting table");
			helper.assertTrue(villager.getMainHandItem().isEmpty(), "Pickup must not manufacture or equip the tool");
			helper.assertValueEqual(3, villager.getInventory().countItem(Items.COBBLESTONE), "Carry the actual three stone");
			helper.assertValueEqual(2, villager.getInventory().countItem(Items.STICK), "Carry the actual two sticks");
			villager.setPos(station.getX() + 0.5, station.getY() + 1, station.getZ() + 0.5);
			for (int tick = 0; tick < 240 && goal.canContinueToUse(); tick++) goal.tick();
			helper.assertFalse(goal.canContinueToUse(), "The arrived workbench batch must finish in its work budget");
			helper.assertTrue(villager.getMainHandItem().is(Items.STONE_PICKAXE), "Equip the one completed actual tool");
		} finally {
			goal.stop();
			villager.setNoAi(noAi);
			villager.setPos(original.x, original.y, original.z);
		}
	}

	/** Explicit arrivals verify custody stages; this fixture does not exercise navigation. */
	private static void runStickWorkbenchAction(GameTestHelper helper, VillagerWorkGoal goal, Villager villager) {
		var original = villager.position();
		boolean noAi = villager.isNoAi();
		try {
			villager.setNoAi(false);
			BlockPos store = helper.absolutePos(CHEST), station = helper.absolutePos(CRAFTING_TABLE);
			villager.setPos(store.getX() + 0.5, store.getY() + 1, store.getZ() + 0.5);
			helper.assertTrue(goal.canUse(), "Stick demand must select public warehouse pickup");
			helper.assertValueEqual("collect_stick_inputs", goal.status().action(), "Select stick inputs");
			helper.assertValueEqual(store, goal.status().target(), "Collect at the real warehouse");
			goal.start();
			helper.assertValueEqual("craft_sticks_at_workbench", goal.status().action(), "Carry inputs to the table");
			helper.assertValueEqual(station, goal.status().target(), "Use the bound table");
			helper.assertValueEqual(1, villager.getInventory().countItem(Items.OAK_LOG), "Carry the one real log");
			helper.assertValueEqual(0, villager.getInventory().countItem(Items.STICK), "Pickup cannot manufacture sticks");
			villager.setPos(station.getX() + 0.5, station.getY() + 1, station.getZ() + 0.5);
			for (int tick = 0; tick < 240 && goal.canContinueToUse(); tick++) goal.tick();
			helper.assertFalse(goal.canContinueToUse(), "Finish one arrived stick batch within the work budget");
			helper.assertValueEqual(8, villager.getInventory().countItem(Items.STICK), "One log produces eight real sticks");
		} finally {
			goal.stop();
			villager.setNoAi(noAi);
			villager.setPos(original.x, original.y, original.z);
		}
	}

	private static void runWorkshopAction(GameTestHelper helper, VillagerWorkGoal goal, String action, BlockPos station) {
		helper.assertTrue(goal.canUse(), "The next autonomous workshop action must be selectable: " + action);
		helper.assertValueEqual(action, goal.status().action(), "The Goal must choose the expected workshop action");
		helper.assertValueEqual(helper.absolutePos(station), goal.status().target(), "The action must use the real registered station");
		goal.start();
		for (int tick = 0; tick < 240 && goal.canContinueToUse(); tick++) {
			goal.tick();
		}
		helper.assertFalse(goal.canContinueToUse(), "The workshop action must finish within its work budget");
	}

	private static SimulationEvent craftingEvent(SettlementSimulationState state, String recipe) {
		return state.world().ledger().events().stream()
			.filter(event -> event.type() == EventType.EXTERNAL_EXPORT)
			.filter(event -> "craft".equals(event.context().get("activity")))
			.filter(event -> recipe.equals(event.context().get("recipe")))
			.findFirst().orElseThrow();
	}

	private static SimulationEvent craftingCompletion(SettlementSimulationState state, String recipe) {
		return state.world().ledger().events().stream()
			.filter(event -> event.type() == EventType.CRAFT_COMPLETED)
			.filter(event -> recipe.equals(event.context().get("recipe")))
			.findFirst().orElseThrow();
	}

	private static SimulationEvent craftingPractice(SettlementSimulationState state, String recipe) {
		return state.world().ledger().events().stream()
			.filter(event -> event.type() == EventType.SKILL_PRACTICED)
			.filter(event -> recipe.equals(event.context().get("recipe")))
			.findFirst().orElseThrow();
	}
}
