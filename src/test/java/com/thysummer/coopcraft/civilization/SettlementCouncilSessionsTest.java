package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementCouncilSessionsTest {
	private static UUID id(int n) { return new UUID(82, n); }
	private static List<UUID> actors(int size) { return IntStream.rangeClosed(1, size).mapToObj(SettlementCouncilSessionsTest::id).toList(); }
	@Test void boundedAttendanceAndWholeAdultPopulationDetermineQuorum() {
		assertFalse(SettlementCouncilSessions.quorum(1, 2));
		assertFalse(SettlementCouncilSessions.quorum(5, 5));
		assertFalse(SettlementCouncilSessions.quorum(4, 3));
		assertTrue(SettlementCouncilSessions.quorum(2, 5));
		assertFalse(SettlementCouncilSessions.quorum(2, 6));
		assertTrue(SettlementCouncilSessions.quorum(4, 11));
		assertFalse(SettlementCouncilSessions.quorum(4, 12));
		assertFalse(SettlementCouncilSessions.quorum(4, Integer.MAX_VALUE));
	}
	@Test void campAndEveryActorAreExclusiveUntilCancellation() {
		var registry = new SettlementCouncilSessions<String>();
		var group = registry.reserve(id(10), actors(3), 5, 0, "meeting");
		assertNotNull(group); assertTrue(registry.claimed(id(2)));
		assertNull(registry.reserve(id(10), List.of(id(4), id(5)), 5, 0, "same camp"));
		assertNull(registry.reserve(id(11), List.of(id(3), id(4)), 5, 0, "same actor"));
		assertNull(registry.reserve(id(11), List.of(id(4), id(4)), 5, 0, "duplicate"));
		registry.cancelResident(id(2));
		for (UUID actor : actors(3)) assertFalse(registry.claimed(actor));
		assertTrue(group.closed); assertFalse(group.completed);
		assertNotNull(registry.reserve(id(10), List.of(id(3), id(4)), 5, 0, "replacement"));
	}
	@Test void twoThreeAndFourActorsMustAllJoinAndIndependentlyPulseForTwoHundredTicks() {
		for (int size = 2; size <= 4; size++) {
			var registry = new SettlementCouncilSessions<String>(); var ids = actors(size);
			var group = registry.reserve(id(10), ids, size, 0, "meeting");
			assertEquals("wait", group.phase());
			assertFalse(registry.join(group, id(99), 0));
			for (int index = 0; index < size - 1; index++) registry.join(group, ids.get(index), 0);
			assertFalse(registry.pulse(group, ids.getFirst(), 0, true)); assertEquals(0, group.together);
			registry.join(group, ids.getLast(), 0);
			for (int tick = 1; tick <= 200; tick++) {
				for (int index = 0; index < size; index++) {
					assertEquals(tick == 200 && index == size - 1, registry.pulse(group, ids.get(index), tick, true));
					assertFalse(registry.pulse(group, ids.get(index), tick, true));
				}
				assertEquals(tick == 200 ? "closed" : "discussing", group.phase());
			}
			assertEquals(200, group.together); assertTrue(group.completed);
			for (UUID actor : ids) assertFalse(registry.claimed(actor));
		}
	}
	@Test void unsafeMissingPartialTickOrGapCancelsWithoutCompletion() {
		for (String failure : List.of("unsafe", "missing", "gap", "unsafe-wait")) {
			var registry = new SettlementCouncilSessions<String>(); var group = registry.reserve(id(10), actors(2), 2, 0, "meeting");
			registry.join(group, id(1), 0);
			if (!failure.equals("unsafe-wait")) registry.join(group, id(2), 0);
			registry.pulse(group, id(1), 1, true);
			if (!failure.equals("missing")) registry.pulse(group, id(2), 1, true);
			assertFalse(registry.pulse(group, id(1), failure.equals("gap") ? 3 : 2, !failure.startsWith("unsafe")));
			assertTrue(group.closed); assertFalse(group.completed);
		}
	}
	@Test void ttlRewindAndClockOverflowReleaseEveryClaim() {
		var registry = new SettlementCouncilSessions<String>(); var group = registry.reserve(id(10), actors(4), 4, 10, "meeting");
		assertTrue(registry.active(group, 609)); assertFalse(registry.active(group, 610));
		group = registry.reserve(id(10), actors(4), 4, 610, "meeting");
		assertFalse(registry.active(group, 609)); assertTrue(registry.groups().isEmpty());
		assertNull(registry.reserve(id(10), actors(2), 2, Long.MAX_VALUE, "overflow"));
	}
	@Test void capacityAndCampRetryAreBounded() {
		var registry = new SettlementCouncilSessions<String>();
		for (int i = 0; i < 128; i++) assertNotNull(registry.reserve(new UUID(i, 0), List.of(new UUID(i, 1), new UUID(i, 2)), 2, 0, "meeting"));
		assertNull(registry.reserve(id(1000), actors(2), 2, 0, "full"));
		assertTrue(registry.attempt(id(1000), 0)); assertFalse(registry.attempt(id(1000), 199)); assertTrue(registry.attempt(id(1000), 200));
		assertTrue(registry.attempt(id(1000), 100)); assertTrue(registry.groups().isEmpty());
	}
}
