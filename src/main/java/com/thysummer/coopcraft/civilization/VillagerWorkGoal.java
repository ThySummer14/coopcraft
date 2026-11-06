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
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.DyeColor;
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
	private static final int IRON_TOOL_GATHER_TICKS = 20;
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
	private HousingRecipes.Recipe currentHousingRecipe;
	private java.util.UUID sheepId;
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
		if (settlementData().activePlanForWorker(settlement, villager.getUUID())
			.filter(plan -> plan.kind() == SettlementPlan.Kind.BAKE_BREAD).isPresent() && canBakeBread()) {
			action = Action.BAKE_BREAD;
			target = settlementData().getCraftingStation(settlement).orElse(null);
			progress = 0;
			return target != null;
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
		currentHousingRecipe = null;
		sheepId = null;
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
		if (action == Action.SHEAR_SHEEP) {
			Sheep sheep = sheep();
			if (sheep != null) {
				target = sheep.blockPosition();
			}
		}
		if (!navigateToTarget()) {
			return;
		}
		progress++;
		int baseDuration = switch (action) {
			case GATHER -> hasIronPickaxe() ? IRON_TOOL_GATHER_TICKS : hasStonePickaxe() ? TOOL_GATHER_TICKS : HAND_GATHER_TICKS;
			case SHEAR_SHEEP, FORAGE -> FORAGE_TICKS;
			case BUILD -> BUILD_TICKS;
			case EAT -> EAT_TICKS;
			default -> CRAFT_TICKS;
		};
		var workType = switch (action) {
			case GATHER, SHEAR_SHEEP -> VillagerPersonality.WorkType.GATHER;
			case FORAGE -> VillagerPersonality.WorkType.GATHER;
			case BUILD -> VillagerPersonality.WorkType.BUILD;
			case EAT -> VillagerPersonality.WorkType.GENERAL;
			default -> VillagerPersonality.WorkType.CRAFT;
		};
		int duration = settlementData().personalityOf(settlement, villager.getUUID()).workDuration(baseDuration, workType);
		if (action != Action.EAT && settlementData().needsOf(settlement, villager.getUUID()).satiety() < 20) {
			duration = duration * 5 / 4;
		}
		if (action == Action.GATHER || action == Action.SHEAR_SHEEP) {
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
			case BAKE_BREAD -> bakeBread();
			case CRAFT_HOUSING -> craftHousing();
			case CRAFT_SHEARS -> craftShears();
			case SHEAR_SHEEP -> shearSheep();
			case COLLECT_IRON -> collectIron();
			case SMELT_IRON -> smeltIron();
			case CRAFT_IRON_PICKAXE -> craftIronPickaxe();
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
		if (canBakeBread()) {
			return Action.BAKE_BREAD;
		}
		currentHousingRecipe = chooseHousingRecipe();
		if (currentHousingRecipe != null) {
			return Action.CRAFT_HOUSING;
		}
		if (canCollectIron()) {
			return Action.COLLECT_IRON;
		}
		if (canCraftIronPickaxe()) {
			return Action.CRAFT_IRON_PICKAXE;
		}
		if (canCraftShears()) {
			return Action.CRAFT_SHEARS;
		}
		if (canShearSheep()) {
			return Action.SHEAR_SHEEP;
		}
		if (canSmeltIron()) {
			return Action.SMELT_IRON;
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
		if (action == Action.SHEAR_SHEEP) {
			Sheep sheep = sheep();
			return sheep == null ? null : sheep.blockPosition();
		}
		if (action == Action.SMELT_IRON || action == Action.COLLECT_IRON) {
			return settlementData().getSmeltingStation(settlement).orElse(null);
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
			BlockPos ore = chooseOreTarget();
			if (ore != null) {
				return ore;
			}
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
		if (needsPlanks()) {
			BlockPos logs = nearestResource(true);
			if (logs != null) {
				return logs;
			}
		}
		BlockPos ore = chooseOreTarget();
		if (ore != null) {
			return ore;
		}
		if (hasMiningPickaxe()) {
			BlockPos stone = nearestResource(false);
			if (stone != null) {
				return stone;
			}
			return nearestResource(true);
		}
		return null;
	}

	private BlockPos chooseOreTarget() {
		if (!hasMiningPickaxe() || hasIronPickaxe() && !needsShears()) {
			return null;
		}
		if (needsRawIron()) {
			BlockPos iron = nearestOre(true);
			if (iron != null) {
				return iron;
			}
		}
		return needsCoal() ? nearestOre(false) : null;
	}

	private BlockPos nearestOre(boolean iron) {
		if (settlement == null || !(villager.level() instanceof ServerLevel level)) {
			return null;
		}
		return settlementData().resourcesOf(settlement).stream()
			.filter(pos -> villager.distanceToSqr(pos.getCenter()) <= SEARCH_RADIUS_SQUARED)
			.filter(pos -> iron ? SettlementMetallurgy.isIronOre(level.getBlockState(pos))
				: SettlementMetallurgy.isCoalOre(level.getBlockState(pos)))
			.min(Comparator.comparingDouble(pos -> pos.distSqr(villager.blockPosition())))
			.orElse(null);
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
		if (action == Action.SHEAR_SHEEP) {
			return canShearSheep();
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
				|| plantingItem() != null && SettlementFarming.isPlantingSite(state, level.getBlockState(target.above()));
		}
		if (action == Action.BUILD) {
			return currentBuildTarget != null && ConstructionSystem.nextBuildTarget(level, settlementData(), settlement)
				.filter(next -> next.job().equals(currentBuildTarget.job()) && next.position().equals(target))
				.isPresent();
		}
		if (action == Action.SMELT_IRON) {
			return canSmeltIron();
		}
		if (action == Action.COLLECT_IRON) {
			return canCollectIron();
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
		ServerLevel level = (ServerLevel) villager.level();
		boolean canFarm = SettlementFarming.canFarm(level, settlementData(), settlement);
		boolean canHarvest = canFarm && SettlementFarming.findMatureCrop(level, villager.blockPosition()).isPresent();
		boolean canPlant = canFarm && plantingItem() != null;
		return SettlementForaging.needsForage(
			missingFood || reserveLow || canHarvest || canPlant, level.getGameTime(), forageCooldownUntil
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
		if (canFarm && plantingItem() != null) {
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
		} else {
			Item planting = plantingItem();
			if (planting != null && withdrawItems(planting, 1) && removeItems(villager.getInventory(), planting, 1) == 1
				&& !SettlementFarming.plant(level, settlementData(), settlement, villager, target, planting)) {
				SettlementStockpile.insert(villager.getInventory(), new ItemStack(planting));
			}
		}
	}

	private Item plantingItem() {
		for (Item item : List.of(Items.POTATO, Items.WHEAT_SEEDS, Items.CARROT, Items.BEETROOT_SEEDS)) {
			if (availableItems(item) > 0) {
				return item;
			}
		}
		return null;
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
		if (SettlementMetallurgy.isOre(state)) {
			return hasMiningToolInHand();
		}
		return state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE) || state.is(BlockTags.LOGS);
	}

	private void gather() {
		if (!isGatherable(target)) {
			settlementData().removeResource(settlement, target);
			return;
		}
		ServerLevel level = (ServerLevel) villager.level();
		BlockState state = level.getBlockState(target);
		List<ItemStack> drops = SettlementMetallurgy.isOre(state)
			? Block.getDrops(state, level, target, level.getBlockEntity(target), villager, villager.getMainHandItem())
			: Block.getDrops(state, level, target, level.getBlockEntity(target));
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

	private boolean canBakeBread() {
		return settlementData().stageOf(settlement) != SettlementStage.PRIMITIVE
			&& hasCraftingStation()
			&& availableItems(Items.WHEAT) >= 3
			&& villager.getInventory().canAddItem(new ItemStack(Items.BREAD));
	}

	private void bakeBread() {
		if (!canBakeBread() || !withdrawItems(Items.WHEAT, 3)) {
			return;
		}
		if (removeItems(villager.getInventory(), Items.WHEAT, 3) != 3) {
			return;
		}
		int inserted = SettlementStockpile.insert(villager.getInventory(), new ItemStack(Items.BREAD));
		if (inserted < 1) {
			villager.spawnAtLocation(new ItemStack(Items.BREAD));
		}
		SettlementSimulationService.recordCrafting(villager, settlementData(), settlement, "bread");
		SettlementSimulationService.recordCraftedProduct(villager, settlementData(), settlement, "bread", Items.BREAD, inserted);
		SettlementIntelligence.recordCrafted((ServerLevel) villager.level(), settlementData(), settlement, villager, Items.BREAD, inserted);
		settlementData().completeEraMilestone(settlement, CivilizationEras.BREAD_BAKED);
		CivilizationEras.refresh((ServerLevel) villager.level(), settlementData(), settlement);
	}

	private HousingRecipes.Recipe chooseHousingRecipe() {
		if (!hasCraftingStation() || !ConstructionPlanner.housingPlan(settlementData(), settlement).needsHousing()) {
			return null;
		}
		long planks = availableItems(Items.OAK_PLANKS);
		if (availableItems(Items.OAK_DOOR) >= 1 && availableItems(Items.WHITE_BED) >= 1
			&& availableItems(Items.TORCH) >= 2 && planks >= 29) {
			return null;
		}
		if (planks < 29) {
			return hasLogs() ? HousingRecipes.PLANKS : null;
		}
		if (planks < 35 && hasLogs()) {
			return HousingRecipes.PLANKS;
		}
		if (availableItems(Items.OAK_DOOR) < 1 && availableItems(Items.OAK_PLANKS) >= 3) {
			return HousingRecipes.DOOR;
		}
		if (availableItems(Items.TORCH) < 2 && availableItems(Items.COAL) >= 1 && availableItems(Items.STICK) >= 1) {
			return HousingRecipes.TORCH;
		}
		if (availableItems(Items.WHITE_BED) < 1 && availableItems(Items.WHITE_WOOL) >= 3
			&& availableItems(Items.OAK_PLANKS) >= 3) {
			return HousingRecipes.BED;
		}
		return null;
	}

	private boolean needsPlanks() {
		if (!ConstructionPlanner.housingPlan(settlementData(), settlement).needsHousing()) {
			return false;
		}
		if (availableItems(Items.OAK_DOOR) >= 1 && availableItems(Items.WHITE_BED) >= 1
			&& availableItems(Items.TORCH) >= 2 && availableItems(Items.OAK_PLANKS) >= 29) {
			return false;
		}
		return availableItems(Items.OAK_PLANKS) < 35 && !hasLogs();
	}

	private void craftHousing() {
		HousingRecipes.Recipe recipe = currentHousingRecipe == null ? chooseHousingRecipe() : currentHousingRecipe;
		if (recipe == null || !canCraftHousingIngredients(recipe)) {
			return;
		}
		currentHousingRecipe = recipe;
		for (HousingRecipes.Ingredient ingredient : recipe.ingredients()) {
			if (ingredient.item() == Items.OAK_LOG) {
				if (!withdrawOneLog() || !removeOneLog(villager.getInventory())) {
					return;
				}
			} else if (!withdrawItems(ingredient.item(), ingredient.count())
				|| removeItems(villager.getInventory(), ingredient.item(), ingredient.count()) != ingredient.count()) {
				return;
			}
		}
		int inserted = SettlementStockpile.insert(villager.getInventory(), new ItemStack(recipe.output(), recipe.outputCount()));
		if (inserted < recipe.outputCount()) {
			villager.spawnAtLocation(new ItemStack(recipe.output(), recipe.outputCount() - inserted));
		}
		SettlementSimulationService.recordCrafting(villager, settlementData(), settlement, recipe.id());
		SettlementSimulationService.recordCraftedProduct(
			villager, settlementData(), settlement, recipe.id(), recipe.output(), inserted
		);
	}

	private boolean canCraftHousingIngredients(HousingRecipes.Recipe recipe) {
		if (!hasCraftingStation()) {
			return false;
		}
		for (HousingRecipes.Ingredient ingredient : recipe.ingredients()) {
			if (ingredient.item() == Items.OAK_LOG ? !hasLogs() : availableItems(ingredient.item()) < ingredient.count()) {
				return false;
			}
		}
		return true;
	}

	private void collectIron() {
		var furnace = furnace();
		int inserted = SettlementMetallurgy.collectIron(furnace, villager.getInventory());
		if (inserted <= 0) {
			return;
		}
		SettlementSimulationService.recordCrafting(villager, settlementData(), settlement, "iron_ingot");
		settlementData().completeEraMilestone(settlement, CivilizationEras.METAL_SMELTING);
		CivilizationEras.refresh((ServerLevel) villager.level(), settlementData(), settlement);
	}

	private void smeltIron() {
		if (!canSmeltIron() || !withdrawItems(Items.RAW_IRON, 1)) {
			return;
		}
		if (SettlementMetallurgy.needsFuel(furnace()) && !withdrawItems(Items.COAL, 1)) {
			return;
		}
		SettlementMetallurgy.load(furnace(), villager.getInventory());
	}

	private void craftIronPickaxe() {
		if (!canCraftIronPickaxe() || !withdrawItems(Items.IRON_INGOT, 3) || !withdrawItems(Items.STICK, 2)) {
			return;
		}
		if (removeItems(villager.getInventory(), Items.IRON_INGOT, 3) != 3
			|| removeItems(villager.getInventory(), Items.STICK, 2) != 2) {
			return;
		}
		ItemStack previousTool = villager.getMainHandItem().copy();
		if (!previousTool.isEmpty()) {
			ItemStack remainder = villager.getInventory().addItem(previousTool);
			if (!remainder.isEmpty()) {
				villager.spawnAtLocation(remainder);
			}
		}
		villager.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
		SettlementSimulationService.recordCrafting(villager, settlementData(), settlement, "iron_pickaxe");
		settlementData().completeEraMilestone(settlement, CivilizationEras.METAL_TOOL_PRODUCTION);
		CivilizationEras.refresh((ServerLevel) villager.level(), settlementData(), settlement);
	}

	private void craftShears() {
		if (!canCraftShears() || !withdrawItems(Items.IRON_INGOT, 2)) {
			return;
		}
		if (removeItems(villager.getInventory(), Items.IRON_INGOT, 2) != 2) {
			return;
		}
		ItemStack previousTool = villager.getMainHandItem().copy();
		if (!previousTool.isEmpty()) {
			ItemStack remainder = villager.getInventory().addItem(previousTool);
			if (!remainder.isEmpty()) {
				villager.spawnAtLocation(remainder);
			}
		}
		villager.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHEARS));
		SettlementSimulationService.recordCrafting(villager, settlementData(), settlement, "shears");
	}

	private boolean canCraftShears() {
		return hasCraftingStation() && needsShears() && availableItems(Items.IRON_INGOT) >= 2 && sheep() != null;
	}

	private boolean canShearSheep() {
		return needsWool() && hasShears() && sheep() != null;
	}

	private void shearSheep() {
		Sheep sheep = sheep();
		if (!canShearSheep() || sheep == null) {
			return;
		}
		sheep.shear(SoundSource.PLAYERS);
		villager.getMainHandItem().hurtAndBreak(1, villager, EquipmentSlot.MAINHAND);
		ServerLevel level = (ServerLevel) villager.level();
		for (var itemEntity : level.getEntities(
			net.minecraft.world.entity.EntityType.ITEM, new AABB(sheep.blockPosition()).inflate(2),
			entity -> entity.getItem().is(Items.WHITE_WOOL)
		)) {
			ItemStack stack = itemEntity.getItem();
			int produced = stack.getCount();
			ItemStack remainder = villager.getInventory().addItem(stack);
			if (remainder.isEmpty()) {
				itemEntity.discard();
			} else {
				itemEntity.setItem(remainder);
			}
			int inserted = produced - remainder.getCount();
			if (inserted > 0) {
				SettlementSimulationService.recordProduction(villager, settlementData(), settlement, Items.WHITE_WOOL, inserted);
			}
		}
	}

	private Sheep sheep() {
		if (!(villager.level() instanceof ServerLevel level)) {
			return null;
		}
		if (sheepId != null && level.getEntity(sheepId) instanceof Sheep sheep && isShearableWhiteSheep(sheep)) {
			return sheep;
		}
		return level.getEntities(EntityType.SHEEP, new AABB(villager.blockPosition()).inflate(12), this::isShearableWhiteSheep)
			.stream()
			.min(Comparator.comparingDouble(sheep -> sheep.distanceToSqr(villager)))
			.map(sheep -> {
				sheepId = sheep.getUUID();
				return sheep;
			})
			.orElse(null);
	}

	private boolean isShearableWhiteSheep(Sheep sheep) {
		return sheep.readyForShearing() && sheep.getColor() == DyeColor.WHITE;
	}

	private boolean canCollectIron() {
		var furnace = furnace();
		return hasSmeltingStation()
			&& SettlementMetallurgy.hasIronOutput(furnace)
			&& villager.getInventory().canAddItem(furnace.getItem(SettlementMetallurgy.OUTPUT_SLOT));
	}

	private boolean canSmeltIron() {
		return hasSmeltingStation()
			&& (!hasIronPickaxe() || needsShears())
			&& availableItems(Items.IRON_INGOT) < requiredIronIngots()
			&& availableItems(Items.RAW_IRON) >= 1
			&& (!SettlementMetallurgy.needsFuel(furnace()) || availableItems(Items.COAL) >= 1)
			&& SettlementMetallurgy.canLoad(furnace());
	}

	private boolean canCraftIronPickaxe() {
		return hasCraftingStation()
			&& !hasIronPickaxe()
			&& hasStonePickaxe()
			&& availableItems(Items.IRON_INGOT) >= 3
			&& availableItems(Items.STICK) >= 2;
	}

	private boolean canCraftPickaxe() {
		return hasCraftingStation()
			&& !hasMiningPickaxe()
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
			case BAKE_BREAD -> "bread";
			case CRAFT_HOUSING -> currentHousingRecipe == null ? "housing" : currentHousingRecipe.id();
			case CRAFT_SHEARS -> "shears";
			case CRAFT_IRON_PICKAXE -> "iron_pickaxe";
			case SMELT_IRON, COLLECT_IRON -> "iron_ingot";
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

	private boolean hasSmeltingStation() {
		return settlement != null && settlementData().getSmeltingStation(settlement)
			.filter(pos -> settlement.center().distSqr(pos) <= SEARCH_RADIUS_SQUARED)
			.filter(pos -> SettlementMetallurgy.isSmeltingStation(villager.level().getBlockState(pos)))
			.isPresent();
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

	private net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace() {
		return settlement == null ? null : SettlementMetallurgy.furnaceAt((ServerLevel) villager.level(), settlementData(), settlement);
	}

	private long metalSupply(Item item) {
		return availableItems(item) + SettlementMetallurgy.count(furnace(), item);
	}

	private int requiredIronIngots() {
		return hasIronPickaxe() && needsShears() ? 2 : 3;
	}

	private boolean needsShears() {
		return needsWool() && !hasShears();
	}

	private boolean needsWool() {
		return ConstructionPlanner.housingPlan(settlementData(), settlement).needsHousing()
			&& availableItems(Items.WHITE_WOOL) < 3;
	}

	private boolean needsRawIron() {
		return metalSupply(Items.RAW_IRON) + metalSupply(Items.IRON_INGOT) < requiredIronIngots();
	}

	private boolean needsCoal() {
		return metalSupply(Items.RAW_IRON) > 0 && metalSupply(Items.IRON_INGOT) < requiredIronIngots()
			&& metalSupply(Items.COAL) < 1;
	}

	private boolean needsSticks() {
		if (!hasMiningPickaxe()) {
			return availableItems(Items.STICK) < 2;
		}
		return !hasIronPickaxe() && availableItems(Items.IRON_INGOT) >= 3 && availableItems(Items.STICK) < 2;
	}

	private boolean needsCobblestone() {
		return !hasMiningPickaxe() && availableItems(Items.COBBLESTONE) < 3;
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

	private boolean hasShears() {
		ItemStack hand = villager.getMainHandItem();
		if (hand.is(Items.SHEARS)) {
			return true;
		}
		return (hand.isEmpty() || hasMiningToolInHand()) && equipPickaxe(Items.SHEARS);
	}

	private boolean hasMiningPickaxe() {
		if (hasMiningToolInHand()) {
			return true;
		}
		return villager.getMainHandItem().isEmpty() && (equipPickaxe(Items.IRON_PICKAXE) || equipPickaxe(Items.STONE_PICKAXE));
	}

	private boolean hasMiningToolInHand() {
		return villager.getMainHandItem().is(Items.STONE_PICKAXE) || villager.getMainHandItem().is(Items.IRON_PICKAXE);
	}

	private boolean hasIronPickaxe() {
		ItemStack hand = villager.getMainHandItem();
		if (hand.is(Items.IRON_PICKAXE)) {
			return true;
		}
		return (hand.isEmpty() || hand.is(Items.STONE_PICKAXE)) && equipPickaxe(Items.IRON_PICKAXE);
	}

	private boolean hasStonePickaxe() {
		ItemStack hand = villager.getMainHandItem();
		if (hand.is(Items.STONE_PICKAXE)) {
			return true;
		}
		return hand.isEmpty() && equipPickaxe(Items.STONE_PICKAXE);
	}

	private boolean equipPickaxe(Item item) {
		for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
			ItemStack stack = villager.getInventory().getItem(slot);
			if (stack.is(item)) {
				ItemStack previous = villager.getMainHandItem();
				villager.setItemInHand(InteractionHand.MAIN_HAND, stack);
				villager.getInventory().setItem(slot, previous.isEmpty() ? ItemStack.EMPTY : previous);
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
		BAKE_BREAD("bake"),
		CRAFT_HOUSING("craft_housing"),
		CRAFT_SHEARS("craft_shears"),
		SHEAR_SHEEP("shear"),
		COLLECT_IRON("collect"),
		SMELT_IRON("smelt"),
		CRAFT_IRON_PICKAXE("forge"),
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
