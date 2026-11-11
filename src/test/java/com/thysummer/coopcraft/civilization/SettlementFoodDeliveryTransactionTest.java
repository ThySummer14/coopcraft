package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Representative ticket/reservation/session pipeline; not an entity or navigation GameTest. */
class SettlementFoodDeliveryTransactionTest {
	@BeforeAll static void bootstrap() { SettlementFoodSharingTest.bootstrap(); }

	private static final class Route {
		final SettlementFoodSharingTest.Fixture fixture;
		final SettlementFoodDeliveryReservations<SettlementFoodSharing.Ticket> reservations = new SettlementFoodDeliveryReservations<>();
		final SettlementFoodSharingSessions<SettlementFoodSharing.Ticket> sessions = new SettlementFoodSharingSessions<>();
		final SettlementFoodSharing.Ticket ticket;
		final SettlementFoodDeliveryReservations.Lease<SettlementFoodSharing.Ticket> lease;
		Route(SettlementFoodSharingTest.Fixture fixture) {
			this.fixture = fixture;
			ticket = fixture.prepare(); assertNotNull(ticket);
			lease = reservations.reserve(fixture.giver, fixture.recipient, 0, ticket); assertNotNull(lease);
		}
		boolean enRoute(long tick) {
			if (reservations.active(lease, tick) && SettlementFoodSharing.valid(ticket)) return true;
			cancel(); return false;
		}
		void cancel() { reservations.cancel(lease); SettlementFoodSharing.cancel(ticket); }
		boolean arrive(long tick) { return arrive(tick, fixture::prepare); }
		boolean arrive(long tick, Supplier<SettlementFoodSharing.Ticket> prepareStationary) {
			if (!enRoute(tick)) return false;
			try {
				return reservations.transfer(lease, tick, () -> {
					if (!SettlementFoodSharing.valid(ticket)) return false;
					var stationary = prepareStationary.get();
					if (stationary == null) return false;
					if (sessions.reserve(fixture.giver, fixture.recipient, tick, stationary) != null) return true;
					SettlementFoodSharing.cancel(stationary); return false;
				});
			} finally { SettlementFoodSharing.cancel(ticket); }
		}
		boolean pulse(SettlementFoodSharingSessions.Pair<SettlementFoodSharing.Ticket> pair, UUID resident, long tick) {
			return sessions.pulse(pair, resident, tick, SettlementFoodSharing.valid(pair.value))
				&& SettlementFoodSharing.finish(pair.value, pair::completed);
		}
	}

	@Test void requestGatePrefersCurrentFoodAndRequiresHungryMemberWithRoom() {
		for (String scenario : List.of("empty", "no_store", "own_food", "public_food", "fed", "full", "survival", "other_camp", "nonmember")) {
			var f = new SettlementFoodSharingTest.Fixture();
			var store = scenario.equals("no_store") ? null : new net.minecraft.world.SimpleContainer(8);
			Settlement camp = f.camp;
			switch (scenario) {
				case "own_food" -> f.other.setItem(0, new ItemStack(Items.BREAD));
				case "public_food" -> store.setItem(0, new ItemStack(Items.BREAD));
				case "fed" -> f.data.setSatiety(camp, f.recipient, SettlementNeeds.HUNGRY_AT + 1);
				case "full" -> { for (int slot = 0; slot < f.other.getContainerSize(); slot++) f.other.setItem(slot, new ItemStack(Items.STICK)); }
				case "survival" -> f.data.getOrCreateSimulation(camp).setSurvivalEnabled(true, 0);
				case "other_camp" -> camp = f.data.getOrCreate(new net.minecraft.core.BlockPos(1000, 64, 0), "Other", 0);
				case "nonmember" -> f.data.removeMember(f.recipient);
			}
			f.data.setDirty(false);
			assertEquals(scenario.equals("empty") || scenario.equals("no_store"),
				SettlementFoodDeliveryScene.requestEligible(f.data, camp, f.recipient, f.other, store), scenario);
			assertFalse(f.data.isDirty(), scenario);
			if (!scenario.equals("survival")) assertTrue(f.data.simulationOf(camp).isEmpty(), scenario);
		}
	}

