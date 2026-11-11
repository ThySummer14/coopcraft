package com.thysummer.coopcraft.civilization.simulation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Validates immutable ledger evidence without changing simulation state. */
final class MealAllocationEvidence {
	static final String KEY = "allocationEvidence";
	static final String VERSION = "meal-consumption-v1";
	static final String COUNT = "allocationBatchCount";

	private MealAllocationEvidence() { }

	static String validate(EventLedger ledger, long day, String requester, String expectedCamp,
		SurvivalSystem.MealWithEvidence evidence) {
		var meal = evidence.meal();
		require(meal.units() > 0 && !evidence.allocations().isEmpty());
		long units = 0;
		long nutrition = 0;
		long previousConsume = 0;
		String camp = null;
		String householdLocation = null;
		var seen = new HashSet<Long>();
		for (var part : evidence.allocations()) {
			require(part.units() > 0 && seen.add(part.consumptionEventId()));
			var consume = event(ledger, part.consumptionEventId());
			var share = event(ledger, part.allocationEventId());
			var request = event(ledger, part.requestEventId());
			require(consume.type() == EventType.CONSUME && consume.day() == day
				&& consume.actors().equals(List.of(requester)) && consume.inputs().size() == 1
				&& consume.outputs().isEmpty() && consume.causalLinks().equals(List.of(share.eventId())));
			require(share.type() == EventType.SHARE && share.day() == day
				&& "camp-storage".equals(share.context().get("allocation"))
				&& share.actors().size() == 2 && share.actors().getFirst().equals(requester)
				&& share.inputs().size() == 1 && share.outputs().size() == 1);
			String sourceCamp = share.actors().get(1);
			require(!sourceCamp.isBlank() && (camp == null || camp.equals(sourceCamp))
				&& (expectedCamp == null || expectedCamp.equals(sourceCamp)));
			camp = sourceCamp;
			require(request.type() == EventType.SHARE_REQUEST && request.day() == day
				&& request.actors().equals(List.of(requester, camp))
				&& request.inputs().isEmpty() && request.outputs().isEmpty()
				&& share.causalLinks().contains(request.eventId())
				&& share.causalLinks().stream().map(id -> event(ledger, id))
					.filter(cause -> cause.type() == EventType.SHARE_REQUEST).count() == 1);
			// A care request may precede gift/import events and be reused by later parts of the same meal.
			require(share.eventId() > request.eventId() && consume.eventId() == share.eventId() + 1);
			if (previousConsume != 0) {
				require(request.eventId() == previousConsume + 1
					|| request.eventId() == evidence.allocations().getFirst().requestEventId());
			}
			previousConsume = consume.eventId();
			require(Long.parseLong(request.context().get("neededNutrition")) > 0);
			ResourceFlow input = share.inputs().getFirst();
			ResourceFlow output = share.outputs().getFirst();
			ResourceFlow eaten = consume.inputs().getFirst();
			require(input.resourceType().isFood() && input.nutritionPerUnit() > 0
				&& input.quantity() == part.units() && output.quantity() == part.units()
				&& eaten.quantity() == part.units()
				&& input.resourceType() == output.resourceType() && output.resourceType() == eaten.resourceType()
				&& input.nutritionPerUnit() == output.nutritionPerUnit()
				&& output.nutritionPerUnit() == eaten.nutritionPerUnit()
				&& ("camp:" + camp).equals(input.fromLocation()) && input.toLocation() == null
				&& output.fromLocation() == null && output.toLocation() != null
				&& output.toLocation().startsWith("household:")
				&& Objects.equals(output.toLocation(), eaten.fromLocation()) && eaten.toLocation() == null
				&& output.batchId() != null && output.batchId().equals(eaten.batchId())
				&& output.batchId().equals(consume.context().get("batchId")));
			require(output.toLocation().length() > "household:".length()
				&& (householdLocation == null || householdLocation.equals(output.toLocation())));
			householdLocation = output.toLocation();
			List<SimulationEvent> sources = share.causalLinks().stream().filter(id -> id != request.eventId())
				.map(id -> event(ledger, id)).toList();
			require(sources.size() == 1 && input.batchId() != null);
			SimulationEvent source = sources.getFirst();
			// A partial common allocation or export leaves the original batch at camp;
			// the removal input is then its latest location witness, not a new output.
			boolean sourceMatches = source.outputs().stream().anyMatch(flow -> matchesSource(flow, input, true))
				|| (canLeaveRemainder(source, input.batchId()) && source.inputs().stream()
					.anyMatch(flow -> matchesSource(flow, input, false)));
			require(sourceMatches);
			units = Math.addExact(units, part.units());
			nutrition = Math.addExact(nutrition, eaten.nutrition());
		}
		var last = evidence.allocations().getLast();
		require(units == meal.units() && nutrition == meal.nutrition()
			&& last.requestEventId() == meal.requestEventId() && last.allocationEventId() == meal.allocationEventId());
		return camp;
	}

