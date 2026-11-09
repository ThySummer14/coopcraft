package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.Norm;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementFoodQueueTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	private static final UUID A = new UUID(1, 1), B = new UUID(1, 2), C = new UUID(1, 3);

	@Test void fixedDeadlineAndDuplicateTicketGiveOneMealInReverseArrivalOrder() {
		Fixture f = fixture();
		f.person(A).setHunger(65);
		f.person(B).setHunger(90);
		var a = f.submit(A, 100);
		assertSame(a, f.data.foodRequests().submit(f.data, f.settlement, A, 130, 0, a, () -> true, () -> fail()));
		assertSame(a, f.submit(A, 135));
		var b = f.submit(B, 139);
		f.flush(139);
		assertTrue(a.waiting()); assertTrue(f.order.isEmpty());
		f.flush(140);
		assertEquals(List.of(B, A), f.order);
		assertFalse(a.waiting()); assertFalse(b.waiting());
		f.flush(200);
		assertEquals(2, f.order.size());
		assertSame(a, f.data.foodRequests().submit(f.data, f.settlement, A, 200, 0, a, () -> true, () -> fail()));
		f.state.world().assertConservation();
	}

	@Test void singleArrivalWaitsOnlyFortyTicksAndUnsubmittedHungryResidentsDoNotParticipate() {
		Fixture f = fixture();
		f.person(A).setHunger(65); f.person(C).setHunger(100);
		f.submit(A, 10); f.flush(49); assertTrue(f.order.isEmpty());
		f.flush(50); assertEquals(List.of(A), f.order);
	}

	@Test void missingDisabledDormantAndUnsupervisedNormsDoNotQueueOrCreateEvents() {
		Fixture f = fixture();
		int events = f.state.world().ledger().events().size();
		for (int i = 0; i < 30; i++) assertTrue(SettlementFoodRequests.enabled(f.data, f.settlement));
		assertEquals(events, f.state.world().ledger().events().size());
		f.state.setNormsEnabled(false); assertNull(f.submit(A, 0));
		f.state.setNormsEnabled(true); f.state.setSurvivalEnabled(false, 0); assertNull(f.submit(A, 0));
		f.state.setSurvivalEnabled(true, 0);
		f.person(B).restoreSocialState(0, 6, 0); assertNull(f.submit(A, 0));
		SettlementSavedData blank = new SettlementSavedData();
		Settlement settlement = blank.getOrCreate(BlockPos.ZERO, "blank", 0);
		assertFalse(SettlementFoodRequests.enabled(blank, settlement));
		assertTrue(blank.simulationOf(settlement).isEmpty());
	}

	@Test void lossOfSupervisionReleasesInRegistrationOrderImmediately() {
		Fixture f = fixture(); f.person(A).setHunger(100); f.person(B).setHunger(90); f.person(C).setHunger(65);
		var norm = f.state.world().norm(f.state.campOwner().id(), Norm.NEED_FIRST_COMMON_STORE).orElseThrow();
		f.state.world().registerNorm(new Norm(norm.normId(), norm.campId(), 2, norm.supporters(),
			norm.opponents(), norm.knownBy(), 50, Norm.Status.ACTIVE, List.of()));
		f.submit(C, 0); f.submit(A, 1); f.submit(B, 2);
		f.person(B).restoreSocialState(0, 6, 0);
		assertEquals(2, f.state.dailyFoodUnitCap(f.person(A).id()), "Lost ordering authority must not change the active quota");
		f.flush(3); assertEquals(List.of(C, A, B), f.order);
		for (UUID id : List.of(A, B, C)) assertEquals(2, f.state.allocatedFoodUnits(f.person(id).id()));
	}

	@Test void invalidAttendanceAndExplicitCancellationCannotConsumeOrRevive() {
		Fixture f = fixture(); AtomicBoolean present = new AtomicBoolean(true);
		var a = f.data.foodRequests().submit(f.data, f.settlement, A, 0, 0, null, present::get,
			() -> fail("Absent resident ate"));
		var b = f.submit(B, 0); b.cancel();
		present.set(false); f.flush(1); assertFalse(a.waiting());
		present.set(true); f.flush(40); assertTrue(f.order.isEmpty());
	}

	@Test void modeToggleOffThenOnAndWarehouseReregistrationCancelOldRequests() {
		Fixture f = fixture(); var a = f.submit(A, 0);
		f.state.setNormsEnabled(false); f.state.setNormsEnabled(true);
		f.flush(40); assertFalse(a.waiting()); assertTrue(f.order.isEmpty());
		a = f.submit(A, 50); SettlementSimulationService.setNormsEnabled(f.data, f.settlement, false);
		assertFalse(a.waiting()); SettlementSimulationService.setNormsEnabled(f.data, f.settlement, true);
		a = f.submit(A, 60); f.data.setStockpile(f.settlement, BlockPos.ZERO);
		assertFalse(a.waiting()); f.flush(100); assertTrue(f.order.isEmpty());
	}

	@Test void gameAndCoreDayChangesDiscardRequests() {
		Fixture f = fixture(); var a = f.submit(A, 0);
		f.data.foodRequests().flush(f.data, f.settlement, 40, 1);
		assertFalse(a.waiting()); assertTrue(f.order.isEmpty());
		a = f.submit(A, 50); f.state.world().advanceDay(); f.flush(90);
		assertFalse(a.waiting()); assertTrue(f.order.isEmpty());
	}

	@Test void sameWorldSettlementsHaveIndependentWindows() {
		Fixture f = fixture();
		Settlement other = f.data.getOrCreate(new BlockPos(1000, 0, 0), "other", 0);
		var a = f.submit(A, 0);
		f.data.foodRequests().flush(f.data, other, 100, 0);
		f.data.foodRequests().cancel(other); assertTrue(a.waiting());
		f.flush(40); assertEquals(List.of(A), f.order);
	}

	@Test void shortageIsZeroAllocationNotQuotaRefusalAndSnapshotSurvivesSupervisionExhaustion() {
		Fixture f = fixture(3);
		f.person(A).setHunger(100); f.person(B).setHunger(90); f.person(C).setHunger(80);
		f.person(A).restoreSocialState(0, 5, 0); f.person(B).restoreSocialState(0, 5, 0);
		f.submit(C, 0); f.submit(B, 1); f.submit(A, 2); f.flush(40);
		assertEquals(List.of(A, B, C), f.order);
		assertEquals(3, f.state.allocatedFoodUnits(f.person(A).id()));
		assertEquals(0, f.state.allocatedFoodUnits(f.person(B).id()));
		assertEquals(6, f.person(A).socialHours());
		assertEquals(0, f.state.world().ledger().events().stream().filter(e -> e.type() == EventType.SHARE_REFUSED).count());
		f.state.world().assertConservation();
	}

	@Test void quotaRequestsUseExistingOnceDailyRefusalAndPreserveConservation() {
		Fixture f = fixture();
		assertEquals(54, SettlementSimulationService.eat(f.data, f.settlement, A));
		assertEquals(3, f.state.allocatedFoodUnits(f.person(A).id()));
		f.submit(A, 0); f.flush(40); f.submit(A, 50); f.flush(90);
		assertEquals(1, f.state.world().ledger().events().stream().filter(e -> e.type() == EventType.SHARE_REFUSED).count());
		f.state.world().assertConservation();
	}

	@Test void waitingSaveDoesNotConsumeAndReloadDropsQueueButCompletedMealsPersist() {
		Fixture f = fixture(); f.submit(A, 0);
		SettlementSavedData restored = SettlementSavedData.load(f.data.save(new CompoundTag(), null), null);
		var state = restored.simulationOf(f.settlement).orElseThrow();
		assertEquals(f.state.world().inventoryTotals(), state.world().inventoryTotals());
		restored.foodRequests().flush(restored, f.settlement, 100, 0);
		assertEquals(0, state.allocatedFoodUnits(f.person(A).id()));
		f.flush(40);
		restored = SettlementSavedData.load(f.data.save(new CompoundTag(), null), null);
		state = restored.simulationOf(f.settlement).orElseThrow();
		assertEquals(f.state.allocatedFoodUnits(f.person(A).id()), state.allocatedFoodUnits(f.person(A).id()));
		assertEquals(f.state.world().inventoryTotals(), state.world().inventoryTotals());
		state.world().assertConservation();
	}

	@Test void callbackFailureCancelsAllTokensWithoutReplay() {
		Fixture f = fixture();
		var a = f.data.foodRequests().submit(f.data, f.settlement, A, 0, 0, null, () -> true,
			() -> { throw new IllegalStateException("injected failure"); });
		var b = f.submit(B, 0);
		assertThrows(IllegalStateException.class, () -> f.flush(40));
		assertFalse(a.waiting()); assertFalse(b.waiting());
		f.flush(41); assertTrue(f.order.isEmpty());
	}

	@Test void invalidSubmissionReturnsCancelledTokenAndNeverCallsMeal() {
		Fixture f = fixture();
		var rejected = f.data.foodRequests().submit(f.data, f.settlement, A, 0, 0, null, () -> false, () -> fail());
		assertNotNull(rejected); assertFalse(rejected.waiting()); f.flush(40);
		assertEquals(0, f.state.allocatedFoodUnits(f.person(A).id()));
	}

	@Test void removeAndRejoinDoesNotReviveEarlierMembershipTicket() {
		Fixture f = fixture(); var a = f.submit(A, 0);
		f.data.removeMember(A); f.data.registerMember(f.settlement, A, 0);
		f.flush(40); assertFalse(a.waiting()); assertTrue(f.order.isEmpty());
	}

	@Test void dormantNormReleasesArrivalOrderWithoutChangingDailyCapRules() {
		Fixture f = fixture(); f.submit(B, 0); f.submit(A, 1);
		var norm = f.state.world().norm(f.state.campOwner().id(), Norm.NEED_FIRST_COMMON_STORE).orElseThrow();
		f.state.world().registerNorm(new Norm(norm.normId(), norm.campId(), norm.allocationUnitCap(),
			norm.supporters(), norm.opponents(), norm.knownBy(), 0, Norm.Status.DORMANT, List.of()));
		assertNull(f.submit(C, 2)); f.flush(2); assertEquals(List.of(B, A), f.order);
	}

	@Test void arrivalAtDeadlineJoinsClosingRoundWithoutExtendingIt() {
		Fixture f = fixture(); f.submit(A, 10); var b = f.submit(B, 50);
		f.flush(50); assertFalse(b.waiting()); assertEquals(List.of(A, B), f.order);
	}

	@Test void noNeedFirstOrCareAloneDoesNotAddWaiting() {
		for (String norm : new String[] {null, Norm.CARE_FIRST_COMMON_STORE}) {
			Fixture f = fixture(30, norm);
			assertNull(f.submit(A, 0)); assertTrue(f.order.isEmpty());
			assertEquals(54, SettlementSimulationService.eat(f.data, f.settlement, A));
			f.state.world().assertConservation();
		}
	}

	@Test void equalHungerUsesUnableToWorkThenPersonId() {
		Fixture f = fixture();
		f.person(C).initializeAge(com.thysummer.coopcraft.civilization.simulation.Person.AgeBand.CHILD, 0);
		f.submit(B, 0); f.submit(A, 1); f.submit(C, 2); f.flush(40);
		assertEquals(List.of(C, A, B), f.order);
	}

	@Test void barrelLidChangesKeepTheStoreButChestStructureChangesDoNot() {
		var barrel = net.minecraft.world.level.block.Blocks.BARREL.defaultBlockState();
		assertTrue(SettlementFoodRequests.sameStoreState(barrel,
			barrel.setValue(net.minecraft.world.level.block.BarrelBlock.OPEN, true)));
		assertFalse(SettlementFoodRequests.sameStoreState(barrel, barrel.setValue(
			net.minecraft.world.level.block.BarrelBlock.FACING, net.minecraft.core.Direction.EAST)));
		var chest = net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState();
		assertFalse(SettlementFoodRequests.sameStoreState(barrel, chest));
		assertTrue(SettlementFoodRequests.sameStoreState(chest, chest));
		assertFalse(SettlementFoodRequests.sameStoreState(chest, chest.setValue(
			net.minecraft.world.level.block.ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.LEFT)));
	}

	private static Fixture fixture() { return fixture(30); }
	private static Fixture fixture(int food) { return fixture(food, Norm.NEED_FIRST_COMMON_STORE); }
	private static Fixture fixture(int food, String norm) {
		SettlementSavedData data = new SettlementSavedData();
		Settlement settlement = data.getOrCreate(BlockPos.ZERO, "queue", 0);
		SettlementSimulationState state = data.getOrCreateSimulation(settlement);
		for (UUID id : List.of(A, B, C)) { data.registerMember(settlement, id, 0); state.mapResident(id, false, 0); }
		state.setSurvivalEnabled(true, 0); state.setNormsEnabled(true);
		state.world().importExternalBatch(ResourceType.WILD_FRUIT, food, state.campOwner());
		Set<String> supporters = Set.of(state.residentId(A).orElseThrow(), state.residentId(B).orElseThrow());
		if (norm != null) state.world().registerNorm(new Norm(norm, state.campOwner().id(), 3,
			supporters, Set.of(), supporters, 50, Norm.Status.ACTIVE, List.of()));
		return new Fixture(data, settlement, state, new ArrayList<>());
	}
	private record Fixture(SettlementSavedData data, Settlement settlement, SettlementSimulationState state, List<UUID> order) {
		com.thysummer.coopcraft.civilization.simulation.Person person(UUID id) { return state.world().person(state.residentId(id).orElseThrow()); }
		SettlementFoodRequests.Ticket submit(UUID id, long tick) {
			return data.foodRequests().submit(data, settlement, id, tick, 0, null, () -> true,
				() -> { order.add(id); SettlementSimulationService.eat(data, settlement, id); });
		}
		void flush(long tick) { data.foodRequests().flush(data, settlement, tick, 0); }
	}
}
