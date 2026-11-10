package com.thysummer.coopcraft.civilization;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.FoodAccessTestPeople;
import com.thysummer.coopcraft.civilization.simulation.FoodDayTestResidents;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Real service boundaries with supplied world facts; no entity, server, pathfinding or GameTest execution. */
class SettlementBerryReturnIntegrationTest {
	private static final UUID ACTOR = new UUID(61, 1);
	private static final BlockPos STORE = BlockPos.ZERO.east();
	private static HolderLookup.Provider registries;

	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}

	private static final class Fixture {
		SettlementSavedData data = new SettlementSavedData();
		Settlement camp = data.getOrCreate(BlockPos.ZERO, "Berry return", 0);
		SettlementSimulationState state;
		final SimpleContainer bag = new SimpleContainer(8);
		final SimpleContainer store = new SimpleContainer(1);
		final AtomicBoolean present = new AtomicBoolean(true);
		BlockState bush = ripeBush();

		Fixture() {
			data.registerMember(camp, ACTOR, 0);
			assertEquals(SettlementForagingAreas.Status.CREATED,
				data.permitForagingArea(camp, ACTOR, BlockPos.ZERO, 1).status());
			data.setStockpile(camp, STORE);
			state = data.getOrCreateSimulation(camp);
			state.mapResident(ACTOR, false, 0);
			state.setSurvivalEnabled(true, 0);
			state.setProductionEnabled(true);
		}

		int harvest(int amount) {
			bush = ripeBush();
			return SettlementForaging.harvest(data, camp, ACTOR, BlockPos.ZERO, bag, () -> true, () -> true, () -> true,
				() -> bush, () -> List.of(new ItemStack(Items.SWEET_BERRIES, amount)), mature -> {
					bush = mature.setValue(SweetBerryBushBlock.AGE, 1);
					return true;
				});
		}

		SettlementCargoDelivery.Ticket prepare() {
			return SettlementCargoDelivery.prepare(data, camp, ACTOR, bag, present::get);
		}

		SettlementCargoDelivery.Result finish(SettlementCargoDelivery.Ticket ticket, boolean atStore) {
			return SettlementCargoDelivery.finish(ticket, data, camp, ACTOR, bag, store, () -> atStore);
		}

		boolean deliverable() { return SettlementCargoDelivery.hasDeliverableFood(data, camp, ACTOR, bag); }
		long food() { return state.world().inventoryTotals().getOrDefault(ResourceType.WILD_FRUIT, 0L); }
		ItemStack marked(int amount, long day) {
			return SettlementFoodAge.stamp(new ItemStack(Items.SWEET_BERRIES, amount), state.foodClockId().orElseThrow(), day);
		}
		void advanceTo(long day) { while (state.world().day() < day) state.world().advanceDay(); }
		void load(CompoundTag saved) {
			data = SettlementSavedData.load(saved, null);
			camp = data.find(BlockPos.ZERO).orElseThrow();
			state = data.simulationOf(camp).orElseThrow();
		}
	}

	private static BlockState ripeBush() {
		return Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3);
	}

	@Test void actualHarvestWaitsForArrivalAndNeverImportsOldPlainBagFood() {
		Fixture f = new Fixture();
		ItemStack oldFood = new ItemStack(Items.SWEET_BERRIES, 7);
		f.bag.setItem(0, oldFood.copy());
		assertEquals(4, f.harvest(4));
		assertEquals(1, f.bush.getValue(SweetBerryBushBlock.AGE));
		assertTrue(ItemStack.matches(oldFood, f.bag.getItem(0)));
		assertTrue(SettlementFoodAge.isMarked(f.bag.getItem(1)));
		assertEquals(0, f.food());
		assertTrue(f.store.isEmpty());
		assertTrue(f.deliverable());
		var failed = f.prepare();
		assertEquals(SettlementCargoDelivery.Status.NOT_AT_STORE, f.finish(failed, false).status());
		assertEquals(11, f.bag.countItem(Items.SWEET_BERRIES));
		assertEquals(0, f.food());
		assertEquals(SettlementCargoDelivery.Status.INVALID, f.finish(failed, true).status());
		var arrival = f.prepare();
		assertEquals(4, f.finish(arrival, true).moved());
		assertEquals(4, f.food());
		assertTrue(ItemStack.matches(oldFood, f.bag.getItem(0)));
		assertEquals(7, f.bag.countItem(Items.SWEET_BERRIES));
		assertTrue(f.store.isEmpty());
		assertEquals(0, f.finish(arrival, true).moved());
		assertFalse(f.deliverable());
		f.state.world().assertConservation();
	}

	@Test void deliveryKeepsObservedHarvestDayInsteadOfRefreshingAgeOnArrival() {
		Fixture f = new Fixture();
		f.advanceTo(2);
		assertEquals(3, f.harvest(3));
		var original = SettlementFoodAge.read(f.bag.getItem(0)).orElseThrow();
		var ticket = f.prepare();
		f.advanceTo(4);
		assertEquals(3, f.finish(ticket, true).moved());
		var batch = f.state.world().batchesAt(f.state.campOwner()).getFirst();
		assertEquals(2, batch.harvestDay());
		assertEquals(original.expiryDay(), batch.expiryDay());
		assertEquals(4, f.state.world().ledger().lastEvent().orElseThrow().day());
		assertTrue(f.bag.isEmpty());
		assertTrue(f.store.isEmpty());
		f.state.world().assertConservation();
	}

	@Test void fullAndPartialChestUseOneWholeTripBudgetWithoutTouchingExistingFood() {
		Fixture f = new Fixture();
		assertEquals(4, f.harvest(4));
		f.bag.setItem(1, f.marked(4, 0));
		f.store.setItem(0, new ItemStack(Items.DIRT, 64));
		var saved = f.data.save(new CompoundTag(), null);
		assertEquals(SettlementCargoDelivery.Status.NO_CAPACITY, f.finish(f.prepare(), true).status());
		assertEquals(8, f.bag.countItem(Items.SWEET_BERRIES));
		assertEquals(saved, f.data.save(new CompoundTag(), null));
		ItemStack existing = f.marked(63, 0);
		f.store.setItem(0, existing.copy());
		assertEquals(1, f.finish(f.prepare(), true).moved(), "One spare place cannot be reused for the next source slot");
		assertTrue(ItemStack.matches(existing, f.store.getItem(0)));
		assertEquals(3, f.bag.getItem(0).getCount());
		assertEquals(4, f.bag.getItem(1).getCount());
		assertEquals(1, f.food());
		assertEquals(8, f.bag.countItem(Items.SWEET_BERRIES) + f.food());
		f.state.world().assertConservation();
	}

	@Test void productionToggleInvalidatesOldTicketButIdempotentSetterDoesNot() {
		Fixture f = new Fixture();
		f.harvest(3);
		var ticket = f.prepare();
		f.state.setProductionEnabled(true);
		assertTrue(SettlementCargoDelivery.valid(ticket, f.data, f.camp, ACTOR, f.bag));
		f.state.setProductionEnabled(false);
		assertFalse(f.deliverable());
		f.state.setProductionEnabled(true);
		assertFalse(SettlementCargoDelivery.valid(ticket, f.data, f.camp, ACTOR, f.bag));
		assertEquals(SettlementCargoDelivery.Status.INVALID, f.finish(ticket, true).status());
		assertEquals(3, f.bag.countItem(Items.SWEET_BERRIES));
		assertEquals(0, f.food());
		assertEquals(3, f.finish(f.prepare(), true).moved());
		f.state.world().assertConservation();
	}

	@Test void survivalToggleCannotReviveAnEarlierFoodReturnTicket() {
		Fixture f = new Fixture();
		f.harvest(3);
		var ticket = f.prepare();
		f.state.setSurvivalEnabled(false, 0);
		assertFalse(f.deliverable());
		f.state.setSurvivalEnabled(true, 0);
		assertFalse(SettlementCargoDelivery.valid(ticket, f.data, f.camp, ACTOR, f.bag));
		assertEquals(SettlementCargoDelivery.Status.INVALID, f.finish(ticket, true).status());
		assertEquals(3, f.bag.countItem(Items.SWEET_BERRIES));
		assertEquals(0, f.food());
		assertEquals(3, f.finish(f.prepare(), true).moved());
		f.state.world().assertConservation();
	}

	@Test void missingMemberMissingMappingDeadAndMigratedResidentsCannotFeedTheCamp() {
		for (String change : List.of("member", "mapping", "dead", "migrated")) {
			Fixture f = new Fixture();
			f.harvest(3);
			var ticket = f.prepare();
			switch (change) {
				case "member" -> f.data.removeMember(ACTOR);
				case "mapping" -> {
					CompoundTag saved = f.data.save(new CompoundTag(), null);
					saved.getList("Simulations", Tag.TAG_COMPOUND).getCompound(0).put("Residents", new ListTag());
					f.load(saved);
					assertTrue(f.state.residentId(ACTOR).isEmpty());
					ticket = f.prepare();
				}
				case "dead" -> FoodAccessTestPeople.markDead(f.state.world().person(f.state.residentId(ACTOR).orElseThrow()), 0);
				case "migrated" -> FoodDayTestResidents.move(f.state.world().person(f.state.residentId(ACTOR).orElseThrow()), "other-camp");
				default -> fail(change);
			}
			assertFalse(f.deliverable(), change);
			assertEquals(change.equals("member") ? SettlementCargoDelivery.Status.INVALID
				: SettlementCargoDelivery.Status.FOOD_UNAVAILABLE, f.finish(ticket, true).status(), change);
			assertEquals(3, f.bag.countItem(Items.SWEET_BERRIES), change);
			assertEquals(0, f.food(), change);
			assertTrue(f.store.isEmpty(), change);
			f.state.world().assertConservation();
		}
	}

	@Test void expiryDuringTravelPreservesCargoAndReportsFoodUnavailableRatherThanFull() {
		Fixture f = new Fixture();
		f.harvest(5);
		ItemStack before = f.bag.getItem(0).copy();
		var age = SettlementFoodAge.read(before).orElseThrow();
		var ticket = f.prepare();
		f.advanceTo(age.expiryDay() + 1);
		assertFalse(f.deliverable());
		assertEquals(SettlementCargoDelivery.Status.FOOD_UNAVAILABLE, f.finish(ticket, true).status());
		assertTrue(ItemStack.matches(before, f.bag.getItem(0)));
		assertEquals(0, f.food());
		assertTrue(f.store.isEmpty());
		assertTrue(f.state.world().ledger().events().isEmpty());
	}

	@Test void lostLivePresenceConsumesTicketWithoutMovingOrRevivingFood() {
		Fixture f = new Fixture();
		f.harvest(3);
		var ticket = f.prepare();
		f.present.set(false);
		assertEquals(SettlementCargoDelivery.Status.INVALID, f.finish(ticket, true).status());
		f.present.set(true);
		assertEquals(SettlementCargoDelivery.Status.INVALID, f.finish(ticket, true).status());
		assertEquals(3, f.bag.countItem(Items.SWEET_BERRIES));
		assertEquals(0, f.food());
		assertTrue(f.store.isEmpty());
	}

	@Test void deathCustodyAndPartialRecoveryKeepOnePhysicalQuantityAndOriginalAgeAcrossReload() {
		Fixture f = new Fixture();
		f.advanceTo(1);
		f.harvest(5);
		var age = SettlementFoodAge.read(f.bag.getItem(0)).orElseThrow();
		var ticket = f.prepare();
		var claims = SettlementMembershipClaims.resolve(List.of(
			new SettlementMembershipClaims.Source(Level.OVERWORLD, f.data)), Level.OVERWORLD, ACTOR);
		var recovery = new SettlementCargoRecovery();
		f.present.set(false);
		assertEquals(5, recovery.captureDeath(claims, Level.OVERWORLD, ACTOR, STORE, "Resident", () -> f.bag));
		assertEquals(0, recovery.captureDeath(claims, Level.OVERWORLD, ACTOR, STORE, "Resident", () -> f.bag));
		f.data.removeMember(ACTOR);
		assertEquals(SettlementCargoDelivery.Status.INVALID, f.finish(ticket, true).status());
		assertTrue(f.bag.isEmpty());
		assertEquals(0, f.food());
		recovery = SettlementCargoRecovery.load(recovery.save(new CompoundTag(), registries), registries);
		SimpleContainer first = new SimpleContainer(1) {
			@Override public int getMaxStackSize() { return 2; }
		};
		assertEquals(2, recovery.recover(ACTOR, first, () -> true).moved());
		assertEquals(age, SettlementFoodAge.read(first.getItem(0)).orElseThrow());
		assertEquals(3, recovery.inspect(ACTOR).orElseThrow().totalCount());
		assertEquals(5, first.countItem(Items.SWEET_BERRIES) + recovery.inspect(ACTOR).orElseThrow().totalCount());
		recovery = SettlementCargoRecovery.load(recovery.save(new CompoundTag(), registries), registries);
		SimpleContainer second = new SimpleContainer(1);
		assertEquals(3, recovery.recover(ACTOR, second, () -> true).moved());
		assertEquals(age, SettlementFoodAge.read(second.getItem(0)).orElseThrow());
		assertTrue(recovery.inspect(ACTOR).isEmpty());
		assertEquals(0, recovery.recover(ACTOR, second, () -> true).moved());
		assertEquals(5, first.countItem(Items.SWEET_BERRIES) + second.countItem(Items.SWEET_BERRIES) + f.food());
		f.state.world().assertConservation();
	}

	@Test void stateReloadInvalidatesOldTicketWhileFreshArrivalPreservesTheSameClockAndCargo() {
		Fixture f = new Fixture();
		f.advanceTo(1);
		f.harvest(4);
		var age = SettlementFoodAge.read(f.bag.getItem(0)).orElseThrow();
		var oldTicket = f.prepare();
		f.load(f.data.save(new CompoundTag(), null));
		assertEquals(age.clockId(), f.state.foodClockId().orElseThrow());
		assertEquals(SettlementCargoDelivery.Status.INVALID, f.finish(oldTicket, true).status());
		assertEquals(4, f.bag.countItem(Items.SWEET_BERRIES));
		assertEquals(0, f.food());
		assertEquals(4, f.finish(f.prepare(), true).moved());
		assertTrue(f.bag.isEmpty());
		for (int round = 0; round < 3; round++) {
			CompoundTag saved = f.data.save(new CompoundTag(), null);
			f.load(saved);
			assertEquals(saved, f.data.save(new CompoundTag(), null));
			assertEquals(4, f.food());
			assertEquals(age.harvestDay(), f.state.world().batchesAt(f.state.campOwner()).getFirst().harvestDay());
			assertEquals(age.expiryDay(), f.state.world().batchesAt(f.state.campOwner()).getFirst().expiryDay());
			f.state.world().assertConservation();
		}
	}

	@Test void exportedBerryReturnCreatesNoNewDonorCreditAndLegacyProductionApiRemainsAvailable() {
		Fixture f = new Fixture();
		f.harvest(3);
		assertEquals(3, f.finish(f.prepare(), true).moved());
		var person = f.state.world().person(f.state.residentId(ACTOR).orElseThrow());
		int trust = person.trustIn(f.state.campOwner().id()), grievance = person.grievance();
		var reciprocity = person.reciprocityMemories();
		var batch = f.state.world().batchesAt(f.state.campOwner()).getFirst();
		assertEquals(3, SettlementSimulationService.exportToStockpile(f.data, f.camp, batch.batchId(), 3, f.bag));
		assertEquals(0, f.food());
		assertEquals(3, f.finish(f.prepare(), true).moved());
		assertEquals(3, f.food());
		assertTrue(f.bag.isEmpty());
		assertEquals(trust, person.trustIn(f.state.campOwner().id()));
		assertEquals(grievance, person.grievance());
		assertEquals(reciprocity, person.reciprocityMemories());
		var imports = f.state.world().ledger().events().stream().filter(event -> event.type() == EventType.EXTERNAL_IMPORT).toList();
		assertEquals(2, imports.size());
		assertTrue(imports.stream().allMatch(event -> event.actors().isEmpty() && event.causalLinks().isEmpty()));
		assertEquals(List.of(EventType.EXTERNAL_IMPORT, EventType.EXTERNAL_EXPORT, EventType.EXTERNAL_IMPORT),
			f.state.world().ledger().events().stream().map(event -> event.type()).toList());
		f.bag.setItem(0, new ItemStack(Items.POTATO, 2));
		assertEquals(2, SettlementSimulationService.recordProduction(f.data, f.camp, ACTOR, f.bag, Items.POTATO, 2, "harvest_crop"));
		assertTrue(f.bag.isEmpty());
		assertEquals(2L, f.state.world().inventoryTotals().getOrDefault(ResourceType.TUBERS, 0L).longValue());
		assertEquals(3, f.food());
		f.state.world().assertConservation();
	}

	@Test void sourceWiringUsesFoodAwareEmergencyGuardForSelectionContinuationAndReturnTicks() throws Exception {
		String source = Files.readString(Path.of("src/main/java/com/thysummer/coopcraft/civilization/VillagerWorkGoal.java"));
		String emergency = source.substring(source.indexOf("private boolean emergencyForage()"), source.indexOf("private boolean hasDeliverableFoodCargo()"));
		assertTrue(emergency.contains("if (hasDeliverableFoodCargo()) return false;"));
		assertTrue(emergency.indexOf("hasDeliverableFoodCargo()") < emergency.indexOf("satiety()"));
		String food = source.substring(source.indexOf("private boolean hasDeliverableFoodCargo()"), source.indexOf("private boolean prepareCargoReturn()"));
		assertTrue(food.contains("SettlementCargoDelivery.hasDeliverableFood(data, camp, villager.getUUID(), villager.getInventory())"));
		String selection = source.substring(source.indexOf("public boolean canUse()"), source.indexOf("public boolean canContinueToUse()"));
		String continuation = source.substring(source.indexOf("public boolean canContinueToUse()"), source.indexOf("public void start()"));
		String returning = source.substring(source.indexOf("private void tickCargoReturn()"), source.indexOf("private boolean shouldEat()"));
		assertTrue(selection.contains("carrying && !emergencyForage()"));
		assertTrue(continuation.matches("(?s).*&& !emergencyForage\\(\\)\\)\\s*\\{\\s*releaseForageLease\\(\\);\\s*releaseMaterialLease\\(\\);\\s*cancelShearing\\(\\);\\s*return false;\\s*}.*"),
			"Food-aware cargo preemption must release forage, material and sheep work before stopping");
		assertTrue(returning.contains("meetingTicket != null || emergencyForage()"));
		assertTrue(returning.indexOf("if (atCargoStore())") < returning.indexOf("Container currentStore"));
		assertTrue(returning.contains("currentStore, this::atCargoStore"));
	}
}
