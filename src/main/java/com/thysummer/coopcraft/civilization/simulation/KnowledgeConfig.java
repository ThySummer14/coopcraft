package com.thysummer.coopcraft.civilization.simulation;

public record KnowledgeConfig(
	boolean enabled,
	double minimumTeacherSkill,
	double minimumSkillGap,
	double learningRate,
	double techniqueLearningChance,
	double maximumSkill
) {
	public KnowledgeConfig {
		if (!Double.isFinite(minimumTeacherSkill) || minimumTeacherSkill <= 0
			|| !Double.isFinite(minimumSkillGap) || minimumSkillGap < 0
			|| !Double.isFinite(learningRate) || learningRate < 0 || learningRate > 1
			|| !Double.isFinite(techniqueLearningChance) || techniqueLearningChance < 0 || techniqueLearningChance > 1
			|| !Double.isFinite(maximumSkill) || maximumSkill < minimumTeacherSkill) {
			throw new IllegalArgumentException("Invalid knowledge limits or learning coefficients");
		}
	}

	public static KnowledgeConfig disabled() {
		return new KnowledgeConfig(false, 1.1, 0.1, 0.2, 0.75, 2.0);
	}

	public static KnowledgeConfig enabledDefaults() {
		return new KnowledgeConfig(true, 1.1, 0.1, 0.2, 0.75, 2.0);
	}
}
