package com.thysummer.coopcraft.civilization;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.EventType;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

public final class SettlementCareGameTest implements FabricGameTest {
	private static final BlockPos BELL = new BlockPos(2, 0, 2);
	private static final BlockPos CHEST = new BlockPos(1, 0, 1);

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000, batch = "settlement_care")
	public void nonRelativeCareUsesOneRealWorkSlot(GameTestHelper helper) {
		CareScene scene = scene(helper);
		CareAssignment assignment = scene.state().careForDependent(scene.dependent().getUUID()).orElseThrow();
		helper.assertValueEqual(scene.caregiver().getUUID(), assignment.caregiverId(), "The trusted non-relative must volunteer");
		helper.assertFalse(assignment.completed(), "Planning must not complete care instantly");
		helper.assertValueEqual(0L, careEvents(scene.state()), "No care event exists before real work");
		helper.runAfterDelay(20, () -> helper.assertValueEqual(
			"care", VillagerWorkGoal.statusOf(scene.caregiver()).action(), "The caregiver must perform the care action"
		));
		helper.runAfterDelay(CareAssignment.DURATION_TICKS + 60, () -> {
			helper.assertValueEqual(1L, careEvents(scene.state()), "Exactly one care event must be recorded");
			CareAssignment completed = scene.state().careForDependent(scene.dependent().getUUID()).orElseThrow();
			helper.assertTrue(completed.completed(), "The care assignment must finish after real ticks");
			String caregiverPerson = scene.state().residentId(scene.caregiver().getUUID()).orElseThrow();
			helper.assertValueEqual(1, scene.state().world().person(caregiverPerson).careHours(), "Care must consume labor time");
			helper.assertFalse(
				SettlementCareService.activeForCaregiver(
					helper.getLevel(), scene.data(), scene.settlement(), scene.caregiver().getUUID()
				).isPresent(),
				"A completed assignment cannot keep occupying work"
			);
			helper.assertFalse("care".equals(VillagerWorkGoal.statusOf(scene.caregiver()).action()),
				"The caregiver must return to ordinary work selection");
			scene.caregiver().kill();
			scene.dependent().kill();
			cleanup(scene);
			helper.succeed();
		});
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void careProgressSurvivesTwoReloadsWithoutDuplicateEvents(GameTestHelper helper) {
		CareScene scene = scene(helper);
		for (int tick = 0; tick < 40; tick++) {
			helper.assertFalse(SettlementCareService.progressCare(
				helper.getLevel(), scene.data(), scene.settlement(), scene.caregiver().getUUID()
			), "Care must not finish after forty ticks");
		}
		SettlementSavedData current = scene.data();
		Settlement settlement = scene.settlement();
		for (int reload = 0; reload < 2; reload++) {
			current = SettlementSavedData.load(
				current.save(new CompoundTag(), helper.getLevel().registryAccess()), helper.getLevel().registryAccess()
			);
			settlement = current.find(scene.settlement().center()).orElseThrow();
			SettlementSimulationState restored = current.simulationOf(settlement).orElseThrow();
			CareAssignment restoredAssignment = restored.careForDependent(scene.dependent().getUUID()).orElseThrow();
			helper.assertValueEqual(40, restoredAssignment.elapsedTicks(), "Elapsed care must survive reload " + reload);
			helper.assertFalse(restoredAssignment.completed(), "Reload cannot complete unfinished care");
			helper.assertValueEqual(0L, careEvents(restored), "Reload cannot create a care event");
		}
		for (int tick = 40; tick < CareAssignment.DURATION_TICKS; tick++) {
			SettlementCareService.progressCare(helper.getLevel(), current, settlement, scene.caregiver().getUUID());
		}
		SettlementSimulationState completedState = current.simulationOf(settlement).orElseThrow();
		helper.assertValueEqual(1L, careEvents(completedState), "Resumed care must produce one event");
		String caregiverPerson = completedState.residentId(scene.caregiver().getUUID()).orElseThrow();
		helper.assertValueEqual(1, completedState.world().person(caregiverPerson).careHours(), "Labor hours must survive completion");
		helper.assertTrue(SettlementCareService.progressCare(
			helper.getLevel(), current, settlement, scene.caregiver().getUUID()
		), "A duplicate completion callback must be a no-op");
		helper.assertValueEqual(1L, careEvents(completedState), "A duplicate callback cannot append another event");

		SettlementSavedData reloaded = SettlementSavedData.load(
			current.save(new CompoundTag(), helper.getLevel().registryAccess()), helper.getLevel().registryAccess()
		);
		Settlement reloadedSettlement = reloaded.find(settlement.center()).orElseThrow();
		SettlementSimulationState reloadedState = reloaded.simulationOf(reloadedSettlement).orElseThrow();
		helper.assertTrue(reloadedState.careForDependent(scene.dependent().getUUID()).orElseThrow().completed(),
			"Completed status must survive reload");
		helper.assertValueEqual(1, reloadedState.world().person(caregiverPerson).careHours(), "Labor hours must survive reload");
		helper.assertTrue(SettlementCareService.progressCare(
			helper.getLevel(), reloaded, reloadedSettlement, scene.caregiver().getUUID()
		), "Completed care must remain a no-op after reload");
		helper.assertValueEqual(1L, careEvents(reloadedState), "Reloading completed care cannot duplicate its event");
		scene.caregiver().kill();
		scene.dependent().kill();
		cleanup(scene);
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void carePausesOutsideRangeAndResumes(GameTestHelper helper) {
		CareScene scene = scene(helper);
		scene.dependent().teleportTo(scene.dependent().getX() + 100, scene.dependent().getY(), scene.dependent().getZ());
		helper.assertTrue(SettlementCareService.activeForCaregiver(
			helper.getLevel(), scene.data(), scene.settlement(), scene.caregiver().getUUID()
		).isEmpty(), "An out-of-range dependent cannot receive care");
		helper.assertTrue(SettlementCareService.progressCare(
			helper.getLevel(), scene.data(), scene.settlement(), scene.caregiver().getUUID()
		), "The caregiver must stop when the dependent is unavailable");
		helper.assertValueEqual(0, scene.state().careForDependent(scene.dependent().getUUID()).orElseThrow().elapsedTicks(),
			"Unavailable care cannot consume progress");
		scene.dependent().teleportTo(scene.caregiver().getX(), scene.caregiver().getY(), scene.caregiver().getZ());
		helper.assertTrue(SettlementCareService.activeForCaregiver(
			helper.getLevel(), scene.data(), scene.settlement(), scene.caregiver().getUUID()
		).isPresent(), "Care must resume when the dependent returns");
		scene.caregiver().kill();
		scene.dependent().kill();
		cleanup(scene);
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void deadCaregiverIsReplacedWithoutReplayingHistory(GameTestHelper helper) {
		CareScene scene = scene(helper);
		Villager replacement = helper.spawn(EntityType.VILLAGER, new BlockPos(4, 0, 3));
		scene.data().registerLoadedResidents(helper.getLevel(), scene.settlement(), List.of(replacement));
		String replacementPerson = scene.state().residentId(replacement.getUUID()).orElseThrow();
		String dependentPerson = scene.state().residentId(scene.dependent().getUUID()).orElseThrow();
		scene.state().world().person(replacementPerson).setTrust(dependentPerson, 30);
		UUID original = scene.caregiver().getUUID();
		scene.caregiver().kill();
		scene.data().removeMember(original);

		long day = helper.getLevel().getDayTime() / Level.TICKS_PER_DAY;
		List<CareAssignment> replanned = CarePlanner.plan(
			helper.getLevel(), scene.data(), scene.settlement(), day, scene.state().careAssignments()
		);
		scene.state().replaceCareAssignments(day, replanned);

		CareAssignment assignment = scene.state().careForDependent(scene.dependent().getUUID()).orElseThrow();
		helper.assertValueEqual(replacement.getUUID(), assignment.caregiverId(), "Another willing resident must take over");
		helper.assertValueEqual(0L, careEvents(scene.state()), "Reassignment must not invent completed care");
		scene.dependent().kill();
		replacement.kill();
		scene.data().removeMember(replacement.getUUID());
		cleanup(scene);
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void realCareMealConsumesOneGiftAndTracesItsRequest(GameTestHelper helper) {
		CareScene scene = scene(helper);
		long day = helper.getLevel().getDayTime() / Level.TICKS_PER_DAY;
		long request = scene.state().careFoodRequest(scene.dependent().getUUID(), day).orElseThrow();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.moveTo(scene.dependent().getX(), scene.dependent().getY(), scene.dependent().getZ());
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD, 2));
		var chest = (net.minecraft.world.Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));

		VillagerInteraction.OfferResult gift = VillagerInteraction.offerFood(
			player, scene.dependent(), scene.settlement(), scene.data()
		);

		helper.assertTrue(gift.accepted(), "The dependent must accept real care food: " + gift.reason());
		helper.assertValueEqual(1, player.getInventory().countItem(Items.BREAD), "Only one bread may leave the player");
		helper.assertValueEqual(1, chest.countItem(Items.BREAD), "The gift must enter the real public stockpile");
		var giftEvent = scene.state().world().ledger().events().getLast();
		helper.assertValueEqual(EventType.GIFT_RECEIVED, giftEvent.type(), "The player help must be recorded");
		helper.assertTrue(giftEvent.causalLinks().contains(request), "The gift must answer the care food request");
		helper.assertValueEqual(Long.toString(request), giftEvent.context().get("careRequestId"), "The gift must name its request");
		ItemStack distributedRation = chest.removeItem(0, 1);
		helper.assertTrue(scene.caregiver().getInventory().addItem(distributedRation).isEmpty(),
			"The public ration cycle may move the gift into the caregiver's inventory");

		completeCare(helper, scene);

		helper.assertValueEqual(0, chest.countItem(Items.BREAD), "Care must consume exactly the gifted bread");
		helper.assertValueEqual(0, scene.caregiver().getInventory().countItem(Items.BREAD),
			"Care must consume the caregiver's distributed ration");
		helper.assertValueEqual(100, scene.data().needsOf(scene.settlement(), scene.dependent().getUUID()).satiety(),
			"The real meal must restore the dependent's satiety");
		var care = scene.state().world().ledger().events().getLast();
		helper.assertValueEqual(EventType.CARE_PROVIDED, care.type(), "The completed care slot must be recorded");
		helper.assertValueEqual("20", care.context().get("mealNutrition"), "The care event must record real nutrition");
		helper.assertValueEqual(Items.BREAD.getDescriptionId(), care.context().get("foodItem"), "The care event must name the food");
		helper.assertValueEqual("caregiver_inventory", care.context().get("foodSource"), "The meal must come from the caregiver");
		helper.assertTrue(care.causalLinks().contains(request), "The care event must trace the original request");
		scene.caregiver().kill();
		scene.dependent().kill();
		cleanup(scene);
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void realMealBeforeCareCompletionRemainsLinkedWithoutDuplicateConsumption(GameTestHelper helper) {
		CareScene scene = scene(helper);
		long day = helper.getLevel().getDayTime() / Level.TICKS_PER_DAY;
		long request = scene.state().careFoodRequest(scene.dependent().getUUID(), day).orElseThrow();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.moveTo(scene.dependent().getX(), scene.dependent().getY(), scene.dependent().getZ());
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD, 2));
		var chest = (net.minecraft.world.Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));
		helper.assertTrue(VillagerInteraction.offerFood(player, scene.dependent(), scene.settlement(), scene.data()).accepted(),
			"The dependent must accept the real gift");

		helper.assertValueEqual(4, SettlementNeeds.eat(helper.getLevel(), scene.data(), scene.settlement(), scene.dependent()),
			"The dependent may eat through the ordinary work goal before care completes");
		var consumption = scene.state().world().ledger().events().getLast();
		helper.assertValueEqual(EventType.CONSUME, consumption.type(), "The ordinary real meal must be observable");
		helper.assertTrue(consumption.causalLinks().contains(request), "The ordinary meal must answer the care request");
		helper.assertValueEqual(0, chest.countItem(Items.BREAD), "The ordinary meal must consume the real bread");

		completeCare(helper, scene);

		var care = scene.state().world().ledger().events().getLast();
		helper.assertValueEqual(EventType.CARE_PROVIDED, care.type(), "Care must still complete after an ordinary meal");
		helper.assertValueEqual("20", care.context().get("mealNutrition"), "Care must reuse the earlier real meal");
		helper.assertValueEqual("public_stockpile", care.context().get("foodSource"), "Care must retain the earlier food source");
		helper.assertTrue(care.causalLinks().contains(consumption.eventId()), "Care must link the earlier consumption event");
		helper.assertValueEqual(0, chest.countItem(Items.BREAD), "Care completion must not consume another bread");
		helper.assertValueEqual(1, player.getInventory().countItem(Items.BREAD), "The player must retain the second bread");
		scene.caregiver().kill();
		scene.dependent().kill();
		cleanup(scene);
		helper.succeed();
	}

	@GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 1000)
	public void abstractCareMealTracesPlayerImportAllocationAndConsumption(GameTestHelper helper) {
		CareScene scene = scene(helper);
		SettlementSimulationService.setSurvivalEnabled(helper.getLevel(), scene.data(), scene.settlement(), true);
		long day = helper.getLevel().getDayTime() / Level.TICKS_PER_DAY;
		long request = scene.state().careFoodRequest(scene.dependent().getUUID(), day).orElseThrow();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.moveTo(scene.dependent().getX(), scene.dependent().getY(), scene.dependent().getZ());
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.POTATO, 2));
		var chest = (net.minecraft.world.Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));

		VillagerInteraction.OfferResult gift = VillagerInteraction.offerFood(
			player, scene.dependent(), scene.settlement(), scene.data()
		);

		helper.assertTrue(gift.accepted(), "The dependent must accept mapped care food: " + gift.reason());
		helper.assertValueEqual(1, player.getInventory().countItem(Items.POTATO), "Only one potato may leave the player");
		helper.assertValueEqual(0, chest.countItem(Items.POTATO), "Abstract food must not also remain in the real chest");
		var giftEvent = scene.state().world().ledger().events().getLast();
		helper.assertValueEqual(EventType.GIFT_RECEIVED, giftEvent.type(), "The abstract gift must be recorded");
		helper.assertTrue(giftEvent.causalLinks().contains(request), "The abstract gift must answer the care request");
		helper.assertValueEqual(24, SettlementNeeds.eat(helper.getLevel(), scene.data(), scene.settlement(), scene.dependent()),
			"The dependent may use the ordinary abstract meal path before care completes");

		completeCare(helper, scene);

		helper.assertValueEqual(100, scene.data().needsOf(scene.settlement(), scene.dependent().getUUID()).satiety(),
			"The abstract meal must synchronize real satiety");
		var care = scene.state().world().ledger().events().getLast();
		helper.assertValueEqual(EventType.CARE_PROVIDED, care.type(), "The abstract care slot must be recorded");
		helper.assertValueEqual("24", care.context().get("mealNutrition"), "One potato must provide its mapped nutrition");
		helper.assertValueEqual("TUBERS", care.context().get("foodItem"), "The abstract food must remain identifiable");
		var ancestry = scene.state().world().ledger().traceAncestry(care.eventId());
		helper.assertTrue(ancestry.stream().anyMatch(event -> event.type() == EventType.EXTERNAL_IMPORT
			&& "care_food_gift".equals(event.context().get("activity"))), "Care must trace the player's material import");
		helper.assertTrue(ancestry.stream().anyMatch(event -> event.type() == EventType.SHARE), "Care must trace the common allocation");
		helper.assertTrue(ancestry.stream().anyMatch(event -> event.type() == EventType.CONSUME), "Care must trace real consumption");
		helper.assertValueEqual(0L, scene.state().world().batchesAt(scene.state().campOwner()).stream()
			.filter(batch -> batch.resourceType().isFood()).count(), "The single abstract food batch must be consumed");
		scene.state().world().assertConservation();
		scene.caregiver().kill();
		scene.dependent().kill();
		cleanup(scene);
		helper.succeed();
	}

	private static void completeCare(GameTestHelper helper, CareScene scene) {
		for (int tick = 0; tick < CareAssignment.DURATION_TICKS; tick++) {
			SettlementCareService.progressCare(
				helper.getLevel(), scene.data(), scene.settlement(), scene.caregiver().getUUID()
			);
		}
		helper.assertTrue(scene.state().careForDependent(scene.dependent().getUUID()).orElseThrow().completed(),
			"The care assignment must complete before checking its meal");
	}

	private static CareScene scene(GameTestHelper helper) {
		helper.setBlock(CHEST, Blocks.CHEST);
		SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
		Settlement settlement = isolatedSettlement(helper, data);
		data.setStockpile(settlement, helper.absolutePos(CHEST));
		Villager caregiver = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 0, 3));
		Villager dependent = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 0, 3));
		dependent.setBaby(true);
		data.registerLoadedResidents(helper.getLevel(), settlement, List.of(caregiver, dependent));
		SettlementSimulationState state = data.simulationOf(settlement).orElseThrow();
		String caregiverPerson = state.residentId(caregiver.getUUID()).orElseThrow();
		String dependentPerson = state.residentId(dependent.getUUID()).orElseThrow();
		state.world().person(caregiverPerson).setTrust(dependentPerson, 30);
		SettlementCareService.tick(helper.getLevel(), data, settlement);
		return new CareScene(data, settlement, state, caregiver, dependent);
	}

	private static void cleanup(CareScene scene) {
		scene.data().removeMember(scene.caregiver().getUUID());
		scene.data().removeMember(scene.dependent().getUUID());
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
			throw new IllegalStateException("Could not clean up the care test settlement", exception);
		}
	}

	private static void remove(SettlementSavedData data, String fieldName, Object key) throws ReflectiveOperationException {
		Field field = SettlementSavedData.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		((Map<?, ?>) field.get(data)).remove(key);
	}

	private static Settlement isolatedSettlement(GameTestHelper helper, SettlementSavedData data) {
		Settlement settlement = new Settlement(UUID.randomUUID(), helper.absolutePos(BELL), "Care Settlement", 0);
		try {
			Field field = SettlementSavedData.class.getDeclaredField("settlements");
			field.setAccessible(true);
			@SuppressWarnings("unchecked")
			Map<BlockPos, Settlement> settlements = (Map<BlockPos, Settlement>) field.get(data);
			settlements.put(settlement.center(), settlement);
			return settlement;
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Could not create an isolated care test settlement", exception);
		}
	}

	private static long careEvents(SettlementSimulationState state) {
		return state.world().ledger().events().stream().filter(event -> event.type() == EventType.CARE_PROVIDED).count();
	}

	private record CareScene(
		SettlementSavedData data,
		Settlement settlement,
		SettlementSimulationState state,
		Villager caregiver,
		Villager dependent
	) {
	}
}
