package com.thysummer.coopcraft.client.anim;

import java.util.List;

/**
 * 一段关键帧动画。
 *
 * <p>所有时间都以「刻」为单位（20 刻 = 1 秒），采样时传入浮点刻数，所以掉帧不会让
 * 动画变慢——它跟的是游戏时间，不是帧数。
 *
 * <p><b>插值方式是基数样条（Cardinal spline），不是逐段缓动。</b>
 * 早期版本在每两个关键帧之间单独做 smoothstep，结果每个关键帧处速度都会归零，
 * 动作看起来一卡一卡的。样条会依据前后两个关键帧算出切线，让速度在整个动画里连续，
 * 关键帧只是"转折点"，不是"停顿点"。
 */
public final class AnimationClip {
	/** 样条张力。0 = 直线（无弯曲），0.5 是常见的手感，再大就容易过冲。 */
	private static final float TENSION = 0.5F;

	/**
	 * 全局播放速度倍率。
	 *
	 * <p>改这一个数就能把所有动画整体调快或调慢——时长和取样都按它换算，不需要
	 * 逐段改数字。1.0 是参考模组（DeltaForce Melee Pack）的节奏；
	 * 觉得检视太长就调成 0.7，觉得切刀拖沓就调成 0.8。
	 */
	public static float TIME_SCALE = 1.0F;

	private final String id;
	private final float durationTicks;
	private final List<Keyframe> frames;

	// 展开成数组，避免采样时反复装箱。
	private final float[] times;
	private final float[][] channels;

	public AnimationClip(String id, float durationTicks, List<Keyframe> frames) {
		if (frames.isEmpty()) {
			throw new IllegalArgumentException("clip '" + id + "' has no keyframes");
		}

		this.id = id;
		this.durationTicks = durationTicks;
		this.frames = List.copyOf(frames);

		int size = this.frames.size();
		this.times = new float[size];
		this.channels = new float[6][size];

		for (int i = 0; i < size; i++) {
			Keyframe frame = this.frames.get(i);
			Transform transform = frame.transform();
			this.times[i] = frame.time();
			this.channels[0][i] = transform.x();
			this.channels[1][i] = transform.y();
			this.channels[2][i] = transform.z();
			this.channels[3][i] = transform.rotX();
			this.channels[4][i] = transform.rotY();
			this.channels[5][i] = transform.rotZ();
		}
	}

	public String id() {
		return id;
	}

	public float durationTicks() {
		return durationTicks;
	}

	/**
	 * 采样给定时刻的变换。调用方需自行保证 {@code elapsedTicks} 不小于 0。
	 */
	public Transform sample(float elapsedTicks) {
		float span0 = durationTicks * TIME_SCALE;
		float progress = span0 <= 0.0F ? 1.0F : Math.clamp(elapsedTicks / span0, 0.0F, 1.0F);
		return sampleProgress(progress);
	}

	/**
	 * 按归一化进度 (0..1) 采样。
	 *
	 * <p>攻击动作用这个入口：原版挥击的进度来自 {@code LivingEntity#getAttackAnim}，
	 * 由攻击冷却驱动，不是固定时长，所以不能按刻数采样。
	 */
	public Transform sampleProgress(float progress) {
		float clamped = Math.clamp(progress, 0.0F, 1.0F);
		progress = clamped;
		int last = this.times.length - 1;

		if (progress <= this.times[0]) {
			return this.transformAt(0);
		}

		if (progress >= this.times[last]) {
			return this.transformAt(last);
		}

		int index = 0;
		while (index < last - 1 && progress > this.times[index + 1]) {
			index++;
		}

		float t1 = this.times[index];
		float t2 = this.times[index + 1];
		float span = t2 - t1;
		float u = span <= 0.0F ? 1.0F : (progress - t1) / span;

		// Hermite 基函数
		float u2 = u * u;
		float u3 = u2 * u;
		float h00 = 2.0F * u3 - 3.0F * u2 + 1.0F;
		float h10 = u3 - 2.0F * u2 + u;
		float h01 = -2.0F * u3 + 3.0F * u2;
		float h11 = u3 - u2;

		float[] result = new float[6];

		for (int c = 0; c < 6; c++) {
			float v1 = this.channels[c][index];
			float v2 = this.channels[c][index + 1];
			// 切线按"每秒变化量 × 本段时长"换算成 Hermite 需要的尺度。
			float m1 = this.tangent(c, index) * span;
			float m2 = this.tangent(c, index + 1) * span;
			result[c] = h00 * v1 + h10 * m1 + h01 * v2 + h11 * m2;
		}

		return new Transform(result[0], result[1], result[2], result[3], result[4], result[5]);
	}

	/**
	 * 第 {@code index} 个关键帧处的切线（对归一化时间的导数）。
	 *
	 * <p>首尾两端没有"前一个"或"后一个"关键帧，就把自己复制一份当作邻居，
	 * 等价于让动画在开头和结尾的速度自然收到 0，不会突然启动或急停。
	 */
	private float tangent(int channel, int index) {
		int last = this.times.length - 1;
		int previous = Math.max(0, index - 1);
		int next = Math.min(last, index + 1);

		float dt = this.times[next] - this.times[previous];
		if (dt <= 0.0F) {
			return 0.0F;
		}

		float dv = this.channels[channel][next] - this.channels[channel][previous];
		return dv / dt * TENSION * 2.0F;
	}

	private Transform transformAt(int index) {
		return new Transform(
			this.channels[0][index],
			this.channels[1][index],
			this.channels[2][index],
			this.channels[3][index],
			this.channels[4][index],
			this.channels[5][index]
		);
	}
}
