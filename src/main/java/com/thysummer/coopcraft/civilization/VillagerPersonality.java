package com.thysummer.coopcraft.civilization;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

public record VillagerPersonality(
	int diligence,
	int selfishness,
	int sociability,
	PersonalityArchetype archetype,
	Set<VillagerTrait> traits
) {
	private static final List<PersonalityArchetype> GENERATED_ARCHETYPES = List.of(
		PersonalityArchetype.HONEST,
		PersonalityArchetype.DECEITFUL,
		PersonalityArchetype.AMBITIOUS,
		PersonalityArchetype.CONTENT,
		PersonalityArchetype.PACIFIST
	);

	public VillagerPersonality {
		if (diligence < 0 || diligence > 100 || selfishness < 0 || selfishness > 100 || sociability < 0 || sociability > 100) {
			throw new IllegalArgumentException("Personality values must be between 0 and 100");
		}
		Objects.requireNonNull(archetype, "archetype");
		Objects.requireNonNull(traits, "traits");
		Set<String> conflictGroups = new HashSet<>();
		for (VillagerTrait trait : traits) {
			if (!conflictGroups.add(trait.conflictGroup())) {
				throw new IllegalArgumentException("Conflicting villager traits: " + trait.conflictGroup());
			}
		}
		List<VillagerTrait> sorted = traits.stream().sorted(Comparator.comparing(VillagerTrait::id)).toList();
		traits = Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
	}

	public VillagerPersonality(int diligence, int selfishness, int sociability) {
		this(diligence, selfishness, sociability, PersonalityArchetype.BALANCED, Set.of());
	}

	public static VillagerPersonality fromVillager(UUID villagerId) {
		Objects.requireNonNull(villagerId, "villagerId");
		Random random = new Random(villagerId.getMostSignificantBits() ^ villagerId.getLeastSignificantBits());
		int diligence = random.nextInt(101);
		int selfishness = random.nextInt(101);
		int sociability = random.nextInt(101);
		PersonalityArchetype archetype = GENERATED_ARCHETYPES.get(random.nextInt(GENERATED_ARCHETYPES.size()));
		List<VillagerTrait> pool = new ArrayList<>(List.of(VillagerTrait.values()));
		Collections.shuffle(pool, random);
		int traitCount = 2 + random.nextInt(3);
		Set<VillagerTrait> traits = new LinkedHashSet<>();
		Set<String> conflictGroups = new HashSet<>();
		for (VillagerTrait trait : pool) {
			if (conflictGroups.add(trait.conflictGroup())) {
				traits.add(trait);
				if (traits.size() == traitCount) {
					break;
				}
			}
		}
		return new VillagerPersonality(diligence, selfishness, sociability, archetype, traits);
	}

	public int basicFoodPoints(int baseFoodPoints) {
		if (baseFoodPoints < 0) {
			throw new IllegalArgumentException("Basic food points cannot be negative");
		}
		int total = baseFoodPoints;
		for (VillagerTrait trait : traits) {
			total += trait.basicFoodBonus();
		}
		return total;
	}

	public int foodRetentionPoints(int baseFoodPoints) {
		int extra = baseFoodPoints * selfishness / 100 + archetype.surplusFoodBonus();
		for (VillagerTrait trait : traits) {
			extra += trait.surplusFoodBonus();
		}
		return basicFoodPoints(baseFoodPoints) + Math.max(0, extra);
	}

	public int workDuration(int baseDuration) {
		return workDuration(baseDuration, WorkType.GENERAL);
	}

	public int workDuration(int baseDuration, WorkType workType) {
		if (baseDuration <= 0) {
			throw new IllegalArgumentException("Work duration must be positive");
		}
		int percent = 125 - diligence / 2;
		percent = percent * archetype.workDurationPercent() / 100;
		for (VillagerTrait trait : traits) {
			percent = percent * trait.generalWorkDurationPercent() / 100;
			int typePercent = switch (workType) {
				case GATHER -> trait.gatherDurationPercent();
				case CRAFT -> trait.craftDurationPercent();
				case BUILD -> trait.buildDurationPercent();
				case GENERAL -> 100;
			};
			percent = percent * typePercent / 100;
		}
		return Math.max(1, baseDuration * percent / 100);
	}

	public double movementSpeed() {
		int percent = 100;
		for (VillagerTrait trait : traits) {
			percent = percent * trait.movementSpeedPercent() / 100;
		}
		return percent / 100.0;
	}

	public int leadershipScore() {
		int score = sociability + archetype.leadershipBonus();
		for (VillagerTrait trait : traits) {
			score += trait.leadershipBonus();
		}
		return score;
	}

	public List<String> traitIds() {
		return traits.stream().map(VillagerTrait::id).toList();
	}

	CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Diligence", diligence);
		tag.putInt("Selfishness", selfishness);
		tag.putInt("Sociability", sociability);
		tag.putString("Archetype", archetype.id());
		ListTag traitList = new ListTag();
		for (VillagerTrait trait : traits) {
			traitList.add(StringTag.valueOf(trait.id()));
		}
		tag.put("Traits", traitList);
		return tag;
	}

	static VillagerPersonality load(CompoundTag tag) {
		return load(tag, PersonalityArchetype.BALANCED, Set.of());
	}

	static VillagerPersonality load(CompoundTag tag, UUID villagerId) {
		VillagerPersonality generated = fromVillager(villagerId);
		return load(tag, generated.archetype(), generated.traits());
	}

	private static VillagerPersonality load(
		CompoundTag tag,
		PersonalityArchetype defaultArchetype,
		Set<VillagerTrait> defaultTraits
	) {
		PersonalityArchetype archetype = tag.contains("Archetype")
			? PersonalityArchetype.byId(tag.getString("Archetype"))
			: defaultArchetype;
		Set<VillagerTrait> traits = new LinkedHashSet<>();
		if (tag.contains("Traits")) {
			ListTag traitList = tag.getList("Traits", ListTag.TAG_STRING);
			for (int i = 0; i < traitList.size(); i++) {
				traits.add(VillagerTrait.byId(traitList.getString(i)));
			}
		} else {
			traits.addAll(defaultTraits);
		}
		return new VillagerPersonality(
			tag.getInt("Diligence"),
			tag.getInt("Selfishness"),
			tag.getInt("Sociability"),
			archetype,
			traits
		);
	}

	public enum WorkType {
		GATHER,
		CRAFT,
		BUILD,
		GENERAL
	}
}
