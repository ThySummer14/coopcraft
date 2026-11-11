package com.thysummer.coopcraft.civilization.simulation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One explicit pair only. Social time is persisted and reduces the next daily labour budget. */
public final class FieldCraftTeaching {
	private FieldCraftTeaching() {}
	public record Plan(String status, double teacherSkill, double before, double after,
		int teacherHours, int learnerHours, int teacherTrust, int learnerTrust, boolean sameHousehold) {}

	public static Plan inspect(Person teacher, Person learner) {
		KnowledgeConfig config = KnowledgeConfig.enabledDefaults();
		double skill = teacher.skill(WorkType.CRAFT), before = learner.skill(WorkType.CRAFT);
		int teacherTrust = teacher.trustIn(learner.id()), learnerTrust = learner.trustIn(teacher.id());
		boolean sameHousehold = teacher.householdId().equals(learner.householdId());
		String status = "ready";
		if (teacher == learner || teacher.id().equals(learner.id())) status = "same_resident";
		else if (!teacher.canMakeDecisions() || !learner.canMakeDecisions()
			|| !teacher.campId().equals(learner.campId())) status = "unavailable";
		else if (!Double.isFinite(skill) || !Double.isFinite(before)) status = "unavailable";
		else if (skill < config.minimumTeacherSkill()) status = "teacher_unskilled";
		else if (before >= config.maximumSkill()) status = "skill_maximum";
		else if (skill - before < config.minimumSkillGap()) status = "skill_gap";
		else if (teacher.socialHours() > 5 || learner.socialHours() > 5) status = "exhausted";
		double after = status.equals("ready") ? Math.max(before, Math.min(config.maximumSkill(),
			Math.min(skill, before + config.learningRate() * (skill - before)
				* (0.75 + (teacherTrust + learnerTrust) / 400.0)))) : before;
		return new Plan(status, skill, before, after, teacher.socialHours(), learner.socialHours(),
			teacherTrust, learnerTrust, sameHousehold);
	}

	/** No callbacks or external operations occur between validation and both time charges. */
	public static boolean apply(SimulationWorld world, Person teacher, Person learner, Plan expected,
		Map<String, String> context) {
		Map<String, String> details = new LinkedHashMap<>(Map.copyOf(context));
		if (!expected.status().equals("ready") || !expected.equals(inspect(teacher, learner))
			|| world.person(teacher.id()) != teacher || world.person(learner.id()) != learner) return false;
		details.put("workType", WorkType.CRAFT.name());
		details.put("teacherSkill", Double.toString(expected.teacherSkill()));
		details.put("skillBefore", Double.toString(expected.before()));
		details.put("skillAfter", Double.toString(expected.after()));
		details.put("hours", "1");
		details.put("costTiming", "next_settled_day");
		details.put("teacherHours", "1");
		details.put("learnerHours", "1");
		details.put("techniqueAcquired", "false");
		details.put("sameHousehold", Boolean.toString(expected.sameHousehold()));
		details.put("source", "minecraft_field_craft");
		teacher.addSocialHours(1);
		learner.addSocialHours(1);
		learner.setSkill(WorkType.CRAFT, expected.after());
		world.recordSocialEvent(EventType.TEACHING_SESSION, List.of(teacher.id(), learner.id()), details, List.of());
		return true;
	}
}
