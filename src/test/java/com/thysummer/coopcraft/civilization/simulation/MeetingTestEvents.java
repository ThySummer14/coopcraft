package com.thysummer.coopcraft.civilization.simulation;

import java.util.List;
import java.util.Map;

/** Test-only access to the ledger writer for persisted legacy and malformed evidence fixtures. */
public final class MeetingTestEvents {
	private MeetingTestEvents() {}

	public static long record(SimulationWorld world, EventType type, List<String> actors,
		Map<String, String> context, List<Long> causes) {
		return world.recordSocialEvent(type, actors, context, causes);
	}
}
