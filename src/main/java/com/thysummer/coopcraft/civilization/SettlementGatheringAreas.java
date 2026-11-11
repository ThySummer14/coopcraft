package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Explicit, dimension-local wood/stone permissions. Observations never create a permit. */
final class SettlementGatheringAreas {
	static final int MAX_RADIUS = 8;
	static final int MAX_PER_DIMENSION = 128;
	private static final int MAX_COORDINATE = 29_999_984;
	private static final int VERSION = 1;
	private static final Set<String> ROOT_KEYS = Set.of("Version", "Areas");
	private static final Set<String> AREA_KEYS = Set.of("Id", "SettlementId", "Authorizer",
		"CenterX", "CenterZ", "Radius", "Mode");

	enum Mode {
		WOOD, STONE, BOTH;

		boolean permits(SettlementResourceObservations.Kind kind) {
			return kind == SettlementResourceObservations.Kind.OAK_LOG && this != STONE
				|| kind == SettlementResourceObservations.Kind.STONE && this != WOOD;
		}
	}

	enum Status { CREATED, REPLACED, UNCHANGED, REMOVED, NOT_FOUND, INVALID, LIMIT, PRESERVED_UNSUPPORTED }

	/** Successful and unchanged permits return the immutable area, including its authorization identity. */
	record PermitResult(Status status, Area area) { }

	record Area(UUID id, UUID settlementId, UUID authorizer, int centerX, int centerZ, int radius, Mode mode) {
		Area {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(settlementId, "settlementId");
			Objects.requireNonNull(authorizer, "authorizer");
			Objects.requireNonNull(mode, "mode");
			if (!validBounds(centerX, centerZ, radius)) throw new IllegalArgumentException("Invalid gathering area");
		}

		/** Inclusive square in X/Z, across the full height of this dimension. */
		boolean contains(BlockPos position) {
			return position != null && Math.abs((long) position.getX() - centerX) <= radius
				&& Math.abs((long) position.getZ() - centerZ) <= radius;
		}
	}

	// Copy-on-write prevents callers from bypassing SavedData dirty tracking.
	record Update(SettlementGatheringAreas value, Status status, Area area) { }
	private final List<Area> areas;
	private final Tag preserved;

	SettlementGatheringAreas() { this(List.of(), null); }
	private SettlementGatheringAreas(List<Area> areas, Tag preserved) {
		this.areas = List.copyOf(areas);
		this.preserved = preserved == null ? null : preserved.copy();
	}

	boolean blocked() { return preserved != null; }

	Optional<Area> of(UUID settlementId) {
		for (Area area : areas) if (area.settlementId().equals(settlementId)) return Optional.of(area);
		return Optional.empty();
	}

	Update permit(UUID settlementId, UUID authorizer, BlockPos center, int radius, Mode mode) {
		if (blocked()) return unchanged(Status.PRESERVED_UNSUPPORTED);
		if (settlementId == null || authorizer == null || center == null || mode == null
				|| !validBounds(center.getX(), center.getZ(), radius)) return unchanged(Status.INVALID);
		Area previous = of(settlementId).orElse(null);
		if (previous != null && previous.authorizer().equals(authorizer) && previous.centerX() == center.getX()
				&& previous.centerZ() == center.getZ() && previous.radius() == radius && previous.mode() == mode) {
			return new Update(this, Status.UNCHANGED, previous);
		}
		if (previous == null && areas.size() >= MAX_PER_DIMENSION) return unchanged(Status.LIMIT);
		UUID id = UUID.randomUUID();
		// Collisions may neither reuse an authorization identity nor silently replace another permit.
		for (Area area : areas) if (area.id().equals(id)) return unchanged(Status.INVALID);
		Area area = new Area(id, settlementId, authorizer, center.getX(), center.getZ(), radius, mode);
		List<Area> updated = new ArrayList<>(areas);
		if (previous == null) updated.add(area);
		else updated.set(updated.indexOf(previous), area);
		return new Update(new SettlementGatheringAreas(updated, null),
			previous == null ? Status.CREATED : Status.REPLACED, area);
	}

