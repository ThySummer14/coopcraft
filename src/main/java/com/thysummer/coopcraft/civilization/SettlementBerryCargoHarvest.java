package com.thysummer.coopcraft.civilization;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.thysummer.coopcraft.civilization.simulation.Person;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Shared berry/potato harvest clock and eligibility, captured before evaluating loot. */
final class SettlementBerryCargoHarvest {
	private SettlementBerryCargoHarvest() { }

	static Attempt capture(SettlementSavedData data, Settlement settlement, UUID actor) {
		return new Attempt(data, settlement, actor);
	}

	static boolean plainBerry(ItemStack stack) {
		return !stack.isEmpty() && stack.is(Items.SWEET_BERRIES) && stack.getComponentsPatch().isEmpty();
	}

	static final class Attempt {
		private final SettlementSavedData data;
		private final UUID camp, actor;
		private final Object member;
		private final SettlementSimulationState state;
		private final boolean survival, production;
		private final long modeVersion, day;
		private final UUID clock;
		private final String personId;
		private final Person person;

		private Attempt(SettlementSavedData data, Settlement settlement, UUID actor) {
			this.data = data; this.camp = settlement.id(); this.actor = actor;
			this.member = data.memberIdentity(actor);
			this.state = data.simulationOf(settlement).orElse(null);
			this.survival = state != null && state.survivalEnabled();
			this.production = state != null && state.productionEnabled();
			this.modeVersion = state == null ? 0 : state.foodRequestModeVersion();
			this.day = state == null ? 0 : state.world().day();
			this.clock = state == null ? null : state.foodClockId().orElse(null);
			this.personId = state == null ? null : state.residentId(actor).orElse(null);
			this.person = personId == null ? null : state.world().allPeople().stream()
				.filter(candidate -> candidate.id().equals(personId)).findFirst().orElse(null);
		}

		boolean valid(SettlementSavedData current, Settlement settlement, UUID currentActor, BooleanSupplier adult) {
			if (!adult.getAsBoolean() || data != current || !camp.equals(settlement.id())
				|| !actor.equals(currentActor) || member != current.memberIdentity(actor)
				|| state != current.simulationOf(settlement).orElse(null)) return false;
			if (state == null) return true;
			return survival == state.survivalEnabled() && production == state.productionEnabled()
				&& modeVersion == state.foodRequestModeVersion() && day == state.world().day()
				&& Objects.equals(clock, state.foodClockId().orElse(null))
				&& Objects.equals(personId, state.residentId(actor).orElse(null))
				&& (!survival || production && clock != null && day >= 0 && personId != null
					&& current.isMember(settlement, actor) && person != null && person.isAlive()
					&& person.campId().equals(state.campOwner().id())
					&& state.world().allPeople().stream().anyMatch(candidate -> candidate == person));
		}

		/** Called only after reserving a plain seed from this harvest's original loot. */
		ItemStack potatoOutputCopy(ItemStack stack) {
			return survival && SettlementCropHarvest.ordinaryPotato(stack)
				? SettlementFoodAge.stamp(stack, clock, day) : stack.copy();
		}

		/** Only newly produced plain berries are stamped; old cargo and custom loot are untouched. */
		ItemStack outputCopy(ItemStack stack) {
			return survival && plainBerry(stack) ? SettlementFoodAge.stamp(stack, clock, day) : stack.copy();
		}
	}
}
