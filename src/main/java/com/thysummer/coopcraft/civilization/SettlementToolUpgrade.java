package com.thysummer.coopcraft.civilization;

import java.util.Optional;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/** Upgrades one existing tool without repairing it or replacing its other components. */
public final class SettlementToolUpgrade {
	private SettlementToolUpgrade() {
	}

	/** Explicitly removed enchantments remain disabled; polishing must not restore that component. */
	public static boolean supportsPolishing(ItemStack source) {
		return !source.isEmpty() && source.is(Items.STONE_PICKAXE) && source.getCount() == 1
			&& source.get(DataComponents.ENCHANTMENTS) != null;
	}

	/** Returns a changed copy, or no result when the stack is invalid or already polished. */
	public static Optional<ItemStack> polish(ItemStack source, Holder<Enchantment> efficiency,
		Holder<Enchantment> unbreaking) {
		if (!supportsPolishing(source)) {
			return Optional.empty();
		}
		ItemEnchantments enchantments = source.getEnchantments();
		if (enchantments.getLevel(efficiency) >= 1 && enchantments.getLevel(unbreaking) >= 1) {
			return Optional.empty();
		}
		ItemEnchantments.Mutable upgraded = new ItemEnchantments.Mutable(enchantments);
		upgraded.upgrade(efficiency, 1);
		upgraded.upgrade(unbreaking, 1);
		ItemStack polished = source.copy();
		polished.set(DataComponents.ENCHANTMENTS, upgraded.toImmutable());
		return Optional.of(polished);
	}
}
