package com.thysummer.coopcraft.civilization.simulation;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FieldCraftTeachingTest {
	@Test void persistedSocialTimeBecomesNextDaysLaborCostWithoutLearningHours() {
		SimulationWorld world = new SimulationWorld();
		world.registerCamp(new Camp("camp", "Camp"));
		world.registerHousehold(new Household("first", "camp"));
		world.registerHousehold(new Household("second", "camp"));
		Person teacher = new Person("teacher", "camp", "first", Person.AgeBand.ADULT, 0, 50);
		Person learner = new Person("learner", "camp", "second", Person.AgeBand.ADULT, 0, 50);
		world.registerPerson(teacher); world.registerPerson(learner);
		teacher.setSkill(WorkType.CRAFT, 1.8);
		assertTrue(FieldCraftTeaching.apply(world, teacher, learner, FieldCraftTeaching.inspect(teacher, learner), Map.of()));
		teacher.startDay(); learner.startDay();
		assertEquals(1, teacher.previousSocialHours()); assertEquals(1, learner.previousSocialHours());
		assertEquals(5, SimulationConfig.defaults().effortHours() - learner.previousSocialHours());
		assertEquals(0, teacher.socialHours()); assertEquals(0, learner.socialHours());
		assertEquals(0, teacher.learningHours()); assertEquals(0, learner.learningHours());
	}

	@Test void defaultsAndTrustAffectOnlyGainWithTeacherAndGlobalCeilings() {
		Person teacher = new Person("t", "c", "h1", Person.AgeBand.ADULT, 0, 50);
		Person learner = new Person("l", "c", "h2", Person.AgeBand.ADULT, 0, 50);
		assertEquals("teacher_unskilled", FieldCraftTeaching.inspect(teacher, learner).status());
		teacher.setSkill(WorkType.CRAFT, 1.8);
		assertEquals(1.12, FieldCraftTeaching.inspect(teacher, learner).after(), 1e-10);
		teacher.adjustTrust("l", 100); learner.adjustTrust("t", 100);
		assertEquals(1.2, FieldCraftTeaching.inspect(teacher, learner).after(), 1e-10);
		teacher.setSkill(WorkType.CRAFT, 20);
		assertEquals(2, FieldCraftTeaching.inspect(teacher, learner).after());
		learner.setSkill(WorkType.CRAFT, 2);
		assertEquals("skill_maximum", FieldCraftTeaching.inspect(teacher, learner).status());
	}
}
