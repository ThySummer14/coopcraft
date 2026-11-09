package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.Camp;
import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.Household;
import com.thysummer.coopcraft.civilization.simulation.Norm;
import com.thysummer.coopcraft.civilization.simulation.NormProposal;
import com.thysummer.coopcraft.civilization.simulation.NormSystem;
import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.ResourceBatch;
import com.thysummer.coopcraft.civilization.simulation.ResourceFlow;
import com.thysummer.coopcraft.civilization.simulation.ResourceType;
import com.thysummer.coopcraft.civilization.simulation.SimulationConfig;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;
import com.thysummer.coopcraft.civilization.simulation.SimulationWorld;
import com.thysummer.coopcraft.civilization.simulation.SocialInformation;
import com.thysummer.coopcraft.civilization.simulation.StorageOwner;
import com.thysummer.coopcraft.civilization.simulation.WorkType;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

public final class SettlementSimulationState {
	private final Settlement settlement;
	private final SimulationWorld world;
	private final NormSystem norms;
	static final int DAILY_FOOD_UNIT_CAP = 3;

	private final Map<UUID, String> residentIds = new LinkedHashMap<>();
	private final Map<String, Integer> foodAllocations = new LinkedHashMap<>();
	private boolean survivalEnabled;
	private boolean productionEnabled;
	private boolean growthEnabled;
	private boolean skillsEnabled;
	private boolean normsEnabled;
	private long foodRequestModeVersion;

	long foodRequestModeVersion() { return foodRequestModeVersion; }
	private long lastSurvivalDay = Long.MIN_VALUE;
	private long foodAllocationDay = Long.MIN_VALUE;

	private SettlementSimulationState(Settlement settlement, SimulationWorld world) {
		this.settlement = settlement;
		this.world = world;
		this.norms = new NormSystem(world, SimulationConfig.defaults());
	}

	public static SettlementSimulationState create(Settlement settlement) {
		SimulationWorld world = new SimulationWorld();
		world.registerCamp(new Camp(campId(settlement), settlement.name()));
		return new SettlementSimulationState(settlement, world);
	}

	public SimulationWorld world() {
		return world;
	}

	public NormSystem norms() {
		return norms;
	}

	public StorageOwner campOwner() {
		return StorageOwner.camp(campId(settlement));
	}

	public Optional<String> residentId(UUID villagerId) {
		return Optional.ofNullable(residentIds.get(villagerId));
	}

	public Map<UUID, String> residentMappings() {
		return Map.copyOf(residentIds);
	}

	public boolean survivalEnabled() {
		return survivalEnabled;
	}

	public boolean productionEnabled() {
		return productionEnabled;
	}

	public void setProductionEnabled(boolean enabled) {
		productionEnabled = enabled;
	}

	public boolean growthEnabled() {
		return growthEnabled;
	}

	public void setGrowthEnabled(boolean enabled) {
		growthEnabled = enabled;
	}

	public boolean skillsEnabled() {
		return skillsEnabled;
	}

	public void setSkillsEnabled(boolean enabled) {
		skillsEnabled = enabled;
	}

	public boolean normsEnabled() {
		return normsEnabled;
	}

	public void setNormsEnabled(boolean enabled) {
		if (normsEnabled != enabled) foodRequestModeVersion++;
		normsEnabled = enabled;
	}

	public long lastSurvivalDay() {
		return lastSurvivalDay;
	}

	public void setSurvivalEnabled(boolean enabled, long currentDay) {
		if (survivalEnabled != enabled) foodRequestModeVersion++;
		survivalEnabled = enabled;
		lastSurvivalDay = currentDay;
	}

	public void markSurvivalDay(long currentDay) {
		lastSurvivalDay = currentDay;
	}

	public int remainingFoodUnits(String personId) {
		if (foodAllocationDay != world.day()) {
			return dailyFoodUnitCap(personId);
		}
		return Math.max(0, dailyFoodUnitCap(personId) - foodAllocations.getOrDefault(personId, 0));
	}

