package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.function.Function;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.net.SettlementOverviewPayload.SimulationEventDetail;

/** Historical evidence for the explicit local lesson, not current skill or inferred relationships. */
final class TeachingEventDetails {
	private TeachingEventDetails() { }

	static boolean isFieldLesson(SimulationEvent event) {
		return event != null && event.type() == EventType.TEACHING_SESSION
			&& "minecraft_field_craft".equals(event.context().get("source"));
	}

	static List<SimulationEventDetail> project(SimulationEvent event, Function<String, String> residentName) {
		if (!isFieldLesson(event) || residentName == null || event.actors().size() != 2
			|| event.actors().get(0).equals(event.actors().get(1))) return List.of();
		var context = event.context();
		Double before = skill(context.get("skillBefore")), after = skill(context.get("skillAfter"));
		if (!"CRAFT".equals(context.get("workType")) || !"1".equals(context.get("hours"))
			|| !"next_settled_day".equals(context.get("costTiming"))
			|| before == null || after == null || after <= before) return List.of();
		return List.of(
			detail("teacher", residentName.apply(event.actors().get(0))),
			detail("learner", residentName.apply(event.actors().get(1))),
			detail("work_type", "coopcraft.simulation.worktype.CRAFT"),
			detail("skill", context.get("skillBefore") + " → " + context.get("skillAfter")),
			detail("teaching_cost", "coopcraft.simulation.teaching_cost.next_settled_day"));
	}

	private static Double skill(String value) {
		if (value == null || value.length() > 32) return null;
		try {
			double result = Double.parseDouble(value);
			return Double.isFinite(result) && result > 0 && result <= 2 ? result : null;
		} catch (NumberFormatException invalid) { return null; }
	}

	private static SimulationEventDetail detail(String key, String value) {
		String shown = value == null || value.isBlank() ? "?" : value;
		if (shown.length() > 96) shown = shown.substring(0, 95) + "…";
		return new SimulationEventDetail("coopcraft.simulation.detail." + key, shown);
	}
}
