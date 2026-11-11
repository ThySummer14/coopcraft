package com.thysummer.coopcraft.automation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.thysummer.coopcraft.civilization.CivilizationEras;
import com.thysummer.coopcraft.civilization.Settlement;
import com.thysummer.coopcraft.civilization.SettlementSavedData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public final class ComputerCoreBlockEntity extends BlockEntity {
	private static final long TICK_INTERVAL = 20;
	private static final int REQUIRED_ARCHIVES = 2;
	private static final int DEFAULT_THRESHOLD = 4;
	private static final int MAX_WIRE_BLOCKS = 64;
	private static final double ARCHIVE_RANGE_SQUARED = 16.0 * 16.0;

	private ResourceLocation target = BuiltInRegistries.ITEM.getKey(Items.BREAD);
	private int signal;
	private int connectedArchives;
	private long amount;

	public ComputerCoreBlockEntity(BlockPos pos, BlockState state) {
		this(Automation.COMPUTER_CORE_ENTITY, pos, state);
	}

	public ComputerCoreBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	public static void tick(Level level, BlockPos pos, BlockState state, ComputerCoreBlockEntity core) {
		if (level instanceof ServerLevel serverLevel && Math.floorMod(level.getGameTime() + pos.asLong(), TICK_INTERVAL) == 0) {
			core.recompute(serverLevel);
		}
	}

	private void recompute(ServerLevel level) {
		SettlementSavedData data = SettlementSavedData.get(level);
		Settlement settlement = data.find(worldPosition).orElse(null);
		if (settlement == null) {
			updateResult(level, 0, 0, 0);
			return;
		}
		data.registerPlanningCore(settlement, worldPosition);
		List<ArchiveBlockEntity> archives = new ArrayList<>();
		for (BlockPos archivePos : data.archiveStationsOf(settlement)) {
			if (archivePos.distSqr(worldPosition) > ARCHIVE_RANGE_SQUARED) {
				continue;
			}
			if (!(level.getBlockEntity(archivePos) instanceof ArchiveBlockEntity archive)) {
				data.unregisterArchive(settlement, archivePos);
				continue;
			}
			if (archive.hasRecord() && hasSignalLink(level, archivePos, worldPosition)) {
				archives.add(archive);
			}
		}
		Optional<ArchiveBlockEntity> latest = archives.stream()
			.filter(archive -> archive.catalog().orElseThrow().settlementId().equals(settlement.id()))
			.max(Comparator.comparingLong((ArchiveBlockEntity archive) -> archive.catalog().orElseThrow().recordedTick())
				.thenComparingLong(archive -> archive.getBlockPos().asLong()));
		latest.ifPresent(source -> archives.forEach(archive -> archive.synchronize(source.catalog().orElseThrow())));
		connectedArchives = archives.size();
		amount = latest.map(archive -> archive.count(BuiltInRegistries.ITEM.get(target))).orElse(0L);
		updateResult(level, connectedArchives, amount, connectedArchives >= REQUIRED_ARCHIVES && amount >= DEFAULT_THRESHOLD ? 15 : 0);
		if (signal == 15 && hasLitReceiver(level)) {
			data.completeEraMilestone(settlement, CivilizationEras.COMPUTATION_NETWORK);
			CivilizationEras.refresh(level, data, settlement);
		}
	}

	public void selectItem(Item item) {
		target = BuiltInRegistries.ITEM.getKey(item);
		setChanged();
	}

	public Component status() {
		if (connectedArchives < REQUIRED_ARCHIVES) {
			return Component.translatable("coopcraft.computer.no_network", connectedArchives);
		}
		return Component.translatable(
			"coopcraft.computer.status", new ItemStack(BuiltInRegistries.ITEM.get(target)).getHoverName(), amount,
			DEFAULT_THRESHOLD, connectedArchives
		);
	}

	public int signal() {
		return signal;
	}

	public int connectedArchives() {
		return connectedArchives;
	}

	public long amount() {
		return amount;
	}

	public ResourceLocation target() {
		return target;
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putString("Target", target.toString());
		tag.putInt("Signal", signal);
		tag.putInt("ConnectedArchives", connectedArchives);
		tag.putLong("Amount", amount);
	}

	@Override
	public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		target = Optional.ofNullable(ResourceLocation.tryParse(tag.getString("Target")))
			.orElse(BuiltInRegistries.ITEM.getKey(Items.BREAD));
		signal = tag.getInt("Signal");
		connectedArchives = tag.getInt("ConnectedArchives");
		amount = tag.getLong("Amount");
	}

	private void updateResult(ServerLevel level, int nodes, long total, int output) {
		boolean changed = connectedArchives != nodes || amount != total || signal != output;
		connectedArchives = nodes;
		amount = total;
		boolean powerChanged = signal != output;
		signal = output;
		if (changed) {
			setChanged();
		}
		if (powerChanged) {
			level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
		}
	}

	private boolean hasLitReceiver(ServerLevel level) {
		for (Direction direction : Direction.values()) {
			BlockState receiver = level.getBlockState(worldPosition.relative(direction));
			if (receiver.is(Blocks.REDSTONE_LAMP) && receiver.getValue(RedstoneLampBlock.LIT)) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasSignalLink(ServerLevel level, BlockPos archive, BlockPos core) {
		Set<BlockPos> visited = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		visited.add(archive);
		queue.add(archive);
		while (!queue.isEmpty() && visited.size() <= MAX_WIRE_BLOCKS) {
			BlockPos current = queue.removeFirst();
			for (Direction direction : Direction.values()) {
				BlockPos next = current.relative(direction);
				if (next.equals(core)) {
					return true;
				}
				if (isPoweredWire(level, next) && visited.add(next)) {
					queue.addLast(next);
				}
			}
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos upper = current.relative(direction).above();
				BlockPos lower = current.relative(direction).below();
				if (isPoweredWire(level, upper) && visited.add(upper)) {
					queue.addLast(upper);
				}
				if (isPoweredWire(level, lower) && visited.add(lower)) {
					queue.addLast(lower);
				}
			}
		}
		return false;
	}

	private static boolean isPoweredWire(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.is(Blocks.REDSTONE_WIRE) && state.getValue(RedStoneWireBlock.POWER) > 0;
	}
}
