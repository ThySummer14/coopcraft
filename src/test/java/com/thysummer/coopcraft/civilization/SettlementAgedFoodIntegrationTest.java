package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit service boundaries and standard containers, without a running game. */
class SettlementAgedFoodIntegrationTest {
	private static HolderLookup.Provider registries;
	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}
	static class Fixture {
		final UUID actor = new UUID(59, 1);
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement camp = data.getOrCreate(BlockPos.ZERO, "Age", 0);
		final SettlementSimulationState state = data.getOrCreateSimulation(camp);
		Fixture() { data.registerMember(camp, actor, 0); state.mapResident(actor, false, 50); }
		ItemStack marked(int count) { return SettlementFoodAge.stamp(new ItemStack(Items.POTATO, count), state.foodClockId().orElseThrow(), 0); }
	}

	@Test void commandRoundTripSurvivesReloadAndNeverRefreshesAge() {
		Fixture f = new Fixture(); var world = f.state.world();
		var batch = world.importExternalBatch(ResourceType.WILD_FRUIT, 5, f.state.campOwner());
		world.advanceDay(); world.advanceDay();
		SimpleContainer chest = new SimpleContainer(3);
		assertEquals(3, SettlementSimulationService.exportToStockpile(f.data, f.camp, batch.batchId(), 3, chest));
		assertEquals(2, batch.quantity()); assertEquals(3, chest.countItem(Items.SWEET_BERRIES));
		ItemStack preserved = ItemStack.parseOptional(registries, (CompoundTag) chest.getItem(0).save(registries));
		var saved = f.data.save(new CompoundTag(), registries);
		var loaded = SettlementSavedData.load(saved, registries);
		var camp = loaded.find(BlockPos.ZERO).orElseThrow(); var state = loaded.simulationOf(camp).orElseThrow();
		assertEquals(f.state.foodClockId(), state.foodClockId());
		chest.setItem(0, preserved); state.world().advanceDay();
		assertEquals(2, SettlementSimulationService.importFromStockpile(loaded, camp, chest, ResourceType.WILD_FRUIT, 2));
		assertEquals(1, chest.countItem(Items.SWEET_BERRIES));
		assertTrue(state.world().batches().stream().allMatch(b -> b.harvestDay() == 0 && b.expiryDay() == 3));
		assertEquals(4L, state.world().inventoryTotals().get(ResourceType.WILD_FRUIT));
		state.world().advanceDay(); var before = loaded.save(new CompoundTag(), registries);
		assertEquals(0, SettlementSimulationService.importFromStockpile(loaded, camp, chest, ResourceType.WILD_FRUIT, 1));
		assertEquals(before, loaded.save(new CompoundTag(), registries));
		assertEquals(1, chest.countItem(Items.SWEET_BERRIES)); state.world().assertConservation();
	}

	@Test void legacyClockMigrationIsSavedOnceAndIndependentEvenWithSameCampId() {
		Fixture f = new Fixture(); var tag = f.data.save(new CompoundTag(), registries);
		tag.getList("Simulations", Tag.TAG_COMPOUND).getCompound(0).remove("FoodClockId");
		var loaded = SettlementSavedData.load(tag, registries);
		assertTrue(loaded.isDirty());
		var state = loaded.simulationOf(loaded.find(BlockPos.ZERO).orElseThrow()).orElseThrow();
		var reloaded = SettlementSavedData.load(loaded.save(new CompoundTag(), registries), registries);
		assertEquals(state.foodClockId(), reloaded.simulationOf(reloaded.find(BlockPos.ZERO).orElseThrow()).orElseThrow().foodClockId());
		Fixture other = new Fixture(); assertEquals(f.camp.id(), other.camp.id());
		assertNotEquals(f.state.foodClockId(), other.state.foodClockId());
		SimpleContainer cargo = new SimpleContainer(f.marked(4));
		assertEquals(0, SettlementSimulationService.importFromStockpile(other.data, other.camp, cargo, ResourceType.TUBERS, 4));
		assertEquals(4, cargo.countItem(Items.POTATO)); assertTrue(other.state.world().batches().isEmpty());
	}

	@Test void ordinaryModeDoesNotEatCountDistributeOrRemotelyDepositMarkedStock() {
		Fixture f = new Fixture(); SimpleContainer bag = new SimpleContainer(f.marked(64));
		SimpleContainer store = new SimpleContainer(3);
		assertEquals(0, SettlementStockpile.communalFoodPoints(bag));
		assertFalse(SettlementNeeds.hasFood(bag, null)); assertEquals(0, SettlementNeeds.consumeOneFood(bag));
		assertFalse(SettlementFoodCargo.hasSurplus(f.data, f.camp, f.actor, bag));
		assertEquals(0, SettlementStockpile.depositExcessFood(store, bag));
		assertEquals(0, SettlementStockpile.depositItem(store, bag, Items.POTATO));
		assertEquals(0, SettlementStockpile.distributeBasicFood(bag, store));
		assertEquals(64, bag.countItem(Items.POTATO)); assertTrue(store.isEmpty());
		store.setItem(0, new ItemStack(Items.POTATO, 2));
		assertEquals(1, SettlementNeeds.consumeOneFood(store)); assertEquals(1, store.countItem(Items.POTATO));
	}

	@Test void unknownMarkerIsPreservedAndNeverFallsBackToOrdinaryRation() {
		Fixture f = new Fixture(); ItemStack unknown = new ItemStack(Items.SWEET_BERRIES, 4);
		CompoundTag marker = new CompoundTag(); marker.putString("coopcraft_food_age", "future-format");
		unknown.set(DataComponents.CUSTOM_DATA, CustomData.of(marker));
		SimpleContainer bag = new SimpleContainer(unknown.copy());
		assertEquals(0, SettlementFood.points(unknown)); assertFalse(SettlementFoodCargo.isFood(unknown));
		assertEquals(0, SettlementNeeds.consumeOneFood(bag));
		assertEquals(0, SettlementSimulationService.importFromStockpile(f.data, f.camp, bag, ResourceType.WILD_FRUIT, 4));
		assertTrue(ItemStack.matches(unknown, bag.getItem(0)));
	}

	@Test void markedDeathCargoRetainsKnownAndUnknownComponentsInEitherMode() {
		for (boolean survival : List.of(false, true)) {
			Fixture f = new Fixture(); f.state.setSurvivalEnabled(survival, 0);
			ItemStack known = f.marked(3), unknown = new ItemStack(Items.COD, 2);
			CompoundTag marker = new CompoundTag(); marker.putString("coopcraft_food_age", "future");
			unknown.set(DataComponents.CUSTOM_DATA, CustomData.of(marker));
			SimpleContainer bag = new SimpleContainer(8); bag.setItem(0, known.copy()); bag.setItem(1, unknown.copy());
			var claims = SettlementMembershipClaims.resolve(List.of(new SettlementMembershipClaims.Source(Level.OVERWORLD, f.data)), Level.OVERWORLD, f.actor);
			var recovery = new SettlementCargoRecovery();
			assertEquals(5, recovery.captureDeath(claims, Level.OVERWORLD, f.actor, BlockPos.ZERO, "Resident", () -> bag));
			assertTrue(bag.isEmpty()); assertEquals(0, recovery.captureDeath(claims, Level.OVERWORLD, f.actor, BlockPos.ZERO, "Resident", () -> bag));
			var restored = SettlementCargoRecovery.load(recovery.save(new CompoundTag(), registries), registries);
			SimpleContainer receiver = new SimpleContainer(1);
			assertEquals(3, restored.recover(f.actor, receiver, () -> true).moved());
			assertTrue(ItemStack.matches(known, receiver.getItem(0)));
			assertEquals(0, restored.recover(f.actor, receiver, () -> true).moved());
			receiver.clearContent(); assertEquals(2, restored.recover(f.actor, receiver, () -> true).moved());
			assertTrue(ItemStack.matches(unknown, receiver.getItem(0))); assertTrue(restored.inspect(f.actor).isEmpty());
		}
	}

	@Test void modeChangesKeepAgeAndDoNotMakeExpiredStockFresh() {
		Fixture f = new Fixture(); SimpleContainer bag = new SimpleContainer(f.marked(3));
		var clock = f.state.foodClockId();
		for (int i = 0; i < 9; i++) f.state.world().advanceDay();
		f.state.setSurvivalEnabled(true, 0); f.state.setProductionEnabled(true);
		f.state.setSurvivalEnabled(false, 2000); f.state.setProductionEnabled(false);
		f.state.setSurvivalEnabled(true, 3000); assertEquals(clock, f.state.foodClockId());
		assertEquals(0, SettlementSimulationService.importFromStockpile(f.data, f.camp, bag, ResourceType.TUBERS, 3));
		assertEquals(3, bag.countItem(Items.POTATO)); assertEquals(0, SettlementFoodAge.read(bag.getItem(0)).orElseThrow().harvestDay());
	}

	@Test void bareWorldFoodExportFailsBeforeEitherInventoryChangesAndMaterialsStillWork() {
		Fixture f = new Fixture(); var world = f.state.world();
		var food = world.importExternalBatch(ResourceType.TUBERS, 2, f.state.campOwner());
		SimpleContainer box = new SimpleContainer(2); var before = f.data.save(new CompoundTag(), registries);
		assertThrows(IllegalArgumentException.class, () -> MinecraftStockpileAdapter.exportToContainer(world, f.state.campOwner(), food.batchId(), 2, box));
		assertEquals(before, f.data.save(new CompoundTag(), registries)); assertTrue(box.isEmpty());
		var wood = world.importExternalBatch(ResourceType.WOOD, 3, f.state.campOwner());
		assertEquals(3, SettlementSimulationService.exportToStockpile(f.data, f.camp, wood.batchId(), 3, box));
		assertEquals(3, box.countItem(Items.OAK_LOG)); assertEquals(2, food.quantity()); world.assertConservation();
	}

	@Test void commandSkipsExpiredAndNonfittingAgesBeforeAcceptingLaterMatchingFood() {
		Fixture f = new Fixture(); var world = f.state.world();
		var expired = world.importExternalBatch(ResourceType.WILD_FRUIT, 3, f.state.campOwner());
		world.advanceDay(); world.advanceDay();
		var older = world.importExternalBatch(ResourceType.WILD_FRUIT, 3, f.state.campOwner());
		world.advanceDay();
		var newer = world.importExternalBatch(ResourceType.WILD_FRUIT, 3, f.state.campOwner());
		world.advanceDay();
		SimpleContainer box = new SimpleContainer(SettlementFoodAge.stamp(new ItemStack(Items.SWEET_BERRIES, 62), f.state.foodClockId().orElseThrow(), 3));
		assertEquals(2, SettlementCommands.export(f.data, f.camp, box, ResourceType.WILD_FRUIT, 9));
		assertEquals(64, box.countItem(Items.SWEET_BERRIES));
		assertEquals(3, expired.quantity()); assertEquals(3, older.quantity()); assertEquals(1, newer.quantity());
		assertEquals(0, SettlementCommands.export(f.data, f.camp, box, ResourceType.WILD_FRUIT, 9));
		world.assertConservation();
	}

	@Test void productionImportSkipsMarkedStockInsteadOfRefreshingItsAge() {
		Fixture f = new Fixture(); f.state.setSurvivalEnabled(true, 0); f.state.setProductionEnabled(true);
		f.state.world().advanceDay(); f.state.world().advanceDay();
		ItemStack marked = f.marked(3); SimpleContainer bag = new SimpleContainer(marked.copy(), new ItemStack(Items.POTATO, 3));
		assertEquals(2, SettlementSimulationService.recordProduction(f.data, f.camp, f.actor, bag, Items.POTATO, 2, "harvest_crop"));
		assertTrue(ItemStack.matches(marked, bag.getItem(0))); assertEquals(1, bag.getItem(1).getCount());
		var batch = f.state.world().batches().getFirst();
		assertEquals(2, batch.quantity()); assertEquals(2, batch.harvestDay());
		assertEquals(0, SettlementFoodAge.read(bag.getItem(0)).orElseThrow().harvestDay());
		f.state.world().assertConservation();
	}

	@Test void giftsRejectMarkedFoodBeforeDebitingOrRecordingANewBatch() {
		for (boolean survival : List.of(false, true)) {
			Fixture f = new Fixture(); f.state.setSurvivalEnabled(survival, 0);
			ItemStack marked = f.marked(3); SimpleContainer hand = new SimpleContainer(marked.copy());
			var before = f.data.save(new CompoundTag(), registries);
			var result = VillagerGiftService.offer(f.data, f.camp, f.actor, false, "player:test", "Test",
				hand, 0, () -> 0, marked, new SimpleContainer(2));
			assertFalse(result.accepted()); assertEquals("unsupported_food", result.reason());
			assertTrue(ItemStack.matches(marked, hand.getItem(0)));
			assertEquals(before, f.data.save(new CompoundTag(), registries));
		}
	}
}
