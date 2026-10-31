package com.thysummer.coopcraft.civilization.simulation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class TradeSystem {
	private final SimulationWorld world;
	private final SimulationConfig simulationConfig;
	private final TradeConfig tradeConfig;
	private long lastTradeDay = Long.MIN_VALUE;

	public TradeSystem(SimulationWorld world, SimulationConfig simulationConfig, TradeConfig tradeConfig) {
		this.world = world;
		this.simulationConfig = simulationConfig;
		this.tradeConfig = tradeConfig;
	}

	public int executeTrades() {
		if (lastTradeDay == world.day()) {
			return 0;
		}
		lastTradeDay = world.day();
		if (!tradeConfig.enabled()) {
			return 0;
		}
		Set<String> participants = new HashSet<>();
		Set<String> households = new HashSet<>();
		int completed = 0;
		for (Person initiator : world.people()) {
			if (!initiator.canMakeDecisions() || participants.contains(initiator.id()) || households.contains(initiator.householdId())) {
				continue;
			}
			Optional<TradeAttempt> selected = findAttempt(initiator, participants, households);
			if (selected.isEmpty()) {
				continue;
			}
			TradeAttempt attempt = selected.get();
			Person responder = attempt.responder();
			long requestEventId = recordRequest(attempt);
			participants.add(initiator.id());
			participants.add(responder.id());
			households.add(initiator.householdId());
			households.add(responder.householdId());
			Response response = respond(attempt);
			if (response.refusalReason() != null) {
				world.recordSocialEvent(
					EventType.TRADE_REFUSED,
					List.of(initiator.id(), responder.id()),
					Map.of("reason", response.refusalReason(), "routeId", attempt.route().routeId()),
					List.of(requestEventId)
				);
				continue;
			}
			world.exchangeOneForOne(
				requestEventId, initiator.id(), attempt.offeredBatch().batchId(), responder.id(),
				response.returnedBatch().batchId(), attempt.route().routeId()
			);
			initiator.addSocialHours(attempt.route().travelHours());
			responder.addSocialHours(1);
			completed++;
		}
		return completed;
	}

	private Optional<TradeAttempt> findAttempt(Person initiator, Set<String> participants, Set<String> households) {
		return world.communicationContacts(initiator).stream()
			.filter(contact -> !contact.campId().equals(initiator.campId()))
			.filter(Person::canMakeDecisions)
			.filter(contact -> !participants.contains(contact.id()) && !households.contains(contact.householdId()))
			.filter(contact -> initiator.trustIn(contact.id()) >= tradeConfig.minimumMutualTrust()
				&& contact.trustIn(initiator.id()) >= tradeConfig.minimumMutualTrust())
			.map(contact -> attemptFor(initiator, contact))
			.flatMap(Optional::stream)
			.max(Comparator.comparingInt((TradeAttempt attempt) -> Math.min(
				attempt.initiator().trustIn(attempt.responder().id()),
				attempt.responder().trustIn(attempt.initiator().id())
			)).thenComparing(attempt -> attempt.responder().id()));
	}

	private Optional<TradeAttempt> attemptFor(Person initiator, Person responder) {
		Optional<CampLink> route = world.routeBetween(initiator.campId(), responder.campId())
			.filter(link -> initiator.knowsCampRoute(link.routeId()) && responder.knowsCampRoute(link.routeId()))
			.filter(link -> initiator.socialHours() + link.travelHours() <= 6 && responder.socialHours() < 6);
		if (route.isEmpty()) {
			return Optional.empty();
		}
		Optional<CampKnowledge> report = initiator.campKnowledge(responder.campId())
			.filter(knowledge -> knowledge.effectiveConfidence(world.day()) >= tradeConfig.minimumReportConfidence());
		if (report.isEmpty()) {
			return Optional.empty();
		}
		List<ResourceType> needed = neededTypes(initiator);
		for (ResourceType offeredType : ResourceType.values()) {
			Optional<ResourceBatch> offered = surplusBatch(initiator, offeredType);
			if (offered.isEmpty()) {
				continue;
			}
			for (ResourceType requestedType : needed) {
				if (offeredType != requestedType && reportSupports(report.get(), offeredType, requestedType)) {
					return Optional.of(new TradeAttempt(initiator, responder, route.get(), report.get(), offered.get(), requestedType));
				}
			}
		}
		return Optional.empty();
	}

	private long recordRequest(TradeAttempt attempt) {
		Map<String, String> context = new LinkedHashMap<>();
		context.put("routeId", attempt.route().routeId());
		context.put("travelHours", Integer.toString(attempt.route().travelHours()));
		context.put("offeredBatchId", attempt.offeredBatch().batchId());
		context.put("offeredType", attempt.offeredBatch().resourceType().name());
		context.put("requestedType", attempt.requestedType().name());
		context.put("reportedDay", Long.toString(attempt.report().reportedDay()));
		context.put("reportConfidence", Double.toString(attempt.report().effectiveConfidence(world.day())));
		return world.recordSocialEvent(
			EventType.TRADE_REQUEST,
			List.of(attempt.initiator().id(), attempt.responder().id()),
			context,
			List.of(attempt.report().sourceEventId())
		);
	}

	private Response respond(TradeAttempt attempt) {
		Person responder = attempt.responder();
		if (!needsResource(responder, attempt.offeredBatch().resourceType())) {
			return new Response(null, "no-need");
		}
		Optional<ResourceBatch> returned = surplusBatch(responder, attempt.requestedType());
		return returned.map(batch -> new Response(batch, null)).orElseGet(() -> new Response(null, "no-surplus"));
	}

	private List<ResourceType> neededTypes(Person person) {
		List<ResourceType> needed = new ArrayList<>();
		if (world.availableNutrition(StorageOwner.household(person.householdId())) <= foodReserve(person)) {
			for (ResourceType type : ResourceType.values()) {
				if (type.isFood()) {
					needed.add(type);
				}
			}
			return needed;
		}
		for (ResourceType type : new ResourceType[] {ResourceType.STONE, ResourceType.WOOD}) {
			if (quantityAt(person, type) == 0) {
				needed.add(type);
			}
		}
		return needed;
	}

	private boolean needsResource(Person person, ResourceType type) {
		if (type.isFood()) {
			return world.availableNutrition(StorageOwner.household(person.householdId())) <= foodReserve(person);
		}
		return quantityAt(person, type) == 0;
	}

	private Optional<ResourceBatch> surplusBatch(Person person, ResourceType type) {
		List<ResourceBatch> candidates = world.batchesAt(StorageOwner.household(person.householdId())).stream()
			.filter(batch -> batch.resourceType() == type)
			.toList();
		if (candidates.isEmpty()) {
			return Optional.empty();
		}
		if (type.isFood()) {
			long surplus = world.availableNutrition(StorageOwner.household(person.householdId())) - foodReserve(person);
			if (surplus < type.nutritionPerUnit()) {
				return Optional.empty();
			}
		} else if (quantityAt(person, type) <= 1) {
			return Optional.empty();
		}
		return Optional.of(candidates.getFirst());
	}

	private long quantityAt(Person person, ResourceType type) {
		return world.batchesAt(StorageOwner.household(person.householdId())).stream()
			.filter(batch -> batch.resourceType() == type)
			.mapToLong(ResourceBatch::quantity)
			.sum();
	}

	private long foodReserve(Person person) {
		return (long) world.peopleInHousehold(person.householdId()).size()
			* simulationConfig.dailyHunger() * tradeConfig.foodReserveDays();
	}

	private static boolean reportSupports(CampKnowledge report, ResourceType offeredType, ResourceType requestedType) {
		return (!requestedType.isFood() || report.foodSecurity() >= 50)
			&& (!offeredType.isFood() || report.foodSecurity() <= 80);
	}

	private record TradeAttempt(
		Person initiator,
		Person responder,
		CampLink route,
		CampKnowledge report,
		ResourceBatch offeredBatch,
		ResourceType requestedType
	) {
	}

	private record Response(ResourceBatch returnedBatch, String refusalReason) {
	}
}
