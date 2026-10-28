package com.thysummer.coopcraft.civilization;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

public final class SettlementSavedData extends SavedData {
	private static final String DATA_NAME = "coopcraft_settlements";
	private static final int DATA_VERSION = 9;
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
	private final Map<UUID, Set<BlockPos>> resourceSites = new LinkedHashMap<>();
	private final Map<UUID, BlockPos> craftingStations = new LinkedHashMap<>();
	private final Map<UUID, ConstructionJob> constructionJobs = new LinkedHashMap<>();
	private final Map<UUID, Set<BlockPos>> housingBeds = new LinkedHashMap<>();
	private final Map<UUID, BlockPos> grindingStations = new LinkedHashMap<>();
	private final Map<UUID, SocialOrganization> socialOrganizations = new LinkedHashMap<>();
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
