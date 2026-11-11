package com.thysummer.coopcraft.civilization;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

/** Reconciles only explicitly registered beds; never discovers or claims nearby beds. */
public final class SettlementHousing {
	private static final int INTERVAL = 100;

	private SettlementHousing() {
	}

	public enum RegistrationResult { ADDED, ALREADY_REGISTERED, OTHER_SETTLEMENT, INVALID, UNLOADED }

	enum BedStatus { VALID, INVALID, UNLOADED }

	record BedCheck(BedStatus status, BlockPos foot, BlockPos head) { }

	/** The state reader is called only after a successful loaded check. */
	interface BedAccess {
		boolean isLoaded(BlockPos pos);
		BlockState stateAt(BlockPos pos);
	}

	private static BedAccess access(ServerLevel level) {
		return new BedAccess() {
			public boolean isLoaded(BlockPos pos) { return level.hasChunkAt(pos); }
			public BlockState stateAt(BlockPos pos) { return level.getBlockState(pos); }
		};
	}

	public static void tick(ServerLevel level, SettlementSavedData data, Settlement settlement, long gameTime) {
		if (maintenanceDue(settlement, gameTime)) {
			reconcile(level, data, settlement);
		}
	}

	static boolean maintenanceDue(Settlement settlement, long gameTime) {
		// Compare residues rather than adding a phase, which could overflow near Long.MAX_VALUE.
		return Math.floorMod(gameTime, INTERVAL) == Math.floorMod(settlement.id().hashCode(), INTERVAL);
	}

	/** Refresh before capacity-based decisions, without waiting for the periodic tick. */
	public static void reconcile(ServerLevel level, SettlementSavedData data, Settlement settlement) {
		reconcile(access(level), data, settlement);
	}

	public static RegistrationResult registerBed(ServerLevel level, SettlementSavedData data,
			Settlement settlement, BlockPos pos) {
		return registerBed(access(level), data, settlement, pos);
	}

	static BedCheck inspectBed(ServerLevel level, BlockPos pos) {
		return inspectBed(access(level), pos);
	}

	static BedCheck inspectBed(BedAccess world, BlockPos pos) {
		if (!world.isLoaded(pos)) return new BedCheck(BedStatus.UNLOADED, null, null);
		BlockState state = world.stateAt(pos);
		if (!(state.getBlock() instanceof BedBlock)) {
			// An old record may point to either half. Once that half is gone its facing is unknown.
			// Defer removal at unloaded chunk edges rather than reading or guessing the missing half.
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				if (!world.isLoaded(pos.relative(direction))) return new BedCheck(BedStatus.UNLOADED, null, null);
			}
			return new BedCheck(BedStatus.INVALID, null, null);
		}
		Direction facing = state.getValue(BedBlock.FACING);
		boolean isFoot = state.getValue(BedBlock.PART) == BedPart.FOOT;
		BlockPos foot = (isFoot ? pos : pos.relative(facing.getOpposite())).immutable();
		BlockPos head = foot.relative(facing);
		BlockPos other = isFoot ? head : foot;
		if (!world.isLoaded(other)) return new BedCheck(BedStatus.UNLOADED, null, null);
		BlockState counterpart = world.stateAt(other);
		boolean valid = counterpart.getBlock() == state.getBlock()
			&& counterpart.getValue(BedBlock.FACING) == facing
			&& counterpart.getValue(BedBlock.PART) == (isFoot ? BedPart.HEAD : BedPart.FOOT);
		return new BedCheck(valid ? BedStatus.VALID : BedStatus.INVALID, foot, head);
	}

	static RegistrationResult registerBed(BedAccess world, SettlementSavedData data,
			Settlement settlement, BlockPos pos) {
		BedCheck check = inspectBed(world, pos);
		if (check.status() == BedStatus.UNLOADED) return RegistrationResult.UNLOADED;
		if (check.status() == BedStatus.INVALID) return RegistrationResult.INVALID;
		// Checking both physical positions also covers legacy head-only registrations, even
		// if the owning settlement has not had its periodic maintenance turn yet.
		for (Settlement other : data.all()) {
			if (!other.id().equals(settlement.id())
					&& (data.hasHousingBed(other, check.foot()) || data.hasHousingBed(other, check.head()))) {
				return RegistrationResult.OTHER_SETTLEMENT;
			}
		}
		boolean registered = data.hasHousingBed(settlement, check.foot())
			|| data.hasHousingBed(settlement, check.head());
		data.removeHousingBed(settlement, check.head());
		data.addHousingBed(settlement, check.foot());
		return registered ? RegistrationResult.ALREADY_REGISTERED : RegistrationResult.ADDED;
	}

	static void reconcile(BedAccess world, SettlementSavedData data, Settlement settlement) {
		var settlements = data.all();
		for (BlockPos recorded : data.housingBeds(settlement)) {
			BedCheck check = inspectBed(world, recorded);
			if (check.status() == BedStatus.INVALID) {
				data.removeHousingBed(settlement, recorded);
			} else if (check.status() == BedStatus.VALID) {
				// Stable save-order ownership resolves historical cross-settlement duplicates.
				Settlement owner = settlements.stream()
					.filter(candidate -> data.hasHousingBed(candidate, check.foot())
						|| data.hasHousingBed(candidate, check.head()))
					.findFirst().orElse(settlement);
				if (!owner.id().equals(settlement.id())) {
					data.removeHousingBed(settlement, recorded);
				} else {
					data.removeHousingBed(settlement, check.head());
					data.addHousingBed(settlement, check.foot());
				}
			}
		}
	}
}
