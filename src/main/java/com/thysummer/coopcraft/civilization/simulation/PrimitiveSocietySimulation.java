package com.thysummer.coopcraft.civilization.simulation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

public final class PrimitiveSocietySimulation {
	private static final String NEIGHBOR_REQUESTS = "neighbor-requests";
	private static final String CAMP_STORAGE = "camp-storage";

	private final SimulationWorld world;
	private final SimulationConfig config;
	private final Random random;
	private final NormSystem normSystem;
	private final MigrationSystem migrationSystem;
	private final DemographySystem demographySystem;
	private final DemographyConfig demographyConfig;
	private final KnowledgeSystem knowledgeSystem;
	private final TradeSystem tradeSystem;
	private final Set<String> workReservations = new LinkedHashSet<>();
	private final List<DailyReport> reports = new ArrayList<>();

	public PrimitiveSocietySimulation(SimulationWorld world, SimulationConfig config, long seed) {
		this(world, config, DemographyConfig.disabled(), KnowledgeConfig.disabled(), TradeConfig.disabled(), seed);
	}

	public PrimitiveSocietySimulation(SimulationWorld world, SimulationConfig config, DemographyConfig demographyConfig, long seed) {
		this(world, config, demographyConfig, KnowledgeConfig.disabled(), TradeConfig.disabled(), seed);
	}

	public PrimitiveSocietySimulation(
		SimulationWorld world,
		SimulationConfig config,
		DemographyConfig demographyConfig,
		KnowledgeConfig knowledgeConfig,
		long seed
	) {
		this(world, config, demographyConfig, knowledgeConfig, TradeConfig.disabled(), seed);
	}

	public PrimitiveSocietySimulation(
		SimulationWorld world,
		SimulationConfig config,
		DemographyConfig demographyConfig,
		KnowledgeConfig knowledgeConfig,
		TradeConfig tradeConfig,
		long seed
	) {
		this.world = world;
		this.config = config;
		this.random = new Random(seed);
		this.normSystem = new NormSystem(world, config);
		this.migrationSystem = new MigrationSystem(world, config);
		this.demographyConfig = demographyConfig;
		this.demographySystem = new DemographySystem(world, demographyConfig);
		this.knowledgeSystem = new KnowledgeSystem(world, knowledgeConfig);
		this.tradeSystem = new TradeSystem(world, config, tradeConfig);
	}

	public SimulationWorld world() {
		return world;
	}

	public NormSystem normSystem() {
		return normSystem;
	}

	public MigrationSystem migrationSystem() {
		return migrationSystem;
	}

	public DemographySystem demographySystem() {
		return demographySystem;
	}

	public KnowledgeSystem knowledgeSystem() {
		return knowledgeSystem;
	}

	public TradeSystem tradeSystem() {
		return tradeSystem;
	}

	public List<DailyReport> reports() {
		return List.copyOf(reports);
	}

	public Set<String> workReservations() {
		return Set.copyOf(workReservations);
	}

	public DailyReport simulateDay() {
		long currentDay = world.day();
		workReservations.clear();
		for (Person person : world.people()) {
			person.startDay();
			person.increaseHunger(config.dailyHunger());
		}
		normSystem.propagateInformation();
		demographySystem.organizeCare();
		knowledgeSystem.organizeLearning(random);
		for (Person person : world.people()) {
			if (person.canWork()) {
				produce(person);
			}
		}
		if (config.sharingEnabled()) {
			for (Person person : normSystem.prioritizeFoodRequests(world.people())) {
				resolveFoodRequests(person);
			}
		}
		for (Person person : world.people()) {
			consumeAvailableFood(person);
		}
		world.spoilExpired();
		world.regeneratePatches(config.seasonMultiplier());

		int hungryPeople = 0;
		int foodAccessCount = 0;
		for (Person person : world.people()) {
			if (person.hunger() > config.foodAccessThreshold()) {
				hungryPeople++;
				person.recordHungerDay();
			} else {
				foodAccessCount++;
				person.recordFoodAccessDay();
			}
			person.rest();
		}
		normSystem.processSocialPhase();
		tradeSystem.executeTrades();
		migrationSystem.evaluateMigration();
		demographySystem.advancePopulation(random);
		DailyReport report = buildReport(currentDay, hungryPeople, foodAccessCount);
		reports.add(report);
		world.assertConservation();
		world.advanceDay();
		return report;
	}

	public List<DailyReport> runDays(int days) {
		if (days < 0) {
			throw new IllegalArgumentException("Days cannot be negative");
		}
		List<DailyReport> result = new ArrayList<>();
		for (int i = 0; i < days; i++) {
			result.add(simulateDay());
		}
		return List.copyOf(result);
	}

