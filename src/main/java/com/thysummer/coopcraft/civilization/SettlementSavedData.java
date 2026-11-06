package com.thysummer.coopcraft.civilization;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

public final class SettlementSavedData extends SavedData {
	private static final String DATA_NAME = "coopcraft_settlements";
	private static final int DATA_VERSION = 26;
	private static final double MERGE_RADIUS_SQUARED = 48.0 * 48.0;
	private static final double MEMBER_RADIUS_SQUARED = 64.0 * 64.0;
	private static final Factory<SettlementSavedData> FACTORY = new Factory<>(
		SettlementSavedData::new,
		SettlementSavedData::load,
		null
	);

	private final Map<BlockPos, Settlement> settlements = new LinkedHashMap<>();
	private final Map<UUID, SettlementMember> members = new LinkedHashMap<>();
	private final Map<UUID, BlockPos> stockpiles = new LinkedHashMap<>();
	private final Map<UUID, BlockPos> hearths = new LinkedHashMap<>();
	private final Map<UUID, Set<BlockPos>> resourceSites = new LinkedHashMap<>();
	private final Map<UUID, BlockPos> craftingStations = new LinkedHashMap<>();
	private final Map<UUID, ConstructionJob> constructionJobs = new LinkedHashMap<>();
	private final Map<UUID, Set<BlockPos>> housingBeds = new LinkedHashMap<>();
	private final Map<UUID, BlockPos> grindingStations = new LinkedHashMap<>();
	private final Map<UUID, BlockPos> smeltingStations = new LinkedHashMap<>();
	private final Map<UUID, Map<BlockPos, BlockPos>> poweredProducers = new LinkedHashMap<>();
	private final Map<UUID, Set<BlockPos>> archiveStations = new LinkedHashMap<>();
	private final Map<UUID, Set<BlockPos>> planningCores = new LinkedHashMap<>();
	private final Map<UUID, SettlementPlan> plans = new LinkedHashMap<>();
	private final Map<UUID, Map<String, ProductionStats>> productionStatistics = new LinkedHashMap<>();
	private final Map<UUID, SocialOrganization> socialOrganizations = new LinkedHashMap<>();
	private final Map<UUID, SettlementStage> stages = new LinkedHashMap<>();
	private final Map<UUID, ResourceLocation> eras = new LinkedHashMap<>();
	private final Map<UUID, SettlementOrigin> origins = new LinkedHashMap<>();
	private final Map<UUID, Set<ResourceLocation>> eraMilestones = new LinkedHashMap<>();
	private final Map<UUID, UUID> leaders = new LinkedHashMap<>();
	private final Map<UUID, MemberNeeds> memberNeeds = new LinkedHashMap<>();
	private final Map<UUID, SettlementSimulationState> simulations = new LinkedHashMap<>();

