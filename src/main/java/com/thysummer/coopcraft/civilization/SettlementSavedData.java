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
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

public final class SettlementSavedData extends SavedData {
	private static final String DATA_NAME = "coopcraft_settlements";
	private static final int DATA_VERSION = 24;
	private static final double MERGE_RADIUS_SQUARED = 48.0 * 48.0;
	private static final Factory<SettlementSavedData> FACTORY = new Factory<>(
		SettlementSavedData::new,
		SettlementSavedData::load,
		null
	);

	private final Map<UUID, FoodReserveTarget> foodReserveTargets = new LinkedHashMap<>();
	private final Map<BlockPos, Settlement> settlements = new LinkedHashMap<>();
	private final Map<UUID, Settlement> settlementsById = new LinkedHashMap<>();
	private final Set<UUID> ambiguousSettlementIds = new LinkedHashSet<>();
	private long membershipRevision;
	private SettlementResourceObservations resourceObservations = new SettlementResourceObservations();
	private SettlementSowingPlots sowingPlots = new SettlementSowingPlots();
	private SettlementShearingPermits shearingPermits = new SettlementShearingPermits();
	private PlayerProtectedAreas protectedAreas = new PlayerProtectedAreas();
	private SettlementGatheringAreas gatheringAreas = new SettlementGatheringAreas();
	private SettlementForagingAreas foragingAreas = new SettlementForagingAreas();
	private final SettlementForagingLeases foragingLeases = new SettlementForagingLeases();
	private final SettlementMaterialLeases materialLeases = new SettlementMaterialLeases();
	// Coordination only: never serialized, and old entity/session identities cannot survive reload.
	private final SettlementShearingLeases shearingLeases = new SettlementShearingLeases();
	private final SettlementResourceDiscovery resourceDiscovery = new SettlementResourceDiscovery();
	private final Map<UUID, SettlementMember> members = new LinkedHashMap<>();
	// Transient round-robin order; unrelated membership changes must not restart active sweeps.
	private final LinkedHashMap<UUID, SettlementMember> discoveryRoster = new LinkedHashMap<>();
	private final Map<UUID, Object> stockpileBindings = new LinkedHashMap<>();
	private final Map<UUID, Object> craftingStationBindings = new LinkedHashMap<>();
	private final Map<UUID, BlockPos> stockpiles = new LinkedHashMap<>();
	private final Map<UUID, BlockPos> hearths = new LinkedHashMap<>();
	private final Map<UUID, Set<BlockPos>> resourceSites = new LinkedHashMap<>();
	private final Map<UUID, BlockPos> craftingStations = new LinkedHashMap<>();
	private final Map<UUID, ConstructionJob> constructionJobs = new LinkedHashMap<>();
	private final Map<UUID, Set<BlockPos>> housingBeds = new LinkedHashMap<>();
	private final Map<UUID, BlockPos> grindingStations = new LinkedHashMap<>();
	private final Map<UUID, SocialOrganization> socialOrganizations = new LinkedHashMap<>();
	private final Map<UUID, SettlementStage> stages = new LinkedHashMap<>();
	private final Map<UUID, UUID> leaders = new LinkedHashMap<>();
	private final Map<UUID, MemberNeeds> memberNeeds = new LinkedHashMap<>();
	private final SettlementFoodRequests foodRequests = new SettlementFoodRequests();
	// Transient, dimension-local retry timing; intentionally absent from NBT and dirty tracking.
	private final ConstructionSiteRetries constructionSiteRetries = new ConstructionSiteRetries();

	ConstructionSiteRetries constructionSiteRetries() { return constructionSiteRetries; }

	SettlementFoodRequests foodRequests() { return foodRequests; }

