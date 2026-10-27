package com.thysummer.coopcraft.client.anim;

/**
 * 一段完整的动作：物品本身的运动 + 可选的两条手臂轨道 + 可选的镜头晃动。
 *
 * <p>所有轨道共用一个总时长，这样"抛刀—接住—左手抚摸"这类需要手和刀配合的动作，
 * 只要让各轨道的关键帧在同一时间点上对齐就行。
 *
 * <p><b>单位：</b>物品轨道的位移是<b>方块</b>（1.0 = 一格）；手臂轨道是
 * <b>模型像素</b>（1.0 = 1/16 格），也就是玩家皮肤上的一格像素。两者不要混。
 * 旋转都是度。
 *
 * <p><b>手臂跟随（armFollow）：</b>为了让手一直握在剑柄上，主手和副手都会先绕
 * 握持点套一遍武器自己的变换，再叠加各自的轨道。这个系数决定"套多少"：
 * 1 是完全刚性绑定（武器翻一圈，手臂也跟着翻一圈），0 是手臂完全独立。
 * 取中间值可以让手臂跟着动、又不会真的绕镜头转一整圈。
 *
 * <p><b>camera 轨道</b>用来模拟参考模组里的 camera 骨：它不是真的转视角，
 * 而是让**整个第一人称视图模型**（武器 + 双手）相对镜头轻微转动。这一点点偏移
 * 是"有重量感"的主要来源，比单纯把位移调大有效得多。
 */
public final class Clip {
	/** 默认跟随权重：跟得上武器，但不会跟着转满一整圈。 */
	public static final float DEFAULT_FOLLOW = 0.6F;

	private final String id;
	private final float durationTicks;
	private final AnimationClip item;
	private final AnimationClip mainArm;
	private final AnimationClip offArm;
	private final AnimationClip camera;
	private final float armFollow;

	public Clip(
		String id,
		float durationTicks,
		AnimationClip item,
		AnimationClip mainArm,
		AnimationClip offArm,
		AnimationClip camera,
		float armFollow
	) {
		this.id = id;
		this.durationTicks = durationTicks;
		this.item = item;
		this.mainArm = mainArm;
		this.offArm = offArm;
		this.camera = camera;
		this.armFollow = armFollow;
	}

	/** 只有物品动、手臂跟着武器一起动（不再有额外的动作）。 */
	public static Clip itemOnly(AnimationClip item) {
		return new Clip(item.id(), item.durationTicks(), item, null, null, null, DEFAULT_FOLLOW);
	}

	/** 物品 + 手臂额外动作，手臂仍然跟随武器。 */
	public static Clip withMainArm(AnimationClip item, AnimationClip mainArm) {
		return new Clip(item.id(), item.durationTicks(), item, mainArm, null, null, DEFAULT_FOLLOW);
	}

	/** 双手都要出场，手臂仍然跟随武器。 */
	public static Clip withBothArms(AnimationClip item, AnimationClip mainArm, AnimationClip offArm) {
		return new Clip(item.id(), item.durationTicks(), item, mainArm, offArm, null, DEFAULT_FOLLOW);
	}

	/** 手臂完全独立编排（抛接这类"武器离开手"的动作要这么做）。 */
	public static Clip detached(AnimationClip item, AnimationClip mainArm, AnimationClip offArm) {
		return new Clip(item.id(), item.durationTicks(), item, mainArm, offArm, null, 0.0F);
	}

	/** 复制一份并挂上 camera 轨道。链式调用，避免每个工厂方法都多一个参数。 */
	public Clip withCamera(AnimationClip camera) {
		return new Clip(this.id, this.durationTicks, this.item, this.mainArm, this.offArm, camera, this.armFollow);
	}

	public String id() {
		return id;
	}

	public float durationTicks() {
		return durationTicks;
	}

	/** 实际播放时长（含全局速度倍率）。 */
	public float effectiveDurationTicks() {
		return this.durationTicks * AnimationClip.TIME_SCALE;
	}

	public float armFollow() {
		return this.armFollow;
	}

	public Transform sampleItem(float elapsed) {
		return this.item.sample(elapsed);
	}

	/** 主手手臂的额外偏移；没有配置时返回 null，表示只有"跟随武器"这一层。 */
	public Transform sampleMainArm(float elapsed) {
		return this.mainArm == null ? null : this.mainArm.sample(elapsed);
	}

	/** 副手（左手）是否要在这一段里出现。 */
	public boolean hasOffArm() {
		return this.offArm != null;
	}

	public Transform sampleOffArm(float elapsed) {
		return this.offArm == null ? null : this.offArm.sample(elapsed);
	}

	/** 镜头晃动；null 表示这段不晃。 */
	public Transform sampleCamera(float elapsed) {
		return this.camera == null ? null : this.camera.sample(elapsed);
	}
}
