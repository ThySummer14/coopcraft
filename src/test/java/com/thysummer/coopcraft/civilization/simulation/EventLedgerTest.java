package com.thysummer.coopcraft.civilization.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventLedgerTest {
	@Test
	void emptyLedgerAndRecentLimitBoundaries() {
		EventLedger ledger = new EventLedger();
		assertTrue(ledger.events().isEmpty());
		assertTrue(ledger.eventsAtDay(0).isEmpty());
		assertTrue(ledger.eventsAtDay(-1).isEmpty());
		assertTrue(ledger.lastEvent().isEmpty());
		assertTrue(ledger.recentEvents(0).isEmpty());
		assertTrue(ledger.recentEvents(Integer.MAX_VALUE).isEmpty());
		assertThrows(IllegalArgumentException.class, () -> ledger.recentEvents(-1));
		assertThrows(IllegalArgumentException.class, () -> ledger.recentEvents(Integer.MIN_VALUE));

		SimulationEvent first = event(1, 0);
		SimulationEvent second = event(2, 8);
		ledger.append(first);
		ledger.append(second);
		assertEquals(List.of(), ledger.recentEvents(0));
		assertEquals(List.of(second), ledger.recentEvents(1));
		assertEquals(List.of(first, second), ledger.recentEvents(2));
		assertEquals(List.of(first, second), ledger.recentEvents(3));
		assertEquals(List.of(first, second), ledger.recentEvents(Integer.MAX_VALUE));
		assertEquals(second, ledger.lastEvent().orElseThrow());
	}

	@Test
	void sparseDaysAndSameDayEventsRetainAppendOrder() {
		EventLedger ledger = new EventLedger();
		List<SimulationEvent> expected = List.of(event(1, 0), event(2, 50), event(3, 50), event(4, Long.MAX_VALUE));
		expected.forEach(ledger::append);
		assertEquals(expected, ledger.events());
		assertEquals(List.of(expected.get(0)), ledger.eventsAtDay(0));
		assertEquals(expected.subList(1, 3), ledger.eventsAtDay(50));
		assertTrue(ledger.eventsAtDay(49).isEmpty());
		assertTrue(ledger.eventsAtDay(51).isEmpty());
		assertEquals(List.of(expected.getLast()), ledger.eventsAtDay(Long.MAX_VALUE));
	}

	@Test
	void rejectedAppendsLeaveAllIndexesAndSequenceUnchanged() {
		EventLedger ledger = new EventLedger();
		SimulationEvent first = event(1, 5);
		ledger.append(first);
		assertThrows(IllegalArgumentException.class, () -> ledger.append(event(3, 6)));
		assertThrows(IllegalArgumentException.class, () -> ledger.append(event(1, 5)));
		assertThrows(IllegalArgumentException.class, () -> ledger.append(event(2, 4)));
		assertEquals(2, ledger.nextEventId());
		assertEquals(List.of(first), ledger.events());
		assertEquals(List.of(first), ledger.eventsAtDay(5));
		assertTrue(ledger.eventsAtDay(4).isEmpty());
		assertTrue(ledger.eventsAtDay(6).isEmpty());
		assertTrue(ledger.event(2).isEmpty());
		assertTrue(ledger.event(3).isEmpty());
		assertEquals(first, ledger.lastEvent().orElseThrow());

		SimulationEvent second = event(2, 5);
		ledger.append(second);
		assertEquals(List.of(first, second), ledger.eventsAtDay(5));
		assertEquals(second, ledger.event(2).orElseThrow());
	}

	@Test
	void restoreBuildsIndexesAndAllowsFurtherAppend() {
		EventLedger ledger = new EventLedger();
		List<SimulationEvent> restored = List.of(event(1, 2), event(2, 2), event(3, 70));
		ledger.restore(restored);
		assertEquals(restored, ledger.events());
		assertEquals(restored.subList(0, 2), ledger.eventsAtDay(2));
		assertEquals(List.of(restored.getLast()), ledger.eventsAtDay(70));
		assertTrue(ledger.eventsAtDay(3).isEmpty());
		assertEquals(restored.getLast(), ledger.lastEvent().orElseThrow());
		assertEquals(restored.subList(1, 3), ledger.recentEvents(2));
		for (SimulationEvent event : restored) {
			assertEquals(event, ledger.event(event.eventId()).orElseThrow());
		}
		assertThrows(IllegalArgumentException.class, () -> ledger.restore(restored));
		assertEquals(restored, ledger.events());
		SimulationEvent next = event(4, 70);
		ledger.append(next);
		assertEquals(List.of(restored.getLast(), next), ledger.eventsAtDay(70));
		assertEquals(next, ledger.lastEvent().orElseThrow());
		assertEquals(5, ledger.nextEventId());
	}

	@Test
	void snapshotsAreUnmodifiableAndDetachedFromLaterAppends() {
		EventLedger ledger = new EventLedger();
		List<SimulationEvent> emptyDay = ledger.eventsAtDay(3);
		SimulationEvent first = event(1, 3);
		ledger.append(first);
		List<SimulationEvent> all = ledger.events();
		List<SimulationEvent> day = ledger.eventsAtDay(3);
		List<SimulationEvent> recent = ledger.recentEvents(1);
		for (List<SimulationEvent> snapshot : List.of(emptyDay, all, day, recent, ledger.recentEvents(0))) {
			assertThrows(UnsupportedOperationException.class, () -> snapshot.add(first));
		}
		ledger.append(event(2, 3));
		ledger.append(event(3, 4));
		assertEquals(List.of(), emptyDay);
		assertEquals(List.of(first), all);
		assertEquals(List.of(first), day);
		assertEquals(List.of(first), recent);
	}

	@Test
	void largeFixedHistoryMatchesLinearReferenceBeforeAndAfterRestore() {
		List<SimulationEvent> reference = new ArrayList<>();
		EventLedger ledger = new EventLedger();
		for (int index = 0; index < 30_000; index++) {
			SimulationEvent event = event(index + 1L, (index / 37L) * 11L);
			reference.add(event);
			ledger.append(event);
		}
		EventLedger restored = new EventLedger();
		restored.restore(reference);
		for (EventLedger candidate : List.of(ledger, restored)) {
			assertEquals(reference, candidate.events());
			assertEquals(reference.getLast(), candidate.lastEvent().orElseThrow());
			for (long day = -1; day <= reference.getLast().day() + 1; day += 7) {
				long queriedDay = day;
				assertEquals(reference.stream().filter(event -> event.day() == queriedDay).toList(),
					candidate.eventsAtDay(day));
			}
			for (int limit : new int[] {0, 1, 37, 600, 29_999, 30_000, 30_001, Integer.MAX_VALUE}) {
				assertEquals(reference.subList(Math.max(0, reference.size() - limit), reference.size()),
					candidate.recentEvents(limit));
			}
		}
	}

	private static SimulationEvent event(long id, long day) {
		return new SimulationEvent(id, day, EventType.HARVEST, List.of(), List.of(), List.of(), Map.of(),
			id == 1 ? List.of() : List.of(id - 1));
	}
}
