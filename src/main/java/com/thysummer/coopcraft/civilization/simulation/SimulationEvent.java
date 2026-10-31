package com.thysummer.coopcraft.civilization.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

public record SimulationEvent(
	long eventId,
	long day,
	EventType type,
	List<String> actors,
	List<ResourceFlow> inputs,
	List<ResourceFlow> outputs,
	Map<String, String> context,
	List<Long> causalLinks
) {
	public SimulationEvent {
		if (eventId <= 0 || day < 0) {
			throw new IllegalArgumentException("Event ID must be positive and day nonnegative");
		}
		Objects.requireNonNull(type, "type");
		actors = List.copyOf(actors);
		inputs = List.copyOf(inputs);
		outputs = List.copyOf(outputs);
		context = Collections.unmodifiableMap(new LinkedHashMap<>(context));
		causalLinks = Collections.unmodifiableList(new ArrayList<>(new TreeSet<>(causalLinks)));
		for (long causalLink : causalLinks) {
			if (causalLink <= 0 || causalLink >= eventId) {
				throw new IllegalArgumentException("Causal links must reference earlier events");
			}
		}
	}
}
