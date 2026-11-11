package com.thysummer.coopcraft.civilization;

import java.util.List;
import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.net.SettlementOverviewPayload.SimulationEventDetail;

/** Displays complete decision-time gift evidence, never inferred from present-day stock. */
final class GiftReserveDetails {
	private GiftReserveDetails() {}

	static List<SimulationEventDetail> project(SimulationEvent event) {
		if (event.type() != EventType.GIFT_RECEIVED && event.type() != EventType.GIFT_REFUSED) return List.of();
		var context = event.context();
		String target = context.get("targetId");
		String unit = context.get("unit");
		if (!("normal".equals(target) || "high".equals(target))
			|| !("points".equals(unit) || "nutrition".equals(unit))) return List.of();
		Long current = nonnegativeLong(context.get("currentBefore"));
		Long goal = nonnegativeLong(context.get("goalBefore"));
		if (current == null || goal == null) return List.of();
		return List.of(
			new SimulationEventDetail("coopcraft.simulation.detail.gift_reserve_target",
				"coopcraft.command.settlement.reserves_" + target),
			new SimulationEventDetail("coopcraft.simulation.detail.gift_reserve_" + unit, current + "/" + goal));
	}

	private static Long nonnegativeLong(String value) {
		if (value == null || value.isEmpty() || value.length() > 19) return null;
		for (int i = 0; i < value.length(); i++) {
			if (value.charAt(i) < '0' || value.charAt(i) > '9') return null;
		}
		try { return Long.parseLong(value); }
		catch (NumberFormatException invalid) { return null; }
	}
}
