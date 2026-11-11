package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.net.SettlementOverviewPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/** Bounded, read-only ledger projection. Never creates a simulation or advances its clock. */
final class SettlementEvents {
	static final int DEFAULT_COUNT = 5;
	static final int MAX_COUNT = 12;
	static final int MAX_CAUSES = 4;

	private SettlementEvents() {}

	static List<Component> messages(ServerLevel level, SettlementSavedData data, Settlement settlement, int count) {
		if (count < 1 || count > MAX_COUNT) {
			throw new IllegalArgumentException("Event count must be between 1 and " + MAX_COUNT);
		}
		// Service delegates to EventLedger.recentEvents: copy only this bounded tail, even when disabled.
		var events = SettlementSimulationService.recentEvents(data, settlement, count);
		if (events.isEmpty()) {
			return List.of(message("events_empty", settlement.name()));
		}
		List<Component> lines = new ArrayList<>();
		lines.add(message("events_header", settlement.name(), events.size()));
		for (var event : events) {
			lines.add(row(event, Civilization.toSimulationEventInfo(level, data, settlement, event)));
		}
		return List.copyOf(lines);
	}

	/** One chat component per event keeps all six overview details together and copyable. */
	static Component row(SimulationEvent event, SettlementOverviewPayload.SimulationEventInfo info) {
		Component resource = info.resourceType().equals("NONE") ? message("events_none")
			: Component.translatable("coopcraft.simulation.resource." + info.resourceType());
		var row = message("events_row", event.eventId(), info.day(),
			Component.translatable("coopcraft.simulation.event." + info.eventType()), resource, info.quantity(), causes(event));
		for (var detail : info.details()) {
			String value = detail.value();
			Component localized = value.startsWith("coopcraft.") || value.startsWith("item.") || value.startsWith("block.")
				? Component.translatable(value) : Component.literal(value);
			row.append(" | ").append(Component.translatable(detail.label(), localized));
		}
		return row;
	}

	static Component causes(SimulationEvent event) {
		if (event.causalLinks().isEmpty()) return message("events_none");
		var result = Component.literal(event.causalLinks().stream().limit(MAX_CAUSES)
			.map(id -> "#" + id).collect(Collectors.joining(", ")));
		int more = event.causalLinks().size() - MAX_CAUSES;
		if (more > 0) result.append(message("events_more_causes", more));
		return result;
	}

	private static net.minecraft.network.chat.MutableComponent message(String key, Object... arguments) {
		return Component.translatable("coopcraft.command.settlement." + key, arguments);
	}
}
