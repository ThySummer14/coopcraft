package com.thysummer.coopcraft.civilization;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.ResourceFlow;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;

/** Read-only observations of the existing unit allowance and recorded food nutrition. */
public final class SettlementRations {
	private SettlementRations() {
	}

	public static List<Snapshot> inspect(SettlementSavedData data, Settlement settlement) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null) {
			return List.of();
		}
		long day = state.world().day();
		String campId = state.campOwner().id();
		Map<String, Received> received = new HashMap<>();
		// One indexed day lookup and one aggregation, rather than copying the ledger per resident.
		for (SimulationEvent event : state.world().ledger().eventsAtDay(day)) {
			if (event.type() != EventType.SHARE || !"camp-storage".equals(event.context().get("allocation"))
				|| event.actors().size() != 2 || !campId.equals(event.actors().get(1))) {
				continue;
			}
			Received total = received.computeIfAbsent(event.actors().getFirst(), ignored -> new Received());
			for (ResourceFlow output : event.outputs()) {
				if (output.resourceType().isFood()) {
					total.add(output);
				}
			}
		}
		return state.residentMappings().entrySet().stream()
			.filter(entry -> data.isMember(settlement, entry.getKey()))
			.sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
			.map(entry -> {
				String personId = entry.getValue();
				int units = state.allocatedFoodUnits(personId);
				Received total = received.get(personId);
				OptionalLong nutrition = total == null
					? (units == 0 ? OptionalLong.of(0) : OptionalLong.empty())
					: total.nutritionFor(units);
				return new Snapshot(entry.getKey(), day, state.dailyFoodUnitCap(personId), units,
					state.remainingFoodUnits(personId), nutrition, state.world().person(personId).hunger());
			}).toList();
	}

	/** Nutrition received is food content, not the amount by which hunger was relieved. */
	public record Snapshot(UUID villagerId, long day, int dailyUnitCap, int allocatedUnits,
		int remainingUnits, OptionalLong foodNutritionReceived, int hunger) {
	}

	private static final class Received {
		private long units;
		private long nutrition;
		private boolean unknown;

		private void add(ResourceFlow flow) {
			try {
				units = Math.addExact(units, flow.quantity());
				nutrition = Math.addExact(nutrition, Math.multiplyExact(flow.quantity(), flow.nutritionPerUnit()));
				// Food with no saved nutrition cannot establish its historical nutritional content.
				unknown |= flow.nutritionPerUnit() == 0;
			} catch (ArithmeticException overflow) {
				unknown = true;
			}
		}

		private OptionalLong nutritionFor(int allocatedUnits) {
			return unknown || units != allocatedUnits ? OptionalLong.empty() : OptionalLong.of(nutrition);
		}
	}
}
