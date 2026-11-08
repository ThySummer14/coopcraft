package com.thysummer.coopcraft.civilization;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.LivingEntity;
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
			Container stockpile = loadedStockpile(level, data, settlement);
			for (ConstructionPlanner.Project project : ConstructionPlanner.projects(data, settlement)) {
				ConstructionPlan candidate = BlueprintManager.get(project.blueprintId());
				if (candidate == null || !ConstructionMaterials.hasAll(stockpile, data.simulationOf(settlement).orElse(null), candidate)) {
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
		if (!level.isLoaded(position) || !level.isPositionEntityTicking(position)) {
			return Optional.empty();
		}
		Container stockpile = loadedStockpile(level, data, settlement);
		if (stockpile == null || (instruction.consume() && ConstructionMaterials.available(stockpile, data.simulationOf(settlement).orElse(null), instruction.item()) < 1)) {
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
		ConstructionPlan plan = BlueprintManager.get(current.blueprintId());
		if (plan == null || current.nextIndex() < 0 || current.nextIndex() >= plan.instructions().size()
			|| !plan.instructions().get(current.nextIndex()).equals(target.instruction())
			|| !current.origin().offset(target.instruction().offset()).equals(target.position())) {
			return false;
		}
		ConstructionInstruction instruction = target.instruction();
		if (!level.isLoaded(target.position()) || !level.isPositionEntityTicking(target.position())
			|| !level.getEntitiesOfClass(LivingEntity.class, new AABB(target.position()),
				entity -> entity.isAlive() && !entity.isSpectator()).isEmpty()) {
			return false;
		}
		if (!level.getBlockState(target.position()).isAir()) {
			data.cancelConstruction(settlement);
			return false;
		}

		Container stockpile = loadedStockpile(level, data, settlement);
		boolean firstPart = instruction.consume()
			&& (instruction.state().getBlock() instanceof DoorBlock || instruction.state().getBlock() instanceof BedBlock);
		if (!ConstructionMaterials.place(stockpile, data.simulationOf(settlement).orElse(null),
			instruction.item(), instruction.consume(), current, villager.getUUID(), data::setDirty,
			() -> level.setBlock(target.position(), instruction.state(), firstPart ? Block.UPDATE_CLIENTS : Block.UPDATE_ALL))) {
			return false;
		}
		if (current.nextIndex() + 1 >= plan.instructions().size()) {
			data.completeConstruction(settlement);
			for (ConstructionInstruction part : plan.instructions()) {
				BlockPos bed = current.origin().offset(part.offset());
				if (part.item() == Items.WHITE_BED && part.consume() && level.isLoaded(bed) && level.getBlockState(bed).is(Blocks.WHITE_BED)) {
					data.addHousingBed(settlement, bed);
				}
			}
			if (plan.id().equals(ConstructionPlanner.CAMPFIRE_HEARTH)) {
				for (ConstructionInstruction part : plan.instructions()) {
					if (part.state().is(Blocks.CAMPFIRE)) {
						data.setHearth(settlement, current.origin().offset(part.offset()));
						break;
					}
				}
			}
		} else {
			data.advanceConstruction(settlement);
		}
		return true;
	}

	private static Container loadedStockpile(ServerLevel level, SettlementSavedData data, Settlement settlement) {
		BlockPos position = data.getStockpile(settlement).orElse(null);
		if (position == null || !level.isLoaded(position)) {
			return null;
		}
		// A double chest can touch the neighboring chunk; do not ask its container to load that chunk.
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			if (!level.isLoaded(position.relative(direction))) {
				return null;
			}
		}
		return SettlementStockpile.getStockpile(level, data, settlement);
	}

	private static void clearStaleHearth(ServerLevel level, SettlementSavedData data, Settlement settlement) {
		BlockPos hearth = data.getHearth(settlement).orElse(null);
		if (hearth != null && level.isLoaded(hearth) && !level.getBlockState(hearth).is(Blocks.CAMPFIRE)) {
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
			if (!level.isLoaded(position) || !level.isPositionEntityTicking(position) || !level.getBlockState(position).isAir()) {
				return false;
			}
		}
		return true;
	}

	public record BuildTarget(ConstructionJob job, ConstructionInstruction instruction, BlockPos position) {
	}
}
