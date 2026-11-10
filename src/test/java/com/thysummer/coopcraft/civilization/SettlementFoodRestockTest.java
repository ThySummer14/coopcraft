package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.thysummer.coopcraft.civilization.simulation.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Shared production, decision, queue and consumption services; no simulated callback proves entity arrival. */
class SettlementFoodRestockTest {
	static final UUID A = new UUID(39, 1), B = new UUID(39, 2);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void actualProductionRefillsInterruptedMealWithoutInventingSocialRefusal() {
		Fixture f = fixture(2);
		int trust = f.person().trustIn(f.camp()); int grievance = f.person().grievance();
		assertEquals(40, eat(f));
		assertEquals(40, f.data.needsOf(f.settlement, A).satiety());
		assertEquals(2, f.state.allocatedFoodUnits(f.person().id()));
		assertEquals(SettlementFoodRestock.Status.NO_FOOD, snapshot(f).status());
		assertFalse(decision(f)); assertEquals(0, count(f, EventType.SHARE_REFUSED));
		assertEquals(trust, f.person().trustIn(f.camp())); assertEquals(grievance, f.person().grievance());
		f.state.setProductionEnabled(true);
		SimpleContainer inventory = new SimpleContainer(new ItemStack(Items.COD, 2));
		assertEquals(2, SettlementSimulationService.recordProduction(f.data, f.settlement, B, inventory, Items.COD, 2, "gather"));
		assertTrue(inventory.isEmpty()); assertTrue(decision(f));
		assertEquals(SettlementFoodRestock.Status.READY, snapshot(f).status());
		assertEquals(20, eat(f)); assertEquals(1L, f.state.world().inventoryTotals().get(ResourceType.FISH).longValue());
		assertEquals(3, f.state.allocatedFoodUnits(f.person().id()));
		assertEquals(SettlementFoodRestock.Status.ALLOCATION_CHANGED, snapshot(f).status());
		assertTrue(decision(f), "Only the existing pending-refusal trip now remains");
		assertEquals(0, eat(f)); assertEquals(1, count(f, EventType.SHARE_REFUSED));
		assertFalse(decision(f)); assertEquals(1, count(f, EventType.COMMON_MEAL_INTERRUPTED));
		assertEquals(trust - 2, f.person().trustIn(f.camp()));
		f.state.world().assertConservation();
	}

	@Test void repeatedSmallRestocksReplaceEvidenceOnlyAfterRealConsumption() {
		Fixture f = fixture(1); assertEquals(20, eat(f));
		long first = snapshot(f).evidence().eventId();
		f.state.world().importExternalBatch(ResourceType.FISH, 1, f.state.campOwner());
		assertEquals(20, eat(f));
		assertTrue(snapshot(f).evidence().eventId() > first);
		assertEquals(2, snapshot(f).evidence().allocatedUnits());
		assertEquals(60, snapshot(f).evidence().hunger());
		assertEquals(2, count(f, EventType.COMMON_MEAL_INTERRUPTED));
		var before = save(f); f.data.setDirty(false);
		for (int i = 0; i < 5; i++) { assertFalse(decision(f)); assertEquals(0, eat(f)); }
		assertEquals(before, save(f)); assertFalse(f.data.isDirty());
		f.state.world().importExternalBatch(ResourceType.FISH, 1, f.state.campOwner());
		assertTrue(decision(f)); assertEquals(20, eat(f));
		assertEquals(2, count(f, EventType.COMMON_MEAL_INTERRUPTED));
		assertEquals(SettlementFoodRestock.Status.ALLOCATION_CHANGED, snapshot(f).status());
		f.state.world().assertConservation();
	}

	@Test void reloadedCurrentDayEvidenceAndReadOnlyDecisionSurviveThreeRoundTrips() {
		Fixture f = fixture(2); eat(f);
		f.state.world().importExternalBatch(ResourceType.FISH, 1, f.state.campOwner());
		var expected = snapshot(f);
		for (int i = 0; i < 3; i++) {
			var before = save(f); f = reload(f); f.data.setDirty(false);
			assertEquals(before, save(f)); assertEquals(24, before.getInt("DataVersion"));
			for (int n = 0; n < 5; n++) { assertEquals(expected, snapshot(f)); assertTrue(decision(f)); }
			assertEquals(before, save(f)); assertFalse(f.data.isDirty());
			f.state.world().assertConservation();
		}
	}

