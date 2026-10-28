package com.thysummer.coopcraft.civilization.simulation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class NormSystem {
	private static final String NEIGHBOR_REQUESTS = "neighbor-requests";
	private static final int MIN_GRIEVANCE_FOR_PROPOSAL = 4;
	private static final int MAX_DELIBERATION_ROUNDS = 5;

	private final SimulationWorld world;
	private final SimulationConfig config;
	private final Set<String> chargedSupervision = new LinkedHashSet<>();
	private final Map<String, Integer> commonAllocations = new HashMap<>();
	private long lastPropagationDay = Long.MIN_VALUE;

	public NormSystem(SimulationWorld world, SimulationConfig config) {
		this.world = world;
		this.config = config;
	}

	public void propagateInformation() {
		if (lastPropagationDay == world.day()) {
			return;
		}
		lastPropagationDay = world.day();
		Map<String, List<SocialInformation>> snapshots = new HashMap<>();
		for (Person person : world.people()) {
			snapshots.put(person.id(), person.information());
		}
		for (Person source : world.people()) {
			List<SocialInformation> known = snapshots.get(source.id()).stream()
				.filter(information -> information.confidence() >= 0.5)
				.sorted(Comparator.comparingLong(SocialInformation::learnedDay).reversed()
					.thenComparing(SocialInformation::informationId))
				.toList();
			for (SocialInformation information : known) {
				Optional<Person> recipient = world.communicationContacts(source).stream()
					.filter(candidate -> !candidate.knowsInformation(information.informationId()))
					.filter(candidate -> canCommunicate(source, candidate))
					.max(Comparator.comparingInt((Person candidate) -> candidate.trustIn(source.id()))
						.thenComparing(Person::id));
				if (recipient.isEmpty()) {
					continue;
				}
				Person target = recipient.get();
				int travelHours = communicationCost(source, target);
				Map<String, String> context = new LinkedHashMap<>();
				context.put("informationId", information.informationId());
				context.put("confidence", Double.toString(information.confidence()));
				if (travelHours > 0) {
					context.put("routeId", world.routeBetween(source.campId(), target.campId()).orElseThrow().routeId());
					context.put("travelHours", Integer.toString(travelHours));
				}
				long eventId = world.recordSocialEvent(
					EventType.INFORMATION_SHARED, List.of(source.id(), target.id()), context, List.of(information.sourceEventId())
				);
				if (travelHours > 0) {
					source.addSocialHours(travelHours);
					target.addSocialHours(1);
				}
				learn(target, information.propagate(world.day(), source.id()));
				shareCampContext(source, target, eventId);
				break;
			}
		}
	}

	private boolean canCommunicate(Person source, Person target) {
		int travelHours = communicationCost(source, target);
		return source.socialHours() + travelHours <= 6 && target.socialHours() + (travelHours > 0 ? 1 : 0) <= 6;
	}

	private int communicationCost(Person source, Person target) {
		if (source.campId().equals(target.campId())) {
			return 0;
		}
		return world.routeBetween(source.campId(), target.campId()).orElseThrow().travelHours();
	}

	private void shareCampContext(Person source, Person target, long eventId) {
		for (String routeId : source.knownCampRoutes()) {
			target.learnCampRoute(routeId);
		}
		for (CampKnowledge knowledge : source.campKnowledge()) {
			target.learnCampKnowledge(knowledge.propagate(world.day(), source.id()));
		}
		target.learnCampKnowledge(new CampKnowledge(
			source.campId(), world.foodSecurity(source.campId()), world.day(), world.day(), source.id(), 1.0, eventId
		));
	}

	public void processSocialPhase() {
		observeRefusals();
		observeShortages();
		if (config.publicStorageEnabled()) {
			createProposals();
		}
		deliberate();
	}

	public List<Person> prioritizeFoodRequests(List<Person> people) {
		return people.stream().sorted((first, second) -> {
			int campOrder = first.campId().compareTo(second.campId());
			if (campOrder != 0) {
				return campOrder;
			}
			if (isEnforced(first.campId())) {
				int hunger = Integer.compare(second.hunger(), first.hunger());
				if (hunger != 0) {
					return hunger;
				}
				int care = Boolean.compare(first.canWork(), second.canWork());
				if (care != 0) {
					return care;
				}
			}
			return first.id().compareTo(second.id());
		}).toList();
	}

	public int limitCommonAllocation(Person requester, int requestedUnits) {
		if (requestedUnits <= 0) {
			throw new IllegalArgumentException("Requested allocation must be positive");
		}
		Optional<Norm> norm = enforcedNorm(requester.campId());
		if (norm.isEmpty()) {
			return requestedUnits;
		}
		String key = world.day() + ":" + requester.id();
		int remaining = norm.get().allocationUnitCap() - commonAllocations.getOrDefault(key, 0);
		return Math.min(requestedUnits, Math.max(0, remaining));
	}

	public void recordCommonAllocation(Person requester, int allocatedUnits, long requestEventId, long allocationEventId) {
		if (allocatedUnits <= 0) {
			throw new IllegalArgumentException("Allocated quantity must be positive");
		}
		Optional<Norm> active = world.norm(requester.campId(), Norm.NEED_FIRST_COMMON_STORE).filter(Norm::isActive);
		if (active.isEmpty()) {
			return;
		}
		Norm norm = active.get();
		List<Person> supervisors = supervisors(norm);
		if (supervisors.size() < 2) {
			return;
		}
		int dailyTotal = commonAllocations.merge(world.day() + ":" + requester.id(), allocatedUnits, Integer::sum);
		boolean compliant = dailyTotal <= norm.allocationUnitCap();
		List<String> actors = new ArrayList<>();
		actors.add(requester.id());
		for (Person supervisor : supervisors) {
			actors.add(supervisor.id());
			String chargeKey = world.day() + ":" + norm.campId() + ':' + supervisor.id();
			if (chargedSupervision.add(chargeKey)) {
				supervisor.addSocialHours(1);
			}
		}
		List<Long> causes = new ArrayList<>();
		causes.add(requestEventId);
		causes.add(allocationEventId);
		if (!norm.revisionHistory().isEmpty()) {
			causes.add(norm.revisionHistory().getLast().eventId());
		}
		long eventId = world.recordSocialEvent(
			compliant ? EventType.NORM_ENFORCED : EventType.NORM_VIOLATION,
			actors,
			Map.of("normId", norm.normId(), "allocatedUnits", Integer.toString(allocatedUnits), "cap",
				Integer.toString(norm.allocationUnitCap())),
			causes
		);
		if (!compliant) {
			SocialInformation violation = new SocialInformation(
				"violation:" + eventId, SocialInformation.Kind.NORM_VIOLATION, requester.id(), norm.normId(),
				eventId, world.day(), null, 1.0
			);
			for (Person supervisor : supervisors) {
				learn(supervisor, violation);
			}
			learn(requester, violation);
		}
		if (norm.recordPractice(compliant)) {
			recordDormancy(norm, eventId);
		}
	}

	private void observeRefusals() {
		for (SimulationEvent event : world.ledger().eventsAtDay(world.day())) {
			if (event.type() != EventType.SHARE_REFUSED) {
				continue;
			}
			String requesterId = event.actors().getFirst();
			String donorId = event.actors().get(1);
			SocialInformation information = new SocialInformation(
				"refusal:" + event.eventId(), SocialInformation.Kind.SHARE_REFUSAL, requesterId, donorId,
				event.eventId(), world.day(), null, 1.0
			);
			Person requester = world.person(requesterId);
			Person donor = world.person(donorId);
			learn(requester, information);
			learn(donor, information);
			requester.increaseGrievance(3);
			donor.increaseGrievance(1);
		}
	}

	private void observeShortages() {
		for (Person person : world.people()) {
			if (person.hungerStreak() != 2) {
				continue;
			}
			long eventId = world.recordSocialEvent(
				EventType.SHORTAGE_CONFLICT,
				List.of(person.id()),
				Map.of("campId", person.campId(), "hunger", Integer.toString(person.hunger())),
				List.of()
			);
			SocialInformation information = new SocialInformation(
				"shortage:" + eventId, SocialInformation.Kind.SHORTAGE, person.id(), person.householdId(),
				eventId, world.day(), null, 1.0
			);
			learn(person, information);
			person.increaseGrievance(2);
			Optional<Norm> active = world.norm(person.campId(), Norm.NEED_FIRST_COMMON_STORE)
				.filter(Norm::isActive)
				.filter(norm -> norm.knownBy().contains(person.id()));
			if (active.isPresent() && active.get().recordPractice(false)) {
				recordDormancy(active.get(), eventId);
			}
		}
	}

	private void createProposals() {
		for (Person person : world.people()) {
			if (!person.canMakeDecisions() || person.grievance() < MIN_GRIEVANCE_FOR_PROPOSAL) {
				continue;
			}
			boolean openExists = world.proposalsForCamp(person.campId()).stream()
				.anyMatch(proposal -> proposal.status() == NormProposal.Status.OPEN
					&& proposal.normId().equals(Norm.NEED_FIRST_COMMON_STORE));
			if (openExists) {
				continue;
			}
			Optional<SocialInformation> origin = person.information().stream()
				.filter(information -> information.kind() == SocialInformation.Kind.SHARE_REFUSAL
					|| information.kind() == SocialInformation.Kind.SHORTAGE)
				.filter(information -> information.confidence() >= 0.5)
				.max(Comparator.comparingLong(SocialInformation::learnedDay)
					.thenComparing(SocialInformation::sourceEventId));
			if (origin.isEmpty()) {
				continue;
			}
			Optional<Norm> existing = world.norm(person.campId(), Norm.NEED_FIRST_COMMON_STORE);
			int cap = proposedCap(person, existing);
			if (existing.isPresent() && existing.get().isActive() && existing.get().allocationUnitCap() == cap) {
				continue;
			}
			long eventId = world.ledger().nextEventId();
			String proposalId = "proposal-" + eventId;
			NormProposal proposal = new NormProposal(
				proposalId, person.campId(), Norm.NEED_FIRST_COMMON_STORE, person.id(), world.day(),
				origin.get().sourceEventId(), cap, existing.map(norm -> norm.revisionHistory().size()).orElse(0)
			);
			world.registerProposal(proposal);
			proposal.learn(person.id(), positionFor(person, proposal));
			Map<String, String> context = new LinkedHashMap<>();
			context.put("proposalId", proposalId);
			context.put("normId", proposal.normId());
			context.put("cap", Integer.toString(cap));
			context.put("revision", Integer.toString(proposal.revision()));
			eventId = world.recordSocialEvent(
				EventType.NORM_PROPOSED, List.of(person.id()), context, List.of(origin.get().sourceEventId())
			);
			SocialInformation information = new SocialInformation(
				"proposal-info:" + proposalId, SocialInformation.Kind.NORM_PROPOSAL, person.id(), proposalId,
				eventId, world.day(), null, 1.0
			);
			learn(person, information);
		}
	}

	private int proposedCap(Person person, Optional<Norm> existing) {
		long householdStock = world.availableNutrition(StorageOwner.household(person.householdId()));
		long householdNeed = world.peopleInHousehold(person.householdId()).size() * (long) config.dailyHunger() * 3;
		if (existing.isPresent()) {
			int current = existing.get().allocationUnitCap();
			return Math.max(1, Math.min(4, householdStock > householdNeed ? current - 1 : current + 1));
		}
		return person.hunger() > config.foodAccessThreshold() ? 2 : 1;
	}

	private void deliberate() {
		List<NormProposal> open = world.people().stream()
			.flatMap(person -> world.proposalsForCamp(person.campId()).stream())
			.distinct()
			.filter(proposal -> proposal.status() == NormProposal.Status.OPEN)
			.sorted(Comparator.comparing(NormProposal::proposalId))
			.toList();
		for (NormProposal proposal : open) {
			for (String personId : proposal.knownBy().stream().sorted().toList()) {
				Person person = world.person(personId);
				proposal.updatePosition(personId, !person.canMakeDecisions()
					? NormProposal.Position.UNDECIDED : positionFor(person, proposal));
			}
			List<Person> participants = proposal.knownBy().stream()
				.map(world::person)
				.filter(person -> person.campId().equals(proposal.campId()))
				.filter(person -> person.canMakeDecisions())
				.sorted(Comparator.comparing(Person::id))
				.toList();
			for (Person participant : participants) {
				if (participant.socialHours() < 6) {
					participant.addSocialHours(1);
				}
			}
			proposal.advanceRound();
			long deliberationEvent = world.recordSocialEvent(
				EventType.NORM_DELIBERATED,
				participants.stream().map(Person::id).toList(),
				Map.of("proposalId", proposal.proposalId(), "round", Integer.toString(proposal.rounds()),
					"participants", Integer.toString(participants.size())),
				List.of(proposal.originEventId())
			);
			Decision decision = evaluate(proposal, participants);
			if (decision.adopt()) {
				adopt(proposal, deliberationEvent);
			} else if (proposal.rounds() >= MAX_DELIBERATION_ROUNDS) {
				reject(proposal, deliberationEvent, decision.reason());
			}
		}
	}

	private Decision evaluate(NormProposal proposal, List<Person> participants) {
		int adultCount = (int) world.peopleInCamp(proposal.campId()).stream()
			.filter(person -> person.canMakeDecisions()).count();
		int support = countPosition(proposal, NormProposal.Position.SUPPORT);
		int oppose = countPosition(proposal, NormProposal.Position.OPPOSE);
		double participation = adultCount == 0 ? 0 : (double) participants.size() / adultCount;
		double supportRatio = participants.isEmpty() ? 0 : (double) support / participants.size();
		long supportingHouseholds = householdsAt(proposal, NormProposal.Position.SUPPORT);
		long opposingHouseholds = householdsAt(proposal, NormProposal.Position.OPPOSE);
		long enforcers = proposal.positions().entrySet().stream()
			.filter(entry -> entry.getValue() == NormProposal.Position.SUPPORT)
			.map(entry -> world.person(entry.getKey()))
			.filter(person -> person.campId().equals(proposal.campId()))
			.filter(this::canSupervise)
			.count();
		boolean adopt = proposal.rounds() >= 2 && participation >= 0.35 && supportRatio >= 0.55
			&& supportingHouseholds >= opposingHouseholds && supportingHouseholds > 0 && enforcers >= 2;
		String reason = participation < 0.35 ? "insufficient-participation"
			: supportRatio < 0.55 ? "insufficient-support"
			: supportingHouseholds < opposingHouseholds ? "household-opposition"
			: enforcers < 2 ? "insufficient-supervision" : "deliberating";
		return new Decision(adopt, reason);
	}

	private void adopt(NormProposal proposal, long deliberationEvent) {
		Optional<Norm> existing = world.norm(proposal.campId(), proposal.normId());
		int previousCap = existing.map(Norm::allocationUnitCap).orElse(0);
		Set<String> supporters = positions(proposal, NormProposal.Position.SUPPORT);
		Set<String> opponents = positions(proposal, NormProposal.Position.OPPOSE);
		int stability = existing.map(norm -> Math.max(20, norm.stability())).orElse(40);
		Norm norm = new Norm(
			proposal.normId(), proposal.campId(), proposal.allocationUnitCap(), supporters, opponents,
			proposal.knownBy(), stability, Norm.Status.ACTIVE, existing.map(Norm::revisionHistory).orElse(List.of())
		);
		EventType eventType = existing.isEmpty() ? EventType.NORM_ADOPTED : EventType.NORM_REVISED;
		long eventId = world.recordSocialEvent(
			eventType,
			proposal.knownBy().stream().sorted().toList(),
			Map.of("proposalId", proposal.proposalId(), "normId", proposal.normId(), "previousCap",
				Integer.toString(previousCap), "newCap", Integer.toString(proposal.allocationUnitCap())),
			List.of(proposal.originEventId(), deliberationEvent)
		);
		norm.recordRevision(new Norm.Revision(
			world.day(), proposal.proposalId(), eventId, previousCap, proposal.allocationUnitCap(),
			existing.isEmpty() ? Norm.Revision.Action.ADOPTED : Norm.Revision.Action.REVISED
		));
		world.registerNorm(norm);
		proposal.conclude(NormProposal.Status.ADOPTED);
		SocialInformation.Kind kind = existing.isEmpty() ? SocialInformation.Kind.NORM_ADOPTION : SocialInformation.Kind.NORM_REVISION;
		for (String personId : proposal.knownBy().stream().sorted().toList()) {
			Person person = world.person(personId);
			person.observeNorm(proposal.normId());
			SocialInformation information = new SocialInformation(
				"norm-info:" + eventId, kind, proposal.proposerId(), proposal.normId(), eventId,
				world.day(), null, 1.0
			);
			learn(person, information);
		}
	}

	private void reject(NormProposal proposal, long deliberationEvent, String reason) {
		world.recordSocialEvent(
			EventType.NORM_REJECTED,
			proposal.knownBy().stream().sorted().toList(),
			Map.of("proposalId", proposal.proposalId(), "normId", proposal.normId(), "reason", reason),
			List.of(proposal.originEventId(), deliberationEvent)
		);
		proposal.conclude(reason.equals("deliberating") ? NormProposal.Status.EXPIRED : NormProposal.Status.REJECTED);
	}

	private void learn(Person recipient, SocialInformation information) {
		recipient.learnInformation(information);
		if (information.kind() == SocialInformation.Kind.NORM_PROPOSAL) {
			world.proposal(information.objectId()).ifPresent(proposal -> {
				if (!proposal.knownBy().contains(recipient.id())) {
					proposal.learn(recipient.id(), !recipient.canMakeDecisions()
						? NormProposal.Position.UNDECIDED : positionFor(recipient, proposal));
				}
			});
		} else if (information.kind() == SocialInformation.Kind.NORM_ADOPTION
			|| information.kind() == SocialInformation.Kind.NORM_REVISION) {
			world.norm(recipient.campId(), information.objectId()).ifPresent(norm -> {
				recipient.observeNorm(norm.normId());
				norm.learn(recipient.id(), positionFor(recipient, norm) == NormProposal.Position.SUPPORT);
			});
		} else if ((information.kind() == SocialInformation.Kind.SHARE_REFUSAL
			|| information.kind() == SocialInformation.Kind.SHORTAGE
			|| information.kind() == SocialInformation.Kind.NORM_VIOLATION)
			&& (recipient.hunger() > config.foodAccessThreshold() || recipient.trustIn(information.subjectId()) >= 40)) {
			recipient.increaseGrievance(1);
		}
	}

	private NormProposal.Position positionFor(Person person, NormProposal proposal) {
		double score = 35;
		if (person.knowsNorm(NEIGHBOR_REQUESTS)) {
			score += 15;
		}
		if (person.hunger() > config.foodAccessThreshold()) {
			score += 15;
		}
		if (world.peopleInHousehold(person.householdId()).stream()
			.anyMatch(member -> member.hunger() > config.foodAccessThreshold())) {
			score += 10;
		}
		score += Math.min(15, person.grievance() * 2);
		score += person.trustIn(proposal.proposerId()) * 0.15;
		long stock = world.availableNutrition(StorageOwner.household(person.householdId()));
		long reserve = world.peopleInHousehold(person.householdId()).size() * (long) config.dailyHunger() * 3;
		if (stock > reserve) {
			score -= 20;
		}
		if (proposal.allocationUnitCap() >= 3 && stock > reserve) {
			score -= 10;
		}
		return score >= 55 ? NormProposal.Position.SUPPORT
			: score < 40 ? NormProposal.Position.OPPOSE : NormProposal.Position.UNDECIDED;
	}

	private NormProposal.Position positionFor(Person person, Norm norm) {
		if (norm.supporters().contains(person.id())) {
			return NormProposal.Position.SUPPORT;
		}
		if (norm.opponents().contains(person.id())) {
			return NormProposal.Position.OPPOSE;
		}
		double score = 35 + (person.knowsNorm(NEIGHBOR_REQUESTS) ? 15 : 0)
			+ (person.hunger() > config.foodAccessThreshold() ? 15 : 0);
		return score >= 55 ? NormProposal.Position.SUPPORT : NormProposal.Position.UNDECIDED;
	}

	private boolean canSupervise(Person person) {
		return person.canMakeDecisions() && person.socialHours() < 6;
	}

	private Optional<Norm> enforcedNorm(String campId) {
		return world.norm(campId, Norm.NEED_FIRST_COMMON_STORE)
			.filter(Norm::isActive)
			.filter(norm -> supervisors(norm).size() >= 2);
	}

	private boolean isEnforced(String campId) {
		return enforcedNorm(campId).isPresent();
	}

	private List<Person> supervisors(Norm norm) {
		return norm.supporters().stream()
			.map(world::person)
			.filter(person -> person.campId().equals(norm.campId()))
			.filter(this::canSupervise)
			.sorted(Comparator.comparing(Person::id))
			.limit(2)
			.toList();
	}

	private void recordDormancy(Norm norm, long causeEventId) {
		long eventId = world.recordSocialEvent(
			EventType.NORM_DORMANT,
			norm.knownBy().stream().sorted().toList(),
			Map.of("normId", norm.normId()),
			List.of(causeEventId)
		);
		norm.recordRevision(new Norm.Revision(
			world.day(), "dormancy-" + eventId, eventId, norm.allocationUnitCap(), norm.allocationUnitCap(),
			Norm.Revision.Action.DORMANT
		));
	}

	private int countPosition(NormProposal proposal, NormProposal.Position position) {
		return (int) proposal.positions().entrySet().stream()
			.filter(entry -> entry.getValue() == position)
			.map(entry -> world.person(entry.getKey()))
			.filter(person -> person.campId().equals(proposal.campId()) && person.canMakeDecisions())
			.count();
	}

	private long householdsAt(NormProposal proposal, NormProposal.Position position) {
		return proposal.positions().entrySet().stream()
			.filter(entry -> entry.getValue() == position)
			.map(entry -> world.person(entry.getKey()))
			.filter(person -> person.campId().equals(proposal.campId()))
			.map(Person::householdId)
			.distinct()
			.count();
	}

	private static Set<String> positions(NormProposal proposal, NormProposal.Position position) {
		Set<String> result = new LinkedHashSet<>();
		proposal.positions().forEach((personId, value) -> {
			if (value == position) {
				result.add(personId);
			}
		});
		return result;
	}

	private record Decision(boolean adopt, String reason) {
	}
}
