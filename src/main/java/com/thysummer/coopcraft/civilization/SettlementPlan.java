package com.thysummer.coopcraft.civilization;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;

public record SettlementPlan(
	String id,
	Kind kind,
	UUID workerId,
	String outputItem,
	int targetQuantity,
	int producedQuantity,
	Status status,
	long createdDay,
	long completedDay
) {
	public enum Kind {
		BAKE_BREAD("bake_bread", "minecraft:bread");

		private final String id;
		private final String outputItem;

		Kind(String id, String outputItem) {
			this.id = id;
			this.outputItem = outputItem;
		}

		public String id() {
			return id;
		}

		public String outputItem() {
			return outputItem;
		}

		public String translationKey() {
			return "coopcraft.intelligence.plan." + id;
		}
	}

	public enum Status {
		ACTIVE,
		COMPLETED
	}

	public SettlementPlan {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(workerId, "workerId");
		Objects.requireNonNull(outputItem, "outputItem");
		Objects.requireNonNull(status, "status");
		if (targetQuantity <= 0 || producedQuantity < 0 || producedQuantity > targetQuantity) {
			throw new IllegalArgumentException("Invalid plan quantities");
		}
	}

	public static SettlementPlan create(Kind kind, UUID workerId, long createdDay) {
		return new SettlementPlan(kind.id() + ":" + workerId, kind, workerId, kind.outputItem(), 1, 0, Status.ACTIVE, createdDay, -1);
	}

	public SettlementPlan advance(int amount, long day) {
		if (status != Status.ACTIVE) {
			return this;
		}
		if (amount <= 0) {
			throw new IllegalArgumentException("Plan output must be positive");
		}
		int produced = Math.min(targetQuantity, producedQuantity + amount);
		boolean completed = produced >= targetQuantity;
		return new SettlementPlan(
			id, kind, workerId, outputItem, targetQuantity, produced,
			completed ? Status.COMPLETED : Status.ACTIVE, createdDay, completed ? day : completedDay
		);
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putString("Id", id);
		tag.putString("Kind", kind.id);
		tag.putUUID("WorkerId", workerId);
		tag.putString("OutputItem", outputItem);
		tag.putInt("TargetQuantity", targetQuantity);
		tag.putInt("ProducedQuantity", producedQuantity);
		tag.putString("Status", status.name());
		tag.putLong("CreatedDay", createdDay);
		tag.putLong("CompletedDay", completedDay);
		return tag;
	}

	public static SettlementPlan load(CompoundTag tag) {
		Kind kind = null;
		for (Kind candidate : Kind.values()) {
			if (candidate.id.equals(tag.getString("Kind"))) {
				kind = candidate;
				break;
			}
		}
		if (kind == null) {
			throw new IllegalArgumentException("Unknown settlement plan kind: " + tag.getString("Kind"));
		}
		return new SettlementPlan(
			tag.getString("Id"), kind, tag.getUUID("WorkerId"), tag.getString("OutputItem"),
			tag.getInt("TargetQuantity"), tag.getInt("ProducedQuantity"), Status.valueOf(tag.getString("Status")),
			tag.getLong("CreatedDay"), tag.getLong("CompletedDay")
		);
	}
}