	@Test void modesDatesHungerAndMembershipUseCurrentFactsWithoutInventingEvidence() {
		Fixture f = fixture(2); eat(f); f.state.world().importExternalBatch(ResourceType.FISH, 1, f.state.campOwner());
		f.state.setNormsEnabled(false); assertTrue(decision(f), "Physical interruption is not a political refusal");
		f.state.setSurvivalEnabled(false, 0); assertFalse(decision(f));
		assertEquals(SettlementFoodRestock.Status.SURVIVAL_DISABLED, snapshot(f).status());
		f.state.setSurvivalEnabled(true, 0); assertTrue(decision(f));
		for (long day : new long[] {-1, 1}) {
			assertEquals(SettlementFoodRestock.Status.DAY_PENDING, SettlementFoodRestock.inspect(f.data, f.settlement, A, day).orElseThrow().status());
			assertFalse(SettlementFoodAccess.shouldEat(f.data, f.settlement, A, day, 40, true));
		}
		f.person().setHunger(5); assertEquals(SettlementFoodRestock.Status.SATISFIED, snapshot(f).status());
		assertFalse(decision(f)); f.person().setHunger(60);
		var copy = new Settlement(f.settlement.id(), f.settlement.center(), f.settlement.name(), f.settlement.foundedDay());
		assertTrue(SettlementFoodRestock.inspect(f.data, copy, A, 0).isEmpty());
		assertTrue(SettlementFoodRestock.inspect(f.data, f.settlement, new UUID(39, 99), 0).isEmpty());
		f.data.removeMember(A); assertTrue(SettlementFoodRestock.inspect(f.data, f.settlement, A, 0).isEmpty());
	}

	@Test void oldDayDeadResidentAndMissingLegacyRecordCannotGrantAnotherMeal() {
		for (String change : List.of("day", "dead", "legacy")) {
			Fixture f = fixture(2); eat(f);
			if (change.equals("day")) { f.state.world().advanceDay(); f.state.markSurvivalDay(1); }
			else if (change.equals("dead")) FoodAccessTestPeople.markDead(f.person(), 0);
			else {
				CompoundTag saved = save(f);
				// Remove only the optional new ledger event, as an old save would lack it.
				removeInterruption(saved); var data = SettlementSavedData.load(saved, null);
				var settlement = data.find(BlockPos.ZERO).orElseThrow();
				f = new Fixture(data, settlement, data.simulationOf(settlement).orElseThrow());
			}
			f.state.world().importExternalBatch(ResourceType.FISH, 1, f.state.campOwner());
			assertTrue(SettlementFoodRestock.inspect(f.data, f.settlement, A, f.state.lastSurvivalDay()).isEmpty(), change);
			assertFalse(decision(f), change);
		}
	}

	@Test void fullAllowanceWithoutRefusalStillCannotResumeJustBecauseCapIsRaised() {
		Fixture f = fixture(10); assertEquals(60, eat(f)); cap(f, 4);
		assertEquals(0, count(f, EventType.COMMON_MEAL_INTERRUPTED));
		assertFalse(decision(f)); assertEquals(0, count(f, EventType.SHARE_REFUSED));
	}

	@Test void queuedRestockRechecksCurrentCapStockAndDoesNotReplayCompletedTicket() {
		for (String change : List.of("none", "raise", "lower", "empty")) {
			Fixture f = fixture(2); eat(f); cap(f, 3);
			f.state.world().importExternalBatch(ResourceType.FISH, 3, f.state.campOwner());
			assertTrue(decision(f)); assertTrue(SettlementFoodRequests.enabled(f.data, f.settlement));
			int[] calls = {0}, nutrition = {0};
			var ticket = f.data.foodRequests().submit(f.data, f.settlement, A, 100, 0, null, () -> true,
				() -> { calls[0]++; nutrition[0] = eat(f); });
			assertSame(ticket, f.data.foodRequests().submit(f.data, f.settlement, A, 110, 0, ticket, () -> true, () -> fail("replay")));
			if (change.equals("raise")) cap(f, 4);
			if (change.equals("lower")) cap(f, 2);
			if (change.equals("empty")) {
				var batch = f.state.world().batchesAt(f.state.campOwner()).getFirst();
				f.state.world().exportExternalBatch(f.state.campOwner(), batch.batchId(), batch.quantity());
			}
			long stock = f.state.world().availableNutrition(f.state.campOwner());
			f.data.foodRequests().flush(f.data, f.settlement, 139, 0); assertEquals(0, calls[0]);
			f.data.foodRequests().flush(f.data, f.settlement, 140, 0); assertEquals(1, calls[0]);
			int expected = change.equals("raise") ? 40 : change.equals("none") ? 20 : 0;
			assertEquals(expected, nutrition[0]); assertEquals(stock - expected, f.state.world().availableNutrition(f.state.campOwner()));
			f.data.foodRequests().flush(f.data, f.settlement, 200, 0); assertEquals(1, calls[0]);
			assertFalse(ticket.waiting()); f.state.world().assertConservation();
		}
	}

