package com.thysummer.coopcraft.automation;

import java.util.List;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.world.item.ItemStack;

final class MachineTransfer {
	private MachineTransfer() {
	}

	static boolean insertAll(Storage<ItemVariant> storage, List<ItemStack> stacks) {
		try (Transaction transaction = Transaction.openOuter()) {
			for (ItemStack stack : stacks) {
				if (storage.insert(ItemVariant.of(stack), stack.getCount(), transaction) != stack.getCount()) {
					return false;
				}
			}
			transaction.commit();
		}
		return true;
	}

	static long insert(Storage<ItemVariant> storage, ItemStack stack) {
		try (Transaction transaction = Transaction.openOuter()) {
			long inserted = storage.insert(ItemVariant.of(stack), stack.getCount(), transaction);
			transaction.commit();
			return inserted;
		}
	}
}
