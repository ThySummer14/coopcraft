package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.net.SettlementOverviewPayload.SimulationEventDetail;

/** Projects recorded refusal facts only, without consulting current stock or hunger. */
final class FoodRefusalDetails {
	private FoodRefusalDetails() {
	}

	static List<SimulationEventDetail> project(SimulationEvent event) {
		Map<String, String> context = event.context();
		if (event.type() != EventType.SHARE_REFUSED
			|| !"daily-unit-cap-exhausted".equals(context.get("reason"))) {
			return List.of();
		}
		List<SimulationEventDetail> details = new ArrayList<>();
		add(details, "reason", "coopcraft.simulation.reason.daily-unit-cap-exhausted");
		if (context.containsKey("allocatedUnits") && context.containsKey("cap")) {
			String label = "item_units".equals(context.get("allocationBasis")) ? "allocation_units" : "allocation";
			add(details, label, context.get("allocatedUnits") + "/" + context.get("cap"));
		} else {
			add(details, "cap", context.get("cap"));
		}
		add(details, "hunger_at_refusal", context.get("hunger"));
		add(details, "needed_nutrition", context.get("neededNutrition"));
		add(details, "available_nutrition", context.get("availableNutrition"));
		return List.copyOf(details);
	}

	private static void add(List<SimulationEventDetail> details, String label, String value) {
		// Historical or incomplete context must not be replaced with guessed values.
		if (value != null && !value.isBlank() && value.length() <= 96) {
			details.add(new SimulationEventDetail("coopcraft.simulation.detail." + label, value));
		}
	}
}
