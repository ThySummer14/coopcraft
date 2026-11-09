package com.thysummer.coopcraft.civilization;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Synchronous server-thread gift transaction for standard, non-reentrant Containers.
 * Debit precedes destination movement; a rejected physical insertion restores the original slot.
 * The simulation adapter likewise rolls back through the live slot view. This is not crash atomic.
 */
final class VillagerGiftService {
	private VillagerGiftService() {}

	static VillagerInteraction.OfferResult offer(SettlementSavedData data, Settlement settlement, UUID resident,
		boolean baby, String actor, String playerName, Container inventory, int offeredSlot,
		IntSupplier currentSlot, ItemStack offered, Container stockpile) {
		if (!data.isMember(settlement, resident)) return failed("not_member");
		if (offeredSlot < 0 || offeredSlot >= inventory.getContainerSize()
			|| currentSlot.getAsInt() != offeredSlot || !sameOffer(inventory.getItem(offeredSlot), offered))
			return failed("transfer_failed");
		if (!VillagerInteraction.isFood(offered)) return failed("not_food");
		if (!offered.getComponentsPatch().isEmpty()) return failed("unsupported_food");
		var reserves = SettlementReserves.inspect(data, settlement, stockpile);
		if (reserves.current().isEmpty()) return failed("no_stockpile");
		if (!reserves.survival() && inventory == stockpile) return failed("transfer_failed");
		var state = data.getOrCreateSimulation(settlement);
		if (state.residentId(resident).isEmpty()) {
			state.mapResident(resident, baby, data.needsOf(settlement, resident).satiety());
			data.setDirty();
		}
		String personId = state.residentId(resident).orElseThrow();
		var person = state.world().person(personId);
		int satiety = reserves.survival() ? 100 - person.hunger() : data.needsOf(settlement, resident).satiety();
		var decision = VillagerInteractionDecision.decide(satiety, reserves, person.trustIn(actor),
			data.personalityOf(settlement, resident).sociability(), VillagerInteraction.isSupportedFood(offered, reserves.survival()));
		String reason = decision.reason();
		int moved = 0;
		if (decision.accepted()) {
			// Check the captured hand again immediately before debit. The view can only restore that same slot.
			if (currentSlot.getAsInt() != offeredSlot || !sameOffer(inventory.getItem(offeredSlot), offered)) {
				reason = "transfer_failed";
			} else {
				Container hand = new SingleSlot(inventory, offeredSlot);
				if (reserves.survival()) {
					moved = MinecraftStockpileAdapter.importToSimulation(hand, state.world(), state.campOwner(),
						MinecraftStockpileAdapter.resourceType(offered).orElseThrow(), 1,
						List.of(personId, actor), Map.of("activity", "player_gift", "playerName", playerName,
							"villagerId", resident.toString()));
				} else {
					ItemStack removed = hand.removeItem(0, 1);
					if (!removed.isEmpty()) {
						moved = SettlementStockpile.insert(stockpile, removed);
						if (moved == 0) hand.setItem(0, offered.copy());
						hand.setChanged();
					}
				}
				if (moved == 0) reason = reserves.survival() ? "transfer_failed" : "stockpile_full";
			}
		}
		if (moved == 1) person.recordAidFrom(actor);
		state.world().recordGiftInteraction(personId, actor, moved == 1, offered.getItem().getDescriptionId(),
			playerName, reason, person.trustIn(actor), Map.of(
				"targetId", reserves.target().id(), "unit", reserves.survival() ? "nutrition" : "points",
				"currentBefore", Long.toString(reserves.current().orElseThrow()), "goalBefore", Long.toString(reserves.goal())));
		data.setDirty();
		return new VillagerInteraction.OfferResult(moved == 1, reason, moved);
	}

	private static boolean sameOffer(ItemStack current, ItemStack offered) {
		return current.getCount() == offered.getCount() && ItemStack.isSameItemSameComponents(current, offered);
	}
	private static VillagerInteraction.OfferResult failed(String reason) {
		return new VillagerInteraction.OfferResult(false, reason, 0);
	}

	/** Live view, not a copy: adapter debit and rollback both address the captured slot. */
	static final class SingleSlot implements Container {
		private final Container inventory;
		private final int slot;
		SingleSlot(Container inventory, int slot) { this.inventory = inventory; this.slot = slot; }
		private void check(int index) { if (index != 0) throw new IndexOutOfBoundsException(index); }
		public int getContainerSize() { return 1; }
		public boolean isEmpty() { return getItem(0).isEmpty(); }
		public ItemStack getItem(int index) { check(index); return inventory.getItem(slot); }
		public ItemStack removeItem(int index, int amount) { check(index); return inventory.removeItem(slot, amount); }
		public ItemStack removeItemNoUpdate(int index) { check(index); return inventory.removeItemNoUpdate(slot); }
		public void setItem(int index, ItemStack stack) { check(index); inventory.setItem(slot, stack); }
		public void setChanged() { inventory.setChanged(); }
		public boolean stillValid(Player player) { return inventory.stillValid(player); }
		public void clearContent() { setItem(0, ItemStack.EMPTY); }
		public int getMaxStackSize() { return inventory.getMaxStackSize(); }
		public boolean canPlaceItem(int index, ItemStack stack) { check(index); return inventory.canPlaceItem(slot, stack); }
	}
}