	@Test void requestingRecipientUsesSameEndpointsAndCompletesOneFactAfterFreshJointTicks() {
		var f = new SettlementFoodSharingTest.Fixture(); var route = new Route(f);
		assertSame(route.lease, route.reservations.find(f.recipient, 0));
		assertNull(route.reservations.reserve(UUID.randomUUID(), f.recipient, 0, f.prepare()));
		assertNull(route.reservations.reserve(f.giver, UUID.randomUUID(), 0, f.prepare()));
		assertTrue(route.arrive(60)); var pair = route.sessions.find(f.recipient, 60);
		assertTrue(route.sessions.join(pair, f.recipient, 60));
		assertTrue(route.sessions.join(pair, f.giver, 60));
		assertFalse(route.sessions.pulse(pair, f.recipient, 60, true));
		assertFalse(route.sessions.pulse(pair, f.giver, 60, true));
		for (int tick = 61; tick <= 100; tick++) {
			assertFalse(route.sessions.pulse(pair, f.recipient, tick, SettlementFoodSharing.valid(pair.value)));
			boolean completed = route.sessions.pulse(pair, f.giver, tick, SettlementFoodSharing.valid(pair.value));
			assertEquals(tick == 100, completed);
			if (completed) {
				var proof = SettlementFoodSharing.finish(pair.value, pair::completed, tick, "Original giver", "Hungry requester");
				assertNotNull(proof); assertTrue(f.data.rememberFoodSharing(f.camp, proof));
				assertFalse(f.data.rememberFoodSharing(f.camp, proof));
			}
		}
		assertEquals(15, f.bag.countItem(Items.BREAD)); assertEquals(1, f.other.countItem(Items.BREAD));
		var history = f.data.foodSharingMemoriesOf(f.camp, f.recipient);
		assertEquals(1, history.size()); assertEquals(f.giver, history.getFirst().giver());
		assertEquals(f.recipient, history.getFirst().recipient());
	}

	@Test void surplusPointsMustCoverOneWholeAvailableRation() {
		int checked = 0;
		int points = SettlementFood.points(Items.BREAD);
		assertTrue(points > 1);
		for (int seed = 10; seed < 90; seed++) {
			var f = new SettlementFoodSharingTest.Fixture(new UUID(92, seed));
			if (f.retained() % points == 0) continue;
			checked++;
			int count = f.retained() / points + 1;
			f.bag.setItem(0, new ItemStack(Items.BREAD, count));
			long surplus = SettlementFoodSharing.ordinaryFoodPoints(f.bag) - f.retained();
			assertTrue(surplus > 0 && surplus < points);
			assertFalse(SettlementFoodSharing.hasSpareRation(f.data, f.camp, f.giver, f.bag));
			assertNull(f.prepare());
			assertEquals(count, f.bag.countItem(Items.BREAD)); assertTrue(f.other.isEmpty());
			f.bag.getItem(0).grow(1);
			assertTrue(SettlementFoodSharing.hasSpareRation(f.data, f.camp, f.giver, f.bag));
			assertNotNull(f.prepare());
			assertEquals(count + 1, f.bag.countItem(Items.BREAD)); assertTrue(f.other.isEmpty());
		}
		assertTrue(checked > 0, "Fixture seeds must include a non-divisible retained budget");
	}

	@Test void routeValidationAndCancellationNeverDebitFood() {
		var f = new SettlementFoodSharingTest.Fixture(); var route = new Route(f);
		for (int tick = 0; tick < 100; tick++) {
			assertTrue(route.enRoute(tick));
			assertEquals(16, f.bag.countItem(Items.BREAD)); assertTrue(f.other.isEmpty());
			assertNull(route.sessions.find(f.giver, tick));
		}
		route.cancel();
		assertFalse(route.arrive(100)); assertFalse(f.finish(route.ticket));
		assertNull(route.reservations.find(f.recipient, 100));
		assertEquals(16, f.bag.countItem(Items.BREAD)); assertTrue(f.other.isEmpty());
	}

