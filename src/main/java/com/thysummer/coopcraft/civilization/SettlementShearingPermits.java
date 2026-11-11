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

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Immutable, dimension-local permissions for shearing only. Never implies entity access or mutation. */
public final class SettlementShearingPermits {
	public static final int MAX_PER_SETTLEMENT = 16;
	public static final int MAX_PER_DIMENSION = 128;
	private static final int VERSION = 1;
	private static final int REQUIRED_SAVED_DATA_VERSION = 24;
	private static final Set<String> ROOT_KEYS = Set.of("Version", "Permits");
	private static final Set<String> PERMIT_KEYS = Set.of("Id", "SheepId", "SettlementId", "Authorizer");

	public enum Status {
		CREATED, REPLACED, UNCHANGED, REMOVED, NOT_FOUND, OTHER_SETTLEMENT, INVALID, LIMIT, PRESERVED_UNSUPPORTED
	}

	public record Permit(UUID id, UUID sheepId, UUID settlementId, UUID authorizer) {
		public Permit {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(sheepId, "sheepId");
			Objects.requireNonNull(settlementId, "settlementId");
			Objects.requireNonNull(authorizer, "authorizer");
		}
	}

	public record PermitResult(Status status, Permit permit) { }
	record Update(SettlementShearingPermits value, Status status, Permit permit) { }

	private final List<Permit> permits;
	private final Tag preserved;
	private final boolean missingRequired;
	// Identity is deliberately neither persisted nor derived from content: empty -> granted -> empty is a change.
	private final Object revision = new Object();

	SettlementShearingPermits() { this(List.of(), null, false); }

	private SettlementShearingPermits(List<Permit> permits, Tag preserved, boolean missingRequired) {
		this.permits = List.copyOf(permits);
		this.preserved = preserved == null ? null : preserved.copy();
		this.missingRequired = missingRequired;
	}

	public boolean blocked() { return preserved != null || missingRequired; }
	public Object revision() { return revision; }
	public Optional<Permit> of(UUID sheepId) {
		return permits.stream().filter(permit -> permit.sheepId().equals(sheepId)).findFirst();
	}
	public List<Permit> ofSettlement(UUID settlementId) {
		return permits.stream().filter(permit -> permit.settlementId().equals(settlementId)).toList();
	}

	Update grant(UUID settlementId, UUID authorizer, UUID sheepId) {
		if (blocked()) return unchanged(Status.PRESERVED_UNSUPPORTED);
		if (settlementId == null || authorizer == null || sheepId == null) return unchanged(Status.INVALID);
		Permit existing = of(sheepId).orElse(null);
		if (existing != null) {
			if (!existing.settlementId().equals(settlementId)) return unchanged(Status.OTHER_SETTLEMENT);
			if (existing.authorizer().equals(authorizer)) return new Update(this, Status.UNCHANGED, existing);
		} else if (permits.size() >= MAX_PER_DIMENSION || ofSettlement(settlementId).size() >= MAX_PER_SETTLEMENT) {
			return unchanged(Status.LIMIT);
		}
		UUID id = UUID.randomUUID();
		if (permits.stream().anyMatch(permit -> permit.id().equals(id))) return unchanged(Status.INVALID);
		Permit permit = new Permit(id, sheepId, settlementId, authorizer);
		List<Permit> updated = new ArrayList<>(permits);
		if (existing == null) updated.add(permit);
		else updated.set(updated.indexOf(existing), permit);
		return new Update(new SettlementShearingPermits(updated, null, false),
			existing == null ? Status.CREATED : Status.REPLACED, permit);
	}

	Update revoke(UUID settlementId, UUID sheepId) {
		if (blocked()) return unchanged(Status.PRESERVED_UNSUPPORTED);
		if (settlementId == null || sheepId == null) return unchanged(Status.INVALID);
		Permit existing = of(sheepId).orElse(null);
		if (existing == null) return unchanged(Status.NOT_FOUND);
		if (!existing.settlementId().equals(settlementId)) return unchanged(Status.OTHER_SETTLEMENT);
		List<Permit> updated = new ArrayList<>(permits);
		updated.remove(existing);
		return new Update(new SettlementShearingPermits(updated, null, false), Status.REMOVED, existing);
	}

	private Update unchanged(Status status) { return new Update(this, status, null); }

	Tag save() {
		if (missingRequired) return null;
		if (preserved != null) return preserved.copy();
		CompoundTag root = new CompoundTag();
		root.putInt("Version", VERSION);
		ListTag list = new ListTag();
		for (Permit permit : permits) {
			CompoundTag entry = new CompoundTag();
			entry.putUUID("Id", permit.id());
			entry.putUUID("SheepId", permit.sheepId());
			entry.putUUID("SettlementId", permit.settlementId());
			entry.putUUID("Authorizer", permit.authorizer());
			list.add(entry);
		}
		root.put("Permits", list);
		return root;
	}

	static SettlementShearingPermits load(Tag raw, Tag savedDataVersion) {
		if (raw == null) {
			boolean legacy = savedDataVersion == null || savedDataVersion instanceof IntTag version
				&& version.getAsInt() >= 0 && version.getAsInt() < REQUIRED_SAVED_DATA_VERSION;
			return new SettlementShearingPermits(List.of(), null, !legacy);
		}
		if (raw instanceof CompoundTag root && root.getAllKeys().equals(ROOT_KEYS)
				&& root.contains("Version", Tag.TAG_INT) && root.getInt("Version") == VERSION
				&& root.get("Permits") instanceof ListTag list
				&& (list.getElementType() == Tag.TAG_COMPOUND || list.isEmpty() && list.getElementType() == Tag.TAG_END)
				&& list.size() <= MAX_PER_DIMENSION) {
			List<Permit> decoded = new ArrayList<>(list.size());
			Set<UUID> ids = new HashSet<>(), sheepIds = new HashSet<>();
			Map<UUID, Integer> counts = new HashMap<>();
			for (int i = 0; i < list.size(); i++) {
				CompoundTag entry = list.getCompound(i);
				if (!entry.getAllKeys().equals(PERMIT_KEYS) || !entry.hasUUID("Id") || !entry.hasUUID("SheepId")
						|| !entry.hasUUID("SettlementId") || !entry.hasUUID("Authorizer")) return preserve(raw);
				UUID id = entry.getUUID("Id"), sheepId = entry.getUUID("SheepId"), settlementId = entry.getUUID("SettlementId");
				if (!ids.add(id) || !sheepIds.add(sheepId)
						|| counts.merge(settlementId, 1, Integer::sum) > MAX_PER_SETTLEMENT) return preserve(raw);
				decoded.add(new Permit(id, sheepId, settlementId, entry.getUUID("Authorizer")));
			}
			return new SettlementShearingPermits(decoded, null, false);
		}
		return preserve(raw);
	}

	private static SettlementShearingPermits preserve(Tag raw) {
		return new SettlementShearingPermits(List.of(), raw, false);
	}
}
