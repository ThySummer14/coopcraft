package com.thysummer.coopcraft.civilization.simulation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Objects;
import java.util.function.ToIntFunction;

public final class NormSystem {
	private static final String NEIGHBOR_REQUESTS = "neighbor-requests";
	private static final int MIN_GRIEVANCE_FOR_PROPOSAL = 4;
	private static final int MAX_DELIBERATION_ROUNDS = 5;

	private final SimulationWorld world;
	private final SimulationConfig config;
	private final Integer defaultBaseAllowance;
	private ToIntFunction<Person> opinionSelfishnessProvider = person -> 50;
	private final Set<String> chargedSupervision = new LinkedHashSet<>();
	private final Map<String, Integer> commonAllocations = new HashMap<>();
	private long lastPropagationDay = Long.MIN_VALUE;
	private final Map<String, Long> lastMeetingDays = new HashMap<>();

	public NormSystem(SimulationWorld world, SimulationConfig config) {
		this(world, config, null);
	}

	/** Explicit adapter policy used for new proposals and current opinions, never historical votes. */
	public NormSystem(SimulationWorld world, SimulationConfig config, int defaultBaseAllowance) {
		this(world, config, Integer.valueOf(defaultBaseAllowance));
		if (defaultBaseAllowance < 1 || defaultBaseAllowance > 4) {
			throw new IllegalArgumentException("Default base allowance must be between 1 and 4");
		}
	}

	private NormSystem(SimulationWorld world, SimulationConfig config, Integer defaultBaseAllowance) {
		this.world = world;
		this.config = config;
		this.defaultBaseAllowance = defaultBaseAllowance;
	}

	/**
	 * Supply the authoritative saved personality without copying it into simulation history.
	 * The callback must be read-only; values are clamped to 0..100 when evaluating an opinion.
	 * Legacy standalone experiments deliberately ignore this adapter-only weighting.
	 */
	public void setOpinionSelfishnessProvider(ToIntFunction<Person> provider) {
		opinionSelfishnessProvider = Objects.requireNonNull(provider, "provider");
	}

	/**
	 * Rebuild only supervision charges evidenced by today's restored ledger. Call after
	 * restoring the world, not from the constructor: resident social hours are already saved.
	 * Allocation totals and information propagation cannot be reconstructed unambiguously.
	 */
	public void restoreChargedSupervision() {
		for (SimulationEvent event : world.ledger().eventsAtDay(world.day())) {
			if ((event.type() != EventType.NORM_ENFORCED && event.type() != EventType.NORM_VIOLATION)
				|| !Norm.NEED_FIRST_COMMON_STORE.equals(event.context().get("normId"))
				|| event.actors().size() < 3) {
				continue;
			}
			if (event.context().containsKey(MealAllocationEvidence.KEY)) {
				try {
					String camp = MealAllocationEvidence.restoredCamp(world.ledger(), event);
					for (String supervisor : event.actors().subList(1, event.actors().size())) {
						chargedSupervision.add(event.day() + ":" + camp + ':' + supervisor);
					}
				} catch (IllegalArgumentException | ArithmeticException | NullPointerException invalidEvidence) {
					// Marked records never fall back to the less specific legacy inference.
				}
				continue;
			}
			List<SimulationEvent> allocations = event.causalLinks().stream()
				.map(world.ledger()::event).flatMap(Optional::stream)
				.filter(cause -> cause.type() == EventType.SHARE)
				.filter(cause -> "camp-storage".equals(cause.context().get("allocation")))
				.toList();
			// Ambiguous or missing historical evidence must not grant free supervision.
			if (allocations.size() != 1) {
				continue;
			}
			SimulationEvent allocation = allocations.getFirst();
			if (allocation.day() != event.day() || allocation.actors().size() != 2
				|| !allocation.actors().getFirst().equals(event.actors().getFirst())) {
				continue;
			}
			String campId = allocation.actors().get(1);
			if (campId.isBlank() || allocation.inputs().isEmpty()
				|| allocation.inputs().stream().anyMatch(flow -> !("camp:" + campId).equals(flow.fromLocation()))) {
				continue;
			}
			// Position, not identity, distinguishes requester from actual supervisors:
			// a requester can also appear again as one of the supervisors.
			for (String supervisorId : event.actors().subList(1, event.actors().size())) {
				chargedSupervision.add(event.day() + ":" + campId + ':' + supervisorId);
			}
		}
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
		processSocialPhase(world.people(), false);
	}

	public void processSocialPhase(java.util.Collection<Person> residents, boolean commonStoreMeeting) {
		List<Person> active = activeResidents(residents);
		observeSocialDay(active);
		if (commonStoreMeeting) {
			conveneCommonStoreMeeting(active);
		}
		deliberate(active);
	}

	/** Close the current day's personal experiences without convening or deliberating. */
	public void observeSocialDay(java.util.Collection<Person> residents) {
		List<Person> active = activeResidents(residents);
		observeRefusals(active);
		observeShortages(active);
		if (config.publicStorageEnabled()) createProposals(active);
	}

	public record MeetingResult(String status, String proposalId, int adultCount, int participants,
		int round, String outcome, long deliberationEventId) {
		public MeetingResult(String status, String proposalId, int adultCount, int participants, int round, String outcome) {
			this(status, proposalId, adultCount, participants, round, outcome, 0);
		}
		public boolean held() { return status.equals("held"); }
	}

	private record MeetingPlan(MeetingResult result, NormProposal proposal, List<Person> active,
		List<Person> participants, Person source, SocialInformation information) {}

