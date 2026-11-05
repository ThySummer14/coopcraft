package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

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
	private static final double BUILD_INTERACTION_DISTANCE_SQUARED = 36.0;
	private static final int HAND_GATHER_TICKS = 60;
	private static final int TOOL_GATHER_TICKS = 30;
	private static final int CRAFT_TICKS = 60;
	private static final int BUILD_TICKS = 20;
	private static final int EAT_TICKS = 40;
	private static final int FORAGE_TICKS = 40;
	private static final long FORAGE_RETRY_TICKS = 200;
	private static final int FOOD_RESERVE_POINTS_PER_MEMBER = 8;

	private final Villager villager;
	private Action action;
	private BlockPos target;
	private BlockPos forageTarget;
	private ConstructionSystem.BuildTarget currentBuildTarget;
	private Settlement settlement;
	private WorkReservation workReservation;
	private WorkRole role = WorkRole.GENERALIST;
	private int progress;
	private int searchCooldown;
	private int travelTicks;
	private int approachIndex;
	private long forageCooldownUntil;

	public VillagerWorkGoal(Villager villager) {
		this.villager = villager;
		setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		Optional<Settlement> workSettlement = findWorkSettlement();
		if (workSettlement.isEmpty()) {
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
			pauseWorkReservation("personal_hunger");
			action = Action.EAT;
			target = settlementData().getStockpile(settlement).orElse(null);
			progress = 0;
			return target != null;
		}
		if (villager.isBaby()) {
			pauseWorkReservation("not_available");
			action = null;
			target = null;
			return false;
		}
		Optional<CareAssignment> care = activeCare();
		if (care.isPresent()) {
			pauseWorkReservation("care_commitment");
			action = Action.CARE;
			target = careDependent(care.get()).blockPosition();
			progress = 0;
			return true;
		}
		if (action == Action.BUILD && starvingWithoutFood()) {
			pauseWorkReservation("personal_hunger");
			action = null;
			target = null;
			currentBuildTarget = null;
		}
		ServerLevel level = (ServerLevel) villager.level();
		Optional<WorkReservation> offered = SettlementCoordination.availableFor(
			level, settlementData(), settlement, villager.getUUID()
		);
		if (offered.filter(reservation -> reservation.task() == WorkReservation.Task.BUILD).isPresent()
			&& starvingWithoutFood()) {
			pauseWorkReservation("personal_hunger");
			SettlementCoordination.pause(level, settlementData(), settlement, villager.getUUID(), "personal_hunger");
			offered = Optional.empty();
		}
		if (offered.filter(reservation -> reservation.task() == WorkReservation.Task.FORAGE).isPresent()
			&& villager.level().getGameTime() < forageCooldownUntil) {
			offered = Optional.empty();
		}
		if (workReservation != null && (action == Action.BUILD || action == Action.FORAGE)
			&& (offered.isEmpty() || !offered.get().id().equals(workReservation.id()))) {
			action = null;
			target = null;
			forageTarget = null;
			currentBuildTarget = null;
			workReservation = null;
		}
		if (offered.isPresent() && (action == null || action == Action.BUILD || action == Action.FORAGE)) {
			WorkReservation candidate = offered.get();
			if (candidate.task() == WorkReservation.Task.FORAGE && (action != Action.FORAGE || workReservation == null)) {
				BlockPos bush = findFoodSource();
				if (bush != null) {
					action = Action.FORAGE;
					forageTarget = bush;
					target = bush;
					workReservation = SettlementCoordination.begin(level, settlementData(), settlement, villager.getUUID());
					progress = 0;
					return true;
				}
				forageCooldownUntil = villager.level().getGameTime() + FORAGE_RETRY_TICKS;
				SettlementCoordination.pause(level, settlementData(), settlement, villager.getUUID(), "forage_unavailable");
			} else if (candidate.task() == WorkReservation.Task.BUILD && (action != Action.BUILD || workReservation == null)) {
				Optional<ConstructionSystem.BuildTarget> buildTarget = ConstructionSystem.nextBuildTarget(
					level, settlementData(), settlement
				);
				if (buildTarget.isPresent()) {
					currentBuildTarget = buildTarget.get();
					action = Action.BUILD;
					target = currentBuildTarget.position();
					workReservation = SettlementCoordination.begin(level, settlementData(), settlement, villager.getUUID());
					progress = 0;
					return true;
				}
				SettlementCoordination.pause(level, settlementData(), settlement, villager.getUUID(), "construction_blocked");
			}
		}
		if (settlementData().getStockpile(settlement).isEmpty()) {
			action = null;
			target = null;
			return false;
		}
		if (searchCooldown > 0) {
			searchCooldown--;
			return false;
		}
		if (shouldForage()) {
			BlockPos bush = findFoodSource();
			if (bush != null) {
				action = Action.FORAGE;
				forageTarget = bush;
				target = bush;
				progress = 0;
				return true;
			}
			forageCooldownUntil = villager.level().getGameTime() + FORAGE_RETRY_TICKS;
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
		if (action == null || findWorkSettlement().isEmpty()) {
			return false;
		}
		if (action != Action.EAT && shouldEat() || action == Action.BUILD && starvingWithoutFood()) {
			return false;
		}
		if (workReservation != null && (action == Action.BUILD || action == Action.FORAGE)
			&& SettlementCoordination.availableFor(
				(ServerLevel) villager.level(), settlementData(), settlement, villager.getUUID()
			).filter(reservation -> reservation.id().equals(workReservation.id())).isEmpty()) {
			return false;
		}
		return (action != Action.CARE || !shouldEat()) && isTargetValid();
	}

	@Override
	public void start() {
		progress = 0;
		travelTicks = 0;
		if (workReservation != null) {
			workReservation = SettlementCoordination.begin(
				(ServerLevel) villager.level(), settlementData(), settlement, villager.getUUID()
			);
		}
		navigateToTarget();
	}

	@Override
	public void stop() {
		if (workReservation != null && settlement != null) {
			if (action == Action.BUILD && starvingWithoutFood()) {
				SettlementCoordination.pause(
					(ServerLevel) villager.level(), settlementData(), settlement, villager.getUUID(), "personal_hunger"
				);
			} else {
				SettlementCoordination.rest((ServerLevel) villager.level(), settlementData(), settlement, villager.getUUID());
			}
		}
		workReservation = null;
		action = null;
		target = null;
		forageTarget = null;
		currentBuildTarget = null;
		settlement = null;
		progress = 0;
		searchCooldown = 0;
		travelTicks = 0;
		villager.getNavigation().stop();
	}

	String debugState() {
		boolean running = ((com.thysummer.coopcraft.mixin.MobAccessor) villager).getGoalSelector().getAvailableGoals().stream()
			.anyMatch(goal -> goal.getGoal() == this && goal.isRunning());
		String reservation = findWorkSettlement()
			.flatMap(current -> settlementData().simulationOf(current))
			.flatMap(state -> state.coordinationPlan().reservationFor(villager.getUUID()))
			.map(Object::toString).orElse("none");
		return "reservation=" + reservation + ", action=" + action + ", target=" + target + ", progress=" + progress + ", running=" + running
			+ ", canContinue=" + canContinueToUse() + ", alive=" + villager.isAlive() + ", villagerTick=" + villager.tickCount
			+ ", gameTime=" + villager.level().getGameTime()
			+ ", entityTicking=" + ((ServerLevel) villager.level()).isPositionEntityTicking(villager.blockPosition())
			+ ", travel=" + travelTicks + ", approach=" + approachIndex
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
		if (action != null) {
			if (action == Action.EAT && !hasFoodAvailable()) {
				return new WorkStatus("idle", null, "hungry_no_food");
			}
			return new WorkStatus(action.id(), target, travelTicks >= 100 ? "path_blocked" : null);
		}
		if (!(villager.level() instanceof ServerLevel level)) {
			return new WorkStatus("idle", null, "unknown");
		}
		SettlementSavedData data = SettlementSavedData.get(level);
		Optional<Settlement> workSettlement = data.findSettlementForVillager(villager.getUUID());
		if (workSettlement.isEmpty()) {
			return new WorkStatus("idle", null, "no_stockpile");
		}
		Settlement current = workSettlement.get();
		Optional<CareAssignment> receivingCare = SettlementCareService.activeForDependent(
			level, data, current, villager.getUUID()
		);
		if (receivingCare.isPresent()
			&& level.getEntity(receivingCare.get().caregiverId()) instanceof Villager caregiver) {
			return new WorkStatus("receiving_care", caregiver.blockPosition(), null);
		}
		if (villager.isBaby()) {
			return new WorkStatus("idle", null, "child");
		}
		Optional<String> paused = SettlementCoordination.pausedCause(level, data, current, villager.getUUID());
		if (paused.isPresent()) {
			BlockPos project = data.getConstructionJob(current).map(ConstructionJob::origin).orElse(null);
			return new WorkStatus("idle", project, paused.get());
		}
		if (data.getStockpile(current).isEmpty()) {
			return new WorkStatus("idle", null, "no_stockpile");
		}
		int satiety = data.needsOf(current, villager.getUUID()).satiety();
		boolean foodAvailable = SettlementSimulationService.isSurvivalEnabled(data, current)
			? SettlementSimulationService.hasFoodFor(data, current, villager.getUUID())
			: SettlementNeeds.hasFood(villager.getInventory(), SettlementStockpile.getStockpile(level, data, current));
		if (satiety <= SettlementNeeds.HUNGRY_AT && !foodAvailable) {
			return new WorkStatus("idle", null, "hungry_no_food");
		}
		if (data.getConstructionJob(current).isPresent()) {
			return new WorkStatus("idle", null, "construction_blocked");
		}
		if (data.resourcesOf(current).isEmpty()) {
			return new WorkStatus("idle", null, "no_resources");
		}
		return new WorkStatus("idle", null, null);
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
		if (action == Action.CARE) {
			Optional<CareAssignment> care = activeCare();
			if (care.isEmpty()) {
				stop();
				return;
			}
			target = careDependent(care.get()).blockPosition();
			if (navigateToTarget() && SettlementCareService.progressCare(
				(ServerLevel) villager.level(), settlementData(), settlement, villager.getUUID()
			)) {
				stop();
			}
			return;
		}
		if (!navigateToTarget()) {
			return;
		}
		progress++;
		int baseDuration = switch (action) {
			case GATHER -> hasStonePickaxe() ? TOOL_GATHER_TICKS : HAND_GATHER_TICKS;
			case FORAGE -> FORAGE_TICKS;
			case BUILD -> BUILD_TICKS;
			case EAT -> EAT_TICKS;
			default -> CRAFT_TICKS;
		};
		var workType = switch (action) {
			case GATHER -> VillagerPersonality.WorkType.GATHER;
			case FORAGE -> VillagerPersonality.WorkType.GATHER;
			case BUILD -> VillagerPersonality.WorkType.BUILD;
			case EAT -> VillagerPersonality.WorkType.GENERAL;
			default -> VillagerPersonality.WorkType.CRAFT;
		};
		int duration = settlementData().personalityOf(settlement, villager.getUUID()).workDuration(baseDuration, workType);
		if (action != Action.EAT && settlementData().needsOf(settlement, villager.getUUID()).satiety() < 20) {
			duration = duration * 5 / 4;
		}
		if (action == Action.GATHER) {
			duration = SettlementSimulationService.gatherWorkDuration(settlementData(), settlement, villager.getUUID(), duration);
		} else if (action != Action.BUILD && action != Action.EAT) {
			duration = SettlementSimulationService.craftingWorkDuration(settlementData(), settlement, villager.getUUID(), duration);
		}
		if (progress < duration) {
			return;
		}

		switch (action) {
			case GATHER -> gather();
			case FORAGE -> forage();
			case CRAFT_PICKAXE -> craftPickaxe();
			case CRAFT_STICKS -> craftSticks();
			case BUILD -> {
				if (ConstructionSystem.buildNext(
					(ServerLevel) villager.level(), settlementData(), settlement, currentBuildTarget, villager
				) && settlementData().getConstructionJob(settlement).isEmpty()) {
					SettlementCoordination.complete(
						(ServerLevel) villager.level(), settlementData(), settlement, villager.getUUID()
					);
				}
			}
			case POLISH_PICKAXE -> craftPolishedPickaxe();
			case GRIND_SMOOTH_STONE -> grindSmoothStone();
			case EAT -> SettlementNeeds.eat((ServerLevel) villager.level(), settlementData(), settlement, villager);
		}
		if (action == Action.EAT && isTargetValid()) {
			progress = 0;
			return;
		}
		stop();
	}

	private Action chooseAction() {
		if (shouldEat()) {
			return Action.EAT;
		}
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
		BlockPos gatherTarget = chooseGatherTarget();
		return gatherTarget == null ? null : Action.GATHER;
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
		if (action == Action.FORAGE) {
			return forageTarget;
		}
		if (action == Action.EAT) {
			return settlementData().getStockpile(settlement).orElse(null);
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
		if (action == Action.BUILD && !constructionPathIsTicking()) {
			villager.getNavigation().stop();
			approachIndex++;
			travelTicks = 0;
		}
		if (action == Action.BUILD && villager.getBoundingBox().intersects(new AABB(target))) {
			travelTicks++;
			if (villager.getNavigation().isDone() || travelTicks >= 20) {
				if (travelTicks >= 20) {
					villager.getNavigation().stop();
					approachIndex++;
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
			approachIndex = 0;
			return true;
		}
		travelTicks++;
		if (travelTicks >= 100) {
			villager.getNavigation().stop();
			approachIndex++;
			travelTicks = 0;
		}
		if (villager.getNavigation().isDone()) {
			boolean moving = action == Action.BUILD ? navigateToConstructionApproach()
				: action == Action.FORAGE ? navigateToForageApproach() : navigateTo(target.above());
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
		List<BlockPos> candidates = new ArrayList<>();
		for (int vertical : new int[] {0, 1, -1, -2}) {
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos candidate = target.relative(direction).above(vertical);
				if (level.isPositionEntityTicking(candidate)
					&& level.getBlockState(candidate).getCollisionShape(level, candidate).isEmpty()
					&& level.getBlockState(candidate.above()).getCollisionShape(level, candidate.above()).isEmpty()
					&& level.getBlockState(candidate.below()).isSolidRender(level, candidate.below())) {
					candidates.add(candidate);
				}
			}
		}
		candidates.sort(Comparator.comparingDouble(pos -> pos.distSqr(villager.blockPosition())));
		int start = candidates.isEmpty() ? 0 : Math.floorMod(approachIndex, candidates.size());
		for (int i = 0; i < candidates.size(); i++) {
			if (navigateTo(candidates.get((start + i) % candidates.size()))) {
				return true;
			}
		}
		return navigateTo(target.above());
	}

	private boolean navigateTo(BlockPos pos) {
		double speed = settlementData().personalityOf(settlement, villager.getUUID()).movementSpeed();
		boolean moving = villager.getNavigation().moveTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, speed);
		if (!moving || action != Action.BUILD) {
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
			if (!level.isPositionEntityTicking(path.getNodePos(index))) {
				return false;
			}
		}
		return true;
	}

	private boolean isTargetValid() {
		if (target == null || action == null || settlement == null || !(villager.level() instanceof ServerLevel level)) {
			return false;
		}
		if (action == Action.EAT) {
			return settlementData().getStockpile(settlement).filter(target::equals).isPresent()
				&& settlementData().needsOf(settlement, villager.getUUID()).satiety() < SettlementNeeds.FULL_AT
				&& hasFoodAvailable();
		}
		if (action == Action.CARE) {
			return activeCare().isPresent();
		}
		if (action == Action.GATHER) {
			return isGatherable(target);
		}
		if (action == Action.FORAGE) {
			BlockState state = level.getBlockState(target);
			if (SettlementForaging.isRipe(state)) {
				return true;
			}
			if (!SettlementFarming.canFarm(level, settlementData(), settlement)) {
				return false;
			}
			return SettlementFarming.isMature(state)
				|| availableItems(Items.POTATO) > 0
				&& SettlementFarming.isPlantingSite(state, level.getBlockState(target.above()));
		}
		if (action == Action.BUILD) {
			return currentBuildTarget != null && ConstructionSystem.nextBuildTarget(level, settlementData(), settlement)
				.filter(next -> next.job().equals(currentBuildTarget.job()) && next.position().equals(target))
				.isPresent();
		}
		Block station = action == Action.POLISH_PICKAXE || action == Action.GRIND_SMOOTH_STONE
			? Blocks.STONECUTTER
			: Blocks.CRAFTING_TABLE;
		return villager.level().getBlockState(target).is(station);
	}

	private boolean shouldEat() {
		return settlementData().needsOf(settlement, villager.getUUID()).satiety() <= SettlementNeeds.HUNGRY_AT
			&& hasFoodAvailable();
	}

	private boolean shouldForage() {
		if (!SettlementForaging.canForage(settlementData(), settlement)) {
			return false;
		}
		boolean missingFood = starvingWithoutFood();
		boolean reserveLow = role == WorkRole.FORAGER && foodReserveLow();
		return SettlementForaging.needsForage(
			missingFood || reserveLow, villager.level().getGameTime(), forageCooldownUntil
		);
	}

	private boolean foodReserveLow() {
		Container stockpile = SettlementStockpile.getStockpile(
			(ServerLevel) villager.level(), settlementData(), settlement
		);
		return SettlementStockpile.communalFoodPoints(stockpile)
			< settlementData().registeredCount(settlement) * FOOD_RESERVE_POINTS_PER_MEMBER;
	}

	private BlockPos findFoodSource() {
		if (!(villager.level() instanceof ServerLevel level)) {
			return null;
		}
		boolean canFarm = SettlementFarming.canFarm(level, settlementData(), settlement);
		if (canFarm) {
			Optional<BlockPos> crop = SettlementFarming.findMatureCrop(level, villager.blockPosition());
			if (crop.isPresent()) {
				return crop.get();
			}
		}
		BlockPos bush = SettlementForaging.findRipeBush(level, villager.blockPosition()).orElse(null);
		if (bush != null) {
			return bush;
		}
		if (canFarm && availableItems(Items.POTATO) > 0) {
			return SettlementFarming.findPlantingSite(level, settlementData(), settlement, villager.blockPosition()).orElse(null);
		}
		return null;
	}

	private void forage() {
		if (target == null || !(villager.level() instanceof ServerLevel level)) {
			return;
		}
		BlockState state = level.getBlockState(target);
		if (SettlementFarming.isMature(state)) {
			SettlementFarming.harvest(level, settlementData(), settlement, villager, target);
		} else if (SettlementForaging.isRipe(state)) {
			SettlementForaging.harvest(level, settlementData(), settlement, villager, target);
		} else if (availableItems(Items.POTATO) > 0 && withdrawItems(Items.POTATO, 1)
			&& removeItems(villager.getInventory(), Items.POTATO, 1) == 1
			&& !SettlementFarming.plantPotato(level, settlementData(), settlement, villager, target)) {
			SettlementStockpile.insert(villager.getInventory(), new ItemStack(Items.POTATO));
		}
	}

	private boolean hasFoodAvailable() {
		if (SettlementSimulationService.isSurvivalEnabled(settlementData(), settlement)) {
			return SettlementSimulationService.hasFoodFor(settlementData(), settlement, villager.getUUID());
		}
		Container stockpile = SettlementStockpile.getStockpile((ServerLevel) villager.level(), settlementData(), settlement);
		return SettlementNeeds.hasFood(villager.getInventory(), stockpile);
	}

	private boolean isGatherable(BlockPos pos) {
		BlockState state = villager.level().getBlockState(pos);
		return state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE) || state.is(BlockTags.LOGS);
	}

	private void gather() {
		if (!isGatherable(target)) {
			settlementData().removeResource(settlement, target);
			return;
		}
		ServerLevel level = (ServerLevel) villager.level();
		BlockState state = level.getBlockState(target);
		List<ItemStack> drops = Block.getDrops(state, level, target, level.getBlockEntity(target));
		for (ItemStack drop : drops) {
			if (!villager.getInventory().canAddItem(drop)) {
				return;
			}
		}

		level.destroyBlock(target, false, villager);
		for (ItemStack drop : drops) {
			int produced = drop.getCount();
			ItemStack remainder = villager.getInventory().addItem(drop);
			if (!remainder.isEmpty()) {
				villager.spawnAtLocation(remainder);
			}
			int inserted = produced - remainder.getCount();
			if (inserted > 0) {
				SettlementSimulationService.recordProduction(villager, settlementData(), settlement, drop.getItem(), inserted);
			}
		}
		settlementData().removeResource(settlement, target);
	}

	private void craftPickaxe() {
		if (!canCraftPickaxe() || !withdrawItems(Items.COBBLESTONE, 3) || !withdrawItems(Items.STICK, 2)) {
			return;
		}
		if (removeItems(villager.getInventory(), Items.COBBLESTONE, 3) != 3
			|| removeItems(villager.getInventory(), Items.STICK, 2) != 2) {
			return;
		}
		villager.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE_PICKAXE));
		SettlementSimulationService.recordCrafting(villager, settlementData(), settlement, "stone_pickaxe");
	}

	private void craftSticks() {
		if (!canCraftSticks() || !withdrawOneLog() || !removeOneLog(villager.getInventory())) {
			return;
		}
		int inserted = SettlementStockpile.insert(villager.getInventory(), new ItemStack(Items.STICK, 8));
		if (inserted < 8) {
			villager.spawnAtLocation(new ItemStack(Items.STICK, 8 - inserted));
		}
		SettlementSimulationService.recordCrafting(villager, settlementData(), settlement, "sticks");
		SettlementSimulationService.recordCraftedProduct(
			villager, settlementData(), settlement, "sticks", Items.STICK, inserted
		);
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
			&& hasLogs()
			&& villager.getInventory().canAddItem(new ItemStack(Items.STICK, 8));
	}

	private boolean canPolishPickaxe() {
		return hasGrindingStation()
			&& hasStonePickaxe()
			&& !isPolishedPickaxe(villager.getItemInHand(InteractionHand.MAIN_HAND))
			&& availableItems(Items.SMOOTH_STONE) >= 3
			&& availableItems(Items.STICK) >= 2;
	}

	private boolean canGrindSmoothStone() {
		return hasGrindingStation()
			&& hasStonePickaxe()
			&& !isPolishedPickaxe(villager.getItemInHand(InteractionHand.MAIN_HAND))
			&& availableItems(Items.SMOOTH_STONE) < 3
			&& availableItems(Items.COBBLESTONE) >= 1
			&& villager.getInventory().canAddItem(new ItemStack(Items.SMOOTH_STONE));
	}

	private void craftPolishedPickaxe() {
		if (!canPolishPickaxe() || !withdrawItems(Items.SMOOTH_STONE, 3) || !withdrawItems(Items.STICK, 2)) {
			return;
		}
		removeItems(villager.getInventory(), Items.SMOOTH_STONE, 3);
		removeItems(villager.getInventory(), Items.STICK, 2);
		ItemStack polished = new ItemStack(Items.STONE_PICKAXE);
		var enchantments = villager.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT);
		polished.enchant(enchantments.getHolderOrThrow(Enchantments.EFFICIENCY), 1);
		polished.enchant(enchantments.getHolderOrThrow(Enchantments.UNBREAKING), 1);
		villager.setItemInHand(InteractionHand.MAIN_HAND, polished);
		SettlementSimulationService.recordCrafting(villager, settlementData(), settlement, "polished_stone_pickaxe");
	}

	private void grindSmoothStone() {
		if (!canGrindSmoothStone() || !withdrawItems(Items.COBBLESTONE, 1)
			|| removeItems(villager.getInventory(), Items.COBBLESTONE, 1) != 1) {
			return;
		}
		int inserted = SettlementStockpile.insert(villager.getInventory(), new ItemStack(Items.SMOOTH_STONE));
		if (inserted < 1) {
			villager.spawnAtLocation(new ItemStack(Items.SMOOTH_STONE));
		}
		SettlementSimulationService.recordCrafting(villager, settlementData(), settlement, "smooth_stone");
		SettlementSimulationService.recordCraftedProduct(
			villager, settlementData(), settlement, "smooth_stone", Items.SMOOTH_STONE, inserted
		);
	}

	private String craftingRecipe() {
		return switch (action) {
			case CRAFT_PICKAXE -> "stone_pickaxe";
			case CRAFT_STICKS -> "sticks";
			case POLISH_PICKAXE -> "polished_stone_pickaxe";
			case GRIND_SMOOTH_STONE -> "smooth_stone";
			case FORAGE -> "plant_potato";
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
			int removed = removeItems(stockpile, item, Math.min(missing, realAvailable));
			int inserted = SettlementStockpile.insert(villager.getInventory(), new ItemStack(item, removed));
			if (inserted < removed) {
				SettlementStockpile.insert(stockpile, new ItemStack(item, removed - inserted));
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
			.filter(pos -> settlement.center().distSqr(pos) <= SEARCH_RADIUS_SQUARED)
			.filter(pos -> villager.level().getBlockState(pos).is(block))
			.isPresent();
	}

	private boolean needsSticks() {
		return !hasStonePickaxe() && availableItems(Items.STICK) < 2;
	}

	private boolean needsCobblestone() {
		return !hasStonePickaxe() && availableItems(Items.COBBLESTONE) < 3;
	}

	private boolean hasLogs() {
		if (villager.getInventory().hasAnyMatching(stack -> stack.is(ItemTags.LOGS))) {
			return true;
		}
		Container stockpile = SettlementStockpile.getStockpile((ServerLevel) villager.level(), settlementData(), settlement);
		if (stockpile != null && stockpile.hasAnyMatching(stack -> stack.is(ItemTags.LOGS))) {
			return true;
		}
		return SettlementSimulationService.craftingResourceCount(settlementData(), settlement, Items.OAK_LOG) > 0;
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
		return SettlementSavedData.get(level).findSettlementForVillager(villager.getUUID());
	}

	private void pauseWorkReservation(String cause) {
		if (workReservation != null && settlement != null && villager.level() instanceof ServerLevel level) {
			SettlementCoordination.pause(level, settlementData(), settlement, villager.getUUID(), cause);
		}
		workReservation = null;
	}

	private boolean starvingWithoutFood() {
		return settlementData().needsOf(settlement, villager.getUUID()).satiety() <= SettlementNeeds.HUNGRY_AT
			&& !hasFoodAvailable();
	}

	private Optional<CareAssignment> activeCare() {
		if (settlement == null || villager.isBaby() || !(villager.level() instanceof ServerLevel level)) {
			return Optional.empty();
		}
		return SettlementCareService.activeForCaregiver(level, settlementData(), settlement, villager.getUUID());
	}

	private Villager careDependent(CareAssignment assignment) {
		return (Villager) ((ServerLevel) villager.level()).getEntity(assignment.dependentId());
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

	private static boolean removeOneLog(Container inventory) {
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.is(ItemTags.LOGS) && !inventory.removeItem(slot, 1).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private enum Action {
		GATHER("gather"),
		FORAGE("forage"),
		CRAFT_PICKAXE("craft"),
		CRAFT_STICKS("craft"),
		BUILD("build"),
		POLISH_PICKAXE("polish"),
		GRIND_SMOOTH_STONE("grind"),
		EAT("eat"),
		CARE("care");

		private final String id;

		Action(String id) {
			this.id = id;
		}

		String id() {
			return id;
		}
	}
}