	public static SettlementSavedData get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
	}

	public Optional<Settlement> find(BlockPos center) {
		Settlement exact = settlements.get(center);
		if (exact != null) {
			return Optional.of(exact);
		}
		return settlements.values().stream()
			.filter(settlement -> settlement.center().distSqr(center) <= MERGE_RADIUS_SQUARED)
			.min(Comparator.comparingDouble(settlement -> settlement.center().distSqr(center)));
	}

	public Settlement getOrCreate(ServerLevel level, BlockPos center) {
		return getOrCreate(
			center,
			SettlementNaming.forSettlement(level.dimension(), center),
			level.getDayTime() / Level.TICKS_PER_DAY
		);
	}

	Settlement getOrCreate(BlockPos center, String name, long foundedDay) {
		Optional<Settlement> existing = find(center);
		if (existing.isPresent()) {
			return existing.get();
		}

		UUID id = UUID.nameUUIDFromBytes(
			("coopcraft:" + name + ':' + center.asLong()).getBytes(StandardCharsets.UTF_8)
		);
		Settlement settlement = new Settlement(id, center, name, foundedDay);
		settlements.put(center, settlement);
		socialOrganizations.put(id, SocialOrganization.PRIMITIVE_COMMUNE);
		setDirty();
		return settlement;
	}

	public List<Settlement> all() {
		return List.copyOf(settlements.values());
	}

	public SocialOrganization socialOrganizationOf(Settlement settlement) {
		return socialOrganizations.getOrDefault(settlement.id(), SocialOrganization.PRIMITIVE_COMMUNE);
	}

	public boolean hasOrigin(Settlement settlement) {
		return origins.containsKey(settlement.id());
	}

	public SettlementOrigin originOf(Settlement settlement) {
		return origins.getOrDefault(settlement.id(), SettlementOrigin.NEW_CAMP);
	}

	void setOrigin(Settlement settlement, SettlementOrigin origin) {
		Objects.requireNonNull(origin, "origin");
		if (origins.put(settlement.id(), origin) != origin) {
			setDirty();
		}
	}

	public SettlementStage stageOf(Settlement settlement) {
		return stages.getOrDefault(settlement.id(), SettlementStage.PRIMITIVE);
	}

	void setStage(Settlement settlement, SettlementStage stage) {
		Objects.requireNonNull(stage, "stage");
		if (stages.put(settlement.id(), stage) != stage) {
			setDirty();
		}
	}

	public CivilizationEra eraOf(Settlement settlement) {
		return CivilizationEras.byId(eras.getOrDefault(settlement.id(), CivilizationEras.PRIMITIVE));
	}

	void setEra(Settlement settlement, CivilizationEra era) {
		Objects.requireNonNull(era, "era");
		if (eras.put(settlement.id(), era.id()) != era.id()) {
			setDirty();
		}
	}

	public Set<ResourceLocation> eraMilestonesOf(Settlement settlement) {
		return Set.copyOf(eraMilestones.getOrDefault(settlement.id(), Set.of()));
	}

	public void completeEraMilestone(Settlement settlement, ResourceLocation milestone) {
		Objects.requireNonNull(milestone, "milestone");
		if (!CivilizationEras.isMilestone(milestone)) {
			throw new IllegalArgumentException("Unknown civilization milestone: " + milestone);
		}
		if (eraMilestones.computeIfAbsent(settlement.id(), key -> new LinkedHashSet<>()).add(milestone)) {
			setDirty();
		}
	}

	public void registerLoadedResidents(ServerLevel level, Settlement settlement, List<Villager> villagers) {
		long day = level.getDayTime() / Level.TICKS_PER_DAY;
		boolean changed = false;
		for (Villager villager : villagers) {
			SettlementMember existing = members.get(villager.getUUID());
			if (existing == null || !existing.settlementId().equals(settlement.id())) {
				members.put(villager.getUUID(), new SettlementMember(villager.getUUID(), settlement.id(), day));
				changed = true;
			}
		}

		for (SettlementMember member : new ArrayList<>(members.values())) {
			if (!member.settlementId().equals(settlement.id())) {
				continue;
			}
			if (level.getEntity(member.villagerId()) instanceof Villager loaded
				&& loaded.distanceToSqr(settlement.center().getCenter()) > MEMBER_RADIUS_SQUARED) {
				members.remove(member.villagerId());
				changed = true;
			}
		}
		if (leaderOf(settlement).isEmpty()) {
			Optional<Villager> leader = villagers.stream()
				.filter(villager -> !villager.isBaby() && isMember(settlement, villager.getUUID()))
				.max(Comparator.comparingInt((Villager villager) -> personalityOf(settlement, villager.getUUID()).leadershipScore())
					.thenComparingInt(villager -> personalityOf(settlement, villager.getUUID()).diligence())
					.thenComparing(villager -> villager.getUUID().toString()));
			if (leader.isPresent()) {
				leaders.put(settlement.id(), leader.get().getUUID());
				changed = true;
			}
		}
		SettlementSimulationState simulation = getOrCreateSimulation(settlement);
		for (Villager villager : villagers) {
			changed |= simulation.mapResident(
				villager.getUUID(), villager.isBaby(), needsOf(settlement, villager.getUUID()).satiety()
			);
			VillagerNames.assignOrMigrate(this, settlement, villager);
		}
		if (changed) {
			setDirty();
		}
	}

	public SettlementSimulationState getOrCreateSimulation(Settlement settlement) {
		return simulations.computeIfAbsent(settlement.id(), id -> {
			setDirty();
			return SettlementSimulationState.create(settlement);
		});
	}

	public Optional<SettlementSimulationState> simulationOf(Settlement settlement) {
		return Optional.ofNullable(simulations.get(settlement.id()));
	}

	void registerMember(Settlement settlement, UUID villagerId, long joinedDay) {
		members.put(villagerId, new SettlementMember(villagerId, settlement.id(), joinedDay));
		setDirty();
	}

	public boolean isMember(Settlement settlement, UUID villagerId) {
		SettlementMember member = members.get(villagerId);
		return member != null && member.settlementId().equals(settlement.id());
	}

	public VillagerPersonality personalityOf(Settlement settlement, UUID villagerId) {
		SettlementMember member = members.get(villagerId);
		return member != null && member.settlementId().equals(settlement.id())
			? member.personality()
			: VillagerPersonality.fromVillager(villagerId);
	}

	public MemberNeeds needsOf(Settlement settlement, UUID villagerId) {
		return isMember(settlement, villagerId) ? memberNeeds.getOrDefault(villagerId, MemberNeeds.DEFAULT) : MemberNeeds.DEFAULT;
	}

	void setSatiety(Settlement settlement, UUID villagerId, int satiety) {
		if (!isMember(settlement, villagerId)) {
			return;
		}
		int clamped = Math.max(0, Math.min(100, satiety));
		if (needsOf(settlement, villagerId).satiety() != clamped) {
			memberNeeds.put(villagerId, new MemberNeeds(clamped));
			setDirty();
		}
	}

	public Optional<UUID> leaderOf(Settlement settlement) {
		UUID leaderId = leaders.get(settlement.id());
		if (leaderId != null && isMember(settlement, leaderId)) {
			return Optional.of(leaderId);
		}
		return Optional.empty();
	}

	void setLeader(Settlement settlement, UUID villagerId) {
		if (!isMember(settlement, villagerId)) {
			throw new IllegalArgumentException("Leader must be a settlement member");
		}
		leaders.put(settlement.id(), villagerId);
		setDirty();
	}

	public Optional<Settlement> findSettlementForVillager(UUID villagerId) {
		SettlementMember member = members.get(villagerId);
		if (member == null) {
			return Optional.empty();
		}
		return settlements.values().stream()
			.filter(settlement -> settlement.id().equals(member.settlementId()))
			.findFirst();
	}

	public void removeMember(UUID villagerId) {
		boolean changed = members.remove(villagerId) != null;
		if (leaders.values().removeIf(leaderId -> leaderId.equals(villagerId))) {
			changed = true;
		}
		if (memberNeeds.remove(villagerId) != null) {
			changed = true;
		}
		if (changed) {
			setDirty();
		}
	}

	public int registeredCount(Settlement settlement) {
		return (int) members.values().stream()
			.filter(member -> member.settlementId().equals(settlement.id()))
			.count();
	}

	List<SettlementMember> membersOf(Settlement settlement) {
		return members.values().stream()
			.filter(member -> member.settlementId().equals(settlement.id()))
			.sorted(Comparator.comparing(member -> member.villagerId().toString()))
			.toList();
	}

	public Optional<BlockPos> getStockpile(Settlement settlement) {
		return Optional.ofNullable(stockpiles.get(settlement.id()));
	}

	public void setStockpile(Settlement settlement, BlockPos pos) {
		stockpiles.put(settlement.id(), pos.immutable());
		setDirty();
	}

	public Optional<BlockPos> getHearth(Settlement settlement) {
		return Optional.ofNullable(hearths.get(settlement.id()));
	}

	public void setHearth(Settlement settlement, BlockPos pos) {
		hearths.put(settlement.id(), pos.immutable());
		setDirty();
	}

	public void clearHearth(Settlement settlement) {
		if (hearths.remove(settlement.id()) != null) {
			setDirty();
		}
	}

	public boolean toggleResource(Settlement settlement, BlockPos pos) {
		Set<BlockPos> sites = resourceSites.computeIfAbsent(settlement.id(), key -> new LinkedHashSet<>());
		boolean added = sites.add(pos.immutable());
		if (!added) {
			sites.remove(pos);
		}
		if (sites.isEmpty()) {
			resourceSites.remove(settlement.id());
		}
		setDirty();
		return added;
	}

	public void removeResource(Settlement settlement, BlockPos pos) {
		Set<BlockPos> sites = resourceSites.get(settlement.id());
		if (sites == null) {
			return;
		}
		if (sites.remove(pos)) {
			setDirty();
		}
		if (sites.isEmpty()) {
			resourceSites.remove(settlement.id());
		}
	}

	public Set<BlockPos> resourcesOf(Settlement settlement) {
		return resourceSites.getOrDefault(settlement.id(), Set.of());
	}

	public Optional<BlockPos> getCraftingStation(Settlement settlement) {
		return Optional.ofNullable(craftingStations.get(settlement.id()));
	}

	public void setCraftingStation(Settlement settlement, BlockPos pos) {
		craftingStations.put(settlement.id(), pos.immutable());
		setDirty();
	}

	public Optional<ConstructionJob> getConstructionJob(Settlement settlement) {
		return Optional.ofNullable(constructionJobs.get(settlement.id()));
	}

	public void setConstructionJob(Settlement settlement, ConstructionJob job) {
		constructionJobs.put(settlement.id(), job);
		setDirty();
	}

	public void advanceConstruction(Settlement settlement) {
		ConstructionJob job = constructionJobs.get(settlement.id());
		if (job != null) {
			constructionJobs.put(settlement.id(), new ConstructionJob(job.blueprintId(), job.origin(), job.nextIndex() + 1));
			setDirty();
		}
	}

	public void cancelConstruction(Settlement settlement) {
		if (constructionJobs.remove(settlement.id()) != null) {
			setDirty();
		}
	}

	public void completeConstruction(Settlement settlement) {
		if (constructionJobs.remove(settlement.id()) != null) {
			setDirty();
		}
	}

	public int housingCapacity(Settlement settlement) {
		return housingBeds.getOrDefault(settlement.id(), Set.of()).size();
	}

	public void addHousingBed(Settlement settlement, BlockPos pos) {
		Set<BlockPos> beds = housingBeds.computeIfAbsent(settlement.id(), key -> new LinkedHashSet<>());
		if (beds.add(pos.immutable())) {
			setDirty();
		}
	}

	public Optional<BlockPos> getGrindingStation(Settlement settlement) {
		return Optional.ofNullable(grindingStations.get(settlement.id()));
	}

	public void setGrindingStation(Settlement settlement, BlockPos pos) {
		grindingStations.put(settlement.id(), pos.immutable());
		setDirty();
	}

	public Optional<BlockPos> getSmeltingStation(Settlement settlement) {
		return Optional.ofNullable(smeltingStations.get(settlement.id()));
	}

	public void setSmeltingStation(Settlement settlement, BlockPos pos) {
		smeltingStations.put(settlement.id(), pos.immutable());
		setDirty();
	}

	public void recordPoweredProducer(Settlement settlement, BlockPos machine, BlockPos source) {
		Map<BlockPos, BlockPos> producers = poweredProducers.computeIfAbsent(settlement.id(), key -> new LinkedHashMap<>());
		producers.put(machine.immutable(), source.immutable());
		boolean shared = Set.copyOf(producers.values()).size() < producers.size();
		if (shared) {
			completeEraMilestone(settlement, CivilizationEras.AUTOMATED_PRODUCTION);
		}
		setDirty();
	}

	public Set<BlockPos> archiveStationsOf(Settlement settlement) {
		return Set.copyOf(archiveStations.getOrDefault(settlement.id(), Set.of()));
	}

	public void registerArchive(Settlement settlement, BlockPos pos) {
		if (archiveStations.computeIfAbsent(settlement.id(), key -> new LinkedHashSet<>()).add(pos.immutable())) {
			setDirty();
		}
	}

	public void unregisterArchive(Settlement settlement, BlockPos pos) {
		Set<BlockPos> stations = archiveStations.get(settlement.id());
		if (stations != null && stations.remove(pos)) {
			if (stations.isEmpty()) {
				archiveStations.remove(settlement.id());
			}
			setDirty();
		}
	}

	public Set<BlockPos> planningCoresOf(Settlement settlement) {
		return Set.copyOf(planningCores.getOrDefault(settlement.id(), Set.of()));
	}

	public void registerPlanningCore(Settlement settlement, BlockPos pos) {
		if (planningCores.computeIfAbsent(settlement.id(), key -> new LinkedHashSet<>()).add(pos.immutable())) {
			setDirty();
		}
	}

	public void unregisterPlanningCore(Settlement settlement, BlockPos pos) {
		Set<BlockPos> cores = planningCores.get(settlement.id());
		if (cores != null && cores.remove(pos)) {
			if (cores.isEmpty()) {
				planningCores.remove(settlement.id());
			}
			setDirty();
		}
	}

	public Optional<SettlementPlan> activePlanOf(Settlement settlement) {
		return latestPlanOf(settlement).filter(plan -> plan.status() == SettlementPlan.Status.ACTIVE);
	}

	public Optional<SettlementPlan> latestPlanOf(Settlement settlement) {
		return Optional.ofNullable(plans.get(settlement.id()));
	}

	public Optional<SettlementPlan> activePlanForWorker(Settlement settlement, UUID workerId) {
		return activePlanOf(settlement).filter(plan -> plan.workerId().equals(workerId));
	}

	public boolean startPlan(Settlement settlement, SettlementPlan.Kind kind, UUID workerId, long day) {
		if (activePlanOf(settlement).isPresent()) {
			return false;
		}
		plans.put(settlement.id(), SettlementPlan.create(kind, workerId, day));
		setDirty();
		return true;
	}

	public boolean recordPlanOutput(Settlement settlement, UUID workerId, String item, int amount, long day) {
		SettlementPlan plan = activePlanForWorker(settlement, workerId).orElse(null);
		if (plan == null || !plan.kind().outputItem().equals(item)) {
			return false;
		}
		SettlementPlan updated = plan.advance(amount, day);
		plans.put(settlement.id(), updated);
		if (updated.status() == SettlementPlan.Status.COMPLETED) {
			completeEraMilestone(settlement, CivilizationEras.AUTONOMOUS_PLANNING);
		}
		setDirty();
		return true;
	}

	public ProductionStats productionStatsOf(Settlement settlement, String recipe) {
		return productionStatistics.getOrDefault(settlement.id(), Map.of()).getOrDefault(recipe, ProductionStats.initial());
	}

	public int productionIntervalOf(Settlement settlement, String recipe) {
		return usesIntelligence(settlement) ? productionStatsOf(settlement, recipe).recommendedIntervalTicks() : ProductionStats.DEFAULT_INTERVAL;
	}

	public ProductionStats recordProductionCycle(Settlement settlement, String recipe, long gameTime) {
		ProductionStats current = productionStatsOf(settlement, recipe);
		if (!usesIntelligence(settlement)) {
			return current;
		}
		ProductionStats updated = current.observe(gameTime);
		productionStatistics.computeIfAbsent(settlement.id(), key -> new LinkedHashMap<>()).put(recipe, updated);
		if (updated.adaptiveLearned()) {
			completeEraMilestone(settlement, CivilizationEras.ADAPTIVE_LEARNING);
		}
		if (updated.selfImproved()
			&& eraMilestonesOf(settlement).contains(CivilizationEras.AUTONOMOUS_PLANNING)
			&& eraMilestonesOf(settlement).contains(CivilizationEras.ADAPTIVE_LEARNING)) {
			completeEraMilestone(settlement, CivilizationEras.SELF_IMPROVING_PRODUCTION);
		}
		setDirty();
		return updated;
	}

	private boolean usesIntelligence(Settlement settlement) {
		return eraOf(settlement).id().equals(CivilizationEras.INFORMATION) || eraOf(settlement).id().equals(CivilizationEras.INTELLIGENT);
	}

	int size() {
		return settlements.size();
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		tag.putInt("DataVersion", DATA_VERSION);

		ListTag settlementList = new ListTag();
		for (Settlement settlement : settlements.values()) {
			settlementList.add(settlement.save());
		}
		tag.put("Settlements", settlementList);

		ListTag socialList = new ListTag();
		for (Settlement settlement : settlements.values()) {
			CompoundTag socialTag = socialOrganizationOf(settlement).save();
			socialTag.putUUID("SettlementId", settlement.id());
			socialList.add(socialTag);
		}
		tag.put("SocialOrganizations", socialList);

		ListTag stageList = new ListTag();
		for (Settlement settlement : settlements.values()) {
			CompoundTag stageTag = new CompoundTag();
			stageTag.putUUID("SettlementId", settlement.id());
			stageTag.putString("Stage", stageOf(settlement).id());
			stageList.add(stageTag);
		}
		tag.put("Stages", stageList);

		ListTag eraList = new ListTag();
		for (Settlement settlement : settlements.values()) {
			CompoundTag eraTag = new CompoundTag();
			eraTag.putUUID("SettlementId", settlement.id());
			eraTag.putString("Era", eraOf(settlement).id().toString());
			eraList.add(eraTag);
		}
		tag.put("Eras", eraList);

		ListTag originList = new ListTag();
		for (Map.Entry<UUID, SettlementOrigin> entry : origins.entrySet()) {
			CompoundTag originTag = new CompoundTag();
			originTag.putUUID("SettlementId", entry.getKey());
			originTag.putString("Origin", entry.getValue().id());
			originList.add(originTag);
		}
		tag.put("Origins", originList);

		ListTag eraMilestoneList = new ListTag();
		for (Map.Entry<UUID, Set<ResourceLocation>> entry : eraMilestones.entrySet()) {
			for (ResourceLocation milestone : entry.getValue()) {
				CompoundTag milestoneTag = new CompoundTag();
				milestoneTag.putUUID("SettlementId", entry.getKey());
				milestoneTag.putString("Milestone", milestone.toString());
				eraMilestoneList.add(milestoneTag);
			}
		}
		tag.put("EraMilestones", eraMilestoneList);

		ListTag memberList = new ListTag();
		for (SettlementMember member : members.values()) {
			memberList.add(member.save());
		}
		tag.put("Members", memberList);

		ListTag needsList = new ListTag();
		for (Map.Entry<UUID, MemberNeeds> entry : memberNeeds.entrySet()) {
			CompoundTag needsTag = entry.getValue().save();
			needsTag.putUUID("VillagerId", entry.getKey());
			needsList.add(needsTag);
		}
		tag.put("MemberNeeds", needsList);

		ListTag leaderList = new ListTag();
		for (Map.Entry<UUID, UUID> entry : leaders.entrySet()) {
			CompoundTag leaderTag = new CompoundTag();
			leaderTag.putUUID("SettlementId", entry.getKey());
			leaderTag.putUUID("VillagerId", entry.getValue());
			leaderList.add(leaderTag);
		}
		tag.put("Leaders", leaderList);

		ListTag stockpileList = new ListTag();
		for (Map.Entry<UUID, BlockPos> entry : stockpiles.entrySet()) {
			CompoundTag stockpileTag = new CompoundTag();
			stockpileTag.putUUID("SettlementId", entry.getKey());
			stockpileTag.putLong("Position", entry.getValue().asLong());
			stockpileList.add(stockpileTag);
		}
		tag.put("Stockpiles", stockpileList);

		ListTag hearthList = new ListTag();
		for (Map.Entry<UUID, BlockPos> entry : hearths.entrySet()) {
			CompoundTag hearthTag = new CompoundTag();
			hearthTag.putUUID("SettlementId", entry.getKey());
			hearthTag.putLong("Position", entry.getValue().asLong());
			hearthList.add(hearthTag);
		}
		tag.put("Hearths", hearthList);

		ListTag resourceList = new ListTag();
		for (Map.Entry<UUID, Set<BlockPos>> entry : resourceSites.entrySet()) {
			for (BlockPos pos : entry.getValue()) {
				CompoundTag resourceTag = new CompoundTag();
				resourceTag.putUUID("SettlementId", entry.getKey());
				resourceTag.putLong("Position", pos.asLong());
				resourceList.add(resourceTag);
			}
		}
		tag.put("ResourceSites", resourceList);

		ListTag stationList = new ListTag();
		for (Map.Entry<UUID, BlockPos> entry : craftingStations.entrySet()) {
			CompoundTag stationTag = new CompoundTag();
			stationTag.putUUID("SettlementId", entry.getKey());
			stationTag.putLong("Position", entry.getValue().asLong());
			stationList.add(stationTag);
		}
		tag.put("CraftingStations", stationList);

		ListTag constructionList = new ListTag();
		for (Map.Entry<UUID, ConstructionJob> entry : constructionJobs.entrySet()) {
			CompoundTag constructionTag = entry.getValue().save();
			constructionTag.putUUID("SettlementId", entry.getKey());
			constructionList.add(constructionTag);
		}
		tag.put("ConstructionJobs", constructionList);

		ListTag bedList = new ListTag();
		for (Map.Entry<UUID, Set<BlockPos>> entry : housingBeds.entrySet()) {
			for (BlockPos pos : entry.getValue()) {
				CompoundTag bedTag = new CompoundTag();
				bedTag.putUUID("SettlementId", entry.getKey());
				bedTag.putLong("Position", pos.asLong());
				bedList.add(bedTag);
			}
		}
		tag.put("HousingBeds", bedList);

		ListTag grindingList = new ListTag();
		for (Map.Entry<UUID, BlockPos> entry : grindingStations.entrySet()) {
			CompoundTag grindingTag = new CompoundTag();
			grindingTag.putUUID("SettlementId", entry.getKey());
			grindingTag.putLong("Position", entry.getValue().asLong());
			grindingList.add(grindingTag);
		}
		tag.put("GrindingStations", grindingList);

		ListTag smeltingList = new ListTag();
		for (Map.Entry<UUID, BlockPos> entry : smeltingStations.entrySet()) {
			CompoundTag smeltingTag = new CompoundTag();
			smeltingTag.putUUID("SettlementId", entry.getKey());
			smeltingTag.putLong("Position", entry.getValue().asLong());
			smeltingList.add(smeltingTag);
		}
		tag.put("SmeltingStations", smeltingList);

		ListTag producerList = new ListTag();
		for (Map.Entry<UUID, Map<BlockPos, BlockPos>> entry : poweredProducers.entrySet()) {
			for (Map.Entry<BlockPos, BlockPos> producer : entry.getValue().entrySet()) {
				CompoundTag producerTag = new CompoundTag();
				producerTag.putUUID("SettlementId", entry.getKey());
				producerTag.putLong("Machine", producer.getKey().asLong());
				producerTag.putLong("Source", producer.getValue().asLong());
				producerList.add(producerTag);
			}
		}
		tag.put("PoweredProducers", producerList);

		ListTag archiveList = new ListTag();
		for (Map.Entry<UUID, Set<BlockPos>> entry : archiveStations.entrySet()) {
			for (BlockPos pos : entry.getValue()) {
				CompoundTag archiveTag = new CompoundTag();
				archiveTag.putUUID("SettlementId", entry.getKey());
				archiveTag.putLong("Position", pos.asLong());
				archiveList.add(archiveTag);
			}
		}
		tag.put("ArchiveStations", archiveList);

		ListTag coreList = new ListTag();
		for (Map.Entry<UUID, Set<BlockPos>> entry : planningCores.entrySet()) {
			for (BlockPos pos : entry.getValue()) {
				CompoundTag coreTag = new CompoundTag();
				coreTag.putUUID("SettlementId", entry.getKey());
				coreTag.putLong("Position", pos.asLong());
				coreList.add(coreTag);
			}
		}
		tag.put("PlanningCores", coreList);

		ListTag planList = new ListTag();
		for (Map.Entry<UUID, SettlementPlan> entry : plans.entrySet()) {
			CompoundTag planTag = entry.getValue().save();
			planTag.putUUID("SettlementId", entry.getKey());
			planList.add(planTag);
		}
		tag.put("Plans", planList);

		ListTag statisticsList = new ListTag();
		for (Map.Entry<UUID, Map<String, ProductionStats>> entry : productionStatistics.entrySet()) {
			for (Map.Entry<String, ProductionStats> statistic : entry.getValue().entrySet()) {
				CompoundTag statisticTag = new CompoundTag();
				statisticTag.putUUID("SettlementId", entry.getKey());
				statisticTag.putString("Recipe", statistic.getKey());
				statisticTag.put("Statistics", statistic.getValue().save());
				statisticsList.add(statisticTag);
			}
		}
		tag.put("ProductionStatistics", statisticsList);

		ListTag simulationList = new ListTag();
		for (Map.Entry<UUID, SettlementSimulationState> entry : simulations.entrySet()) {
			CompoundTag simulationTag = entry.getValue().save();
			simulationTag.putUUID("SettlementId", entry.getKey());
			simulationList.add(simulationTag);
		}
		tag.put("Simulations", simulationList);
		return tag;
	}

	static SettlementSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
		SettlementSavedData data = new SettlementSavedData();

		ListTag settlementList = tag.getList("Settlements", ListTag.TAG_COMPOUND);
		for (int i = 0; i < settlementList.size(); i++) {
			Settlement settlement = Settlement.load(settlementList.getCompound(i));
			data.settlements.put(settlement.center(), settlement);
		}

		ListTag socialList = tag.getList("SocialOrganizations", ListTag.TAG_COMPOUND);
		for (int i = 0; i < socialList.size(); i++) {
			CompoundTag socialTag = socialList.getCompound(i);
			data.socialOrganizations.put(socialTag.getUUID("SettlementId"), SocialOrganization.load(socialTag));
		}
		for (Settlement settlement : data.settlements.values()) {
			data.socialOrganizations.putIfAbsent(settlement.id(), SocialOrganization.PRIMITIVE_COMMUNE);
		}

		ListTag stageList = tag.getList("Stages", ListTag.TAG_COMPOUND);
		for (int i = 0; i < stageList.size(); i++) {
			CompoundTag stageTag = stageList.getCompound(i);
			data.stages.put(stageTag.getUUID("SettlementId"), SettlementStage.byId(stageTag.getString("Stage")));
		}

		ListTag eraList = tag.getList("Eras", ListTag.TAG_COMPOUND);
		for (int i = 0; i < eraList.size(); i++) {
			CompoundTag eraTag = eraList.getCompound(i);
			data.eras.put(eraTag.getUUID("SettlementId"), ResourceLocation.parse(eraTag.getString("Era")));
		}

		ListTag originList = tag.getList("Origins", ListTag.TAG_COMPOUND);
		for (int i = 0; i < originList.size(); i++) {
			CompoundTag originTag = originList.getCompound(i);
			data.origins.put(originTag.getUUID("SettlementId"), SettlementOrigin.byId(originTag.getString("Origin")));
		}

		ListTag eraMilestoneList = tag.getList("EraMilestones", ListTag.TAG_COMPOUND);
		for (int i = 0; i < eraMilestoneList.size(); i++) {
			CompoundTag milestoneTag = eraMilestoneList.getCompound(i);
			data.eraMilestones.computeIfAbsent(milestoneTag.getUUID("SettlementId"), key -> new LinkedHashSet<>())
				.add(ResourceLocation.parse(milestoneTag.getString("Milestone")));
		}

		ListTag memberList = tag.getList("Members", ListTag.TAG_COMPOUND);
		for (int i = 0; i < memberList.size(); i++) {
			SettlementMember member = SettlementMember.load(memberList.getCompound(i));
			data.members.put(member.villagerId(), member);
		}

		ListTag needsList = tag.getList("MemberNeeds", ListTag.TAG_COMPOUND);
		for (int i = 0; i < needsList.size(); i++) {
			CompoundTag needsTag = needsList.getCompound(i);
			data.memberNeeds.put(needsTag.getUUID("VillagerId"), MemberNeeds.load(needsTag));
		}

		ListTag leaderList = tag.getList("Leaders", ListTag.TAG_COMPOUND);
		for (int i = 0; i < leaderList.size(); i++) {
			CompoundTag leaderTag = leaderList.getCompound(i);
			data.leaders.put(leaderTag.getUUID("SettlementId"), leaderTag.getUUID("VillagerId"));
		}

		ListTag stockpileList = tag.getList("Stockpiles", ListTag.TAG_COMPOUND);
		for (int i = 0; i < stockpileList.size(); i++) {
			CompoundTag stockpileTag = stockpileList.getCompound(i);
			data.stockpiles.put(stockpileTag.getUUID("SettlementId"), BlockPos.of(stockpileTag.getLong("Position")));
		}

		ListTag hearthList = tag.getList("Hearths", ListTag.TAG_COMPOUND);
		for (int i = 0; i < hearthList.size(); i++) {
			CompoundTag hearthTag = hearthList.getCompound(i);
			data.hearths.put(hearthTag.getUUID("SettlementId"), BlockPos.of(hearthTag.getLong("Position")));
		}

		ListTag resourceList = tag.getList("ResourceSites", ListTag.TAG_COMPOUND);
		for (int i = 0; i < resourceList.size(); i++) {
			CompoundTag resourceTag = resourceList.getCompound(i);
			UUID settlementId = resourceTag.getUUID("SettlementId");
			data.resourceSites.computeIfAbsent(settlementId, key -> new LinkedHashSet<>())
				.add(BlockPos.of(resourceTag.getLong("Position")));
		}

		ListTag stationList = tag.getList("CraftingStations", ListTag.TAG_COMPOUND);
		for (int i = 0; i < stationList.size(); i++) {
			CompoundTag stationTag = stationList.getCompound(i);
			data.craftingStations.put(stationTag.getUUID("SettlementId"), BlockPos.of(stationTag.getLong("Position")));
		}

		ListTag constructionList = tag.getList("ConstructionJobs", ListTag.TAG_COMPOUND);
		for (int i = 0; i < constructionList.size(); i++) {
			CompoundTag constructionTag = constructionList.getCompound(i);
			ConstructionJob job = ConstructionJob.load(constructionTag);
			data.constructionJobs.put(constructionTag.getUUID("SettlementId"), job);
		}

		ListTag bedList = tag.getList("HousingBeds", ListTag.TAG_COMPOUND);
		for (int i = 0; i < bedList.size(); i++) {
			CompoundTag bedTag = bedList.getCompound(i);
			UUID settlementId = bedTag.getUUID("SettlementId");
			data.housingBeds.computeIfAbsent(settlementId, key -> new LinkedHashSet<>())
				.add(BlockPos.of(bedTag.getLong("Position")));
		}

		ListTag grindingList = tag.getList("GrindingStations", ListTag.TAG_COMPOUND);
		for (int i = 0; i < grindingList.size(); i++) {
			CompoundTag grindingTag = grindingList.getCompound(i);
			data.grindingStations.put(grindingTag.getUUID("SettlementId"), BlockPos.of(grindingTag.getLong("Position")));
		}

		ListTag smeltingList = tag.getList("SmeltingStations", ListTag.TAG_COMPOUND);
		for (int i = 0; i < smeltingList.size(); i++) {
			CompoundTag smeltingTag = smeltingList.getCompound(i);
			data.smeltingStations.put(smeltingTag.getUUID("SettlementId"), BlockPos.of(smeltingTag.getLong("Position")));
		}

		ListTag producerList = tag.getList("PoweredProducers", ListTag.TAG_COMPOUND);
		for (int i = 0; i < producerList.size(); i++) {
			CompoundTag producerTag = producerList.getCompound(i);
			data.poweredProducers.computeIfAbsent(producerTag.getUUID("SettlementId"), key -> new LinkedHashMap<>())
				.put(BlockPos.of(producerTag.getLong("Machine")), BlockPos.of(producerTag.getLong("Source")));
		}

		ListTag archiveList = tag.getList("ArchiveStations", ListTag.TAG_COMPOUND);
		for (int i = 0; i < archiveList.size(); i++) {
			CompoundTag archiveTag = archiveList.getCompound(i);
			data.archiveStations.computeIfAbsent(archiveTag.getUUID("SettlementId"), key -> new LinkedHashSet<>())
				.add(BlockPos.of(archiveTag.getLong("Position")));
		}

		ListTag coreList = tag.getList("PlanningCores", ListTag.TAG_COMPOUND);
		for (int i = 0; i < coreList.size(); i++) {
			CompoundTag coreTag = coreList.getCompound(i);
			data.planningCores.computeIfAbsent(coreTag.getUUID("SettlementId"), key -> new LinkedHashSet<>())
				.add(BlockPos.of(coreTag.getLong("Position")));
		}

		ListTag planList = tag.getList("Plans", ListTag.TAG_COMPOUND);
		for (int i = 0; i < planList.size(); i++) {
			CompoundTag planTag = planList.getCompound(i);
			data.plans.put(planTag.getUUID("SettlementId"), SettlementPlan.load(planTag));
		}

		ListTag statisticsList = tag.getList("ProductionStatistics", ListTag.TAG_COMPOUND);
		for (int i = 0; i < statisticsList.size(); i++) {
			CompoundTag statisticTag = statisticsList.getCompound(i);
			data.productionStatistics.computeIfAbsent(statisticTag.getUUID("SettlementId"), key -> new LinkedHashMap<>())
				.put(statisticTag.getString("Recipe"), ProductionStats.load(statisticTag.getCompound("Statistics")));
		}

		ListTag simulationList = tag.getList("Simulations", ListTag.TAG_COMPOUND);
		for (int i = 0; i < simulationList.size(); i++) {
			CompoundTag simulationTag = simulationList.getCompound(i);
			UUID settlementId = simulationTag.getUUID("SettlementId");
			data.settlements.values().stream()
				.filter(settlement -> settlement.id().equals(settlementId))
				.findFirst()
				.ifPresent(settlement -> data.simulations.put(
					settlementId, SettlementSimulationState.load(settlement, simulationTag)
				));
		}
		return data;
	}
}