	private void produce(Person person) {
		int effortHours = config.effortHours() - person.previousSocialHours() - person.careHours() - person.learningHours();
		if (person.ageBand() == Person.AgeBand.ADOLESCENT) {
			effortHours = Math.min(effortHours, demographyConfig.adolescentWorkHours());
		}
		if (effortHours <= 0) {
			return;
		}
		reserveWork(person.id());
		Optional<ResourcePatch> selected = world.patchesForCamp(person.campId()).stream()
			.filter(patch -> patch.resourceType().isFood() && patch.stock() > 0)
			.filter(patch -> knowledgeSystem.canPerform(person, patch.resourceType().workType()))
			.max(Comparator.comparingDouble((ResourcePatch patch) -> productionPreference(person, patch))
				.thenComparing(ResourcePatch::id));
		if (selected.isEmpty()) {
			return;
		}
		double stochasticFactor = 0.55 + random.nextDouble() * 0.9;
		Optional<ResourceBatch> harvested = world.harvest(
			person.id(), selected.get().id(), effortHours, config.carryCapacity(),
			config.baseYieldPerHour(), stochasticFactor
		);
		if (harvested.isEmpty()) {
			return;
		}
		ResourceBatch batch = harvested.get();
		if (person.hunger() > config.fieldEatingThreshold() && batch.resourceType().isFood()) {
			long fieldNeed = person.hunger() - config.fieldEatingThreshold();
			long units = Math.min(batch.quantity(), unitsFor(fieldNeed, batch.nutritionPerUnit()));
			world.consume(person.id(), batch.batchId(), units, EventType.FIELD_CONSUMPTION);
		}
		Optional<ResourceBatch> remaining = world.findBatch(batch.batchId());
		if (remaining.isEmpty()) {
			return;
		}
		batch = remaining.get();
		world.transfer(
			person.id(), batch.batchId(), batch.quantity(), StorageOwner.household(person.householdId()),
			EventType.TRANSPORT, 0
		);
		if (!config.publicStorageEnabled() || !batch.resourceType().isFood()) {
			return;
		}
		long contribution = batch.quantity() * config.publicContributionPercent() / 100;
		if (contribution > 0) {
			world.transfer(
				person.id(), batch.batchId(), contribution, StorageOwner.camp(person.campId()), EventType.STORE, 0
			);
		}
	}

	private void resolveFoodRequests(Person requester) {
		long shortfall = shortfall(requester);
		Set<String> refusedTargets = new HashSet<>();
		for (int attempt = 0; attempt < 2 && shortfall > 0; attempt++) {
			Optional<DonorCandidate> candidate = bestDonor(requester, refusedTargets);
			if (candidate.isEmpty()) {
				break;
			}
			DonorCandidate donor = candidate.get();
			long requestId = world.recordShareRequest(requester.id(), donor.person().id(), shortfall);
			double score = shareScore(requester, donor, shortfall);
			long quantity = shareQuantity(donor, shortfall);
			if (quantity <= 0 || score + random.nextDouble() * 20 < config.shareAcceptanceThreshold()) {
				world.recordShareRefusal(requestId, requester.id(), donor.person().id(), score);
				refusedTargets.add(donor.person().id());
				continue;
			}
			world.transfer(
				donor.person().id(), donor.batch().batchId(), quantity,
				StorageOwner.household(requester.householdId()), EventType.SHARE, requestId
			);
			world.recordShareAcceptance(requester.id(), donor.person().id());
			shortfall = shortfall(requester);
		}
		if (shortfall > 0 && config.publicStorageEnabled()) {
			requestCommonFood(requester, shortfall);
		}
	}

	private Optional<DonorCandidate> bestDonor(Person requester, Set<String> excluded) {
		return world.peopleInCamp(requester.campId()).stream()
			.filter(donor -> !donor.householdId().equals(requester.householdId()))
			.filter(donor -> !excluded.contains(donor.id()))
			.filter(donor -> requester.trustIn(donor.id()) > 0 || donor.trustIn(requester.id()) > 0)
			.map(donor -> DonorCandidate.create(world, requester, donor, config))
			.filter(candidate -> candidate.batch() != null)
			.max(Comparator.comparingInt((DonorCandidate candidate) -> candidate.person().trustIn(requester.id()))
				.thenComparingLong(DonorCandidate::surplusNutrition)
				.thenComparing(candidate -> candidate.person().id()));
	}

	private double shareScore(Person requester, DonorCandidate donor, long shortfall) {
		double needObserved = Math.min(30, shortfall / 2.0);
		double trust = donor.person().trustIn(requester.id()) * 0.25;
		double reciprocity = Math.min(20, requester.reciprocityWith(donor.person().id()) * 5.0);
		double normPressure = requester.knowsNorm(NEIGHBOR_REQUESTS) && donor.person().knowsNorm(NEIGHBOR_REQUESTS) ? 20 : 0;
		double ownFutureRisk = donor.surplusNutrition() <= 0 ? 60 : Math.max(0, 20 - donor.surplusNutrition() / 20.0);
		double transportCost = 5;
		double riskAdjustment = (50 - donor.person().riskPreference()) * 0.1;
		return needObserved + trust + reciprocity + normPressure - ownFutureRisk - transportCost - riskAdjustment;
	}