	@Test void occupiedDestinationCancelsWithoutOverwritingOrDebiting() {
		var f = new SettlementFoodSharingTest.Fixture(); var route = new Route(f);
		var occupant = new ItemStack(Items.STICK, 3); f.other.setItem(route.ticket.destination, occupant);
		assertFalse(route.enRoute(5)); assertFalse(route.arrive(6));
		assertSame(occupant, f.other.getItem(route.ticket.destination));
		assertEquals(3, occupant.getCount()); assertEquals(16, f.bag.countItem(Items.BREAD));
		assertEquals(0, f.other.countItem(Items.BREAD));
		assertNull(route.reservations.find(f.giver, 6));
	}

	@Test void changedSourceIdentityCountOrTypeCannotBeDelivered() {
		for (String change : List.of("identity", "count", "type")) {
			var f = new SettlementFoodSharingTest.Fixture(); var route = new Route(f);
			switch (change) {
				case "identity" -> f.bag.setItem(0, f.bag.getItem(0).copy());
				case "count" -> f.bag.getItem(0).shrink(1);
				case "type" -> f.bag.setItem(0, new ItemStack(Items.CARROT, 16));
			}
			var changed = f.bag.getItem(0); var snapshot = changed.copy();
			assertFalse(route.enRoute(10)); assertFalse(route.arrive(11));
			assertSame(changed, f.bag.getItem(0)); assertTrue(ItemStack.matches(snapshot, changed));
			assertTrue(f.other.isEmpty()); assertFalse(f.finish(route.ticket));
		}
	}

	@Test void modeChangeAndModeAbaInvalidateRouteTicket() {
		for (boolean aba : new boolean[]{false, true}) {
			var f = new SettlementFoodSharingTest.Fixture();
			var state = f.data.getOrCreateSimulation(f.camp); var route = new Route(f);
			state.setSurvivalEnabled(true, 5);
			if (aba) state.setSurvivalEnabled(false, 6);
			assertFalse(route.enRoute(10)); assertFalse(route.arrive(11));
			assertEquals(16, f.bag.countItem(Items.BREAD)); assertTrue(f.other.isEmpty());
			assertNull(route.sessions.find(f.giver, 11));
		}
	}

	@Test void eitherMemberAbaInvalidatesRouteEvenAfterHungerIsRestored() {
		for (boolean giver : new boolean[]{false, true}) {
			var f = new SettlementFoodSharingTest.Fixture(); var route = new Route(f);
			UUID resident = giver ? f.giver : f.recipient;
			int satiety = f.data.needsOf(f.camp, resident).satiety();
			f.data.removeMember(resident); f.data.registerMember(f.camp, resident, 5);
			f.data.setSatiety(f.camp, resident, satiety);
			assertFalse(route.enRoute(10)); assertFalse(route.arrive(11));
			assertEquals(16, f.bag.countItem(Items.BREAD)); assertTrue(f.other.isEmpty());
			assertFalse(f.finish(route.ticket));
		}
	}

	@Test void invalidTravelTicketCannotBeReissuedEvenWhenFreshTicketWouldBeEligible() {
		var f = new SettlementFoodSharingTest.Fixture(); var route = new Route(f);
		f.bag.setItem(0, f.bag.getItem(0).copy());
		assertNotNull(f.prepare()); // Contents are eligible, but the original identity was replaced.
		var calls = new AtomicInteger();
		assertFalse(route.arrive(10, () -> { calls.incrementAndGet(); return f.prepare(); }));
		assertEquals(0, calls.get());
		assertNull(route.reservations.find(f.giver, 10)); assertNull(route.sessions.find(f.giver, 10));
		assertFalse(f.finish(route.ticket));
		assertEquals(16, f.bag.countItem(Items.BREAD)); assertTrue(f.other.isEmpty());
	}

