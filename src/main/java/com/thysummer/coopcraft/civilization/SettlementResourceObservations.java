package com.thysummer.coopcraft.civilization;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Historical observations, never gathering permission or a task reservation. Dimension-local. */
final class SettlementResourceObservations {
	static final int MAX_PER_SETTLEMENT = 64;
	static final int MAX_PER_DIMENSION = 1024;
	private static final int VERSION = 1;
	private static final Set<String> ROOT_KEYS = Set.of("Version", "Records");
	private static final Set<String> RECORD_KEYS = Set.of("SettlementId", "Position", "Kind", "Source", "State");

	enum Kind { SWEET_BERRY }
	enum Source { UNKNOWN }
	enum State { CANDIDATE, SUPPRESSED }
	enum Change { CHANGED, UNCHANGED, NOT_FOUND, NOT_SUPPRESSED, PRESERVED_UNSUPPORTED }

	record Observation(BlockPos position, Kind kind, Source source, State state) {
		Observation { position = position.immutable(); }
		String id() { return "berry-" + Long.toUnsignedString(position.asLong(), 16); }
	}

	private final Map<UUID, LinkedHashMap<BlockPos, Observation>> records = new LinkedHashMap<>();
	private int size;
	// Preserve unsupported/malformed data exactly, including an unexpected NBT tag type.
	private Tag preserved;

	boolean blocked() { return preserved != null; }
	List<Observation> of(UUID settlement) {
		var found = records.get(settlement);
		return found == null ? List.of() : List.copyOf(found.values());
	}

	boolean suppressed(UUID settlement, BlockPos position) {
		var found = records.get(settlement);
		return found != null && found.containsKey(position) && found.get(position).state() == State.SUPPRESSED;
	}

	/** Only an observation can be added. No natural-source inference and no authorization API. */
	boolean observe(UUID settlement, BlockPos position, boolean ripeBerry) {
		if (blocked() || suppressed(settlement, position)) return false;
		var found = records.get(settlement);
		if (!ripeBerry) {
			if (found == null || found.remove(position) == null) return false;
			size--;
			if (found.isEmpty()) records.remove(settlement);
			return true;
		}
		if (found != null && found.containsKey(position)) return false;
		if (size >= MAX_PER_DIMENSION || found != null && found.size() >= MAX_PER_SETTLEMENT) return false;
		records.computeIfAbsent(settlement, ignored -> new LinkedHashMap<>()).put(position.immutable(),
			new Observation(position, Kind.SWEET_BERRY, Source.UNKNOWN, State.CANDIDATE));
		size++;
		return true;
	}

	Change suppress(UUID settlement, String id) {
		if (blocked()) return Change.PRESERVED_UNSUPPORTED;
		var found = find(settlement, id);
		if (found == null) return Change.NOT_FOUND;
		if (found.state() == State.SUPPRESSED) return Change.UNCHANGED;
		// Replace in place, even when either cap is full. Never evict a veto to make room.
		records.get(settlement).put(found.position(), new Observation(found.position(), found.kind(), found.source(), State.SUPPRESSED));
		return Change.CHANGED;
	}

	Change resume(UUID settlement, String id) {
		if (blocked()) return Change.PRESERVED_UNSUPPORTED;
		var found = find(settlement, id);
		if (found == null) return Change.NOT_FOUND;
		if (found.state() != State.SUPPRESSED) return Change.NOT_SUPPRESSED;
		var entries = records.get(settlement);
		entries.remove(found.position());
		if (entries.isEmpty()) records.remove(settlement);
		size--;
		// Explicitly remove the veto; a fresh observation is required to create a candidate again.
		return Change.CHANGED;
	}

	private Observation find(UUID settlement, String id) {
		return of(settlement).stream().filter(record -> record.id().equals(id)).findFirst().orElse(null);
	}

	Tag save() {
		if (blocked()) return preserved.copy();
		CompoundTag tag = new CompoundTag();
		tag.putInt("Version", VERSION);
		ListTag list = new ListTag();
		for (var camp : records.entrySet()) for (var observation : camp.getValue().values()) {
			CompoundTag entry = new CompoundTag();
			entry.putUUID("SettlementId", camp.getKey());
			entry.putLong("Position", observation.position().asLong());
			entry.putString("Kind", observation.kind().name());
			entry.putString("Source", observation.source().name());
			entry.putString("State", observation.state().name());
			list.add(entry);
		}
		tag.put("Records", list);
		return tag;
	}

	static SettlementResourceObservations load(Tag raw) {
		var result = new SettlementResourceObservations();
		if (raw == null) return result; // Legacy worlds start with no observations.
		if (raw instanceof CompoundTag tag && tag.getAllKeys().equals(ROOT_KEYS)
			&& tag.contains("Version", Tag.TAG_INT) && tag.getInt("Version") == VERSION
			&& tag.get("Records") instanceof ListTag list
			&& (list.isEmpty() || list.getElementType() == Tag.TAG_COMPOUND)
			&& list.size() <= MAX_PER_DIMENSION) {
			boolean valid = true;
			for (int i = 0; i < list.size(); i++) {
				CompoundTag entry = list.getCompound(i);
				if (!entry.getAllKeys().equals(RECORD_KEYS) || !entry.hasUUID("SettlementId")
					|| !entry.contains("Position", Tag.TAG_LONG)
					|| !entry.contains("Kind", Tag.TAG_STRING) || !entry.getString("Kind").equals(Kind.SWEET_BERRY.name())
					|| !entry.contains("Source", Tag.TAG_STRING) || !entry.getString("Source").equals(Source.UNKNOWN.name())
					|| !entry.contains("State", Tag.TAG_STRING)
					|| !(entry.getString("State").equals(State.CANDIDATE.name()) || entry.getString("State").equals(State.SUPPRESSED.name()))) {
					valid = false; break;
				}
				UUID camp = entry.getUUID("SettlementId");
				BlockPos pos = BlockPos.of(entry.getLong("Position"));
				var entries = result.records.computeIfAbsent(camp, ignored -> new LinkedHashMap<>());
				if (entries.size() >= MAX_PER_SETTLEMENT || entries.containsKey(pos)) { valid = false; break; }
				entries.put(pos, new Observation(pos, Kind.SWEET_BERRY, Source.UNKNOWN, State.valueOf(entry.getString("State"))));
				result.size++;
			}
			if (valid) return result;
		}
		result.records.clear();
		result.size = 0;
		result.preserved = raw.copy();
		return result;
	}
}