	private static long shareQuantity(DonorCandidate donor, long shortfall) {
		if (donor.surplusNutrition() <= 0) {
			return 0;
		}
		long neededUnits = unitsFor(shortfall, donor.batch().nutritionPerUnit());
		long surplusUnits = donor.surplusNutrition() / donor.batch().nutritionPerUnit();
		return Math.min(donor.batch().quantity(), Math.min(neededUnits, surplusUnits));
	}

	private void requestCommonFood(Person requester, long shortfall) {
		StorageOwner campStore = StorageOwner.camp(requester.campId());
		for (ResourceBatch batch : world.batchesAt(campStore)) {
			if (!batch.resourceType().isFood() || shortfall <= 0) {
				continue;
			}
			int requestedUnits = (int) Math.min(batch.quantity(), unitsFor(shortfall, batch.nutritionPerUnit()));
			int quantity = normSystem.limitCommonAllocation(requester, requestedUnits);
			if (quantity == 0) {
				break;
			}
			long requestId = world.recordShareRequest(requester.id(), requester.campId(), shortfall);
			SimulationWorld.TransferResult allocation = world.allocateCommon(
				requester.campId(), requester.id(), batch.batchId(), quantity, requestId
			);
			normSystem.recordCommonAllocation(requester, quantity, requestId, allocation.eventId());
			shortfall = shortfall(requester);
		}
	}

	private long shortfall(Person person) {
		long available = world.availableNutrition(StorageOwner.person(person.id()))
			+ world.availableNutrition(StorageOwner.household(person.householdId()));
		return Math.max(0, person.hunger() - available);
	}

	private void consumeAvailableFood(Person person) {
		consumeFrom(person, StorageOwner.person(person.id()));
		consumeFrom(person, StorageOwner.household(person.householdId()));
	}

	private void consumeFrom(Person person, StorageOwner owner) {
		for (ResourceBatch batch : world.batchesAt(owner)) {
			if (person.hunger() <= 0 || !batch.resourceType().isFood()) {
				continue;
			}
			long quantity = Math.min(batch.quantity(), unitsFor(person.hunger(), batch.nutritionPerUnit()));
			world.consume(person.id(), batch.batchId(), quantity, EventType.CONSUME);
		}
	}

	private DailyReport buildReport(long day, int hungryPeople, int foodAccessCount) {
		long produced = 0;
		long consumed = 0;
		long spoiled = 0;
		long laborHours = 0;
		int shares = 0;
		int refusals = 0;
		for (SimulationEvent event : world.ledger().eventsAtDay(day)) {
			switch (event.type()) {
				case HARVEST -> {
					produced += event.outputs().stream().mapToLong(ResourceFlow::nutrition).sum();
					laborHours += Long.parseLong(event.context().get("effortHours"));
				}
				case CONSUME, FIELD_CONSUMPTION -> consumed += event.inputs().stream().mapToLong(ResourceFlow::nutrition).sum();
				case SPOIL -> spoiled += event.inputs().stream().mapToLong(ResourceFlow::nutrition).sum();
				case SHARE -> shares++;
				case SHARE_REFUSED -> refusals++;
				default -> {
				}
			}
		}
		return new DailyReport(day, produced, consumed, spoiled, laborHours, hungryPeople, foodAccessCount, shares, refusals);
	}

	private void reserveWork(String personId) {
		if (!workReservations.add(personId)) {
			throw new IllegalStateException("Person already has a work reservation: " + personId);
		}
	}

	private static double productionPreference(Person person, ResourcePatch patch) {
		double environment = 0.5 + 0.5 * (double) patch.stock() / patch.carryingCapacity();
		return person.skill(patch.resourceType().workType()) * environment;
	}

	private static long unitsFor(long nutrition, long nutritionPerUnit) {
		if (nutrition <= 0) {
			return 0;
		}
		return Math.max(1, (nutrition + nutritionPerUnit - 1) / nutritionPerUnit);
	}

	private record DonorCandidate(Person person, ResourceBatch batch, long surplusNutrition) {
		static DonorCandidate create(SimulationWorld world, Person requester, Person donor, SimulationConfig config) {
			StorageOwner owner = StorageOwner.household(donor.householdId());
			ResourceBatch batch = world.batchesAt(owner).stream()
				.filter(candidate -> candidate.resourceType().isFood())
				.findFirst().orElse(null);
			if (batch == null) {
				return new DonorCandidate(donor, null, 0);
			}
			long reserve = world.peopleInHousehold(donor.householdId()).stream()
				.mapToLong(member -> Math.max(member.hunger(), config.dailyHunger())).sum();
			long available = world.availableNutrition(owner);
			return new DonorCandidate(donor, batch, Math.max(0, available - reserve));
		}
	}
}
