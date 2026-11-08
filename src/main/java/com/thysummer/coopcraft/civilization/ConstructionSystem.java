package com.thysummer.coopcraft.civilization;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.LivingEntity;
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
				job = ConstructionJob.start(candidate, origin);
				data.setConstructionJob(settlement, job);
				break;
			}
			if (job == null) {
				return Optional.empty();
			}
		}

		// Never reinterpret an old index against a changed or unverified plan.
		ConstructionPlan plan = BlueprintManager.get(job.blueprintId());
		if (!job.compatibilityBlocker(plan).equals("ready")) {
			return Optional.empty();
		}

		ConstructionInstruction instruction = plan.instructions().get(job.nextIndex());
		BlockPos position = job.origin().offset(instruction.offset());
		if (!level.isLoaded(position) || !level.isPositionEntityTicking(position)
			|| !level.getBlockState(position).isAir()) {
			return Optional.empty();
		}
		Container stockpile = loadedStockpile(level, data, settlement);
		if (stockpile == null || (instruction.consume() && ConstructionMaterials.available(stockpile, data.simulationOf(settlement).orElse(null), instruction.item()) < 1)) {
			return Optional.empty();
		}
		if (instruction.consume() && stockpile.countItem(instruction.item()) < 1
			&& !ConstructionReadiness.canInsertOne(stockpile, new ItemStack(instruction.item()))) {
			return Optional.empty();
		}
		return Optional.of(new BuildTarget(job, instruction, position));
	}

	/** Read-only snapshot of an existing job; never starts, cancels or advances construction. */
	public static Optional<ConstructionReadiness> inspect(ServerLevel level, SettlementSavedData data, Settlement settlement) {
		return data.getConstructionJob(settlement).map(job -> ConstructionReadiness.inspect(
			job, BlueprintManager.get(job.blueprintId()), new ConstructionReadiness.Probe() {
				@Override
				public boolean ticking(BlockPos position) {
					return level.isLoaded(position) && level.isPositionEntityTicking(position);
				}

				@Override
				public boolean air(BlockPos position) {
					return level.getBlockState(position).isAir();
				}

				@Override
				public boolean entityObstructed(BlockPos position) {
					return !level.getEntitiesOfClass(LivingEntity.class, new AABB(position),
						entity -> entity.isAlive() && !entity.isSpectator()).isEmpty();
				}

				@Override
				public Container stockpile() {
					return loadedStockpile(level, data, settlement);
				}

				@Override
				public SettlementSimulationState simulation() {
					return data.simulationOf(settlement).orElse(null);
				}
			}));
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
		if (!current.compatibilityBlocker(plan).equals("ready")
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
		// Player blocks, even identical states, pause the existing job without consuming or skipping.
		if (!level.getBlockState(target.position()).isAir()) {
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
				if (part.state().getBlock() instanceof BedBlock && part.consume()) {
					SettlementHousing.registerBed(level, data, settlement, bed);
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

	/** Explicit legacy-only adoption. Validation never consumes items or changes blocks/index. */
	public static ConstructionPlanAdoption.Result adoptLegacyPlan(ServerLevel level, SettlementSavedData data,
		Settlement settlement, String expectedFingerprint) {
		return adoptLegacyPlan(data, settlement, expectedFingerprint, new ConstructionPlanAdoption.Probe() {
			public boolean loaded(BlockPos position) { return level.isLoaded(position); }
			public net.minecraft.world.level.block.state.BlockState state(BlockPos position) {
				return level.getBlockState(position);
			}
		});
	}

	static ConstructionPlanAdoption.Result adoptLegacyPlan(SettlementSavedData data, Settlement settlement,
		String expectedFingerprint, ConstructionPlanAdoption.Probe probe) {
		ConstructionJob job = data.getConstructionJob(settlement).orElse(null);
		ConstructionPlan plan = job == null ? null : BlueprintManager.get(job.blueprintId());
		var result = ConstructionPlanAdoption.inspect(job, plan, expectedFingerprint, probe);
		if (result.status().equals("adopted")) {
			data.setConstructionJob(settlement,
				new ConstructionJob(job.blueprintId(), job.origin(), job.nextIndex(), plan.fingerprint()));
		}
		return result;
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
