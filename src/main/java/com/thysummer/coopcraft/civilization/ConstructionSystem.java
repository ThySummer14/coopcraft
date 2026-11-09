package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.List;
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
				BlockPos origin = chooseOrigin(level, data, settlement, candidate);
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
		var readiness = ConstructionPairReadiness.inspect(job, plan, pairProbe(level), false);
		if (!readiness.ready()) return Optional.empty();
		BlockPos position = readiness.position();
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
				public boolean inBounds(BlockPos position) {
					return !level.isOutsideBuildHeight(position) && level.getWorldBorder().isWithinBounds(position);
				}

				@Override
				public boolean ticking(BlockPos position) {
					return level.isLoaded(position) && level.isPositionEntityTicking(position);
				}

				@Override
				public net.minecraft.world.level.block.state.BlockState state(BlockPos position) {
					return level.getBlockState(position);
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
		// Re-read pair state on execution, even when the work claim was valid earlier.
		if (!placeIfReady(current, plan, pairProbe(level), () -> {
			Container stockpile = loadedStockpile(level, data, settlement);
			boolean firstPart = instruction.consume()
				&& (instruction.state().getBlock() instanceof DoorBlock || instruction.state().getBlock() instanceof BedBlock);
			return ConstructionMaterials.place(stockpile, data.simulationOf(settlement).orElse(null),
				instruction.item(), instruction.consume(), current, villager.getUUID(), data::setDirty,
				() -> level.setBlock(target.position(), instruction.state(), firstPart ? Block.UPDATE_CLIENTS : Block.UPDATE_ALL));
		})) return false;
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

	/** Shared test seam around the actual material transaction, not a cached claim. */
	static boolean placeIfReady(ConstructionJob job, ConstructionPlan plan, ConstructionPairReadiness.Probe probe,
		java.util.function.BooleanSupplier placement) {
		return ConstructionPairReadiness.inspect(job, plan, probe, true).ready() && placement.getAsBoolean();
	}

	private static ConstructionPairReadiness.Probe pairProbe(ServerLevel level) {
		return new ConstructionPairReadiness.Probe() {
			public boolean inBounds(BlockPos position) {
				return !level.isOutsideBuildHeight(position) && level.getWorldBorder().isWithinBounds(position);
			}
			public boolean ticking(BlockPos position) {
				return level.isLoaded(position) && level.isPositionEntityTicking(position);
			}
			public net.minecraft.world.level.block.state.BlockState state(BlockPos position) {
				return level.getBlockState(position);
			}
			public boolean entityObstructed(BlockPos position) {
				return !level.getEntitiesOfClass(LivingEntity.class, new AABB(position),
					entity -> entity.isAlive() && !entity.isSpectator()).isEmpty();
			}
		};
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

	private static BlockPos chooseOrigin(ServerLevel level, SettlementSavedData data, Settlement settlement, ConstructionPlan plan) {
		if (ConstructionSite.isHousing(plan)) {
			return chooseHousingOrigin(data, settlement, plan, level.getGameTime(), siteProbe(level));
		}
		for (BlockPos origin : ConstructionSite.candidates(settlement.center())) {
			if (canPlace(level, plan, origin)) return origin;
		}
		return null;
	}

	/** New housing only, after material checks. Failure throttles never cache world observations. */
	static BlockPos chooseHousingOrigin(SettlementSavedData data, Settlement settlement, ConstructionPlan plan,
		long gameTime, ConstructionSite.Probe probe) {
		ConstructionSiteRetries retries = data.constructionSiteRetries();
		if (retries.shouldWait(settlement, plan, gameTime)) return null;
		BlockPos origin = ConstructionSite.firstReady(plan, settlement.center(), probe)
			.map(ConstructionSite.Result::origin).orElse(null);
		if (origin == null) retries.failed(settlement, plan, gameTime);
		else retries.succeeded(settlement, plan);
		return origin;
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

	/** Current housing projects only; no material checks, job creation or persistent writes. */
	public static List<ConstructionSite.Result> inspectNewHousingSites(ServerLevel level, SettlementSavedData data,
		Settlement settlement) {
		if (data.getConstructionJob(settlement).isPresent()) return List.of();
		List<ConstructionSite.Result> results = new ArrayList<>();
		for (ConstructionPlanner.Project project : ConstructionPlanner.projects(data, settlement)) {
			ConstructionPlan plan = BlueprintManager.get(project.blueprintId());
			if (plan == null) {
				if (ConstructionPlanner.isHousingBlueprint(project.blueprintId())) {
					results.add(new ConstructionSite.Result(project.blueprintId(), settlement.center(), "plan_unavailable", null));
				}
			} else if (ConstructionSite.isHousing(plan)) {
				results.addAll(inspectSites(level, data, settlement, plan));
			}
		}
		return List.copyOf(results);
	}

	/** Read-only new-job diagnostics; an existing job is deliberately never re-sited. */
	public static List<ConstructionSite.Result> inspectSites(ServerLevel level, SettlementSavedData data,
		Settlement settlement, ConstructionPlan plan) {
		return inspectSites(data, settlement, plan, siteProbe(level));
	}

	static List<ConstructionSite.Result> inspectSites(SettlementSavedData data, Settlement settlement,
		ConstructionPlan plan, ConstructionSite.Probe probe) {
		if (data.getConstructionJob(settlement).isPresent()) return List.of();
		return ConstructionSite.inspectCandidates(plan, settlement.center(), probe);
	}

	private static ConstructionSite.Probe siteProbe(ServerLevel level) {
		return new ConstructionSite.Probe() {
			public boolean inBounds(BlockPos position) {
				return !level.isOutsideBuildHeight(position) && level.getWorldBorder().isWithinBounds(position);
			}
			public boolean ticking(BlockPos position) {
				return level.isLoaded(position) && level.isPositionEntityTicking(position);
			}
			public net.minecraft.world.level.block.state.BlockState state(BlockPos position) {
				return level.getBlockState(position);
			}
		};
	}

	public record BuildTarget(ConstructionJob job, ConstructionInstruction instruction, BlockPos position) {
	}
}