	static String restoredCamp(EventLedger ledger, SimulationEvent normEvent) {
		require(VERSION.equals(normEvent.context().get(KEY)) && !normEvent.actors().isEmpty());
		List<SimulationEvent> causes = normEvent.causalLinks().stream().map(id -> event(ledger, id)).toList();
		List<SimulationEvent> shares = causes.stream().filter(e -> e.type() == EventType.SHARE).toList();
		List<SimulationEvent> requests = causes.stream().filter(e -> e.type() == EventType.SHARE_REQUEST).toList();
		require(shares.size() == 1 && requests.size() == 1);
		var parts = new ArrayList<SurvivalSystem.ConsumedAllocation>();
		long nutrition = 0;
		for (var consume : causes.stream().filter(e -> e.type() == EventType.CONSUME).toList()) {
			require(consume.causalLinks().size() == 1 && consume.inputs().size() == 1);
			var share = event(ledger, consume.causalLinks().getFirst());
			List<SimulationEvent> linkedRequests = share.causalLinks().stream().map(id -> event(ledger, id))
				.filter(e -> e.type() == EventType.SHARE_REQUEST).toList();
			require(linkedRequests.size() == 1);
			parts.add(new SurvivalSystem.ConsumedAllocation(linkedRequests.getFirst().eventId(), share.eventId(),
				consume.eventId(), Math.toIntExact(consume.inputs().getFirst().quantity())));
			nutrition = Math.addExact(nutrition, consume.inputs().getFirst().nutrition());
		}
		require(parts.size() == Integer.parseInt(normEvent.context().get(COUNT)));
		var meal = new SurvivalSystem.MealResult(nutrition, Integer.parseInt(normEvent.context().get("allocatedUnits")),
			requests.getFirst().eventId(), shares.getFirst().eventId());
		return validate(ledger, normEvent.day(), normEvent.actors().getFirst(), null,
			new SurvivalSystem.MealWithEvidence(meal, parts));
	}

	private static boolean canLeaveRemainder(SimulationEvent source, String batchId) {
		return switch (source.type()) {
			case EXTERNAL_EXPORT -> source.outputs().isEmpty();
			case SHARE -> source.outputs().stream()
				.noneMatch(flow -> batchId.equals(flow.batchId()));
			default -> false;
		};
	}

	private static boolean matchesSource(ResourceFlow source, ResourceFlow input, boolean output) {
		return Objects.equals(source.batchId(), input.batchId()) && source.resourceType() == input.resourceType()
			&& source.nutritionPerUnit() == input.nutritionPerUnit()
			&& Objects.equals(output ? source.toLocation() : source.fromLocation(), input.fromLocation());
	}

	private static SimulationEvent event(EventLedger ledger, long id) {
		return ledger.event(id).orElseThrow(() -> new IllegalArgumentException("Missing meal evidence"));
	}

	private static void require(boolean valid) {
		if (!valid) throw new IllegalArgumentException("Invalid common meal evidence");
	}
}
