package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real inventories and service boundaries; does not claim live navigation or loot-table coverage. */
class SettlementFoodCargoTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	static class Fixture {
		final UUID actor;
		final SettlementSavedData data = new SettlementSavedData();
		final Settlement camp = data.getOrCreate(BlockPos.ZERO, "Food cargo", 0);
		final SimpleContainer bag = new SimpleContainer(8), store = new SimpleContainer(4);
		Fixture() { this(new UUID(712, 1)); }
		Fixture(UUID actor) {
			this.actor = actor; data.registerMember(camp, actor, 0); data.setStockpile(camp, BlockPos.ZERO.east());
			assertEquals(SettlementForagingAreas.Status.CREATED,
				data.permitForagingArea(camp, actor, BlockPos.ZERO, 1).status());
		}
		int retained() { return SettlementFoodCargo.retainedFoodPoints(data, camp, actor); }
		boolean surplus() { return SettlementFoodCargo.hasSurplus(data, camp, actor, bag); }
		SettlementCargoDelivery.Ticket prepare() { return SettlementCargoDelivery.prepare(data, camp, actor, bag, () -> true); }
		SettlementCargoDelivery.Result finish(SettlementCargoDelivery.Ticket ticket) {
			return SettlementCargoDelivery.finish(ticket, data, camp, actor, bag, store, () -> true);
		}
		void tick(long cycle) {
			SettlementStockpile.tick(data, camp, Math.floorMod(-camp.id().getLeastSignificantBits(), 40L) + cycle * 40L,
				() -> store, id -> bag);
		}
	}

	@Test void berryHarvestStaysPhysicalUntilArrivalAndKeepsPersonalRation() {
		Fixture f = new Fixture();
		var ripe = Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3);
		AtomicInteger regrowth = new AtomicInteger();
		assertEquals(64, SettlementForaging.harvest(f.data, f.camp, f.actor, BlockPos.ZERO, f.bag, () -> true, () -> true, () -> true,
			() -> ripe, () -> List.of(new ItemStack(Items.SWEET_BERRIES, 64)), state -> { regrowth.incrementAndGet(); return true; }));
		assertEquals(1, regrowth.get()); assertEquals(64, f.bag.countItem(Items.SWEET_BERRIES));
		assertTrue(f.store.isEmpty()); assertTrue(f.data.simulationOf(f.camp).isEmpty()); assertTrue(f.surplus());
		for (int cycle = 0; cycle < 10; cycle++) f.tick(cycle);
		assertEquals(64, f.bag.countItem(Items.SWEET_BERRIES)); assertTrue(f.store.isEmpty());
		assertEquals(64 - f.retained(), f.finish(f.prepare()).moved());
		assertEquals(f.retained(), f.bag.countItem(Items.SWEET_BERRIES)); assertFalse(f.surplus());
	}

	@Test void potatoSeedReservationAndProductionKeepNetHarvestForPhysicalDelivery() {
		Fixture f = new Fixture();
		var output = SettlementCropHarvest.outputAfterSeed(List.of(new ItemStack(Items.POTATO, 64), new ItemStack(Items.POISONOUS_POTATO))).orElseThrow();
		assertTrue(SettlementHarvestCommit.commit(f.bag, output, () -> true));
		assertEquals(0, SettlementSimulationService.recordProduction(f.data, f.camp, f.actor, f.bag, Items.POTATO, 63, "harvest_crop"));
		assertEquals(63, f.bag.countItem(Items.POTATO)); assertTrue(f.store.isEmpty());
		assertEquals(63 - f.retained(), f.finish(f.prepare()).moved());
		assertEquals(f.retained(), f.bag.countItem(Items.POTATO));
		assertEquals(1, f.bag.countItem(Items.POISONOUS_POTATO));
	}

	@Test void arrivalUsesCurrentSurplusAfterEatingAndCountsMixedHighValueFoodExactly() {
		Fixture f = new Fixture();
		f.bag.setItem(0, new ItemStack(Items.SWEET_BERRIES, f.retained() + 5));
		var eaten = f.prepare(); f.bag.getItem(0).shrink(5);
		assertFalse(f.surplus()); assertEquals(0, f.finish(eaten).moved()); assertTrue(f.store.isEmpty());
		f.bag.clearContent();
		int breadPoints = SettlementFood.points(Items.BREAD);
		int breads = f.retained() / breadPoints;
		f.bag.setItem(0, new ItemStack(Items.BREAD, breads));
		f.bag.setItem(1, new ItemStack(Items.POTATO, f.retained() - breads * breadPoints + 9));
		var reduced = f.prepare(); f.bag.getItem(1).shrink(5);
		assertTrue(f.surplus()); assertEquals(4, f.finish(reduced).moved());
		assertEquals(f.retained(), SettlementStockpile.communalFoodPoints(f.bag));
		assertEquals(breads, f.bag.countItem(Items.BREAD)); assertFalse(f.surplus());
	}

	@Test void partialAndFullStorePreserveComponentsAndOnlyDebitAcceptedItems() {
		Fixture f = new Fixture();
		ItemStack named = new ItemStack(Items.POTATO, 64);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Harvest with components"));
		CompoundTag marker = new CompoundTag(); marker.putString("harvest", "private");
		named.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(marker));
		f.bag.setItem(0, named.copy()); f.store.setItem(0, named.copyWithCount(62));
		for (int slot = 1; slot < 4; slot++) f.store.setItem(slot, new ItemStack(Items.DIRT, 64));
		var ticket = f.prepare(); assertEquals(2, f.finish(ticket).moved());
		assertEquals(62, f.bag.countItem(Items.POTATO)); assertTrue(f.surplus());
		assertTrue(ItemStack.isSameItemSameComponents(named, f.bag.getItem(0)));
		assertTrue(ItemStack.isSameItemSameComponents(named, f.store.getItem(0)));
		assertEquals(SettlementCargoDelivery.Status.INVALID, f.finish(ticket).status());
		assertEquals(SettlementCargoDelivery.Status.NO_CAPACITY, f.finish(f.prepare()).status());
		f.store.setItem(1, ItemStack.EMPTY);
		assertEquals(62 - f.retained(), f.finish(f.prepare()).moved()); assertFalse(f.surplus());
		assertTrue(ItemStack.isSameItemSameComponents(named, f.store.getItem(1)));
	}

	@Test void allFoodCountsTowardRetentionButOnlyBerriesAndPotatoesAreCargo() {
		Fixture f = new Fixture(); f.bag.setItem(0, new ItemStack(Items.BREAD, 16));
		assertFalse(f.surplus());
		f.bag.setItem(1, new ItemStack(Items.POTATO, 3)); f.bag.setItem(2, new ItemStack(Items.SWEET_BERRIES, 4));
		f.bag.setItem(3, new ItemStack(Items.STONE_PICKAXE)); f.bag.setItem(4, new ItemStack(Items.COBBLESTONE, 5));
		assertTrue(f.surplus()); assertEquals(12, f.finish(f.prepare()).moved());
		assertEquals(16, f.bag.countItem(Items.BREAD)); assertEquals(1, f.bag.countItem(Items.STONE_PICKAXE));
		assertEquals(5, f.store.countItem(Items.COBBLESTONE)); assertFalse(f.surplus());
		assertFalse(SettlementCargo.hasCargo(f.bag));
	}

	@Test void personalityRetentionAndDistributedRationsNeverCreateReturnLoop() {
		for (int id = 0; id < 30; id++) {
			Fixture f = new Fixture(new UUID(922, id));
			assertEquals(Math.max(12, f.data.personalityOf(f.camp, f.actor).foodRetentionPoints(12)), f.retained());
			for (var food : List.of(Items.POTATO, Items.SWEET_BERRIES)) {
				f.bag.clearContent(); f.store.clearContent(); f.store.setItem(0, new ItemStack(food, 64));
				f.tick(0); int ration = f.bag.countItem(food);
				assertTrue(ration >= 12); assertFalse(f.surplus()); assertEquals(0, f.finish(f.prepare()).moved());
				for (int round = 1; round <= 5; round++) f.tick(round);
				assertEquals(ration, f.bag.countItem(food)); assertEquals(64 - ration, f.store.countItem(food));
				f.bag.setItem(0, new ItemStack(food, f.retained() + 1));
				assertTrue(f.surplus()); assertEquals(1, f.finish(f.prepare()).moved()); assertFalse(f.surplus());
			}
		}
	}

	@Test void noArrivalNoTransferAndRejectedContainerDoesNotConsumeFood() {
		Fixture f = new Fixture(); f.bag.setItem(0, new ItemStack(Items.POTATO, 64));
		var ticket = f.prepare();
		assertEquals(SettlementCargoDelivery.Status.NOT_AT_STORE,
			SettlementCargoDelivery.finish(ticket, f.data, f.camp, f.actor, f.bag, f.store, () -> false).status());
		assertEquals(0, f.finish(ticket).moved()); assertEquals(64, f.bag.countItem(Items.POTATO));
		SimpleContainer reject = new SimpleContainer(2) {
			@Override public boolean canPlaceItem(int slot, ItemStack stack) { return false; }
		};
		assertEquals(0, SettlementCargoDelivery.finish(f.prepare(), f.data, f.camp, f.actor, f.bag, reject, () -> true).moved());
		assertEquals(0, SettlementCargoDelivery.finish(f.prepare(), f.data, f.camp, f.actor, f.bag, f.bag, () -> true).moved());
		assertEquals(64, f.bag.countItem(Items.POTATO)); assertTrue(f.store.isEmpty());
	}

	@Test void survivalModeChangesInvalidateEvenMaterialOnlyTicketsIncludingRoundTrips() {
		for (boolean roundTrip : List.of(false, true)) {
			Fixture f = new Fixture(); var state = f.data.getOrCreateSimulation(f.camp);
			f.bag.setItem(0, new ItemStack(Items.COBBLESTONE, 3));
			f.bag.setItem(1, new ItemStack(Items.POTATO, 64));
			var ticket = f.prepare(); state.setSurvivalEnabled(true, 0);
			if (roundTrip) state.setSurvivalEnabled(false, 0);
			assertFalse(SettlementCargoDelivery.valid(ticket, f.data, f.camp, f.actor, f.bag));
			assertEquals(SettlementCargoDelivery.Status.INVALID, f.finish(ticket).status()); assertTrue(f.store.isEmpty());
			assertEquals(roundTrip ? 3 + 64 - f.retained() : 3, f.finish(f.prepare()).moved());
			assertEquals(roundTrip ? f.retained() : 64, f.bag.countItem(Items.POTATO));
		}
	}

	@Test void arrivalAndIdentityCallbacksAreRevalidatedBeforeAnyTransfer() {
		for (boolean identityCallback : List.of(false, true)) {
			Fixture f = new Fixture(); var state = f.data.getOrCreateSimulation(f.camp);
			f.bag.setItem(0, new ItemStack(Items.POTATO, 64));
			AtomicBoolean mutate = new AtomicBoolean(false);
			var ticket = SettlementCargoDelivery.prepare(f.data, f.camp, f.actor, f.bag, () -> {
				if (identityCallback && mutate.get()) state.setSurvivalEnabled(true, 0);
				return true;
			});
			mutate.set(true);
			assertEquals(SettlementCargoDelivery.Status.INVALID,
				SettlementCargoDelivery.finish(ticket, f.data, f.camp, f.actor, f.bag, f.store, () -> {
					if (!identityCallback) state.setSurvivalEnabled(true, 0);
					return true;
				}).status());
			assertEquals(64, f.bag.countItem(Items.POTATO)); assertTrue(f.store.isEmpty());
		}
	}

	@Test void reloadInvalidatesOldTicketButNewDeliveryUsesCurrentMemberAndRealStacks() {
		Fixture f = new Fixture(); f.bag.setItem(0, new ItemStack(Items.POTATO, 64)); var old = f.prepare();
		SettlementSavedData data = SettlementSavedData.load(f.data.save(new CompoundTag(), null), null);
		Settlement camp = data.find(BlockPos.ZERO).orElseThrow();
		assertEquals(SettlementCargoDelivery.Status.INVALID,
			SettlementCargoDelivery.finish(old, data, camp, f.actor, f.bag, f.store, () -> true).status());
		var fresh = SettlementCargoDelivery.prepare(data, camp, f.actor, f.bag, () -> true);
		assertEquals(64 - f.retained(), SettlementCargoDelivery.finish(fresh, data, camp, f.actor, f.bag, f.store, () -> true).moved());
		data.removeMember(f.actor);
		assertFalse(SettlementFoodCargo.hasSurplus(data, camp, f.actor, f.bag));
		assertEquals(Integer.MAX_VALUE, SettlementFoodCargo.retainedFoodPoints(data, camp, f.actor));
		assertFalse(SettlementFoodCargo.hasSurplus(null, camp, f.actor, f.bag));
	}

	@Test void survivalBerriesRequireAgedArrivalWhileLegacyProductionImportRemainsExplicit() {
		Fixture f = new Fixture(); var state = f.data.getOrCreateSimulation(f.camp);
		state.mapResident(f.actor, false, 0); state.setSurvivalEnabled(true, 0); state.setProductionEnabled(true);
		var ripe = Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3);
		assertEquals(4, SettlementForaging.harvest(f.data, f.camp, f.actor, BlockPos.ZERO, f.bag, () -> true, () -> true, () -> true,
			() -> ripe, () -> List.of(new ItemStack(Items.SWEET_BERRIES, 4)), block -> true));
		long harvestDay = state.world().day();
		assertEquals(4, f.bag.countItem(Items.SWEET_BERRIES));
		assertTrue(SettlementFoodAge.isMarked(f.bag.getItem(0)));
		assertEquals(harvestDay, SettlementFoodAge.read(f.bag.getItem(0)).orElseThrow().harvestDay());
		assertFalse(f.surplus(), "Survival cargo must not use ordinary surplus/ration rules");
		assertEquals(0L, state.world().inventoryTotals().getOrDefault(ResourceType.WILD_FRUIT, 0L));
		f.tick(0);
		assertEquals(4, f.bag.countItem(Items.SWEET_BERRIES));
		assertTrue(f.store.isEmpty());
		assertEquals(4, f.finish(f.prepare()).moved());
		assertEquals(0, f.bag.countItem(Items.SWEET_BERRIES));
		assertTrue(f.store.isEmpty(), "Accepted survival cargo must not leave a second physical copy");
		var fruit = state.world().batches().stream().filter(batch -> batch.resourceType() == ResourceType.WILD_FRUIT).findFirst().orElseThrow();
		assertEquals(harvestDay, fruit.harvestDay()); assertEquals(harvestDay + 3, fruit.expiryDay());
		f.bag.setItem(0, new ItemStack(Items.POTATO, 5));
		assertEquals(5, SettlementSimulationService.recordProduction(f.data, f.camp, f.actor, f.bag, Items.POTATO, 5, "harvest_crop"));
		assertEquals(5L, state.world().inventoryTotals().getOrDefault(ResourceType.TUBERS, 0L));
		var tubers = state.world().batches().stream().filter(batch -> batch.resourceType() == ResourceType.TUBERS).findFirst().orElseThrow();
		assertEquals(harvestDay, tubers.harvestDay()); assertEquals(harvestDay + 8, tubers.expiryDay());
		for (int day = 0; day < 3; day++) state.world().advanceDay();
		assertEquals(0, state.world().spoilExpired());
		state.world().advanceDay();
		assertEquals(1, state.world().spoilExpired());
		assertEquals(0L, state.world().inventoryTotals().getOrDefault(ResourceType.WILD_FRUIT, 0L));
		assertEquals(5L, state.world().inventoryTotals().getOrDefault(ResourceType.TUBERS, 0L));
		for (int day = 4; day < 8; day++) state.world().advanceDay();
		assertEquals(0, state.world().spoilExpired());
		state.world().advanceDay();
		assertEquals(1, state.world().spoilExpired());
		assertEquals(0L, state.world().inventoryTotals().getOrDefault(ResourceType.TUBERS, 0L));
		f.bag.setItem(0, new ItemStack(Items.POTATO, 64)); assertFalse(f.surplus());
		assertEquals(0, f.finish(f.prepare()).moved()); f.tick(0);
		assertEquals(64, f.bag.countItem(Items.POTATO)); assertTrue(f.store.isEmpty()); state.world().assertConservation();
	}

	@Test void newlyCreatedSimulationAndLeavingSurvivalInvalidatePendingTicket() {
		for (boolean initiallySurvival : List.of(false, true)) {
			Fixture f = new Fixture();
			if (initiallySurvival) f.data.getOrCreateSimulation(f.camp).setSurvivalEnabled(true, 0);
			f.bag.setItem(0, new ItemStack(Items.STICK, 4));
			var ticket = f.prepare();
			var state = f.data.getOrCreateSimulation(f.camp);
			if (!initiallySurvival) state.setSurvivalEnabled(true, 0);
			state.setSurvivalEnabled(false, 0);
			assertEquals(SettlementCargoDelivery.Status.INVALID, f.finish(ticket).status());
			assertEquals(4, f.bag.countItem(Items.STICK)); assertEquals(4, f.finish(f.prepare()).moved());
		}
	}

	@Test void defensiveMemberGateDoesNotReadOutsiderInventoryAndPreparationRechecksCallbacks() {
		Fixture f = new Fixture();
		SimpleContainer unreadable = new SimpleContainer(1) {
			@Override public int getContainerSize() { return fail("Invalid member must not inspect inventory"); }
		};
		assertFalse(SettlementFoodCargo.hasSurplus(f.data, f.camp, UUID.randomUUID(), unreadable));
		assertFalse(SettlementFoodCargo.isFood(null)); assertFalse(SettlementFoodCargo.isFood(ItemStack.EMPTY));
		assertFalse(SettlementFoodCargo.isFood(new ItemStack(Items.BREAD)));
		assertNull(SettlementCargoDelivery.prepare(f.data, f.camp, f.actor, f.bag, () -> {
			f.data.removeMember(f.actor); return true;
		}));
	}

	@Test void normalTickStillDepositsOtherLegacyFoodWithoutTeleportingHarvestCargo() {
		Fixture f = new Fixture(); f.bag.setItem(0, new ItemStack(Items.POTATO, 64));
		f.bag.setItem(1, new ItemStack(Items.SWEET_BERRIES, 64)); f.bag.setItem(2, new ItemStack(Items.CARROT, 10));
		for (int cycle = 0; cycle < 5; cycle++) f.tick(cycle);
		assertEquals(64, f.bag.countItem(Items.POTATO)); assertEquals(64, f.bag.countItem(Items.SWEET_BERRIES));
		assertEquals(10, f.store.countItem(Items.CARROT)); assertEquals(0, f.store.countItem(Items.POTATO));
		assertEquals(0, f.store.countItem(Items.SWEET_BERRIES));
	}
}
