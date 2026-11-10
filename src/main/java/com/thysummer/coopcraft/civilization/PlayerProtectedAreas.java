package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Immutable, dimension-local vetoes on resident block changes, independent of settlements.
 * A clear result is not gathering permission. Every X/Z rectangle covers the full world height.
 */
public final class PlayerProtectedAreas {
	public static final int MAX_AREAS = 128;
	public static final int MAX_PER_OWNER = 16;
	public static final int MAX_EDGE = 128;
	public static final int MAX_NAME_LENGTH = 48;
	public static final int MAX_COORDINATE = 29_999_984;
	private static final int VERSION = 1;
	private static final int REQUIRED_SAVED_DATA_VERSION = 21;
	private static final Set<String> ROOT_KEYS = Set.of("Version", "Areas");
	private static final Set<String> AREA_KEYS = Set.of("Id", "OwnerId", "Name", "MinX", "MinZ", "MaxX", "MaxZ");

	public enum Status {
		ADDED, REMOVED, NOT_FOUND, NOT_OWNER, INVALID, DIMENSION_LIMIT, OWNER_LIMIT, DATA_UNAVAILABLE
	}

	/** area is present only when the addition succeeded. */
	public record AddResult(Status status, Area area) { }

	public record Area(UUID id, UUID ownerId, String name, int minX, int minZ, int maxX, int maxZ) {
		public Area {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(ownerId, "ownerId");
			if (!validName(name) || minX > maxX || minZ > maxZ || !validBounds(minX, minZ, maxX, maxZ)) {
				throw new IllegalArgumentException("Invalid protected area");
			}
		}

		public int width() { return (int) ((long) maxX - minX + 1L); }
		public int depth() { return (int) ((long) maxZ - minZ + 1L); }

		public boolean contains(BlockPos position) {
			return position != null && contains(position.getX(), position.getZ());
		}

		public boolean contains(int x, int z) {
			return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
		}

		/** Inclusive intersection, accepting either corner order and any query rectangle size. */
		public boolean intersects(int x1, int z1, int x2, int z2) {
			return minX <= Math.max(x1, x2) && maxX >= Math.min(x1, x2)
				&& minZ <= Math.max(z1, z2) && maxZ >= Math.min(z1, z2);
		}
	}

	// Updates return a fresh value so protectedAreas() cannot bypass SavedData dirty tracking.
	record Update(PlayerProtectedAreas value, Status status, Area area) { }
	private final List<Area> areas;
	// Unsupported or malformed data is retained without exposing a mutable NBT alias.
	private final Tag preserved;
	// An unexpectedly absent subtree must stay absent on save, never become valid empty data.
	private final boolean missingRequired;

	PlayerProtectedAreas() { this(List.of(), null); }

	private PlayerProtectedAreas(List<Area> areas, Tag preserved) {
		this(areas, preserved, false);
	}

	private PlayerProtectedAreas(List<Area> areas, Tag preserved, boolean missingRequired) {
		this.areas = List.copyOf(areas);
		this.preserved = preserved == null ? null : preserved.copy();
		this.missingRequired = missingRequired;
	}

	/** Unavailable data vetoes every resident block change in this SavedData's dimension. */
	public boolean blocked() { return preserved != null || missingRequired; }
	public int size() { return areas.size(); }
	public List<Area> all() { return areas; }

	public Optional<Area> find(UUID id) {
		for (Area area : areas) if (area.id().equals(id)) return Optional.of(area);
		return Optional.empty();
	}

	public int ownedCount(UUID ownerId) {
		int count = 0;
		for (Area area : areas) if (area.ownerId().equals(ownerId)) count++;
		return count;
	}

	/** Hot-path lookup: bounded scan, no list construction, world access, or cached permission. */
	public boolean contains(BlockPos position) {
		if (blocked() || position == null) return true;
		for (Area area : areas) if (area.contains(position)) return true;
		return false;
	}

	public boolean intersects(int x1, int z1, int x2, int z2) {
		if (blocked()) return true;
		for (Area area : areas) if (area.intersects(x1, z1, x2, z2)) return true;
		return false;
	}

	public static String normalizeName(String name) { return name == null ? null : name.strip(); }

	/** Names are canonical, nonblank, single-line labels of at most 48 Unicode code points. */
	public static boolean validName(String name) {
		return name != null && !name.isBlank() && name.length() <= MAX_NAME_LENGTH * 2
			&& name.equals(normalizeName(name)) && name.codePointCount(0, name.length()) <= MAX_NAME_LENGTH
			&& name.codePoints().allMatch(PlayerProtectedAreas::validNameCodePoint);
	}

	private static boolean validNameCodePoint(int codePoint) {
		int type = Character.getType(codePoint);
		return !Character.isISOControl(codePoint) && type != Character.FORMAT && type != Character.SURROGATE
			&& type != Character.LINE_SEPARATOR && type != Character.PARAGRAPH_SEPARATOR;
	}

	public static boolean validBounds(int x1, int z1, int x2, int z2) {
		return inBounds(x1) && inBounds(z1) && inBounds(x2) && inBounds(z2)
			&& (long) Math.max(x1, x2) - Math.min(x1, x2) + 1L <= MAX_EDGE
			&& (long) Math.max(z1, z2) - Math.min(z1, z2) + 1L <= MAX_EDGE;
	}

