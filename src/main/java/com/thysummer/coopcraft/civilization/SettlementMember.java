package com.thysummer.coopcraft.civilization;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;

public record SettlementMember(UUID villagerId, UUID settlementId, long joinedDay, VillagerPersonality personality) {
	public SettlementMember {
		Objects.requireNonNull(villagerId, "villagerId");
		Objects.requireNonNull(settlementId, "settlementId");
		Objects.requireNonNull(personality, "personality");
	}

	public SettlementMember(UUID villagerId, UUID settlementId, long joinedDay) {
		this(villagerId, settlementId, joinedDay, VillagerPersonality.fromVillager(villagerId));
	}

	CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putUUID("VillagerId", villagerId);
		tag.putUUID("SettlementId", settlementId);
		tag.putLong("JoinedDay", joinedDay);
		tag.put("Personality", personality.save());
		return tag;
	}

	static SettlementMember load(CompoundTag tag) {
		UUID villagerId = tag.getUUID("VillagerId");
		VillagerPersonality personality = tag.contains("Personality")
			? VillagerPersonality.load(tag.getCompound("Personality"), villagerId)
			: VillagerPersonality.fromVillager(villagerId);
		return new SettlementMember(villagerId, tag.getUUID("SettlementId"), tag.getLong("JoinedDay"), personality);
	}
}
