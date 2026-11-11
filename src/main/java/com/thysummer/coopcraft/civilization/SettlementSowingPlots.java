package com.thysummer.coopcraft.civilization;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;

/** Dimension-local, explicit consent for one crop cell. Never creates legacy resource markers. */
public final class SettlementSowingPlots {
	public static final int MAX_PER_SETTLEMENT = 32, MAX_PER_DIMENSION = 128;
	public enum Status { CREATED, UNCHANGED, REMOVED, NOT_FOUND, OTHER_SETTLEMENT, INVALID, LIMIT, PRESERVED_UNSUPPORTED }
	public record Permit(UUID id, UUID campId, BlockPos position) {
		public Permit {
			Objects.requireNonNull(id); Objects.requireNonNull(campId);
			position = Objects.requireNonNull(position).immutable();
		}
	}
	record Update(SettlementSowingPlots value, Status status) {}
	private final List<Permit> permits;
	private final Tag preserved;
	SettlementSowingPlots() { this(List.of(), null); }
	private SettlementSowingPlots(List<Permit> permits, Tag preserved) {
		this.permits = List.copyOf(permits); this.preserved = preserved == null ? null : preserved.copy();
	}
	public boolean blocked() { return preserved != null; }
	public List<Permit> all() { return permits; }
	static boolean validPosition(BlockPos pos) {
		return pos != null && pos.getX() >= -30_000_000 && pos.getX() <= 30_000_000
			&& pos.getZ() >= -30_000_000 && pos.getZ() <= 30_000_000 && pos.getY() >= -2048 && pos.getY() <= 2047;
	}
	public Optional<Permit> of(BlockPos position) { return permits.stream().filter(p -> p.position().equals(position)).findFirst(); }
	Update add(UUID camp, BlockPos pos) {
		if (blocked()) return new Update(this, Status.PRESERVED_UNSUPPORTED);
		if (camp == null || !validPosition(pos)) return new Update(this, Status.INVALID);
		Permit previous = of(pos).orElse(null);
		if (previous != null) return new Update(this, previous.campId().equals(camp) ? Status.UNCHANGED : Status.OTHER_SETTLEMENT);
		if (permits.size() >= MAX_PER_DIMENSION || permits.stream().filter(p -> p.campId().equals(camp)).count() >= MAX_PER_SETTLEMENT)
			return new Update(this, Status.LIMIT);
		UUID id;
		do { id = UUID.randomUUID(); } while (hasId(id));
		var next = new ArrayList<>(permits); next.add(new Permit(id, camp, pos));
		return new Update(new SettlementSowingPlots(next, null), Status.CREATED);
	}
	private boolean hasId(UUID id) { return permits.stream().anyMatch(p -> p.id().equals(id)); }
	Update remove(UUID camp, BlockPos pos) {
		if (blocked()) return new Update(this, Status.PRESERVED_UNSUPPORTED);
		if (camp == null || !validPosition(pos)) return new Update(this, Status.INVALID);
		Permit previous = of(pos).orElse(null);
		if (previous == null) return new Update(this, Status.NOT_FOUND);
		if (!previous.campId().equals(camp)) return new Update(this, Status.OTHER_SETTLEMENT);
		var next = new ArrayList<>(permits); next.remove(previous);
		return new Update(new SettlementSowingPlots(next, null), Status.REMOVED);
	}
	Tag save() {
		if (preserved != null) return preserved.copy();
		CompoundTag root = new CompoundTag(); root.putInt("Version", 1);
		ListTag list = new ListTag();
		for (Permit p : permits) {
			CompoundTag entry = new CompoundTag(); entry.putUUID("Id", p.id()); entry.putUUID("CampId", p.campId());
			entry.putInt("X", p.position().getX()); entry.putInt("Y", p.position().getY()); entry.putInt("Z", p.position().getZ()); list.add(entry);
		}
		root.put("Permits", list); return root;
	}
	static SettlementSowingPlots load(Tag raw) {
		if (raw == null) return new SettlementSowingPlots();
		if (raw instanceof CompoundTag root && root.getAllKeys().equals(Set.of("Version", "Permits"))
			&& root.contains("Version", Tag.TAG_INT) && root.getInt("Version") == 1
			&& root.get("Permits") instanceof ListTag list && list.size() <= MAX_PER_DIMENSION
			&& (list.getElementType() == Tag.TAG_COMPOUND || list.isEmpty() && list.getElementType() == Tag.TAG_END)) {
			List<Permit> decoded = new ArrayList<>(); Set<UUID> ids = new HashSet<>(); Set<BlockPos> positions = new HashSet<>();
			Map<UUID, Integer> counts = new HashMap<>();
			for (int i = 0; i < list.size(); i++) {
				CompoundTag e = list.getCompound(i);
				if (!e.getAllKeys().equals(Set.of("Id", "CampId", "X", "Y", "Z")) || !e.hasUUID("Id") || !e.hasUUID("CampId")
					|| !e.contains("X", Tag.TAG_INT) || !e.contains("Y", Tag.TAG_INT) || !e.contains("Z", Tag.TAG_INT)) return preserve(raw);
				UUID id = e.getUUID("Id"), camp = e.getUUID("CampId"); BlockPos pos = new BlockPos(e.getInt("X"), e.getInt("Y"), e.getInt("Z"));
				if (!validPosition(pos) || !ids.add(id) || !positions.add(pos) || counts.merge(camp, 1, Integer::sum) > MAX_PER_SETTLEMENT) return preserve(raw);
				decoded.add(new Permit(id, camp, pos));
			}
			return new SettlementSowingPlots(decoded, null);
		}
		return preserve(raw);
	}
	private static SettlementSowingPlots preserve(Tag raw) { return new SettlementSowingPlots(List.of(), raw); }
}
