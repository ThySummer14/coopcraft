package com.thysummer.coopcraft.waystation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 全服共享的驿站网络。存在主世界存档里，所有维度共用一份。
 */
public final class WaystationSavedData extends SavedData {
	public static final String DATA_NAME = "coopcraft_waystations";
	private static final String ENTRIES_KEY = "Entries";
	private static final String NEXT_ID_KEY = "NextId";
	public static final int MAX_WAYSTATIONS = 64;
	public static final int MAX_NAME_LENGTH = 32;

	private static final Factory<WaystationSavedData> FACTORY = new Factory<>(
		WaystationSavedData::new,
		WaystationSavedData::load,
		null
	);

	private final Map<Integer, Entry> entries = new LinkedHashMap<>();
	private int nextId = 1;

	public static WaystationSavedData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
	}

	public List<Entry> entries() {
		return List.copyOf(entries.values());
	}

	public Optional<Entry> find(int id) {
		return Optional.ofNullable(entries.get(id));
	}

	public Optional<Entry> findAt(String dimension, BlockPos pos) {
		return entries.values().stream()
			.filter(entry -> entry.dimension().equals(dimension) && entry.pos().equals(pos))
			.findFirst();
	}

	public boolean hasNear(String dimension, BlockPos pos, int radius) {
		int radiusSquared = radius * radius;
		return entries.values().stream()
			.anyMatch(entry -> entry.dimension().equals(dimension)
				&& entry.pos().distSqr(pos) <= radiusSquared);
	}

	public boolean isFull() {
		return entries.size() >= MAX_WAYSTATIONS;
	}

	public Entry add(String name, String dimension, BlockPos pos, UUID settlementId,
			String settlementName, UUID owner, String ownerName) {
		Entry entry = new Entry(
			nextId++,
			trim(name),
			Objects.requireNonNull(dimension),
			Objects.requireNonNull(pos),
			Objects.requireNonNull(settlementId),
			Objects.requireNonNull(settlementName),
			Objects.requireNonNull(owner),
			Objects.requireNonNull(ownerName)
		);
		entries.put(entry.id(), entry);
		setDirty();
		return entry;
	}

	public boolean remove(int id) {
		if (entries.remove(id) == null) {
			return false;
		}
		setDirty();
		return true;
	}

	private static String trim(String name) {
		String cleaned = name == null ? "" : name.strip();
		if (cleaned.isEmpty()) {
			cleaned = "驿站";
		}
		return cleaned.length() > MAX_NAME_LENGTH ? cleaned.substring(0, MAX_NAME_LENGTH) : cleaned;
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		ListTag list = new ListTag();
		for (Entry entry : entries.values()) {
			CompoundTag entryTag = new CompoundTag();
			entryTag.putInt("Id", entry.id());
			entryTag.putString("Name", entry.name());
			entryTag.putString("Dimension", entry.dimension());
			entryTag.putLong("Pos", entry.pos().asLong());
			entryTag.putUUID("SettlementId", entry.settlementId());
			entryTag.putString("SettlementName", entry.settlementName());
			entryTag.putUUID("Owner", entry.owner());
			entryTag.putString("OwnerName", entry.ownerName());
			list.add(entryTag);
		}
		tag.put(ENTRIES_KEY, list);
		tag.putInt(NEXT_ID_KEY, nextId);
		return tag;
	}

	static WaystationSavedData loadForTest(CompoundTag tag) {
		return load(tag, null);
	}

	private static WaystationSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
		WaystationSavedData data = new WaystationSavedData();
		ListTag list = tag.getList(ENTRIES_KEY, Tag.TAG_COMPOUND);
		int highestId = 0;
		for (int i = 0; i < list.size(); i++) {
			CompoundTag entryTag = list.getCompound(i);
			Entry entry = new Entry(
				entryTag.getInt("Id"),
				entryTag.getString("Name"),
				entryTag.getString("Dimension"),
				BlockPos.of(entryTag.getLong("Pos")),
				entryTag.getUUID("SettlementId"),
				entryTag.getString("SettlementName"),
				entryTag.getUUID("Owner"),
				entryTag.getString("OwnerName")
			);
			data.entries.put(entry.id(), entry);
			highestId = Math.max(highestId, entry.id());
		}
		data.nextId = Math.max(tag.getInt(NEXT_ID_KEY), highestId + 1);
		return data;
	}

	public record Entry(
		int id,
		String name,
		String dimension,
		BlockPos pos,
		UUID settlementId,
		String settlementName,
		UUID owner,
		String ownerName
	) {
		public Entry {
			Objects.requireNonNull(name, "name");
			Objects.requireNonNull(dimension, "dimension");
			Objects.requireNonNull(pos, "pos");
			Objects.requireNonNull(settlementId, "settlementId");
			Objects.requireNonNull(settlementName, "settlementName");
			Objects.requireNonNull(owner, "owner");
			Objects.requireNonNull(ownerName, "ownerName");
		}
	}
}
