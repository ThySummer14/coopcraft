package com.thysummer.coopcraft.civilization.simulation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class EventLedger {
	private final List<SimulationEvent> events = new ArrayList<>();
	private final Map<Long, SimulationEvent> eventsById = new HashMap<>();
	private long nextEventId = 1;
	private long lastDay;

	long nextEventId() {
		return nextEventId;
	}

	void append(SimulationEvent event) {
		if (event.eventId() != nextEventId) {
			throw new IllegalArgumentException("Event IDs must be contiguous");
		}
		if (event.day() < lastDay) {
			throw new IllegalArgumentException("Events cannot move backwards in simulated time");
		}
		for (long causalLink : event.causalLinks()) {
			if (!eventsById.containsKey(causalLink)) {
				throw new IllegalArgumentException("Unknown causal event: " + causalLink);
			}
		}
		events.add(event);
		eventsById.put(event.eventId(), event);
		nextEventId++;
		lastDay = event.day();
	}

	void restore(List<SimulationEvent> restoredEvents) {
		if (!events.isEmpty()) {
			throw new IllegalArgumentException("Cannot restore over a nonempty ledger");
		}
		for (SimulationEvent event : restoredEvents) {
			append(event);
		}
	}

	public List<SimulationEvent> events() {
		return List.copyOf(events);
	}

	public List<SimulationEvent> eventsAtDay(long day) {
		return events.stream().filter(event -> event.day() == day).toList();
	}

	public Optional<SimulationEvent> event(long eventId) {
		return Optional.ofNullable(eventsById.get(eventId));
	}

	public List<SimulationEvent> traceAncestry(long eventId) {
		if (!eventsById.containsKey(eventId)) {
			throw new IllegalArgumentException("Unknown event: " + eventId);
		}
		Set<Long> visited = new LinkedHashSet<>();
		Deque<Long> pending = new ArrayDeque<>();
		pending.push(eventId);
		while (!pending.isEmpty()) {
			long current = pending.pop();
			if (!visited.add(current)) {
				continue;
			}
			for (long cause : eventsById.get(current).causalLinks()) {
				pending.push(cause);
			}
		}
		return visited.stream().map(eventsById::get).sorted(java.util.Comparator.comparingLong(SimulationEvent::eventId)).toList();
	}

	public List<SimulationEvent> traceOrigins(long eventId) {
		return traceAncestry(eventId).stream()
			.filter(event -> event.type() == EventType.HARVEST || event.type() == EventType.INITIAL_STOCK
				|| event.type() == EventType.EXTERNAL_IMPORT)
			.toList();
	}
}
