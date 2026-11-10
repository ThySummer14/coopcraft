package com.thysummer.coopcraft.civilization;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementTeachingSessionsTest {
	private static final UUID A = new UUID(80, 1), B = new UUID(80, 2), C = new UUID(80, 3);
	@Test void bothRolesAreExclusiveAndCancellationReleasesBoth() {
		var registry = new SettlementTeachingSessions<String>();
		var pair = registry.reserve(A, B, 0, "lesson");
		assertNull(registry.reserve(C, A, 0, "other"));
		assertNull(registry.reserve(B, C, 0, "other"));
		assertNull(registry.reserve(C, C, 0, "self"));
		assertSame(pair, registry.find(A, 0)); assertSame(pair, registry.find(B, 0));
		registry.cancelResident(B);
		assertNull(registry.find(A, 0)); assertNull(registry.find(B, 0));
		assertNotNull(registry.reserve(C, A, 0, "new"));
	}
	@Test void requiresBothJoiningAndTwoIndependentPulsesPerTickAndFinishesOnce() {
		var registry = new SettlementTeachingSessions<String>(); var pair = registry.reserve(A, B, 0, "lesson");
		assertFalse(registry.join(pair, C, 0)); assertTrue(registry.join(pair, A, 0));
		assertFalse(registry.pulse(pair, A, 0, true)); assertEquals(0, pair.together);
		assertTrue(registry.join(pair, B, 0));
		for (int tick = 0; tick < 200; tick++) {
			assertFalse(registry.pulse(pair, A, tick, true));
			assertFalse(registry.pulse(pair, A, tick, true));
			assertEquals(tick == 199, registry.pulse(pair, B, tick, true));
			assertFalse(registry.pulse(pair, B, tick, true));
		}
		assertTrue(pair.completed); assertTrue(pair.closed); assertEquals(200, pair.together);
		assertNull(registry.find(A, 200)); assertNull(registry.find(B, 200));
	}
	@Test void waitingDoesNotAwardAndStartedLessonCancelsOnUnsafeOrMissingTick() {
		for (boolean missing : new boolean[]{true, false}) {
			var registry = new SettlementTeachingSessions<String>(); var pair = registry.reserve(A, B, 0, "lesson");
			registry.join(pair, A, 0); registry.join(pair, B, 0);
			registry.pulse(pair, A, 0, false); registry.pulse(pair, B, 0, true);
			assertEquals(0, pair.together); assertFalse(pair.closed);
			registry.pulse(pair, B, 1, true); registry.pulse(pair, A, 1, true);
			assertEquals(1, pair.together);
			assertFalse(registry.pulse(pair, A, missing ? 3 : 2, missing));
			assertTrue(pair.closed); assertFalse(pair.completed);
		}
	}
	@Test void expiryAndClockRewindCannotCarryProgressOrOwnership() {
		var registry = new SettlementTeachingSessions<String>(); var pair = registry.reserve(A, B, 10, "lesson");
		assertTrue(registry.active(pair, 1209)); assertFalse(registry.active(pair, 1210));
		pair = registry.reserve(A, B, 1210, "new");
		assertFalse(registry.active(pair, 1200)); assertNull(registry.find(B, 1200));
	}
	@Test void capacityAndSearchCooldownAreBoundedAndResetAfterRewind() {
		var registry = new SettlementTeachingSessions<String>();
		for (int i = 0; i < 128; i++) assertNotNull(registry.reserve(new UUID(i, 1), new UUID(i, 2), 0, "lesson"));
		assertNull(registry.reserve(new UUID(1000, 1), new UUID(1000, 2), 0, "full"));
		assertTrue(registry.attempt(A, 0)); assertFalse(registry.attempt(A, 199)); assertTrue(registry.attempt(A, 200));
		assertTrue(registry.attempt(A, 100)); assertTrue(registry.pairs().isEmpty());
	}
}
