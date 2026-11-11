package com.thysummer.coopcraft.civilization;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementFoodSharingSessionsTest {
	private static final UUID A = id(1), B = id(2), C = id(3);
	private static UUID id(long n) { return new UUID(81, n); }

	@Test void rolesAreExclusiveAndReleasedIdentitiesKeepTheirCooldown() {
		var sessions = new SettlementFoodSharingSessions<String>();
		var pair = sessions.reserve(A, B, 0, "notice");
		assertSame(pair, sessions.find(B, 0));
		assertNull(sessions.reserve(C, A, 0, "other"));
		assertNull(sessions.reserve(B, C, 0, "other"));
		assertNull(sessions.reserve(C, C, 0, "self"));
		sessions.cancelResident(A);
		assertNull(sessions.find(B, 0));
		assertFalse(sessions.attempt(B, 199));
		assertNull(sessions.reserve(C, A, 199, "retry"));
		assertTrue(sessions.attempt(A, 200));
		assertNotNull(sessions.reserve(C, A, 200, "retry"));
	}

	@Test void finalJoinTickIsExcludedAndEachFollowingTickNeedsBothIndependentPulses() {
		var sessions = new SettlementFoodSharingSessions<String>();
		var pair = sessions.reserve(A, B, 0, "notice");
		assertFalse(sessions.join(pair, C, 0));
		assertTrue(sessions.join(pair, A, 0));
		assertTrue(pair.joined(A)); assertFalse(pair.joined(B));
		assertFalse(sessions.pulse(pair, A, 0, true));
		assertTrue(sessions.join(pair, B, 10));
		assertFalse(sessions.pulse(pair, A, 10, true));
		assertFalse(sessions.pulse(pair, B, 10, true));
		for (int tick = 11; tick <= 50; tick++) {
			assertTrue(sessions.join(pair, A, tick)); // Repeated joins cannot reset progress.
			assertFalse(sessions.pulse(pair, A, tick, true));
			assertFalse(sessions.pulse(pair, A, tick, true));
			assertEquals(tick == 50, sessions.pulse(pair, B, tick, true));
			assertFalse(sessions.pulse(pair, B, tick, true));
		}
		assertTrue(pair.completed()); assertEquals("closed", pair.phase());
		assertNull(sessions.find(A, 50)); assertNull(sessions.find(B, 50));
		assertNull(sessions.reserve(A, B, 249, "too soon"));
		assertNotNull(sessions.reserve(A, B, 250, "retry"));
	}

	@Test void missedFirstPulsePartialTickGapUnsafeAndReversedClockAllCancel() {
		for (String failure : new String[]{"first", "partial", "gap", "unsafe", "rewind"}) {
			var sessions = new SettlementFoodSharingSessions<String>();
			var pair = sessions.reserve(A, B, 10, "notice");
			sessions.join(pair, A, 10); sessions.join(pair, B, 10);
			if (!failure.equals("first")) sessions.pulse(pair, A, 11, true);
			if (!failure.equals("first") && !failure.equals("partial")) sessions.pulse(pair, B, 11, true);
			long next = failure.equals("gap") ? 13 : failure.equals("rewind") ? 10 : 12;
			assertFalse(sessions.pulse(pair, A, next, !failure.equals("unsafe")));
			assertEquals("closed", pair.phase()); assertFalse(pair.completed());
		}
	}

	@Test void unsafeBeforeFinalJoinOrOnDuplicatePulseCancels() {
		for (boolean beforeJoin : new boolean[]{true, false}) {
			var sessions = new SettlementFoodSharingSessions<String>();
			var pair = sessions.reserve(A, B, 0, "notice");
			sessions.join(pair, A, 0);
			if (!beforeJoin) {
				sessions.join(pair, B, 0);
				sessions.pulse(pair, A, 1, true); sessions.pulse(pair, B, 1, true);
			}
			assertFalse(sessions.pulse(pair, A, beforeJoin ? 0 : 1, false));
			assertEquals("closed", pair.phase()); assertFalse(pair.completed());
		}
	}

	@Test void ttlOverflowForeignAndStaleTokensCannotMutateLiveOwnership() {
		var sessions = new SettlementFoodSharingSessions<String>();
		var foreign = new SettlementFoodSharingSessions<String>();
		var pair = sessions.reserve(A, B, 10, "notice");
		assertFalse(foreign.join(pair, A, 10)); foreign.cancel(pair);
		assertTrue(sessions.active(pair, 209)); assertFalse(sessions.active(pair, 210));
		var replacement = sessions.reserve(A, B, 410, "new");
		sessions.cancel(pair);
		assertFalse(sessions.pulse(pair, A, 410, true));
		assertTrue(sessions.active(replacement, 410));
		assertNull(sessions.reserve(C, id(4), Long.MAX_VALUE, "overflow"));
	}

	@Test void capacityIncludesCooldownIdentitiesWithoutEarlyEviction() {
		var sessions = new SettlementFoodSharingSessions<String>();
		for (int n = 0; n < 128; n++) {
			var pair = sessions.reserve(id(n * 2), id(n * 2 + 1), 0, "notice");
			assertNotNull(pair);
		}
		assertEquals(128, sessions.pairs().size());
		assertNull(sessions.reserve(id(1000), id(1001), 0, "full"));
		for (var pair : sessions.pairs()) sessions.cancel(pair);
		for (int n = 1000; n < 1500; n++) assertFalse(sessions.attempt(id(n), 199));
		assertFalse(sessions.attempt(id(0), 199));
		assertNotNull(sessions.reserve(id(1000), id(1001), 200, "free"));
	}

	@Test void rejectsNegativeTimeAndSaturatesExpiryWithoutOverflow() {
		var sessions = new SettlementFoodSharingSessions<String>();
		assertFalse(sessions.attempt(A, -1));
		assertNull(sessions.reserve(A, B, -1, "negative"));
		long lastSafe = Long.MAX_VALUE - SettlementFoodSharingSessions.TTL - SettlementFoodSharingSessions.RETRY;
		assertTrue(sessions.attempt(A, lastSafe));
		var pair = sessions.reserve(A, B, lastSafe, "boundary");
		assertNotNull(pair);
		assertFalse(sessions.attempt(C, lastSafe + 1));
		assertNull(sessions.reserve(C, id(4), lastSafe + 1, "overflow"));
		assertFalse(sessions.active(pair, Long.MAX_VALUE));
		assertTrue(sessions.pairs().isEmpty());
		assertFalse(pair.completed());
	}

	@Test void rewindClosesAllLeasesAndStartsFreshCooldownWithoutRevivingOldTokens() {
		var sessions = new SettlementFoodSharingSessions<String>();
		var pair = sessions.reserve(A, B, 100, "old");
		assertFalse(sessions.active(pair, 10));
		assertFalse(sessions.attempt(A, 209));
		var replacement = sessions.reserve(A, B, 210, "new");
		assertNotNull(replacement);
		sessions.cancel(pair);
		assertTrue(sessions.active(replacement, 210));
	}

	@Test void unjoinedInvitationCannotBeOverwrittenEvenAtReservationCooldownBoundary() {
		var sessions = new SettlementFoodSharingSessions<String>();
		var pair = sessions.reserve(A, B, 0, "first");
		assertFalse(sessions.attempt(A, 199));
		assertNull(sessions.reserve(A, C, 199, "overwrite"));
		assertSame(pair, sessions.find(A, 199));
		assertSame(pair, sessions.find(B, 199));
		assertNull(sessions.find(C, 199));
		assertFalse(sessions.active(pair, 200));
		assertNull(sessions.reserve(A, C, 399, "cooling"));
		assertNotNull(sessions.reserve(A, C, 400, "new"));
	}

	@Test void freshRegistryDoesNotRetainSessionOrCooldownAcrossReload() {
		var original = new SettlementFoodSharingSessions<String>();
		var pair = original.reserve(A, B, 0, "old");
		original.cancel(pair);
		var restored = new SettlementFoodSharingSessions<String>();
		assertTrue(restored.pairs().isEmpty());
		assertNotNull(restored.reserve(A, B, 1, "new"));
	}

	@Test void savedDataCoordinationDoesNotDirtyOrPersistAndRemovalCancels() {
		var data = new SettlementSavedData();
		CompoundTag before = data.save(new CompoundTag(), null);
		data.setDirty(false);
		var sessions = data.foodSharingSessions();
		var pair = sessions.reserve(A, B, 0, null);
		sessions.join(pair, A, 0); sessions.join(pair, B, 0);
		sessions.pulse(pair, A, 1, true); sessions.pulse(pair, B, 1, true);
		assertEquals(before, data.save(new CompoundTag(), null));
		assertFalse(data.isDirty());
		var restored = SettlementSavedData.load(data.save(new CompoundTag(), null), null);
		assertTrue(restored.foodSharingSessions().pairs().isEmpty());
		data.removeMember(A);
		assertFalse(sessions.active(pair, 1));
		assertNull(sessions.find(B, 1));
		assertFalse(data.isDirty());
		assertEquals(before, data.save(new CompoundTag(), null));
	}
}
