package com.thysummer.coopcraft.civilization;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillagerPersonalityTest {
	@Test
	void generatesStableValuesFromVillagerId() {
		UUID villagerId = UUID.randomUUID();

		VillagerPersonality personality = VillagerPersonality.fromVillager(villagerId);

		assertEquals(personality, VillagerPersonality.fromVillager(villagerId));
		assertTrue(personality.diligence() >= 0 && personality.diligence() <= 100);
		assertTrue(personality.selfishness() >= 0 && personality.selfishness() <= 100);
		assertTrue(personality.sociability() >= 0 && personality.sociability() <= 100);
	}

	@Test
	void generatesTwoToFourNonConflictingTraits() {
		for (int i = 0; i < 30; i++) {
			VillagerPersonality personality = VillagerPersonality.fromVillager(UUID.randomUUID());
			assertTrue(personality.archetype() != PersonalityArchetype.BALANCED);
			assertTrue(personality.traits().size() >= 2 && personality.traits().size() <= 4);
			assertEquals(personality.traits().size(), personality.traits().stream().map(VillagerTrait::conflictGroup).distinct().count());
			assertEquals(personality.traits().size(), new HashSet<>(personality.traitIds()).size());
		}
	}

	@Test
	void preservesPersonalityThroughMemberSaveRoundTrip() {
		UUID villagerId = UUID.randomUUID();
		VillagerPersonality personality = new VillagerPersonality(
			73, 88, 41, PersonalityArchetype.DECEITFUL, Set.of(VillagerTrait.GREEDY, VillagerTrait.FAST)
		);
		SettlementMember member = new SettlementMember(villagerId, UUID.randomUUID(), 9, personality);

		SettlementMember restored = SettlementMember.load(member.save());

		assertEquals(personality, restored.personality());
		assertEquals(9, restored.joinedDay());
	}

	@Test
	void migratesLegacyNumbersWithoutReplacingThem() {
		UUID villagerId = UUID.randomUUID();
		VillagerPersonality generated = VillagerPersonality.fromVillager(villagerId);
		CompoundTag legacy = new CompoundTag();
		legacy.putInt("Diligence", 73);
		legacy.putInt("Selfishness", 88);
		legacy.putInt("Sociability", 41);

		VillagerPersonality migrated = VillagerPersonality.load(legacy, villagerId);

		assertEquals(73, migrated.diligence());
		assertEquals(88, migrated.selfishness());
		assertEquals(41, migrated.sociability());
		assertEquals(generated.archetype(), migrated.archetype());
		assertEquals(generated.traits(), migrated.traits());
	}

	@Test
	void scalesWorkDurationWithoutChangingExtremeBounds() {
		assertEquals(75, new VillagerPersonality(0, 0, 0).workDuration(60));
		assertEquals(60, new VillagerPersonality(50, 0, 0).workDuration(60));
		assertEquals(45, new VillagerPersonality(100, 0, 0).workDuration(60));
		assertThrows(IllegalArgumentException.class, () -> new VillagerPersonality(101, 0, 0));
	}

	@Test
	void keepsLegacyNumericFoodRetention() {
		assertEquals(12, new VillagerPersonality(100, 0, 100).foodRetentionPoints(12));
		assertEquals(18, new VillagerPersonality(0, 50, 0).foodRetentionPoints(12));
		assertEquals(24, new VillagerPersonality(0, 100, 0).foodRetentionPoints(12));
	}

	@Test
	void appliesArchetypeEffects() {
		assertEquals(12, withTraits(PersonalityArchetype.HONEST).foodRetentionPoints(12));
		assertEquals(26, withTraits(PersonalityArchetype.DECEITFUL).foodRetentionPoints(12));
		assertEquals(22, withTraits(PersonalityArchetype.AMBITIOUS).foodRetentionPoints(12));
		assertEquals(14, withTraits(PersonalityArchetype.CONTENT).foodRetentionPoints(12));
		assertEquals(18, withTraits(PersonalityArchetype.PACIFIST).foodRetentionPoints(12));
		assertEquals(57, withTraits(PersonalityArchetype.AMBITIOUS).workDuration(60));
		assertEquals(90, withTraits(PersonalityArchetype.AMBITIOUS).leadershipScore());
		assertEquals(10, withTraits(PersonalityArchetype.CONTENT).leadershipScore());
	}

	@Test
	void appliesEveryTraitToItsOwnDomain() {
		assertEquals(26, withTraits(PersonalityArchetype.BALANCED, VillagerTrait.GREEDY).foodRetentionPoints(12));
		assertEquals(12, withTraits(PersonalityArchetype.BALANCED, VillagerTrait.GENEROUS).foodRetentionPoints(12));
		assertEquals(16, withTraits(PersonalityArchetype.BALANCED, VillagerTrait.GLUTTONOUS).basicFoodPoints(12));
		assertEquals(1.2, withTraits(PersonalityArchetype.BALANCED, VillagerTrait.FAST).movementSpeed(), 0.001);
		assertEquals(0.85, withTraits(PersonalityArchetype.BALANCED, VillagerTrait.SLOW).movementSpeed(), 0.001);
		assertEquals(51, withTraits(PersonalityArchetype.BALANCED, VillagerTrait.STRONG).workDuration(60, VillagerPersonality.WorkType.GATHER));
		assertEquals(69, withTraits(PersonalityArchetype.BALANCED, VillagerTrait.WEAK).workDuration(60, VillagerPersonality.WorkType.GATHER));
		assertEquals(54, withTraits(PersonalityArchetype.BALANCED, VillagerTrait.WISE).workDuration(60, VillagerPersonality.WorkType.CRAFT));
		assertEquals(51, withTraits(PersonalityArchetype.BALANCED, VillagerTrait.FAST_BUILDER).workDuration(60, VillagerPersonality.WorkType.BUILD));
		assertEquals(69, withTraits(PersonalityArchetype.BALANCED, VillagerTrait.SLOW_BUILDER).workDuration(60, VillagerPersonality.WorkType.BUILD));
		assertEquals(70, withTraits(PersonalityArchetype.BALANCED, VillagerTrait.ATTRACTIVE).leadershipScore());
		assertEquals(30, withTraits(PersonalityArchetype.BALANCED, VillagerTrait.UGLY).leadershipScore());
		assertEquals(57, withTraits(PersonalityArchetype.BALANCED, VillagerTrait.LUCKY).workDuration(60));
		assertEquals(63, withTraits(PersonalityArchetype.BALANCED, VillagerTrait.UNLUCKY).workDuration(60));
	}

	@Test
	void rejectsConflictingTraits() {
		assertThrows(IllegalArgumentException.class, () -> withTraits(
			PersonalityArchetype.BALANCED, VillagerTrait.FAST, VillagerTrait.SLOW
		));
		assertThrows(IllegalArgumentException.class, () -> withTraits(
			PersonalityArchetype.BALANCED, VillagerTrait.GREEDY, VillagerTrait.GENEROUS
		));
	}

	private static VillagerPersonality withTraits(PersonalityArchetype archetype, VillagerTrait... traits) {
		return new VillagerPersonality(50, 50, 50, archetype, Set.of(traits));
	}
}
