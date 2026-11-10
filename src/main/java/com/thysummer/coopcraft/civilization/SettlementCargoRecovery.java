package com.thysummer.coopcraft.civilization;

import com.thysummer.coopcraft.CoopCraft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
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
import net.minecraft.world.item.Items;
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
	private static final int DATA_VERSION = 3;
	private static final int MAX_CARGO_SLOTS = 8;
	private static final int MAX_RECORD_SLOTS = MAX_CARGO_SLOTS + 1;
	private static final int MAX_PENDING_DEATH_NOTICES = 128;
	private static final Factory<SettlementCargoRecovery> FACTORY = new Factory<>(
		SettlementCargoRecovery::new, SettlementCargoRecovery::load, null);
	private final Map<UUID, CargoRecord> records = new LinkedHashMap<>();
	// The early hook and AFTER_DEATH run synchronously in the same vanilla die invocation.
	// Only the exact dying object may consume its notification; receipts are never persisted.
	private final Map<Object, Integer> pendingDeathNotices = new IdentityHashMap<>();
	private CompoundTag preservedUnreadable;

	/** False means unknown/corrupt stored data is retained verbatim and all mutations fail closed. */
	public boolean available() { return preservedUnreadable == null; }

	public enum Status { UNAVAILABLE, INVALID, NOT_AT_PLACE, NO_CAPACITY, RECOVERED }
	public record Result(Status status, int moved, int remaining) { }
	public record MaterialCount(ResourceLocation itemId, int count) { }
	public record ShearsClaim(UUID camp, int count) { }
	public record Snapshot(UUID recordId, UUID deceasedId, BlockPos position, String name,
		List<MaterialCount> items, int totalCount) {
		public Snapshot { position = position.immutable(); items = List.copyOf(items); }
	}

	public static SettlementCargoRecovery get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
	}

	/** Called just after vanilla commits dead=true, before killer conversion or any equipment loot. */
	public static void captureBeforeDeath(ServerLevel level, Villager villager) {
		if (level == null || villager == null || villager.level() != level || !level.getServer().isSameThread()) return;
		SettlementMembershipClaims.Resolution claims = SettlementMembershipClaims.resolve(level, villager.getUUID());
		if (!eligible(claims, level.dimension(), villager.getUUID())) return;
		SettlementCargoRecovery recovery = get(level);
		if (!recovery.available()) return;
		recovery.captureBeforeDeath(villager, claims, level.dimension(), villager.getUUID(), villager.blockPosition(),
			villager.getName().getString(), villager::getInventory, villager::getMainHandItem,
			stack -> SettlementShearsEquipment.clear(villager, stack));
	}

	/** AFTER_DEATH consumes the early receipt once; the fallback still accepts discarded originals. */
	public static int captureDeath(ServerLevel level, Villager villager) {
		if (level == null || villager == null || villager.level() != level) return 0;
		SettlementCargoRecovery recovery = get(level);
		int captured = recovery.takeDeathNotice(villager);
		if (captured > 0) return captured;
		SettlementMembershipClaims.Resolution claims = SettlementMembershipClaims.resolve(level, villager.getUUID());
		if (!eligible(claims, level.dimension(), villager.getUUID())) return 0;
		// Deliberately no isRemoved(), isAlive() or level.getEntity() gate: AFTER_DEATH is the evidence.
		if (!recovery.available()) {
			CoopCraft.LOGGER.warn("Cannot preserve deceased member cargo: recovery data in {} is unknown or malformed; original data retained (member {})",
				level.dimension().location(), villager.getUUID());
			return 0;
		}
		return recovery.captureDeath(claims, level.dimension(), villager.getUUID(), villager.blockPosition(),
			villager.getName().getString(), villager::getInventory, villager::getMainHandItem,
			stack -> SettlementShearsEquipment.clear(villager, stack));
	}

	int captureBeforeDeath(Object deathIdentity, SettlementMembershipClaims.Resolution claims, ResourceKey<Level> dimension,
		UUID deceasedId, BlockPos position, String name, Supplier<? extends Container> inventory,
		Supplier<ItemStack> mainHand, Predicate<ItemStack> clearHand) {
		if (deathIdentity == null) return 0;
		int captured = captureDeath(claims, dimension, deceasedId, position, name, inventory, mainHand, clearHand);
		if (captured > 0 && !pendingDeathNotices.containsKey(deathIdentity)) {
			// A third-party exception before AFTER_DEATH must not retain unbounded entity references.
			// Eviction drops only a chat receipt; persistent physical custody remains untouched.
			if (pendingDeathNotices.size() >= MAX_PENDING_DEATH_NOTICES) {
				var iterator = pendingDeathNotices.keySet().iterator(); iterator.next(); iterator.remove();
			}
			pendingDeathNotices.put(deathIdentity, captured);
		}
		return captured;
	}

	int takeDeathNotice(Object deathIdentity) {
		Integer count = pendingDeathNotices.remove(deathIdentity);
		return count == null ? 0 : count;
	}

	/** Pure death-event entry contract: removed/discarded entities remain eligible by their unique raw claim. */
	int captureDeath(SettlementMembershipClaims.Resolution claims, ResourceKey<Level> dimension,
		UUID deceasedId, BlockPos position, String name, Supplier<? extends Container> inventory) {
		return captureDeath(claims, dimension, deceasedId, position, name, inventory, () -> ItemStack.EMPTY, stack -> false);
	}

	/** A marked hand is extra real custody. clearHand must remove exactly its expected stack or return false. */
	int captureDeath(SettlementMembershipClaims.Resolution claims, ResourceKey<Level> dimension,
		UUID deceasedId, BlockPos position, String name, Supplier<? extends Container> inventory,
		Supplier<ItemStack> mainHand, Predicate<ItemStack> clearHand) {
		if (!available() || !eligible(claims, dimension, deceasedId) || position == null || inventory == null
			|| mainHand == null || clearHand == null || records.containsKey(deceasedId)) return 0;
		Container source = inventory.get();
		if (source == null || source.getContainerSize() > MAX_CARGO_SLOTS) return 0;
		var claim = claims.uniqueClaim().orElseThrow();
		// Explicit age-marked food remains real custody in either mode, including unknown formats.
		// Potatoes and their poisonous byproduct are real custody in either mode, including old/custom stacks.
		// Other ordinary food keeps the earlier mode-specific personal-ration boundary.
		boolean preserveFood = !SettlementSimulationService.isSurvivalEnabled(claim.data(), claim.settlement());
		CargoRecord record = new CargoRecord(deceasedId, position, safeName(name), claim.settlement().id(), new ArrayList<>());
		// Move only actual source contents. Repeated callbacks see the drained inventory.
		for (int slot = 0; slot < source.getContainerSize(); slot++) {
			ItemStack stack = source.getItem(slot);
			boolean agedFood = SettlementFoodAge.isMarked(stack)
				&& SettlementFoodAge.ordinaryFoodType(new ItemStack(stack.getItem())).isPresent();
			boolean potatoCargo = stack.is(Items.POTATO) || stack.is(Items.POISONOUS_POTATO);
			if (!SettlementCargo.isMaterial(stack) && !ConstructionSupply.isMarked(stack)
				&& !SettlementWorkbenchCargo.isMarked(stack) && !SettlementShearingCargo.isMarked(stack)
				&& !SettlementShearsLoan.isMarked(stack)
				&& !agedFood && !potatoCargo
				&& !(preserveFood && SettlementFoodCargo.isFood(stack))) continue;
			ItemStack removed = source.removeItem(slot, stack.getCount());
			if (!removed.isEmpty()) record.stacks.add(removed.copy());
		}
		ItemStack held = mainHand.get();
		if (SettlementShearsLoan.isMarked(held)) {
			// Preserve unknown/foreign stamps verbatim. No validation, unmarking, new tool or repair.
			ItemStack actual = held.copy();
			if (clearHand.test(held)) record.stacks.add(actual);
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

	/**
	 * Read-only, immutable counts. A tool's own marker cannot prove the deceased's camp: the record
	 * must independently retain that evidence from capture. Migrated records never acquire it by inference.
	 */
	public List<ShearsClaim> reclaimableShears(UUID recordId, ResourceLocation dimension) {
		if (!available() || recordId == null || dimension == null) return List.of();
		CargoRecord record = records.get(recordId);
		if (record == null || record.deathSettlement == null) return List.of();
		int count = 0;
		for (ItemStack stack : record.stacks) {
			if (SettlementShearsLoan.ownedBy(stack, dimension, record.deathSettlement, record.deceasedId)) count++;
		}
		return count == 0 ? List.of() : List.of(new ShearsClaim(record.deathSettlement, count));
	}

	/**
	 * Explicitly reclaims only existing, recognized loans from this actual death record. The caller
	 * supplies the player's 36 main slots and rechecks operator permission, same-dimension death-site
	 * arrival, the explicitly selected camp and the absence of an active borrower on every callback.
	 * Storage and destination must be distinct, non-overlapping and non-reentrant, with the same
	 * synchronous reference-retaining setter contract as SettlementShearsLoan. Ordinary recover keeps
	 * all markers intact; this method never scans a player's already recovered belongings.
	 * Result.remaining counts all cargo still in the record, including quarantined/non-loan items.
	 */
	public Result reclaimShears(UUID recordId, ResourceLocation dimension, UUID camp, Container destination,
		BooleanSupplier authority) {
		if (!available()) return new Result(Status.UNAVAILABLE, 0, 0);
		if (recordId == null || dimension == null || camp == null || destination == null || authority == null)
			return new Result(Status.INVALID, 0, 0);
		CargoRecord record = records.get(recordId);
		if (!authority.getAsBoolean() || !available() || records.get(recordId) != record)
			return new Result(Status.NOT_AT_PLACE, 0, 0);
		if (record == null || !camp.equals(record.deathSettlement)) return new Result(Status.INVALID, 0, 0);
		if (reclaimableShears(recordId, dimension).isEmpty()) return new Result(Status.INVALID, 0, record.total());
		boolean[] stillAuthorized = { true };
		BooleanSupplier currentAuthority = () -> {
			boolean allowed = available() && records.get(recordId) == record && authority.getAsBoolean();
			// An authority check must not make a stale record into a source of new items.
			stillAuthorized[0] = allowed && available() && records.get(recordId) == record;
			return stillAuthorized[0];
		};
		int moved = 0;
		try {
			for (int slot = 0; slot < record.stacks.size(); slot++) {
				if (!SettlementShearsLoan.ownedBy(record.stacks.get(slot), dimension, camp, record.deceasedId)) continue;
				int recordSlot = slot;
				SettlementShearsLoan.Hand source = new SettlementShearsLoan.Hand() {
					@Override public ItemStack get() { return record.stacks.get(recordSlot); }
					@Override public void set(ItemStack stack) {
						if (record.stacks.get(recordSlot) == stack) return;
						record.stacks.set(recordSlot, stack);
						setDirty();
					}
				};
				// The live marked reference is detached, accepted, then unmarked by the existing loan logic.
				if (SettlementShearsLoan.returnFromHand(source, destination, dimension, camp, record.deceasedId, currentAuthority))
					moved++;
				if (!stillAuthorized[0]) break;
			}
		} finally {
			if (records.get(recordId) == record) {
				if (record.stacks.removeIf(ItemStack::isEmpty)) setDirty();
				if (record.stacks.isEmpty()) { records.remove(recordId); setDirty(); }
			}
		}
		return new Result(moved > 0 ? Status.RECOVERED : stillAuthorized[0] ? Status.NO_CAPACITY : Status.NOT_AT_PLACE,
			moved, record.total());
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
			if (record.deathSettlement != null) entry.putUUID("DeathSettlement", record.deathSettlement);
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
			int version = tag.getInt("DataVersion");
			if (!tag.contains("DataVersion", Tag.TAG_INT) || (version != 1 && version != 2 && version != DATA_VERSION)
				|| !tag.contains("Records", Tag.TAG_LIST)) {
				return unreadable(tag);
			}
			ListTag list = (ListTag) tag.get("Records");
			if (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) return unreadable(tag);
			for (int index = 0; index < list.size(); index++) {
				CompoundTag entry = list.getCompound(index);
				if (!entry.hasUUID("Deceased") || !entry.contains("Position", Tag.TAG_LONG)
					|| !entry.contains("Stacks", Tag.TAG_LIST) || !entry.contains("Name", Tag.TAG_STRING)) return unreadable(tag);
				// Old formats never recorded this evidence. A same-named field cannot be promoted to proof.
				if (entry.contains("DeathSettlement") && (version < 3 || !entry.hasUUID("DeathSettlement"))) return unreadable(tag);
				UUID deathSettlement = entry.hasUUID("DeathSettlement") ? entry.getUUID("DeathSettlement") : null;
				UUID id = entry.getUUID("Deceased");
				if (data.records.containsKey(id)) return unreadable(tag);
				List<ItemStack> stacks = new ArrayList<>();
				ListTag savedStacks = (ListTag) entry.get("Stacks");
				if (savedStacks.size() > (version == 1 ? MAX_CARGO_SLOTS : MAX_RECORD_SLOTS)
					|| (!savedStacks.isEmpty() && savedStacks.getElementType() != Tag.TAG_COMPOUND)) return unreadable(tag);
				for (int slot = 0; slot < savedStacks.size(); slot++) {
					ItemStack stack = ItemStack.parseOptional(registries, savedStacks.getCompound(slot));
					// Preserve custody independently of changed datapack tags, but never silently discard bad stacks.
					if (stack.isEmpty()) return unreadable(tag);
					stacks.add(stack);
				}
				data.records.put(id, new CargoRecord(id, BlockPos.of(entry.getLong("Position")),
					safeName(entry.getString("Name")), deathSettlement, stacks));
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
		private final UUID deathSettlement;
		private final List<ItemStack> stacks;
		private CargoRecord(UUID deceasedId, BlockPos position, String name, UUID deathSettlement, List<ItemStack> stacks) {
			this.deceasedId = deceasedId; this.position = position.immutable(); this.name = name; this.stacks = stacks;
			this.deathSettlement = deathSettlement;
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
