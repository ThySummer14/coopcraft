package com.thysummer.coopcraft.civilization;

import java.util.UUID;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Explicit, local collection of material remnants; never spawns items or changes terrain. */
final class SettlementCargoCommands {
	private SettlementCargoCommands() {}

	static int execute(CommandSourceStack source, String option, String record) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		var player = source.getPlayerOrException();
		var level = player.serverLevel();
		boolean rescue = option.equals("rescue");
		boolean stranded = option.equals("stranded");
		if ((rescue || stranded) && !source.hasPermission(2)) {
			source.sendFailure(message("permission"));
			return 0;
		}
		var recovery = SettlementCargoRecovery.get(level);
		if (!recovery.available()) {
			source.sendFailure(message("cargo_unavailable"));
			return 0;
		}
		java.util.function.Predicate<net.minecraft.core.BlockPos> inBounds = position ->
			!level.isOutsideBuildHeight(position) && level.getWorldBorder().isWithinBounds(position);
		if (option.isEmpty() || stranded) {
			var nearby = stranded ? recovery.stranded(inBounds, 8) : recovery.nearby(player.blockPosition(), 64, 8);
			if (nearby.isEmpty()) source.sendSuccess(() -> message(stranded ? "cargo_stranded_none" : "cargo_none"), false);
			for (var snapshot : nearby) {
				var items = Component.empty();
				for (var item : snapshot.items()) {
					if (!items.getSiblings().isEmpty()) items.append(", ");
					items.append(Component.translatable(BuiltInRegistries.ITEM.get(item.itemId()).getDescriptionId()))
						.append(" ×" + item.count());
				}
				source.sendSuccess(() -> message("cargo_row", snapshot.name(), snapshot.position().toShortString(),
					items, snapshot.recordId().toString()), false);
			}
			if (!nearby.isEmpty()) source.sendSuccess(() -> message(stranded ? "cargo_rescue_hint" : "cargo_hint"), false);
			return nearby.size();
		}
		UUID id = parseId(record);
		var snapshot = id == null ? null : recovery.inspect(id).orElse(null);
		if (snapshot == null) {
			source.sendFailure(message("cargo_invalid"));
			return 0;
		}
		var position = snapshot.position();
		if (rescue && inBounds.test(position)) {
			source.sendFailure(message("cargo_rescue_blocked"));
			return 0;
		}
		var result = recovery.recover(id, new MainInventory(player.getInventory()), () ->
			rescue ? canRescue(source.hasPermission(2), !inBounds.test(position),
				canRecover(player.isAlive(), player.isRemoved(), player.isSpectator(), player.level() == level,
					inBounds.test(player.blockPosition()), level.isLoaded(player.blockPosition()),
					level.isPositionEntityTicking(player.blockPosition()), 0))
				: canRecover(player.isAlive(), player.isRemoved(), player.isSpectator(), player.level() == level,
					inBounds.test(position), level.isLoaded(position), level.isPositionEntityTicking(position),
					player.distanceToSqr(position.getCenter())));
		if (rescue && result.moved() > 0) source.sendSuccess(() -> message("cargo_rescued",
			snapshot.name(), position.toShortString(), result.moved(), result.remaining()), true);
		else if (rescue && result.status() == SettlementCargoRecovery.Status.NOT_AT_PLACE)
			source.sendFailure(message("cargo_rescue_requirements"));
		else source.sendSuccess(() -> message("cargo_" + result.status().name().toLowerCase(java.util.Locale.ROOT),
			result.moved(), result.remaining()), false);
		return result.moved();
	}

	static UUID parseId(String value) {
		if (value == null) return null;
		try {
			UUID id = UUID.fromString(value);
			return id.toString().equalsIgnoreCase(value) ? id : null;
		} catch (IllegalArgumentException invalid) { return null; }
	}

	static boolean canRecover(boolean alive, boolean removed, boolean spectator, boolean sameLevel,
		boolean inBounds, boolean loaded, boolean ticking, double distanceSquared) {
		return alive && !removed && !spectator && sameLevel && inBounds && loaded && ticking
			&& Double.isFinite(distanceSquared) && distanceSquared >= 0 && distanceSquared <= 9;
	}

	static boolean canRescue(boolean operator, boolean stranded, boolean playerLocationValid) {
		return operator && stranded && playerLocationValid;
	}

	private static Component message(String key, Object... args) {
		return Component.translatable("coopcraft.command.settlement." + key, args);
	}

	/** Player inventory also exposes armor and offhand slots. Recovery uses only the 36 main slots. */
	static final class MainInventory implements Container {
		private final Container source;
		MainInventory(Container source) { this.source = source; }
		private void check(int slot) { if (slot < 0 || slot >= getContainerSize()) throw new IndexOutOfBoundsException(slot); }
		@Override public int getContainerSize() { return Math.min(36, source.getContainerSize()); }
		@Override public boolean isEmpty() {
			for (int slot = 0; slot < getContainerSize(); slot++) if (!getItem(slot).isEmpty()) return false;
			return true;
		}
		@Override public ItemStack getItem(int slot) { check(slot); return source.getItem(slot); }
		@Override public ItemStack removeItem(int slot, int amount) { check(slot); return source.removeItem(slot, amount); }
		@Override public ItemStack removeItemNoUpdate(int slot) { check(slot); return source.removeItemNoUpdate(slot); }
		@Override public void setItem(int slot, ItemStack stack) { check(slot); source.setItem(slot, stack); }
		@Override public void setChanged() { source.setChanged(); }
		@Override public boolean stillValid(Player player) { return source.stillValid(player); }
		@Override public int getMaxStackSize() { return source.getMaxStackSize(); }
		@Override public int getMaxStackSize(ItemStack stack) { return source.getMaxStackSize(stack); }
		@Override public boolean canPlaceItem(int slot, ItemStack stack) { check(slot); return source.canPlaceItem(slot, stack); }
		@Override public void clearContent() {
			for (int slot = 0; slot < getContainerSize(); slot++) source.setItem(slot, ItemStack.EMPTY);
			source.setChanged();
		}
	}
}
