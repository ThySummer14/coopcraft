package com.thysummer.coopcraft.civilization;

import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConstructionJobIdentityTest {
	private static final ResourceLocation ID = ResourceLocation.parse("coopcraft:identity_fixture");

	@BeforeAll static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test void identicalNewJobsHaveDistinctInstancesIncludingLegacyConstructors() {
		var first = new ConstructionJob(ID, BlockPos.ZERO, 0);
		var second = new ConstructionJob(ID, BlockPos.ZERO, 0);
		var pinned = new ConstructionJob(ID, BlockPos.ZERO, 0, "pinned");
		assertTrue(first.hasValidJobId());
		assertNotEquals(first.jobId(), second.jobId());
		assertNotEquals(first, second);
		assertNotEquals(first.jobId(), pinned.jobId());
		assertEquals(first, new ConstructionJob(ID, BlockPos.ZERO, 0, "", first.jobId()));
	}

	@Test void advanceAndPlanAdoptionKeepInstanceAcrossRepeatedSaveReload() {
		var initial = new ConstructionJob(ID, BlockPos.ZERO, 0);
		var adopted = initial.withPlanFingerprint("pinned");
		assertEquals(initial.jobId(), adopted.jobId());
		assertEquals("pinned", adopted.planFingerprint());
		var advanced = adopted.advance();
		assertEquals(initial.jobId(), advanced.jobId());
		assertEquals(1, advanced.nextIndex());
		for (int i = 0; i < 3; i++) {
			advanced = ConstructionJob.load(advanced.save());
			assertEquals(initial.jobId(), advanced.jobId());
			assertEquals(adopted.advance(), advanced);
		}
	}

	@Test void missingLegacyIdIsAssignedOnceAndLoadIsDirtyUntilPersisted() {
		var data = new SettlementSavedData();
		var settlement = data.getOrCreate(BlockPos.ZERO, "Identity", 2);
		data.setConstructionJob(settlement, new ConstructionJob(ID, BlockPos.ZERO, 0));
		var oldSave = data.save(new CompoundTag(), null);
		jobTag(oldSave).remove("JobId");

		var loaded = SettlementSavedData.load(oldSave, null);
		assertTrue(loaded.isDirty());
		var migrated = loaded.getConstructionJob(settlement).orElseThrow();
		assertTrue(migrated.hasValidJobId());
		assertEquals("", migrated.planFingerprint());
		var saved = loaded.save(new CompoundTag(), null);
		assertEquals(migrated.jobId(), jobTag(saved).getUUID("JobId"));
		for (int i = 0; i < 3; i++) {
			loaded = SettlementSavedData.load(saved, null);
			assertFalse(loaded.isDirty());
			assertEquals(migrated, loaded.getConstructionJob(settlement).orElseThrow());
			saved = loaded.save(new CompoundTag(), null);
		}
	}

	@Test void malformedPresentIdsStayBlockedAndPreserveExactRawTag() {
		for (Tag malformed : List.of(StringTag.valueOf("not-a-uuid"), new IntArrayTag(new int[] {1, 2}), new CompoundTag())) {
			var data = new SettlementSavedData();
			var settlement = data.getOrCreate(BlockPos.ZERO, "Identity", 2);
			data.setConstructionJob(settlement, new ConstructionJob(ID, BlockPos.ZERO, 0));
			var saved = data.save(new CompoundTag(), null);
			jobTag(saved).put("JobId", malformed.copy());
			for (int i = 0; i < 3; i++) {
				var loaded = SettlementSavedData.load(saved, null);
				assertFalse(loaded.isDirty());
				var job = loaded.getConstructionJob(settlement).orElseThrow();
				assertNull(job.jobId());
				assertFalse(job.hasValidJobId());
				assertEquals("invalid_job_id", job.compatibilityBlocker(null));
				assertEquals(malformed, job.advance().save().get("JobId"));
				assertEquals("invalid_job_id", job.withPlanFingerprint("pinned").compatibilityBlocker(null));
				saved = loaded.save(new CompoundTag(), null);
				assertEquals(malformed, jobTag(saved).get("JobId"));
			}
		}
	}

	@Test void malformedMutableTagCannotBeChangedThroughInputOrAccessor() {
		var saved = new ConstructionJob(ID, BlockPos.ZERO, 0).save();
		var malformed = new CompoundTag();
		malformed.putString("raw", "preserve");
		saved.put("JobId", malformed);
		var loaded = ConstructionJob.load(saved);
		malformed.putString("raw", "changed input");
		((CompoundTag) loaded.malformedJobId()).putString("raw", "changed accessor");
		assertEquals("preserve", loaded.save().getCompound("JobId").getString("raw"));
	}

	private static CompoundTag jobTag(CompoundTag saved) {
		return saved.getList("ConstructionJobs", ListTag.TAG_COMPOUND).getCompound(0);
	}
}