	public enum ProposalFactorKind {
		NEIGHBOR_REQUESTS, PERSONAL_HUNGER, HOUSEHOLD_HUNGER, GRIEVANCE, PROPOSER_TRUST,
		HOUSEHOLD_SURPLUS, LARGE_ALLOCATION, COMMUNITY_RESERVE, SELFISHNESS
	}

	public record ProposalFactor(ProposalFactorKind kind, double contribution) {}

	/** Factors describe the existing score above its baseline of 35, in calculation order. */
	public record ProposalOpinion(NormProposal.Position position, double score, List<ProposalFactor> factors) {
		public ProposalOpinion {
			factors = List.copyOf(factors);
		}
	}

	/** A current, independent tendency, never a stored position or a promise about a future vote. */
	public record ProposalInquiry(String proposalId, String normId, int allocationUnitCap, long proposedDay,
		boolean known, boolean decisionCapable, boolean hasReliableInformation, int remainingSocialHours,
		boolean canIntroduce, Optional<ProposalOpinion> opinion) {}

	/**
	 * Inspect one explicit proposal without learning, voting or spending time. Introduction is a
	 * capability if the resident attends; this method neither verifies nor promises attendance.
	 */
	public Optional<ProposalInquiry> inspectProposal(Person resident, String proposalId) {
		if (resident == null || proposalId == null || proposalId.isBlank()
			|| !resident.isAlive() || world.people().stream().noneMatch(person -> person == resident)) {
			return Optional.empty();
		}
		NormProposal proposal = world.proposal(proposalId).orElse(null);
		if (proposal == null || !resident.campId().equals(proposal.campId())
			|| proposal.status() != NormProposal.Status.OPEN || !validMeetingProposal(proposal)
			|| !(Norm.NEED_FIRST_COMMON_STORE.equals(proposal.normId())
				|| Norm.CARE_FIRST_COMMON_STORE.equals(proposal.normId()))) {
			return Optional.empty();
		}
		boolean known = proposal.knownBy().contains(resident.id());
		boolean decisionCapable = resident.canMakeDecisions();
		boolean reliable = known && proposalInformation(resident, proposal).isPresent();
		int remaining = Math.max(0, 6 - resident.socialHours());
		return Optional.of(new ProposalInquiry(proposal.proposalId(), proposal.normId(), proposal.allocationUnitCap(),
			proposal.proposedDay(), known, decisionCapable, reliable, remaining,
			decisionCapable && remaining > 0 && reliable,
			known && decisionCapable ? Optional.of(proposalOpinion(resident, proposal)) : Optional.empty()));
	}

	public long lastMeetingDay(String campId) {
		return lastMeetingDays.getOrDefault(campId, Long.MIN_VALUE);
	}

	/** Only the persistence adapter restores this; switching modes never clears it. */
	public void restoreLastMeetingDay(String campId, long day) {
		if (day != Long.MIN_VALUE && (day < 0 || day > world.day())) {
			throw new IllegalArgumentException("Invalid restored meeting day");
		}
		lastMeetingDays.put(campId, day);
	}

	/** Read-only: introductions are planned, never learned, until a meeting can progress. */
	public MeetingResult inspectCommonStoreMeeting(java.util.Collection<Person> active,
		java.util.Collection<Person> onsite, String campId) {
		return planMeeting(active, onsite, campId).result();
	}

	/** Opaque read-only identity/eligibility review. Opinions are deliberately not captured. */
	public record MeetingReview(MeetingResult result, Object stamp) {
		/** Whether this review found an onsite source with core-validated proposal provenance. */
		public boolean hasVerifiedSource() {
			return stamp instanceof ReviewStamp review && review.source() != null && review.information() != null;
		}
	}

	private record ReviewedResident(Person person, String id, String camp, boolean adult, boolean hasTime) {}
	private record ReviewStamp(NormSystem owner, long day, long lastMeetingDay, NormProposal proposal,
		int cap, int revision, List<ReviewedResident> active, List<Person> participants,
		Person source, SocialInformation information) {}

	public MeetingReview reviewCommonStoreMeeting(java.util.Collection<Person> active,
		java.util.Collection<Person> onsite, String campId) {
		MeetingPlan plan = planMeeting(active, onsite, campId);
		NormProposal proposal = plan.proposal();
		return new MeetingReview(plan.result(), new ReviewStamp(this, world.day(), lastMeetingDay(campId), proposal,
			proposal == null ? 0 : proposal.allocationUnitCap(), proposal == null ? 0 : proposal.revision(),
			plan.active().stream().map(person -> new ReviewedResident(person, person.id(), person.campId(),
				person.canMakeDecisions(), person.socialHours() < 6)).toList(),
			List.copyOf(plan.participants()), plan.source(), plan.information()));
	}

	/** Caller supplies verified physical attendance; absent residents remain in the denominator. */
	public MeetingResult holdCommonStoreMeeting(java.util.Collection<Person> active,
		java.util.Collection<Person> onsite, String campId, String location) {
		if (location == null || location.isBlank() || location.length() > 96) {
			throw new IllegalArgumentException("A bounded meeting location is required");
		}
		MeetingPlan plan = planMeeting(active, onsite, campId);
		if (!plan.result().status().equals("ready")) return plan.result();
		NormProposal proposal = plan.proposal();
		for (Person participant : plan.participants()) {
			if (!proposal.knownBy().contains(participant.id())) {
				world.recordSocialEvent(EventType.INFORMATION_SHARED,
					List.of(plan.source().id(), participant.id()),
					Map.of("proposalId", proposal.proposalId(), "meeting", "common-store-v1", "location", location),
					List.of(plan.information().sourceEventId()));
				learn(participant, plan.information().propagate(world.day(), plan.source().id()));
			}
			proposal.updatePosition(participant.id(), positionFor(participant, proposal));
		}
		Map<String, String> evidence = new LinkedHashMap<>();
		evidence.put("meeting", "common-store-v1");
		evidence.put("location", location);
		evidence.put("adultCount", Integer.toString(plan.result().adultCount()));
		evidence.put("supportCount", Integer.toString(countPosition(proposal, plan.participants(), NormProposal.Position.SUPPORT)));
		evidence.put("opposeCount", Integer.toString(countPosition(proposal, plan.participants(), NormProposal.Position.OPPOSE)));
		Deliberation deliberation = deliberateProposal(proposal, plan.participants(), plan.active(), evidence);
		lastMeetingDays.put(campId, world.day());
		return new MeetingResult("held", proposal.proposalId(), plan.result().adultCount(),
			plan.participants().size(), proposal.rounds(), deliberation.outcome(), deliberation.eventId());
	}

