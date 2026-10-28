package com.thysummer.coopcraft.civilization.simulation;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

public final class KnowledgeSystem {
	private static final int TEACHING_HOURS = 1;

	private final SimulationWorld world;
	private final KnowledgeConfig config;
	private long lastLearningDay = Long.MIN_VALUE;

	public KnowledgeSystem(SimulationWorld world, KnowledgeConfig config) {
		this.world = world;
		this.config = config;
	}

	public void organizeLearning(Random random) {
		if (lastLearningDay == world.day()) {
			return;
		}
		lastLearningDay = world.day();
		if (!config.enabled()) {
			return;
		}
		Set<String> participants = new HashSet<>();
		for (Person learner : world.people()) {
			if (participants.contains(learner.id()) || !canLearn(learner)) {
				continue;
			}
			Optional<Lesson> selected = findLesson(learner, participants);
			if (selected.isEmpty()) {
				continue;
			}
			Lesson lesson = selected.get();
			participants.add(learner.id());
			participants.add(lesson.teacher().id());
			teach(lesson, random);
		}
	}

	public boolean canPerform(Person person, WorkType workType) {
		return !config.enabled() || person.knowsTechnique(workType);
	}

	public long techniqueHolders(String campId, WorkType workType) {
		return world.peopleInCamp(campId).stream().filter(person -> person.knowsTechnique(workType)).count();
	}

	private Optional<Lesson> findLesson(Person learner, Set<String> participants) {
		return world.peopleInCamp(learner.campId()).stream()
			.filter(teacher -> !participants.contains(teacher.id()))
			.filter(teacher -> canTeach(teacher) && canCommunicate(learner, teacher))
			.flatMap(teacher -> java.util.Arrays.stream(WorkType.values())
				.filter(teacher::knowsTechnique)
				.filter(workType -> teacher.skill(workType) >= config.minimumTeacherSkill())
				.filter(workType -> !learner.knowsTechnique(workType)
					|| teacher.skill(workType) - learner.skill(workType) >= config.minimumSkillGap())
				.map(workType -> new Lesson(teacher, learner, workType)))
			.max(Comparator.comparingDouble((Lesson lesson) -> lesson.acquisition() ? 1.0 : 0.0)
				.thenComparingDouble(Lesson::skillGap)
				.thenComparing(lesson -> lesson.teacher().id())
				.thenComparing(lesson -> lesson.workType().name()));
	}

	private void teach(Lesson lesson, Random random) {
		Person teacher = lesson.teacher();
		Person learner = lesson.learner();
		WorkType workType = lesson.workType();
		teacher.assignLearningHours(TEACHING_HOURS);
		learner.assignLearningHours(TEACHING_HOURS);
		double skillBefore = learner.skill(workType);
		boolean acquired = lesson.acquisition() && random.nextDouble() < config.techniqueLearningChance();
		double skillAfter = skillBefore;
		if (acquired) {
			learner.learnTechnique(workType);
		} else if (!lesson.acquisition()) {
			double relationshipFactor = 0.75
				+ (learner.trustIn(teacher.id()) + teacher.trustIn(learner.id())) / 400.0;
			double gain = config.learningRate() * (teacher.skill(workType) - skillBefore) * relationshipFactor;
			skillAfter = Math.max(skillBefore, Math.min(
				config.maximumSkill(), Math.min(teacher.skill(workType), skillBefore + gain)
			));
			if (skillAfter > skillBefore) {
				learner.setSkill(workType, skillAfter);
			}
		}
		world.recordSocialEvent(
			EventType.TEACHING_SESSION,
			java.util.List.of(teacher.id(), learner.id()),
			Map.of(
				"workType", workType.name(),
				"techniqueAcquired", Boolean.toString(acquired),
				"skillBefore", Double.toString(skillBefore),
				"skillAfter", Double.toString(skillAfter),
				"hours", Integer.toString(TEACHING_HOURS),
				"sameHousehold", Boolean.toString(teacher.householdId().equals(learner.householdId()))
			),
			java.util.List.of()
		);
	}

	private static boolean canLearn(Person person) {
		return person.isAlive() && person.health() > 20 && person.ageBand() != Person.AgeBand.INFANT;
	}

	private static boolean canTeach(Person person) {
		return person.canMakeDecisions();
	}

	private static boolean canCommunicate(Person learner, Person teacher) {
		return !learner.id().equals(teacher.id()) && (learner.householdId().equals(teacher.householdId())
			|| learner.trustIn(teacher.id()) >= 20 || teacher.trustIn(learner.id()) >= 20);
	}

	private record Lesson(Person teacher, Person learner, WorkType workType) {
		boolean acquisition() {
			return !learner.knowsTechnique(workType);
		}

		double skillGap() {
			return Math.max(0, teacher.skill(workType) - learner.skill(workType));
		}
	}
}
