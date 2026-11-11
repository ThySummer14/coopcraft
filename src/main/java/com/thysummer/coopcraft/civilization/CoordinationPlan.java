package com.thysummer.coopcraft.civilization;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record CoordinationPlan(long day, List<NeedSignal> signals, List<WorkReservation> reservations) {
	public CoordinationPlan {
		signals = List.copyOf(signals);
		reservations = List.copyOf(reservations);
		if (day < -1 || signals.size() > 3) {
			throw new IllegalArgumentException("Invalid coordination plan");
		}
		Map<NeedSignal.Type, NeedSignal> needs = new HashMap<>();
		Set<UUID> actors = new HashSet<>();
		for (NeedSignal signal : signals) {
			if (signal.day() != day || needs.put(signal.type(), signal) != null) {
				throw new IllegalArgumentException("Invalid coordination signals");
			}
		}
		for (WorkReservation reservation : reservations) {
			NeedSignal need = needs.values().stream().filter(signal -> signal.id().equals(reservation.needId())).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("A reservation must refer to an observed need"));
			if (reservation.day() != day || reservation.task() != WorkReservation.Task.forNeed(need.type())
				|| !actors.add(reservation.actorId())) {
				throw new IllegalArgumentException("An actor can hold only one reservation per planning cycle");
			}
		}
	}

	public static CoordinationPlan empty(long day) {
		return new CoordinationPlan(day, List.of(), List.of());
	}

	public Optional<NeedSignal> need(NeedSignal.Type type) {
		return signals.stream().filter(signal -> signal.type() == type).findFirst();
	}

	public Optional<WorkReservation> reservationFor(UUID actorId) {
		return reservations.stream().filter(reservation -> reservation.actorId().equals(actorId)).findFirst();
	}

	public boolean hasPausedConstruction() {
		return reservations.stream().anyMatch(reservation -> reservation.task() == WorkReservation.Task.BUILD
			&& reservation.status() == WorkReservation.Status.PAUSED);
	}

	public List<WorkReservation> orderedReservations() {
		return reservations.stream().sorted(Comparator.comparing(reservation -> reservation.actorId().toString())).toList();
	}
}