	@Test void pendingRestockBatchCancelsOnDayModeMembershipOrAttendanceChange() {
		for (String change : List.of("day", "mode", "member", "attendance")) {
			Fixture f = fixture(2); eat(f); cap(f, 3);
			f.state.world().importExternalBatch(ResourceType.FISH, 1, f.state.campOwner());
			boolean[] present = {true};
			var ticket = f.data.foodRequests().submit(f.data, f.settlement, A, 100, 0, null, () -> present[0], () -> fail("cancelled meal"));
			if (change.equals("day")) f.state.world().advanceDay();
			if (change.equals("mode")) f.state.setNormsEnabled(false);
			if (change.equals("member")) f.data.removeMember(A);
			if (change.equals("attendance")) present[0] = false;
			long stock = f.state.world().availableNutrition(f.state.campOwner());
			f.data.foodRequests().flush(f.data, f.settlement, 140, 0);
			assertFalse(ticket.waiting()); assertEquals(stock, f.state.world().availableNutrition(f.state.campOwner()));
			f.state.world().assertConservation();
		}
	}

	static Fixture fixture(int fish) {
		var data = new SettlementSavedData(); var settlement = data.getOrCreate(BlockPos.ZERO, "Restock", 0);
		var state = data.getOrCreateSimulation(settlement);
		for (UUID id : List.of(A, B)) { data.registerMember(settlement, id, 0); state.mapResident(id, false, 0); }
		state.setSurvivalEnabled(true, 0); state.setNormsEnabled(true);
		state.world().person(state.residentId(A).orElseThrow()).setTrust(state.campOwner().id(), 50);
		if (fish > 0) state.world().importExternalBatch(ResourceType.FISH, fish, state.campOwner());
		return new Fixture(data, settlement, state);
	}
	static void cap(Fixture f, int cap) {
		Set<String> people = Set.of(f.person().id(), f.state.residentId(B).orElseThrow());
		f.state.world().registerNorm(new Norm(Norm.NEED_FIRST_COMMON_STORE, f.camp(), cap, people, Set.of(), people, 100, Norm.Status.ACTIVE, List.of()));
	}
	static int eat(Fixture f) { return SettlementSimulationService.eat(f.data, f.settlement, A); }
	static boolean decision(Fixture f) { return SettlementFoodAccess.shouldEat(f.data, f.settlement, A, f.state.lastSurvivalDay(),
		100 - f.person().hunger(), SettlementSimulationService.hasFoodFor(f.data, f.settlement, A)); }
	static SettlementFoodRestock.Snapshot snapshot(Fixture f) { return SettlementFoodRestock.inspect(f.data, f.settlement, A, f.state.lastSurvivalDay()).orElseThrow(); }
	static long count(Fixture f, EventType type) { return f.state.world().ledger().events().stream().filter(e -> e.type() == type).count(); }
	static CompoundTag save(Fixture f) { return f.data.save(new CompoundTag(), null); }
	static Fixture reload(Fixture f) { var data = SettlementSavedData.load(save(f), null); var settlement = data.find(BlockPos.ZERO).orElseThrow();
		return new Fixture(data, settlement, data.simulationOf(settlement).orElseThrow()); }
	private static void removeInterruption(CompoundTag tag) {
		for (String key : Set.copyOf(tag.getAllKeys())) {
			var value = tag.get(key);
			if (value instanceof CompoundTag child) removeInterruption(child);
			if (value instanceof net.minecraft.nbt.ListTag list) for (int i = list.size() - 1; i >= 0; i--) {
				if (list.get(i) instanceof CompoundTag child) {
					if (child.getString("Type").equals("COMMON_MEAL_INTERRUPTED")) list.remove(i);
					else removeInterruption(child);
				}
			}
		}
	}
	static record Fixture(SettlementSavedData data, Settlement settlement, SettlementSimulationState state) {
		Person person() { return state.world().person(state.residentId(A).orElseThrow()); }
		String camp() { return state.campOwner().id(); }
	}
}