	private static boolean inBounds(int coordinate) {
		return coordinate >= -MAX_COORDINATE && coordinate <= MAX_COORDINATE;
	}

	Update add(UUID ownerId, String name, int x1, int z1, int x2, int z2) {
		if (blocked()) return unchanged(Status.DATA_UNAVAILABLE);
		name = normalizeName(name);
		if (ownerId == null || !validName(name) || !validBounds(x1, z1, x2, z2)) return unchanged(Status.INVALID);
		if (size() >= MAX_AREAS) return unchanged(Status.DIMENSION_LIMIT);
		if (ownedCount(ownerId) >= MAX_PER_OWNER) return unchanged(Status.OWNER_LIMIT);
		UUID id = UUID.randomUUID();
		// Even an improbable collision must never replace an existing protection.
		if (find(id).isPresent()) return unchanged(Status.INVALID);
		Area area = new Area(id, ownerId, name, Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
		List<Area> updated = new ArrayList<>(areas);
		updated.add(area);
		return new Update(new PlayerProtectedAreas(updated, null), Status.ADDED, area);
	}

	Update remove(UUID id, UUID requesterId, boolean operator) {
		if (blocked()) return unchanged(Status.DATA_UNAVAILABLE);
		if (id == null || requesterId == null) return unchanged(Status.INVALID);
		Area area = find(id).orElse(null);
		if (area == null) return unchanged(Status.NOT_FOUND);
		if (!operator && !area.ownerId().equals(requesterId)) return unchanged(Status.NOT_OWNER);
		List<Area> updated = new ArrayList<>(areas);
		updated.remove(area);
		return new Update(new PlayerProtectedAreas(updated, null), Status.REMOVED, area);
	}

	private Update unchanged(Status status) { return new Update(this, status, null); }

	Tag save() {
		if (missingRequired) return null;
		if (preserved != null) return preserved.copy();
		CompoundTag root = new CompoundTag();
		root.putInt("Version", VERSION);
		ListTag list = new ListTag();
		for (Area area : areas) {
			CompoundTag entry = new CompoundTag();
			entry.putUUID("Id", area.id());
			entry.putUUID("OwnerId", area.ownerId());
			entry.putString("Name", area.name());
			entry.putInt("MinX", area.minX());
			entry.putInt("MinZ", area.minZ());
			entry.putInt("MaxX", area.maxX());
			entry.putInt("MaxZ", area.maxZ());
			list.add(entry);
		}
		root.put("Areas", list);
		return root;
	}

	static PlayerProtectedAreas load(Tag raw, Tag savedDataVersion) {
		if (raw == null) {
			// Only a recognizable legacy save may omit protection. Numeric coercion would hide bad types.
			boolean legacy = savedDataVersion == null || savedDataVersion instanceof IntTag version
				&& version.getAsInt() >= 0 && version.getAsInt() < REQUIRED_SAVED_DATA_VERSION;
			return legacy ? new PlayerProtectedAreas() : new PlayerProtectedAreas(List.of(), null, true);
		}
		if (raw instanceof CompoundTag root && root.getAllKeys().equals(ROOT_KEYS)
				&& root.contains("Version", Tag.TAG_INT) && root.getInt("Version") == VERSION
				&& root.get("Areas") instanceof ListTag list
				&& (list.getElementType() == Tag.TAG_COMPOUND || list.isEmpty() && list.getElementType() == Tag.TAG_END)
				&& list.size() <= MAX_AREAS) {
			List<Area> decoded = new ArrayList<>(list.size());
			Set<UUID> ids = new HashSet<>();
			Map<UUID, Integer> ownerCounts = new HashMap<>();
			for (int i = 0; i < list.size(); i++) {
				CompoundTag entry = list.getCompound(i);
				if (!entry.getAllKeys().equals(AREA_KEYS) || !entry.hasUUID("Id") || !entry.hasUUID("OwnerId")
						|| !entry.contains("Name", Tag.TAG_STRING) || !validName(entry.getString("Name"))
						|| !entry.contains("MinX", Tag.TAG_INT) || !entry.contains("MinZ", Tag.TAG_INT)
						|| !entry.contains("MaxX", Tag.TAG_INT) || !entry.contains("MaxZ", Tag.TAG_INT)) {
					return preserve(raw);
				}
				int minX = entry.getInt("MinX"), minZ = entry.getInt("MinZ");
				int maxX = entry.getInt("MaxX"), maxZ = entry.getInt("MaxZ");
				UUID id = entry.getUUID("Id"), ownerId = entry.getUUID("OwnerId");
				if (minX > maxX || minZ > maxZ || !validBounds(minX, minZ, maxX, maxZ)
						|| !ids.add(id) || ownerCounts.merge(ownerId, 1, Integer::sum) > MAX_PER_OWNER) {
					return preserve(raw);
				}
				decoded.add(new Area(id, ownerId, entry.getString("Name"), minX, minZ, maxX, maxZ));
			}
			return new PlayerProtectedAreas(decoded, null);
		}
		return preserve(raw);
	}

	private static PlayerProtectedAreas preserve(Tag raw) { return new PlayerProtectedAreas(List.of(), raw); }
}
