package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Bounded records of completed physical handovers, never a simulation event or shared knowledge. */
final class SettlementFoodSharingMemories {
	static final int MAX_PER_CAMP = 32, MAX_PER_DIMENSION = 256, MAX_VISIBLE = 3, MAX_NAME = 64;
	private static final Set<String> ROOT_KEYS = Set.of("Version", "Deliveries");
	private static final Set<String> ENTRY_KEYS = Set.of("Id", "Camp", "Giver", "Recipient", "GameTime",
		"GiverName", "RecipientName", "Item", "ItemName", "Count");
	record Delivery(UUID id, UUID camp, UUID giver, UUID recipient, long gameTime,
		String giverName, String recipientName, String item, String itemName, int count) { }
	private final List<Delivery> deliveries = new ArrayList<>();
	private Tag preserved;

	boolean blocked() { return preserved != null; }

	boolean record(SettlementSavedData data, Settlement camp, SettlementFoodSharing.Completion completion) {
		if (completion == null || !data.canonicalSettlement(camp)) return false;
		Delivery delivery = completion.consume(data, camp);
		if (delivery == null || blocked()) return false;
		for (Delivery old : deliveries) if (old.id().equals(delivery.id())) return false;
		long campCount = deliveries.stream().filter(old -> old.camp().equals(delivery.camp())).count();
		if (campCount >= MAX_PER_CAMP) {
			for (int i = 0; i < deliveries.size(); i++) if (deliveries.get(i).camp().equals(delivery.camp())) {
				deliveries.remove(i); break;
			}
		}
		if (deliveries.size() >= MAX_PER_DIMENSION) deliveries.removeFirst();
		deliveries.add(delivery);
		return true;
	}

	List<Delivery> recent(UUID camp, UUID resident) {
		List<Delivery> result = new ArrayList<>();
		for (int i = deliveries.size() - 1; i >= 0 && result.size() < MAX_VISIBLE; i--) {
			Delivery entry = deliveries.get(i);
			if (entry.camp().equals(camp) && (entry.giver().equals(resident) || entry.recipient().equals(resident))) result.add(entry);
		}
		return List.copyOf(result);
	}

	static List<Component> inspect(SettlementSavedData data, Settlement camp, UUID resident) {
		return data.foodSharingMemoriesOf(camp, resident).stream().map(entry -> (Component) Component.translatable(
			"coopcraft.interaction.food_sharing.memory", entry.gameTime(), Component.literal(entry.giverName()),
			Component.literal(entry.recipientName()), entry.count(), foodName(entry))).toList();
	}

	/** Fixed ordinary-food identity, localized without consulting today's inventory or entity name. */
	static Component foodName(Delivery delivery) {
		String key = switch (delivery.item()) {
			case "minecraft:bread" -> "item.minecraft.bread";
			case "minecraft:carrot" -> "item.minecraft.carrot";
			case "minecraft:potato" -> "item.minecraft.potato";
			case "minecraft:beetroot" -> "item.minecraft.beetroot";
			case "minecraft:sweet_berries" -> "item.minecraft.sweet_berries";
			default -> null;
		};
		return (key == null ? Component.literal(delivery.itemName()) : Component.translatable(key))
			.append(Component.literal(" [" + delivery.item() + "]"));
	}

	/** Snapshot only plain bounded text; never preserve component styles, events or formatting codes. */
	static String snapshotName(String name) {
		if (name == null) return "?";
		StringBuilder result = new StringBuilder();
		name.codePoints().filter(cp -> !Character.isISOControl(cp) && cp != 0xA7
			&& Character.getType(cp) != Character.FORMAT && Character.getType(cp) != Character.SURROGATE && cp != 0x2028 && cp != 0x2029)
			.limit(MAX_NAME).forEach(result::appendCodePoint);
		return result.isEmpty() ? "?" : result.toString();
	}
	private static boolean validName(String name) { return !name.isEmpty() && name.equals(snapshotName(name)); }

	Tag save() {
		if (preserved != null) return preserved.copy();
		if (deliveries.isEmpty()) return null; // Legacy worlds need no state just to be inspected or saved.
		CompoundTag root = new CompoundTag(); root.putInt("Version", 1);
		ListTag list = new ListTag();
		for (Delivery delivery : deliveries) {
			CompoundTag entry = new CompoundTag();
			entry.putUUID("Id", delivery.id()); entry.putUUID("Camp", delivery.camp());
			entry.putUUID("Giver", delivery.giver()); entry.putUUID("Recipient", delivery.recipient());
			entry.putLong("GameTime", delivery.gameTime());
			entry.putString("GiverName", delivery.giverName()); entry.putString("RecipientName", delivery.recipientName());
			entry.putString("Item", delivery.item()); entry.putString("ItemName", delivery.itemName());
			entry.putInt("Count", delivery.count()); list.add(entry);
		}
		root.put("Deliveries", list); return root;
	}

	static SettlementFoodSharingMemories load(Tag raw) {
		SettlementFoodSharingMemories result = new SettlementFoodSharingMemories();
		if (raw == null) return result;
		try {
			if (!(raw instanceof CompoundTag root) || !root.getAllKeys().equals(ROOT_KEYS)
				|| !root.contains("Version", Tag.TAG_INT) || root.getInt("Version") != 1
				|| !(root.get("Deliveries") instanceof ListTag list) || list.size() > MAX_PER_DIMENSION
				|| !(list.getElementType() == Tag.TAG_COMPOUND || list.isEmpty() && list.getElementType() == Tag.TAG_END)) return preserve(raw);
			Set<UUID> ids = new HashSet<>(); var counts = new HashMap<UUID, Integer>();
			for (int i = 0; i < list.size(); i++) {
				CompoundTag entry = list.getCompound(i);
				if (!entry.getAllKeys().equals(ENTRY_KEYS) || !entry.hasUUID("Id") || !entry.hasUUID("Camp")
					|| !entry.hasUUID("Giver") || !entry.hasUUID("Recipient") || !entry.contains("GameTime", Tag.TAG_LONG)
					|| entry.getLong("GameTime") < 0 || !entry.contains("Count", Tag.TAG_INT) || entry.getInt("Count") != 1) return preserve(raw);
				for (String key : List.of("GiverName", "RecipientName", "ItemName"))
					if (!entry.contains(key, Tag.TAG_STRING) || !validName(entry.getString(key))) return preserve(raw);
				String item = entry.getString("Item");
				if (!entry.contains("Item", Tag.TAG_STRING) || item.length() > 128 || ResourceLocation.tryParse(item) == null
					|| !item.equals(ResourceLocation.tryParse(item).toString())) return preserve(raw);
				UUID id = entry.getUUID("Id"), camp = entry.getUUID("Camp"), giver = entry.getUUID("Giver"), recipient = entry.getUUID("Recipient");
				if (!ids.add(id) || giver.equals(recipient) || counts.merge(camp, 1, Integer::sum) > MAX_PER_CAMP) return preserve(raw);
				result.deliveries.add(new Delivery(id, camp, giver, recipient, entry.getLong("GameTime"),
					entry.getString("GiverName"), entry.getString("RecipientName"), item, entry.getString("ItemName"), 1));
			}
			return result;
		} catch (RuntimeException malformed) { return preserve(raw); }
	}
	private static SettlementFoodSharingMemories preserve(Tag raw) {
		SettlementFoodSharingMemories result = new SettlementFoodSharingMemories(); result.preserved = raw.copy(); return result;
	}
}
