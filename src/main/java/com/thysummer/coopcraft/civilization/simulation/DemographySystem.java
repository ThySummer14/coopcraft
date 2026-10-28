package com.thysummer.coopcraft.civilization.simulation;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

public final class DemographySystem {
	private final SimulationWorld world;
	private final DemographyConfig config;
	private final Map<String, Integer> careReceived = new LinkedHashMap<>();

	public DemographySystem(SimulationWorld world, DemographyConfig config) {
		this.world = world;
		this.config = config;
	}

	public void organizeCare() {
		careReceived.clear();
		if (!config.enabled()) {
			return;
		}
		for (Person dependent : world.people()) {
			int required = careRequirement(dependent);
			int received = 0;
			while (received < required) {
				Optional<Person> caregiver = findCaregiver(dependent);
				if (caregiver.isEmpty()) {
					break;
				}
				Person provider = caregiver.get();
				int hours = Math.min(required - received, config.maxCareHoursPerPerson() - provider.careHours());
				if (hours <= 0) {
					break;
				}
				provider.assignCareHours(hours);
				received += hours;
				careReceived.merge(dependent.id(), hours, Integer::sum);
				world.recordSocialEvent(
					EventType.CARE_PROVIDED,
					List.of(provider.id(), dependent.id()),
					Map.of(
						"hours", Integer.toString(hours),
						"sameHousehold", Boolean.toString(provider.householdId().equals(dependent.householdId())),
						"ageBand", dependent.ageBand().name()
					),
					List.of()
				);
			}
		}
	}

	public int careHoursFor(String personId) {
		return careReceived.getOrDefault(personId, 0);
	}

	public void advancePopulation(Random random) {
		if (!config.enabled()) {
			return;
		}
		for (Person person : world.people()) {
			person.advanceAgeDays();
			Person.AgeBand previous = person.ageBand();
			Person.AgeBand current = ageBandFor(person.ageDays());
			if (person.updateAgeBand(current)) {
				world.recordSocialEvent(
					EventType.LIFE_STAGE_CHANGED,
					List.of(person.id()),
					Map.of("previous", previous.name(), "current", current.name(), "ageDays", Long.toString(person.ageDays())),
					List.of()
				);
			}
			applyHealth(person, random);
		}
		processBirths(random);
	}

	private Optional<Person> findCaregiver(Person dependent) {
		return world.peopleInCamp(dependent.campId()).stream()
			.filter(candidate -> !candidate.id().equals(dependent.id()))
			.filter(Person::canProvideCare)
			.filter(candidate -> candidate.careHours() < config.maxCareHoursPerPerson())
			.filter(candidate -> candidate.householdId().equals(dependent.householdId())
				|| candidate.trustIn(dependent.id()) >= 30 || dependent.trustIn(candidate.id()) >= 30)
			.min(Comparator.comparingInt((Person candidate) -> candidate.householdId().equals(dependent.householdId()) ? 0 : 1)
				.thenComparingInt(Person::careHours)
				.thenComparing(Person::id));
	}

	private int careRequirement(Person person) {
		return switch (person.ageBand()) {
			case INFANT -> config.infantCareHours();
			case CHILD -> config.childCareHours();
			case ADOLESCENT -> config.adolescentCareHours();
			case ADULT, ELDER -> 0;
		};
	}

	private void applyHealth(Person person, Random random) {
		if (person.hunger() >= 80 && person.hungerStreak() % 10 == 0) {
			person.adjustHealth(-1);
		} else if (person.hunger() >= 60 && person.hungerStreak() % 15 == 0) {
			person.adjustHealth(-1);
		} else if (person.hunger() <= 30) {
			person.adjustHealth(1);
		}
		int required = careRequirement(person);
		if (required > 0) {
			int received = careHoursFor(person.id());
			if (received < required) {
				person.adjustHealth(person.ageBand() == Person.AgeBand.INFANT ? -2 : -1);
			} else {
				person.adjustHealth(1);
			}
		}
		if (person.health() <= 0) {
			person.markDead(world.day());
			world.recordSocialEvent(EventType.DEATH, List.of(person.id()), Map.of("reason", "living-conditions"), List.of());
			return;
		}
		if (person.ageBand() == Person.AgeBand.ELDER) {
			double risk = config.elderDailyMortality() * (1.5 - person.health() / 200.0);
			if (random.nextDouble() < risk) {
				person.markDead(world.day());
				world.recordSocialEvent(EventType.DEATH, List.of(person.id()), Map.of("reason", "age-related-risk"), List.of());
			}
		}
	}

	private void processBirths(Random random) {
		for (Household household : world.households()) {
			if (world.peopleInCamp(household.campId()).size() >= config.maxPopulation()) {
				continue;
			}
			List<Person> members = world.peopleInHousehold(household.id());
			List<Person> adults = members.stream().filter(Person::canMakeDecisions).toList();
			if (adults.size() < 2 || foodSecurity(members) < config.minBirthFoodSecurity()) {
				continue;
			}
			boolean careAvailable = world.peopleInCamp(household.campId()).stream()
				.anyMatch(person -> person.canProvideCare() && person.careHours() < config.maxCareHoursPerPerson());
			if (!careAvailable || random.nextDouble() >= config.birthChancePerDay()) {
				continue;
			}
			String childId = "child-" + world.ledger().nextEventId();
			Person child = new Person(childId, household.campId(), household.id(), Person.AgeBand.INFANT, 20, random.nextInt(101));
			world.registerPerson(child);
			world.recordSocialEvent(
				EventType.BIRTH,
				List.of(adults.getFirst().id(), adults.get(1).id(), childId),
				Map.of("householdId", household.id(), "campId", household.campId()),
				List.of()
			);
		}
	}

	private Person.AgeBand ageBandFor(long ageDays) {
		if (ageDays < (long) config.infantEndYears() * config.daysPerYear()) {
			return Person.AgeBand.INFANT;
		}
		if (ageDays < (long) config.childEndYears() * config.daysPerYear()) {
			return Person.AgeBand.CHILD;
		}
		if (ageDays < (long) config.adolescentEndYears() * config.daysPerYear()) {
			return Person.AgeBand.ADOLESCENT;
		}
		return ageDays < (long) config.elderStartYears() * config.daysPerYear()
			? Person.AgeBand.ADULT : Person.AgeBand.ELDER;
	}

	private static int foodSecurity(List<Person> members) {
		if (members.isEmpty()) {
			return 0;
		}
		double averageHunger = members.stream().mapToInt(Person::hunger).average().orElse(100);
		return (int) Math.max(0, Math.min(100, Math.round(100 - averageHunger)));
	}
}
