package com.thysummer.coopcraft.waypoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.thysummer.coopcraft.net.WaypointListPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 全队共享的路径点。
 *
 * <p>存在主世界的存档里，所有维度共用一份 —— 路径点本来就带着维度 id，
 * 按世界各存一份只会让下界和下界之外的列表对不上。
 */
public final class WaypointSavedData extends SavedData {
	public static final String DATA_NAME = "coopcraft_waypoints";
	private static final String ENTRIES_KEY = "Entries";
	private static final String NEXT_ID_KEY = "NextId";
	/** 全服上限，防止有人把列表刷成几百条把包撑爆。 */
	public static final int MAX_WAYPOINTS = 200;
	public static final int MAX_NAME_LENGTH = 24;

	private static final Factory<WaypointSavedData> FACTORY = new Factory<>(
		WaypointSavedData::new,
		WaypointSavedData::load,
		null
	);

	private final Map<Integer, WaypointListPayload.Entry> entries = new LinkedHashMap<>();
	private int nextId = 1;

	public static WaypointSavedData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
	}

	public List<WaypointListPayload.Entry> entries() {
		return List.copyOf(entries.values());
	}

	public Optional<WaypointListPayload.Entry> find(int id) {
		return Optional.ofNullable(entries.get(id));
	}

	/**
	 * 看这个位置是否已经有路径点了。
	 *
	 * <p>同一个位置重复点两次没有意义，还会让列表里出现两条一模一样的记录，
	 * 所以合并成一米见方的格子去重。
	 */
	public Optional<WaypointListPayload.Entry> findNear(String dimension, BlockPos pos) {
		return entries.values().stream()
			.filter(entry -> entry.dimension().equals(dimension))
			.filter(entry -> Math.abs(entry.x() - pos.getX()) <= 1
				&& Math.abs(entry.y() - pos.getY()) <= 1
				&& Math.abs(entry.z() - pos.getZ()) <= 1)
			.findFirst();
	}

	public boolean isFull() {
		return entries.size() >= MAX_WAYPOINTS;
	}

	public WaypointListPayload.Entry add(String name, String dimension, BlockPos pos, String owner) {
		WaypointListPayload.Entry entry = new WaypointListPayload.Entry(
			nextId++,
			trim(name),
			dimension,
			pos.getX(),
			pos.getY(),
			pos.getZ(),
			owner
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
			cleaned = "?";
		}
		return cleaned.length() > MAX_NAME_LENGTH ? cleaned.substring(0, MAX_NAME_LENGTH) : cleaned;
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		ListTag list = new ListTag();
		for (WaypointListPayload.Entry entry : entries.values()) {
			CompoundTag entryTag = new CompoundTag();
			entryTag.putInt("Id", entry.id());
			entryTag.putString("Name", entry.name());
			entryTag.putString("Dimension", entry.dimension());
			entryTag.putInt("X", entry.x());
			entryTag.putInt("Y", entry.y());
			entryTag.putInt("Z", entry.z());
			entryTag.putString("Owner", entry.owner());
			list.add(entryTag);
		}
		tag.put(ENTRIES_KEY, list);
		tag.putInt(NEXT_ID_KEY, nextId);
		return tag;
	}

	static WaypointSavedData loadForTest(CompoundTag tag) {
		return load(tag, null);
	}

	private static WaypointSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
		WaypointSavedData data = new WaypointSavedData();
		ListTag list = tag.getList(ENTRIES_KEY, Tag.TAG_COMPOUND);
		int highestId = 0;
		for (int i = 0; i < list.size(); i++) {
			CompoundTag entryTag = list.getCompound(i);
			WaypointListPayload.Entry entry = new WaypointListPayload.Entry(
				entryTag.getInt("Id"),
				entryTag.getString("Name"),
				entryTag.getString("Dimension"),
				entryTag.getInt("X"),
				entryTag.getInt("Y"),
				entryTag.getInt("Z"),
				entryTag.getString("Owner")
			);
			data.entries.put(entry.id(), entry);
			highestId = Math.max(highestId, entry.id());
		}
		data.nextId = Math.max(tag.getInt(NEXT_ID_KEY), highestId + 1);
		return data;
	}

	/** 给命令补全用：按名字找最近的几条。 */
	public List<WaypointListPayload.Entry> matching(String prefix) {
		List<WaypointListPayload.Entry> result = new ArrayList<>();
		for (WaypointListPayload.Entry entry : entries.values()) {
			if (entry.name().startsWith(prefix)) {
				result.add(entry);
			}
		}
		return result;
	}
}
