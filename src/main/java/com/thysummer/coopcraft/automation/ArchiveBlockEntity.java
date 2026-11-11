package com.thysummer.coopcraft.automation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class ArchiveBlockEntity extends BlockEntity {
	private Catalog catalog;

	public ArchiveBlockEntity(BlockPos pos, BlockState state) {
		super(Automation.ARCHIVE_ENTITY, pos, state);
	}

	public Catalog record(Container stockpile, UUID settlementId, long recordedTick) {
		Map<ResourceLocation, Long> items = new LinkedHashMap<>();
		for (int slot = 0; slot < stockpile.getContainerSize(); slot++) {
			ItemStack stack = stockpile.getItem(slot);
			if (!stack.isEmpty()) {
				items.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()), (long) stack.getCount(), Long::sum);
			}
		}
		catalog = new Catalog(recordedTick, settlementId, items);
		setChanged();
		notifyNeighbors();
		return catalog;
	}

	public boolean synchronize(Catalog source) {
		if (catalog != null && catalog.recordedTick() >= source.recordedTick()) {
			return false;
		}
		catalog = source;
		setChanged();
		notifyNeighbors();
		return true;
	}

	public Optional<Catalog> catalog() {
		return Optional.ofNullable(catalog);
	}

	public boolean hasRecord() {
		return catalog != null;
	}

	public int entries() {
		return catalog == null ? 0 : catalog.items().size();
	}

	public long totalItems() {
		return catalog == null ? 0 : catalog.items().values().stream().mapToLong(Long::longValue).sum();
	}

	public long count(Item item) {
		return catalog == null ? 0 : catalog.items().getOrDefault(BuiltInRegistries.ITEM.getKey(item), 0L);
	}

	public Component describe(Item item) {
		if (catalog == null) {
			return Component.translatable("coopcraft.archive.empty");
		}
		if (item == Items.AIR) {
			return Component.translatable("coopcraft.archive.summary", entries(), totalItems(), catalog.recordedTick());
		}
		return Component.translatable("coopcraft.archive.query", new ItemStack(item).getHoverName(), count(item));
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putBoolean("HasCatalog", catalog != null);
		if (catalog == null) {
			return;
		}
		tag.putLong("RecordedTick", catalog.recordedTick());
		tag.putUUID("SettlementId", catalog.settlementId());
		ListTag items = new ListTag();
		catalog.items().forEach((item, count) -> {
			CompoundTag entry = new CompoundTag();
			entry.putString("Item", item.toString());
			entry.putLong("Count", count);
			items.add(entry);
		});
		tag.put("Catalog", items);
	}

	@Override
	public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		catalog = null;
		if (!tag.getBoolean("HasCatalog")) {
			return;
		}
		Map<ResourceLocation, Long> items = new LinkedHashMap<>();
		ListTag saved = tag.getList("Catalog", ListTag.TAG_COMPOUND);
		for (int index = 0; index < saved.size(); index++) {
			CompoundTag entry = saved.getCompound(index);
			items.put(ResourceLocation.parse(entry.getString("Item")), entry.getLong("Count"));
		}
		catalog = new Catalog(tag.getLong("RecordedTick"), tag.getUUID("SettlementId"), items);
	}

	private void notifyNeighbors() {
		if (level != null) {
			level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
		}
	}

	public record Catalog(long recordedTick, UUID settlementId, Map<ResourceLocation, Long> items) {
		public Catalog {
			Objects.requireNonNull(settlementId, "settlementId");
			items = Map.copyOf(items);
		}
	}
}
