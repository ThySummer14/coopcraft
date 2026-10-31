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

	private final Villager villager;
	private Action action;
	private BlockPos target;
	private ConstructionSystem.BuildTarget currentBuildTarget;
	private Settlement settlement;
	private int progress;
	private int searchCooldown;
	private int travelTicks;
	private int approachIndex;

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
		if (action == Action.EAT && isTargetValid()) {
			return true;
		}
		if (shouldEat()) {
			action = Action.EAT;
			target = settlementData().getStockpile(settlement).orElse(null);
			progress = 0;
			return target != null;
		}
		if (villager.isBaby()) {
			action = null;
			target = null;
			return false;
		}
		if (action != Action.BUILD) {
			Optional<ConstructionSystem.BuildTarget> buildTarget = ConstructionSystem.nextBuildTarget(
				(ServerLevel) villager.level(), settlementData(), settlement, villager
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
		if (searchCooldown > 0) {
			searchCooldown--;
			return false;
		}

		action = chooseAction();
		searchCooldown = SEARCH_INTERVAL;
		target = chooseTarget(action);
		progress = 0;
		return action != null;
	}

	@Override
	public boolean canContinueToUse() {
		return action != null && isTargetValid() && findWorkSettlement().isPresent();
	}

	@Override
	public void start() {
		progress = 0;
		travelTicks = 0;
		navigateToTarget();
	}

	@Override
	public void stop() {
		action = null;
		target = null;
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
		return "action=" + action + ", target=" + target + ", progress=" + progress + ", running=" + running
			+ ", canContinue=" + canContinueToUse() + ", alive=" + villager.isAlive() + ", villagerTick=" + villager.tickCount
			+ ", navigationDone=" + villager.getNavigation().isDone() + ", path=" + villager.getNavigation().getPath();
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		if (!navigateToTarget()) {
			return;
		}
		progress++;
		int baseDuration = switch (action) {
			case GATHER -> hasStonePickaxe() ? TOOL_GATHER_TICKS : HAND_GATHER_TICKS;
			case BUILD -> BUILD_TICKS;
			case EAT -> EAT_TICKS;
			default -> CRAFT_TICKS;
		};
		var workType = switch (action) {
			case GATHER -> VillagerPersonality.WorkType.GATHER;
			case BUILD -> VillagerPersonality.WorkType.BUILD;
			case EAT -> VillagerPersonality.WorkType.GENERAL;
			default -> VillagerPersonality.WorkType.CRAFT;
		};
		int duration = settlementData().personalityOf(settlement, villager.getUUID()).workDuration(baseDuration, workType);
		if (action != Action.EAT && settlementData().needsOf(settlement, villager.getUUID()).satiety() < 20) {
			duration = duration * 5 / 4;
		}
		if (progress < duration) {
			return;
		}

		switch (action) {
			case GATHER -> gather();
			case CRAFT_PICKAXE -> craftPickaxe();
			case CRAFT_STICKS -> craftSticks();
			case BUILD -> ConstructionSystem.buildNext(
				(ServerLevel) villager.level(), settlementData(), settlement, currentBuildTarget, villager
			);
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
		if (action == Action.EAT) {
			return settlementData().getStockpile(settlement).orElse(null);
		}
		if (action == Action.POLISH_PICKAXE || action == Action.GRIND_SMOOTH_STONE) {
			return settlementData().getGrindingStation(settlement).orElse(null);
		}
		return settlementData().getCraftingStation(settlement).orElse(null);
	}

	private BlockPos chooseGatherTarget() {
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
			boolean moving = action == Action.BUILD ? navigateToConstructionApproach() : navigateTo(target.above());
			if (!moving) {
				stop();
			} else {
				travelTicks = 0;
			}
		}
		return false;
	}

	private boolean navigateToConstructionApproach() {
		ServerLevel level = (ServerLevel) villager.level();
		List<BlockPos> candidates = new ArrayList<>();
		for (int vertical : new int[] {0, 1, -1, -2}) {
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos candidate = target.relative(direction).above(vertical);
				if (level.isLoaded(candidate)
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
		return false;
	}

	private boolean navigateTo(BlockPos pos) {
		double speed = settlementData().personalityOf(settlement, villager.getUUID()).movementSpeed();
		return villager.getNavigation().moveTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, speed);
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
		if (action == Action.GATHER) {
			return isGatherable(target);
		}
		if (action == Action.BUILD) {
			return currentBuildTarget != null && ConstructionSystem.nextBuildTarget(level, settlementData(), settlement, villager)
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

	private boolean hasFoodAvailable() {
		if (SettlementSimulationService.isSurvivalEnabled(settlementData(), settlement)) {
			return SettlementSimulationService.hasFood(settlementData(), settlement);
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
			ItemStack remainder = villager.getInventory().addItem(drop);
			if (!remainder.isEmpty()) {
				villager.spawnAtLocation(remainder);
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
	}

	private void craftSticks() {
		if (!canCraftSticks() || !withdrawOneLog() || !removeOneLog(villager.getInventory())) {
			return;
		}
		int inserted = SettlementStockpile.insert(villager.getInventory(), new ItemStack(Items.STICK, 8));
		if (inserted < 8) {
			villager.spawnAtLocation(new ItemStack(Items.STICK, 8 - inserted));
		}
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
	}

	private void grindSmoothStone() {
		if (!canGrindSmoothStone() || !withdrawItems(Items.COBBLESTONE, 1)
			|| removeItems(villager.getInventory(), Items.COBBLESTONE, 1) != 1) {
			return;
		}
		if (SettlementStockpile.insert(villager.getInventory(), new ItemStack(Items.SMOOTH_STONE)) != 1) {
			villager.spawnAtLocation(new ItemStack(Items.SMOOTH_STONE));
		}
	}

	private int availableItems(Item item) {
		Container stockpile = SettlementStockpile.getStockpile((ServerLevel) villager.level(), settlementData(), settlement);
		return villager.getInventory().countItem(item) + (stockpile == null ? 0 : stockpile.countItem(item));
	}

	private boolean withdrawItems(Item item, int count) {
		int missing = count - villager.getInventory().countItem(item);
		if (missing <= 0) {
			return true;
		}
		Container stockpile = SettlementStockpile.getStockpile((ServerLevel) villager.level(), settlementData(), settlement);
		if (stockpile == null || stockpile.countItem(item) < missing) {
			return false;
		}
		int removed = removeItems(stockpile, item, missing);
		int inserted = SettlementStockpile.insert(villager.getInventory(), new ItemStack(item, removed));
		if (inserted < removed) {
			SettlementStockpile.insert(stockpile, new ItemStack(item, removed - inserted));
			return false;
		}
		return true;
	}

	private boolean withdrawOneLog() {
		if (villager.getInventory().hasAnyMatching(stack -> stack.is(ItemTags.LOGS))) {
			return true;
		}
		Container stockpile = SettlementStockpile.getStockpile((ServerLevel) villager.level(), settlementData(), settlement);
		if (stockpile == null) {
			return false;
		}
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
		return false;
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
		return stockpile != null && stockpile.hasAnyMatching(stack -> stack.is(ItemTags.LOGS));
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
		GATHER,
		CRAFT_PICKAXE,
		CRAFT_STICKS,
		BUILD,
		POLISH_PICKAXE,
		GRIND_SMOOTH_STONE,
		EAT
	}
}
