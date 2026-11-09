package com.thysummer.coopcraft.civilization;

import com.thysummer.coopcraft.CoopCraft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Dimension-local custody of real cargo stacks from an observed member death. Server thread only.
 * This is not loot: it neither spawns entities nor reads doMobLoot, tickets or abstract resources.
 * Standard synchronous, non-reentrant Container mutation semantics are required. Normal saves preserve
 * custody; separate entity/chunk/player files do not provide process-crash atomicity.
 */
public final class SettlementCargoRecovery extends SavedData {
	private static final String DATA_NAME = "coopcraft_settlement_cargo_recovery";
	private static final int DATA_VERSION = 1;
	private static final int MAX_CARGO_SLOTS = 8;
	private static final Factory<SettlementCargoRecovery> FACTORY = new Factory<>(
		SettlementCargoRecovery::new, SettlementCargoRecovery::load, null);
	private final Map<UUID, CargoRecord> records = new LinkedHashMap<>();
	private CompoundTag preservedUnreadable;

	/** False means unknown/corrupt stored data is retained verbatim and all mutations fail closed. */
	public boolean available() { return preservedUnreadable == null; }

	public enum Status { UNAVAILABLE, INVALID, NOT_AT_PLACE, NO_CAPACITY, RECOVERED }
	public record Result(Status status, int moved, int remaining) { }
	public record MaterialCount(ResourceLocation itemId, int count) { }
	public record Snapshot(UUID recordId, UUID deceasedId, BlockPos position, String name,
		List<MaterialCount> items, int totalCount) {
		public Snapshot { position = position.immutable(); items = List.copyOf(items); }
	}

