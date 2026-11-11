package com.thysummer.coopcraft.civilization;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.mixin.MobAccessor;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;

public final class SettlementCoordinationGameTest implements FabricGameTest {
	private static final BlockPos BELL = new BlockPos(2, 0, 2);
	private static final BlockPos CHEST = new BlockPos(1, 0, 1);

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400)
	public void foodShortagePausesAndRecoveryResumesRealConstruction(GameTestHelper helper) {
		Scene scene = scene(helper);
		SettlementCoordination.tick(helper.getLevel(), scene.data(), scene.settlement());
		WorkReservation committed = reservation(scene);
		SettlementCoordination.begin(helper.getLevel(), scene.data(), scene.settlement(), scene.builder().getUUID());
		int materials = scene.chest().countItem(Items.COBBLESTONE);

		removeFood(scene.chest());
		SettlementCoordination.requestReplan(scene.data(), scene.settlement());
		SettlementCoordination.tick(helper.getLevel(), scene.data(), scene.settlement());

		assertConstructionUnchanged(helper, scene, materials);
		WorkReservation paused = reservation(scene);
		helper.assertValueEqual(committed.id(), paused.id(), "Food shortage must preserve the builder's commitment");
		helper.assertValueEqual(WorkReservation.Status.PAUSED, paused.status(), "Construction must pause during a food emergency");
		helper.assertValueEqual("food_shortage", paused.cause(), "The pause reason must be visible");
		helper.assertTrue(SettlementCoordination.availableFor(
			helper.getLevel(), scene.data(), scene.settlement(), scene.builder().getUUID()
		).isEmpty(), "A paused builder cannot receive another construction action");

		SettlementStockpile.insert(scene.chest(), new ItemStack(Items.BREAD, 64));
		SettlementCoordination.requestReplan(scene.data(), scene.settlement());
		SettlementCoordination.tick(helper.getLevel(), scene.data(), scene.settlement());

		assertConstructionUnchanged(helper, scene, materials);
		WorkReservation resumed = reservation(scene);
		helper.assertValueEqual(committed.id(), resumed.id(), "Recovery must resume the original commitment");
		helper.assertValueEqual(WorkReservation.Status.COMMITTED, resumed.status(), "A recovered project must be available again");
		helper.assertValueEqual(1L, coordinationEvents(scene, EventType.WORK_PAUSED), "Repeated planning must not duplicate pause events");
		helper.assertValueEqual(1L, coordinationEvents(scene, EventType.WORK_RESUMED), "Recovery must record one resume event");
		cleanup(scene);
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400)
	public void acuteHungerInterruptsTheRealWorkGoalWithoutConsumingMaterials(GameTestHelper helper) {
		Scene scene = scene(helper);
		SettlementCoordination.tick(helper.getLevel(), scene.data(), scene.settlement());
		WorkReservation committed = reservation(scene);
		VillagerWorkGoal goal = workGoal(scene.builder());
		helper.assertTrue(goal.canUse(), "The informed volunteer must be able to start the available project");
		goal.start();
		int materials = scene.chest().countItem(Items.COBBLESTONE);

		scene.data().setSatiety(scene.settlement(), scene.builder().getUUID(), 10);
		removeFood(scene.chest());
		SettlementCoordination.requestReplan(scene.data(), scene.settlement());
		SettlementCoordination.tick(helper.getLevel(), scene.data(), scene.settlement());
		helper.assertFalse(goal.canContinueToUse(), "Acute hunger must interrupt real construction");
		goal.stop();

		assertConstructionUnchanged(helper, scene, materials);
		helper.assertValueEqual("personal_hunger", SettlementCoordination.pausedCause(
			helper.getLevel(), scene.data(), scene.settlement(), scene.builder().getUUID()
		).orElseThrow(), "The personal interruption must remain distinguishable from a material shortage");
		helper.assertFalse(goal.canUse(), "A starving builder cannot immediately reaccept construction");

		SettlementStockpile.insert(scene.chest(), new ItemStack(Items.BREAD, 64));
		scene.data().setSatiety(scene.settlement(), scene.builder().getUUID(), 80);
		SettlementCoordination.requestReplan(scene.data(), scene.settlement());
		SettlementCoordination.tick(helper.getLevel(), scene.data(), scene.settlement());
		helper.assertValueEqual(committed.id(), reservation(scene).id(), "Recovery must keep the original work identity");
		helper.assertTrue(goal.canUse(), "The fed resident must be able to resume the preserved project: " + goal.debugState());
		assertConstructionUnchanged(helper, scene, materials);
		goal.stop();
		cleanup(scene);
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400)
	public void unloadedBuilderIsReplacedWithoutOfflineProgress(GameTestHelper helper) {
		Scene scene = scene(helper);
		SettlementCoordination.tick(helper.getLevel(), scene.data(), scene.settlement());
		SettlementCoordination.begin(helper.getLevel(), scene.data(), scene.settlement(), scene.builder().getUUID());
		int materials = scene.chest().countItem(Items.COBBLESTONE);

		scene.builder().discard();
		SettlementCoordination.requestReplan(scene.data(), scene.settlement());
		SettlementCoordination.tick(helper.getLevel(), scene.data(), scene.settlement());

		assertConstructionUnchanged(helper, scene, materials);
		helper.assertTrue(SettlementCoordination.availableFor(
			helper.getLevel(), scene.data(), scene.settlement(), scene.builder().getUUID()
		).isEmpty(), "An unloaded resident cannot execute a reservation");
		WorkReservation replacement = scene.state().coordinationPlan().reservationFor(scene.forager().getUUID()).orElseThrow();
		helper.assertValueEqual(WorkReservation.Task.BUILD, replacement.task(), "A loaded volunteer may take over the same job");
		cleanup(scene);
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400)
	public void unavailableForageDoesNotOscillateAndResumesForRealBush(GameTestHelper helper) {
		Scene scene = scene(helper);
		removeFood(scene.chest());
		SettlementCoordination.tick(helper.getLevel(), scene.data(), scene.settlement());
		VillagerWorkGoal goal = workGoal(scene.forager());

		helper.assertFalse(goal.canUse(), "No ripe berry bush should offer real forage work: " + goal.debugState());
		WorkReservation paused = scene.state().coordinationPlan().reservationFor(scene.forager().getUUID()).orElseThrow();
		helper.assertValueEqual(WorkReservation.Status.PAUSED, paused.status(), "Unavailable forage must remain paused");
		helper.assertValueEqual("forage_unavailable", paused.cause(), "The blocker must be visible");
		helper.assertValueEqual(1L, coordinationEvents(scene, scene.forager().getUUID(), EventType.WORK_PAUSED),
			"The initial failed search must record one pause");
		for (int review = 0; review < 3; review++) {
			SettlementCoordination.requestReplan(scene.data(), scene.settlement());
			SettlementCoordination.tick(helper.getLevel(), scene.data(), scene.settlement());
			helper.assertValueEqual(1L, coordinationEvents(scene, scene.forager().getUUID(), EventType.WORK_PAUSED),
				"Periodic planning must not append duplicate pauses");
			helper.assertValueEqual(0L, coordinationEvents(scene, scene.forager().getUUID(), EventType.WORK_RESUMED),
				"Periodic planning must not invent a recovery");
		}

		helper.setBlock(new BlockPos(1, 0, 4), Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3));
		clearForageCooldown(goal);
		helper.assertTrue(goal.canUse(), "A real ripe bush must make the paused reservation executable again");
		helper.assertValueEqual(WorkReservation.Status.ACTIVE,
			scene.state().coordinationPlan().reservationFor(scene.forager().getUUID()).orElseThrow().status(),
			"Starting real forage must activate the reservation: " + goal.debugState()
				+ ", cause=" + SettlementCoordination.pausedCause(helper.getLevel(), scene.data(), scene.settlement(), scene.forager().getUUID()));
		helper.assertValueEqual(1L, coordinationEvents(scene, scene.forager().getUUID(), EventType.WORK_RESUMED),
			"Real recovery must record exactly one resume");
		helper.assertValueEqual(1L, coordinationEvents(scene, scene.forager().getUUID(), EventType.WORK_PAUSED),
			"Real recovery must not add another pause");
		goal.stop();
		for (int review = 0; review < 3; review++) {
			SettlementCoordination.requestReplan(scene.data(), scene.settlement());
			SettlementCoordination.tick(helper.getLevel(), scene.data(), scene.settlement());
			helper.assertValueEqual(1L, coordinationEvents(scene, scene.forager().getUUID(), EventType.WORK_RESUMED),
				"Planning after real work must not invent another recovery");
		}
		cleanup(scene);
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400)
	public void foodShortageHarvestsReplantsAndDepositsRealCrop(GameTestHelper helper) {
		Scene scene = scene(helper);
		scene.data().setStage(scene.settlement(), SettlementStage.AGRICULTURAL);
		removeFood(scene.chest());
		BlockPos cropPos = new BlockPos(1, 0, 4);
		var beetroots = (net.minecraft.world.level.block.CropBlock) Blocks.BEETROOTS;
		helper.setBlock(cropPos.below(), Blocks.FARMLAND);
		helper.setBlock(cropPos, beetroots.getStateForAge(beetroots.getMaxAge()));
		SettlementCoordination.tick(helper.getLevel(), scene.data(), scene.settlement());
		VillagerWorkGoal goal = workGoal(scene.forager());

		helper.assertTrue(goal.canUse(), "A mature food crop must satisfy the existing food reservation");
		goal.start();
		scene.forager().setPos(helper.absolutePos(cropPos).getCenter());
		for (int tick = 0; tick < 120 && SettlementFarming.isMature(helper.getLevel().getBlockState(helper.absolutePos(cropPos))); tick++) {
			CivilizationGameTestSupport.advanceOneTick(helper);
			goal.tick();
		}

		helper.assertValueEqual(beetroots.getStateForAge(0), helper.getLevel().getBlockState(helper.absolutePos(cropPos)),
			"Harvesting must reserve one real beetroot seed and replant the crop");
		helper.assertTrue(scene.chest().countItem(Items.BEETROOT) > 0, "The edible harvest must enter the real common stockpile");
		helper.assertValueEqual(0, scene.forager().getInventory().countItem(Items.BEETROOT),
			"The harvest must not remain duplicated in the resident inventory");
		int deposited = scene.chest().countItem(Items.BEETROOT);
		helper.assertValueEqual(0, SettlementFarming.harvest(
			helper.getLevel(), scene.data(), scene.settlement(), scene.forager(), helper.absolutePos(cropPos)
		), "An immature replanted crop cannot be harvested again");
		helper.assertValueEqual(deposited, scene.chest().countItem(Items.BEETROOT), "Repeated harvest cannot duplicate food");
		goal.stop();
		cleanup(scene);
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 400)
	public void potatoSeedIsConsumedOnceBeforeRealHarvest(GameTestHelper helper) {
		Scene scene = scene(helper);
		scene.data().setStage(scene.settlement(), SettlementStage.AGRICULTURAL);
		removeFood(scene.chest());
		scene.chest().setItem(0, new ItemStack(Items.POTATO));
		BlockPos cropPos = new BlockPos(1, 0, 4);
		helper.setBlock(cropPos.below(), Blocks.FARMLAND);
		helper.setBlock(cropPos, Blocks.AIR);
		SettlementCoordination.tick(helper.getLevel(), scene.data(), scene.settlement());
		VillagerWorkGoal goal = workGoal(scene.forager());

		helper.assertTrue(goal.canUse(), "One potato and empty farmland must offer real planting work");
		goal.start();
		scene.forager().setPos(helper.absolutePos(cropPos).getCenter());
		for (int tick = 0; tick < 120 && helper.getLevel().getBlockState(helper.absolutePos(cropPos)).isAir(); tick++) {
			CivilizationGameTestSupport.advanceOneTick(helper);
			goal.tick();
		}

		helper.assertValueEqual(Blocks.POTATOES.defaultBlockState(), helper.getLevel().getBlockState(helper.absolutePos(cropPos)),
			"Planting must place one real potato crop");
		helper.assertValueEqual(0, scene.chest().countItem(Items.POTATO), "Planting must consume the only stored potato");
		helper.assertValueEqual(0, scene.forager().getInventory().countItem(Items.POTATO),
			"Planting cannot leave a second potato in the worker inventory");

		var potatoes = (net.minecraft.world.level.block.CropBlock) Blocks.POTATOES;
		helper.setBlock(cropPos, potatoes.getStateForAge(potatoes.getMaxAge()));
		helper.assertTrue(goal.canUse(), "The newly matured crop must use the existing harvest action");
		goal.start();
		scene.forager().setPos(helper.absolutePos(cropPos).getCenter());
		for (int tick = 0; tick < 120 && SettlementFarming.isMature(helper.getLevel().getBlockState(helper.absolutePos(cropPos))); tick++) {
			CivilizationGameTestSupport.advanceOneTick(helper);
			goal.tick();
		}
		helper.assertValueEqual(potatoes.getStateForAge(0), helper.getLevel().getBlockState(helper.absolutePos(cropPos)),
			"The mature potato crop must be harvested and replanted once");
		int potatoTotal = scene.chest().countItem(Items.POTATO) + scene.forager().getInventory().countItem(Items.POTATO);
		helper.assertValueEqual(0, SettlementFarming.harvest(
			helper.getLevel(), scene.data(), scene.settlement(), scene.forager(), helper.absolutePos(cropPos)
		), "The replanted immature crop cannot yield a duplicate harvest");
		helper.assertValueEqual(potatoTotal,
			scene.chest().countItem(Items.POTATO) + scene.forager().getInventory().countItem(Items.POTATO),
			"A repeated harvest cannot duplicate potatoes");
		goal.stop();
		cleanup(scene);
		helper.succeed();
	}

	private static Scene scene(GameTestHelper helper) {
		CivilizationGameTestSupport.clearAround(helper, BELL, 10);
		helper.setBlock(CHEST, Blocks.CHEST);
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		Settlement settlement = CivilizationGameTestSupport.isolatedSettlement(helper, data, "Coordination Settlement");
		data.setStockpile(settlement, helper.absolutePos(CHEST));
		Villager forager = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 0, 3));
		data.permitForagingArea(settlement, forager.getUUID(), helper.absolutePos(BELL), 12);
		Villager builder = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 0, 3));
		long day = helper.getLevel().getDayTime() / net.minecraft.world.level.Level.TICKS_PER_DAY;
		data.registerMember(settlement, forager.getUUID(), day);
		data.registerMember(settlement, builder.getUUID(), day);
		data.registerLoadedResidents(helper.getLevel(), settlement, List.of(forager, builder));
		setPersonality(data, settlement, forager, new VillagerPersonality(30, 10, 90));
		setPersonality(data, settlement, builder, new VillagerPersonality(90, 10, 10));
		Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));
		chest.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
		chest.setItem(1, new ItemStack(Items.OAK_PLANKS, 64));
		chest.setItem(2, new ItemStack(Items.WHITE_BED));
		chest.setItem(3, new ItemStack(Items.OAK_DOOR));
		chest.setItem(4, new ItemStack(Items.BREAD, 64));
		BlockPos origin = settlement.center().north(6);
		data.setConstructionJob(settlement, ConstructionJob.start(BlueprintManager.get(ConstructionPlanner.STONE_COTTAGE), origin));
		ConstructionSystem.BuildTarget target = ConstructionSystem.nextBuildTarget(helper.getLevel(), data, settlement).orElseThrow();
		helper.assertTrue(ConstructionSystem.buildNext(helper.getLevel(), data, settlement, target, builder),
			"The test must start with one real placed block");
		helper.assertValueEqual(1, data.getConstructionJob(settlement).orElseThrow().nextIndex(), "The real block must advance the job");
		helper.assertValueEqual(63, chest.countItem(Items.COBBLESTONE), "The real block must consume exactly one material");
		return new Scene(
			data, settlement, data.simulationOf(settlement).orElseThrow(), forager, builder, chest,
			data.getConstructionJob(settlement).orElseThrow(), target.position()
		);
	}

	private static WorkReservation reservation(Scene scene) {
		return scene.state().coordinationPlan().reservationFor(scene.builder().getUUID()).orElseThrow();
	}

	private static void assertConstructionUnchanged(GameTestHelper helper, Scene scene, int expectedMaterials) {
		helper.assertValueEqual(scene.job(), scene.data().getConstructionJob(scene.settlement()).orElseThrow(),
			"Coordination cannot advance, cancel, or replace the construction job");
		helper.assertValueEqual(expectedMaterials, scene.chest().countItem(Items.COBBLESTONE),
			"Coordination cannot consume building materials");
		helper.assertTrue(helper.getLevel().getBlockState(scene.builtPosition()).is(Blocks.COBBLESTONE),
			"The already placed block must remain in the world");
	}

	private static void clearForageCooldown(VillagerWorkGoal goal) {
		try {
			Field field = VillagerWorkGoal.class.getDeclaredField("forageCooldownUntil");
			field.setAccessible(true);
			field.setLong(goal, 0);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Could not clear the forage test cooldown", exception);
		}
	}

	private static VillagerWorkGoal workGoal(Villager villager) {
		for (var wrapped : ((MobAccessor) villager).getGoalSelector().getAvailableGoals()) {
			if (wrapped.getGoal() instanceof VillagerWorkGoal goal) {
				return goal;
			}
		}
		throw new IllegalStateException("The villager work goal was not installed");
	}

	private static long coordinationEvents(Scene scene, EventType type) {
		return coordinationEvents(scene, scene.builder().getUUID(), type);
	}

	private static long coordinationEvents(Scene scene, UUID actorId, EventType type) {
		String actor = scene.state().residentId(actorId).orElseThrow();
		return scene.state().world().ledger().events().stream()
			.filter(event -> event.type() == type && event.actors().equals(List.of(actor))).count();
	}

	private static void removeFood(Container chest) {
		VillagerWorkGoal.removeItems(chest, Items.BREAD, chest.countItem(Items.BREAD));
	}

	private static void setPersonality(
		SettlementSavedData data,
		Settlement settlement,
		Villager villager,
		VillagerPersonality personality
	) {
		try {
			Field field = SettlementSavedData.class.getDeclaredField("members");
			field.setAccessible(true);
			@SuppressWarnings("unchecked")
			Map<UUID, SettlementMember> members = (Map<UUID, SettlementMember>) field.get(data);
			members.put(villager.getUUID(), new SettlementMember(
				villager.getUUID(), settlement.id(), 0, personality
			));
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Could not set a deterministic test personality", exception);
		}
	}

	private static Settlement isolatedSettlement(GameTestHelper helper, SettlementSavedData data) {
		Settlement settlement = new Settlement(UUID.randomUUID(), helper.absolutePos(BELL), "Coordination Settlement", 0);
		try {
			Field field = SettlementSavedData.class.getDeclaredField("settlements");
			field.setAccessible(true);
			@SuppressWarnings("unchecked")
			Map<BlockPos, Settlement> settlements = (Map<BlockPos, Settlement>) field.get(data);
			settlements.put(settlement.center(), settlement);
			return settlement;
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Could not create an isolated coordination test settlement", exception);
		}
	}

	private static void cleanup(Scene scene) {
		scene.forager().kill();
		scene.builder().kill();
		scene.data().removeMember(scene.forager().getUUID());
		scene.data().removeMember(scene.builder().getUUID());
		try {
			remove(scene.data(), "settlements", scene.settlement().center());
			for (String name : List.of(
				"socialOrganizations", "stages", "stockpiles", "hearths", "resourceSites", "craftingStations",
				"constructionJobs", "housingBeds", "grindingStations", "leaders", "simulations"
			)) {
				remove(scene.data(), name, scene.settlement().id());
			}
			scene.data().setDirty();
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Could not clean up the coordination test settlement", exception);
		}
	}

	private static void remove(SettlementSavedData data, String fieldName, Object key) throws ReflectiveOperationException {
		Field field = SettlementSavedData.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		((Map<?, ?>) field.get(data)).remove(key);
	}

	private record Scene(
		SettlementSavedData data,
		Settlement settlement,
		SettlementSimulationState state,
		Villager forager,
		Villager builder,
		Container chest,
		ConstructionJob job,
		BlockPos builtPosition
	) {
	}
}
