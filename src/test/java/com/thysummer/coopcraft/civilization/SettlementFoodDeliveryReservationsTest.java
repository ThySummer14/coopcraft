package com.thysummer.coopcraft.civilization;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementFoodDeliveryReservationsTest {
	private static final UUID A = id(1), B = id(2), C = id(3);
	private static UUID id(long n) { return new UUID(93, n); }

	@Test void bothRolesAreExclusiveAndCancellationKeepsCooldown() {
		var reservations = new SettlementFoodDeliveryReservations<String>();
		var lease = reservations.reserve(A, B, 0, "food");
		assertNotNull(lease);
		assertEquals(A, lease.giver); assertEquals(B, lease.recipient); assertEquals("food", lease.value);
		assertSame(lease, reservations.find(A, 0)); assertSame(lease, reservations.find(B, 0));
		assertNull(reservations.reserve(C, A, 0, "conflict"));
		assertNull(reservations.reserve(B, C, 0, "conflict"));
		assertNull(reservations.reserve(A, A, 0, "self"));
		assertNull(reservations.reserve(null, C, 0, "invalid"));
		assertNull(reservations.reserve(C, null, 0, "invalid"));
		assertFalse(reservations.attempt(null, 0));
		reservations.cancelResident(B);
		assertNull(reservations.find(A, 0)); assertNull(reservations.find(B, 0));
		assertFalse(reservations.attempt(A, 199)); assertFalse(reservations.attempt(B, 199));
		assertNotNull(reservations.reserve(A, B, 200, "retry"));
	}

	@Test void transferOwnsBothEndsAndInvokesPromotionExactlyOnceEvenReentrantly() {
		var reservations = new SettlementFoodDeliveryReservations<String>();
		var sessions = new SettlementFoodSharingSessions<String>();
		var lease = reservations.reserve(A, B, 0, "food");
		var calls = new AtomicInteger();
		assertTrue(reservations.transfer(lease, 10, () -> {
			calls.incrementAndGet();
			assertTrue(reservations.active(lease, 10));
			assertSame(lease, reservations.find(B, 10));
			assertNull(reservations.reserve(C, B, 10, "competing"));
			assertFalse(reservations.transfer(lease, 10, () -> { calls.incrementAndGet(); return true; }));
			return sessions.reserve(A, B, 10, lease.value) != null;
		}));
		assertEquals(1, calls.get());
		assertNotNull(sessions.find(A, 10));
		assertNull(reservations.find(A, 10)); assertNull(reservations.find(B, 10));
		assertFalse(reservations.transfer(lease, 10, () -> { calls.incrementAndGet(); return true; }));
		assertEquals(1, calls.get());
		assertFalse(reservations.attempt(B, 209)); assertTrue(reservations.attempt(B, 210));
	}

	@Test void promotionCannotReportSuccessAfterCallbackRevokesOwnership() {
		for (String revocation : new String[]{"cancel", "expire", "rewind"}) {
			var reservations = new SettlementFoodDeliveryReservations<String>();
			var lease = reservations.reserve(A, B, 100, "food");
			assertFalse(reservations.transfer(lease, 110, () -> {
				switch (revocation) {
					case "cancel" -> reservations.cancel(lease);
					case "expire" -> assertFalse(reservations.active(lease, 260));
					case "rewind" -> assertFalse(reservations.active(lease, 10));
				}
				return true;
			}));
			long closedAt = revocation.equals("expire") ? 260 : revocation.equals("rewind") ? 10 : 110;
			assertNull(reservations.find(A, closedAt)); assertNull(reservations.find(B, closedAt));
			assertFalse(reservations.transfer(lease, closedAt, () -> fail("revoked token")));
			assertFalse(reservations.attempt(A, closedAt + 199));
			assertTrue(reservations.attempt(A, closedAt + 200));
		}
	}

	@Test void failedAndThrowingPromotionsConsumeOwnershipAndKeepBackoff() {
		for (boolean throwing : new boolean[]{false, true}) {
			var reservations = new SettlementFoodDeliveryReservations<String>();
			var lease = reservations.reserve(A, B, 0, "food");
			if (throwing) assertThrows(IllegalStateException.class, () -> reservations.transfer(lease, 20, () -> {
				throw new IllegalStateException("promotion failed");
			}));
			else assertFalse(reservations.transfer(lease, 20, () -> false));
			assertNull(reservations.find(A, 20)); assertNull(reservations.find(B, 20));
			assertFalse(reservations.transfer(lease, 20, () -> fail("must not retry promotion")));
			assertFalse(reservations.attempt(A, 219)); assertTrue(reservations.attempt(A, 220));
		}
	}

	@Test void ttlBoundaryInvalidatesBothEndsAndStaleOrForeignTokensCannotCancelReplacement() {
		var reservations = new SettlementFoodDeliveryReservations<String>();
		var foreign = new SettlementFoodDeliveryReservations<String>();
		var lease = reservations.reserve(A, B, 10, "food");
		foreign.cancel(lease);
		assertFalse(foreign.transfer(lease, 10, () -> fail("foreign token")));
		assertTrue(reservations.active(lease, 169));
		assertFalse(reservations.transfer(lease, 170, () -> fail("expired token")));
		assertNull(reservations.find(B, 170));
		assertFalse(reservations.attempt(A, 369));
		var replacement = reservations.reserve(A, B, 370, "new");
		assertNotNull(replacement);
		reservations.cancel(lease);
		assertFalse(reservations.transfer(lease, 370, () -> fail("stale token")));
		assertTrue(reservations.active(replacement, 370));
	}

	@Test void rewindClosesAllLeasesAndRebasesExistingCooldowns() {
		var reservations = new SettlementFoodDeliveryReservations<String>();
		var lease = reservations.reserve(A, B, 100, "food");
		var other = reservations.reserve(C, id(4), 100, "other");
		reservations.cancel(other);
		assertFalse(reservations.active(lease, 10));
		assertNull(reservations.find(B, 10));
		assertFalse(reservations.attempt(A, 209)); assertFalse(reservations.attempt(C, 209));
		assertTrue(reservations.attempt(A, 210)); assertTrue(reservations.attempt(C, 210));
	}

	@Test void capacityCountsCooldownsWithoutEvictingThemEarly() {
		var reservations = new SettlementFoodDeliveryReservations<String>();
		for (int n = 0; n < SettlementFoodDeliveryReservations.LIMIT; n++)
			assertNotNull(reservations.reserve(id(n * 2), id(n * 2 + 1), 0, "food"));
		assertNull(reservations.reserve(id(1000), id(1001), 0, "full"));
		for (int n = 0; n < SettlementFoodDeliveryReservations.LIMIT; n++) reservations.cancelResident(id(n * 2));
		assertFalse(reservations.attempt(id(1000), 199));
		assertNull(reservations.reserve(id(1000), id(1001), 199, "still full"));
		assertNotNull(reservations.reserve(id(1000), id(1001), 200, "free"));
	}

	@Test void negativeTimeClosesLeasesAndOverflowCannotCreateThem() {
		var reservations = new SettlementFoodDeliveryReservations<String>();
		var lease = reservations.reserve(A, B, 100, "food");
		assertFalse(reservations.active(lease, -1));
		assertNull(reservations.find(B, 0));
		assertFalse(reservations.attempt(A, 199)); assertTrue(reservations.attempt(A, 200));
		assertNull(reservations.reserve(A, B, -1, "negative"));
		long lastSafe = Long.MAX_VALUE - SettlementFoodDeliveryReservations.TTL - SettlementFoodDeliveryReservations.RETRY;
		assertNotNull(reservations.reserve(A, B, lastSafe, "boundary"));
		assertFalse(reservations.attempt(C, lastSafe + 1));
		assertNull(reservations.reserve(C, id(4), Long.MAX_VALUE, "overflow"));
		assertNull(reservations.find(A, Long.MAX_VALUE));
	}
}