	@Test void failedStationaryReissueClosesTravelReservationAndTicket() {
		var f = new SettlementFoodSharingTest.Fixture(); var route = new Route(f);
		var calls = new AtomicInteger();
		assertFalse(route.arrive(10, () -> {
			calls.incrementAndGet();
			f.present = false; // The stationary preparation's presence check fails.
			return f.prepare();
		}));
		assertEquals(1, calls.get()); f.present = true;
		assertNull(route.reservations.find(f.giver, 10)); assertNull(route.reservations.find(f.recipient, 10));
		assertNull(route.sessions.find(f.giver, 10)); assertFalse(f.finish(route.ticket));
		assertFalse(route.reservations.attempt(f.giver, 209));
		assertTrue(route.reservations.attempt(f.giver, 210));
		assertEquals(16, f.bag.countItem(Items.BREAD)); assertTrue(f.other.isEmpty());
	}

	@Test void journeyAndWaitingTicksDoNotCountTowardFortyCompleteJointTicks() {
		var f = new SettlementFoodSharingTest.Fixture(); var route = new Route(f);
		for (int tick = 0; tick < 80; tick++) assertTrue(route.enRoute(tick));
		assertTrue(route.arrive(80));
		var pair = route.sessions.find(f.giver, 80); assertNotNull(pair);
		assertNotSame(route.ticket, pair.value); assertFalse(f.finish(route.ticket));
		assertNull(route.reservations.find(f.giver, 80));
		assertTrue(route.sessions.join(pair, f.giver, 80));
		for (int tick = 81; tick < 100; tick++) assertFalse(route.pulse(pair, f.giver, tick));
		assertEquals(16, f.bag.countItem(Items.BREAD)); assertTrue(f.other.isEmpty());
		assertTrue(route.sessions.join(pair, f.recipient, 100));
		assertFalse(route.pulse(pair, f.giver, 100)); assertFalse(route.pulse(pair, f.recipient, 100));
		for (int tick = 101; tick <= 140; tick++) {
			assertFalse(route.pulse(pair, f.giver, tick));
			assertEquals(tick == 140, route.pulse(pair, f.recipient, tick));
			assertEquals(tick == 140 ? 15 : 16, f.bag.countItem(Items.BREAD));
			assertEquals(tick == 140 ? 1 : 0, f.other.countItem(Items.BREAD));
		}
		assertTrue(pair.completed()); assertFalse(route.pulse(pair, f.recipient, 140));
		assertFalse(f.finish(route.ticket));
		assertEquals(15, f.bag.countItem(Items.BREAD)); assertEquals(1, f.other.countItem(Items.BREAD));
	}

	@Test void interruptedJointTicksCannotFinishAndLeaveBothInventoriesIntact() {
		var f = new SettlementFoodSharingTest.Fixture(); var route = new Route(f);
		assertTrue(route.arrive(10)); var pair = route.sessions.find(f.giver, 10);
		assertTrue(route.sessions.join(pair, f.giver, 10)); assertTrue(route.sessions.join(pair, f.recipient, 10));
		for (int tick = 11; tick < 20; tick++) {
			assertFalse(route.pulse(pair, f.giver, tick)); assertFalse(route.pulse(pair, f.recipient, tick));
		}
		assertFalse(route.pulse(pair, f.giver, 20)); // Recipient misses a complete tick.
		assertFalse(route.pulse(pair, f.recipient, 21));
		assertFalse(pair.completed()); assertFalse(route.sessions.active(pair, 21));
		SettlementFoodSharing.cancel(pair.value);
		assertFalse(f.finish(pair.value)); assertFalse(f.finish(route.ticket));
		assertEquals(16, f.bag.countItem(Items.BREAD)); assertTrue(f.other.isEmpty());
	}
}
