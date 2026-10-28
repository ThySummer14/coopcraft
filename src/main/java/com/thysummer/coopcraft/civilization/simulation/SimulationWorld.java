package com.thysummer.coopcraft.civilization.simulation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SimulationWorld {
	private final Map<String, Camp> camps = new LinkedHashMap<>();
	private final Map<String, CampLink> campLinks = new LinkedHashMap<>();
	private final Map<String, Household> households = new LinkedHashMap<>();
	private final Map<String, Long> householdLastMigration = new LinkedHashMap<>();
	private final Map<String, Person> people = new LinkedHashMap<>();
	private final Map<String, ResourcePatch> patches = new LinkedHashMap<>();
	private final Map<String, Long> initialPatchStock = new LinkedHashMap<>();
	private final Map<String, ResourceBatch> batches = new LinkedHashMap<>();
	private final Map<String, Norm> norms = new LinkedHashMap<>();
	private final Map<String, NormProposal> proposals = new LinkedHashMap<>();
	private final EventLedger ledger = new EventLedger();
	private long day;
	private long nextBatchId = 1;

	public void registerCamp(Camp camp) {
		if (camps.putIfAbsent(camp.id(), camp) != null) {
			throw new IllegalArgumentException("Duplicate camp: " + camp.id());
		}
	}

	public void registerCampLink(CampLink link) {
		if (!camps.containsKey(link.firstCampId()) || !camps.containsKey(link.secondCampId())) {
			throw new IllegalArgumentException("Camp link needs registered camps");
		}
		if (campLinks.putIfAbsent(link.routeId(), link) != null) {
			throw new IllegalArgumentException("Duplicate camp route: " + link.routeId());
		}
	}

	public Optional<CampLink> routeBetween(String firstCampId, String secondCampId) {
		return campLinks.values().stream()
			.filter(link -> link.connects(firstCampId, secondCampId))
			.min(Comparator.comparingInt(CampLink::travelHours).thenComparing(CampLink::routeId));
	}

	public void registerHousehold(Household household) {
		if (!camps.containsKey(household.campId())) {
			throw new IllegalArgumentException("Unknown household camp: " + household.campId());
		}
		if (households.putIfAbsent(household.id(), household) != null) {
			throw new IllegalArgumentException("Duplicate household: " + household.id());
		}
	}

	public void registerPerson(Person person) {
		if (!camps.containsKey(person.campId()) || !households.containsKey(person.householdId())) {
			throw new IllegalArgumentException("Person needs a registered camp and household");
		}
		if (!households.get(person.householdId()).campId().equals(person.campId())) {
			throw new IllegalArgumentException("Person household belongs to another camp");
		}
		if (people.putIfAbsent(person.id(), person) != null) {
			throw new IllegalArgumentException("Duplicate person: " + person.id());
		}
	}

	public void registerPatch(ResourcePatch patch) {
		if (!camps.containsKey(patch.campId())) {
			throw new IllegalArgumentException("Unknown patch camp: " + patch.campId());
		}
		if (patches.putIfAbsent(patch.id(), patch) != null) {
			throw new IllegalArgumentException("Duplicate resource patch: " + patch.id());
		}
		initialPatchStock.put(patch.id(), patch.stock());
	}

	public void restoreState(
		long restoredDay,
		long restoredNextBatchId,
		List<RestoredBatch> restoredBatches,
		List<SimulationEvent> restoredEvents
	) {
		if (restoredDay < 0 || restoredNextBatchId <= 0 || !batches.isEmpty() || !ledger.events().isEmpty()) {
			throw new IllegalArgumentException("Cannot restore over a nonempty or invalid world state");
		}
		if (!restoredEvents.isEmpty() && restoredDay < restoredEvents.getLast().day()) {
			throw new IllegalArgumentException("World day cannot precede its ledger");
		}
		long minimumNextBatchId = 1;
		Set<String> restoredIds = new LinkedHashSet<>();
		for (RestoredBatch restored : restoredBatches) {
			validateStorageOwner(restored.holder());
			if (!restoredIds.add(restored.batchId())) {
				throw new IllegalArgumentException("Duplicate restored batch: " + restored.batchId());
			}
			if (restored.batchId().startsWith("batch-")) {
				long numericId;
				try {
					numericId = Long.parseLong(restored.batchId().substring("batch-".length()));
				} catch (NumberFormatException exception) {
					throw new IllegalArgumentException("Invalid restored batch ID: " + restored.batchId(), exception);
				}
				minimumNextBatchId = Math.max(minimumNextBatchId, Math.addExact(numericId, 1));
			}
		}
		if (restoredNextBatchId < minimumNextBatchId) {
			throw new IllegalArgumentException("Restored next batch ID would duplicate an existing batch");
		}
		for (RestoredBatch restored : restoredBatches) {
			ResourceBatch batch = new ResourceBatch(
				restored.batchId(), restored.resourceType(), restored.quantity(), restored.nutritionPerUnit(),
				restored.quality(), restored.holder(), restored.location(), restored.sourcePatchId(),
				restored.originEventId(), restored.parentBatchIds(), restored.harvestDay(), restored.expiryDay(),
				restored.lastEventId()
			);
			batches.put(batch.batchId(), batch);
		}
		day = restoredDay;
		nextBatchId = restoredNextBatchId;
		ledger.restore(restoredEvents);
		assertConservation();
	}

	public long day() {
		return day;
	}

	public long nextBatchId() {
		return nextBatchId;
	}

	public void advanceDay() {
		day = Math.addExact(day, 1);
	}

	public EventLedger ledger() {
		return ledger;
	}

	public void registerNorm(Norm norm) {
		norms.put(norm.campId() + ':' + norm.normId(), norm);
	}

	public Optional<Norm> norm(String campId, String normId) {
		return Optional.ofNullable(norms.get(campId + ':' + normId));
	}

	public List<Norm> normsForCamp(String campId) {
		return norms.values().stream()
			.filter(norm -> norm.campId().equals(campId))
			.sorted(Comparator.comparing(Norm::normId))
			.toList();
	}

	public void registerProposal(NormProposal proposal) {
		if (proposals.putIfAbsent(proposal.proposalId(), proposal) != null) {
			throw new IllegalArgumentException("Duplicate norm proposal: " + proposal.proposalId());
		}
	}

	public Optional<NormProposal> proposal(String proposalId) {
		return Optional.ofNullable(proposals.get(proposalId));
	}

	public List<NormProposal> proposalsForCamp(String campId) {
		return proposals.values().stream()
			.filter(proposal -> proposal.campId().equals(campId))
			.sorted(Comparator.comparing(NormProposal::proposalId))
			.toList();
	}

	long recordSocialEvent(EventType type, List<String> actors, Map<String, String> context, List<Long> causalLinks) {
		long eventId = ledger.nextEventId();
		ledger.append(new SimulationEvent(eventId, day, type, actors, List.of(), List.of(), context, causalLinks));
		return eventId;
	}

	public Camp camp(String id) {
		return required(camps, id, "camp");
	}

	public Household household(String id) {
		return required(households, id, "household");
	}

	public Person person(String id) {
		return required(people, id, "person");
	}

	public ResourcePatch patch(String id) {
		return required(patches, id, "resource patch");
	}

	public ResourceBatch batch(String id) {
		return required(batches, id, "resource batch");
	}

	public Optional<ResourceBatch> findBatch(String id) {
		return Optional.ofNullable(batches.get(id));
	}

	public List<Person> people() {
		return people.values().stream().filter(Person::isAlive).sorted(Comparator.comparing(Person::id)).toList();
	}

	public List<Person> allPeople() {
		return people.values().stream().sorted(Comparator.comparing(Person::id)).toList();
	}

	public List<Person> peopleInCamp(String campId) {
		return people().stream().filter(person -> person.campId().equals(campId)).toList();
	}

	public List<Person> peopleInHousehold(String householdId) {
		return people().stream().filter(person -> person.householdId().equals(householdId)).toList();
	}

	public List<Household> households() {
		return households.values().stream().sorted(Comparator.comparing(Household::id)).toList();
	}

	public List<Person> communicationContacts(Person source) {
		return people().stream()
			.filter(candidate -> !candidate.id().equals(source.id()))
			.filter(candidate -> candidate.trustIn(source.id()) >= 20 || source.trustIn(candidate.id()) >= 20)
			.filter(candidate -> {
				if (candidate.campId().equals(source.campId())) {
					return true;
				}
				return routeBetween(source.campId(), candidate.campId())
					.filter(link -> source.knowsCampRoute(link.routeId()) && candidate.knowsCampRoute(link.routeId()))
					.isPresent();
			})
			.toList();
	}

	public int foodSecurity(String campId) {
		List<Person> residents = peopleInCamp(campId);
		if (residents.isEmpty()) {
			return 0;
		}
		double averageHunger = residents.stream().mapToInt(Person::hunger).average().orElse(100);
		return (int) Math.max(0, Math.min(100, Math.round(100 - averageHunger)));
	}

	void moveHousehold(String householdId, String destinationCampId) {
		Household current = household(householdId);
		camp(destinationCampId);
		if (current.campId().equals(destinationCampId)) {
			throw new IllegalArgumentException("Household is already in the destination camp");
		}
		households.put(householdId, new Household(householdId, destinationCampId));
		for (Person member : peopleInHousehold(householdId)) {
			member.moveToCamp(destinationCampId);
		}
		householdLastMigration.put(householdId, day);
	}

	public long lastMigrationDay(String householdId) {
		return householdLastMigration.getOrDefault(householdId, Long.MIN_VALUE);
	}

	public List<ResourcePatch> patchesForCamp(String campId) {
		return patches.values().stream()
			.filter(patch -> patch.campId().equals(campId))
			.sorted(Comparator.comparing(ResourcePatch::id))
			.toList();
	}

	public List<ResourceBatch> batches() {
		return batches.values().stream().sorted(Comparator.comparing(ResourceBatch::batchId)).toList();
	}

	public List<ResourceBatch> batchesAt(StorageOwner owner) {
		return batches.values().stream()
			.filter(batch -> batch.holder().equals(owner))
			.sorted(Comparator.comparingLong(ResourceBatch::expiryDay).thenComparing(ResourceBatch::batchId))
			.toList();
	}

	public long availableNutrition(StorageOwner owner) {
		return batchesAt(owner).stream().filter(batch -> batch.resourceType().isFood()).mapToLong(ResourceBatch::nutrition).sum();
	}

	public ResourceBatch createInitialBatch(ResourceType resourceType, long quantity, StorageOwner holder) {
		return createInitialBatch(resourceType, quantity, 50, holder, day, expiryDay(resourceType, day));
	}

	public ResourceBatch createInitialBatch(
		ResourceType resourceType,
		long quantity,
		int quality,
		StorageOwner holder,
		long harvestDay,
		long expiryDay
	) {
		validateStorageOwner(holder);
		if (quantity <= 0 || harvestDay < 0 || harvestDay > day || expiryDay < harvestDay) {
			throw new IllegalArgumentException("Invalid initial stock");
		}
		long eventId = ledger.nextEventId();
		ResourceBatch batch = new ResourceBatch(
			newBatchId(), resourceType, quantity, resourceType.nutritionPerUnit(), quality, holder, holder.location(),
			null, eventId, Set.of(), harvestDay, expiryDay, eventId
		);
		batches.put(batch.batchId(), batch);
		ResourceFlow output = batchFlow(batch, quantity, null, batch.location());
		ledger.append(new SimulationEvent(
			eventId, day, EventType.INITIAL_STOCK, List.of(), List.of(), List.of(output),
			Map.of("holder", holder.toString()), List.of()
		));
		return batch;
	}

	public ResourceBatch importExternalBatch(ResourceType resourceType, long quantity, StorageOwner holder) {
		validateStorageOwner(holder);
		if (quantity <= 0) {
			throw new IllegalArgumentException("External import must have a positive quantity");
		}
		long eventId = ledger.nextEventId();
		ResourceBatch batch = new ResourceBatch(
			newBatchId(), resourceType, quantity, resourceType.nutritionPerUnit(), 50, holder, holder.location(),
			null, eventId, Set.of(), day, expiryDay(resourceType, day), eventId
		);
		batches.put(batch.batchId(), batch);
		ResourceFlow output = batchFlow(batch, quantity, null, batch.location());
		ledger.append(new SimulationEvent(
			eventId, day, EventType.EXTERNAL_IMPORT, List.of(), List.of(), List.of(output),
			Map.of("holder", holder.toString()), List.of()
		));
		return batch;
	}

	public long exportExternalBatch(StorageOwner expectedHolder, String batchId, long quantity) {
		ResourceBatch batch = batch(batchId);
		if (!batch.holder().equals(expectedHolder) || quantity <= 0 || quantity > batch.quantity()) {
			throw new IllegalArgumentException("External export exceeds the holder's batch authority");
		}
		long eventId = ledger.nextEventId();
		long cause = batch.lastEventId();
		ResourceFlow input = batchFlow(batch, quantity, batch.location(), null);
		batch.remove(quantity);
		if (batch.quantity() == 0) {
			batches.remove(batchId);
		} else {
			batch.recordEvent(eventId);
		}
		ledger.append(new SimulationEvent(
			eventId, day, EventType.EXTERNAL_EXPORT, List.of(), List.of(input), List.of(),
			Map.of("batchId", batchId, "holder", expectedHolder.toString()), List.of(cause)
		));
		return quantity;
	}

	public Optional<ResourceBatch> harvest(
		String personId,
		String patchId,
		int effortHours,
		long carryCapacity,
		double baseRate,
		double stochasticFactor
	) {
		Person person = person(personId);
		ResourcePatch patch = patch(patchId);
		if (!person.canWork() || !person.campId().equals(patch.campId())) {
			throw new IllegalArgumentException("Worker cannot access this patch");
		}
		if (effortHours <= 0 || carryCapacity <= 0 || !Double.isFinite(baseRate) || baseRate <= 0
			|| !Double.isFinite(stochasticFactor) || stochasticFactor <= 0) {
			throw new IllegalArgumentException("Invalid production parameters");
		}
		int energyCost = Math.multiplyExact(effortHours, 8);
		if (energyCost > person.energy()) {
			throw new IllegalArgumentException("Worker does not have enough energy");
		}
		double environment = 0.5 + 0.5 * (double) patch.stock() / patch.carryingCapacity();
		double rawYield = effortHours * baseRate * person.skill(patch.resourceType().workType())
			* environment * stochasticFactor;
		if (rawYield < 1 || patch.stock() == 0) {
			return Optional.empty();
		}
		long requested = Math.min(carryCapacity, (long) Math.floor(rawYield));
		long harvested = patch.harvest(requested, day);
		if (harvested == 0) {
			return Optional.empty();
		}
		person.spendEnergy(energyCost);
		long eventId = ledger.nextEventId();
		String batchId = newBatchId();
		int quality = (int) Math.max(1, Math.min(100, Math.round(50 * person.skill(patch.resourceType().workType()))));
		ResourceBatch batch = new ResourceBatch(
			batchId, patch.resourceType(), harvested, patch.resourceType().nutritionPerUnit(), quality,
			StorageOwner.person(personId), "field:" + patchId, patchId, eventId, Set.of(), day,
			expiryDay(patch.resourceType(), day), eventId
		);
		batches.put(batchId, batch);
		ResourceFlow input = new ResourceFlow(
			null, patch.resourceType(), harvested, patch.resourceType().nutritionPerUnit(), "patch:" + patchId, null
		);
		ResourceFlow output = batchFlow(batch, harvested, null, batch.location());
		ledger.append(new SimulationEvent(
			eventId, day, EventType.HARVEST, List.of(personId), List.of(input), List.of(output),
			Map.of("patchId", patchId, "effortHours", Integer.toString(effortHours)), List.of()
		));
		return Optional.of(batch);
	}

	public TransferResult transfer(
		String actorId,
		String batchId,
		long quantity,
		StorageOwner destination,
		EventType type,
		long requestEventId
	) {
		if (type != EventType.TRANSPORT && type != EventType.STORE && type != EventType.SHARE) {
			throw new IllegalArgumentException("Invalid transfer event type");
		}
		Person actor = person(actorId);
		ResourceBatch batch = batch(batchId);
		if (!canWithdraw(actor, batch.holder())) {
			throw new IllegalArgumentException("Actor cannot withdraw from this storage");
		}
		validateDestination(actor, destination, type);
		return move(batch, quantity, destination, type, List.of(actorId), requestEventId, Map.of());
	}

	public TransferResult allocateCommon(
		String campId,
		String requesterId,
		String batchId,
		long quantity,
		long requestEventId
	) {
		Person requester = person(requesterId);
		ResourceBatch batch = batch(batchId);
		if (!requester.campId().equals(campId) || !batch.holder().equals(StorageOwner.camp(campId))) {
			throw new IllegalArgumentException("Common allocation must come from the requester's camp");
		}
		return move(
			batch, quantity, StorageOwner.household(requester.householdId()), EventType.SHARE,
			List.of(requesterId, campId), requestEventId, Map.of("allocation", "camp-storage")
		);
	}

	public TradeResult exchangeOneForOne(
		long requestEventId,
		String initiatorId,
		String offeredBatchId,
		String responderId,
		String requestedBatchId,
		String routeId
	) {
		Person initiator = person(initiatorId);
		Person responder = person(responderId);
		ResourceBatch offered = batch(offeredBatchId);
		ResourceBatch requested = batch(requestedBatchId);
		SimulationEvent request = ledger.event(requestEventId).orElseThrow();
		if (request.type() != EventType.TRADE_REQUEST || !request.actors().equals(List.of(initiatorId, responderId))) {
			throw new IllegalArgumentException("Trade must reference its matching request");
		}
		if (!initiator.canMakeDecisions() || !responder.canMakeDecisions()
			|| initiator.campId().equals(responder.campId()) || offeredBatchId.equals(requestedBatchId)
			|| !offered.holder().equals(StorageOwner.household(initiator.householdId()))
			|| !requested.holder().equals(StorageOwner.household(responder.householdId()))) {
			throw new IllegalArgumentException("Trade needs two eligible camps and household-held batches");
		}
		CampLink route = routeBetween(initiator.campId(), responder.campId())
			.filter(link -> link.routeId().equals(routeId))
			.filter(link -> initiator.knowsCampRoute(routeId) && responder.knowsCampRoute(routeId))
			.orElseThrow(() -> new IllegalArgumentException("Trade route is not known by both parties"));
		Map<String, String> offerContext = Map.of(
			"routeId", routeId, "travelHours", Integer.toString(route.travelHours()), "leg", "offered"
		);
		TransferResult offerResult = move(
			offered, 1, StorageOwner.household(responder.householdId()), EventType.TRADE_COMPLETED,
			List.of(initiatorId, responderId), requestEventId, offerContext
		);
		Map<String, String> returnContext = Map.of(
			"routeId", routeId, "travelHours", Integer.toString(route.travelHours()), "leg", "requested"
		);
		TransferResult returnResult = move(
			requested, 1, StorageOwner.household(initiator.householdId()), EventType.TRADE_COMPLETED,
			List.of(initiatorId, responderId), requestEventId, returnContext
		);
		return new TradeResult(offerResult.batchId(), returnResult.batchId(), offerResult.eventId(), returnResult.eventId());
	}

	public long consume(String personId, String batchId, long quantity, EventType type) {
		if (type != EventType.CONSUME && type != EventType.FIELD_CONSUMPTION) {
			throw new IllegalArgumentException("Invalid consumption event type");
		}
		Person person = person(personId);
		ResourceBatch batch = batch(batchId);
		if (!batch.resourceType().isFood() || !canWithdraw(person, batch.holder())) {
			throw new IllegalArgumentException("Person cannot consume this batch");
		}
		if (quantity <= 0 || quantity > batch.quantity()) {
			throw new IllegalArgumentException("Consumption exceeds batch quantity");
		}
		long eventId = ledger.nextEventId();
		long cause = batch.lastEventId();
		ResourceFlow input = batchFlow(batch, quantity, batch.location(), null);
		long nutrition = Math.multiplyExact(quantity, batch.nutritionPerUnit());
		batch.remove(quantity);
		person.consumeNutrition(nutrition);
		if (batch.quantity() == 0) {
			batches.remove(batchId);
		} else {
			batch.recordEvent(eventId);
		}
		ledger.append(new SimulationEvent(
			eventId, day, type, List.of(personId), List.of(input), List.of(), Map.of("batchId", batchId), List.of(cause)
		));
		return nutrition;
	}

	public long recordShareRequest(String requesterId, String targetId, long neededNutrition) {
		person(requesterId);
		if (targetId.isBlank() || neededNutrition <= 0) {
			throw new IllegalArgumentException("A share request needs a target and positive nutrition need");
		}
		long eventId = ledger.nextEventId();
		ledger.append(new SimulationEvent(
			eventId, day, EventType.SHARE_REQUEST, List.of(requesterId, targetId), List.of(), List.of(),
			Map.of("neededNutrition", Long.toString(neededNutrition)), List.of()
		));
		return eventId;
	}

	public long recordShareRefusal(long requestEventId, String requesterId, String targetId, double score) {
		SimulationEvent request = ledger.event(requestEventId).orElseThrow();
		if (request.type() != EventType.SHARE_REQUEST) {
			throw new IllegalArgumentException("Refusal must reference a share request");
		}
		Person requester = person(requesterId);
		requester.adjustTrust(targetId, -2);
		long eventId = ledger.nextEventId();
		ledger.append(new SimulationEvent(
			eventId, day, EventType.SHARE_REFUSED, List.of(requesterId, targetId), List.of(), List.of(),
			Map.of("score", Double.toString(score)), List.of(requestEventId)
		));
		return eventId;
	}

	public void recordShareAcceptance(String requesterId, String donorId) {
		Person requester = person(requesterId);
		Person donor = person(donorId);
		requester.recordAidFrom(donorId);
		donor.adjustTrust(requesterId, 1);
	}

	public int spoilExpired() {
		int spoiledBatches = 0;
		for (ResourceBatch batch : batches()) {
			if (day <= batch.expiryDay()) {
				continue;
			}
			long eventId = ledger.nextEventId();
			long cause = batch.lastEventId();
			ResourceFlow input = batchFlow(batch, batch.quantity(), batch.location(), null);
			batches.remove(batch.batchId());
			ledger.append(new SimulationEvent(
				eventId, day, EventType.SPOIL, List.of(), List.of(input), List.of(),
				Map.of("batchId", batch.batchId()), List.of(cause)
			));
			spoiledBatches++;
		}
		return spoiledBatches;
	}

	public void regeneratePatches(double seasonMultiplier) {
		for (ResourcePatch patch : patches.values().stream().sorted(Comparator.comparing(ResourcePatch::id)).toList()) {
			long before = patch.stock();
			long regenerated = patch.regenerate(seasonMultiplier);
			if (regenerated == 0) {
				continue;
			}
			long eventId = ledger.nextEventId();
			ResourceFlow output = new ResourceFlow(
				null, patch.resourceType(), regenerated, patch.resourceType().nutritionPerUnit(), null, "patch:" + patch.id()
			);
			ledger.append(new SimulationEvent(
				eventId, day, EventType.REGENERATE, List.of(), List.of(), List.of(output),
				Map.of("patchId", patch.id(), "stockBefore", Long.toString(before), "stockAfter", Long.toString(patch.stock())),
				List.of()
			));
		}
	}

	public Map<ResourceType, Long> inventoryTotals() {
		Map<ResourceType, Long> totals = resourceMap();
		for (ResourceBatch batch : batches.values()) {
			totals.merge(batch.resourceType(), batch.quantity(), Math::addExact);
		}
		return totals;
	}

	public Map<ResourceType, Long> nutritionTotals() {
		Map<ResourceType, Long> totals = resourceMap();
		for (ResourceBatch batch : batches.values()) {
			totals.merge(batch.resourceType(), batch.nutrition(), Math::addExact);
		}
		return totals;
	}

	public void assertConservation() {
		Map<ResourceType, Long> expectedQuantities = resourceMap();
		Map<ResourceType, Long> expectedNutrition = resourceMap();
		Map<String, Long> expectedPatchStock = new LinkedHashMap<>(initialPatchStock);
		for (SimulationEvent event : ledger.events()) {
			for (ResourceFlow input : event.inputs()) {
				if (input.batchId() != null) {
					expectedQuantities.merge(input.resourceType(), -input.quantity(), Math::addExact);
					expectedNutrition.merge(input.resourceType(), -input.nutrition(), Math::addExact);
				}
			}
			for (ResourceFlow output : event.outputs()) {
				if (output.batchId() != null) {
					expectedQuantities.merge(output.resourceType(), output.quantity(), Math::addExact);
					expectedNutrition.merge(output.resourceType(), output.nutrition(), Math::addExact);
				}
			}
			if (event.type() == EventType.HARVEST) {
				String patchId = event.context().get("patchId");
				expectedPatchStock.merge(patchId, -event.inputs().getFirst().quantity(), Math::addExact);
			} else if (event.type() == EventType.REGENERATE) {
				String patchId = event.context().get("patchId");
				expectedPatchStock.merge(patchId, event.outputs().getFirst().quantity(), Math::addExact);
			}
		}
		if (!expectedQuantities.equals(inventoryTotals()) || !expectedNutrition.equals(nutritionTotals())) {
			throw new IllegalStateException("Resource ledger does not conserve inventory");
		}
		for (ResourcePatch patch : patches.values()) {
			if (patch.stock() < 0 || patch.stock() > patch.carryingCapacity()
				|| expectedPatchStock.get(patch.id()) != patch.stock()) {
				throw new IllegalStateException("Resource patch ledger mismatch: " + patch.id());
			}
		}
		for (ResourceBatch batch : batches.values()) {
			SimulationEvent origin = ledger.event(batch.originEventId()).orElseThrow();
			if (origin.type() != EventType.HARVEST && origin.type() != EventType.INITIAL_STOCK
				&& origin.type() != EventType.EXTERNAL_IMPORT) {
				throw new IllegalStateException("Batch has no material origin: " + batch.batchId());
			}
		}
	}

	private TransferResult move(
		ResourceBatch source,
		long quantity,
		StorageOwner destination,
		EventType type,
		List<String> actors,
		long requestEventId,
		Map<String, String> additionalContext
	) {
		validateStorageOwner(destination);
		if (quantity <= 0 || quantity > source.quantity() || source.holder().equals(destination)) {
			throw new IllegalArgumentException("Invalid batch transfer");
		}
		if (requestEventId > 0) {
			SimulationEvent request = ledger.event(requestEventId).orElseThrow();
			boolean validCause = type == EventType.TRADE_COMPLETED
				? request.type() == EventType.TRADE_REQUEST : request.type() == EventType.SHARE_REQUEST;
			if (!validCause) {
				throw new IllegalArgumentException("Transfer cause does not match the transfer type");
			}
		}
		long eventId = ledger.nextEventId();
		long sourceCause = source.lastEventId();
		ResourceFlow input = batchFlow(source, quantity, source.location(), null);
		List<Long> causes = new ArrayList<>();
		causes.add(sourceCause);
		if (requestEventId > 0) {
			causes.add(requestEventId);
		}
		ResourceBatch moved;
		if (quantity == source.quantity()) {
			source.moveTo(destination, destination.location(), eventId);
			moved = source;
		} else {
			Set<String> parents = new LinkedHashSet<>(source.parentBatchIds());
			parents.add(source.batchId());
			moved = new ResourceBatch(
				newBatchId(), source.resourceType(), quantity, source.nutritionPerUnit(), source.quality(), destination,
				destination.location(), source.sourcePatchId(), source.originEventId(), parents, source.harvestDay(),
				source.expiryDay(), eventId
			);
			source.remove(quantity);
			source.recordEvent(eventId);
			batches.put(moved.batchId(), moved);
		}
		ResourceFlow output = batchFlow(moved, quantity, null, moved.location());
		Map<String, String> context = new LinkedHashMap<>(additionalContext);
		context.put("fromHolder", input.fromLocation());
		context.put("toHolder", destination.toString());
		ledger.append(new SimulationEvent(eventId, day, type, actors, List.of(input), List.of(output), context, causes));
		return new TransferResult(moved.batchId(), eventId, quantity);
	}

	private void validateDestination(Person actor, StorageOwner destination, EventType type) {
		validateStorageOwner(destination);
		if (type == EventType.SHARE) {
			String destinationCamp = switch (destination.scope()) {
				case PERSON -> person(destination.id()).campId();
				case HOUSEHOLD -> household(destination.id()).campId();
				case CAMP -> destination.id();
			};
			if (!actor.campId().equals(destinationCamp)) {
				throw new IllegalArgumentException("First prototype shares only within a camp");
			}
			return;
		}
		boolean ownDestination = switch (destination.scope()) {
			case PERSON -> destination.id().equals(actor.id());
			case HOUSEHOLD -> destination.id().equals(actor.householdId());
			case CAMP -> destination.id().equals(actor.campId());
		};
		if (!ownDestination) {
			throw new IllegalArgumentException("Non-share transfer must remain within the actor's storage scopes");
		}
	}

	private void validateStorageOwner(StorageOwner holder) {
		switch (holder.scope()) {
			case PERSON -> person(holder.id());
			case HOUSEHOLD -> household(holder.id());
			case CAMP -> camp(holder.id());
		}
	}

	private static boolean canWithdraw(Person person, StorageOwner holder) {
		if (!person.isAlive()) {
			return false;
		}
		return switch (holder.scope()) {
			case PERSON -> holder.id().equals(person.id());
			case HOUSEHOLD -> holder.id().equals(person.householdId());
			case CAMP -> false;
		};
	}

	private static ResourceFlow batchFlow(ResourceBatch batch, long quantity, String from, String to) {
		return new ResourceFlow(batch.batchId(), batch.resourceType(), quantity, batch.nutritionPerUnit(), from, to);
	}

	private static long expiryDay(ResourceType resourceType, long harvestDay) {
		long shelfLife = resourceType.shelfLifeDays();
		return shelfLife == Long.MAX_VALUE || harvestDay > Long.MAX_VALUE - shelfLife
			? Long.MAX_VALUE : harvestDay + shelfLife;
	}

	private static Map<ResourceType, Long> resourceMap() {
		Map<ResourceType, Long> values = new EnumMap<>(ResourceType.class);
		for (ResourceType type : ResourceType.values()) {
			values.put(type, 0L);
		}
		return values;
	}

	private String newBatchId() {
		return "batch-" + nextBatchId++;
	}

	private static <T> T required(Map<String, T> values, String id, String description) {
		T value = values.get(id);
		if (value == null) {
			throw new IllegalArgumentException("Unknown " + description + ": " + id);
		}
		return value;
	}

	public record TransferResult(String batchId, long eventId, long quantity) {
	}

	public record TradeResult(String offeredBatchId, String requestedBatchId, long offerEventId, long returnEventId) {
	}

	public record RestoredBatch(
		String batchId,
		ResourceType resourceType,
		long quantity,
		long nutritionPerUnit,
		int quality,
		StorageOwner holder,
		String location,
		String sourcePatchId,
		long originEventId,
		Set<String> parentBatchIds,
		long harvestDay,
		long expiryDay,
		long lastEventId
	) {
		public RestoredBatch {
			parentBatchIds = Set.copyOf(parentBatchIds);
		}
	}
}
