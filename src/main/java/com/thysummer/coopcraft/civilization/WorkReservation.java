package com.thysummer.coopcraft.civilization;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;

public record WorkReservation(
	UUID id,
	UUID actorId,
	long day,
	Task task,
	UUID needId,
	Status status,
	String cause
) {
	public WorkReservation {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(actorId, "actorId");
		Objects.requireNonNull(task, "task");
		Objects.requireNonNull(needId, "needId");
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(cause, "cause");
		if (day < 0 || cause.length() > 64 || status == Status.PAUSED && cause.isBlank()
			|| status != Status.PAUSED && !cause.isEmpty()) {
			throw new IllegalArgumentException("Invalid work reservation");
		}
	}

	public static WorkReservation commit(long day, UUID actorId, NeedSignal need) {
		return new WorkReservation(
			UUID.randomUUID(), actorId, day, Task.forNeed(need.type()), need.id(), Status.COMMITTED, ""
		);
	}

	public WorkReservation activate() {
		if (status == Status.COMPLETED) {
			throw new IllegalStateException("A completed reservation cannot restart");
		}
		return withStatus(Status.ACTIVE, "");
	}

	public WorkReservation rest() {
		return status == Status.ACTIVE ? withStatus(Status.COMMITTED, "") : this;
	}

	public WorkReservation resume() {
		return status == Status.PAUSED ? withStatus(Status.COMMITTED, "") : this;
	}

	public WorkReservation pause(String reason) {
		if (status == Status.COMPLETED) {
			return this;
		}
		if (reason == null || reason.isBlank() || reason.length() > 64) {
			throw new IllegalArgumentException("A pause reason is required");
		}
		return withStatus(Status.PAUSED, reason);
	}

	public WorkReservation complete() {
		return status == Status.COMPLETED ? this : withStatus(Status.COMPLETED, "");
	}

	private WorkReservation withStatus(Status next, String reason) {
		return status == next && cause.equals(reason) ? this
			: new WorkReservation(id, actorId, day, task, needId, next, reason);
	}

	CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putUUID("Id", id);
		tag.putUUID("ActorId", actorId);
		tag.putLong("Day", day);
		tag.putString("Task", task.name());
		tag.putUUID("NeedId", needId);
		tag.putString("Status", status.name());
		tag.putString("Cause", cause);
		return tag;
	}

	static WorkReservation load(CompoundTag tag) {
		return new WorkReservation(
			tag.getUUID("Id"), tag.getUUID("ActorId"), tag.getLong("Day"), Task.valueOf(tag.getString("Task")),
			tag.getUUID("NeedId"), Status.valueOf(tag.getString("Status")), tag.getString("Cause")
		);
	}

	public enum Task {
		FORAGE,
		BUILD;

		static Task forNeed(NeedSignal.Type type) {
			return switch (type) {
				case COMMON_FOOD_SHORTAGE -> FORAGE;
				case SHELTER_GAP -> BUILD;
			};
		}
	}

	public enum Status {
		COMMITTED,
		ACTIVE,
		PAUSED,
		COMPLETED
	}
}
