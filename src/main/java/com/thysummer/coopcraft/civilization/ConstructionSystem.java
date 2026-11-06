package com.thysummer.coopcraft.civilization;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.phys.AABB;

public final class ConstructionSystem {
	private static final int[] BUILD_DISTANCES = {6, 12, 18};

	private ConstructionSystem() {
	}

	public static Optional<BuildTarget> nextBuildTarget(ServerLevel level, SettlementSavedData data, Settlement settlement) {
		ConstructionJob job = data.getConstructionJob(settlement).orElse(null);
		if (job == null) {
			clearStaleHearth(level, data, settlement);
			Container stockpile = SettlementStockpile.getStockpile(level, data, settlement);
			for (ConstructionPlanner.Project project : ConstructionPlanner.projects(data, settlement)) {
				ConstructionPlan candidate = BlueprintManager.get(project.blueprintId());
				if (candidate == null || !hasAllMaterials(stockpile, candidate)) {
					continue;
				}
				BlockPos origin = chooseOrigin(level, settlement, candidate);
				if (origin == null) {
					continue;
				}
				job = new ConstructionJob(candidate.id(), origin, 0);
				data.setConstructionJob(settlement, job);
				break;
			}
			if (job == null) {
				return Optional.empty();
			}
		}

		ConstructionPlan plan = BlueprintManager.get(job.blueprintId());
		if (plan == null) {
			data.cancelConstruction(settlement);
			return Optional.empty();
		}
		if (job.nextIndex() >= plan.instructions().size()) {
			data.cancelConstruction(settlement);
			return Optional.empty();
		}

		ConstructionInstruction instruction = plan.instructions().get(job.nextIndex());
		BlockPos position = job.origin().offset(instruction.offset());
		if (!level.isLoaded(position)) {
			return Optional.empty();
		}
		Container stockpile = SettlementStockpile.getStockpile(level, data, settlement);
		if (stockpile == null || (instruction.consume() && stockpile.countItem(instruction.item()) < 1)) {
			return Optional.empty();
		}
		return Optional.of(new BuildTarget(job, instruction, position));
	}

	public static boolean buildNext(
		ServerLevel level,
		SettlementSavedData data,
		Settlement settlement,
		BuildTarget target,
		Villager villager
	) {
		ConstructionJob current = data.getConstructionJob(settlement).orElse(null);
		if (current == null || !current.equals(target.job())) {
			return false;
		}
		ConstructionInstruction instruction = target.instruction();
		if (!level.isLoaded(target.position()) || villager.getBoundingBox().intersects(new AABB(target.position()))) {
			return false;
		}
		if (!level.getBlockState(target.position()).isAir()) {
			data.cancelConstruction(settlement);
			return false;
		}

		Container stockpile = SettlementStockpile.getStockpile(level, data, settlement);
		if (stockpile == null || (instruction.consume() && stockpile.countItem(instruction.item()) < 1)) {
			return false;
		}
		if (instruction.consume()) {
			removeOne(stockpile, instruction.item());
		}

		boolean firstPart = instruction.consume()
			&& (instruction.state().getBlock() instanceof DoorBlock || instruction.state().getBlock() instanceof BedBlock);
		level.setBlock(target.position(), instruction.state(), firstPart ? Block.UPDATE_CLIENTS : Block.UPDATE_ALL);
		ConstructionPlan plan = BlueprintManager.get(current.blueprintId());
		if (plan == null || current.nextIndex() + 1 >= plan.instructions().size()) {
			data.completeConstruction(settlement);
			if (plan != null) {
				for (ConstructionInstruction part : plan.instructions()) {
					BlockPos bed = current.origin().offset(part.offset());
					if (part.item() == Items.WHITE_BED && part.consume() && level.getBlockState(bed).is(Blocks.WHITE_BED)) {
						data.addHousingBed(settlement, bed);
					}
				}
				if (plan.id().equals(ConstructionPlanner.STONE_COTTAGE)) {
					data.completeEraMilestone(settlement, CivilizationEras.HOUSING_COMPLETE);
				}
				if (plan.id().equals(ConstructionPlanner.CAMPFIRE_HEARTH)) {
					for (ConstructionInstruction part : plan.instructions()) {
						if (part.state().is(Blocks.CAMPFIRE)) {
							data.setHearth(settlement, current.origin().offset(part.offset()));
							break;
						}
					}
				}
			}
		} else {
			data.advanceConstruction(settlement);
		}
		return true;
	}

	private static boolean hasAllMaterials(Container stockpile, ConstructionPlan plan) {
		if (stockpile == null) {
			return false;
		}
		for (var entry : plan.materials().entrySet()) {
			if (stockpile.countItem(entry.getKey()) < entry.getValue()) {
				return false;
			}
		}
		return true;
	}

	private static void clearStaleHearth(ServerLevel level, SettlementSavedData data, Settlement settlement) {
		BlockPos hearth = data.getHearth(settlement).orElse(null);
		if (hearth != null && !level.getBlockState(hearth).is(Blocks.CAMPFIRE)) {
			data.clearHearth(settlement);
		}
	}

	private static BlockPos chooseOrigin(ServerLevel level, Settlement settlement, ConstructionPlan plan) {
		for (int distance : BUILD_DISTANCES) {
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos origin = settlement.center().relative(direction, distance);
				if (canPlace(level, plan, origin)) {
					return origin;
				}
			}
		}
		return null;
	}

	private static boolean canPlace(ServerLevel level, ConstructionPlan plan, BlockPos origin) {
		for (ConstructionInstruction instruction : plan.instructions()) {
			BlockPos position = origin.offset(instruction.offset());
			if (!level.isPositionEntityTicking(position) || !level.getBlockState(position).isAir()) {
				return false;
			}
		}
		return true;
	}

	private static void removeOne(Container stockpile, Item item) {
		for (int slot = 0; slot < stockpile.getContainerSize(); slot++) {
			if (stockpile.getItem(slot).is(item)) {
				stockpile.removeItem(slot, 1);
				return;
			}
		}
	}

	public record BuildTarget(ConstructionJob job, ConstructionInstruction instruction, BlockPos position) {
	}
}
