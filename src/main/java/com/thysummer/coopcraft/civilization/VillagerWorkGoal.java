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

	private final Villager villager;
	private Action action;
	private BlockPos target;
	private BlockPos forageTarget;
	private ConstructionSystem.BuildTarget currentBuildTarget;
	private SettlementMaterialCrafting.Recipe currentMaterialRecipe;
	private SettlementStarterCrafting.Ticket currentStarterCraft;
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

	public VillagerWorkGoal(Villager villager) {
		this.villager = villager;
		setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
	}

	@Override
	public boolean canUse() {
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
		if (searchCooldown > 0) {
			searchCooldown--;
			return false;
		}
		if (shouldForage() && (!carrying || emergencyForage())) {
			BlockPos bush = findRipeBerryBush();
			if (bush != null) {
				action = SettlementCropHarvest.isRipe(villager.level().getBlockState(bush)) ? Action.HARVEST_CROP : Action.FORAGE;
				forageTarget = bush;
				target = bush;
				progress = 0;
				return true;
			}
			forageCooldownUntil = villager.level().getGameTime() + FORAGE_RETRY_TICKS;
		}
		if (carrying && cargoCooldown.ready(villager.level().getGameTime())) {
			if (prepareCargoReturn()) return true;
		}
		if (action != Action.BUILD) {
			Optional<ConstructionSystem.BuildTarget> buildTarget = ConstructionSystem.nextBuildTarget(
				(ServerLevel) villager.level(), settlementData(), settlement
			);
			if (buildTarget.isPresent()) {
				currentBuildTarget = buildTarget.get();
				action = Action.BUILD;
				target = currentBuildTarget.position();
				progress = 0;
				return true;
			}
		}
		if (action != null && isTargetValid()) {
			return true;
		}

		action = chooseAction();
		searchCooldown = SEARCH_INTERVAL;
		target = chooseTarget(action);
		progress = 0;
		return action != null;
	}

	@Override
	public boolean canContinueToUse() {
		refreshMeetingInvitation();
		if (action != null && action != Action.EAT && action != Action.ATTEND_MEETING
			&& action != Action.RETURN_TO_STOCKPILE && !villager.isBaby()
			&& settlement != null && hasReturnCargo()
			&& cargoCooldown.ready(villager.level().getGameTime()) && !emergencyForage()) return false;
		return action != null && isTargetValid() && findWorkSettlement().isPresent();
	}

	@Override
	public void start() {
		progress = 0;
		travelTicks = 0;
		if (action == Action.ATTEND_MEETING) { villager.getNavigation().stop(); tickMeeting(); }
		else if (action == Action.RETURN_TO_STOCKPILE) { villager.getNavigation().stop(); tickCargoReturn(); }
		else navigateToTarget();
	}

	@Override
	public void stop() {
		if (SettlementMeetingInvitations.cancelOnStop(action == Action.ATTEND_MEETING)) cancelMeetingInvitation();
		if (foodTicket != null) foodTicket.cancel();
		foodTicket = null;
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
		if (data.resourcesOf(current).isEmpty()) {
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
		refreshMeetingInvitation();
		if (action == Action.ATTEND_MEETING) { tickMeeting(); return; }
		if (action == null) return;
		if (action == Action.RETURN_TO_STOCKPILE) { tickCargoReturn(); return; }
		if (action == Action.CRAFT_FIRST_WORKBENCH && !isTargetValid()) { stop(); return; }
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
			case ATTEND_MEETING, RETURN_TO_STOCKPILE -> { return; }
			case EAT -> SettlementNeeds.eat((ServerLevel) villager.level(), settlementData(), settlement, villager);
		}
		if (action == Action.EAT && isTargetValid()) {
			progress = 0;
			return;
		}
		stop();
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
		if (shouldEat()) {
			return Action.EAT;
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
		if (currentMaterialRecipe != null) {
			return Action.CRAFT_MATERIALS;
		}
		if (hasReturnCargo()) return null;
		BlockPos gatherTarget = chooseGatherTarget();
		return gatherTarget == null ? null : Action.GATHER;
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
		if (currentMaterialRecipe == null || chooseMaterialRecipe() != currentMaterialRecipe) {
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
		if (action == Action.GATHER) {
			return chooseGatherTarget();
		}
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
		if (needsConstructionLogs()) {
			BlockPos oak = settlementData().resourcesOf(settlement).stream()
				.filter(pos -> villager.distanceToSqr(pos.getCenter()) <= SEARCH_RADIUS_SQUARED)
				.filter(pos -> SettlementGathering.validTarget((ServerLevel) villager.level(), settlementData(), settlement, villager, pos))
				.filter(pos -> villager.level().getBlockState(pos).is(Blocks.OAK_LOG))
				.min(Comparator.comparingDouble(pos -> pos.distSqr(villager.blockPosition())))
				.orElse(null);
			if (oak != null) {
				return oak;
			}
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
		return settlementData().resourcesOf(settlement).stream()
			.filter(pos -> villager.distanceToSqr(pos.getCenter()) <= SEARCH_RADIUS_SQUARED)
			.filter(pos -> SettlementGathering.validTarget(level, settlementData(), settlement, villager, pos))
			.filter(pos -> {
				BlockState state = level.getBlockState(pos);
				return logs ? state.is(BlockTags.LOGS) : state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE);
			})
			.min(Comparator.comparingDouble(pos -> pos.distSqr(villager.blockPosition())))
			.orElse(null);
	}

	private boolean navigateToTarget() {
		if (target == null) {
			return false;
		}
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
				: (action == Action.FORAGE || action == Action.HARVEST_CROP) ? navigateToForageApproach() : navigateTo(target.above());
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
			if (level.isPositionEntityTicking(candidate)
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
		if (action == Action.CRAFT_FIRST_WORKBENCH && !navigationSearchRegionLoaded()) return false;
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
		if (action == Action.ATTEND_MEETING) return meetingTicket != null;
		if (action == Action.RETURN_TO_STOCKPILE) return cargoTicket != null && target.equals(cargoTicket.store())
			&& SettlementCargoDelivery.valid(cargoTicket, settlementData(), settlement, villager.getUUID(), villager.getInventory());
		if (action == Action.FORAGE) {
			return SettlementForaging.validTarget(level, settlementData(), settlement, villager, target);
		}
		if (action == Action.GATHER) {
			return !hasReturnCargo() && SettlementGathering.validTarget(level, settlementData(), settlement, villager, target);
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
			return currentBuildTarget != null && ConstructionSystem.nextBuildTarget(level, settlementData(), settlement)
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
		return SettlementCargoDelivery.hasDeliverableFood(data, camp, villager.getUUID(), villager.getInventory())
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
		if (++travelTicks >= SettlementCargoApproach.MAX_TRAVEL_TICKS || !constructionPathIsTicking()) {
			deferCargo("cargo_path_blocked"); stop(); return;
		}
		if (!villager.getNavigation().isDone()) return;
		var outcome = cargoApproach.attempt(target, villager.position(), level.getGameTime(), cargoProbe(level),
			this::navigationSearchRegionLoaded, candidate -> {
				var path = villager.getNavigation().createPath(candidate, 0);
				if (path == null || !path.canReach()) return false;
				for (int i = 0; i < path.getNodeCount(); i++) {
					if (!level.isLoaded(path.getNodePos(i)) || !level.isPositionEntityTicking(path.getNodePos(i))) return false;
				}
				return villager.getNavigation().moveTo(path, settlementData().personalityOf(settlement, villager.getUUID()).movementSpeed());
			});
		if (outcome == SettlementCargoApproach.Outcome.BLOCKED) { deferCargo("cargo_path_blocked"); stop(); }
	}

	private boolean shouldEat() {
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

	private BlockPos findRipeBerryBush() {
		if (!(villager.level() instanceof ServerLevel level)) {
			return null;
		}
		BlockPos origin = villager.blockPosition();
		BlockPos berry = SettlementForaging.findRipeBush(level, origin).orElse(null);
		BlockPos crop = SettlementCropHarvest.findRipe(level, settlementData(), settlement, origin).orElse(null);
		return crop != null && (berry == null || origin.distSqr(crop) < origin.distSqr(berry)) ? crop : berry;
	}

	private void forage() {
		if (target == null || !(villager.level() instanceof ServerLevel level)) {
			return;
		}
		if (action == Action.HARVEST_CROP) {
			SettlementCropHarvest.harvest(level, settlementData(), settlement, villager, target);
		} else {
			SettlementForaging.harvest(level, settlementData(), settlement, villager, target);
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
		if (villager.level() instanceof ServerLevel level) {
			SettlementGathering.harvest(level, settlementData(), settlement, villager, target);
		}
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
			stack -> stack.is(ItemTags.LOGS), new ItemStack(Items.STICK, 8))) {
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
			&& canConvertOne(stack -> stack.is(ItemTags.LOGS), Items.OAK_LOG, new ItemStack(Items.STICK, 8));
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
			&& canConvertOne(stack -> stack.is(Items.COBBLESTONE), Items.COBBLESTONE,
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
			|| !SettlementPersonalCrafting.craft(villager.getInventory(), stack -> stack.is(Items.COBBLESTONE),
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
		return villager.getInventory().countItem(item) + (stockpile == null ? 0 : stockpile.countItem(item))
			+ SettlementSimulationService.craftingResourceCount(settlementData(), settlement, item);
	}

	private boolean withdrawItems(Item item, int count) {
		int missing = count - villager.getInventory().countItem(item);
		if (missing <= 0) {
			return true;
		}
		Container stockpile = SettlementStockpile.getStockpile((ServerLevel) villager.level(), settlementData(), settlement);
		int realAvailable = stockpile == null ? 0 : stockpile.countItem(item);
		long abstractAvailable = SettlementSimulationService.craftingResourceCount(settlementData(), settlement, item);
		if (realAvailable + abstractAvailable < missing) {
			return false;
		}
		if (realAvailable > 0) {
			int requested = Math.min(missing, realAvailable);
			int inserted = SettlementStockpile.transferItem(stockpile, villager.getInventory(), item, requested);
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
		if (villager.getInventory().hasAnyMatching(stack -> stack.is(ItemTags.LOGS))) {
			return true;
		}
		Container stockpile = SettlementStockpile.getStockpile((ServerLevel) villager.level(), settlementData(), settlement);
		if (stockpile != null) {
			for (int slot = 0; slot < stockpile.getContainerSize(); slot++) {
				if (!stockpile.getItem(slot).is(ItemTags.LOGS)) {
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
		if (!stack.is(Items.STONE_PICKAXE)) {
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
		if (hand.is(Items.STONE_PICKAXE)) {
			return true;
		}
		if (!hand.isEmpty()) {
			return false;
		}
		for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
			ItemStack stack = villager.getInventory().getItem(slot);
			if (stack.is(Items.STONE_PICKAXE)) {
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
			.filter(settlement -> data.getStockpile(settlement).isPresent());
	}

	private SettlementSavedData settlementData() {
		return SettlementSavedData.get((ServerLevel) villager.level());
	}

	static int removeItems(Container inventory, Item item, int count) {
		int remaining = count;
		for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.is(item)) {
				continue;
			}
			ItemStack removed = inventory.removeItem(slot, remaining);
			remaining -= removed.getCount();
		}
		return count - remaining;
	}

	private enum Action {
		RETURN_TO_STOCKPILE("return_to_stockpile"),
		GATHER("gather"),
		FORAGE("forage"),
		HARVEST_CROP("harvest_crop"),
		CRAFT_PICKAXE("craft"),
		CRAFT_STICKS("craft"),
		CRAFT_MATERIALS("craft_materials"),
		CRAFT_FIRST_WORKBENCH("craft_first_workbench"),
		BUILD("build"),
		POLISH_PICKAXE("polish"),
		GRIND_SMOOTH_STONE("grind"),
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