	private MeetingPlan planMeeting(java.util.Collection<Person> residents,
		java.util.Collection<Person> onsite, String campId) {
		List<Person> active = activeResidents(residents).stream().filter(person -> person.campId().equals(campId)).toList();
		List<Person> present = activeResidents(onsite).stream().filter(active::contains)
			.filter(Person::canMakeDecisions).filter(person -> person.socialHours() < 6).toList();
		int adults = (int) active.stream().filter(Person::canMakeDecisions).count();
		if (lastMeetingDay(campId) == world.day()) {
			return emptyMeeting("already_held", adults, active);
		}
		List<NormProposal> open = world.proposalsForCamp(campId).stream()
			.filter(proposal -> proposal.status() == NormProposal.Status.OPEN)
			.sorted(Comparator.comparingLong(NormProposal::proposedDay).thenComparing(NormProposal::proposalId)).toList();
		if (open.isEmpty()) return emptyMeeting("no_proposal", adults, active);
		MeetingPlan first = null;
		for (NormProposal proposal : open) {
			MeetingPlan plan = planProposal(proposal, active, present, adults);
			if (plan.result().status().equals("ready")) return plan;
			if (first == null || (first.result().status().equals("no_informed_resident")
				&& !plan.result().status().equals("no_informed_resident"))) first = plan;
		}
		return first;
	}

	private MeetingPlan planProposal(NormProposal proposal, List<Person> active, List<Person> present, int adults) {
		if (!validMeetingProposal(proposal)) {
			return new MeetingPlan(new MeetingResult("invalid_proposal", proposal.proposalId(), adults, 0,
				proposal.rounds(), ""), proposal, active, List.of(), null, null);
		}
		Person source = null;
		SocialInformation information = null;
		for (Person person : present) {
			if (!proposal.knownBy().contains(person.id())) continue;
			information = proposalInformation(person, proposal).orElse(null);
			if (information != null) { source = person; break; }
		}
		boolean canIntroduce = information != null;
		List<Person> participants = present.stream()
			.filter(person -> proposal.knownBy().contains(person.id()) || canIntroduce).toList();
		String status = participants.isEmpty() ? "no_informed_resident"
			: participants.size() < 2 ? "insufficient_participants" : "ready";
		return new MeetingPlan(new MeetingResult(status, proposal.proposalId(), adults, participants.size(),
			proposal.rounds(), "deliberating"), proposal, active, participants, source, information);
	}

	private boolean validMeetingProposal(NormProposal proposal) {
		return world.ledger().event(proposal.originEventId()).isPresent() && proposal.proposedDay() <= world.day()
			&& proposal.rounds() < MAX_DELIBERATION_ROUNDS;
	}

	private Optional<SocialInformation> proposalInformation(Person person, NormProposal proposal) {
		return person.information().stream().filter(known -> known.kind() == SocialInformation.Kind.NORM_PROPOSAL
			&& known.objectId().equals(proposal.proposalId()) && known.confidence() >= 0.5)
			.filter(known -> validProposalSource(known, proposal))
			.sorted(Comparator.comparing(SocialInformation::informationId)).findFirst();
	}

	private boolean validProposalSource(SocialInformation information, NormProposal proposal) {
		return world.ledger().event(information.sourceEventId())
			.filter(event -> event.type() == EventType.NORM_PROPOSED && event.day() == proposal.proposedDay()
				&& proposal.proposalId().equals(event.context().get("proposalId"))
				&& event.actors().equals(List.of(proposal.proposerId())))
			.isPresent();
	}

	private MeetingPlan emptyMeeting(String status, int adults, List<Person> active) {
		return new MeetingPlan(new MeetingResult(status, "", adults, 0, 0, ""), null, active, List.of(), null, null);
	}

	public void startDay(java.util.Collection<Person> residents) {
		for (Person person : activeResidents(residents)) {
			person.startDay();
		}
	}

	private List<Person> activeResidents(java.util.Collection<Person> residents) {
		List<Person> active = residents.stream().distinct().sorted(Comparator.comparing(Person::id)).toList();
		if (!world.people().containsAll(active)) {
			throw new IllegalArgumentException("Social participants must be living registered residents");
		}
		return active;
	}

