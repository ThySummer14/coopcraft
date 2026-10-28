package com.thysummer.coopcraft.civilization.simulation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class MigrationSystem {
	private final SimulationWorld world;
	private final SimulationConfig config;
	private final Map<String, Integer> householdInsecurityDays = new LinkedHashMap<>();

	public MigrationSystem(SimulationWorld world, SimulationConfig config) {
		this.world = world;
		this.config = config;
	}

	public int evaluateMigration() {
		int migrations = 0;
		for (Household household : world.households()) {
			if (evaluateHousehold(household)) {
				migrations++;
			}
		}
		return migrations;
	}

	private boolean evaluateHousehold(Household household) {
		String originCampId = household.campId();
		List<Person> members = world.peopleInHousehold(household.id());
		int localSecurity = foodSecurity(members);
		if (localSecurity >= 55 + config.foodAccessThreshold()) {
			householdInsecurityDays.remove(household.id());
			return false;
		}
		int insecureDays = householdInsecurityDays.getOrDefault(household.id(), 0) + 1;
		householdInsecurityDays.put(household.id(), insecureDays);
		if (insecureDays < 5) {
			return false;
		}
		List<Person> adults = members.stream().filter(Person::canMakeDecisions).toList();
		if (adults.isEmpty()) {
			return false;
		}
		long dependents = members.size() - adults.size();
		List<Person> supporters = new ArrayList<>();
		List<Person> opponents = new ArrayList<>();
		Map<String, List<MigrationOption>> supporterOptions = new LinkedHashMap<>();
		for (Person adult : adults) {
			Optional<MigrationOption> option = bestOption(adult, originCampId, localSecurity, dependents);
			if (option.isPresent() && option.get().utility() >= 15) {
				supporters.add(adult);
				supporterOptions.computeIfAbsent(option.get().destinationCampId(), key -> new ArrayList<>()).add(option.get());
			} else if (option.isPresent() && option.get().utility() < 5) {
				opponents.add(adult);
			}
		}
		if (supporters.isEmpty() || supporters.size() <= opponents.size()) {
			return false;
		}
		Optional<MigrationOption> selected = supporterOptions.values().stream()
			.flatMap(List::stream)
			.max(Comparator.comparingDouble(MigrationOption::utility));
		if (selected.isEmpty()) {
			return false;
		}
		MigrationOption option = selected.get();
		long lastMigration = world.lastMigrationDay(household.id());
		if (lastMigration != Long.MIN_VALUE && world.day() - lastMigration < option.travelHours() * 2L) {
			return false;
		}
		int socialCost = Math.min(3, option.travelHours());
		if (adults.stream().anyMatch(person -> person.socialHours() + socialCost > 6)) {
			return false;
		}

		Map<String, String> context = new LinkedHashMap<>();
		context.put("householdId", household.id());
		context.put("originCampId", originCampId);
		context.put("destinationCampId", option.destinationCampId());
		context.put("routeId", option.routeId());
		context.put("travelHours", Integer.toString(option.travelHours()));
		context.put("localSecurity", Integer.toString(localSecurity));
		context.put("expectedSecurity", String.format(java.util.Locale.ROOT, "%.2f", option.expectedSecurity()));
		context.put("supporters", Integer.toString(supporters.size()));
		context.put("opponents", Integer.toString(opponents.size()));
		List<Long> causes = new ArrayList<>();
		causes.add(option.sourceEventId());
		latestLocalConflict(members).ifPresent(causes::add);
		long decisionEvent = world.recordSocialEvent(
			EventType.MIGRATION_DECIDED,
			members.stream().map(Person::id).toList(),
			context,
			causes
		);
		world.moveHousehold(household.id(), option.destinationCampId());
		for (Person member : members) {
			member.learnCampRoute(option.routeId());
			if (member.canWork()) {
				member.addSocialHours(socialCost);
			}
		}
		world.recordSocialEvent(
			EventType.MIGRATED,
			members.stream().map(Person::id).toList(),
			Map.of(
				"householdId", household.id(),
				"originCampId", originCampId,
				"destinationCampId", option.destinationCampId(),
				"members", Integer.toString(members.size())
			),
			List.of(decisionEvent)
		);
		return true;
	}

	private static int foodSecurity(List<Person> members) {
		if (members.isEmpty()) {
			return 0;
		}
		double averageHunger = members.stream().mapToInt(Person::hunger).average().orElse(100);
		return (int) Math.max(0, Math.min(100, Math.round(100 - averageHunger)));
	}

	private Optional<MigrationOption> bestOption(Person person, String originCampId, int localSecurity, long dependents) {
		return person.campKnowledge().stream()
			.filter(knowledge -> !knowledge.campId().equals(originCampId))
			.filter(knowledge -> knowledge.effectiveConfidence(world.day()) >= 0.45)
			.map(knowledge -> optionFor(person, knowledge, originCampId, localSecurity, dependents))
			.flatMap(Optional::stream)
			.max(Comparator.comparingDouble(MigrationOption::utility));
	}

	private Optional<MigrationOption> optionFor(
		Person person,
		CampKnowledge knowledge,
		String originCampId,
		int localSecurity,
		long dependents
	) {
		Optional<CampLink> route = world.routeBetween(originCampId, knowledge.campId())
			.filter(link -> person.knowsCampRoute(link.routeId()));
		if (route.isEmpty()) {
			return Optional.empty();
		}
		double confidence = knowledge.effectiveConfidence(world.day());
		double expectedSecurity = localSecurity + (knowledge.foodSecurity() - localSecurity) * confidence;
		int contactTrust = world.peopleInCamp(knowledge.campId()).stream()
			.mapToInt(contact -> person.trustIn(contact.id())).max().orElse(0);
		double utility = expectedSecurity - localSecurity - route.get().travelHours() * 4 - dependents * 2 + contactTrust * 0.1;
		return Optional.of(new MigrationOption(
			knowledge.campId(), route.get().routeId(), route.get().travelHours(), utility,
			knowledge.sourceEventId(), expectedSecurity
		));
	}

	private Optional<Long> latestLocalConflict(List<Person> members) {
		List<String> ids = members.stream().map(Person::id).toList();
		return world.ledger().eventsAtDay(world.day()).stream()
			.filter(event -> event.type() == EventType.SHORTAGE_CONFLICT && ids.contains(event.actors().getFirst()))
			.map(SimulationEvent::eventId)
			.max(Long::compareTo);
	}

	private record MigrationOption(
		String destinationCampId,
		String routeId,
		int travelHours,
		double utility,
		long sourceEventId,
		double expectedSecurity
	) {
	}
}
