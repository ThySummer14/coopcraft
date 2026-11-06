package com.thysummer.coopcraft.civilization;

import java.util.List;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.ResourceBatch;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.civilization.simulation.WorkType;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;

public final class SettlementProductionGameTest implements FabricGameTest {
	private static final BlockPos BELL = new BlockPos(2, 0, 2);
	private static final BlockPos CHEST = new BlockPos(1, 0, 1);
	private static final BlockPos CRAFTING_TABLE = new BlockPos(3, 0, 2);
	private static final BlockPos GRINDING_STATION = new BlockPos(3, 0, 3);
	private static final BlockPos VILLAGER = new BlockPos(2, 0, 2);
	private static final BlockPos STONE = new BlockPos(2, 0, 3);

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void realGatherBecomesOneAbstractBatch(GameTestHelper helper) {
		helper.setBlock(CHEST, Blocks.CHEST);
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		BlockPos bell = helper.absolutePos(BELL);
		BlockPos stone = helper.absolutePos(STONE);
		Settlement settlement = CivilizationGameTestSupport.isolatedSettlement(helper, data, "Production Settlement");
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
		helper.assertValueEqual(0, villager.getInventory().countItem(Items.COBBLESTONE), "Gathered stock must not be duplicated in the real inventory");
		ResourceBatch batch = state.world().batches().stream()
			.filter(candidate -> candidate.resourceType() == ResourceType.STONE).findFirst().orElseThrow();
		helper.assertValueEqual(ResourceType.STONE, batch.resourceType(), "The real drop must become abstract stone");
		helper.assertValueEqual(1L, batch.quantity(), "One real block must produce exactly one abstract unit");
		SimulationEvent production = state.world().ledger().event(batch.originEventId()).orElseThrow();
		helper.assertValueEqual(EventType.EXTERNAL_IMPORT, production.type(), "Production must have a material import origin");
		helper.assertValueEqual(List.of(personId), production.actors(), "The actual gatherer must be recorded");
		helper.assertValueEqual("gather", production.context().get("activity"), "The activity must be recorded");
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
		helper.assertValueEqual(List.of(production.eventId()), practice.causalLinks(), "Practice must link to the actual gathering event");

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
		helper.assertValueEqual(1L, restored.world().batch(batch.batchId()).quantity(), "The produced batch must survive reload");
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
		Settlement settlement = CivilizationGameTestSupport.isolatedSettlement(helper, data, "Crafting Settlement");
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
		helper.assertTrue(goal.canUse(), "Abstract wood must allow the villager to choose stick crafting");
		goal.start();
		for (int tick = 0; tick < 240
			&& SettlementSimulationService.craftingResourceCount(data, settlement, Items.STICK) < 8
			&& goal.canContinueToUse(); tick++) {
			goal.tick();
		}

		helper.assertValueEqual(0, villager.getInventory().countItem(Items.STICK),
			"Crafted sticks must leave the real inventory when production mapping is enabled");
		helper.assertValueEqual(8L, SettlementSimulationService.craftingResourceCount(data, settlement, Items.STICK),
			"One abstract log must craft eight abstract sticks");
		helper.assertValueEqual(0, villager.getInventory().countItem(Items.OAK_LOG), "The crafting input must be consumed");
		helper.assertValueEqual(
			0L, SettlementSimulationService.craftingResourceCount(data, settlement, Items.OAK_LOG),
			"The abstract log must be consumed exactly once"
		);
		ResourceBatch stickBatch = state.world().batchesAt(state.campOwner()).stream()
			.filter(batch -> batch.resourceType() == ResourceType.STICK).findFirst().orElseThrow();
		SimulationEvent sticksProduct = state.world().ledger().event(stickBatch.originEventId()).orElseThrow();
		helper.assertValueEqual("craft", sticksProduct.context().get("activity"), "The stick batch must come from real crafting");
		helper.assertValueEqual("sticks", sticksProduct.context().get("recipe"), "The stick batch must identify its recipe");
		SimulationEvent sticksWithdrawal = craftingEvent(state, "sticks");
		helper.assertValueEqual(List.of(personId), sticksWithdrawal.actors(), "The real crafter must be recorded");
		helper.assertValueEqual(woodBatchId, sticksWithdrawal.context().get("batchId"), "The stick recipe must use the wood batch");
		helper.assertTrue(
			state.world().ledger().traceOrigins(sticksWithdrawal.eventId()).stream()
				.anyMatch(event -> event.eventId() == woodOriginEventId),
			"The stick material withdrawal must retain its import origin"
		);
		SimulationEvent sticksCompleted = craftingCompletion(state, "sticks");
		helper.assertValueEqual(
			List.of(sticksWithdrawal.eventId()), sticksCompleted.causalLinks(),
			"The completed craft must link to its actual material withdrawal"
		);
		helper.assertValueEqual(List.of(sticksCompleted.eventId()), sticksProduct.causalLinks(),
			"The abstract stick product must link to the completed real craft");
		helper.assertTrue(state.world().ledger().traceOrigins(sticksProduct.eventId()).stream()
			.anyMatch(event -> event.eventId() == woodOriginEventId),
			"The abstract stick product must trace back to the original wood import");
		SimulationEvent sticksPractice = craftingPractice(state, "sticks");
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

		helper.assertTrue(goal.canUse(), "Abstract stone and abstract sticks must allow pickaxe crafting");
		goal.start();
		for (int tick = 0; tick < 240 && !villager.getMainHandItem().is(Items.STONE_PICKAXE) && goal.canContinueToUse(); tick++) {
			goal.tick();
		}

		helper.assertTrue(villager.getMainHandItem().is(Items.STONE_PICKAXE), "The crafted pickaxe must remain a real held tool");
		helper.assertValueEqual(0, villager.getInventory().countItem(Items.STICK),
			"Exported sticks must be consumed by the real pickaxe recipe");
		helper.assertValueEqual(6L, SettlementSimulationService.craftingResourceCount(data, settlement, Items.STICK),
			"The pickaxe recipe must consume exactly two abstract sticks");
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
		SimulationEvent stickWithdrawal = state.world().ledger().events().stream()
			.filter(event -> event.type() == EventType.EXTERNAL_EXPORT)
			.filter(event -> "stone_pickaxe".equals(event.context().get("recipe")))
			.filter(event -> event.inputs().stream().anyMatch(flow -> flow.resourceType() == ResourceType.STICK))
			.findFirst().orElseThrow();
		helper.assertValueEqual(stickBatch.batchId(), stickWithdrawal.context().get("batchId"),
			"The pickaxe must consume the batch created by real stick crafting");
		helper.assertTrue(state.world().ledger().traceOrigins(stickWithdrawal.eventId()).stream()
			.anyMatch(event -> event.eventId() == sticksProduct.eventId()),
			"The pickaxe must retain the crafted stick batch in its ancestry");
		SimulationEvent pickaxeCompleted = craftingCompletion(state, "stone_pickaxe");
		helper.assertValueEqual(
			List.of(pickaxeWithdrawal.eventId(), stickWithdrawal.eventId()), pickaxeCompleted.causalLinks(),
			"Pickaxe completion must link to both actual material withdrawals"
		);
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
		helper.assertTrue(polishGoal.canUse(), "Abstract cobblestone must allow smooth-stone grinding");
		polishGoal.start();
		for (int tick = 0; tick < 800
			&& (villager.getMainHandItem().getEnchantments().getLevel(enchantments.getHolderOrThrow(Enchantments.EFFICIENCY)) < 1
				|| villager.getMainHandItem().getEnchantments().getLevel(enchantments.getHolderOrThrow(Enchantments.UNBREAKING)) < 1); tick++) {
			if (!polishGoal.canContinueToUse()) {
				helper.assertTrue(polishGoal.canUse(), "The next grinding or polishing action must be selectable");
				polishGoal.start();
			}
			polishGoal.tick();
		}

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
			"Polishing must consume all three abstract smooth stones");
		helper.assertValueEqual(0L, SettlementSimulationService.craftingResourceCount(data, settlement, Items.COBBLESTONE),
			"Grinding must consume all three abstract cobblestones");
		helper.assertValueEqual(4L, SettlementSimulationService.craftingResourceCount(data, settlement, Items.STICK),
			"Polishing must consume two more abstract sticks");
		List<SimulationEvent> smoothProducts = state.world().ledger().events().stream()
			.filter(event -> event.type() == EventType.EXTERNAL_IMPORT)
			.filter(event -> "smooth_stone".equals(event.context().get("recipe")))
			.filter(event -> event.outputs().stream().anyMatch(flow -> flow.resourceType() == ResourceType.SMOOTH_STONE))
			.toList();
		helper.assertValueEqual(3, smoothProducts.size(), "Each real grinding action must create one abstract smooth stone");
		for (SimulationEvent product : smoothProducts) {
			helper.assertValueEqual(1, product.causalLinks().size(), "A smooth-stone product needs one completion cause");
			helper.assertValueEqual(EventType.CRAFT_COMPLETED,
				state.world().ledger().event(product.causalLinks().getFirst()).orElseThrow().type(),
				"Each smooth-stone product must link to its real completion");
		}
		helper.assertTrue(state.world().ledger().traceOrigins(smoothProducts.getLast().eventId()).stream()
			.anyMatch(event -> event.eventId() == polishStoneOrigin),
			"Smooth-stone products must trace back to their cobblestone import");
		SimulationEvent polishedCompleted = craftingCompletion(state, "polished_stone_pickaxe");
		helper.assertValueEqual(4, polishedCompleted.causalLinks().size(),
			"Polishing must link three smooth-stone withdrawals and one stick withdrawal");
		helper.assertTrue(state.world().ledger().traceOrigins(polishedCompleted.eventId()).stream()
			.anyMatch(event -> event.eventId() == smoothProducts.getLast().eventId()),
			"The polished tool must retain the crafted smooth-stone ancestry");
		double craftSkillBeforeSave = state.world().person(personId).skill(WorkType.CRAFT);
		state.world().assertConservation();

		CompoundTag saved = data.save(new CompoundTag(), helper.getLevel().registryAccess());
		SettlementSavedData reloaded = SettlementSavedData.load(saved, helper.getLevel().registryAccess());
		SettlementSimulationState restored = reloaded.simulationOf(reloaded.find(bell).orElseThrow()).orElseThrow();
		helper.assertValueEqual(0L, restored.world().inventoryTotals().get(ResourceType.WOOD), "Reload must not restore consumed wood");
		helper.assertValueEqual(0L, restored.world().inventoryTotals().get(ResourceType.STONE), "Reload must not restore consumed stone");
		helper.assertValueEqual(0L, restored.world().inventoryTotals().get(ResourceType.SMOOTH_STONE),
			"Reload must not restore consumed smooth stone");
		helper.assertValueEqual(4L, restored.world().inventoryTotals().get(ResourceType.STICK),
			"Reload must preserve exactly the four unconsumed crafted sticks");
		helper.assertValueEqual(4L, restored.world().batch(stickBatch.batchId()).quantity(),
			"Reload must preserve the original crafted stick batch");
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
