package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public class VillagerWorkGoal extends Goal {
	private static final int SEARCH_INTERVAL = 20;
	private static final int SEARCH_RADIUS_SQUARED = 12 * 12;
	private static final double INTERACTION_DISTANCE_SQUARED = 9.0;
	private static final double BUILD_INTERACTION_DISTANCE_SQUARED = ConstructionApproach.REACH_SQUARED;
	private static final int HAND_GATHER_TICKS = 60;
	private static final int TOOL_GATHER_TICKS = 30;
	private static final int CRAFT_TICKS = 60;
	private static final int BUILD_TICKS = 20;
	private static final int EAT_TICKS = 40;
	private static final int FORAGE_TICKS = 40;
	private static final long FORAGE_RETRY_TICKS = 200;
	private static final int SHEARING_TICKS = 40;
	private static final int SHEARING_MAX_TRAVEL_TICKS = 600;
	private static final int SHEARING_REPATH_TICKS = 10;

	private final Villager villager;
	private Action action;
	private SettlementAutonomousTeaching.Session teachingSession;
	private final SettlementCargoApproach.Search teachingApproach = new SettlementCargoApproach.Search();
	private final SettlementCargoApproach.Cooldown teachingCooldown = new SettlementCargoApproach.Cooldown();
	private BlockPos teachingPathTarget;
	private BlockPos selectedGatherTarget;
	private List<SettlementMaterialGathering.Choice> materialCandidates = List.of();
	private SettlementMaterialGathering.Ticket materialTicket;
	private SettlementMaterialLeases materialRegistry;
	private SettlementMaterialLeases.Lease materialLease;
	private BlockPos target;
	private BlockPos forageTarget;
	private SettlementForagingLeases forageRegistry;
	private SettlementForagingLeases.Lease forageLease;
	private SettlementShearing.Session shearingSession;
	private SettlementShearing.LoanTicket shearsLoanTicket;
	private final SettlementCargoApproach.Cooldown shearsLoanCooldown = new SettlementCargoApproach.Cooldown();
	private final SettlementCargoApproach.Cooldown shearingCooldown = new SettlementCargoApproach.Cooldown();
	private BlockPos shearingPathTarget;
	private int shearingPathAge;
	private ConstructionSystem.BuildTarget currentBuildTarget;
	private SettlementMaterialCrafting.Recipe currentMaterialRecipe;
	private SettlementStarterCrafting.Ticket currentStarterCraft;
	private SettlementWorkbenchCrafting.Ticket workbenchTicket;
	private Settlement settlement;
	private WorkRole role = WorkRole.GENERALIST;
	private int progress;
	private int searchCooldown;
	private int travelTicks;
	private final ConstructionApproach.Search constructionApproach = new ConstructionApproach.Search();
	private long forageCooldownUntil;
	private SettlementFoodRequests.Ticket foodTicket;
	private SettlementMeetingInvitations.Ticket meetingTicket;
	private BlockPos meetingStore;
	private SettlementCargoDelivery.Ticket cargoTicket;
	private final SettlementCargoApproach.Search cargoApproach = new SettlementCargoApproach.Search();
	private final SettlementCargoApproach.Cooldown cargoCooldown = new SettlementCargoApproach.Cooldown();
	private String cargoBlocker;
	private java.util.function.BooleanSupplier supplyAuthorization;
	private BlockPos supplyStore;
	private final SettlementCargoApproach.Cooldown supplyCooldown = new SettlementCargoApproach.Cooldown();

	public VillagerWorkGoal(Villager villager) {
		this.villager = villager;
		setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		try {
			// Selection is a boundary, never a licence to interrupt a valid current job.
			if (action != null && isTargetValid()) return true;
			if (teachingSession != null) cancelTeaching();
			releaseMaterialLease();
			cancelShearing();
			cancelWorkbenchCrafting();
			// A released claim must never leave an old GATHER action eligible for an early return.
			if (action == Action.GATHER) { action = null; target = null; }
			selectedGatherTarget = null;
			materialCandidates = List.of();
			releaseForageLease();
			refreshMeetingInvitation();
			Optional<Settlement> workSettlement = findWorkSettlement();
			if (workSettlement.isEmpty()) {
				cargoTicket = null;
				if (!villager.isBaby() && hasReturnCargo()) cargoBlocker = "cargo_store_unavailable";
				action = null;
				target = null;
				return false;
			}
			settlement = workSettlement.get();
			// A persisted tool is return custody, never permission to resume a pre-stop sheep job.
			if (hasAnyShearsLoan()) {
				// Feeding is safe with custody in hand; a full/unreachable store must not cause starvation.
				if (shouldEat()) {
					action = Action.EAT; target = settlementData().getStockpile(settlement).orElse(null); progress = 0;
					return target != null;
				}
				action = null; target = null;
				return shearsLoanCooldown.ready(villager.level().getGameTime()) && prepareShearsLoan(true);
			}
			role = LaborAllocator.roleOf(
				(ServerLevel) villager.level(), settlementData(), settlement, villager.getUUID()
			);
			if (action == Action.EAT && isTargetValid()) {
				return true;
			}
			if (shouldEat()) {
				action = Action.EAT;
				target = settlementData().getStockpile(settlement).orElse(null);
				progress = 0;
				return target != null;
			}
			if (meetingTicket != null && action != null && action != Action.ATTEND_MEETING && action != Action.RETURN_TO_STOCKPILE && isTargetValid()) return true;
			if (meetingTicket != null) {
				action = Action.ATTEND_MEETING;
				target = meetingStore;
				return true;
			}
			if (villager.isBaby()) {
				action = null;
				target = null;
				return false;
			}
			boolean carrying = hasReturnCargo();
			if (!carrying) cargoBlocker = null;
			// Emergency food still wins; reserve replenishment must not indefinitely postpone cargo.
			if (carrying && !emergencyForage() && cargoCooldown.ready(villager.level().getGameTime())) {
				if (prepareCargoReturn()) return true;
			}
			// Existing invitations are read without scanning nearby residents, even during search backoff.
			if (action == null && prepareTeaching(false)) return true;
			if (searchCooldown > 0) {
				searchCooldown--;
				return false;
			}
			if (settlementData().getStockpile(settlement).isPresent() && shouldForage() && (!carrying || emergencyForage())) {
				ForageChoice choice = findRipeBerryBush();
				if (choice != null) {
					action = choice.crop() ? Action.HARVEST_CROP : Action.FORAGE;
					forageTarget = choice.position();
					target = choice.position();
					progress = 0;
					return true;
				}
				forageCooldownUntil = villager.level().getGameTime() + FORAGE_RETRY_TICKS;
			}
			if (carrying && cargoCooldown.ready(villager.level().getGameTime())) {
				if (prepareCargoReturn()) return true;
			}
			if (isSupplyAction() && isTargetValid()) return true;
			if (supplyCooldown.ready(villager.level().getGameTime()) && hasReturnableSupply()) {
				if (prepareBuildSupply(true)) return true;
			}
			if (action != null && isTargetValid()) return true;
			action = null; target = null;
			if (prepareTeaching(true)) return true;
			if (action != Action.BUILD) {
				Optional<ConstructionSystem.BuildTarget> buildTarget = ConstructionSystem.nextBuildTarget(
					(ServerLevel) villager.level(), settlementData(), settlement, villager
				);
				if (buildTarget.isPresent()) {
					currentBuildTarget = buildTarget.get();
					if (requiresBuildSupply() && !hasCurrentSupply()) {
						if (supplyCooldown.ready(villager.level().getGameTime())
							&& !ConstructionSupply.hasAny(villager.getInventory()) && prepareBuildSupply(false)) return true;
						currentBuildTarget = null;
					} else {
						action = Action.BUILD;
						target = currentBuildTarget.position();
						progress = 0;
						return true;
					}
				}
			}
			if (action != null && isTargetValid()) {
				return true;
			}

			action = chooseAction();
			searchCooldown = SEARCH_INTERVAL;
			target = chooseTarget(action);
			if (action == Action.GATHER && !claimGatherTarget()) { action = null; target = null; }
			progress = 0;
			return action != null;
		} catch (RuntimeException | Error failure) { releaseMaterialLease(); cancelWorkbenchCrafting(); cancelShearing(); cancelTeaching(); throw failure; }
	}

	@Override
	public boolean canContinueToUse() {
		try {
			refreshMeetingInvitation();
			if (action == Action.ATTEND_LESSON && !teachingValid()) { cancelTeaching(); return false; }
			if (hasAnyShearsLoan() && action != Action.SHEAR_SHEEP && action != Action.RETURN_SHEARS && action != Action.EAT) {
				cancelShearing(); return false;
			}
			if (action != null && action != Action.EAT && action != Action.ATTEND_MEETING
				&& action != Action.RETURN_TO_STOCKPILE && action != Action.RETURN_SHEARS && !villager.isBaby()
				&& !isWorkbenchAction() && settlement != null && hasReturnCargo()
				&& cargoCooldown.ready(villager.level().getGameTime()) && !emergencyForage()) {
				releaseForageLease();
				releaseMaterialLease();
				cancelShearing();
				return false;
			}
			if ((isWorkbenchAction() || action == Action.SHEAR_SHEEP || action == Action.BORROW_SHEARS) && (shouldEat() || meetingTicket != null || emergencyForage())) {
				cancelWorkbenchCrafting(); cancelShearing(); return false;
			}
			boolean continuing = action != null && isTargetValid() && findWorkSettlement().isPresent();
			if (!continuing) { releaseForageLease(); releaseMaterialLease(); cancelWorkbenchCrafting(); cancelShearing(); cancelTeaching(); }
			return continuing;
		} catch (RuntimeException | Error failure) { releaseMaterialLease(); cancelWorkbenchCrafting(); cancelShearing(); cancelTeaching(); throw failure; }
	}

	@Override
	public void start() {
		try {
			progress = 0;
			travelTicks = 0;
			if (action == Action.ATTEND_LESSON) { villager.getNavigation().stop(); tickTeaching(); }
			else if (isShearsLoanAction()) { villager.getNavigation().stop(); tickShearsLoan(); }
			else if (isWorkbenchAction()) { villager.getNavigation().stop(); tickWorkbenchCrafting(); }
			else if (action == Action.SHEAR_SHEEP) { villager.getNavigation().stop(); tickShearing(); }
			else if (action == Action.ATTEND_MEETING) { villager.getNavigation().stop(); tickMeeting(); }
			else if (isSupplyAction()) { villager.getNavigation().stop(); tickBuildSupply(); }
			else if (action == Action.RETURN_TO_STOCKPILE) { villager.getNavigation().stop(); tickCargoReturn(); }
			else navigateToTarget();
		} catch (RuntimeException | Error failure) { releaseMaterialLease(); cancelWorkbenchCrafting(); cancelShearing(); cancelTeaching(); throw failure; }
	}

	@Override
	public void stop() {
		cancelTeaching();
		cancelWorkbenchCrafting();
		cancelShearing();
		releaseMaterialLease();
		releaseForageLease();
		if (SettlementMeetingInvitations.cancelOnStop(action == Action.ATTEND_MEETING)) cancelMeetingInvitation();
		if (foodTicket != null) foodTicket.cancel();
		foodTicket = null;
		supplyStore = null;
		supplyAuthorization = null; // Physical supply remains in the bag across interruption/unload.
		cargoTicket = null; // Never retain a cargo claim over stop/unload/interruption.
		action = null;
		target = null;
		forageTarget = null;
		currentBuildTarget = null;
		currentMaterialRecipe = null;
		currentStarterCraft = null;
		settlement = null;
		progress = 0;
		searchCooldown = 0;
		travelTicks = 0;
		villager.getNavigation().stop();
	}

	/** Read-only candidate gate: never call another resident's lifecycle methods. */
	static boolean readyForTeaching(Villager resident) {
		VillagerWorkGoal goal = goalOf(resident);
		if (goal == null || !(resident.level() instanceof ServerLevel level)
			|| (goal.action != null && goal.action != Action.ATTEND_LESSON)
			|| goal.foodTicket != null || goal.meetingTicket != null || goal.hasAnyShearsLoan()
			|| goal.hasReturnCargo() || goal.hasTeachingCustody()) return false;
		SettlementSavedData data = SettlementSavedData.get(level);
		Settlement camp = data.findSettlementForVillager(resident.getUUID()).orElse(null);
		return camp != null && SettlementTeachingScene.residentBodyReady(level, data, camp, resident);
	}

	static boolean teachingPurpose(ItemStack stack) {
		return ConstructionSupply.isMarked(stack) || SettlementWorkbenchCargo.isMarked(stack)
			|| SettlementShearingCargo.isMarked(stack) || SettlementShearsLoan.isMarked(stack)
			|| SettlementFoodAge.isMarked(stack);
	}

	private boolean hasTeachingCustody() {
		return ConstructionSupply.hasAny(villager.getInventory())
			|| villager.getInventory().hasAnyMatching(VillagerWorkGoal::teachingPurpose)
			|| teachingPurpose(villager.getMainHandItem()) || teachingPurpose(villager.getOffhandItem());
	}

	static SettlementAutonomousTeaching.Session teachingSessionOf(Villager resident) {
		VillagerWorkGoal goal = goalOf(resident);
		return goal != null && goal.action == Action.ATTEND_LESSON ? goal.teachingSession : null;
	}

	private boolean prepareTeaching(boolean allowSearch) {
		if (!readyForTeaching(villager)) return false;
		var session = SettlementAutonomousTeaching.current(villager, settlementData());
		if (session == null && allowSearch && teachingCooldown.ready(villager.level().getGameTime())) {
			teachingCooldown.defer(villager.level().getGameTime());
			session = SettlementAutonomousTeaching.poll(villager, settlementData(), settlement);
		}
		if (session == null) return false;
		teachingSession = session;
		action = Action.ATTEND_LESSON;
		target = session.station;
		if (!SettlementAutonomousTeaching.join(session, villager)) { cancelTeaching(); return false; }
		return true;
	}

	private boolean teachingValid() {
		return teachingSession != null && target != null && target.equals(teachingSession.station)
			&& readyForTeaching(villager) && SettlementAutonomousTeaching.valid(teachingSession);
	}

	private void cancelTeaching() {
		SettlementAutonomousTeaching.cancelOnUnload(villager); // Includes a reservation not joined yet.
		SettlementAutonomousTeaching.cancel(teachingSession);
		teachingSession = null;
		teachingPathTarget = null;
		if (action == Action.ATTEND_LESSON) { action = null; target = null; }
	}

	private boolean teachingCell(ServerLevel level, BlockPos position) {
		return foragePositionAccessible(level, position) && !settlementData().protectedAreas().blocked()
			&& !settlementData().protectedAreas().contains(position);
	}

	private ConstructionApproach.Probe teachingProbe(ServerLevel level) {
		return new ConstructionApproach.Probe() {
			public boolean inBounds(BlockPos pos) { return teachingCell(level, pos); }
			public boolean loaded(BlockPos pos) { return level.isLoaded(pos); }
			public boolean ticking(BlockPos pos) { return level.isPositionEntityTicking(pos); }
			public BlockState state(BlockPos pos) { return level.getBlockState(pos); }
		};
	}

	private boolean atTeachingStation() {
		return teachingSession != null && villager.level() instanceof ServerLevel level
			&& SettlementCargoApproach.arrived(teachingSession.station, villager.position(), teachingProbe(level));
	}

	private boolean teachingPathSafe(ServerLevel level, net.minecraft.world.level.pathfinder.Path path) {
		if (path == null) return true;
		for (int i = 0; i < path.getNodeCount(); i++) {
			BlockPos pos = path.getNodePos(i);
			if (!teachingCell(level, pos) || !teachingCell(level, pos.above()) || !teachingCell(level, pos.below())) return false;
		}
		return true;
	}

	private void tickTeaching() {
		if (!(villager.level() instanceof ServerLevel level) || !teachingValid()) { stop(); return; }
		var session = teachingSession;
		Villager other = session.teacher == villager ? session.learner : session.teacher;
		boolean arrived = atTeachingStation();
		boolean separate = !villager.blockPosition().equals(other.blockPosition());
		SettlementAutonomousTeaching.pulse(session, villager, arrived && separate
			&& villager.hasLineOfSight(other) && other.hasLineOfSight(villager));
		if (!teachingValid()) { stop(); return; }
		if (arrived && separate) {
			villager.getNavigation().stop();
			villager.getLookControl().setLookAt(other, 30, 30);
			return;
		}
		if (++travelTicks >= SettlementCargoApproach.MAX_TRAVEL_TICKS
			|| !teachingPathSafe(level, villager.getNavigation().getPath())) {
			teachingCooldown.defer(level.getGameTime()); stop(); return;
		}
		VillagerWorkGoal partner = goalOf(other);
		if (teachingPathTarget != null && (teachingPathTarget.equals(other.blockPosition())
			|| partner != null && teachingPathTarget.equals(partner.teachingPathTarget))) villager.getNavigation().stop();
		if (!villager.getNavigation().isDone()) return;
		var outcome = teachingApproach.attempt(target, villager.position(), level.getGameTime(), teachingProbe(level),
			this::navigationSearchRegionLoaded, candidate -> {
				if (candidate.equals(other.blockPosition()) || partner != null && candidate.equals(partner.teachingPathTarget)) return false;
				var path = villager.getNavigation().createPath(candidate, 0);
				if (path == null || !path.canReach() || !teachingPathSafe(level, path) || !teachingValid()) return false;
				teachingPathTarget = candidate;
				return villager.getNavigation().moveTo(path, settlementData().personalityOf(settlement, villager.getUUID()).movementSpeed());
			});
		if (outcome == SettlementCargoApproach.Outcome.BLOCKED) { teachingCooldown.defer(level.getGameTime()); stop(); }
	}

	private void releaseMaterialLease() {
		if (materialRegistry != null) materialRegistry.release(materialLease);
		if (materialTicket != null) materialTicket.cancelled = true;
		materialLease = null;
		materialRegistry = null;
		materialTicket = null;
	}

	private boolean claimGatherTarget() {
		if (target == null || !(villager.level() instanceof ServerLevel level)) return false;
		SettlementSavedData data = settlementData();
		// A coordinate selected from an explicit manual mark keeps the old manual semantics.
		if (!SettlementGathering.validTarget(level, data, settlement, villager, target)) {
			var choice = materialCandidates.stream().filter(candidate -> candidate.position().equals(target)).findFirst().orElse(null);
			materialTicket = SettlementMaterialGathering.prepare(data, settlement, villager.getUUID(), choice,
				SettlementMaterialGathering.probe(level, data, settlement, villager));
			if (materialTicket == null) return false;
		}
		materialRegistry = data.materialLeases();
		materialLease = materialRegistry.claim(data, settlement, villager.getUUID(), target, level.getGameTime());
		if (materialLease == null) { releaseMaterialLease(); return false; }
		return true;
	}

	private void releaseForageLease() {
		if (forageRegistry != null) forageRegistry.release(forageLease);
		forageLease = null;
		forageRegistry = null;
	}

	private void cancelShearing() {
		SettlementShearing.cancelLoan(shearsLoanTicket);
		shearsLoanTicket = null;
		SettlementShearing.cancel(shearingSession);
		shearingSession = null;
		shearingPathTarget = null;
		shearingPathAge = 0;
		if (action == Action.SHEAR_SHEEP || isShearsLoanAction()) { action = null; target = null; }
	}

	/** Entity unload is not guaranteed to invoke Goal.stop; no live sheep claim may survive it. */
	static void cancelShearingOnUnload(Villager villager) {
		VillagerWorkGoal goal = goalOf(villager);
		if (goal != null) goal.cancelShearing();
	}

	private static VillagerWorkGoal goalOf(Villager villager) {
		for (var wrapped : ((com.thysummer.coopcraft.mixin.MobAccessor) villager).getGoalSelector().getAvailableGoals()) {
			if (wrapped.getGoal() instanceof VillagerWorkGoal goal) return goal;
		}
		return null;
	}

	static String inviteToMeeting(net.minecraft.server.level.ServerPlayer player, Villager villager,
		SettlementSavedData data, Settlement settlement) {
		VillagerWorkGoal goal = goalOf(villager);
		if (goal == null) return "unavailable";
		return goal.toggleInvitation(player, data, settlement);
	}

	/** Opaque, ephemeral identity for a server-owned dialog snapshot. Inspection has no side effects. */
	static Object invitationIdentity(Villager villager) {
		VillagerWorkGoal goal = goalOf(villager);
		return goal != null && goal.hasValidInvitation() ? goal.meetingTicket : null;
	}

	static boolean invitationOwnedBy(Villager villager, java.util.UUID inviter) {
		Object ticket = invitationIdentity(villager);
		return ticket instanceof SettlementMeetingInvitations.Ticket invitation && invitation.inviter().equals(inviter);
	}

	static String requestMeetingInvitation(net.minecraft.server.level.ServerPlayer player, Villager villager,
		SettlementSavedData data, Settlement settlement, boolean cancel, Object expectedTicket) {
		VillagerWorkGoal goal = goalOf(villager);
		if (goal == null) return "unavailable";
		return goal.requestInvitation(player, data, settlement, false, cancel, expectedTicket);
	}

	static void cancelMeetingInvitation(Villager villager) {
		VillagerWorkGoal goal = goalOf(villager);
		if (goal != null) {
			if (goal.action == Action.ATTEND_MEETING) goal.stop();
			else goal.cancelMeetingInvitation();
		}
	}

	static String invitationStatus(Villager villager) {
		VillagerWorkGoal goal = goalOf(villager);
		if (goal == null) return "none";
		if (!goal.hasValidInvitation()) return "none";
		if (goal.meetingTicket.waiting()) return "waiting";
		return goal.action == Action.ATTEND_MEETING ? "travelling" : "pending";
	}

	private String toggleInvitation(net.minecraft.server.level.ServerPlayer player, SettlementSavedData data, Settlement requested) {
		return requestInvitation(player, data, requested, true, false, null);
	}

	private String requestInvitation(net.minecraft.server.level.ServerPlayer player, SettlementSavedData data,
		Settlement requested, boolean toggle, boolean cancel, Object expectedTicket) {
		if (!(villager.level() instanceof ServerLevel level) || player.level() != level
			|| !player.isAlive() || player.isRemoved() || player.isSpectator() || !villager.isAlive()
			|| player.distanceToSqr(villager) > 16) return "unavailable";
		refreshMeetingInvitation();
		String decision = toggle
			? (meetingTicket == null ? "ready" : SettlementMeetingInvitations.existing(meetingTicket, player.getUUID()))
			: SettlementMeetingInvitations.explicitRequest(meetingTicket, SettlementSavedData.get(level), level.getGameTime(),
				level.getDayTime() / 24000L, player.getUUID(), cancel, expectedTicket);
		if (decision.equals("cancelled")) {
			if (action == Action.ATTEND_MEETING) stop();
			else cancelMeetingInvitation();
			return decision;
		}
		if (!decision.equals("ready")) return decision;
		String gate = SettlementMeetingInvitations.gate(data, requested, villager.getUUID(), level.getDayTime() / 24000L);
		if (!gate.equals("ready")) return gate;
		if (action == Action.EAT || foodTicket != null) return "eating";
		BlockPos store = data.getStockpile(requested).orElse(null);
		if (store == null || !level.isLoaded(store) || !level.isPositionEntityTicking(store)) return "store_unavailable";
		Map<BlockPos, net.minecraft.world.level.block.entity.BlockEntity> identities = new java.util.LinkedHashMap<>();
		Map<BlockPos, BlockState> states = new java.util.LinkedHashMap<>();
		states.put(store, level.getBlockState(store));
		if (!(states.get(store).is(Blocks.CHEST) || states.get(store).is(Blocks.BARREL))) return "store_unavailable";
		if (states.get(store).getBlock() instanceof net.minecraft.world.level.block.ChestBlock
			&& states.get(store).getValue(net.minecraft.world.level.block.ChestBlock.TYPE)
				!= net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
			BlockPos partner = store.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(states.get(store)));
			if (!level.isLoaded(partner) || !level.isPositionEntityTicking(partner)) return "store_unavailable";
			states.put(partner, level.getBlockState(partner));
		}
		for (BlockPos pos : states.keySet()) {
			var entity = level.getBlockEntity(pos);
			if (entity == null || entity.isRemoved() || entity.getLevel() != level) return "store_unavailable";
			identities.put(pos, entity);
		}
		meetingTicket = SettlementMeetingInvitations.create(data, requested, villager.getUUID(), player.getUUID(),
			level.getGameTime(), level.getDayTime() / 24000L, () -> SettlementSavedData.get(level) == data
				&& new SettlementMeetingInvitations.Presence(villager.isAlive() && !villager.isRemoved(),
					level.getEntity(villager.getUUID()) == villager && level.isLoaded(villager.blockPosition()),
					level.isPositionEntityTicking(villager.blockPosition()), villager.level() == level,
					!villager.isBaby(), !villager.isNoAi(), !villager.isSleeping(), !villager.isTrading(),
					!villager.isPassenger(), villager.hurtTime == 0 && villager.getHealth() >= villager.getMaxHealth(),
					action != Action.EAT && foodTicket == null, villager.distanceToSqr(requested.center().getCenter())).eligible()
				&& player.level() == level && player.isAlive() && !player.isRemoved() && !player.isSpectator()
				&& level.getServer().getPlayerList().getPlayer(player.getUUID()) == player
				&& SettlementMeetingInvitations.withinSettlement(player.distanceToSqr(requested.center().getCenter()))
				&& (meetingTicket == null || !meetingTicket.waiting()
					|| SettlementMeetingInvitations.arrived(villager.distanceToSqr(store.getCenter())))
				&& data.getStockpile(requested).filter(store::equals).isPresent()
				&& identities.entrySet().stream().allMatch(entry -> level.isLoaded(entry.getKey())
					&& level.isPositionEntityTicking(entry.getKey()) && level.getBlockEntity(entry.getKey()) == entry.getValue()
					&& !entry.getValue().isRemoved() && entry.getValue().getLevel() == level)
				&& states.entrySet().stream().allMatch(entry -> SettlementFoodRequests.sameStoreState(entry.getValue(), level.getBlockState(entry.getKey())))
				&& SettlementStockpile.getStockpile(level, data, requested) != null);
		if (meetingTicket == null) return "unavailable";
		meetingStore = store;
		return "accepted";
	}

	private void cancelMeetingInvitation() {
		if (meetingTicket != null) meetingTicket.cancel();
		meetingTicket = null;
		meetingStore = null;
	}

	private boolean hasValidInvitation() {
		return meetingTicket != null && meetingStore != null && villager.level() instanceof ServerLevel level
			&& SettlementMeetingInvitations.waitingPositionValid(meetingTicket.waiting(), villager.distanceToSqr(meetingStore.getCenter()))
			&& meetingTicket.matches(SettlementSavedData.get(level), level.getGameTime(), level.getDayTime() / 24000L);
	}

	private void refreshMeetingInvitation() {
		if (meetingTicket == null) return;
		if (!(villager.level() instanceof ServerLevel level)
			|| !meetingTicket.valid(SettlementSavedData.get(level), level.getGameTime(), level.getDayTime() / 24000L)) {
			cancelMeetingInvitation();
			if (action == Action.ATTEND_MEETING) stop();
		}
	}

	private void tickMeeting() {
		if (meetingTicket == null || target == null) { stop(); return; }
		ServerLevel level = (ServerLevel) villager.level();
		boolean arrived = SettlementMeetingInvitations.arrived(villager.distanceToSqr(target.getCenter()));
		if (!SettlementMeetingInvitations.waitingPositionValid(meetingTicket.waiting(), villager.distanceToSqr(target.getCenter()))) { stop(); return; }
		if (arrived) {
			villager.getNavigation().stop();
			villager.getLookControl().setLookAt(target.getCenter());
			meetingTicket.arrive(level.getGameTime());
			return;
		}
		if (++travelTicks >= SettlementMeetingInvitations.MAX_TRAVEL_TICKS || !meetingPathTicking()) { stop(); return; }
		if (!villager.getNavigation().isDone()) return;
		if (travelTicks > 1 || !navigationSearchRegionLoaded()) { stop(); return; }
		for (int vertical : new int[] {0, 1, -1}) {
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos candidate = target.relative(direction).above(vertical);
				if (!level.isLoaded(candidate) || !level.isLoaded(candidate.above()) || !level.isLoaded(candidate.below())
					|| !level.isPositionEntityTicking(candidate) || !level.isPositionEntityTicking(candidate.above())
					|| !level.isPositionEntityTicking(candidate.below())
					|| !level.getBlockState(candidate).getCollisionShape(level, candidate).isEmpty()
					|| !level.getBlockState(candidate.above()).getCollisionShape(level, candidate.above()).isEmpty()
					|| !level.getBlockState(candidate).getFluidState().isEmpty()
					|| !level.getBlockState(candidate.below()).isSolidRender(level, candidate.below())) continue;
				var path = villager.getNavigation().createPath(candidate, 0);
				if (path == null || !path.canReach()) continue;
				boolean safe = true;
				for (int i = 0; i < path.getNodeCount(); i++) {
					if (!level.isLoaded(path.getNodePos(i)) || !level.isPositionEntityTicking(path.getNodePos(i))) { safe = false; break; }
				}
				if (safe && villager.getNavigation().moveTo(path, settlementData().personalityOf(settlement, villager.getUUID()).movementSpeed())) return;
			}
		}
		stop();
	}

	/** PathNavigation builds a chunk-backed search region before returning any nodes. */
	private boolean navigationSearchRegionLoaded() {
		ServerLevel level = (ServerLevel) villager.level();
		double followRange = villager.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
		if (!Double.isFinite(followRange) || followRange < 0 || followRange > 256) return false;
		int radius = (int) Math.ceil(followRange) + 16;
		BlockPos origin = villager.blockPosition();
		if ((long) origin.getX() - radius < Integer.MIN_VALUE || (long) origin.getX() + radius > Integer.MAX_VALUE
			|| (long) origin.getZ() - radius < Integer.MIN_VALUE || (long) origin.getZ() + radius > Integer.MAX_VALUE) return false;
		for (int x = (origin.getX() - radius) >> 4; x <= (origin.getX() + radius) >> 4; x++) {
			for (int z = (origin.getZ() - radius) >> 4; z <= (origin.getZ() + radius) >> 4; z++) {
				if (!level.isLoaded(new BlockPos(x << 4, origin.getY(), z << 4))) return false;
			}
		}
		return true;
	}

	private boolean meetingPathTicking() {
		var path = villager.getNavigation().getPath();
		if (path == null) return true;
		ServerLevel level = (ServerLevel) villager.level();
		for (int i = 0; i < path.getNodeCount(); i++) {
			if (!level.isLoaded(path.getNodePos(i)) || !level.isPositionEntityTicking(path.getNodePos(i))) return false;
		}
		return true;
	}

	String debugState() {
		boolean running = ((com.thysummer.coopcraft.mixin.MobAccessor) villager).getGoalSelector().getAvailableGoals().stream()
			.anyMatch(goal -> goal.getGoal() == this && goal.isRunning());
		return "action=" + action + ", target=" + target + ", progress=" + progress + ", running=" + running
			+ ", canContinue=" + canContinueToUse() + ", alive=" + villager.isAlive() + ", villagerTick=" + villager.tickCount
			+ ", gameTime=" + villager.level().getGameTime()
			+ ", entityTicking=" + ((ServerLevel) villager.level()).isPositionEntityTicking(villager.blockPosition())
			+ ", travel=" + travelTicks + ", approach=" + constructionApproach.cursor()
			+ ", navigationDone=" + villager.getNavigation().isDone() + ", path=" + villager.getNavigation().getPath()
			+ ", pathTarget=" + (villager.getNavigation().getPath() == null ? null : villager.getNavigation().getPath().getTarget());
	}

	public static WorkStatus statusOf(Villager villager) {
		for (var wrapped : ((com.thysummer.coopcraft.mixin.MobAccessor) villager).getGoalSelector().getAvailableGoals()) {
			if (wrapped.getGoal() instanceof VillagerWorkGoal goal) {
				return goal.status();
			}
		}
		return new WorkStatus("idle", null, "not_installed");
	}

	public WorkStatus status() {
		if (action == Action.ATTEND_LESSON && teachingSession != null) {
			String phase = teachingSession.phase();
			return new WorkStatus(phase.equals("closed") ? "idle" : phase.equals("teaching") ? "lesson_learning"
				: atTeachingStation() ? "lesson_waiting" : "attend_lesson", target, null);
		}
		if (action == Action.SHEAR_SHEEP && shearingSession != null)
			return new WorkStatus(action.id(), shearingSession.position(), travelTicks >= 100 ? "path_blocked" : null);
		if (action == Action.ATTEND_MEETING && !hasValidInvitation()) return new WorkStatus("idle", null, null);
		if (action == Action.ATTEND_MEETING && meetingTicket != null)
			return new WorkStatus(meetingTicket.waiting() ? "meeting_wait" : "attend_meeting", meetingStore, null);
		if (action == Action.RETURN_TO_STOCKPILE) return new WorkStatus(action.id(), target, cargoBlocker);
		if (SettlementCargoDelivery.hasMarkedHarvestFood(villager.getInventory()) && !hasDeliverableFoodCargo())
			return new WorkStatus(action == null ? "idle" : action.id(), target, "cargo_food_unavailable");
		if (action == null && cargoBlocker != null && hasReturnCargo())
			return new WorkStatus("idle", null, cargoBlocker);
		if (action != null) {
			if (foodTicket != null && foodTicket.waiting()) return new WorkStatus("eat", target, "food_request_waiting");
			if (action == Action.EAT && !hasFoodAvailable() && !hasPendingFoodRefusal()) {
				return new WorkStatus("idle", null, "hungry_no_food");
			}
			return new WorkStatus(action.id(), target, travelTicks >= 100 ? "path_blocked" : null);
		}
		if (!(villager.level() instanceof ServerLevel level)) {
			return new WorkStatus("idle", null, "unknown");
		}
		SettlementSavedData data = SettlementSavedData.get(level);
		Optional<Settlement> workSettlement = data.findSettlementForVillager(villager.getUUID())
			.filter(candidate -> data.getStockpile(candidate).isPresent());
		if (workSettlement.isEmpty()) {
			return new WorkStatus("idle", null, "no_stockpile");
		}
		Settlement current = workSettlement.get();
		if (villager.isBaby()) {
			return new WorkStatus("idle", null, "child");
		}
		int satiety = data.needsOf(current, villager.getUUID()).satiety();
		boolean foodAvailable = SettlementSimulationService.isSurvivalEnabled(data, current)
			? SettlementSimulationService.hasFoodFor(data, current, villager.getUUID())
			: SettlementNeeds.hasFood(villager.getInventory(), SettlementStockpile.getStockpile(level, data, current));
		if (satiety <= SettlementNeeds.HUNGRY_AT && !foodAvailable) {
			return new WorkStatus("idle", null, "hungry_no_food");
		}
		var construction = ConstructionSystem.inspect(level, data, current);
		if (construction.isPresent()) {
			return constructionStatus(construction.get());
		}
		if (data.resourcesOf(current).isEmpty() && data.gatheringAreaOf(current).isEmpty()) {
			return new WorkStatus("idle", null, "no_resources");
		}
		return new WorkStatus("idle", null, null);
	}

	/** An available next step does not mean an idle resident has started building or is blocked. */
	static WorkStatus constructionStatus(ConstructionReadiness readiness) {
		return new WorkStatus("idle", null, readiness.blocker().equals("ready")
			? null : "construction_" + readiness.blocker());
	}

	public record WorkStatus(String action, BlockPos target, String blocker) {
		public WorkStatus {
			if (action == null || action.isBlank()) {
				throw new IllegalArgumentException("Work action cannot be blank");
			}
		}
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		try {
			refreshMeetingInvitation();
			if (action == Action.ATTEND_LESSON) { tickTeaching(); return; }
			if (hasAnyShearsLoan() && action != Action.SHEAR_SHEEP && action != Action.RETURN_SHEARS && action != Action.EAT) { stop(); return; }
			if (isShearsLoanAction()) { tickShearsLoan(); return; }
			if (action == Action.ATTEND_MEETING) { tickMeeting(); return; }
			if (action == null) return;
			if (action == Action.SHEAR_SHEEP) { tickShearing(); return; }
			if (isWorkbenchAction()) { tickWorkbenchCrafting(); return; }
			if (isSupplyAction()) { tickBuildSupply(); return; }
			if (action == Action.RETURN_TO_STOCKPILE) { tickCargoReturn(); return; }
			if ((action == Action.GATHER || action == Action.CRAFT_FIRST_WORKBENCH || action == Action.FORAGE || action == Action.HARVEST_CROP)
				&& !isTargetValid()) { stop(); return; }
			if (foodTicket != null) {
				villager.getNavigation().stop();
				if (!foodTicket.waiting()) {
					foodTicket = null;
					progress = 0;
					if (!isTargetValid()) stop();
				}
				return;
			}
			if (!navigateToTarget()) {
				return;
			}
			progress++;
			int baseDuration = switch (action) {
				case GATHER -> hasStonePickaxe() ? TOOL_GATHER_TICKS : HAND_GATHER_TICKS;
				case FORAGE, HARVEST_CROP -> FORAGE_TICKS;
				case BUILD -> BUILD_TICKS;
				case EAT -> EAT_TICKS;
				default -> CRAFT_TICKS;
			};
			var workType = switch (action) {
				case GATHER -> VillagerPersonality.WorkType.GATHER;
				case FORAGE, HARVEST_CROP -> VillagerPersonality.WorkType.GATHER;
				case BUILD -> VillagerPersonality.WorkType.BUILD;
				case EAT -> VillagerPersonality.WorkType.GENERAL;
				default -> VillagerPersonality.WorkType.CRAFT;
			};
			int duration = settlementData().personalityOf(settlement, villager.getUUID()).workDuration(baseDuration, workType);
			if (action != Action.EAT && settlementData().needsOf(settlement, villager.getUUID()).satiety() < 20) {
				duration = duration * 5 / 4;
			}
			if (action == Action.GATHER || (action == Action.FORAGE || action == Action.HARVEST_CROP)) {
				duration = SettlementSimulationService.gatherWorkDuration(settlementData(), settlement, villager.getUUID(), duration);
			} else if (action == Action.BUILD) {
				duration = SettlementSimulationService.constructionWorkDuration(settlementData(), settlement, villager.getUUID(), duration);
			} else if (action != Action.EAT) {
				duration = SettlementSimulationService.craftingWorkDuration(settlementData(), settlement, villager.getUUID(), duration);
			}
			if (progress < duration) {
				return;
			}

			if (action == Action.EAT && queuePreparedMeal()) return;
			switch (action) {
				case GATHER -> gather();
				case FORAGE, HARVEST_CROP -> forage();
				case CRAFT_PICKAXE -> craftPickaxe();
				case CRAFT_STICKS -> craftSticks();
				case CRAFT_MATERIALS -> craftMaterials();
				case CRAFT_FIRST_WORKBENCH -> craftFirstWorkbench();
				case BUILD -> ConstructionSystem.buildNext(
					(ServerLevel) villager.level(), settlementData(), settlement, currentBuildTarget, villager
				);
				case POLISH_PICKAXE -> craftPolishedPickaxe();
				case GRIND_SMOOTH_STONE -> grindSmoothStone();
				case ATTEND_LESSON, BORROW_SHEARS, RETURN_SHEARS, SHEAR_SHEEP, ATTEND_MEETING, RETURN_TO_STOCKPILE, COLLECT_BUILD_SUPPLY, RETURN_BUILD_SUPPLY, COLLECT_PLANK_MATERIALS, WORKBENCH_PLANKS, COLLECT_DOOR_MATERIALS, WORKBENCH_DOORS, COLLECT_BED_MATERIALS, WORKBENCH_BEDS, COLLECT_WOOL_MATERIALS, WORKBENCH_WOOL, COLLECT_TORCH_MATERIALS, WORKBENCH_TORCHES -> { return; }
				case EAT -> SettlementNeeds.eat((ServerLevel) villager.level(), settlementData(), settlement, villager);
			}
			if (action == Action.EAT && isTargetValid()) {
				progress = 0;
				return;
			}
			stop();
		} catch (RuntimeException | Error failure) { releaseMaterialLease(); cancelWorkbenchCrafting(); cancelShearing(); cancelTeaching(); throw failure; }
	}

	/** Capture actual block entities, never the fresh wrapper returned for a double chest. */
	private boolean queuePreparedMeal() {
		SettlementSavedData data = settlementData();
		Settlement requestSettlement = settlement;
		ServerLevel level = (ServerLevel) villager.level();
		java.util.function.Supplier<java.util.List<com.thysummer.coopcraft.civilization.simulation.Person>> candidates =
			() -> SettlementSupervision.candidates(level, data, requestSettlement);
		if (!SettlementFoodRequests.enabled(data, requestSettlement, candidates)) return false;
		BlockPos store = target;
		Map<BlockPos, net.minecraft.world.level.block.entity.BlockEntity> identities = new java.util.LinkedHashMap<>();
		if (!level.isLoaded(store) || SettlementStockpile.getStockpile(level, data, requestSettlement) == null) {
			stop();
			return true;
		}
		identities.put(store, level.getBlockEntity(store));
		Map<BlockPos, BlockState> states = new java.util.LinkedHashMap<>();
		states.put(store, level.getBlockState(store));
		if (states.get(store).getBlock() instanceof net.minecraft.world.level.block.ChestBlock
			&& states.get(store).getValue(net.minecraft.world.level.block.ChestBlock.TYPE)
				!= net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
			BlockPos partner = store.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(states.get(store)));
			if (!level.isLoaded(partner)) { stop(); return true; }
			identities.put(partner, level.getBlockEntity(partner));
			states.put(partner, level.getBlockState(partner));
		}
		foodTicket = data.foodRequests().submit(data, requestSettlement, villager.getUUID(), level.getGameTime(),
			level.getDayTime() / 24000L, foodTicket, () -> action == Action.EAT && settlement == requestSettlement
				&& store.equals(target) && villager.isAlive() && !villager.isRemoved()
				&& level.getEntity(villager.getUUID()) == villager
				&& level.isPositionEntityTicking(villager.blockPosition()) && level.isPositionEntityTicking(store)
				&& villager.distanceToSqr(store.getCenter()) <= INTERACTION_DISTANCE_SQUARED
				&& data.getStockpile(requestSettlement).filter(store::equals).isPresent()
				&& identities.entrySet().stream().allMatch(entry -> level.isLoaded(entry.getKey())
					&& level.getBlockEntity(entry.getKey()) == entry.getValue())
				&& states.entrySet().stream().allMatch(entry -> SettlementFoodRequests.sameStoreState(
					entry.getValue(), level.getBlockState(entry.getKey())))
				&& SettlementStockpile.getStockpile(level, data, requestSettlement) != null,
			() -> SettlementNeeds.eat(level, data, requestSettlement, villager), candidates);
		if (foodTicket == null) return false;
		villager.getNavigation().stop();
		return true;
	}

	private Action chooseAction() {
		if (settlementData().getStockpile(settlement).isEmpty()) return null;
		if (shouldEat()) {
			return Action.EAT;
		}
		if (!hasReturnCargo() && shearingCooldown.ready(villager.level().getGameTime())) {
			ServerLevel level = (ServerLevel) villager.level();
			SettlementSavedData data = settlementData();
			Settlement camp = settlement;
			shearingSession = SettlementShearing.prepare(level, data, camp, villager,
				() -> woolDemandStillNeeded(level, data, camp, ConstructionPlanner.materialDemands(data, camp)));
			if (shearingSession != null) return Action.SHEAR_SHEEP;
			if (shearsLoanCooldown.ready(level.getGameTime()) && prepareShearsLoan(false)) return Action.BORROW_SHEARS;
		}
		currentStarterCraft = prepareStarterCrafting();
		if (currentStarterCraft != null) return Action.CRAFT_FIRST_WORKBENCH;
		if (canCraftPickaxe()) {
			return Action.CRAFT_PICKAXE;
		}
		if (canCraftSticks()) {
			return Action.CRAFT_STICKS;
		}
		if (canPolishPickaxe()) {
			return Action.POLISH_PICKAXE;
		}
		if (canGrindSmoothStone()) {
			return Action.GRIND_SMOOTH_STONE;
		}
		currentMaterialRecipe = chooseMaterialRecipe();
		if (currentMaterialRecipe == SettlementMaterialCrafting.Recipe.PLANKS) {
			workbenchTicket = prepareWorkbenchCrafting();
			return workbenchTicket == null ? null : Action.COLLECT_PLANK_MATERIALS;
		}
		if (currentMaterialRecipe == SettlementMaterialCrafting.Recipe.DOOR) {
			workbenchTicket = prepareWorkbenchCrafting();
			return workbenchTicket == null ? null : Action.COLLECT_DOOR_MATERIALS;
		}
		if (currentMaterialRecipe == SettlementMaterialCrafting.Recipe.BED) {
			workbenchTicket = prepareWorkbenchCrafting();
			return workbenchTicket == null ? null : Action.COLLECT_BED_MATERIALS;
		}
		if (currentMaterialRecipe == SettlementMaterialCrafting.Recipe.WOOL) {
			workbenchTicket = prepareWorkbenchCrafting();
			return workbenchTicket == null ? null : Action.COLLECT_WOOL_MATERIALS;
		}
		if (currentMaterialRecipe == SettlementMaterialCrafting.Recipe.TORCH_COAL
			|| currentMaterialRecipe == SettlementMaterialCrafting.Recipe.TORCH_CHARCOAL) {
			workbenchTicket = prepareWorkbenchCrafting();
			return workbenchTicket == null ? null : Action.COLLECT_TORCH_MATERIALS;
		}
		if (currentMaterialRecipe != null) {
			return Action.CRAFT_MATERIALS;
		}
		if (hasReturnCargo()) return null;
		selectedGatherTarget = chooseGatherTarget();
		return selectedGatherTarget == null ? null : Action.GATHER;
	}

	private boolean isShearsLoanAction() { return action == Action.BORROW_SHEARS || action == Action.RETURN_SHEARS; }

	private boolean hasAnyShearsLoan() {
		return SettlementShearsLoan.isMarked(villager.getMainHandItem()) || SettlementShearsLoan.isMarked(villager.getOffhandItem())
			|| villager.getInventory().hasAnyMatching(SettlementShearsLoan::isMarked);
	}

	private SettlementShearsLoan.Hand mainShearsHand() {
		return new SettlementShearsLoan.Hand() {
			public ItemStack get() { return villager.getMainHandItem(); }
			public void set(ItemStack stack) {
				if (stack.isEmpty() && SettlementShearsLoan.isMarked(get())) {
					if (!SettlementShearsEquipment.clear(villager, get())) throw new IllegalStateException("Loan hand changed before clear");
				} else villager.setItemInHand(InteractionHand.MAIN_HAND, stack);
			}
		};
	}

	/** Bag custody returns directly; a personal main or off hand is never swapped or overwritten. */
	private SettlementShearsLoan.Hand returnShearsHand() {
		var dimension = villager.level().dimension().location();
		if (SettlementShearsLoan.ownedBy(villager.getMainHandItem(), dimension, settlement.id(), villager.getUUID())) return mainShearsHand();
		var bag = villager.getInventory();
		for (int slot = 0; slot < bag.getContainerSize(); slot++) {
			if (!SettlementShearsLoan.ownedBy(bag.getItem(slot), dimension, settlement.id(), villager.getUUID())) continue;
			int captured = slot;
			return new SettlementShearsLoan.Hand() {
				public ItemStack get() { return bag.getItem(captured); }
				public void set(ItemStack stack) { bag.setItem(captured, stack); }
			};
		}
		return null;
	}

	private boolean prepareShearsLoan(boolean returning) {
		if (!(villager.level() instanceof ServerLevel level) || settlement == null) return false;
		SettlementSavedData data = settlementData();
		Settlement camp = settlement;
		if (!returning && (!SettlementCargoRecovery.get(level).available() || hasReturnCargo()
			|| ConstructionSupply.hasAny(villager.getInventory()) || hasAnyShearsLoan())) return false;
		var hand = returning ? returnShearsHand() : mainShearsHand();
		if (hand == null) return false;
		BlockPos store = data.getStockpile(camp).orElse(null);
		var binding = SettlementCraftingStore.capture(level, store);
		if (binding == null) return false;
		if (!returning) {
			Container stockpile = SettlementStockpile.getStockpile(level, data, camp);
			boolean available = false;
			if (stockpile != null) for (int slot = 0; slot < stockpile.getContainerSize(); slot++) {
				ItemStack stack = stockpile.getItem(slot);
				if (SettlementShearsLoan.borrowable(stack)) { available = true; break; }
			}
			if (!available) return false;
		}
		shearsLoanTicket = SettlementShearing.prepareLoan(level, data, camp, villager, hand, returning,
			() -> settlement == camp && binding.valid(),
			() -> SettlementCargoRecovery.get(level).available() && !hasReturnCargo() && !ConstructionSupply.hasAny(villager.getInventory())
				&& woolDemandStillNeeded(level, data, camp, ConstructionPlanner.materialDemands(data, camp)));
		if (shearsLoanTicket == null) return false;
		action = returning ? Action.RETURN_SHEARS : Action.BORROW_SHEARS;
		target = store; progress = 0; travelTicks = 0;
		return true;
	}

	private void tickShearsLoan() {
		if (!(villager.level() instanceof ServerLevel level) || !isTargetValid()) { stop(); return; }
		if (shouldEat() || (action == Action.BORROW_SHEARS && (meetingTicket != null || emergencyForage()))) { stop(); return; }
		if (!atCargoStore()) { tickStoreTravel(false); return; }
		villager.getNavigation().stop();
		// The transfer helper invokes the same ticket and physical-arrival checks at the actual mutation.
		SettlementShearing.LoanTicket ticket = shearsLoanTicket;
		Container stockpile = SettlementStockpile.getStockpile(level, ticket.data, ticket.camp);
		java.util.function.BooleanSupplier authorized = () -> shearsLoanTicket == ticket && isTargetValid() && atCargoStore();
		if (action == Action.RETURN_SHEARS) {
			java.util.function.BooleanSupplier returning = () -> shearsLoanTicket == ticket && action == Action.RETURN_SHEARS
				&& ticket.store.equals(target) && SettlementShearing.returnLoanAuthority(ticket) && atCargoStore();
			if (!SettlementShearsLoan.returnFromHand(ticket.hand, stockpile, ticket.dimension, ticket.camp.id(), ticket.actor, returning))
				shearsLoanCooldown.defer(level.getGameTime());
			stop(); return;
		}
		boolean borrowed = SettlementShearsLoan.borrow(stockpile, ticket.hand, ticket.dimension, ticket.camp.id(), ticket.actor, authorized);
		SettlementShearing.cancelLoan(ticket); shearsLoanTicket = null;
		if (!borrowed) { shearsLoanCooldown.defer(level.getGameTime()); stop(); return; }
		// Only this uninterrupted, real pickup may start one new session. Reloaded loans only return.
		shearingSession = SettlementShearing.prepare(level, ticket.data, ticket.camp, villager,
			() -> woolDemandStillNeeded(level, ticket.data, ticket.camp, ConstructionPlanner.materialDemands(ticket.data, ticket.camp)));
		if (shearingSession == null) { stop(); return; }
		action = Action.SHEAR_SHEEP; target = shearingSession.position(); progress = 0; travelTicks = 0;
	}

	private boolean isWorkbenchAction() {
		return isCollectWorkbenchAction() || action == Action.WORKBENCH_PLANKS || action == Action.WORKBENCH_DOORS || action == Action.WORKBENCH_BEDS || action == Action.WORKBENCH_WOOL || action == Action.WORKBENCH_TORCHES;
	}

	/** The live sheep position, not the originally selected block, drives both travel and work. */
	private void tickShearing() {
		if (!(villager.level() instanceof ServerLevel level) || !isTargetValid()
			|| shouldEat() || meetingTicket != null || emergencyForage() || hasReturnCargo()) { stop(); return; }
		var sheep = shearingSession.sheep();
		target = shearingSession.position();
		if (!constructionPathIsTicking() || !workbenchPathInBounds(level)) { deferShearing(); return; }
		villager.getLookControl().setLookAt(sheep);
		if (villager.distanceToSqr(sheep) <= INTERACTION_DISTANCE_SQUARED
			&& navigationSearchRegionLoaded() && villager.hasLineOfSight(sheep)) {
			villager.getNavigation().stop();
			int duration = settlementData().personalityOf(settlement, villager.getUUID())
				.workDuration(SHEARING_TICKS, VillagerPersonality.WorkType.GATHER);
			if (settlementData().needsOf(settlement, villager.getUUID()).satiety() < 20) duration = duration * 5 / 4;
			duration = SettlementSimulationService.gatherWorkDuration(settlementData(), settlement, villager.getUUID(), duration);
			if (++progress < duration) return;
			try {
				// finish owns the final live gates, the sole vanilla shear call, and real tool damage.
				SettlementShearing.finish(shearingSession);
			} finally { stop(); }
			return;
		}
		progress = 0; // A moving/occluded sheep must be reached again before any work can complete.
		if (++travelTicks >= SHEARING_MAX_TRAVEL_TICKS) { deferShearing(); return; }
		if (!shearingRepathRequired(shearingPathTarget, target, villager.getNavigation().isDone(), ++shearingPathAge)) return;
		if (!navigationSearchRegionLoaded() || !foragePositionAccessible(level, target)) { deferShearing(); return; }
		var path = villager.getNavigation().createPath(target, 0);
		if (path == null || !path.canReach()) { deferShearing(); return; }
		for (int index = 0; index < path.getNodeCount(); index++) {
			if (!foragePositionAccessible(level, path.getNodePos(index))) { deferShearing(); return; }
		}
		// Path callbacks cannot replace the pinned sheep, hand, membership or permit unnoticed.
		if (!isTargetValid()) { stop(); return; }
		if (!villager.getNavigation().moveTo(path, settlementData().personalityOf(settlement, villager.getUUID()).movementSpeed())) {
			deferShearing(); return;
		}
		shearingPathTarget = target.immutable();
		shearingPathAge = 0;
	}

	static boolean shearingRepathRequired(BlockPos previous, BlockPos current, boolean navigationDone, int age) {
		return current != null && (previous == null || !previous.equals(current) || navigationDone || age >= SHEARING_REPATH_TICKS);
	}

	private void deferShearing() {
		shearingCooldown.defer(villager.level().getGameTime());
		stop();
	}

	private boolean isCollectWorkbenchAction() {
		return action == Action.COLLECT_PLANK_MATERIALS || action == Action.COLLECT_DOOR_MATERIALS || action == Action.COLLECT_BED_MATERIALS || action == Action.COLLECT_WOOL_MATERIALS || action == Action.COLLECT_TORCH_MATERIALS;
	}

	private void cancelWorkbenchCrafting() {
		SettlementWorkbenchCrafting.cancel(workbenchTicket);
		workbenchTicket = null;
		if (isWorkbenchAction()) { action = null; target = null; }
	}

	private SettlementWorkbenchCrafting.Ticket prepareWorkbenchCrafting() {
		SettlementMaterialCrafting.Recipe recipe = currentMaterialRecipe;
		if (recipe != SettlementMaterialCrafting.Recipe.PLANKS && recipe != SettlementMaterialCrafting.Recipe.DOOR
			&& recipe != SettlementMaterialCrafting.Recipe.BED && recipe != SettlementMaterialCrafting.Recipe.WOOL
			&& recipe != SettlementMaterialCrafting.Recipe.TORCH_COAL && recipe != SettlementMaterialCrafting.Recipe.TORCH_CHARCOAL) return null;
		if (!(villager.level() instanceof ServerLevel level) || settlement == null) return null;
		SettlementSavedData data = settlementData();
		Settlement camp = settlement;
		BlockPos store = data.getStockpile(camp).orElse(null);
		BlockPos station = data.getCraftingStation(camp).orElse(null);
		if (store == null || station == null || !ConstructionWorkshop.withinWorkRange(camp.center(), store)
			|| !ConstructionWorkshop.withinWorkRange(camp.center(), station) || !foragePositionAccessible(level, station)) return null;
		var binding = SettlementCraftingStore.capture(level, store);
		BlockState stationState = level.getBlockState(station);
		if (binding == null || !stationState.is(Blocks.CRAFTING_TABLE)) return null;
		ConstructionJob job = data.getConstructionJob(camp).orElse(null);
		var projects = ConstructionPlanner.projects(data, camp);
		var planIds = job == null ? projects.stream().map(ConstructionPlanner.Project::blueprintId).toList() : List.of(job.blueprintId());
		var plans = planIds.stream().map(BlueprintManager::get).toList();
		var demands = ConstructionPlanner.materialDemands(data, camp);
		return SettlementWorkbenchCrafting.prepare(data, camp, villager.getUUID(), villager.getInventory(), recipe,
			() -> SettlementSavedData.get(level) == data && settlement == camp && villager.level() == level
				&& villager.isAlive() && !villager.isRemoved() && !villager.isBaby()
				&& !villager.isSleeping() && !villager.isTrading() && !villager.isNoAi() && !villager.isPassenger()
				&& level.getEntity(villager.getUUID()) == villager && foragePositionAccessible(level, villager.blockPosition())
				&& binding.valid() && foragePositionAccessible(level, station)
				&& level.getBlockState(station).equals(stationState)
				&& SettlementMembershipClaims.belongsTo(SettlementMembershipClaims.resolve(level, villager.getUUID()),
					level.dimension(), data, camp),
			() -> java.util.Objects.equals(job, data.getConstructionJob(camp).orElse(null))
				&& projects.equals(ConstructionPlanner.projects(data, camp))
				&& plans.equals(planIds.stream().map(BlueprintManager::get).toList())
				&& demands.equals(ConstructionPlanner.materialDemands(data, camp))
				&& (recipe == SettlementMaterialCrafting.Recipe.WOOL ? woolDemandStillNeeded(level, data, camp, demands)
					: isTorchRecipe(recipe) ? torchDemandStillNeeded(demands, SettlementStockpile.getStockpile(level, data, camp))
					: recipe == SettlementMaterialCrafting.Recipe.BED ? bedDemandStillNeeded(level, data, camp, demands)
					: recipe == SettlementMaterialCrafting.Recipe.PLANKS
					? plankDemandStillNeeded(level, data, camp, demands) : doorDemandStillNeeded(level, data, camp, demands)));
	}

	/** Recheck output/prerequisite shortage without asking for a second log after pickup. */
	private boolean plankDemandStillNeeded(ServerLevel level, SettlementSavedData data, Settlement camp,
		List<Map<Item, Integer>> demands) {
		Container stockpile = SettlementStockpile.getStockpile(level, data, camp);
		if (stockpile == null) return false;
		int planks = SettlementMaterialCrafting.countPlain(stockpile, Items.OAK_PLANKS);
		for (Map<Item, Integer> demand : demands) {
			if (demand.getOrDefault(Items.OAK_PLANKS, 0) > planks) return true;
			for (var recipe : SettlementMaterialCrafting.Recipe.values()) {
				long available = recipe.output() == Items.WHITE_BED
					? ConstructionMaterials.available(stockpile, data.simulationOf(camp).orElse(null), recipe.output())
					: SettlementMaterialCrafting.countPlain(stockpile, recipe.output());
				if (demand.getOrDefault(recipe.output(), 0) > available
					&& recipe.ingredients().getOrDefault(Items.OAK_PLANKS, 0) > planks) return true;
			}
		}
		return false;
	}

	/** Inputs are already in custody after pickup; only the output shortage matters. */
	private boolean doorDemandStillNeeded(ServerLevel level, SettlementSavedData data, Settlement camp,
		List<Map<Item, Integer>> demands) {
		Container stockpile = SettlementStockpile.getStockpile(level, data, camp);
		if (stockpile == null) return false;
		int doors = SettlementMaterialCrafting.countPlain(stockpile, Items.OAK_DOOR);
		return demands.stream().anyMatch(demand -> demand.getOrDefault(Items.OAK_DOOR, 0) > doors);
	}

	/** Recheck public construction beds, not ingredients already carried by the worker. */
	private boolean bedDemandStillNeeded(ServerLevel level, SettlementSavedData data, Settlement camp,
		List<Map<Item, Integer>> demands) {
		Container stockpile = SettlementStockpile.getStockpile(level, data, camp);
		if (stockpile == null) return false;
		long beds = ConstructionMaterials.available(stockpile, data.simulationOf(camp).orElse(null), Items.WHITE_BED);
		return demands.stream().anyMatch(demand -> demand.getOrDefault(Items.WHITE_BED, 0) > beds);
	}

	/** Only make the next bed's wool; never re-require string already carried to the table. */
	private boolean woolDemandStillNeeded(ServerLevel level, SettlementSavedData data, Settlement camp,
		List<Map<Item, Integer>> demands) {
		Container stockpile = SettlementStockpile.getStockpile(level, data, camp);
		return SettlementWoolSupply.nextBedNeedsWool(demands, stockpile, data.simulationOf(camp).orElse(null));
	}

	private static boolean isTorchRecipe(SettlementMaterialCrafting.Recipe recipe) {
		return recipe == SettlementMaterialCrafting.Recipe.TORCH_COAL || recipe == SettlementMaterialCrafting.Recipe.TORCH_CHARCOAL;
	}

	/** Check remaining public output demand even after the last fuel and stick leave the store. */
	static boolean torchDemandStillNeeded(List<Map<Item, Integer>> demands, Container stockpile) {
		if (stockpile == null) return false;
		int torches = SettlementMaterialCrafting.countPlain(stockpile, Items.TORCH);
		return demands.stream().anyMatch(demand -> demand.getOrDefault(Items.TORCH, 0) > torches);
	}

	private void tickWorkbenchCrafting() {
		// start and direct tick both use the same final live gates before resolving the store.
		if (!(villager.level() instanceof ServerLevel level) || !isTargetValid()) { stop(); return; }
		if (shouldEat() || meetingTicket != null || emergencyForage()) { stop(); return; }
		if (!atCargoStore()) { progress = 0; tickStoreTravel(false); return; }
		villager.getNavigation().stop();
		villager.getLookControl().setLookAt(target.getCenter());
		if (isCollectWorkbenchAction()) {
			Container stockpile = SettlementStockpile.getStockpile(level, settlementData(), settlement);
			if (!SettlementWorkbenchCrafting.pickup(workbenchTicket, settlementData(), settlement,
				villager.getUUID(), villager.getInventory(), stockpile, () -> isTargetValid() && atCargoStore())) { stop(); return; }
			action = workbenchTicket.recipe() == SettlementMaterialCrafting.Recipe.WOOL ? Action.WORKBENCH_WOOL
				: isTorchRecipe(workbenchTicket.recipe()) ? Action.WORKBENCH_TORCHES
				: workbenchTicket.recipe() == SettlementMaterialCrafting.Recipe.BED ? Action.WORKBENCH_BEDS
				: workbenchTicket.recipe() == SettlementMaterialCrafting.Recipe.DOOR
				? Action.WORKBENCH_DOORS : Action.WORKBENCH_PLANKS;
			target = workbenchTicket.station();
			progress = 0;
			travelTicks = 0;
			return;
		}
		int duration = settlementData().personalityOf(settlement, villager.getUUID())
			.workDuration(CRAFT_TICKS, VillagerPersonality.WorkType.CRAFT);
		if (settlementData().needsOf(settlement, villager.getUUID()).satiety() < 20) duration = duration * 5 / 4;
		duration = SettlementSimulationService.craftingWorkDuration(settlementData(), settlement, villager.getUUID(), duration);
		if (++progress < duration) return;
		SettlementWorkbenchCrafting.finish(workbenchTicket, settlementData(), settlement, villager.getUUID(),
			villager.getInventory(), () -> isTargetValid() && atCargoStore());
		stop(); // Products stay in the real bag for the ordinary cargo delivery route.
	}

	private SettlementStarterCrafting.Ticket prepareStarterCrafting() {
		if (!(villager.level() instanceof ServerLevel level) || settlement == null) return null;
		SettlementSavedData data = settlementData();
		if (!SettlementStarterCrafting.eligible(data, settlement)) return null;
		BlockPos store = data.getStockpile(settlement).orElse(null);
		if (store == null) return null;
		var binding = SettlementCraftingStore.capture(level, store);
		if (binding == null) return null;
		Container stockpile = SettlementStockpile.getStockpile(level, data, settlement);
		return SettlementStarterCrafting.prepare(data, settlement, villager.getUUID(), stockpile,
			() -> SettlementSavedData.get(level) == data && villager.level() == level
				&& villager.isAlive() && !villager.isRemoved() && !villager.isBaby()
				&& !villager.isSleeping() && !villager.isTrading()
				&& level.getEntity(villager.getUUID()) == villager && level.isLoaded(villager.blockPosition())
				&& level.isPositionEntityTicking(villager.blockPosition()) && binding.valid());
	}

	private void craftFirstWorkbench() {
		// tick() can be driven directly: do not rely only on canContinueToUse or a prior claim.
		if (currentStarterCraft == null || target == null || settlement == null
			|| !(villager.level() instanceof ServerLevel level) || !target.equals(currentStarterCraft.store())
			|| villager.distanceToSqr(target.getCenter()) > INTERACTION_DISTANCE_SQUARED
			|| !SettlementStarterCrafting.valid(currentStarterCraft, settlementData(), settlement, villager.getUUID())) return;
		Container stockpile = SettlementStockpile.getStockpile(level, settlementData(), settlement);
		SettlementStarterCrafting.finish(currentStarterCraft, settlementData(), settlement, villager.getUUID(), stockpile);
	}

	private SettlementMaterialCrafting.Recipe chooseMaterialRecipe() {
		List<Map<Item, Integer>> demands = constructionMaterialDemands();
		if (demands.isEmpty() || !hasCraftingStation()) {
			return null;
		}
		Container stockpile = SettlementStockpile.getStockpile((ServerLevel) villager.level(), settlementData(), settlement);
		if (stockpile == null) {
			return null;
		}
		SettlementSimulationState state = settlementData().simulationOf(settlement).orElse(null);
		for (Map<Item, Integer> demand : demands) {
			var recipe = SettlementMaterialCrafting.chooseRecipe(stockpile, state, demand);
			if (recipe.isPresent()) {
				return recipe.get();
			}
		}
		return null;
	}

	private List<Map<Item, Integer>> constructionMaterialDemands() {
		return ConstructionPlanner.materialDemands(settlementData(), settlement);
	}

	private boolean needsConstructionLogs() {
		List<Map<Item, Integer>> demands = constructionMaterialDemands();
		if (demands.isEmpty()) { return false; }
		Container stockpile = SettlementStockpile.getStockpile((ServerLevel) villager.level(), settlementData(), settlement);
		return SettlementStarterCrafting.needsLog(settlementData(), settlement, stockpile)
			|| SettlementMaterialCrafting.needsConstructionLogs(stockpile, villager.getInventory(),
			settlementData().simulationOf(settlement).orElse(null), demands, hasCraftingStation());
	}

	private void craftMaterials() {
		// Recheck shortages and the pinned plan after travel/work, including a data-pack reload.
		if (currentMaterialRecipe == null || currentMaterialRecipe == SettlementMaterialCrafting.Recipe.PLANKS
			|| currentMaterialRecipe == SettlementMaterialCrafting.Recipe.DOOR
			|| currentMaterialRecipe == SettlementMaterialCrafting.Recipe.BED
			|| currentMaterialRecipe == SettlementMaterialCrafting.Recipe.WOOL
			|| currentMaterialRecipe == SettlementMaterialCrafting.Recipe.TORCH_COAL
			|| currentMaterialRecipe == SettlementMaterialCrafting.Recipe.TORCH_CHARCOAL
			|| chooseMaterialRecipe() != currentMaterialRecipe) {
			return;
		}
		Container stockpile = SettlementStockpile.getStockpile((ServerLevel) villager.level(), settlementData(), settlement);
		if (stockpile == null) return;
		var attempt = SettlementSimulationService.beginCrafting(settlementData(), settlement, villager.getUUID(),
			currentMaterialRecipe.id(), currentMaterialRecipe.output(), currentMaterialRecipe.count());
		if (SettlementMaterialCrafting.craft(stockpile,
			settlementData().simulationOf(settlement).orElse(null), currentMaterialRecipe,
			villager.getUUID(), settlementData()::setDirty)) {
			SettlementSimulationService.recordCrafting(villager, settlementData(), settlement, attempt);
		}
	}

	private BlockPos chooseTarget(Action action) {
		if (action == null || settlement == null) {
			return null;
		}
		if (isWorkbenchAction()) {
			return workbenchTicket == null ? null : isCollectWorkbenchAction()
				? workbenchTicket.store() : workbenchTicket.station();
		}
		if (action == Action.GATHER) {
			return selectedGatherTarget;
		}
		if (isShearsLoanAction()) return shearsLoanTicket == null ? null : shearsLoanTicket.store;
		if (action == Action.SHEAR_SHEEP) return shearingSession == null ? null : shearingSession.position();
		if (action == Action.BUILD) {
			return currentBuildTarget == null ? null : currentBuildTarget.position();
		}
		if (action == Action.FORAGE || action == Action.HARVEST_CROP) {
			return forageTarget;
		}
		if (action == Action.EAT) {
			return settlementData().getStockpile(settlement).orElse(null);
		}
		if (action == Action.CRAFT_FIRST_WORKBENCH) {
			return currentStarterCraft == null ? null : currentStarterCraft.store();
		}
		if (action == Action.POLISH_PICKAXE || action == Action.GRIND_SMOOTH_STONE) {
			return settlementData().getGrindingStation(settlement).orElse(null);
		}
		return settlementData().getCraftingStation(settlement).orElse(null);
	}

	private BlockPos chooseGatherTarget() {
		if (settlement == null || !SettlementSimulationService.hasGatherTime(settlementData(), settlement, villager.getUUID())) {
			return null;
		}
		materialCandidates = SettlementMaterialGathering.candidates(settlementData(), settlement, villager.getUUID(),
			SettlementMaterialGathering.probe((ServerLevel) villager.level(), settlementData(), settlement, villager), villager.level().getGameTime());
		if (needsConstructionLogs()) {
			BlockPos oak = settlementData().resourcesOf(settlement).stream()
				.filter(pos -> villager.distanceToSqr(pos.getCenter()) <= SEARCH_RADIUS_SQUARED)
				.filter(pos -> settlementData().materialLeases().available(pos, villager.getUUID(), villager.level().getGameTime()))
				.filter(pos -> SettlementGathering.validTarget((ServerLevel) villager.level(), settlementData(), settlement, villager, pos))
				.filter(pos -> villager.level().getBlockState(pos).is(Blocks.OAK_LOG))
				.min(Comparator.comparingDouble(pos -> pos.distSqr(villager.blockPosition())))
				.orElse(null);
			if (oak != null) return oak;
			oak = nearestAutomaticMaterial(true);
			if (oak != null) return oak;
		}
		if (role == WorkRole.WOODCUTTER) {
			BlockPos logs = nearestResource(true);
			if (logs != null) {
				return logs;
			}
		} else if (role == WorkRole.MINER) {
			BlockPos stone = nearestResource(false);
			if (stone != null) {
				return stone;
			}
		}
		if (needsSticks()) {
			BlockPos logs = nearestResource(true);
			if (logs != null) {
				return logs;
			}
		}
		if (needsCobblestone()) {
			BlockPos stone = nearestResource(false);
			if (stone != null) {
				return stone;
			}
		}
		if (hasStonePickaxe()) {
			BlockPos stone = nearestResource(false);
			if (stone != null) {
				return stone;
			}
			return nearestResource(true);
		}
		return null;
	}

	private BlockPos nearestResource(boolean logs) {
		if (settlement == null || !(villager.level() instanceof ServerLevel level)) {
			return null;
		}
		BlockPos manual = settlementData().resourcesOf(settlement).stream()
			.filter(pos -> villager.distanceToSqr(pos.getCenter()) <= SEARCH_RADIUS_SQUARED)
			.filter(pos -> settlementData().materialLeases().available(pos, villager.getUUID(), villager.level().getGameTime()))
			.filter(pos -> SettlementGathering.validTarget(level, settlementData(), settlement, villager, pos))
			.filter(pos -> {
				BlockState state = level.getBlockState(pos);
				return logs ? state.is(BlockTags.LOGS) : state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE);
			})
			.min(Comparator.comparingDouble(pos -> pos.distSqr(villager.blockPosition())))
			.orElse(null);
		return manual != null ? manual : nearestAutomaticMaterial(logs);
	}

	private BlockPos nearestAutomaticMaterial(boolean logs) {
		var kind = logs ? SettlementResourceObservations.Kind.OAK_LOG : SettlementResourceObservations.Kind.STONE;
		return materialCandidates.stream().filter(choice -> choice.observation().kind() == kind)
			.map(SettlementMaterialGathering.Choice::position).findFirst().orElse(null);
	}

	private boolean navigateToTarget() {
		if (target == null) {
			return false;
		}
		if (action == Action.GATHER && !isTargetValid()) { stop(); return false; }
		if ((action == Action.FORAGE || action == Action.HARVEST_CROP || materialTicket != null)
			&& (!isTargetValid() || !constructionPathIsTicking())) { stop(); return false; }
		if ((action == Action.BUILD || action == Action.CRAFT_FIRST_WORKBENCH) && !constructionPathIsTicking()) {
			villager.getNavigation().stop();
			travelTicks = 0;
		}
		if (action == Action.BUILD && villager.getBoundingBox().intersects(new AABB(target))) {
			travelTicks++;
			if (villager.getNavigation().isDone() || travelTicks >= 20) {
				if (travelTicks >= 20) {
					villager.getNavigation().stop();
					travelTicks = 0;
				}
				if (!navigateToConstructionApproach()) {
					stop();
				}
			}
			return false;
		}
		double interactionDistance = action == Action.BUILD ? BUILD_INTERACTION_DISTANCE_SQUARED : INTERACTION_DISTANCE_SQUARED;
		if (villager.distanceToSqr(target.getCenter()) <= interactionDistance) {
			if (action == Action.BUILD) {
				villager.getNavigation().stop();
			}
			villager.getLookControl().setLookAt(target.getCenter());
			travelTicks = 0;
			return true;
		}
		travelTicks++;
		if (travelTicks >= 100) {
			villager.getNavigation().stop();
			travelTicks = 0;
		}
		if (villager.getNavigation().isDone()) {
			boolean moving = action == Action.BUILD ? navigateToConstructionApproach()
				: (action == Action.FORAGE || action == Action.HARVEST_CROP || materialTicket != null) ? navigateToForageApproach() : navigateTo(target.above());
			if (!moving) {
				stop();
			} else {
				travelTicks = 0;
			}
		}
		return false;
	}

	private boolean navigateToForageApproach() {
		ServerLevel level = (ServerLevel) villager.level();
		List<BlockPos> candidates = new ArrayList<>();
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos candidate = target.relative(direction);
			if (foragePositionAccessible(level, candidate) && foragePositionAccessible(level, candidate.above())
				&& foragePositionAccessible(level, candidate.below())
				&& level.getBlockState(candidate).getCollisionShape(level, candidate).isEmpty()
				&& level.getBlockState(candidate.above()).getCollisionShape(level, candidate.above()).isEmpty()
				&& level.getBlockState(candidate.below()).isSolidRender(level, candidate.below())) {
				candidates.add(candidate);
			}
		}
		candidates.sort(Comparator.comparingDouble(pos -> pos.distSqr(villager.blockPosition())));
		for (BlockPos candidate : candidates) {
			if (navigateTo(candidate)) {
				return true;
			}
		}
		return navigateTo(target);
	}

	private static boolean foragePositionAccessible(ServerLevel level, BlockPos position) {
		return !level.isOutsideBuildHeight(position) && level.getWorldBorder().isWithinBounds(position)
			&& level.isLoaded(position) && level.isPositionEntityTicking(position);
	}

	private boolean navigateToConstructionApproach() {
		ServerLevel level = (ServerLevel) villager.level();
		return constructionApproach.attempt(target, villager.position(), level.getGameTime(), new ConstructionApproach.Probe() {
			public boolean inBounds(BlockPos position) {
				return !level.isOutsideBuildHeight(position) && level.getWorldBorder().isWithinBounds(position);
			}
			public boolean loaded(BlockPos position) { return level.isLoaded(position); }
			public boolean ticking(BlockPos position) { return level.isPositionEntityTicking(position); }
			public BlockState state(BlockPos position) { return level.getBlockState(position); }
		}, this::navigationSearchRegionLoaded, candidate -> {
			var path = villager.getNavigation().createPath(candidate, 0);
			if (path == null || !path.canReach()) return false;
			for (int i = 0; i < path.getNodeCount(); i++) {
				if (!level.isLoaded(path.getNodePos(i)) || !level.isPositionEntityTicking(path.getNodePos(i))) return false;
			}
			return villager.getNavigation().moveTo(path,
				settlementData().personalityOf(settlement, villager.getUUID()).movementSpeed());
		});
	}

	private boolean navigateTo(BlockPos pos) {
		if ((action == Action.CRAFT_FIRST_WORKBENCH || action == Action.FORAGE || action == Action.HARVEST_CROP || materialTicket != null)
			&& !navigationSearchRegionLoaded()) return false;
		if (action == Action.FORAGE || action == Action.HARVEST_CROP || materialTicket != null) {
			ServerLevel level = (ServerLevel) villager.level();
			if (!foragePositionAccessible(level, pos)) return false;
			var path = villager.getNavigation().createPath(pos, 0);
			if (path == null || !path.canReach()) return false;
			for (int index = 0; index < path.getNodeCount(); index++) {
				if (!foragePositionAccessible(level, path.getNodePos(index))) return false;
			}
			return villager.getNavigation().moveTo(path,
				settlementData().personalityOf(settlement, villager.getUUID()).movementSpeed());
		}
		double speed = settlementData().personalityOf(settlement, villager.getUUID()).movementSpeed();
		boolean moving = villager.getNavigation().moveTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, speed);
		if (!moving || (action != Action.BUILD && action != Action.CRAFT_FIRST_WORKBENCH)) {
			return moving;
		}
		if (constructionPathIsTicking()) {
			return true;
		}
		villager.getNavigation().stop();
		return false;
	}

	private boolean constructionPathIsTicking() {
		var path = villager.getNavigation().getPath();
		if (path == null) {
			return true;
		}
		ServerLevel level = (ServerLevel) villager.level();
		for (int index = 0; index < path.getNodeCount(); index++) {
			if (!level.isLoaded(path.getNodePos(index)) || !level.isPositionEntityTicking(path.getNodePos(index))) {
				return false;
			}
		}
		return true;
	}

	private boolean isTargetValid() {
		if (target == null || action == null || settlement == null || !(villager.level() instanceof ServerLevel level)) {
			return false;
		}
		if (action == Action.ATTEND_LESSON) return teachingValid();
		if (isShearsLoanAction()) return shearsLoanTicket != null && target.equals(shearsLoanTicket.store)
			&& shearsLoanTicket.returning == (action == Action.RETURN_SHEARS) && SettlementShearing.validLoan(shearsLoanTicket);
		if (hasAnyShearsLoan() && action != Action.SHEAR_SHEEP && action != Action.EAT) return false;
		if (isWorkbenchAction()) return workbenchTicket != null
			&& target.equals(isCollectWorkbenchAction() ? workbenchTicket.store() : workbenchTicket.station())
			&& workbenchTicket.collected() == !isCollectWorkbenchAction()
			&& ((action != Action.COLLECT_TORCH_MATERIALS && action != Action.WORKBENCH_TORCHES)
				|| isTorchRecipe(currentMaterialRecipe))
			&& workbenchTicket.recipe() == ((action == Action.COLLECT_TORCH_MATERIALS || action == Action.WORKBENCH_TORCHES)
				? currentMaterialRecipe : (action == Action.COLLECT_BED_MATERIALS || action == Action.WORKBENCH_BEDS)
				? SettlementMaterialCrafting.Recipe.BED : (action == Action.COLLECT_WOOL_MATERIALS || action == Action.WORKBENCH_WOOL)
				? SettlementMaterialCrafting.Recipe.WOOL : (action == Action.COLLECT_DOOR_MATERIALS || action == Action.WORKBENCH_DOORS)
				? SettlementMaterialCrafting.Recipe.DOOR : SettlementMaterialCrafting.Recipe.PLANKS)
			&& SettlementWorkbenchCrafting.valid(workbenchTicket, settlementData(), settlement, villager.getUUID(), villager.getInventory());
		if (action == Action.ATTEND_MEETING) return meetingTicket != null;
		if (action == Action.SHEAR_SHEEP) return shearingSession != null && SettlementShearing.valid(shearingSession);
		if (isSupplyAction()) return target.equals(supplyStore) && supplyAuthorization != null && supplyAuthorization.getAsBoolean()
			&& (action == Action.RETURN_BUILD_SUPPLY ? hasReturnableSupply() : currentSupplyTargetValid());
		if (action == Action.RETURN_TO_STOCKPILE) return cargoTicket != null && target.equals(cargoTicket.store())
			&& SettlementCargoDelivery.valid(cargoTicket, settlementData(), settlement, villager.getUUID(), villager.getInventory());
		if (action == Action.FORAGE) {
			return forageRegistry != null && forageRegistry.valid(forageLease, settlementData(), settlement,
				villager.getUUID(), target, level.getGameTime())
				&& SettlementForaging.validTarget(level, settlementData(), settlement, villager, target);
		}
		if (action == Action.GATHER) {
			return !hasReturnCargo() && materialRegistry != null
				&& materialRegistry.valid(materialLease, settlementData(), settlement, villager.getUUID(), target, level.getGameTime())
				&& (materialTicket != null
					? SettlementMaterialGathering.valid(materialTicket, SettlementMaterialGathering.probe(level, settlementData(), settlement, villager))
					: SettlementGathering.validTarget(level, settlementData(), settlement, villager, target));
		}
		if (!level.isLoaded(target) || !level.isPositionEntityTicking(target)) return false;
		if (action == Action.EAT) {
			if (foodTicket != null) return true; // Queue revalidates attendance even after stock runs out.
			return settlementData().getStockpile(settlement).filter(target::equals).isPresent()
				&& settlementData().needsOf(settlement, villager.getUUID()).satiety() < SettlementNeeds.FULL_AT
				&& (hasFoodAvailable() || hasPendingFoodRefusal());
		}
		if (action == Action.HARVEST_CROP) {
			return SettlementCropHarvest.validTarget(level, settlementData(), settlement, target);
		}
		if (action == Action.BUILD) {
			return currentBuildTarget != null && ConstructionSystem.nextBuildTarget(level, settlementData(), settlement, villager)
				.filter(next -> next.job().equals(currentBuildTarget.job()) && next.position().equals(target))
				.isPresent();
		}
		if (action == Action.CRAFT_FIRST_WORKBENCH) {
			return currentStarterCraft != null && target.equals(currentStarterCraft.store())
				&& SettlementStarterCrafting.valid(currentStarterCraft, settlementData(), settlement, villager.getUUID());
		}
		Block station = action == Action.POLISH_PICKAXE || action == Action.GRIND_SMOOTH_STONE
			? Blocks.STONECUTTER
			: Blocks.CRAFTING_TABLE;
		return villager.level().getBlockState(target).is(station);
	}

	/** Current physical cargo only, sharing food eligibility with final unloading. */
	private boolean hasReturnCargo() {
		if (SettlementCargo.hasCargo(villager.getInventory())) return true;
		if (!(villager.level() instanceof ServerLevel level)) return false;
		SettlementSavedData data = SettlementSavedData.get(level);
		Settlement camp = settlement != null ? settlement : data.findSettlementForVillager(villager.getUUID()).orElse(null);
		return (camp != null && (SettlementWorkbenchCargo.hasReturnable(villager.getInventory(), camp.id())
			|| SettlementShearingCargo.hasReturnable(villager.getInventory(), camp.id())))
			|| SettlementCargoDelivery.hasDeliverableFood(data, camp, villager.getUUID(), villager.getInventory())
			|| SettlementFoodCargo.hasSurplus(data, camp, villager.getUUID(), villager.getInventory());
	}

	private boolean emergencyForage() {
		// Carried usable food is the solution to an empty public ration, not a reason to gather more.
		if (hasDeliverableFoodCargo()) return false;
		return settlement != null && settlementData().needsOf(settlement, villager.getUUID()).satiety() < 20
			&& !hasFoodAvailable() && shouldForage();
	}

	private boolean hasDeliverableFoodCargo() {
		if (!(villager.level() instanceof ServerLevel level)) return false;
		SettlementSavedData data = SettlementSavedData.get(level);
		Settlement camp = settlement != null ? settlement : data.findSettlementForVillager(villager.getUUID()).orElse(null);
		return SettlementCargoDelivery.hasDeliverableFood(data, camp, villager.getUUID(), villager.getInventory());
	}

	private boolean isSupplyAction() {
		return action == Action.COLLECT_BUILD_SUPPLY || action == Action.RETURN_BUILD_SUPPLY;
	}

	private boolean requiresBuildSupply() {
		return currentBuildTarget != null && currentBuildTarget.instruction().consume()
			&& ConstructionSupply.supported(currentBuildTarget.instruction().item());
	}

	private boolean hasCurrentSupply() {
		return currentBuildTarget != null && ConstructionSupply.hasFor(villager.getInventory(),
			villager.level().dimension().location(), settlement.id(), villager.getUUID(),
			currentBuildTarget.job(), currentBuildTarget.instruction());
	}

	private boolean hasReturnableSupply() {
		return settlement != null && ConstructionSupply.hasReturnable(villager.getInventory(),
			villager.level().dimension().location(), settlement.id(), villager.getUUID(),
			returnSupplyJob());
	}

	private ConstructionJob returnSupplyJob() {
		ConstructionJob job = settlementData().getConstructionJob(settlement).orElse(null);
		return ConstructionSystem.returnSupplyJob(settlementData(), job,
			job == null ? null : BlueprintManager.get(job.blueprintId()));
	}

	private boolean currentSupplyTargetValid() {
		return requiresBuildSupply() && !ConstructionSupply.hasAny(villager.getInventory())
			&& settlementData().getConstructionJob(settlement).filter(currentBuildTarget.job()::equals).isPresent()
			&& ConstructionSystem.nextBuildTarget((ServerLevel) villager.level(), settlementData(), settlement, villager)
				.filter(currentBuildTarget::equals).isPresent();
	}

	/** Travel authority binds identities, never an inventory wrapper or a copied item. */
	private boolean prepareBuildSupply(boolean returning) {
		ServerLevel level = (ServerLevel) villager.level();
		SettlementSavedData data = settlementData();
		Settlement camp = settlement;
		if (!SettlementMembershipClaims.belongsTo(SettlementMembershipClaims.resolve(level, villager.getUUID()),
			level.dimension(), data, camp)) return deferBuildSupply();
		BlockPos store = data.getStockpile(camp).orElse(null);
		SettlementCraftingStore.Binding binding = SettlementCraftingStore.capture(level, store);
		if (binding == null) return deferBuildSupply();
		Object member = data.memberIdentity(villager.getUUID());
		Object storeToken = data.stockpileBindingToken(camp);
		supplyAuthorization = () -> SettlementSavedData.get(level) == data && settlement == camp
			&& villager.level() == level && data.findSettlementForVillager(villager.getUUID()).orElse(null) == camp
			&& member != null && data.memberIdentity(villager.getUUID()) == member && data.isMember(camp, villager.getUUID())
			&& SettlementMembershipClaims.belongsTo(SettlementMembershipClaims.resolve(level, villager.getUUID()), level.dimension(), data, camp)
			&& villager.isAlive() && !villager.isRemoved() && !villager.isBaby() && !villager.isSleeping()
			&& !villager.isTrading() && !villager.isNoAi() && !villager.isPassenger()
			&& level.getEntity(villager.getUUID()) == villager && level.isLoaded(villager.blockPosition())
			&& level.isPositionEntityTicking(villager.blockPosition())
			&& data.stockpileBindingToken(camp) == storeToken
			&& data.getStockpile(camp).filter(store::equals).isPresent() && binding.valid();
		if (!supplyAuthorization.getAsBoolean() || (returning ? !hasReturnableSupply() : !currentSupplyTargetValid()))
			return deferBuildSupply();
		supplyStore = store;
		action = returning ? Action.RETURN_BUILD_SUPPLY : Action.COLLECT_BUILD_SUPPLY;
		target = store;
		progress = 0;
		return true;
	}

	private boolean deferBuildSupply() {
		supplyStore = null;
		supplyCooldown.defer(villager.level().getGameTime());
		supplyAuthorization = null;
		return false;
	}

	private void tickBuildSupply() {
		if (!isTargetValid()) { deferBuildSupply(); stop(); return; }
		if (shouldEat() || meetingTicket != null || emergencyForage()) { stop(); return; }
		if (!atCargoStore()) { tickStoreTravel(true); return; }
		villager.getNavigation().stop();
		ServerLevel level = (ServerLevel) villager.level();
		// Resolve the live store only after physical arrival; helper rechecks the full authority at mutation.
		Container store = SettlementStockpile.getStockpile(level, settlementData(), settlement);
		java.util.function.BooleanSupplier authorized = () -> isTargetValid() && atCargoStore();
		if (action == Action.RETURN_BUILD_SUPPLY) {
			ConstructionJob rawJob = settlementData().getConstructionJob(settlement).orElse(null);
			ConstructionJob returnJob = returnSupplyJob();
			ConstructionSupply.returnUnused(villager.getInventory(), store, level.dimension().location(), settlement.id(),
				villager.getUUID(), returnJob, () -> authorized.getAsBoolean()
					&& java.util.Objects.equals(rawJob, settlementData().getConstructionJob(settlement).orElse(null))
					&& java.util.Objects.equals(returnJob, returnSupplyJob()));
			if (hasReturnableSupply()) deferBuildSupply();
		} else {
			ConstructionSupply.pickup(store, villager.getInventory(), level.dimension().location(), settlement.id(),
				villager.getUUID(), currentBuildTarget.job(), currentBuildTarget.instruction(), authorized);
			if (!hasCurrentSupply()) deferBuildSupply();
		}
		stop();
	}

	private boolean prepareCargoReturn() {
		ServerLevel level = (ServerLevel) villager.level();
		SettlementSavedData data = settlementData();
		Settlement camp = settlement;
		if (!SettlementMembershipClaims.belongsTo(SettlementMembershipClaims.resolve(level, villager.getUUID()),
			level.dimension(), data, camp)) { deferCargo("cargo_store_unavailable"); return false; }
		BlockPos store = data.getStockpile(camp).orElse(null);
		var binding = SettlementCraftingStore.capture(level, store);
		if (binding == null) { deferCargo("cargo_store_unavailable"); return false; }
		Object member = data.memberIdentity(villager.getUUID());
		cargoTicket = SettlementCargoDelivery.prepare(data, camp, villager.getUUID(), villager.getInventory(),
			() -> SettlementSavedData.get(level) == data && settlement == camp && villager.level() == level
				&& data.findSettlementForVillager(villager.getUUID()).filter(current -> current == camp).isPresent()
				&& data.memberIdentity(villager.getUUID()) == member && data.isMember(camp, villager.getUUID())
				&& villager.isAlive() && !villager.isRemoved() && !villager.isBaby()
				&& !villager.isSleeping() && !villager.isTrading() && !villager.isNoAi() && !villager.isPassenger()
				&& level.getEntity(villager.getUUID()) == villager && level.isLoaded(villager.blockPosition())
				&& level.isPositionEntityTicking(villager.blockPosition()) && binding.valid());
		if (cargoTicket == null) { deferCargo("cargo_store_unavailable"); return false; }
		action = Action.RETURN_TO_STOCKPILE;
		target = cargoTicket.store();
		cargoBlocker = null;
		progress = 0;
		return true;
	}

	private ConstructionApproach.Probe cargoProbe(ServerLevel level) {
		return new ConstructionApproach.Probe() {
			public boolean inBounds(BlockPos position) {
				return !level.isOutsideBuildHeight(position) && level.getWorldBorder().isWithinBounds(position);
			}
			public boolean loaded(BlockPos position) { return level.isLoaded(position); }
			public boolean ticking(BlockPos position) { return level.isPositionEntityTicking(position); }
			public BlockState state(BlockPos position) { return level.getBlockState(position); }
		};
	}

	private boolean atCargoStore() {
		return villager.level() instanceof ServerLevel level && target != null
			&& SettlementCargoApproach.arrived(target, villager.position(), cargoProbe(level));
	}

	private void deferCargo(String blocker) {
		cargoBlocker = blocker;
		cargoCooldown.defer(villager.level().getGameTime());
		cargoTicket = null;
	}

	private void tickCargoReturn() {
		// Independent final checks: direct ticks cannot bypass membership, binding or position gates.
		if (!(villager.level() instanceof ServerLevel level) || !isTargetValid()) {
			deferCargo("cargo_store_unavailable"); stop(); return;
		}
		if (!hasReturnCargo()) { cargoBlocker = null; stop(); return; }
		if (shouldEat() || meetingTicket != null || emergencyForage()) { stop(); return; }
		if (atCargoStore()) {
			villager.getNavigation().stop();
			if (!SettlementMembershipClaims.belongsTo(SettlementMembershipClaims.resolve(level, villager.getUUID()),
				level.dimension(), settlementData(), settlement)) {
				deferCargo("cargo_store_unavailable"); stop(); return;
			}
			// Do not even resolve a live container until actual safe arrival was established.
			Container currentStore = SettlementStockpile.getStockpile(level, settlementData(), settlement);
			var result = SettlementCargoDelivery.finish(cargoTicket, settlementData(), settlement,
				villager.getUUID(), villager.getInventory(), currentStore, this::atCargoStore);
			if (result.status() == SettlementCargoDelivery.Status.NO_CAPACITY
				|| (result.status() == SettlementCargoDelivery.Status.DELIVERED && hasReturnCargo()))
				deferCargo("cargo_store_full");
			else if (result.status() == SettlementCargoDelivery.Status.FOOD_UNAVAILABLE) deferCargo("cargo_food_unavailable");
			else if (result.status() != SettlementCargoDelivery.Status.DELIVERED) deferCargo("cargo_store_unavailable");
			else cargoBlocker = null;
			stop();
			return;
		}
		tickStoreTravel(false);
	}

	private void tickStoreTravel(boolean supply) {
		ServerLevel level = (ServerLevel) villager.level();
		if (++travelTicks >= SettlementCargoApproach.MAX_TRAVEL_TICKS || !constructionPathIsTicking()
			|| ((isWorkbenchAction() || isShearsLoanAction()) && !workbenchPathInBounds(level))) {
			if (isShearsLoanAction()) shearsLoanCooldown.defer(level.getGameTime()); else if (supply) supplyCooldown.defer(level.getGameTime()); else deferCargo("cargo_path_blocked"); stop(); return;
		}
		if (!villager.getNavigation().isDone()) return;
		var outcome = cargoApproach.attempt(target, villager.position(), level.getGameTime(), cargoProbe(level),
			this::navigationSearchRegionLoaded, candidate -> {
				var path = villager.getNavigation().createPath(candidate, 0);
				if (path == null || !path.canReach()) return false;
				for (int i = 0; i < path.getNodeCount(); i++) {
					if (!level.isLoaded(path.getNodePos(i)) || !level.isPositionEntityTicking(path.getNodePos(i))
						|| ((isWorkbenchAction() || isShearsLoanAction()) && !foragePositionAccessible(level, path.getNodePos(i)))) return false;
				}
				return villager.getNavigation().moveTo(path, settlementData().personalityOf(settlement, villager.getUUID()).movementSpeed());
			});
		if (outcome == SettlementCargoApproach.Outcome.BLOCKED) { if (isShearsLoanAction()) shearsLoanCooldown.defer(level.getGameTime()); else if (supply) supplyCooldown.defer(level.getGameTime()); else deferCargo("cargo_path_blocked"); stop(); }
	}

	private boolean workbenchPathInBounds(ServerLevel level) {
		var path = villager.getNavigation().getPath();
		if (path == null) return true;
		for (int i = 0; i < path.getNodeCount(); i++) {
			if (!foragePositionAccessible(level, path.getNodePos(i))) return false;
		}
		return true;
	}

	private boolean shouldEat() {
		if (settlementData().getStockpile(settlement).isEmpty()) return false;
		return SettlementFoodAccess.shouldEat(settlementData(), settlement, villager.getUUID(),
			villager.level().getDayTime() / net.minecraft.world.level.Level.TICKS_PER_DAY,
			settlementData().needsOf(settlement, villager.getUUID()).satiety(), hasFoodAvailable());
	}

	private boolean hasPendingFoodRefusal() {
		return SettlementSimulationService.hasPendingFoodRefusal(settlementData(), settlement, villager.getUUID());
	}

	private boolean shouldForage() {
		if (!SettlementForaging.canForage(settlementData(), settlement)) {
			return false;
		}
		// A personal ration cap is not a physical shortage in the communal store.
		boolean hasFood = SettlementSimulationService.isSurvivalEnabled(settlementData(), settlement)
			? SettlementSimulationService.hasFood(settlementData(), settlement) : hasFoodAvailable();
		return SettlementForaging.needsForage(
			hasFood, role, this::foodReserveLow, villager.level().getGameTime(), forageCooldownUntil
		);
	}

	private boolean foodReserveLow() {
		return SettlementForaging.reserveLow(settlementData(), settlement, SettlementStockpile.getStockpile(
			(ServerLevel) villager.level(), settlementData(), settlement
		));
	}

	private record ForageChoice(BlockPos position, boolean crop) { }

	private ForageChoice findRipeBerryBush() {
		if (!(villager.level() instanceof ServerLevel level)) return null;
		BlockPos origin = villager.blockPosition();
		BlockPos berry = SettlementForaging.findRipeBush(level, settlementData(), settlement, villager, origin).orElse(null);
		BlockPos crop = SettlementCropHarvest.findRipe(level, settlementData(), settlement, origin).orElse(null);
		// Reserve only the final berry choice, never a berry discarded in favor of a nearer potato plot.
		if (crop != null && (berry == null || origin.distSqr(crop) < origin.distSqr(berry))) return new ForageChoice(crop, true);
		if (berry == null || !SettlementForaging.validTarget(level, settlementData(), settlement, villager, berry)) return null;
		forageRegistry = settlementData().foragingLeases();
		forageLease = forageRegistry.claim(settlementData(), settlement, villager.getUUID(), berry, level.getGameTime());
		if (forageLease == null) { releaseForageLease(); return null; }
		return new ForageChoice(berry, false);
	}

	private void forage() {
		if (target == null || !(villager.level() instanceof ServerLevel level)) {
			return;
		}
		if (action == Action.HARVEST_CROP) {
			SettlementCropHarvest.harvest(level, settlementData(), settlement, villager, target);
		} else {
			try { SettlementForaging.harvest(level, settlementData(), settlement, villager, target, forageRegistry, forageLease); }
			finally { releaseForageLease(); }
		}
	}

	private boolean hasFoodAvailable() {
		if (SettlementSimulationService.isSurvivalEnabled(settlementData(), settlement)) {
			return SettlementSimulationService.hasFoodFor(settlementData(), settlement, villager.getUUID());
		}
		Container stockpile = SettlementStockpile.getStockpile((ServerLevel) villager.level(), settlementData(), settlement);
		return SettlementNeeds.hasFood(villager.getInventory(), stockpile);
	}

	private void gather() {
		try {
			if (!(villager.level() instanceof ServerLevel level) || !isTargetValid()
				|| villager.distanceToSqr(target.getCenter()) > INTERACTION_DISTANCE_SQUARED) return;
			if (materialTicket != null) SettlementMaterialGathering.harvest(level, materialTicket, villager, materialRegistry, materialLease);
			else SettlementGathering.harvest(level, settlementData(), settlement, villager, target);
		} finally { releaseMaterialLease(); }
	}

	private void craftPickaxe() {
		if (!canCraftPickaxe()) return;
		var attempt = SettlementSimulationService.beginCrafting(settlementData(), settlement, villager.getUUID(),
			"stone_pickaxe", Items.STONE_PICKAXE, 1);
		if (!withdrawItems(Items.COBBLESTONE, 3) || !withdrawItems(Items.STICK, 2)) {
			return;
		}
		if (removeItems(villager.getInventory(), Items.COBBLESTONE, 3) != 3
			|| removeItems(villager.getInventory(), Items.STICK, 2) != 2) {
			return;
		}
		villager.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE_PICKAXE));
		SettlementSimulationService.recordCrafting(villager, settlementData(), settlement, attempt);
	}

	private void craftSticks() {
		if (!canCraftSticks()) return;
		var attempt = SettlementSimulationService.beginCrafting(settlementData(), settlement, villager.getUUID(),
			"sticks", Items.STICK, 8);
		if (!withdrawOneLog() || !SettlementPersonalCrafting.craft(villager.getInventory(),
			stack -> stack.is(ItemTags.LOGS) && !SettlementWorkbenchCargo.isMarked(stack) && !SettlementShearingCargo.isMarked(stack) && !SettlementShearsLoan.isMarked(stack), new ItemStack(Items.STICK, 8))) {
			return;
		}
		SettlementSimulationService.recordCrafting(villager, settlementData(), settlement, attempt).ifPresent(completed ->
			SettlementSimulationService.recordCraftedProduct(
				villager, settlementData(), settlement, completed, Items.STICK, 8));
	}

	private boolean canCraftPickaxe() {
		return hasCraftingStation()
			&& !hasStonePickaxe()
			&& villager.getItemInHand(InteractionHand.MAIN_HAND).isEmpty()
			&& availableItems(Items.COBBLESTONE) >= 3
			&& availableItems(Items.STICK) >= 2;
	}

	private boolean canCraftSticks() {
		return hasCraftingStation()
			&& needsSticks()
			&& canConvertOne(stack -> stack.is(ItemTags.LOGS) && !SettlementWorkbenchCargo.isMarked(stack) && !SettlementShearingCargo.isMarked(stack) && !SettlementShearsLoan.isMarked(stack), Items.OAK_LOG, new ItemStack(Items.STICK, 8));
	}

	private boolean canPolishPickaxe() {
		return hasGrindingStation()
			&& hasStonePickaxe()
			&& SettlementToolUpgrade.supportsPolishing(villager.getItemInHand(InteractionHand.MAIN_HAND))
			&& !isPolishedPickaxe(villager.getItemInHand(InteractionHand.MAIN_HAND))
			&& availableItems(Items.SMOOTH_STONE) >= 3
			&& availableItems(Items.STICK) >= 2;
	}

	private boolean canGrindSmoothStone() {
		return hasGrindingStation()
			&& hasStonePickaxe()
			&& SettlementToolUpgrade.supportsPolishing(villager.getItemInHand(InteractionHand.MAIN_HAND))
			&& !isPolishedPickaxe(villager.getItemInHand(InteractionHand.MAIN_HAND))
			&& availableItems(Items.SMOOTH_STONE) < 3
			&& canConvertOne(stack -> stack.is(Items.COBBLESTONE) && !SettlementWorkbenchCargo.isMarked(stack) && !SettlementShearingCargo.isMarked(stack) && !SettlementShearsLoan.isMarked(stack), Items.COBBLESTONE,
				new ItemStack(Items.SMOOTH_STONE));
	}

	private void craftPolishedPickaxe() {
		if (!canPolishPickaxe()) return;
		var enchantments = villager.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT);
		Optional<ItemStack> polished = SettlementToolUpgrade.polish(
			villager.getItemInHand(InteractionHand.MAIN_HAND),
			enchantments.getHolderOrThrow(Enchantments.EFFICIENCY),
			enchantments.getHolderOrThrow(Enchantments.UNBREAKING));
		if (polished.isEmpty()) return;
		var attempt = SettlementSimulationService.beginCrafting(settlementData(), settlement, villager.getUUID(),
			"polished_stone_pickaxe", Items.STONE_PICKAXE, 1);
		if (!withdrawItems(Items.SMOOTH_STONE, 3) || !withdrawItems(Items.STICK, 2)) {
			return;
		}
		removeItems(villager.getInventory(), Items.SMOOTH_STONE, 3);
		removeItems(villager.getInventory(), Items.STICK, 2);
		villager.setItemInHand(InteractionHand.MAIN_HAND, polished.get());
		SettlementSimulationService.recordCrafting(villager, settlementData(), settlement, attempt);
	}

	private void grindSmoothStone() {
		if (!canGrindSmoothStone()) return;
		var attempt = SettlementSimulationService.beginCrafting(settlementData(), settlement, villager.getUUID(),
			"smooth_stone", Items.SMOOTH_STONE, 1);
		if (!withdrawItems(Items.COBBLESTONE, 1)
			|| !SettlementPersonalCrafting.craft(villager.getInventory(), stack -> stack.is(Items.COBBLESTONE) && !SettlementWorkbenchCargo.isMarked(stack) && !SettlementShearingCargo.isMarked(stack) && !SettlementShearsLoan.isMarked(stack),
				new ItemStack(Items.SMOOTH_STONE))) {
			return;
		}
		SettlementSimulationService.recordCrafting(villager, settlementData(), settlement, attempt).ifPresent(completed ->
			SettlementSimulationService.recordCraftedProduct(
				villager, settlementData(), settlement, completed, Items.SMOOTH_STONE, 1));
	}

	private String craftingRecipe() {
		return switch (action) {
			case CRAFT_PICKAXE -> "stone_pickaxe";
			case CRAFT_STICKS -> "sticks";
			case POLISH_PICKAXE -> "polished_stone_pickaxe";
			case GRIND_SMOOTH_STONE -> "smooth_stone";
			default -> "other";
		};
	}

	private long availableItems(Item item) {
		Container stockpile = SettlementStockpile.getStockpile((ServerLevel) villager.level(), settlementData(), settlement);
		return countCraftingItems(villager.getInventory(), item) + (stockpile == null ? 0 : countCraftingItems(stockpile, item))
			+ SettlementSimulationService.craftingResourceCount(settlementData(), settlement, item);
	}

	private boolean withdrawItems(Item item, int count) {
		int missing = count - countCraftingItems(villager.getInventory(), item);
		if (missing <= 0) {
			return true;
		}
		Container stockpile = SettlementStockpile.getStockpile((ServerLevel) villager.level(), settlementData(), settlement);
		int realAvailable = stockpile == null ? 0 : countCraftingItems(stockpile, item);
		long abstractAvailable = SettlementSimulationService.craftingResourceCount(settlementData(), settlement, item);
		if (realAvailable + abstractAvailable < missing) {
			return false;
		}
		if (realAvailable > 0) {
			int requested = Math.min(missing, realAvailable);
			int inserted = transferCraftingItems(stockpile, villager.getInventory(), item, requested);
			if (inserted < requested) {
				return false;
			}
			missing -= inserted;
		}
		return missing == 0 || SettlementSimulationService.withdrawForCrafting(
			villager, settlementData(), settlement, item, missing, craftingRecipe()
		) == missing;
	}

	private boolean withdrawOneLog() {
		if (villager.getInventory().hasAnyMatching(stack -> stack.is(ItemTags.LOGS) && !SettlementWorkbenchCargo.isMarked(stack) && !SettlementShearingCargo.isMarked(stack) && !SettlementShearsLoan.isMarked(stack))) {
			return true;
		}
		Container stockpile = SettlementStockpile.getStockpile((ServerLevel) villager.level(), settlementData(), settlement);
		if (stockpile != null) {
			for (int slot = 0; slot < stockpile.getContainerSize(); slot++) {
				if (!stockpile.getItem(slot).is(ItemTags.LOGS) || SettlementWorkbenchCargo.isMarked(stockpile.getItem(slot)) || SettlementShearingCargo.isMarked(stockpile.getItem(slot)) || SettlementShearsLoan.isMarked(stockpile.getItem(slot))) {
					continue;
				}
				ItemStack removed = stockpile.removeItem(slot, 1);
				if (SettlementStockpile.insert(villager.getInventory(), removed) == removed.getCount()) {
					return true;
				}
				SettlementStockpile.insert(stockpile, removed);
				return false;
			}
		}
		return SettlementSimulationService.withdrawForCrafting(
			villager, settlementData(), settlement, Items.OAK_LOG, 1, craftingRecipe()
		) == 1;
	}

	private boolean isPolishedPickaxe(ItemStack stack) {
		if (!stack.is(Items.STONE_PICKAXE) || SettlementWorkbenchCargo.isMarked(stack) || SettlementShearingCargo.isMarked(stack) || SettlementShearsLoan.isMarked(stack)) {
			return false;
		}
		var enchantments = villager.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT);
		return stack.getEnchantments().getLevel(enchantments.getHolderOrThrow(Enchantments.EFFICIENCY)) >= 1
			&& stack.getEnchantments().getLevel(enchantments.getHolderOrThrow(Enchantments.UNBREAKING)) >= 1;
	}

	private boolean hasCraftingStation() {
		return hasStation(settlementData().getCraftingStation(settlement), Blocks.CRAFTING_TABLE);
	}

	private boolean hasGrindingStation() {
		return settlement != null && hasStation(settlementData().getGrindingStation(settlement), Blocks.STONECUTTER);
	}

	private boolean hasStation(Optional<BlockPos> station, Block block) {
		if (settlement == null) {
			return false;
		}
		return station
			.filter(pos -> ConstructionWorkshop.withinWorkRange(settlement.center(), pos))
			.filter(pos -> villager.level().isLoaded(pos)
				&& ((ServerLevel) villager.level()).isPositionEntityTicking(pos)
				&& villager.level().getBlockState(pos).is(block))
			.isPresent();
	}

	private boolean needsSticks() {
		// Preserve auto-equipping from inventory before classifying the current main hand.
		boolean hasPickaxe = hasStonePickaxe();
		ItemStack hand = villager.getItemInHand(InteractionHand.MAIN_HAND);
		VillagerToolDemand.HandState handState = hasPickaxe && SettlementToolUpgrade.supportsPolishing(hand)
			? (isPolishedPickaxe(hand) ? VillagerToolDemand.HandState.POLISHED_STONE_PICKAXE
				: VillagerToolDemand.HandState.STONE_PICKAXE)
			: (hand.isEmpty() ? VillagerToolDemand.HandState.EMPTY : VillagerToolDemand.HandState.OCCUPIED);
		return VillagerToolDemand.needsSticks(handState, hasGrindingStation(), availableItems(Items.STICK));
	}

	private boolean needsCobblestone() {
		return !hasStonePickaxe() && availableItems(Items.COBBLESTONE) < 3;
	}

	/** Mirror the real withdrawal order without exporting or moving anything during action selection. */
	private boolean canConvertOne(Predicate<ItemStack> ingredient, Item abstractInput, ItemStack output) {
		if (villager.getInventory().hasAnyMatching(ingredient)) {
			return SettlementPersonalCrafting.canCraft(villager.getInventory(), ingredient, ItemStack.EMPTY, output);
		}
		Container stockpile = SettlementStockpile.getStockpile((ServerLevel) villager.level(), settlementData(), settlement);
		if (stockpile != null) {
			for (int slot = 0; slot < stockpile.getContainerSize(); slot++) {
				ItemStack stack = stockpile.getItem(slot);
				if (!stack.isEmpty() && ingredient.test(stack)) {
					return SettlementPersonalCrafting.canCraft(villager.getInventory(), ingredient, stack, output);
				}
			}
		}
		return SettlementSimulationService.craftingResourceCount(settlementData(), settlement, abstractInput) > 0
			&& SettlementPersonalCrafting.canCraft(villager.getInventory(), ingredient, new ItemStack(abstractInput), output);
	}

	private boolean hasStonePickaxe() {
		ItemStack hand = villager.getItemInHand(InteractionHand.MAIN_HAND);
		if (SettlementWorkbenchCargo.isMarked(hand) || SettlementShearingCargo.isMarked(hand) || SettlementShearsLoan.isMarked(hand)) return false;
		if (hand.is(Items.STONE_PICKAXE)) {
			return true;
		}
		if (!hand.isEmpty()) {
			return false;
		}
		for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
			ItemStack stack = villager.getInventory().getItem(slot);
			if (stack.is(Items.STONE_PICKAXE) && !SettlementWorkbenchCargo.isMarked(stack) && !SettlementShearingCargo.isMarked(stack) && !SettlementShearsLoan.isMarked(stack)) {
				villager.setItemInHand(InteractionHand.MAIN_HAND, stack);
				villager.getInventory().setItem(slot, ItemStack.EMPTY);
				return true;
			}
		}
		return false;
	}

	private Optional<Settlement> findWorkSettlement() {
		if (!(villager.level() instanceof ServerLevel level)) {
			return Optional.empty();
		}
		SettlementSavedData data = SettlementSavedData.get(level);
		return data.findSettlementForVillager(villager.getUUID())
			.filter(camp -> data.getStockpile(camp).isPresent() || ConstructionSupply.hasAny(villager.getInventory())
				|| (data.getConstructionJob(camp).isPresent()
					&& ConstructionSystem.nextBuildTarget(level, data, camp, villager)
						.filter(next -> !next.instruction().consume()).isPresent()));
	}

	private SettlementSavedData settlementData() {
		return SettlementSavedData.get((ServerLevel) villager.level());
	}

	/** Keep legacy custom-item semantics, but never borrow a workbench custody marker. */
	static int countCraftingItems(Container inventory, Item item) {
		int count = 0;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.is(item) && !SettlementWorkbenchCargo.isMarked(stack) && !SettlementShearingCargo.isMarked(stack) && !SettlementShearsLoan.isMarked(stack)) count += stack.getCount();
		}
		return count;
	}

	static int transferCraftingItems(Container source, Container target, Item item, int count) {
		if (source == target || count <= 0) return 0;
		int transferred = 0;
		for (int slot = 0; slot < source.getContainerSize() && transferred < count; slot++) {
			ItemStack stack = source.getItem(slot);
			if (stack.isEmpty() || !stack.is(item) || SettlementWorkbenchCargo.isMarked(stack) || SettlementShearingCargo.isMarked(stack) || SettlementShearsLoan.isMarked(stack)) continue;
			ItemStack offered = stack.copy();
			offered.setCount(Math.min(stack.getCount(), count - transferred));
			int inserted = SettlementStockpile.insert(target, offered);
			if (inserted == 0) continue;
			stack.shrink(inserted);
			source.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
			transferred += inserted;
		}
		if (transferred > 0) source.setChanged();
		return transferred;
	}

	static int removeItems(Container inventory, Item item, int count) {
		int remaining = count;
		for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.is(item) || SettlementWorkbenchCargo.isMarked(stack) || SettlementShearingCargo.isMarked(stack) || SettlementShearsLoan.isMarked(stack)) {
				continue;
			}
			ItemStack removed = inventory.removeItem(slot, remaining);
			remaining -= removed.getCount();
		}
		return count - remaining;
	}

	private enum Action {
		RETURN_TO_STOCKPILE("return_to_stockpile"),
		COLLECT_BUILD_SUPPLY("collect_build_supply"),
		RETURN_BUILD_SUPPLY("return_build_supply"),
		GATHER("gather"),
		FORAGE("forage"),
		HARVEST_CROP("harvest_crop"),
		BORROW_SHEARS("borrow_shears"),
		RETURN_SHEARS("return_shears"),
		SHEAR_SHEEP("shear_sheep"),
		CRAFT_PICKAXE("craft"),
		CRAFT_STICKS("craft"),
		CRAFT_MATERIALS("craft_materials"),
		COLLECT_PLANK_MATERIALS("collect_plank_materials"),
		WORKBENCH_PLANKS("workbench_planks"),
		COLLECT_DOOR_MATERIALS("collect_door_materials"),
		WORKBENCH_DOORS("workbench_doors"),
		COLLECT_BED_MATERIALS("collect_bed_materials"),
		WORKBENCH_BEDS("workbench_beds"),
		COLLECT_WOOL_MATERIALS("collect_wool_materials"),
		WORKBENCH_WOOL("workbench_wool"),
		COLLECT_TORCH_MATERIALS("collect_torch_materials"),
		WORKBENCH_TORCHES("workbench_torches"),
		CRAFT_FIRST_WORKBENCH("craft_first_workbench"),
		BUILD("build"),
		POLISH_PICKAXE("polish"),
		GRIND_SMOOTH_STONE("grind"),
		ATTEND_LESSON("attend_lesson"),
		ATTEND_MEETING("attend_meeting"),
		EAT("eat");

		private final String id;

		Action(String id) {
			this.id = id;
		}

		String id() {
			return id;
		}
	}
}