	public void recordFoodAllocation(String personId, int units) {
		if (units <= 0) {
			throw new IllegalArgumentException("Allocated food units must be positive");
		}
		if (foodAllocationDay != world.day()) {
			foodAllocationDay = world.day();
			foodAllocations.clear();
		}
		int total = foodAllocations.getOrDefault(personId, 0) + units;
		if (total > dailyFoodUnitCap(personId)) {
			throw new IllegalArgumentException("Daily food allocation cap exceeded");
		}
		foodAllocations.put(personId, total);
	}

	/** Current-day persisted allocation count, without advancing or resetting its ledger. */
	public int allocatedFoodUnits(String personId) {
		return foodAllocationDay == world.day() ? foodAllocations.getOrDefault(personId, 0) : 0;
	}

	public int dailyFoodUnitCap(String personId) {
		int baseCap = normsEnabled ? world.norm(campOwner().id(), Norm.NEED_FIRST_COMMON_STORE)
			.filter(Norm::isActive)
			.map(Norm::allocationUnitCap)
			.orElse(DAILY_FOOD_UNIT_CAP) : DAILY_FOOD_UNIT_CAP;
		if (!normsEnabled || !world.person(personId).needsCare()) {
			return baseCap;
		}
		return baseCap + world.norm(campOwner().id(), Norm.CARE_FIRST_COMMON_STORE)
			.filter(Norm::isActive)
			.map(Norm::allocationUnitCap)
			.orElse(0);
	}

	public boolean mapResident(UUID villagerId, boolean child, int satiety) {
		return mapResident(villagerId, child, satiety, householdId(villagerId));
	}

	public boolean mapResidentWithParents(UUID villagerId, int satiety, UUID firstParentId, UUID secondParentId) {
		if (residentIds.containsKey(villagerId)) {
			return false;
		}
		if (firstParentId.equals(secondParentId)) {
			throw new IllegalArgumentException("Birth parents must be distinct");
		}
		String firstPersonId = residentId(firstParentId)
			.orElseThrow(() -> new IllegalArgumentException("Birth parents must be mapped residents"));
		String secondPersonId = residentId(secondParentId)
			.orElseThrow(() -> new IllegalArgumentException("Birth parents must be mapped residents"));
		Person firstParent = world.person(firstPersonId);
		Person secondParent = world.person(secondPersonId);
		if (!firstParent.canMakeDecisions() || !secondParent.canMakeDecisions()) {
			throw new IllegalArgumentException("Birth parents must be living adults");
		}
		mapResident(villagerId, true, satiety, firstParent.householdId());
		world.recordBirth(firstPersonId, secondPersonId, personId(villagerId), Map.of(
			"source", "minecraft_villager",
			"villagerId", villagerId.toString(),
			"firstParentVillagerId", firstParentId.toString(),
			"secondParentVillagerId", secondParentId.toString()
		));
		return true;
	}

	private boolean mapResident(UUID villagerId, boolean child, int satiety, String householdId) {
		if (residentIds.containsKey(villagerId)) {
			return false;
		}
		String personId = personId(villagerId);
		if (world.households().stream().noneMatch(household -> household.id().equals(householdId))) {
			world.registerHousehold(new Household(householdId, campId(settlement)));
		}
		Person person = new Person(
			personId,
			campId(settlement),
			householdId,
			child ? Person.AgeBand.CHILD : Person.AgeBand.ADULT,
			100 - Math.max(0, Math.min(100, satiety)),
			50
		);
		world.registerPerson(person);
		residentIds.put(villagerId, personId);
		return true;
	}

	public List<SimulationEvent> recentEvents(int limit) {
		return world.ledger().recentEvents(limit);
	}

	CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putLong("Day", world.day());
		tag.putLong("NextBatchId", world.nextBatchId());
		tag.putBoolean("SurvivalEnabled", survivalEnabled);
		tag.putBoolean("ProductionEnabled", productionEnabled);
		tag.putBoolean("GrowthEnabled", growthEnabled);
		tag.putBoolean("SkillsEnabled", skillsEnabled);
		tag.putBoolean("NormsEnabled", normsEnabled);
		tag.putLong("LastSurvivalDay", lastSurvivalDay);
		tag.putLong("LastMeetingDay", norms.lastMeetingDay(campOwner().id()));
		tag.putLong("FoodAllocationDay", foodAllocationDay);
		ListTag allocations = new ListTag();
		for (Map.Entry<String, Integer> entry : foodAllocations.entrySet().stream()
			.sorted(Map.Entry.comparingByKey()).toList()) {
			CompoundTag allocation = new CompoundTag();
			allocation.putString("PersonId", entry.getKey());
			allocation.putInt("Units", entry.getValue());
			allocations.add(allocation);
		}
		tag.put("FoodAllocations", allocations);
		ListTag residents = new ListTag();
		for (Map.Entry<UUID, String> entry : residentIds.entrySet().stream()
			.sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
			.toList()) {
			Person person = world.person(entry.getValue());
			CompoundTag resident = new CompoundTag();
			resident.putUUID("VillagerId", entry.getKey());
			resident.putString("PersonId", person.id());
			resident.putString("HouseholdId", person.householdId());
			resident.putString("AgeBand", person.ageBand().name());
			resident.putLong("AgeDays", person.ageDays());
			resident.putInt("Hunger", person.hunger());
			resident.putInt("RiskPreference", person.riskPreference());
			if (!person.parents().isEmpty()) {
				resident.put("Parents", saveStrings(person.parents()));
			}
			CompoundTag skills = new CompoundTag();
			for (WorkType workType : WorkType.values()) {
				skills.putDouble(workType.name(), person.skill(workType));
			}
			resident.put("Skills", skills);
			resident.put("Social", saveSocial(person));
			residents.add(resident);
		}
		tag.put("Residents", residents);
		ListTag batches = new ListTag();
		for (ResourceBatch batch : world.batches()) {
			batches.add(saveBatch(batch));
		}
		tag.put("Batches", batches);
		ListTag events = new ListTag();
		for (SimulationEvent event : world.ledger().events()) {
			events.add(saveEvent(event));
		}
		tag.put("Events", events);
		ListTag savedNorms = new ListTag();
		for (Norm norm : world.normsForCamp(campOwner().id())) {
			savedNorms.add(saveNorm(norm));
		}
		tag.put("Norms", savedNorms);
		ListTag proposals = new ListTag();
		for (NormProposal proposal : world.proposalsForCamp(campOwner().id())) {
			proposals.add(saveProposal(proposal));
		}
		tag.put("NormProposals", proposals);
		return tag;
	}

	static SettlementSimulationState load(Settlement settlement, CompoundTag tag) {
		SettlementSimulationState state = create(settlement);
		state.survivalEnabled = tag.contains("SurvivalEnabled") && tag.getBoolean("SurvivalEnabled");
		state.productionEnabled = tag.contains("ProductionEnabled") && tag.getBoolean("ProductionEnabled");
		state.growthEnabled = tag.contains("GrowthEnabled") && tag.getBoolean("GrowthEnabled");
		state.skillsEnabled = tag.contains("SkillsEnabled") && tag.getBoolean("SkillsEnabled");
		state.normsEnabled = tag.contains("NormsEnabled") && tag.getBoolean("NormsEnabled");
		state.lastSurvivalDay = tag.contains("LastSurvivalDay") ? tag.getLong("LastSurvivalDay") : Long.MIN_VALUE;
		ListTag residents = tag.getList("Residents", ListTag.TAG_COMPOUND);
		Map<String, List<String>> parentage = new LinkedHashMap<>();
		for (int i = 0; i < residents.size(); i++) {
			CompoundTag resident = residents.getCompound(i);
			String householdId = resident.getString("HouseholdId");
			if (state.world.allPeople().stream().noneMatch(person -> person.householdId().equals(householdId))) {
				state.world.registerHousehold(new Household(householdId, campId(settlement)));
			}
			Person person = new Person(
				resident.getString("PersonId"),
				campId(settlement),
				householdId,
				Person.AgeBand.valueOf(resident.getString("AgeBand")),
				resident.getInt("Hunger"),
				resident.getInt("RiskPreference")
			);
			if (resident.contains("AgeDays")) {
				person.initializeAge(person.ageBand(), resident.getLong("AgeDays"));
			}
			CompoundTag skills = resident.getCompound("Skills");
			for (WorkType workType : WorkType.values()) {
				if (skills.contains(workType.name())) {
					person.setSkill(workType, skills.getDouble(workType.name()));
				}
			}
			if (resident.contains("Social")) {
				loadSocial(person, resident.getCompound("Social"));
			}
			state.world.registerPerson(person);
			state.residentIds.put(resident.getUUID("VillagerId"), person.id());
			if (resident.contains("Parents")) {
				parentage.put(person.id(), loadStrings(resident.getList("Parents", ListTag.TAG_STRING)));
			}
		}
		for (Map.Entry<String, List<String>> entry : parentage.entrySet()) {
			state.world.restoreParentage(entry.getKey(), entry.getValue());
		}
		List<SimulationWorld.RestoredBatch> batches = new ArrayList<>();
		ListTag batchList = tag.getList("Batches", ListTag.TAG_COMPOUND);
		for (int i = 0; i < batchList.size(); i++) {
			batches.add(loadBatch(batchList.getCompound(i)));
		}
		List<SimulationEvent> events = new ArrayList<>();
		ListTag eventList = tag.getList("Events", ListTag.TAG_COMPOUND);
		for (int i = 0; i < eventList.size(); i++) {
			events.add(loadEvent(eventList.getCompound(i)));
		}
		state.world.restoreState(tag.getLong("Day"), tag.getLong("NextBatchId"), batches, events);
		ListTag savedNorms = tag.getList("Norms", ListTag.TAG_COMPOUND);
		for (int i = 0; i < savedNorms.size(); i++) {
			state.world.registerNorm(loadNorm(savedNorms.getCompound(i)));
		}
		ListTag proposals = tag.getList("NormProposals", ListTag.TAG_COMPOUND);
		for (int i = 0; i < proposals.size(); i++) {
			state.world.registerProposal(loadProposal(proposals.getCompound(i)));
		}
		// Restore the recorded date verbatim. Only a new allocation may roll this ledger forward.
		state.foodAllocationDay = tag.contains("FoodAllocationDay") ? tag.getLong("FoodAllocationDay") : Long.MIN_VALUE;
		ListTag allocations = tag.getList("FoodAllocations", ListTag.TAG_COMPOUND);
		for (int i = 0; i < allocations.size(); i++) {
			CompoundTag allocation = allocations.getCompound(i);
			int units = allocation.getInt("Units");
			if (units <= 0) {
				throw new IllegalArgumentException("Restored food allocation must be positive");
			}
			state.foodAllocations.put(allocation.getString("PersonId"), units);
		}
		long lastMeetingDay = Long.MIN_VALUE;
		long savedMeetingDay = tag.getLong("LastMeetingDay");
		if (tag.contains("LastMeetingDay", ListTag.TAG_LONG)
			&& (savedMeetingDay == Long.MIN_VALUE || (savedMeetingDay >= 0 && savedMeetingDay <= state.world.day()))) {
			lastMeetingDay = savedMeetingDay;
		} else {
			// Legacy rounds reserve only their own current core day; they do not prove attendance.
			boolean deliberatedToday = state.world.ledger().eventsAtDay(state.world.day()).stream()
				.filter(event -> event.type() == EventType.NORM_DELIBERATED)
				.anyMatch(event -> state.world.proposal(event.context().getOrDefault("proposalId", ""))
					.filter(proposal -> proposal.campId().equals(state.campOwner().id())).isPresent());
			if (deliberatedToday) lastMeetingDay = state.world.day();
		}
		state.norms.restoreLastMeetingDay(state.campOwner().id(), lastMeetingDay);
		state.norms.restoreChargedSupervision();
		return state;
	}

	private static CompoundTag saveSocial(Person person) {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Grievance", person.grievance());
		tag.putInt("SocialHours", person.socialHours());
		tag.putInt("PreviousSocialHours", person.previousSocialHours());
		ListTag trust = new ListTag();
		for (Map.Entry<String, Integer> entry : person.trustedPeople().entrySet().stream()
			.sorted(Map.Entry.comparingByKey()).toList()) {
			CompoundTag relationship = new CompoundTag();
			relationship.putString("PersonId", entry.getKey());
			relationship.putInt("Value", entry.getValue());
			trust.add(relationship);
		}
		tag.put("Trust", trust);
		// Optional additive field: older readers ignore it and may discard it when saving.
		ListTag reciprocity = new ListTag();
		for (Map.Entry<String, Integer> entry : person.reciprocityMemories().entrySet().stream()
			.sorted(Map.Entry.comparingByKey()).toList()) {
			CompoundTag relationship = new CompoundTag();
			relationship.putString("PersonId", entry.getKey());
			relationship.putInt("Value", entry.getValue());
			reciprocity.add(relationship);
		}
		tag.put("Reciprocity", reciprocity);
		tag.put("ObservedNorms", saveStrings(person.observedNorms().stream().sorted().toList()));
		ListTag information = new ListTag();
		for (SocialInformation known : person.information()) {
			information.add(saveInformation(known));
		}
		tag.put("Information", information);
		return tag;
	}

	private static void loadSocial(Person person, CompoundTag tag) {
		person.restoreSocialState(tag.getInt("Grievance"), tag.getInt("SocialHours"), tag.getInt("PreviousSocialHours"));
		ListTag trust = tag.getList("Trust", ListTag.TAG_COMPOUND);
		for (int i = 0; i < trust.size(); i++) {
			CompoundTag relationship = trust.getCompound(i);
			person.setTrust(relationship.getString("PersonId"), relationship.getInt("Value"));
		}
		ListTag reciprocity = tag.getList("Reciprocity", ListTag.TAG_COMPOUND);
		for (int i = 0; i < reciprocity.size(); i++) {
			CompoundTag relationship = reciprocity.getCompound(i);
			// Skip just the malformed optional memory, preserving valid residents and resources.
			if (!relationship.contains("PersonId", ListTag.TAG_STRING)
				|| !relationship.contains("Value", ListTag.TAG_INT)) continue;
			String donorId = relationship.getString("PersonId");
			int count = relationship.getInt("Value");
			if (!donorId.isBlank() && count >= 0) person.restoreReciprocity(donorId, count);
		}
		for (String normId : loadStrings(tag.getList("ObservedNorms", ListTag.TAG_STRING))) {
			person.observeNorm(normId);
		}
		ListTag information = tag.getList("Information", ListTag.TAG_COMPOUND);
		for (int i = 0; i < information.size(); i++) {
			person.learnInformation(loadInformation(information.getCompound(i)));
		}
	}

	private static CompoundTag saveInformation(SocialInformation information) {
		CompoundTag tag = new CompoundTag();
		tag.putString("InformationId", information.informationId());
		tag.putString("Kind", information.kind().name());
		tag.putString("SubjectId", information.subjectId());
		tag.putString("ObjectId", information.objectId());
		tag.putLong("SourceEventId", information.sourceEventId());
		tag.putLong("LearnedDay", information.learnedDay());
		if (information.learnedFrom() != null) {
			tag.putString("LearnedFrom", information.learnedFrom());
		}
		tag.putDouble("Confidence", information.confidence());
		return tag;
	}

	private static SocialInformation loadInformation(CompoundTag tag) {
		return new SocialInformation(
			tag.getString("InformationId"),
			SocialInformation.Kind.valueOf(tag.getString("Kind")),
			tag.getString("SubjectId"),
			tag.getString("ObjectId"),
			tag.getLong("SourceEventId"),
			tag.getLong("LearnedDay"),
			tag.contains("LearnedFrom") ? tag.getString("LearnedFrom") : null,
			tag.getDouble("Confidence")
		);
	}

	private static CompoundTag saveNorm(Norm norm) {
		CompoundTag tag = new CompoundTag();
		tag.putString("NormId", norm.normId());
		tag.putString("CampId", norm.campId());
		tag.putInt("AllocationUnitCap", norm.allocationUnitCap());
		tag.put("Supporters", saveStrings(norm.supporters().stream().sorted().toList()));
		tag.put("Opponents", saveStrings(norm.opponents().stream().sorted().toList()));
		tag.put("KnownBy", saveStrings(norm.knownBy().stream().sorted().toList()));
		tag.putInt("Stability", norm.stability());
		tag.putString("Status", norm.status().name());
		ListTag revisions = new ListTag();
		for (Norm.Revision revision : norm.revisionHistory()) {
			CompoundTag saved = new CompoundTag();
			saved.putLong("Day", revision.day());
			saved.putString("ProposalId", revision.proposalId());
			saved.putLong("EventId", revision.eventId());
			saved.putInt("PreviousCap", revision.previousCap());
			saved.putInt("NewCap", revision.newCap());
			saved.putString("Action", revision.action().name());
			revisions.add(saved);
		}
		tag.put("Revisions", revisions);
		return tag;
	}

	private static Norm loadNorm(CompoundTag tag) {
		List<Norm.Revision> revisions = new ArrayList<>();
		ListTag savedRevisions = tag.getList("Revisions", ListTag.TAG_COMPOUND);
		for (int i = 0; i < savedRevisions.size(); i++) {
			CompoundTag revision = savedRevisions.getCompound(i);
			revisions.add(new Norm.Revision(
				revision.getLong("Day"),
				revision.getString("ProposalId"),
				revision.getLong("EventId"),
				revision.getInt("PreviousCap"),
				revision.getInt("NewCap"),
				Norm.Revision.Action.valueOf(revision.getString("Action"))
			));
		}
		return new Norm(
			tag.getString("NormId"),
			tag.getString("CampId"),
			tag.getInt("AllocationUnitCap"),
			Set.copyOf(loadStrings(tag.getList("Supporters", ListTag.TAG_STRING))),
			Set.copyOf(loadStrings(tag.getList("Opponents", ListTag.TAG_STRING))),
			Set.copyOf(loadStrings(tag.getList("KnownBy", ListTag.TAG_STRING))),
			tag.getInt("Stability"),
			Norm.Status.valueOf(tag.getString("Status")),
			revisions
		);
	}

	private static CompoundTag saveProposal(NormProposal proposal) {
		CompoundTag tag = new CompoundTag();
		tag.putString("ProposalId", proposal.proposalId());
		tag.putString("CampId", proposal.campId());
		tag.putString("NormId", proposal.normId());
		tag.putString("ProposerId", proposal.proposerId());
		tag.putLong("ProposedDay", proposal.proposedDay());
		tag.putLong("OriginEventId", proposal.originEventId());
		tag.putInt("AllocationUnitCap", proposal.allocationUnitCap());
		tag.putInt("Revision", proposal.revision());
		tag.putInt("Rounds", proposal.rounds());
		tag.putString("Status", proposal.status().name());
		ListTag positions = new ListTag();
		for (Map.Entry<String, NormProposal.Position> entry : proposal.positions().entrySet().stream()
			.sorted(Map.Entry.comparingByKey()).toList()) {
			CompoundTag position = new CompoundTag();
			position.putString("PersonId", entry.getKey());
			position.putString("Position", entry.getValue().name());
			positions.add(position);
		}
		tag.put("Positions", positions);
		return tag;
	}

	private static NormProposal loadProposal(CompoundTag tag) {
		Map<String, NormProposal.Position> positions = new LinkedHashMap<>();
		ListTag savedPositions = tag.getList("Positions", ListTag.TAG_COMPOUND);
		for (int i = 0; i < savedPositions.size(); i++) {
			CompoundTag position = savedPositions.getCompound(i);
			positions.put(position.getString("PersonId"), NormProposal.Position.valueOf(position.getString("Position")));
		}
		return NormProposal.restore(
			tag.getString("ProposalId"),
			tag.getString("CampId"),
			tag.getString("NormId"),
			tag.getString("ProposerId"),
			tag.getLong("ProposedDay"),
			tag.getLong("OriginEventId"),
			tag.getInt("AllocationUnitCap"),
			tag.getInt("Revision"),
			tag.getInt("Rounds"),
			NormProposal.Status.valueOf(tag.getString("Status")),
			positions
		);
	}

	private static CompoundTag saveBatch(ResourceBatch batch) {
		CompoundTag tag = new CompoundTag();
		tag.putString("BatchId", batch.batchId());
		tag.putString("ResourceType", batch.resourceType().name());
		tag.putLong("Quantity", batch.quantity());
		tag.putLong("NutritionPerUnit", batch.nutritionPerUnit());
		tag.putInt("Quality", batch.quality());
		tag.putString("HolderScope", batch.holder().scope().name());
		tag.putString("HolderId", batch.holder().id());
		tag.putString("Location", batch.location());
		if (batch.sourcePatchId() != null) {
			tag.putString("SourcePatchId", batch.sourcePatchId());
		}
		tag.putLong("OriginEventId", batch.originEventId());
		tag.put("ParentBatchIds", saveStrings(batch.parentBatchIds().stream().sorted().toList()));
		tag.putLong("HarvestDay", batch.harvestDay());
		tag.putLong("ExpiryDay", batch.expiryDay());
		tag.putLong("LastEventId", batch.lastEventId());
		return tag;
	}

	private static SimulationWorld.RestoredBatch loadBatch(CompoundTag tag) {
		return new SimulationWorld.RestoredBatch(
			tag.getString("BatchId"),
			ResourceType.valueOf(tag.getString("ResourceType")),
			tag.getLong("Quantity"),
			tag.getLong("NutritionPerUnit"),
			tag.getInt("Quality"),
			new StorageOwner(StorageOwner.Scope.valueOf(tag.getString("HolderScope")), tag.getString("HolderId")),
			tag.getString("Location"),
			tag.contains("SourcePatchId") ? tag.getString("SourcePatchId") : null,
			tag.getLong("OriginEventId"),
			Set.copyOf(loadStrings(tag.getList("ParentBatchIds", ListTag.TAG_STRING))),
			tag.getLong("HarvestDay"),
			tag.getLong("ExpiryDay"),
			tag.getLong("LastEventId")
		);
	}

	private static CompoundTag saveEvent(SimulationEvent event) {
		CompoundTag tag = new CompoundTag();
		tag.putLong("EventId", event.eventId());
		tag.putLong("Day", event.day());
		tag.putString("Type", event.type().name());
		tag.put("Actors", saveStrings(event.actors()));
		ListTag inputs = new ListTag();
		for (ResourceFlow input : event.inputs()) {
			inputs.add(saveFlow(input));
		}
		tag.put("Inputs", inputs);
		ListTag outputs = new ListTag();
		for (ResourceFlow output : event.outputs()) {
			outputs.add(saveFlow(output));
		}
		tag.put("Outputs", outputs);
		ListTag context = new ListTag();
		for (Map.Entry<String, String> entry : event.context().entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.toList()) {
			CompoundTag value = new CompoundTag();
			value.putString("Key", entry.getKey());
			value.putString("Value", entry.getValue());
			context.add(value);
		}
		tag.put("Context", context);
		tag.putLongArray("CausalLinks", event.causalLinks().stream().mapToLong(Long::longValue).toArray());
		return tag;
	}

	private static SimulationEvent loadEvent(CompoundTag tag) {
		List<ResourceFlow> inputs = new ArrayList<>();
		ListTag inputList = tag.getList("Inputs", ListTag.TAG_COMPOUND);
		for (int i = 0; i < inputList.size(); i++) {
			inputs.add(loadFlow(inputList.getCompound(i)));
		}
		List<ResourceFlow> outputs = new ArrayList<>();
		ListTag outputList = tag.getList("Outputs", ListTag.TAG_COMPOUND);
		for (int i = 0; i < outputList.size(); i++) {
			outputs.add(loadFlow(outputList.getCompound(i)));
		}
		Map<String, String> context = new LinkedHashMap<>();
		ListTag contextList = tag.getList("Context", ListTag.TAG_COMPOUND);
		for (int i = 0; i < contextList.size(); i++) {
			CompoundTag value = contextList.getCompound(i);
			context.put(value.getString("Key"), value.getString("Value"));
		}
		List<Long> causalLinks = new ArrayList<>();
		for (long causalLink : tag.getLongArray("CausalLinks")) {
			causalLinks.add(causalLink);
		}
		return new SimulationEvent(
			tag.getLong("EventId"),
			tag.getLong("Day"),
			EventType.valueOf(tag.getString("Type")),
			loadStrings(tag.getList("Actors", ListTag.TAG_STRING)),
			inputs,
			outputs,
			context,
			causalLinks
		);
	}

	private static CompoundTag saveFlow(ResourceFlow flow) {
		CompoundTag tag = new CompoundTag();
		if (flow.batchId() != null) {
			tag.putString("BatchId", flow.batchId());
		}
		tag.putString("ResourceType", flow.resourceType().name());
		tag.putLong("Quantity", flow.quantity());
		tag.putLong("NutritionPerUnit", flow.nutritionPerUnit());
		if (flow.fromLocation() != null) {
			tag.putString("FromLocation", flow.fromLocation());
		}
		if (flow.toLocation() != null) {
			tag.putString("ToLocation", flow.toLocation());
		}
		return tag;
	}

	private static ResourceFlow loadFlow(CompoundTag tag) {
		return new ResourceFlow(
			tag.contains("BatchId") ? tag.getString("BatchId") : null,
			ResourceType.valueOf(tag.getString("ResourceType")),
			tag.getLong("Quantity"),
			tag.getLong("NutritionPerUnit"),
			tag.contains("FromLocation") ? tag.getString("FromLocation") : null,
			tag.contains("ToLocation") ? tag.getString("ToLocation") : null
		);
	}

	private static ListTag saveStrings(List<String> values) {
		ListTag tag = new ListTag();
		for (String value : values) {
			tag.add(StringTag.valueOf(value));
		}
		return tag;
	}

	private static List<String> loadStrings(ListTag tag) {
		List<String> values = new ArrayList<>();
		for (int i = 0; i < tag.size(); i++) {
			values.add(tag.getString(i));
		}
		return values;
	}

	private static String campId(Settlement settlement) {
		return settlement.id().toString();
	}

	private static String personId(UUID villagerId) {
		return "minecraft-person:" + villagerId;
	}

	private static String householdId(UUID villagerId) {
		return "minecraft-household:" + villagerId;
	}
}
