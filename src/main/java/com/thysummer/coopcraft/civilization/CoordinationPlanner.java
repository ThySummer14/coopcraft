package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class CoordinationPlanner {
	private CoordinationPlanner() {
	}

	static CoordinationPlan plan(
		long day,
		List<Resident> residents,
		NeedSignal food,
		NeedSignal shelter,
		List<WorkReservation> previous
	) {
		List<NeedSignal> signals = new ArrayList<>();
		if (food != null) {
			signals.add(food);
		}
		if (shelter != null) {
			signals.add(shelter);
		}
		List<WorkReservation> reservations = new ArrayList<>();
		List<UUID> builders = previous.stream()
			.filter(reservation -> reservation.task() == WorkReservation.Task.BUILD)
			.filter(reservation -> reservation.status() != WorkReservation.Status.COMPLETED)
			.map(WorkReservation::actorId)
			.toList();
		List<Resident> foragerCandidates = residents.stream()
			.filter(resident -> !builders.contains(resident.id()))
			.toList();
		if (food != null) {
			choose(foragerCandidates, previous, WorkReservation.Task.FORAGE, food).ifPresent(reservations::add);
		}
		if (shelter != null) {
			if (food != null && food.severity() == NeedSignal.Severity.URGENT) {
				previous.stream()
					.filter(reservation -> reservation.task() == WorkReservation.Task.BUILD)
					.filter(reservation -> reservation.status() != WorkReservation.Status.COMPLETED)
					.filter(reservation -> residents.stream().anyMatch(resident -> resident.id().equals(reservation.actorId())
						&& resident.knowsShelter()))
					.findFirst()
					.ifPresent(reservation -> reservations.add(reservation.pause(pauseCause(reservation, residents))));
			} else {
				List<Resident> builderCandidates = residents.stream()
					.filter(resident -> reservations.stream().noneMatch(reservation -> reservation.actorId().equals(resident.id())))
					.toList();
				choose(builderCandidates, previous, WorkReservation.Task.BUILD, shelter).ifPresent(reservations::add);
			}
		}
		return new CoordinationPlan(day, signals, reservations);
	}

	private static String pauseCause(WorkReservation reservation, List<Resident> residents) {
		Resident resident = residents.stream().filter(candidate -> candidate.id().equals(reservation.actorId())).findFirst()
			.orElseThrow();
		if (resident.careAssigned()) {
			return "care_commitment";
		}
		return resident.satiety() <= SettlementNeeds.HUNGRY_AT ? "personal_hunger" : "food_shortage";
	}

	private static Optional<WorkReservation> choose(
		List<Resident> residents,
		List<WorkReservation> previous,
		WorkReservation.Task task,
		NeedSignal need
	) {
		WorkReservation existing = previous.stream()
			.filter(reservation -> reservation.task() == task && reservation.status() != WorkReservation.Status.COMPLETED)
			.findFirst().orElse(null);
		return residents.stream()
			.filter(resident -> eligible(resident, task))
			.sorted(Comparator
				.comparingInt((Resident resident) -> score(resident, existing, task)).reversed()
				.thenComparing(resident -> resident.id().toString()))
			.findFirst()
			.map(resident -> {
				WorkReservation selected = existing == null || !existing.actorId().equals(resident.id())
					? WorkReservation.commit(need.day(), resident.id(), need) : existing;
				if (task == WorkReservation.Task.FORAGE && selected.status() == WorkReservation.Status.PAUSED
					&& selected.cause().equals("forage_unavailable")) {
					return selected;
				}
				if (!resident.constructionAvailable() && task == WorkReservation.Task.BUILD) {
					return selected.pause("construction_blocked");
				}
				return selected.resume();
			});
	}

	private static boolean eligible(Resident resident, WorkReservation.Task task) {
		if (resident.careAssigned()) {
			return false;
		}
		if (task == WorkReservation.Task.FORAGE) {
			return resident.knowsFood() && resident.canForage() && resident.satiety() > 0
				&& (resident.role() == WorkRole.FORAGER || resident.sociability() >= 40
					|| resident.satiety() <= SettlementNeeds.HUNGRY_AT);
		}
		return resident.knowsShelter() && resident.satiety() > SettlementNeeds.HUNGRY_AT
			&& (resident.role() == WorkRole.BUILDER || resident.diligence() + resident.sociability() >= 70);
	}

	private static int score(Resident resident, WorkReservation existing, WorkReservation.Task task) {
		int continuity = existing != null && existing.actorId().equals(resident.id())
			&& !(existing.status() == WorkReservation.Status.PAUSED && existing.cause().equals("forage_unavailable"))
			? 1000 : 0;
		return continuity + switch (task) {
			case FORAGE -> (resident.role() == WorkRole.FORAGER ? 300 : 0) + resident.sociability() + resident.diligence() / 2;
			case BUILD -> (resident.role() == WorkRole.BUILDER ? 300 : 0) + resident.diligence() + resident.sociability() / 2;
		};
	}

	public record Resident(
		UUID id,
		WorkRole role,
		int diligence,
		int sociability,
		int satiety,
		boolean knowsFood,
		boolean knowsShelter,
		boolean careAssigned,
		boolean canForage,
		boolean constructionAvailable
	) {
		public Resident {
			if (diligence < 0 || diligence > 100 || sociability < 0 || sociability > 100 || satiety < 0 || satiety > 100) {
				throw new IllegalArgumentException("Invalid resident observation");
			}
		}
	}
}