	// Ephemeral teaching coordination; deliberately never serialized or marked dirty.
	private final SettlementTeachingSessions<SettlementAutonomousTeaching.Session> teachingSessions = new SettlementTeachingSessions<>();
	SettlementTeachingSessions<SettlementAutonomousTeaching.Session> teachingSessions() { return teachingSessions; }
	private final SettlementCouncilSessions<SettlementAutonomousMeetings.Session> councilSessions = new SettlementCouncilSessions<>();
	SettlementCouncilSessions<SettlementAutonomousMeetings.Session> councilSessions() { return councilSessions; }

	private final Map<UUID, SettlementSimulationState> simulations = new LinkedHashMap<>();

	public static SettlementSavedData get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
	}

	/** Immutable query view. Protection is shared by every settlement in this dimension. */
	public PlayerProtectedAreas protectedAreas() { return protectedAreas; }

	/** A veto only; false never grants permission to harvest or build. */
	public boolean isResidentModificationBlocked(BlockPos position) {
		return protectedAreas.contains(position);
	}

	/** Inclusive planar intersection; Y is intentionally unrestricted. */
	public boolean isResidentModificationBlocked(int minX, int minZ, int maxX, int maxZ) {
		return protectedAreas.intersects(minX, minZ, maxX, maxZ);
	}

	public PlayerProtectedAreas.AddResult addProtectedArea(UUID ownerId, String name, int x1, int z1, int x2, int z2) {
		PlayerProtectedAreas.Update update = protectedAreas.add(ownerId, name, x1, z1, x2, z2);
		if (update.status() == PlayerProtectedAreas.Status.ADDED) {
			protectedAreas = update.value();
			setDirty();
		}
		return new PlayerProtectedAreas.AddResult(update.status(), update.area());
	}

	public PlayerProtectedAreas.Status removeProtectedArea(UUID id, UUID requesterId, boolean operator) {
		PlayerProtectedAreas.Update update = protectedAreas.remove(id, requesterId, operator);
		if (update.status() == PlayerProtectedAreas.Status.REMOVED) {
			protectedAreas = update.value();
			setDirty();
		}
		return update.status();
	}

	public SettlementSowingPlots sowingPlots() { return sowingPlots; }
	static boolean withinSowingRange(Settlement camp, BlockPos pos) {
		if (camp == null || !SettlementSowingPlots.validPosition(pos)) return false;
		double dx = (double) pos.getX() - camp.center().getX(), dz = (double) pos.getZ() - camp.center().getZ();
		return dx * dx + dz * dz <= 4096;
	}
	private boolean ownsSowingPosition(Settlement camp, BlockPos pos) {
		if (!canonicalSettlement(camp) || !withinSowingRange(camp, pos)) return false;
		return all().stream().filter(this::canonicalSettlement)
			.min(java.util.Comparator.comparingDouble((Settlement candidate) -> {
				double dx = (double) pos.getX() - candidate.center().getX(), dz = (double) pos.getZ() - candidate.center().getZ();
				return dx * dx + dz * dz;
			}).thenComparing(candidate -> candidate.id().toString())).orElse(null) == camp;
	}
	public Optional<SettlementSowingPlots.Permit> sowingPermitOf(Settlement camp, BlockPos pos) {
		return ownsSowingPosition(camp, pos) ? sowingPlots.of(pos).filter(p -> p.campId().equals(camp.id())) : Optional.empty();
	}
	public List<BlockPos> sowingPositions(Settlement camp) {
		return sowingPlots.all().stream().filter(p -> sowingPermitOf(camp, p.position()).orElse(null) == p)
			.map(SettlementSowingPlots.Permit::position).toList();
	}
	public boolean canUseSowingPlot(Settlement camp, BlockPos pos) {
		return sowingPermitOf(camp, pos).isPresent() && !protectedAreas.blocked()
			&& !protectedAreas.contains(pos) && !protectedAreas.contains(pos.below());
	}
	public SettlementSowingPlots.Status addSowingPlot(Settlement camp, BlockPos pos) {
		if (sowingPlots.blocked()) return SettlementSowingPlots.Status.PRESERVED_UNSUPPORTED;
		if (!ownsSowingPosition(camp, pos) || protectedAreas.blocked() || protectedAreas.contains(pos) || protectedAreas.contains(pos.below()))
			return SettlementSowingPlots.Status.INVALID;
		return updateSowingPlots(sowingPlots.add(camp.id(), pos));
	}
	public SettlementSowingPlots.Status removeSowingPlot(Settlement camp, BlockPos pos) {
		if (sowingPlots.blocked()) return SettlementSowingPlots.Status.PRESERVED_UNSUPPORTED;
		if (!canonicalSettlement(camp) || !withinSowingRange(camp, pos)) return SettlementSowingPlots.Status.INVALID;
		return updateSowingPlots(sowingPlots.remove(camp.id(), pos));
	}
	private SettlementSowingPlots.Status updateSowingPlots(SettlementSowingPlots.Update update) {
		if (update.value() != sowingPlots) { sowingPlots = update.value(); setDirty(); }
		return update.status();
	}

	public boolean shearingPermitsBlocked() { return shearingPermits.blocked(); }
	public Object shearingRevision() { return shearingPermits.revision(); }
	public Optional<SettlementShearingPermits.Permit> shearingPermitOf(UUID sheepId) { return shearingPermits.of(sheepId); }
	public List<SettlementShearingPermits.Permit> shearingPermitsOf(Settlement camp) {
		return canonicalSettlement(camp) ? shearingPermits.ofSettlement(camp.id()) : List.of();
	}

	/** Explicit grant/replacement only; entity authenticity and live safety belong to the caller. */
	public SettlementShearingPermits.PermitResult permitShearing(Settlement camp, UUID authorizer, UUID sheepId) {
		if (shearingPermits.blocked()) return new SettlementShearingPermits.PermitResult(
			SettlementShearingPermits.Status.PRESERVED_UNSUPPORTED, null);
		if (!canonicalSettlement(camp)) return new SettlementShearingPermits.PermitResult(
			SettlementShearingPermits.Status.INVALID, null);
		SettlementShearingPermits.Update update = shearingPermits.grant(camp.id(), authorizer, sheepId);
		if (update.value() != shearingPermits) {
			shearingPermits = update.value();
			setDirty();
		}
		return new SettlementShearingPermits.PermitResult(update.status(), update.permit());
	}

	/** Revocation needs neither a loaded sheep nor a protection-free location. */
	public SettlementShearingPermits.Status revokeShearing(Settlement camp, UUID sheepId) {
		if (shearingPermits.blocked()) return SettlementShearingPermits.Status.PRESERVED_UNSUPPORTED;
		if (!canonicalSettlement(camp)) return SettlementShearingPermits.Status.INVALID;
		SettlementShearingPermits.Update update = shearingPermits.revoke(camp.id(), sheepId);
		if (update.value() != shearingPermits) {
			shearingPermits = update.value();
			setDirty();
		}
		return update.status();
	}

	boolean gatheringAreasBlocked() { return gatheringAreas.blocked(); }

	Optional<SettlementGatheringAreas.Area> gatheringAreaOf(Settlement settlement) {
		return canonicalSettlement(settlement) ? gatheringAreas.of(settlement.id()) : Optional.empty();
	}

	SettlementGatheringAreas.PermitResult permitGatheringArea(Settlement settlement, UUID authorizer,
			BlockPos center, int radius, SettlementGatheringAreas.Mode mode) {
		if (gatheringAreas.blocked()) return new SettlementGatheringAreas.PermitResult(
			SettlementGatheringAreas.Status.PRESERVED_UNSUPPORTED, null);
		if (!canonicalSettlement(settlement) || !SettlementGatheringAreas.validRange(settlement, center, radius)) {
			return new SettlementGatheringAreas.PermitResult(SettlementGatheringAreas.Status.INVALID, null);
		}
		SettlementGatheringAreas.Update update = gatheringAreas.permit(settlement.id(), authorizer, center, radius, mode);
		if (update.status() == SettlementGatheringAreas.Status.CREATED
				|| update.status() == SettlementGatheringAreas.Status.REPLACED) {
			gatheringAreas = update.value();
			setDirty();
		}
		return new SettlementGatheringAreas.PermitResult(update.status(), update.area());
	}

	SettlementGatheringAreas.Status clearGatheringArea(Settlement settlement) {
		if (gatheringAreas.blocked()) return SettlementGatheringAreas.Status.PRESERVED_UNSUPPORTED;
		if (!canonicalSettlement(settlement)) return SettlementGatheringAreas.Status.INVALID;
		SettlementGatheringAreas.Update update = gatheringAreas.revoke(settlement.id());
		if (update.status() == SettlementGatheringAreas.Status.REMOVED) {
			gatheringAreas = update.value();
			setDirty();
		}
		return update.status();
	}

	/** A live permission check. A permit never overrides current player protection. */
	boolean canGatherMaterial(Settlement settlement, BlockPos position, SettlementResourceObservations.Kind kind) {
		if (!canonicalSettlement(settlement) || position == null || protectedAreas.contains(position)) return false;
		SettlementGatheringAreas.Area area = gatheringAreas.of(settlement.id()).orElse(null);
		return area != null && area.mode().permits(kind) && area.contains(position)
			&& SettlementGatheringAreas.withinCamp(settlement, area.centerX(), area.centerZ(), area.radius());
	}

	boolean foragingAreasBlocked() { return foragingAreas.blocked(); }
	SettlementForagingLeases foragingLeases() { return foragingLeases; }
	SettlementMaterialLeases materialLeases() { return materialLeases; }
	SettlementShearingLeases shearingLeases() { return shearingLeases; }

	Optional<SettlementForagingAreas.Area> foragingAreaOf(Settlement settlement) {
		return canonicalSettlement(settlement) ? foragingAreas.of(settlement.id()) : Optional.empty();
	}

	SettlementForagingAreas.PermitResult permitForagingArea(Settlement settlement, UUID authorizer,
			BlockPos center, int radius) {
		if (foragingAreas.blocked()) return new SettlementForagingAreas.PermitResult(
			SettlementForagingAreas.Status.PRESERVED_UNSUPPORTED, null);
		if (!canonicalSettlement(settlement) || !SettlementForagingAreas.validRange(settlement, center, radius)) {
			return new SettlementForagingAreas.PermitResult(SettlementForagingAreas.Status.INVALID, null);
		}
		SettlementForagingAreas.Update update = foragingAreas.permit(settlement.id(), authorizer, center, radius);
		if (update.status() == SettlementForagingAreas.Status.CREATED
				|| update.status() == SettlementForagingAreas.Status.REPLACED) {
			foragingAreas = update.value();
			setDirty();
		}
		return new SettlementForagingAreas.PermitResult(update.status(), update.area());
	}

	SettlementForagingAreas.Status clearForagingArea(Settlement settlement) {
		if (foragingAreas.blocked()) return SettlementForagingAreas.Status.PRESERVED_UNSUPPORTED;
		if (!canonicalSettlement(settlement)) return SettlementForagingAreas.Status.INVALID;
		SettlementForagingAreas.Update update = foragingAreas.revoke(settlement.id());
		if (update.status() == SettlementForagingAreas.Status.REMOVED) {
			foragingAreas = update.value();
			setDirty();
		}
		return update.status();
	}

	/** A live permission check. A permit never overrides current player protection. */
	boolean canHarvestBerries(Settlement settlement, BlockPos position) {
		if (!canonicalSettlement(settlement) || position == null || protectedAreas.contains(position)) return false;
		SettlementForagingAreas.Area area = foragingAreas.of(settlement.id()).orElse(null);
		return area != null && area.contains(position)
			&& SettlementForagingAreas.withinCamp(settlement, area.centerX(), area.centerZ(), area.radius());
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
		indexSettlement(settlement);
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

	public FoodReserveTarget foodReserveTargetOf(Settlement settlement) {
		return foodReserveTargets.getOrDefault(settlement.id(), FoodReserveTarget.NORMAL);
	}

	boolean setFoodReserveTarget(Settlement settlement, FoodReserveTarget target) {
		Objects.requireNonNull(target, "target");
		if (foodReserveTargetOf(settlement) == target) return false;
		foodReserveTargets.put(settlement.id(), target);
		setDirty();
		return true;
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

	/** Retains the existing public entry point for callers that do not need the accepted roster. */
	public void registerLoadedResidents(ServerLevel level, Settlement settlement, List<Villager> villagers) {
		registerAndGetLoadedResidents(level, settlement, villagers);
	}

	/** Returns only confirmed local members; scans never remove or reassign existing membership. */
	List<Villager> registerAndGetLoadedResidents(ServerLevel level, Settlement settlement, List<Villager> villagers) {
		if (level == null || settlement == null || villagers == null) return List.of();
		long day = level.getDayTime() / Level.TICKS_PER_DAY;
		List<SettlementMembership.Candidate> candidates = new ArrayList<>();
		Map<UUID, Villager> loaded = new LinkedHashMap<>();
		for (Villager villager : villagers) {
			SettlementMembership.Candidate candidate = SettlementMembership.observe(level, settlement.center(), villager);
			candidates.add(candidate);
			if (candidate != null && candidate.loaded()) {
				loaded.put(candidate.villagerId(), villager);
			}
		}
		List<Villager> accepted = SettlementMembership.registerCandidates(
			SettlementMembershipClaims.sources(level), level.dimension(), this, settlement, candidates, day).stream()
			.map(loaded::get).toList();
		if (accepted.isEmpty()) return accepted;
		boolean changed = false;
		if (leaderOf(settlement).isEmpty()) {
			Optional<Villager> leader = accepted.stream()
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
		for (Villager villager : accepted) {
			changed |= simulation.mapResident(
				villager.getUUID(), villager.isBaby(), needsOf(settlement, villager.getUUID()).satiety()
			);
			VillagerNames.assignIfAbsent(this, settlement, villager);
		}
		if (changed) {
			setDirty();
		}
		return accepted;
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

	enum MemberRegistrationResult {
		ADDED, ALREADY_REGISTERED, OTHER_SETTLEMENT, INVALID;

		boolean accepted() { return this == ADDED || this == ALREADY_REGISTERED; }
	}

	/** Read-only preflight, also used before recording a birth in the simulation. */
	MemberRegistrationResult memberRegistrationResult(Settlement settlement, UUID villagerId, long joinedDay) {
		if (settlement == null || villagerId == null || joinedDay < 0
				|| !settlement.equals(settlements.get(settlement.center()))) {
			return MemberRegistrationResult.INVALID;
		}
		SettlementMember existing = members.get(villagerId);
		if (existing == null) return MemberRegistrationResult.ADDED;
		return existing.settlementId().equals(settlement.id())
			? MemberRegistrationResult.ALREADY_REGISTERED : MemberRegistrationResult.OTHER_SETTLEMENT;
	}

	/** Same-dimension join primitive. Production callers must first pass the global claim gate. */
	MemberRegistrationResult registerMember(Settlement settlement, UUID villagerId, long joinedDay) {
		MemberRegistrationResult result = memberRegistrationResult(settlement, villagerId, joinedDay);
		if (result != MemberRegistrationResult.ADDED) return result;
		SettlementMember member = new SettlementMember(villagerId, settlement.id(), joinedDay);
		members.put(villagerId, member);
		discoveryRoster.put(villagerId, member);
		membershipRevision++;
		setDirty();
		return result;
	}

	/** Includes claims whose settlement record is missing; read-only and dimension-local. */
	Optional<SettlementMember> rawMember(UUID villagerId) {
		return Optional.ofNullable(members.get(villagerId));
	}

	public boolean isRegistered(UUID villagerId) {
		return villagerId != null && members.containsKey(villagerId);
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

	/** Called only for an observed death, never absence, unloading or a dimension transfer. */
	public static boolean removeObservedDeadMember(ServerLevel level, UUID villagerId) {
		return removeObservedDeadMember(SettlementMembershipClaims.sources(level),
			level == null ? null : level.dimension(), villagerId);
	}

	static boolean removeObservedDeadMember(List<SettlementMembershipClaims.Source> sources,
			ResourceKey<Level> dimension, UUID villagerId) {
		var claims = SettlementMembershipClaims.resolve(sources, dimension, villagerId);
		if (claims.status() != SettlementMembershipClaims.Status.UNIQUE
				&& claims.status() != SettlementMembershipClaims.Status.ORPHAN) return false;
		claims.claims().getFirst().data().removeMember(villagerId);
		return true;
	}

	public void removeMember(UUID villagerId) {
		foodRequests.cancelResident(villagerId);
		boolean changed = members.remove(villagerId) != null;
		discoveryRoster.remove(villagerId);
		if (changed) membershipRevision++;
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

	Object memberIdentity(UUID resident) { return members.get(resident); }

	private void indexSettlement(Settlement settlement) {
		if (ambiguousSettlementIds.contains(settlement.id())) return;
		if (settlementsById.putIfAbsent(settlement.id(), settlement) != null) {
			settlementsById.remove(settlement.id());
			ambiguousSettlementIds.add(settlement.id());
		}
	}
	Settlement settlementById(UUID id) { return settlementsById.get(id); }
	boolean canonicalSettlement(Settlement settlement) {
		return settlement != null && settlements.get(settlement.center()) == settlement && settlementById(settlement.id()) == settlement;
	}
	long membershipRevision() { return membershipRevision; }
	boolean hasDiscoveryMembers() { return !discoveryRoster.isEmpty(); }
	SettlementMember nextDiscoveryMember() {
		var next = discoveryRoster.pollFirstEntry();
		if (next == null) return null;
		discoveryRoster.putLast(next.getKey(), next.getValue());
		return next.getValue();
	}
	SettlementResourceDiscovery resourceDiscovery() { return resourceDiscovery; }
	boolean resourceObservationsBlocked() { return resourceObservations.blocked(); }
	List<SettlementResourceObservations.Observation> resourceObservationsOf(Settlement settlement) {
		return resourceObservations.of(settlement.id());
	}
	boolean resourceObservationSuppressed(Settlement settlement, BlockPos position) {
		return resourceObservations.suppressed(settlement.id(), position);
	}

	boolean recordResourceObservation(Settlement settlement, BlockPos position, boolean ripeBerry) {
		return recordResourceObservation(settlement, position, ripeBerry ? SettlementResourceObservations.Kind.SWEET_BERRY : null);
	}

	boolean recordResourceObservation(Settlement settlement, BlockPos position, SettlementResourceObservations.Kind kind) {
		if (!canonicalSettlement(settlement)) return false;
		boolean changed = resourceObservations.observe(settlement.id(), position, kind);
		if (changed) setDirty();
		return changed;
	}

	SettlementResourceObservations.Change changeResourceObservation(Settlement settlement, String id, boolean suppress) {
		if (!canonicalSettlement(settlement)) return SettlementResourceObservations.Change.NOT_FOUND;
		var result = suppress ? resourceObservations.suppress(settlement.id(), id) : resourceObservations.resume(settlement.id(), id);
		if (result == SettlementResourceObservations.Change.CHANGED) setDirty();
		return result;
	}

	Object stockpileBindingToken(Settlement settlement) {
		return stockpileBindings.get(settlement.id());
	}

	public void setStockpile(Settlement settlement, BlockPos pos) {
		stockpileBindings.put(settlement.id(), new Object());
		foodRequests.cancel(settlement);
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

	Object craftingStationBindingToken(Settlement settlement) {
		return craftingStationBindings.get(settlement.id());
	}

	public void setCraftingStation(Settlement settlement, BlockPos pos) {
		if (pos.equals(craftingStations.get(settlement.id()))) return;
		craftingStationBindings.put(settlement.id(), new Object());
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
			constructionJobs.put(settlement.id(), job.advance());
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

	/** Immutable snapshot: callers cannot mutate the saved bed ledger while iterating. */
	public Set<BlockPos> housingBeds(Settlement settlement) {
		return Set.copyOf(housingBeds.getOrDefault(settlement.id(), Set.of()));
	}

	public boolean hasHousingBed(Settlement settlement, BlockPos pos) {
		return housingBeds.getOrDefault(settlement.id(), Set.of()).contains(pos);
	}

	public void removeHousingBed(Settlement settlement, BlockPos pos) {
		Set<BlockPos> beds = housingBeds.get(settlement.id());
		if (beds != null && beds.remove(pos)) {
			if (beds.isEmpty()) housingBeds.remove(settlement.id());
			setDirty();
		}
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
			CompoundTag settlementTag = settlement.save();
			settlementTag.putString("FoodReserveTarget", foodReserveTargetOf(settlement).id());
			settlementList.add(settlementTag);
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
		tag.put("ResourceObservations", resourceObservations.save());
		tag.put("GatheringAreas", gatheringAreas.save());
		tag.put("ForagingAreas", foragingAreas.save());
		tag.put("SowingPlots", sowingPlots.save());
		Tag shearingTag = shearingPermits.save();
		if (shearingTag == null) tag.remove("ShearingPermits");
		else tag.put("ShearingPermits", shearingTag);
		Tag protectionTag = protectedAreas.save();
		if (protectionTag == null) tag.remove("PlayerProtectedAreas");
		else tag.put("PlayerProtectedAreas", protectionTag);

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
		data.resourceObservations = SettlementResourceObservations.load(tag.get("ResourceObservations"));
		data.gatheringAreas = SettlementGatheringAreas.load(tag.get("GatheringAreas"));
		data.foragingAreas = SettlementForagingAreas.load(tag.get("ForagingAreas"));
		data.sowingPlots = SettlementSowingPlots.load(tag.get("SowingPlots"));
		data.shearingPermits = SettlementShearingPermits.load(tag.get("ShearingPermits"), tag.get("DataVersion"));
		data.protectedAreas = PlayerProtectedAreas.load(tag.get("PlayerProtectedAreas"), tag.get("DataVersion"));

		ListTag settlementList = tag.getList("Settlements", ListTag.TAG_COMPOUND);
		for (int i = 0; i < settlementList.size(); i++) {
			Settlement settlement = Settlement.load(settlementList.getCompound(i));
			data.settlements.put(settlement.center(), settlement);
			data.foodReserveTargets.put(settlement.id(), FoodReserveTarget.byId(
				settlementList.getCompound(i).getString("FoodReserveTarget")));
		}

		// Derive only from the final canonical center map, never from overwritten load records.
		for (Settlement settlement : data.settlements.values()) data.indexSettlement(settlement);

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

		ListTag memberList = tag.getList("Members", ListTag.TAG_COMPOUND);
		for (int i = 0; i < memberList.size(); i++) {
			SettlementMember member = SettlementMember.load(memberList.getCompound(i));
			data.members.put(member.villagerId(), member);
		}

		data.discoveryRoster.putAll(data.members);

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
			// Assign legacy job identities once, even when the job remains blocked.
			if (!constructionTag.contains("JobId")) data.setDirty();
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
				.ifPresent(settlement -> {
					data.simulations.put(settlementId, SettlementSimulationState.load(settlement, simulationTag));
					// Persist a newly assigned clock for legacy states, even without further activity.
					if (!simulationTag.contains("FoodClockId")) data.setDirty();
				});
		}
		return data;
	}
}
