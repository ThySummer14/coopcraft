package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.EventType;
import com.thysummer.coopcraft.civilization.simulation.Person;
import com.thysummer.coopcraft.civilization.simulation.ResourceFlow;
import com.thysummer.coopcraft.civilization.simulation.SimulationEvent;

public final class SettlementHouseholds {
	private SettlementHouseholds() {
	}

	public static Optional<Observation> observe(SettlementSimulationState state, UUID villagerId) {
		return observe(state, villagerId, state.world().day());
	}

	public static Optional<Observation> observe(SettlementSimulationState state, UUID villagerId, long currentCareDay) {
		String personId = state.residentId(villagerId).orElse(null);
		if (personId == null) {
			return Optional.empty();
		}
		Person person = state.world().person(personId);
		ResidenceLink residence = new ResidenceLink(
			person.householdId(),
			state.world().peopleInHousehold(person.householdId()).stream().map(Person::id).toList()
		);
		List<CareLink> care = new ArrayList<>();
		List<ProvisioningLink> provisioning = new ArrayList<>();
		List<SimulationEvent> events = state.world().ledger().events();
		for (int index = events.size() - 1; index >= 0; index--) {
			SimulationEvent event = events.get(index);
			if (event.day() < state.world().day()) {
				break;
			}
			if (careDay(event) == currentCareDay) {
				careLink(event, personId).ifPresent(care::add);
			}
			if (event.day() == state.world().day()) {
				provisioningLink(event, person).ifPresent(provisioning::add);
			}
		}
		Collections.reverse(care);
		Collections.reverse(provisioning);
		return Optional.of(new Observation(residence, care, provisioning, person.needsCare()));
	}

	private static long careDay(SimulationEvent event) {
		if (event.context().containsKey("minecraftDay")) {
			try {
				return Long.parseLong(event.context().get("minecraftDay"));
			} catch (NumberFormatException exception) {
				return event.day();
			}
		}
		return event.day();
	}

	private static Optional<CareLink> careLink(SimulationEvent event, String personId) {
		if (event.type() != EventType.CARE_PROVIDED || event.actors().size() != 2) {
			return Optional.empty();
		}
		String caregiverId = event.actors().getFirst();
		String dependentId = event.actors().get(1);
		if (!personId.equals(caregiverId) && !personId.equals(dependentId)) {
			return Optional.empty();
		}
		try {
			return Optional.of(new CareLink(
				event.eventId(), careDay(event), caregiverId, dependentId, Integer.parseInt(event.context().get("hours"))
			));
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	private static Optional<ProvisioningLink> provisioningLink(SimulationEvent event, Person recipient) {
		if (event.type() != EventType.SHARE || event.actors().size() != 2 || event.outputs().size() != 1
			|| !"camp-storage".equals(event.context().get("allocation"))
			|| !recipient.id().equals(event.actors().getFirst())
			|| !recipient.campId().equals(event.actors().get(1))) {
			return Optional.empty();
		}
		ResourceFlow output = event.outputs().getFirst();
		try {
			return Optional.of(new ProvisioningLink(
				event.eventId(), event.day(), event.actors().get(1), recipient.id(), output.resourceType(), output.quantity()
			));
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	public record Observation(
		ResidenceLink residence,
		List<CareLink> care,
		List<ProvisioningLink> provisioning,
		boolean needsCare
	) {
		public Observation {
			care = List.copyOf(care);
			provisioning = List.copyOf(provisioning);
		}
	}
}
