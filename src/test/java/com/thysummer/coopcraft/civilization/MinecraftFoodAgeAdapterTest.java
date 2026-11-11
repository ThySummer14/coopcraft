package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.thysummer.coopcraft.civilization.simulation.Camp;
import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.ResourceBatch;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.StorageOwner;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Real detached ItemStacks and SimpleContainers; no server, GameTest, or live gathering runs here. */
class MinecraftFoodAgeAdapterTest {
	private static final Settlement SETTLEMENT = new Settlement(new UUID(812, 1), BlockPos.ZERO, "Food age", 0);
	private static HolderLookup.Provider registries;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}

	@Test
	void plainLegacyFoodImportsAtObservedDayAndOnlyDebitsRequestedUnits() {
		for (ResourceType type : foodTypes()) {
			SettlementSimulationState state = stateAt(2);
			SimpleContainer source = new SimpleContainer(2);
			ItemStack original = new ItemStack(item(type), 7);
			source.setItem(0, original);
			assertEquals(3, intake(source, state, type, 3));
			assertEquals(4, source.getItem(0).getCount());
			assertEquals(7, original.getCount(), "Planning and commit must not mutate the caller's stack reference");
			ResourceBatch first = state.world().batches().getFirst();
			assertEquals(3, first.quantity());
			assertEquals(2, first.harvestDay());
			assertEquals(2 + type.shelfLifeDays(), first.expiryDay());
			assertEquals(4, intake(source, state, type, 100));
			assertTrue(source.isEmpty());
			CompoundTag after = state.save();
			assertEquals(0, intake(source, state, type, 100));
			assertEquals(after, state.save());
			assertEquals(7, stock(state, type));
			assertEquals(2, state.world().ledger().events().size());
			for (var event : state.world().ledger().events()) {
				assertEquals(EventType.EXTERNAL_IMPORT, event.type());
				assertEquals(2, event.day());
				assertTrue(event.actors().isEmpty());
				assertTrue(event.causalLinks().isEmpty());
				assertEquals(state.campOwner().toString(), event.context().get("holder"));
			}
			state.world().assertConservation();
		}
	}

	@Test
	void markedStacksKeepSeparateAgesAndPartialRemainderKeepsExactComponents() {
		SettlementSimulationState state = stateAt(3);
		ItemStack older = marked(state, Items.SWEET_BERRIES, 3, 0);
		ItemStack younger = marked(state, Items.SWEET_BERRIES, 7, 2);
		SimpleContainer source = new SimpleContainer(older, younger, new ItemStack(Items.SWEET_BERRIES, 2));
		assertEquals(6, intake(source, state, ResourceType.WILD_FRUIT, 6));
		assertTrue(source.getItem(0).isEmpty());
		assertStack(younger.copyWithCount(4), source.getItem(1));
		assertEquals(2, source.getItem(2).getCount());
		List<ResourceBatch> batches = state.world().batches();
		assertEquals(2, batches.size());
		assertEquals(List.of(0L, 2L), batches.stream().map(ResourceBatch::harvestDay).sorted().toList());
		assertEquals(List.of(3L, 5L), batches.stream().map(ResourceBatch::expiryDay).sorted().toList());
		assertTrue(batches.stream().allMatch(batch -> batch.quantity() == 3));
		assertEquals(7, younger.getCount());
		assertTrue(MinecraftStockpileAdapter.resourceType(younger).isEmpty());
		state.world().assertConservation();
	}

	@Test
	void partialExportFullContainerAndRepeatOnlyMoveActuallyAcceptedQuantity() {
		SettlementSimulationState state = stateAt(2);
		ResourceBatch batch = initial(state, ResourceType.WILD_FRUIT, 10, 0);
		ItemStack existing = marked(state, Items.SWEET_BERRIES, 63, 0);
		SimpleContainer target = new SimpleContainer(existing, new ItemStack(Items.DIRT, 64));
		assertEquals(1, export(state, batch.batchId(), 10, target));
		assertEquals(9, batch.quantity());
		assertStack(existing.copyWithCount(64), target.getItem(0));
		assertEquals(63, existing.getCount());
		CompoundTag before = state.save();
		List<ItemStack> stacks = copyStacks(target);
		assertEquals(0, export(state, batch.batchId(), 10, target));
		assertEquals(before, state.save());
		assertStacks(stacks, target);
		target.setItem(1, ItemStack.EMPTY);
		assertEquals(9, export(state, batch.batchId(), 100, target));
		assertTrue(state.world().batches().isEmpty());
		assertStack(existing.copyWithCount(9), target.getItem(1));
		assertEquals(73, target.countItem(Items.SWEET_BERRIES));
		before = state.save();
		stacks = copyStacks(target);
		assertThrows(IllegalArgumentException.class, () -> export(state, batch.batchId(), 10, target));
		assertEquals(before, state.save());
		assertStacks(stacks, target);
		assertEquals(3, state.world().ledger().events().size());
		state.world().assertConservation();
	}

	@Test
	void sameItemWithDifferentDatesClockOrComponentsCannotMergeOrBeTakenAsRollback() {
		SettlementSimulationState state = stateAt(2);
		ResourceBatch batch = initial(state, ResourceType.TUBERS, 4, 0);
		ItemStack newer = marked(state, Items.POTATO, 63, 1);
		ItemStack foreign = SettlementFoodAge.stamp(new ItemStack(Items.POTATO, 63), UUID.randomUUID(), 0);
		ItemStack named = marked(state, Items.POTATO, 63, 0);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep this exact food"));
		SimpleContainer target = new SimpleContainer(newer, foreign, named, new ItemStack(Items.POTATO, 63));
		List<ItemStack> original = copyStacks(target);
		CompoundTag before = state.save();
		assertEquals(0, export(state, batch.batchId(), 4, target));
		assertEquals(before, state.save());
		assertStacks(original, target);
		target.setItem(3, marked(state, Items.POTATO, 63, 0));
		assertEquals(1, export(state, batch.batchId(), 4, target));
		for (int slot = 0; slot < 3; slot++) assertStack(original.get(slot), target.getItem(slot));
		assertEquals(3, batch.quantity());
		state.world().assertConservation();
	}

	@Test
	void expiryDayIsInclusiveButNextObservedDayRejectsBothDirectionsWithoutMutation() {
		SettlementSimulationState state = stateAt(2);
		SimpleContainer food = new SimpleContainer(marked(state, Items.COD, 4, 0));
		assertEquals(4, intake(food, state, ResourceType.FISH, 4));
		ResourceBatch batch = state.world().batches().getFirst();
		assertEquals(2, export(state, batch.batchId(), 2, food));
		state.world().advanceDay();
		CompoundTag before = state.save();
		List<ItemStack> stacks = copyStacks(food);
		assertEquals(0, intake(food, state, ResourceType.FISH, 10));
		assertEquals(0, export(state, batch.batchId(), 10, food));
		assertEquals(before, state.save());
		assertStacks(stacks, food);
		assertEquals(2, batch.quantity());
		state.world().assertConservation();
	}

	@Test
	void futureForeignUnknownAndCustomizedStacksStayExactlyInPlace() {
		SettlementSimulationState state = stateAt(4);
		ItemStack ordinaryNamed = new ItemStack(Items.SWEET_BERRIES, 2);
		ordinaryNamed.set(DataComponents.CUSTOM_NAME, Component.literal("Legacy named food"));
		ItemStack named = marked(state, Items.SWEET_BERRIES, 3, 3);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Extra component"));
		ItemStack changedFood = marked(state, Items.SWEET_BERRIES, 3, 3);
		changedFood.remove(DataComponents.FOOD);
		ItemStack unknownData = new ItemStack(Items.SWEET_BERRIES, 2);
		CompoundTag unknown = new CompoundTag();
		unknown.putString("another_mod", "untouched");
		unknownData.set(DataComponents.CUSTOM_DATA, CustomData.of(unknown));
		SimpleContainer source = new SimpleContainer(
			marked(state, Items.SWEET_BERRIES, 2, 0),
			marked(state, Items.SWEET_BERRIES, 2, 5),
			SettlementFoodAge.stamp(new ItemStack(Items.SWEET_BERRIES, 2), UUID.randomUUID(), 3),
			alterAge(marked(state, Items.SWEET_BERRIES, 2, 3), age -> age.putInt("Format", 2)),
			alterAge(marked(state, Items.SWEET_BERRIES, 2, 3), age -> age.putLong("ExpiryDay", 100)),
			ordinaryNamed, named, changedFood, unknownData, new ItemStack(Items.BREAD, 2)
		);
		CompoundTag before = state.save();
		List<ItemStack> stacks = copyStacks(source);
		assertEquals(0, intake(source, state, ResourceType.WILD_FRUIT, 100));
		assertEquals(before, state.save());
		assertStacks(stacks, source);
		state.world().assertConservation();
	}

	@Test
	void rejectedNeighborsDoNotPreventEligibleFoodIntakeOrLoseTheirComponents() {
		SettlementSimulationState state = stateAt(2);
		ItemStack foreign = SettlementFoodAge.stamp(new ItemStack(Items.POTATO, 7), UUID.randomUUID(), 0);
		ItemStack future = marked(state, Items.POTATO, 8, 3);
		SimpleContainer source = new SimpleContainer(foreign, marked(state, Items.POTATO, 3, 0), future);
		assertEquals(3, intake(source, state, ResourceType.TUBERS, 100));
		assertStack(foreign, source.getItem(0));
		assertTrue(source.getItem(1).isEmpty());
		assertStack(future, source.getItem(2));
		assertEquals(0, state.world().batches().getFirst().harvestDay());
		state.world().assertConservation();
	}

	@Test
	void anotherSimulationOfSameSettlementCannotAcceptExportedFoodAtAnyRelativeDay() {
		SettlementSimulationState origin = stateAt(2);
		ResourceBatch batch = initial(origin, ResourceType.TUBERS, 4, 0);
		SimpleContainer food = new SimpleContainer(1);
		assertEquals(4, export(origin, batch.batchId(), 4, food));
		for (long day : List.of(0L, 2L, 5L)) {
			SettlementSimulationState other = stateAt(day);
			assertEquals(origin.campOwner(), other.campOwner());
			assertNotEquals(origin.foodClockId(), other.foodClockId());
			CompoundTag before = other.save();
			List<ItemStack> stacks = copyStacks(food);
			assertEquals(0, intake(food, other, ResourceType.TUBERS, 4));
			assertEquals(before, other.save());
			assertStacks(stacks, food);
			other.world().assertConservation();
		}
		assertEquals(4, intake(food, origin, ResourceType.TUBERS, 4));
		assertEquals(0, origin.world().batches().getFirst().harvestDay());
		origin.world().assertConservation();
	}

	@Test
	void itemAndSimulationReloadPreserveAgeAndRepeatedRoundTripsNeverCopyOrFreshenFood() {
		SettlementSimulationState state = stateAt(2);
		initial(state, ResourceType.WILD_FRUIT, 5, 0);
		SimpleContainer food = new SimpleContainer(1);
		UUID clock = state.foodClockId().orElseThrow();
		for (int round = 0; round < 3; round++) {
			ResourceBatch batch = state.world().batches().getFirst();
			assertEquals(5, export(state, batch.batchId(), 99, food));
			assertEquals(0, stock(state, ResourceType.WILD_FRUIT));
			ItemStack stored = food.getItem(0);
			ItemStack loadedStack = ItemStack.parse(registries, stored.save(registries)).orElseThrow();
			assertStack(stored, loadedStack);
			food.setItem(0, loadedStack);
			CompoundTag saved = state.save();
			state = SettlementSimulationState.load(SETTLEMENT, saved);
			assertEquals(saved, state.save());
			assertEquals(clock, state.foodClockId().orElseThrow());
			SettlementFoodAge.Stamp stamp = SettlementFoodAge.read(loadedStack).orElseThrow();
			assertEquals(0, stamp.harvestDay());
			assertEquals(3, stamp.expiryDay());
			assertEquals(5, intake(food, state, ResourceType.WILD_FRUIT, 99));
			assertEquals(0, intake(food, state, ResourceType.WILD_FRUIT, 99));
			assertTrue(food.isEmpty());
			assertEquals(5, stock(state, ResourceType.WILD_FRUIT));
			assertEquals(0, state.world().batches().getFirst().harvestDay());
			assertEquals(3, state.world().batches().getFirst().expiryDay());
			state.world().assertConservation();
		}
	}

	@Test
	void malformedPersistedClockDisablesBothHandoffsWithoutRepairingOrConsumingAnything() {
		SettlementSimulationState valid = stateAt(2);
		ResourceBatch batch = initial(valid, ResourceType.TUBERS, 4, 0);
		CompoundTag broken = valid.save();
		broken.putString("FoodClockId", "not a UUID");
		SettlementSimulationState state = SettlementSimulationState.load(SETTLEMENT, broken);
		assertTrue(state.foodClockId().isEmpty());
		SimpleContainer container = new SimpleContainer(new ItemStack(Items.POTATO, 5),
			marked(valid, Items.POTATO, 5, 0), ItemStack.EMPTY);
		CompoundTag before = state.save();
		List<ItemStack> stacks = copyStacks(container);
		assertEquals(0, intake(container, state, ResourceType.TUBERS, 10));
		assertEquals(0, export(state, batch.batchId(), 4, container));
		assertEquals(before, state.save());
		assertStacks(stacks, container);
		state.world().assertConservation();
	}

	@Test
	void wholeImportPlanChecksAllIdsBeforeRemovingEvenTheFirstStack() {
		SettlementSimulationState state = stateAt(1);
		CompoundTag saved = state.save();
		saved.putLong("NextBatchId", Long.MAX_VALUE - 1);
		SettlementSimulationState nearlyExhausted = SettlementSimulationState.load(SETTLEMENT, saved);
		SimpleContainer source = new SimpleContainer(marked(nearlyExhausted, Items.POTATO, 2, 0),
			marked(nearlyExhausted, Items.POTATO, 3, 1));
		List<ItemStack> stacks = copyStacks(source);
		CompoundTag before = nearlyExhausted.save();
		assertThrows(IllegalArgumentException.class, () -> intake(source, nearlyExhausted, ResourceType.TUBERS, 5));
		assertEquals(before, nearlyExhausted.save());
		assertStacks(stacks, source);
		assertEquals(2, intake(source, nearlyExhausted, ResourceType.TUBERS, 2));
		assertEquals("batch-" + (Long.MAX_VALUE - 1), nearlyExhausted.world().batches().getFirst().batchId());
		before = nearlyExhausted.save();
		stacks = copyStacks(source);
		assertThrows(IllegalArgumentException.class, () -> intake(source, nearlyExhausted, ResourceType.TUBERS, 3));
		assertEquals(before, nearlyExhausted.save());
		assertStacks(stacks, source);
		nearlyExhausted.world().assertConservation();
	}

	@Test
	void invalidRequestsForeignOwnersAndMaterialBatchesNeverMutateEitherStore() {
		SettlementSimulationState state = stateAt(1);
		ResourceBatch food = initial(state, ResourceType.TUBERS, 4, 0);
		ResourceBatch material = state.world().createInitialBatch(ResourceType.WOOD, 4, state.campOwner());
		state.world().registerCamp(new Camp("other", "Other camp"));
		ResourceBatch foreign = state.world().createInitialBatch(ResourceType.TUBERS, 4, StorageOwner.camp("other"));
		SimpleContainer container = new SimpleContainer(new ItemStack(Items.POTATO, 6), ItemStack.EMPTY);
		List<ItemStack> stacks = copyStacks(container);
		CompoundTag before = state.save();
		for (int requested : new int[] {0, -1, Integer.MIN_VALUE}) {
			assertThrows(IllegalArgumentException.class, () -> intake(container, state, ResourceType.TUBERS, requested));
			assertThrows(IllegalArgumentException.class, () -> export(state, food.batchId(), requested, container));
		}
		assertThrows(IllegalArgumentException.class, () -> intake(container, state, null, 1));
		assertThrows(IllegalArgumentException.class, () -> intake(container, state, ResourceType.WOOD, 1));
		for (String batchId : List.of("missing", "", foreign.batchId(), material.batchId())) {
			assertThrows(IllegalArgumentException.class, () -> export(state, batchId, 1, container));
		}
		assertEquals(before, state.save());
		assertStacks(stacks, container);
		state.world().assertConservation();
	}

	@Test
	void customExpiryAndFutureOrNonstandardNutritionBatchesCannotBeReencoded() {
		for (long expiry : List.of(2L, 100L)) {
			SettlementSimulationState state = stateAt(1);
			ResourceBatch custom = state.world().createInitialBatch(ResourceType.WILD_FRUIT, 4, 50,
				state.campOwner(), 0, expiry);
			assertExportUnchanged(state, custom.batchId());
		}
		SettlementSimulationState valid = stateAt(1);
		ResourceBatch batch = initial(valid, ResourceType.WILD_FRUIT, 4, 0);
		CompoundTag future = valid.save();
		future.getList("Batches", ListTag.TAG_COMPOUND).getCompound(0).putLong("HarvestDay", 2);
		future.getList("Batches", ListTag.TAG_COMPOUND).getCompound(0).putLong("ExpiryDay", 5);
		assertExportUnchanged(SettlementSimulationState.load(SETTLEMENT, future), batch.batchId());
		CompoundTag nutrition = valid.save();
		nutrition.getList("Batches", ListTag.TAG_COMPOUND).getCompound(0).putLong("NutritionPerUnit", 1);
		nutrition.getList("Events", ListTag.TAG_COMPOUND).getCompound(0)
			.getList("Outputs", ListTag.TAG_COMPOUND).getCompound(0).putLong("NutritionPerUnit", 1);
		assertExportUnchanged(SettlementSimulationState.load(SETTLEMENT, nutrition), batch.batchId());
	}

	@Test
	void missingExportCauseIsPreflightedBeforeTouchingExistingLookalikes() {
		SettlementSimulationState valid = stateAt(1);
		ResourceBatch batch = initial(valid, ResourceType.WILD_FRUIT, 4, 0);
		CompoundTag broken = valid.save();
		broken.getList("Batches", ListTag.TAG_COMPOUND).getCompound(0).putLong("LastEventId", 999);
		SettlementSimulationState state = SettlementSimulationState.load(SETTLEMENT, broken);
		SimpleContainer target = new SimpleContainer(marked(state, Items.SWEET_BERRIES, 60, 0));
		CompoundTag before = state.save();
		List<ItemStack> stacks = copyStacks(target);
		assertThrows(IllegalArgumentException.class, () -> export(state, batch.batchId(), 4, target));
		assertEquals(before, state.save());
		assertStacks(stacks, target);
		state.world().assertConservation();
	}

	@Test
	void placementRulesAndSmallStackLimitsAreRespectedBeforeExactDebit() {
		SettlementSimulationState state = stateAt(2);
		ResourceBatch batch = initial(state, ResourceType.GAME, 8, 1);
		SimpleContainer target = new SimpleContainer(2) {
			@Override public boolean canPlaceItem(int slot, ItemStack stack) { return slot == 1; }
			@Override public int getMaxStackSize() { return 3; }
		};
		target.setItem(1, marked(state, Items.BEEF, 1, 1));
		assertEquals(2, export(state, batch.batchId(), 8, target));
		assertTrue(target.getItem(0).isEmpty());
		assertEquals(3, target.getItem(1).getCount());
		assertEquals(6, batch.quantity());
		CompoundTag before = state.save();
		assertEquals(0, export(state, batch.batchId(), 8, target));
		assertEquals(before, state.save());
		state.world().assertConservation();
	}

	@Test
	void saturatedExpiryAtLargestObservedDaySurvivesPhysicalRoundTrip() {
		SettlementSimulationState state = stateAt(Long.MAX_VALUE);
		ResourceBatch batch = state.world().createInitialBatch(ResourceType.TUBERS, 3, state.campOwner());
		SimpleContainer container = new SimpleContainer(1);
		assertEquals(3, export(state, batch.batchId(), 3, container));
		SettlementFoodAge.Stamp stamp = SettlementFoodAge.read(container.getItem(0)).orElseThrow();
		assertEquals(Long.MAX_VALUE, stamp.harvestDay());
		assertEquals(Long.MAX_VALUE, stamp.expiryDay());
		assertEquals(3, intake(container, state, ResourceType.TUBERS, 3));
		assertTrue(container.isEmpty());
		assertEquals(Long.MAX_VALUE, state.world().batches().getFirst().expiryDay());
		assertEquals(3, stock(state, ResourceType.TUBERS));
		state.world().assertConservation();
	}

	private static void assertExportUnchanged(SettlementSimulationState state, String batchId) {
		SimpleContainer target = new SimpleContainer(new ItemStack(Items.SWEET_BERRIES, 12), ItemStack.EMPTY);
		CompoundTag before = state.save();
		List<ItemStack> stacks = copyStacks(target);
		assertEquals(0, export(state, batchId, 100, target));
		assertEquals(before, state.save());
		assertStacks(stacks, target);
		state.world().assertConservation();
	}

	private static SettlementSimulationState stateAt(long day) {
		SettlementSimulationState state = SettlementSimulationState.create(SETTLEMENT);
		state.world().restoreState(day, 1, List.of(), List.of());
		return state;
	}

	private static ResourceBatch initial(SettlementSimulationState state, ResourceType type, long quantity, long harvestDay) {
		return state.world().createInitialBatch(type, quantity, 50, state.campOwner(), harvestDay,
			harvestDay + type.shelfLifeDays());
	}

	private static ItemStack marked(SettlementSimulationState state, Item item, int count, long harvestDay) {
		return SettlementFoodAge.stamp(new ItemStack(item, count), state.foodClockId().orElseThrow(), harvestDay);
	}

	private static ItemStack alterAge(ItemStack stack, Consumer<CompoundTag> change) {
		CompoundTag data = stack.get(DataComponents.CUSTOM_DATA).copyTag();
		change.accept(data.getCompound("coopcraft_food_age"));
		ItemStack result = stack.copy();
		result.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
		return result;
	}

	private static List<ResourceType> foodTypes() {
		return List.of(ResourceType.WILD_FRUIT, ResourceType.TUBERS, ResourceType.FISH, ResourceType.GAME);
	}

	private static Item item(ResourceType type) {
		return switch (type) {
			case WILD_FRUIT -> Items.SWEET_BERRIES;
			case TUBERS -> Items.POTATO;
			case FISH -> Items.COD;
			case GAME -> Items.BEEF;
			default -> throw new IllegalArgumentException("Not food");
		};
	}

	private static int intake(Container container, SettlementSimulationState state, ResourceType type, int requested) {
		return MinecraftFoodAgeAdapter.importToSimulation(container, state, type, requested);
	}

	private static int export(SettlementSimulationState state, String batchId, int requested, Container container) {
		return MinecraftFoodAgeAdapter.exportToContainer(state, batchId, requested, container);
	}

	private static long stock(SettlementSimulationState state, ResourceType type) {
		return state.world().inventoryTotals().get(type);
	}

	private static List<ItemStack> copyStacks(Container container) {
		List<ItemStack> result = new ArrayList<>();
		for (int slot = 0; slot < container.getContainerSize(); slot++) result.add(container.getItem(slot).copy());
		return result;
	}

	private static void assertStacks(List<ItemStack> expected, Container container) {
		assertEquals(expected.size(), container.getContainerSize());
		for (int slot = 0; slot < expected.size(); slot++) assertStack(expected.get(slot), container.getItem(slot));
	}

	private static void assertStack(ItemStack expected, ItemStack actual) {
		assertEquals(expected.isEmpty(), actual.isEmpty());
		assertEquals(expected.getCount(), actual.getCount());
		if (!expected.isEmpty()) assertTrue(ItemStack.isSameItemSameComponents(expected, actual));
	}
}
