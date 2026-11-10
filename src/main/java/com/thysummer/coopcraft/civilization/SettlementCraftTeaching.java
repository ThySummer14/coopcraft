package com.thysummer.coopcraft.civilization;

import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import com.thysummer.coopcraft.civilization.simulation.FieldCraftTeaching;
import com.thysummer.coopcraft.civilization.simulation.Person;

/** Explicit field lesson transaction. The caller supplies a fresh, real-world authority check. */
public final class SettlementCraftTeaching {
	private SettlementCraftTeaching() {}
	public record Result(String status, long coreDay, double teacherSkill, double skillBefore,
		double skillAfter, int teacherHours, int learnerHours) {}

	public static Result inspect(SettlementSavedData data, Settlement camp, UUID teacherUuid, UUID learnerUuid) {
		Snapshot snapshot = capture(data, camp, teacherUuid, learnerUuid);
		return snapshot.result;
	}

	public static Result teach(SettlementSavedData data, Settlement camp, UUID teacherUuid, UUID learnerUuid,
		BooleanSupplier authority) {
		Snapshot before = capture(data, camp, teacherUuid, learnerUuid);
		if (!before.result.status().equals("ready")) return before.result;
		if (authority == null || !authority.getAsBoolean()) return status(before.result, "authority_lost");
		Snapshot after = capture(data, camp, teacherUuid, learnerUuid);
		if (!before.same(after)) return status(before.result, "stale");
		if (!FieldCraftTeaching.apply(before.state.world(), before.teacher, before.learner, before.plan,
			Map.of("teacherUuid", teacherUuid.toString(), "learnerUuid", learnerUuid.toString(),
				"settlement", camp.id().toString()))) return status(before.result, "stale");
		before.state.recordCraftLesson(before.teacher.id(), before.learner.id());
		data.setDirty();
		return new Result("taught", before.result.coreDay(), before.plan.teacherSkill(), before.plan.before(),
			before.plan.after(), before.teacher.socialHours(), before.learner.socialHours());
	}

	private static Snapshot capture(SettlementSavedData data, Settlement camp, UUID teacherUuid, UUID learnerUuid) {
		if (data == null || camp == null) return blocked("disabled", -1);
		SettlementSimulationState state = data.simulationOf(camp).orElse(null);
		long day = state == null ? -1 : state.world().day();
		if (!data.canonicalSettlement(camp) || state == null || !state.survivalEnabled()
			|| !state.skillsEnabled() || !state.normsEnabled()) return blocked("disabled", day);
		if (teacherUuid == null || learnerUuid == null) return blocked("not_member", day);
		if (teacherUuid.equals(learnerUuid)) return blocked("same_resident", day);
		String teacherId = state.residentId(teacherUuid).orElse(null), learnerId = state.residentId(learnerUuid).orElse(null);
		if (!data.isMember(camp, teacherUuid) || !data.isMember(camp, learnerUuid)
			|| teacherId == null || learnerId == null) return blocked("not_member", day);
		Person teacher = state.world().people().stream().filter(p -> p.id().equals(teacherId)).findFirst().orElse(null);
		Person learner = state.world().people().stream().filter(p -> p.id().equals(learnerId)).findFirst().orElse(null);
		if (teacher == null || learner == null || !teacher.campId().equals(state.campOwner().id())
			|| !learner.campId().equals(state.campOwner().id())) return blocked("unavailable", day);
		FieldCraftTeaching.Plan plan = FieldCraftTeaching.inspect(teacher, learner);
		String gate = !state.craftLessonsAvailable() ? "ledger_unavailable"
			: state.craftLessonUsed(teacherId) || state.craftLessonUsed(learnerId) ? "already_taught" : plan.status();
		return new Snapshot(new Result(gate, day, plan.teacherSkill(), plan.before(), plan.after(),
			plan.teacherHours(), plan.learnerHours()), state, teacher, learner,
			data.memberIdentity(teacherUuid), data.memberIdentity(learnerUuid), state.foodRequestModeVersion(), plan);
	}

	private static Snapshot blocked(String gate, long day) {
		return new Snapshot(new Result(gate, day, 0, 0, 0, 0, 0), null, null, null, null, null, -1, null);
	}
	private static Result status(Result result, String status) {
		return new Result(status, result.coreDay(), result.teacherSkill(), result.skillBefore(), result.skillBefore(),
			result.teacherHours(), result.learnerHours());
	}
	private record Snapshot(Result result, SettlementSimulationState state, Person teacher, Person learner,
		Object teacherMember, Object learnerMember, long modeVersion, FieldCraftTeaching.Plan plan) {
		boolean same(Snapshot other) {
			return result.equals(other.result) && state == other.state && teacher == other.teacher && learner == other.learner
				&& teacherMember == other.teacherMember && learnerMember == other.learnerMember
				&& modeVersion == other.modeVersion && plan.equals(other.plan);
		}
	}
}