	private void conveneCommonStoreMeeting(List<Person> active) {
		for (Person proposer : active) {
			List<NormProposal> proposals = world.proposalsForCamp(proposer.campId()).stream()
				.filter(proposal -> proposal.status() == NormProposal.Status.OPEN)
				.filter(proposal -> proposal.proposerId().equals(proposer.id()))
				.toList();
			for (NormProposal proposal : proposals) {
				Optional<SocialInformation> information = proposer.information().stream()
					.filter(known -> known.kind() == SocialInformation.Kind.NORM_PROPOSAL)
					.filter(known -> known.objectId().equals(proposal.proposalId()))
					.findFirst();
				if (information.isEmpty()) {
					continue;
				}
				for (Person recipient : active) {
					if (!recipient.campId().equals(proposal.campId()) || proposal.knownBy().contains(recipient.id())) {
						continue;
					}
					world.recordSocialEvent(
						EventType.INFORMATION_SHARED,
						List.of(proposer.id(), recipient.id()),
						Map.of("proposalId", proposal.proposalId(), "meeting", "common-store"),
						List.of(information.get().sourceEventId())
					);
					learn(recipient, information.get().propagate(world.day(), proposer.id()));
				}
			}
		}
	}

	public List<Person> prioritizeFoodRequests(List<Person> people) {
		return prioritizeFoodRequests(people, world.people());
	}

