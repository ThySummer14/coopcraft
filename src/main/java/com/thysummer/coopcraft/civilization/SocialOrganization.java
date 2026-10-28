package com.thysummer.coopcraft.civilization;

import java.util.Objects;

import net.minecraft.nbt.CompoundTag;

public record SocialOrganization(
	SocialForm form,
	OwnershipRegime ownership,
	LaborRegime labor,
	DistributionPolicy distribution
) {
	public static final SocialOrganization PRIMITIVE_COMMUNE = new SocialOrganization(
		SocialForm.PRIMITIVE_COMMUNE,
		OwnershipRegime.COMMUNAL,
		LaborRegime.COMMON_OBLIGATION,
		DistributionPolicy.BASIC_NEEDS
	);

	public SocialOrganization {
		Objects.requireNonNull(form, "form");
		Objects.requireNonNull(ownership, "ownership");
		Objects.requireNonNull(labor, "labor");
		Objects.requireNonNull(distribution, "distribution");
	}

	CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putString("Form", form.id());
		tag.putString("Ownership", ownership.id());
		tag.putString("Labor", labor.id());
		tag.putString("Distribution", distribution.id());
		return tag;
	}

	static SocialOrganization load(CompoundTag tag) {
		return fromIds(
			tag.getString("Form"),
			tag.getString("Ownership"),
			tag.getString("Labor"),
			tag.getString("Distribution")
		);
	}

	public static SocialOrganization fromIds(String form, String ownership, String labor, String distribution) {
		return new SocialOrganization(
			SocialForm.byId(form),
			OwnershipRegime.byId(ownership),
			LaborRegime.byId(labor),
			DistributionPolicy.byId(distribution)
		);
	}

	public enum SocialForm {
		PRIMITIVE_COMMUNE("primitive_commune");

		private final String id;

		SocialForm(String id) {
			this.id = id;
		}

		public String id() {
			return id;
		}

		public String translationKey() {
			return "coopcraft.social.form." + id;
		}

		static SocialForm byId(String id) {
			for (SocialForm value : values()) {
				if (value.id.equals(id)) {
					return value;
				}
			}
			throw new IllegalArgumentException("Unknown social form: " + id);
		}
	}

	public enum OwnershipRegime {
		COMMUNAL("communal");

		private final String id;

		OwnershipRegime(String id) {
			this.id = id;
		}

		public String id() {
			return id;
		}

		public String translationKey() {
			return "coopcraft.social.ownership." + id;
		}

		static OwnershipRegime byId(String id) {
			for (OwnershipRegime value : values()) {
				if (value.id.equals(id)) {
					return value;
				}
			}
			throw new IllegalArgumentException("Unknown ownership regime: " + id);
		}
	}

	public enum LaborRegime {
		COMMON_OBLIGATION("common_obligation");

		private final String id;

		LaborRegime(String id) {
			this.id = id;
		}

		public String id() {
			return id;
		}

		public String translationKey() {
			return "coopcraft.social.labor." + id;
		}

		static LaborRegime byId(String id) {
			for (LaborRegime value : values()) {
				if (value.id.equals(id)) {
					return value;
				}
			}
			throw new IllegalArgumentException("Unknown labor regime: " + id);
		}
	}

	public enum DistributionPolicy {
		BASIC_NEEDS("basic_needs");

		private final String id;

		DistributionPolicy(String id) {
			this.id = id;
		}

		public String id() {
			return id;
		}

		public String translationKey() {
			return "coopcraft.social.distribution." + id;
		}

		static DistributionPolicy byId(String id) {
			for (DistributionPolicy value : values()) {
				if (value.id.equals(id)) {
					return value;
				}
			}
			throw new IllegalArgumentException("Unknown distribution policy: " + id);
		}
	}
}