	public static SettlementCargoRecovery get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
	}

	/** Called only by AFTER_DEATH, before membership cleanup. Infection may already have discarded the entity. */
	public static int captureDeath(ServerLevel level, Villager villager) {
		if (level == null || villager == null || villager.level() != level) return 0;
		SettlementMembershipClaims.Resolution claims = SettlementMembershipClaims.resolve(level, villager.getUUID());
		if (!eligible(claims, level.dimension(), villager.getUUID())) return 0;
		// Deliberately no isRemoved(), isAlive() or level.getEntity() gate: AFTER_DEATH is the evidence.
		SettlementCargoRecovery recovery = get(level);
		if (!recovery.available()) {
			CoopCraft.LOGGER.warn("Cannot preserve deceased member cargo: recovery data in {} is unknown or malformed; original data retained (member {})",
				level.dimension().location(), villager.getUUID());
			return 0;
		}
		return recovery.captureDeath(claims, level.dimension(), villager.getUUID(), villager.blockPosition(),
			villager.getName().getString(), villager::getInventory);
	}

	/** Pure death-event entry contract: removed/discarded entities remain eligible by their unique raw claim. */
	int captureDeath(SettlementMembershipClaims.Resolution claims, ResourceKey<Level> dimension,
		UUID deceasedId, BlockPos position, String name, Supplier<? extends Container> inventory) {
		if (!available() || !eligible(claims, dimension, deceasedId) || position == null || inventory == null
			|| records.containsKey(deceasedId)) return 0;
		Container source = inventory.get();
		if (source == null || source.getContainerSize() > MAX_CARGO_SLOTS) return 0;
		var claim = claims.uniqueClaim().orElseThrow();
		// Explicit age-marked food remains real custody in either mode, including unknown formats.
		// Ordinary food keeps the earlier mode-specific personal-ration boundary.
		boolean preserveFood = !SettlementSimulationService.isSurvivalEnabled(claim.data(), claim.settlement());
		CargoRecord record = new CargoRecord(deceasedId, position, safeName(name), new ArrayList<>());
		// Move only actual source contents. Repeated callbacks see the drained inventory.
		for (int slot = 0; slot < source.getContainerSize(); slot++) {
			ItemStack stack = source.getItem(slot);
			boolean agedFood = SettlementFoodAge.isMarked(stack)
				&& SettlementFoodAge.ordinaryFoodType(new ItemStack(stack.getItem())).isPresent();
			if (!SettlementCargo.isMaterial(stack) && !agedFood && !(preserveFood && SettlementFoodCargo.isFood(stack))) continue;
			ItemStack removed = source.removeItem(slot, stack.getCount());
			if (!removed.isEmpty()) record.stacks.add(removed.copy());
		}
		if (record.total() > 0) {
			records.put(deceasedId, record);
			setDirty();
			source.setChanged();
		}
		return record.total();
	}

	private static boolean eligible(SettlementMembershipClaims.Resolution claims, ResourceKey<Level> dimension,
		UUID deceasedId) {
		return claims != null && dimension != null && deceasedId != null
			&& claims.uniqueClaim().filter(claim -> dimension.equals(claim.dimension())
				&& claim.member() != null && deceasedId.equals(claim.member().villagerId())
				&& claim.settlement() != null).isPresent();
	}

	public Optional<Snapshot> inspect(UUID recordId) {
		CargoRecord record = records.get(recordId);
		return record == null ? Optional.empty() : Optional.of(record.snapshot());
	}

	/** Immutable count-only snapshots; neither mutable stacks nor the internal record list escape. */
	public List<Snapshot> nearby(BlockPos position, double radius, int limit) {
		if (position == null || !Double.isFinite(radius) || radius < 0 || limit <= 0) return List.of();
		return records.values().stream().filter(record -> record.total() > 0
			&& record.position.distSqr(position) <= radius * radius)
			.sorted(Comparator.comparingDouble((CargoRecord record) -> record.position.distSqr(position))
				.thenComparing(record -> record.deceasedId))
			.limit(limit).map(CargoRecord::snapshot).toList();
	}

	/** Read-only rescue discovery. The caller supplies height/world-border checks, never chunk/block reads. */
	public List<Snapshot> stranded(Predicate<BlockPos> inBounds, int limit) {
		if (!available() || inBounds == null || limit <= 0) return List.of();
		return records.values().stream().filter(record -> record.total() > 0 && !inBounds.test(record.position))
			.sorted(Comparator.comparing(record -> record.deceasedId))
			.limit(limit).map(CargoRecord::snapshot).toList();
	}

	/**
	 * Caller verifies normal same-dimension arrival within 3 blocks at a loaded/ticking position, or an
	 * explicit authorized rescue of a currently out-of-bounds record from a valid loaded/ticking position.
	 * This storage layer does not grant remote access or modify the original death position.
	 */
	public Result recover(UUID recordId, Container destination, BooleanSupplier atPlace) {
		if (!available()) return new Result(Status.UNAVAILABLE, 0, 0);
		if (recordId == null || destination == null || atPlace == null) return new Result(Status.INVALID, 0, 0);
		if (!atPlace.getAsBoolean()) return new Result(Status.NOT_AT_PLACE, 0, 0);
		CargoRecord record = records.get(recordId);
		if (record == null || record.total() == 0) return new Result(Status.INVALID, 0, 0);
		int moved = 0;
		for (ItemStack stack : record.stacks) {
			int accepted = SettlementStockpile.insert(destination, stack);
			if (accepted > 0) {
				stack.shrink(accepted);
				moved += accepted;
				setDirty();
			}
		}
		record.stacks.removeIf(ItemStack::isEmpty);
		if (record.stacks.isEmpty()) records.remove(recordId);
		return new Result(moved > 0 ? Status.RECOVERED : Status.NO_CAPACITY, moved, record.total());
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		if (!available()) return preservedUnreadable.copy();
		tag.putInt("DataVersion", DATA_VERSION);
		ListTag list = new ListTag();
		for (CargoRecord record : records.values()) {
			CompoundTag entry = new CompoundTag();
			entry.putUUID("Deceased", record.deceasedId);
			entry.putLong("Position", record.position.asLong());
			entry.putString("Name", record.name);
			ListTag stacks = new ListTag();
			for (ItemStack stack : record.stacks) if (!stack.isEmpty()) stacks.add(stack.save(registries));
			entry.put("Stacks", stacks);
			list.add(entry);
		}
		tag.put("Records", list);
		return tag;
	}

	static SettlementCargoRecovery load(CompoundTag tag, HolderLookup.Provider registries) {
		SettlementCargoRecovery data = new SettlementCargoRecovery();
		try {
			if (tag.getInt("DataVersion") != DATA_VERSION || !tag.contains("Records", Tag.TAG_LIST)) {
				return unreadable(tag);
			}
			ListTag list = (ListTag) tag.get("Records");
			if (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) return unreadable(tag);
			for (int index = 0; index < list.size(); index++) {
				CompoundTag entry = list.getCompound(index);
				if (!entry.hasUUID("Deceased") || !entry.contains("Position", Tag.TAG_LONG)
					|| !entry.contains("Stacks", Tag.TAG_LIST) || !entry.contains("Name", Tag.TAG_STRING)) return unreadable(tag);
				UUID id = entry.getUUID("Deceased");
				if (data.records.containsKey(id)) return unreadable(tag);
				List<ItemStack> stacks = new ArrayList<>();
				ListTag savedStacks = (ListTag) entry.get("Stacks");
				if (savedStacks.size() > MAX_CARGO_SLOTS
					|| (!savedStacks.isEmpty() && savedStacks.getElementType() != Tag.TAG_COMPOUND)) return unreadable(tag);
				for (int slot = 0; slot < savedStacks.size(); slot++) {
					ItemStack stack = ItemStack.parseOptional(registries, savedStacks.getCompound(slot));
					// Preserve custody independently of changed datapack tags, but never silently discard bad stacks.
					if (stack.isEmpty()) return unreadable(tag);
					stacks.add(stack);
				}
				data.records.put(id, new CargoRecord(id, BlockPos.of(entry.getLong("Position")),
					safeName(entry.getString("Name")), stacks));
			}
			return data;
		} catch (RuntimeException exception) {
			return unreadable(tag);
		}
	}

	private static SettlementCargoRecovery unreadable(CompoundTag original) {
		SettlementCargoRecovery data = new SettlementCargoRecovery();
		data.preservedUnreadable = original.copy();
		return data;
	}

	private static String safeName(String name) {
		if (name == null) return "";
		StringBuilder safe = new StringBuilder();
		name.codePoints().filter(code -> !Character.isISOControl(code) && Character.getType(code) != Character.FORMAT && code != 0x00a7)
			.limit(80).forEach(safe::appendCodePoint);
		return safe.toString();
	}

	private static final class CargoRecord {
		private final UUID deceasedId;
		private final BlockPos position;
		private final String name;
		private final List<ItemStack> stacks;
		private CargoRecord(UUID deceasedId, BlockPos position, String name, List<ItemStack> stacks) {
			this.deceasedId = deceasedId; this.position = position.immutable(); this.name = name; this.stacks = stacks;
		}
		private int total() { return stacks.stream().mapToInt(ItemStack::getCount).sum(); }
		private Snapshot snapshot() {
			Map<ResourceLocation, Integer> counts = new LinkedHashMap<>();
			for (ItemStack stack : stacks) if (!stack.isEmpty()) {
				counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getCount(), Integer::sum);
			}
			return new Snapshot(deceasedId, deceasedId, position, name, counts.entrySet().stream()
				.map(entry -> new MaterialCount(entry.getKey(), entry.getValue())).toList(), total());
		}
	}
}