	Update revoke(UUID settlementId) {
		if (blocked()) return unchanged(Status.PRESERVED_UNSUPPORTED);
		if (settlementId == null) return unchanged(Status.INVALID);
		Area previous = of(settlementId).orElse(null);
		if (previous == null) return unchanged(Status.NOT_FOUND);
		List<Area> updated = new ArrayList<>(areas);
		updated.remove(previous);
		return new Update(new SettlementGatheringAreas(updated, null), Status.REMOVED, previous);
	}

	private Update unchanged(Status status) { return new Update(this, status, null); }

	static boolean validBounds(int centerX, int centerZ, int radius) {
		return radius >= 1 && radius <= MAX_RADIUS
			&& (long) centerX - radius >= -MAX_COORDINATE && (long) centerX + radius <= MAX_COORDINATE
			&& (long) centerZ - radius >= -MAX_COORDINATE && (long) centerZ + radius <= MAX_COORDINATE;
	}

	static boolean validRange(Settlement settlement, BlockPos center, int radius) {
		return settlement != null && center != null && validBounds(center.getX(), center.getZ(), radius)
			&& withinCamp(settlement, center.getX(), center.getZ(), radius);
	}

	/** The farthest square corner must stay within the camp's 64-block horizontal radius. */
	static boolean withinCamp(Settlement settlement, int x, int z, int radius) {
		long dx = Math.abs((long) x - settlement.center().getX()) + radius;
		long dz = Math.abs((long) z - settlement.center().getZ()) + radius;
		return dx <= 64 && dz <= 64 && dx * dx + dz * dz <= 64L * 64L;
	}

	Tag save() {
		if (preserved != null) return preserved.copy();
		CompoundTag root = new CompoundTag();
		root.putInt("Version", VERSION);
		ListTag list = new ListTag();
		for (Area area : areas) {
			CompoundTag entry = new CompoundTag();
			entry.putUUID("Id", area.id());
			entry.putUUID("SettlementId", area.settlementId());
			entry.putUUID("Authorizer", area.authorizer());
			entry.putInt("CenterX", area.centerX());
			entry.putInt("CenterZ", area.centerZ());
			entry.putInt("Radius", area.radius());
			entry.putString("Mode", area.mode().name());
			list.add(entry);
		}
		root.put("Areas", list);
		return root;
	}

	static SettlementGatheringAreas load(Tag raw) {
		if (raw == null) return new SettlementGatheringAreas();
		try {
			if (!(raw instanceof CompoundTag root) || !root.getAllKeys().equals(ROOT_KEYS)
					|| !root.contains("Version", Tag.TAG_INT) || root.getInt("Version") != VERSION
					|| !(root.get("Areas") instanceof ListTag list)
					|| !(list.getElementType() == Tag.TAG_COMPOUND || list.isEmpty() && list.getElementType() == Tag.TAG_END)
					|| list.size() > MAX_PER_DIMENSION) return preserve(raw);
			List<Area> decoded = new ArrayList<>(list.size());
			Set<UUID> ids = new HashSet<>();
			Set<UUID> settlements = new HashSet<>();
			for (int i = 0; i < list.size(); i++) {
				CompoundTag entry = list.getCompound(i);
				if (!entry.getAllKeys().equals(AREA_KEYS) || !entry.hasUUID("Id") || !entry.hasUUID("SettlementId")
						|| !entry.hasUUID("Authorizer") || !entry.contains("CenterX", Tag.TAG_INT)
						|| !entry.contains("CenterZ", Tag.TAG_INT) || !entry.contains("Radius", Tag.TAG_INT)
						|| !entry.contains("Mode", Tag.TAG_STRING)) return preserve(raw);
				UUID id = entry.getUUID("Id"), settlementId = entry.getUUID("SettlementId");
				int x = entry.getInt("CenterX"), z = entry.getInt("CenterZ"), radius = entry.getInt("Radius");
				if (!validBounds(x, z, radius) || !ids.add(id) || !settlements.add(settlementId)) return preserve(raw);
				decoded.add(new Area(id, settlementId, entry.getUUID("Authorizer"), x, z, radius, Mode.valueOf(entry.getString("Mode"))));
			}
			return new SettlementGatheringAreas(decoded, null);
		} catch (RuntimeException malformed) {
			// Keep the entire raw subtree if a decoder cannot interpret it safely.
			return preserve(raw);
		}
	}

	private static SettlementGatheringAreas preserve(Tag raw) { return new SettlementGatheringAreas(List.of(), raw); }
}
