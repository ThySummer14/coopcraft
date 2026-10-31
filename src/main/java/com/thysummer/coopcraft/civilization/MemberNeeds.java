package com.thysummer.coopcraft.civilization;

import net.minecraft.nbt.CompoundTag;

public record MemberNeeds(int satiety) {
	public static final MemberNeeds DEFAULT = new MemberNeeds(80);

	public MemberNeeds {
		if (satiety < 0 || satiety > 100) {
			throw new IllegalArgumentException("Satiety must be between 0 and 100");
		}
	}

	CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Satiety", satiety);
		return tag;
	}

	static MemberNeeds load(CompoundTag tag) {
		return new MemberNeeds(tag.getInt("Satiety"));
	}
}