	/** Use one candidate/qualification snapshot for the entire request window. */
	public List<Person> prioritizeFoodRequests(List<Person> people, Collection<Person> supervisorCandidates) {
		List<Person> candidates = supervisionCandidates(supervisorCandidates);
		Map<String, Boolean> enforced = new HashMap<>();
		for (Person person : people) {
			enforced.computeIfAbsent(person.campId(), camp -> enforcedNorm(camp, candidates).isPresent());
		}
		return people.stream().sorted((first, second) -> {
			int campOrder = first.campId().compareTo(second.campId());
			if (campOrder != 0) {
				return campOrder;
			}
			if (enforced.get(first.campId())) {
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
		int remaining = norm.get().allocationUnitCap() + careBonus(requester) - commonAllocations.getOrDefault(key, 0);
		return Math.min(requestedUnits, Math.max(0, remaining));
	}

	public void recordCommonAllocation(Person requester, SurvivalSystem.MealWithEvidence evidence) {
		recordMealAllocation(requester, evidence, null, world.people());
	}

	/**
	 * Records this meal using the caller's authoritative current-day allocation total,
	 * including this meal. Persistent adapters must not infer that total from this cache.
	 */
	public void recordCommonAllocation(Person requester, SurvivalSystem.MealWithEvidence evidence, int dailyTotal) {
		recordCommonAllocation(requester, evidence, dailyTotal, world.people());
	}

	/** Candidate presence is supplied by the caller and current eligibility is rechecked here. */
	public void recordCommonAllocation(Person requester, SurvivalSystem.MealWithEvidence evidence,
		int dailyTotal, Collection<Person> supervisorCandidates) {
		recordMealAllocation(requester, evidence, dailyTotal, supervisorCandidates);
	}

	private void recordMealAllocation(Person requester, SurvivalSystem.MealWithEvidence evidence, Integer dailyTotal,
		Collection<Person> supervisorCandidates) {
		if (!requester.isAlive() || !world.allPeople().contains(requester)) {
			throw new IllegalArgumentException("Meal requester must be living and registered");
		}
		MealAllocationEvidence.validate(world.ledger(), world.day(), requester.id(), requester.campId(), evidence);
		var meal = evidence.meal();
		if (dailyTotal != null && dailyTotal < (long) meal.units()
			+ commonAllocations.getOrDefault(world.day() + ":" + requester.id(), 0)) {
			throw new IllegalArgumentException("Daily allocation total cannot omit this meal or previous recorded meals");
		}
		recordCommonAllocation(requester, meal.units(), meal.requestEventId(), meal.allocationEventId(), evidence,
			dailyTotal, supervisionCandidates(supervisorCandidates));
	}

	public void recordCommonAllocation(Person requester, int allocatedUnits, long requestEventId, long allocationEventId) {
		recordCommonAllocation(requester, allocatedUnits, requestEventId, allocationEventId, null, null, world.people());
	}

	private void recordCommonAllocation(Person requester, int allocatedUnits, long requestEventId,
		long allocationEventId, SurvivalSystem.MealWithEvidence evidence, Integer authoritativeDailyTotal,
		List<Person> supervisorCandidates) {
		if (allocatedUnits <= 0) {
			throw new IllegalArgumentException("Allocated quantity must be positive");
		}
		Optional<Norm> active = world.norm(requester.campId(), Norm.NEED_FIRST_COMMON_STORE).filter(Norm::isActive);
		String allocationKey = world.day() + ":" + requester.id();
		int dailyTotal = authoritativeDailyTotal == null
			? commonAllocations.merge(allocationKey, allocatedUnits, Integer::sum) : authoritativeDailyTotal;
		if (authoritativeDailyTotal != null) commonAllocations.put(allocationKey, dailyTotal);
		int careBonus = careBonus(requester);
		if (active.isPresent()) {
			Norm norm = active.get();
			List<Person> supervisors = supervisors(norm, supervisorCandidates);
			if (supervisors.size() >= 2) {
				int effectiveCap = norm.allocationUnitCap() + careBonus;
				boolean compliant = dailyTotal <= effectiveCap;
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
				Map<String, String> context = new LinkedHashMap<>();
				context.put("normId", norm.normId());
				context.put("allocatedUnits", Integer.toString(allocatedUnits));
				context.put("cap", Integer.toString(effectiveCap));
				if (careBonus > 0) {
					context.put("careBonus", Integer.toString(careBonus));
				}
				addMealEvidence(context, causes, evidence);
				long eventId = world.recordSocialEvent(
					compliant ? EventType.NORM_ENFORCED : EventType.NORM_VIOLATION, actors, context, causes
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
		}
		if (careBonus > 0 && (active.isEmpty() || dailyTotal > active.get().allocationUnitCap())) {
			recordCareAllocation(requester, allocatedUnits, dailyTotal, requestEventId, allocationEventId, evidence, supervisorCandidates);
		}
	}

	private static void addMealEvidence(Map<String, String> context, List<Long> causes,
		SurvivalSystem.MealWithEvidence evidence) {
		if (evidence == null) return;
		context.put(MealAllocationEvidence.KEY, MealAllocationEvidence.VERSION);
		context.put(MealAllocationEvidence.COUNT, Integer.toString(evidence.allocations().size()));
		for (var allocation : evidence.allocations()) causes.add(allocation.consumptionEventId());
	}

	private int careBonus(Person person) {
		if (!person.needsCare()) {
			return 0;
		}
		return world.norm(person.campId(), Norm.CARE_FIRST_COMMON_STORE)
			.filter(Norm::isActive)
			.map(Norm::allocationUnitCap)
			.orElse(0);
	}

	private void recordCareAllocation(
		Person requester,
		int allocatedUnits,
		int dailyTotal,
		long requestEventId,
		long allocationEventId,
		SurvivalSystem.MealWithEvidence evidence,
		List<Person> supervisorCandidates
	) {
		Norm care = world.norm(requester.campId(), Norm.CARE_FIRST_COMMON_STORE).filter(Norm::isActive).orElseThrow();
		int baseCap = world.norm(requester.campId(), Norm.NEED_FIRST_COMMON_STORE)
			.filter(Norm::isActive)
			.map(Norm::allocationUnitCap)
			.orElse(3);
		int effectiveCap = baseCap + care.allocationUnitCap();
		boolean compliant = dailyTotal <= effectiveCap;
		List<String> actors = new ArrayList<>();
		actors.add(requester.id());
		for (Person supervisor : supervisors(care, supervisorCandidates)) {
			actors.add(supervisor.id());
		}
		List<Long> causes = new ArrayList<>();
		causes.add(requestEventId);
		causes.add(allocationEventId);
		if (!care.revisionHistory().isEmpty()) {
			causes.add(care.revisionHistory().getLast().eventId());
		}
		Map<String, String> context = new LinkedHashMap<>(Map.of(
			"normId", care.normId(), "allocatedUnits", Integer.toString(allocatedUnits),
			"cap", Integer.toString(effectiveCap), "careBonus", Integer.toString(care.allocationUnitCap())
		));
		addMealEvidence(context, causes, evidence);
		long eventId = world.recordSocialEvent(
			compliant ? EventType.NORM_ENFORCED : EventType.NORM_VIOLATION, actors, context, causes
		);
		if (care.recordPractice(compliant)) {
			recordDormancy(care, eventId);
		}
	}

	private void observeRefusals(List<Person> active) {
		for (SimulationEvent event : world.ledger().eventsAtDay(world.day())) {
			if (event.type() != EventType.SHARE_REFUSED) {
				continue;
			}
			String requesterId = event.actors().getFirst();
			String donorId = event.actors().get(1);
			Optional<Person> requester = active.stream().filter(person -> person.id().equals(requesterId)).findFirst();
			if (requester.isEmpty()) {
				continue;
			}
			SocialInformation information = new SocialInformation(
				"refusal:" + event.eventId(), SocialInformation.Kind.SHARE_REFUSAL, requesterId, donorId,
				event.eventId(), world.day(), null, 1.0
			);
			learn(requester.get(), information);
			requester.get().increaseGrievance(3);
			active.stream().filter(person -> person.id().equals(donorId)).findFirst().ifPresent(donor -> {
				learn(donor, information);
				donor.increaseGrievance(1);
			});
		}
	}

	private void observeShortages(List<Person> active) {
		for (Person person : active) {
			if (person.hungerStreak() != 2
				|| (person.hasFoodDayHistory() && person.lastObservedFoodDay() != world.day())
				|| world.ledger().eventsAtDay(world.day()).stream().anyMatch(event ->
					event.type() == EventType.SHORTAGE_CONFLICT && event.actors().equals(List.of(person.id())))) {
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
			Optional<Norm> activeNorm = world.norm(person.campId(), Norm.NEED_FIRST_COMMON_STORE)
				.filter(Norm::isActive)
				.filter(norm -> norm.knownBy().contains(person.id()));
			if (activeNorm.isPresent() && activeNorm.get().recordPractice(false)) {
				recordDormancy(activeNorm.get(), eventId);
			}
		}
	}

	private void createProposals(List<Person> active) {
		for (Person person : active) {
			if (!person.canMakeDecisions() || person.grievance() < MIN_GRIEVANCE_FOR_PROPOSAL) {
				continue;
			}
			String normId = proposalNormId(person);
			boolean openExists = world.proposalsForCamp(person.campId()).stream()
				.anyMatch(proposal -> proposal.status() == NormProposal.Status.OPEN && proposal.normId().equals(normId));
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
			Optional<Norm> existing = world.norm(person.campId(), normId);
			int cap = normId.equals(Norm.CARE_FIRST_COMMON_STORE) ? 1 : proposedCap(person, existing);
			if (existing.isPresent() && existing.get().isActive() && existing.get().allocationUnitCap() == cap) {
				continue;
			}
			long eventId = world.ledger().nextEventId();
			String proposalId = "proposal-" + eventId;
			NormProposal proposal = new NormProposal(
				proposalId, person.campId(), normId, person.id(), world.day(),
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

	private String proposalNormId(Person person) {
		boolean dependentInNeed = world.peopleInHousehold(person.householdId()).stream()
			.anyMatch(member -> member.needsCare() && member.hunger() > config.foodAccessThreshold());
		if (dependentInNeed && world.norm(person.campId(), Norm.CARE_FIRST_COMMON_STORE).filter(Norm::isActive).isEmpty()) {
			return Norm.CARE_FIRST_COMMON_STORE;
		}
		return Norm.NEED_FIRST_COMMON_STORE;
	}

	private int proposedCap(Person person, Optional<Norm> existing) {
		long householdStock = world.availableNutrition(StorageOwner.household(person.householdId()));
		long householdNeed = world.peopleInHousehold(person.householdId()).size() * (long) config.dailyHunger() * 3;
		if (defaultBaseAllowance != null || existing.isPresent()) {
			// Legacy experiments use even dormant norms; the adapter actually falls back to its default.
			int current = defaultBaseAllowance == null ? existing.orElseThrow().allocationUnitCap()
				: existing.filter(Norm::isActive).map(Norm::allocationUnitCap).orElse(defaultBaseAllowance);
			return Math.max(1, Math.min(4, householdStock > householdNeed ? current - 1 : current + 1));
		}
		return person.hunger() > config.foodAccessThreshold() ? 2 : 1;
	}

	private void deliberate(List<Person> active) {
		List<NormProposal> open = active.stream()
			.flatMap(person -> world.proposalsForCamp(person.campId()).stream())
			.distinct()
			.filter(proposal -> proposal.status() == NormProposal.Status.OPEN)
			.sorted(Comparator.comparing(NormProposal::proposalId))
			.toList();
		for (NormProposal proposal : open) {
			for (Person person : active) {
				proposal.updatePosition(person.id(), !person.canMakeDecisions()
					? NormProposal.Position.UNDECIDED : positionFor(person, proposal));
			}
			List<Person> participants = active.stream()
				.filter(person -> proposal.knownBy().contains(person.id()))
				.filter(person -> person.campId().equals(proposal.campId()))
				.filter(person -> person.canMakeDecisions())
				.toList();
			deliberateProposal(proposal, participants, active, Map.of());
		}
	}

	/** Shared decision formula for standalone experiments and verified physical meetings. */
	private record Deliberation(String outcome, long eventId) {}

	private Deliberation deliberateProposal(NormProposal proposal, List<Person> participants, List<Person> active,
		Map<String, String> meetingEvidence) {
		for (Person participant : participants) {
			if (participant.socialHours() < 6) participant.addSocialHours(1);
		}
		proposal.advanceRound();
		Decision decision = evaluate(proposal, participants, active);
		String outcome = decision.adopt() ? (world.norm(proposal.campId(), proposal.normId()).isEmpty() ? "adopted" : "revised")
			: proposal.rounds() >= MAX_DELIBERATION_ROUNDS ? (decision.reason().equals("deliberating") ? "expired" : "rejected")
			: "deliberating";
		Map<String, String> context = new LinkedHashMap<>(meetingEvidence);
		context.put("proposalId", proposal.proposalId());
		context.put("round", Integer.toString(proposal.rounds()));
		context.put("participants", Integer.toString(participants.size()));
		if (!meetingEvidence.isEmpty()) {
			context.put("normId", proposal.normId());
			context.put("meetingOutcome", outcome);
			context.put("reason", decision.reason());
			if (decision.adopt()) {
				context.put("previousCap", Integer.toString(world.norm(proposal.campId(), proposal.normId()).map(Norm::allocationUnitCap).orElse(0)));
				context.put("newCap", Integer.toString(proposal.allocationUnitCap()));
			}
		}
		long deliberationEvent = world.recordSocialEvent(EventType.NORM_DELIBERATED,
			participants.stream().map(Person::id).toList(), context, List.of(proposal.originEventId()));
		Map<String, String> frozen = meetingEvidence.isEmpty() ? Map.of() : context;
		if (decision.adopt()) adopt(proposal, deliberationEvent, participants, frozen);
		else if (proposal.rounds() >= MAX_DELIBERATION_ROUNDS) reject(proposal, deliberationEvent, decision.reason(), participants, frozen);
		return new Deliberation(outcome, deliberationEvent);
	}

	private Decision evaluate(NormProposal proposal, List<Person> participants, List<Person> active) {
		int adultCount = (int) active.stream()
			.filter(person -> person.campId().equals(proposal.campId()))
			.filter(person -> person.canMakeDecisions()).count();
		int support = countPosition(proposal, participants, NormProposal.Position.SUPPORT);
		int oppose = countPosition(proposal, participants, NormProposal.Position.OPPOSE);
		double participation = adultCount == 0 ? 0 : (double) participants.size() / adultCount;
		double supportRatio = participants.isEmpty() ? 0 : (double) support / participants.size();
		long supportingHouseholds = householdsAt(proposal, participants, NormProposal.Position.SUPPORT);
		long opposingHouseholds = householdsAt(proposal, participants, NormProposal.Position.OPPOSE);
		long enforcers = participants.stream()
			.filter(person -> proposal.positions().get(person.id()) == NormProposal.Position.SUPPORT)
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

	private void adopt(NormProposal proposal, long deliberationEvent, List<Person> participants, Map<String, String> meetingEvidence) {
		Optional<Norm> existing = world.norm(proposal.campId(), proposal.normId());
		int previousCap = existing.map(Norm::allocationUnitCap).orElse(0);
		Set<String> participantIds = participants.stream().map(Person::id).collect(java.util.stream.Collectors.toSet());
		Set<String> supporters = positions(proposal, NormProposal.Position.SUPPORT);
		supporters.retainAll(participantIds);
		Set<String> opponents = positions(proposal, NormProposal.Position.OPPOSE);
		opponents.retainAll(participantIds);
		Set<String> informed = meetingEvidence.isEmpty() ? proposal.knownBy() : participantIds;
		int stability = existing.map(norm -> Math.max(20, norm.stability())).orElse(40);
		Norm norm = new Norm(
			proposal.normId(), proposal.campId(), proposal.allocationUnitCap(), supporters, opponents,
			informed, stability, Norm.Status.ACTIVE, existing.map(Norm::revisionHistory).orElse(List.of())
		);
		EventType eventType = existing.isEmpty() ? EventType.NORM_ADOPTED : EventType.NORM_REVISED;
		Map<String, String> context = new LinkedHashMap<>(meetingEvidence);
		context.putAll(Map.of("proposalId", proposal.proposalId(), "normId", proposal.normId(), "previousCap",
			Integer.toString(previousCap), "newCap", Integer.toString(proposal.allocationUnitCap())));
		long eventId = world.recordSocialEvent(
			eventType,
			informed.stream().sorted().toList(),
			context,
			List.of(proposal.originEventId(), deliberationEvent)
		);
		norm.recordRevision(new Norm.Revision(
			world.day(), proposal.proposalId(), eventId, previousCap, proposal.allocationUnitCap(),
			existing.isEmpty() ? Norm.Revision.Action.ADOPTED : Norm.Revision.Action.REVISED
		));
		world.registerNorm(norm);
		proposal.conclude(NormProposal.Status.ADOPTED);
		SocialInformation.Kind kind = existing.isEmpty() ? SocialInformation.Kind.NORM_ADOPTION : SocialInformation.Kind.NORM_REVISION;
		for (String personId : informed.stream().sorted().toList()) {
			Person person = world.person(personId);
			person.observeNorm(proposal.normId());
			SocialInformation information = new SocialInformation(
				"norm-info:" + eventId, kind, proposal.proposerId(), proposal.normId(), eventId,
				world.day(), null, 1.0
			);
			learn(person, information);
		}
	}

	private void reject(NormProposal proposal, long deliberationEvent, String reason, List<Person> participants,
		Map<String, String> meetingEvidence) {
		Map<String, String> context = new LinkedHashMap<>(meetingEvidence);
		context.putAll(Map.of("proposalId", proposal.proposalId(), "normId", proposal.normId(), "reason", reason));
		world.recordSocialEvent(
			EventType.NORM_REJECTED,
			meetingEvidence.isEmpty() ? proposal.knownBy().stream().sorted().toList() : participants.stream().map(Person::id).toList(),
			context,
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
			world.norm(recipient.campId(), information.objectId())
				.filter(norm -> knowsCurrentRevision(norm, information)).ifPresent(norm -> {
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

	private boolean knowsCurrentRevision(Norm norm, SocialInformation information) {
		// An old result remains historical information, never evidence of knowing a new revision.
		if (norm.revisionHistory().isEmpty()
			|| norm.revisionHistory().getLast().eventId() != information.sourceEventId()) return false;
		EventType expected = information.kind() == SocialInformation.Kind.NORM_ADOPTION
			? EventType.NORM_ADOPTED : EventType.NORM_REVISED;
		return world.ledger().event(information.sourceEventId()).filter(event -> event.type() == expected
			&& norm.normId().equals(event.context().get("normId"))).isPresent();
	}

	private NormProposal.Position positionFor(Person person, NormProposal proposal) {
		return proposalOpinion(person, proposal).position();
	}

	private ProposalOpinion proposalOpinion(Person person, NormProposal proposal) {
		return defaultBaseAllowance == null ? legacyProposalOpinion(person, proposal) : directionalProposalOpinion(person, proposal);
	}

	private ProposalOpinion directionalProposalOpinion(Person person, NormProposal proposal) {
		List<ProposalFactor> factors = new ArrayList<>();
		boolean care = Norm.CARE_FIRST_COMMON_STORE.equals(proposal.normId());
		int current = world.norm(proposal.campId(), proposal.normId()).filter(Norm::isActive)
			.map(Norm::allocationUnitCap).orElse(care ? 0 : defaultBaseAllowance);
		int direction = Integer.compare(proposal.allocationUnitCap(), current);
		List<Person> household = world.peopleInHousehold(person.householdId()).stream()
			.filter(Person::isAlive).filter(member -> member.campId().equals(proposal.campId())).toList();
		List<Person> camp = world.people().stream().filter(Person::isAlive)
			.filter(member -> member.campId().equals(proposal.campId())).toList();
		boolean householdAffected = household.stream().anyMatch(member -> !care || member.needsCare());
		boolean campAffected = camp.stream().anyMatch(member -> !care || member.needsCare());
		if (direction != 0 && person.knowsNorm(NEIGHBOR_REQUESTS) && camp.stream()
			.anyMatch(member -> (!care || member.needsCare()) && member.hunger() > config.foodAccessThreshold())) {
			factors.add(new ProposalFactor(ProposalFactorKind.NEIGHBOR_REQUESTS, 15 * direction));
		}
		double personalBenefit = direction != 0 && (!care || person.needsCare())
			&& person.hunger() > config.foodAccessThreshold() ? 15 * direction : 0;
		double householdBenefit = direction != 0 && household.stream()
			.anyMatch(member -> (!care || member.needsCare()) && member.hunger() > config.foodAccessThreshold())
			? 10 * direction : 0;
		if (personalBenefit != 0) factors.add(new ProposalFactor(ProposalFactorKind.PERSONAL_HUNGER, personalBenefit));
		if (householdBenefit != 0) factors.add(new ProposalFactor(ProposalFactorKind.HOUSEHOLD_HUNGER, householdBenefit));
		// Approximation: existing grievance weights a relevant household allowance preference;
		// this does not infer the grievance's cause or invent an unrelated benefit.
		factors.add(new ProposalFactor(ProposalFactorKind.GRIEVANCE,
			householdAffected ? Math.min(15, person.grievance() * 2) * direction : 0));
		factors.add(new ProposalFactor(ProposalFactorKind.PROPOSER_TRUST, person.trustIn(proposal.proposerId()) * 0.15));
		long stock = world.availableNutrition(StorageOwner.household(person.householdId()));
		long reserve = household.size() * (long) config.dailyHunger() * 3;
		if (direction != 0 && householdAffected && stock > reserve) {
			factors.add(new ProposalFactor(ProposalFactorKind.HOUSEHOLD_SURPLUS, -20 * direction));
		}
		// A cap is a ceiling, not guaranteed consumption. Reserve pressure is deliberately modest.
		long communityReserve = camp.size() * (long) config.dailyHunger() * 3;
		if (direction != 0 && campAffected && world.availableNutrition(StorageOwner.camp(proposal.campId())) < communityReserve) {
			factors.add(new ProposalFactor(ProposalFactorKind.COMMUNITY_RESERVE, -10 * direction));
		}
		double relevantBenefit = personalBenefit + householdBenefit;
		if (relevantBenefit != 0) {
			int selfishness = Math.max(0, Math.min(100, opinionSelfishnessProvider.applyAsInt(person)));
			double adjustment = Math.max(-5, Math.min(5, (selfishness - 50) / 250.0 * relevantBenefit));
			if (adjustment != 0) factors.add(new ProposalFactor(ProposalFactorKind.SELFISHNESS, adjustment));
		}
		double score = 35;
		for (ProposalFactor factor : factors) score += factor.contribution();
		NormProposal.Position position = score >= 55 ? NormProposal.Position.SUPPORT
			: score < 40 ? NormProposal.Position.OPPOSE : NormProposal.Position.UNDECIDED;
		return new ProposalOpinion(position, score, factors);
	}

	private ProposalOpinion legacyProposalOpinion(Person person, NormProposal proposal) {
		List<ProposalFactor> factors = new ArrayList<>();
		// Preserve every original double addition and its order, including both hunger terms.
		double score = 35;
		if (person.knowsNorm(NEIGHBOR_REQUESTS)) {
			score += 15;
			factors.add(new ProposalFactor(ProposalFactorKind.NEIGHBOR_REQUESTS, 15));
		}
		if (person.hunger() > config.foodAccessThreshold()) {
			score += 15;
			factors.add(new ProposalFactor(ProposalFactorKind.PERSONAL_HUNGER, 15));
		}
		if (world.peopleInHousehold(person.householdId()).stream()
			.anyMatch(member -> member.hunger() > config.foodAccessThreshold())) {
			score += 10;
			factors.add(new ProposalFactor(ProposalFactorKind.HOUSEHOLD_HUNGER, 10));
		}
		score += Math.min(15, person.grievance() * 2);
		factors.add(new ProposalFactor(ProposalFactorKind.GRIEVANCE, Math.min(15, person.grievance() * 2)));
		score += person.trustIn(proposal.proposerId()) * 0.15;
		factors.add(new ProposalFactor(ProposalFactorKind.PROPOSER_TRUST, person.trustIn(proposal.proposerId()) * 0.15));
		long stock = world.availableNutrition(StorageOwner.household(person.householdId()));
		long reserve = world.peopleInHousehold(person.householdId()).size() * (long) config.dailyHunger() * 3;
		if (stock > reserve) {
			score -= 20;
			factors.add(new ProposalFactor(ProposalFactorKind.HOUSEHOLD_SURPLUS, -20));
		}
		if (proposal.allocationUnitCap() >= 3 && stock > reserve) {
			score -= 10;
			factors.add(new ProposalFactor(ProposalFactorKind.LARGE_ALLOCATION, -10));
		}
		NormProposal.Position position = score >= 55 ? NormProposal.Position.SUPPORT
			: score < 40 ? NormProposal.Position.OPPOSE : NormProposal.Position.UNDECIDED;
		return new ProposalOpinion(position, score, factors);
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
		return enforcedNorm(campId, world.people());
	}

	private Optional<Norm> enforcedNorm(String campId, List<Person> candidates) {
		return world.norm(campId, Norm.NEED_FIRST_COMMON_STORE)
			.filter(Norm::isActive)
			.filter(norm -> supervisors(norm, candidates).size() >= 2);
	}

	/** Read-only: the existing active norm and same-camp decision-capable supervisors with time left. */
	public boolean isNeedFirstEnforced(String campId) {
		return isNeedFirstEnforced(campId, world.people());
	}

	public boolean isNeedFirstEnforced(String campId, Collection<Person> supervisorCandidates) {
		return enforcedNorm(campId, supervisionCandidates(supervisorCandidates)).isPresent();
	}

	/** Stale, foreign and impersonating objects never count, including after food was consumed. */
	private List<Person> supervisionCandidates(Collection<Person> candidates) {
		if (candidates == null) return List.of();
		Set<Person> registered = Collections.newSetFromMap(new IdentityHashMap<>());
		registered.addAll(world.people());
		return candidates.stream().filter(registered::contains).distinct().toList();
	}

	private List<Person> supervisors(Norm norm, List<Person> candidates) {
		return candidates.stream()
			.filter(Person::isAlive)
			.filter(person -> norm.supporters().contains(person.id()))
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

	private int countPosition(NormProposal proposal, List<Person> participants, NormProposal.Position position) {
		return (int) participants.stream()
			.filter(person -> proposal.positions().get(person.id()) == position)
			.count();
	}

	private long householdsAt(NormProposal proposal, List<Person> participants, NormProposal.Position position) {
		return participants.stream()
			.filter(person -> proposal.positions().get(person.id()) == position)
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
