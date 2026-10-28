package com.thysummer.coopcraft.client.anim;

import java.util.List;

/**
 * 模组内置的动画定义。
 *
 * <p><b>调参请直接改这里的数字</b>，改完重新构建即可，不需要动逻辑代码。
 *
 * <p><b>坐标系</b>：+X 向右、+Y 向上、-Z 远离镜头（所以 z 变大 = 靠近镜头）。
 * 角度单位是度。<b>位移单位分两套：</b>
 * <ul>
 *   <li><b>物品轨道：方块</b>。0.1 = 十分之一格。</li>
 *   <li><b>手臂轨道：模型像素</b>（1 = 1/16 格）。16 就是 1 格。
 *       手臂模型本身只有 12 像素长，所以这里一般只写个位数。</li>
 * </ul>
 *
 * <p><b>节奏参照：</b>TACZ 的 DeltaForce Melee Pack（手工 3D 模型 + 逐帧动画）。
 * 它的 draw 是 1.63 秒、inspect 是 8.67 秒，关键帧按 24fps 逐帧手工摆。本文件按同样的
 * 时间尺度来编排：切刀 {@value #DRAW_TICKS} 刻（约 1 秒），检视 {@value #INSPECT_TICKS} 刻（8 秒）。
 * 如果觉得整体太快或太慢，改 {@link AnimationClip#TIME_SCALE} 一个数即可。
 *
 * <p><b>camera 轨道</b>是"重量感"的关键：它让整个视图模型（武器 + 双手）相对镜头轻微
 * 转动 1~2 度。参考模组的 camera 骨就是干这个的，比单纯加大位移有效得多。
 *
 * <p>每段动画的最后一帧必须是"视觉上的原位"：位移和俯仰归零，Z 轴可以是 0 或 ±360
 * 的整数倍。否则动画结束时物品会突然跳一下。
 */
public final class Animations {
	private Animations() {
	}

	/** 切刀时长（刻）。21 刻 ≈ 1.05 秒，对应参考模组的 1.04~1.63 秒。 */
	// 切刀 16 刻 ≈ 0.8 秒。原来 21 刻太拖，参考模组那一下是"甩"出来的，不是"抬"出来的。
	private static final float DRAW_TICKS = 16.0F;

	/** 检视时长（刻）。160 刻 = 8 秒，对应参考模组的 7.92~8.67 秒。 */
	// 检视 120 刻 = 6 秒。8 秒看久了会腻，六秒刚好够转一圈再擦一下剑身。
	private static final float INSPECT_TICKS = 120.0F;

	// ==================== camera：视图模型晃动 ====================

	/** 切刀用的短促晃动。 */
	private static final AnimationClip CAMERA_DRAW = new AnimationClip(
		"camera_draw",
		DRAW_TICKS,
		List.of(
			new Keyframe(0.00F, Transform.IDENTITY),
			new Keyframe(0.22F, new Transform(0.0F, 0.0F, 0.0F, -2.4F, -1.1F, 1.8F)),
			new Keyframe(0.50F, new Transform(0.0F, 0.0F, 0.0F, 1.5F, 0.9F, -1.1F)),
			new Keyframe(0.74F, new Transform(0.0F, 0.0F, 0.0F, -0.7F, -0.4F, 0.5F)),
			new Keyframe(0.88F, new Transform(0.0F, 0.0F, 0.0F, 0.3F, 0.2F, -0.2F)),
			new Keyframe(1.00F, Transform.IDENTITY)
		)
	);

	/** 检视用的缓慢漂移，幅度 1~1.5 度。 */
	private static final AnimationClip CAMERA_SOFT = new AnimationClip(
		"camera_soft",
		INSPECT_TICKS,
		List.of(
			new Keyframe(0.00F, Transform.IDENTITY),
			new Keyframe(0.06F, new Transform(0.0F, 0.0F, 0.0F, -1.3F, 0.5F, 0.9F)),
			new Keyframe(0.18F, new Transform(0.0F, 0.0F, 0.0F, -0.5F, -0.6F, 1.5F)),
			new Keyframe(0.32F, new Transform(0.0F, 0.0F, 0.0F, 0.9F, 0.7F, -0.7F)),
			new Keyframe(0.44F, new Transform(0.0F, 0.0F, 0.0F, -0.3F, -0.4F, 1.1F)),
			new Keyframe(0.58F, new Transform(0.0F, 0.0F, 0.0F, 1.2F, 0.6F, -1.3F)),
			new Keyframe(0.70F, new Transform(0.0F, 0.0F, 0.0F, -0.8F, -0.5F, 0.8F)),
			new Keyframe(0.82F, new Transform(0.0F, 0.0F, 0.0F, 0.6F, 0.3F, -0.6F)),
			new Keyframe(0.92F, new Transform(0.0F, 0.0F, 0.0F, -0.3F, -0.2F, 0.3F)),
			new Keyframe(1.00F, Transform.IDENTITY)
		)
	);

	/** 抛接用的晃动：抛起、到顶点、接住三处有冲击。 */
	private static final AnimationClip CAMERA_HEAVY = new AnimationClip(
		"camera_heavy",
		INSPECT_TICKS,
		List.of(
			new Keyframe(0.00F, Transform.IDENTITY),
			new Keyframe(0.10F, new Transform(0.0F, 0.0F, 0.0F, -1.5F, 0.6F, 1.0F)),
			new Keyframe(0.26F, new Transform(0.0F, 0.0F, 0.0F, 0.8F, -1.2F, 1.6F)),
			new Keyframe(0.33F, new Transform(0.0F, 0.0F, 0.0F, -2.2F, 0.9F, -1.4F)),
			new Keyframe(0.44F, new Transform(0.0F, 0.0F, 0.0F, 1.4F, -0.8F, 1.2F)),
			new Keyframe(0.57F, new Transform(0.0F, 0.0F, 0.0F, -2.6F, 0.7F, -1.8F)),
			new Keyframe(0.64F, new Transform(0.0F, 0.0F, 0.0F, 1.1F, -0.6F, 1.0F)),
			new Keyframe(0.74F, new Transform(0.0F, 0.0F, 0.0F, -0.9F, 0.5F, -0.8F)),
			new Keyframe(0.86F, new Transform(0.0F, 0.0F, 0.0F, 0.5F, -0.3F, 0.5F)),
			new Keyframe(1.00F, Transform.IDENTITY)
		)
	);

	// ==================== 切刀 ====================

	private static final AnimationClip DRAW_SWING_ITEM = new AnimationClip(
		"draw_swing",
		DRAW_TICKS,
		List.of(
			new Keyframe(0.00F, new Transform(0.14F, -0.42F, -0.14F, 92.0F, 0.0F, -38.0F)),
			new Keyframe(0.10F, new Transform(0.11F, -0.30F, -0.11F, 78.0F, 0.0F, -30.0F)),
			new Keyframe(0.26F, new Transform(0.06F, -0.14F, 0.02F, 26.0F, 0.0F, -6.0F)),
			new Keyframe(0.40F, new Transform(0.02F, 0.12F, 0.10F, -18.0F, 0.0F, 14.0F)),
			new Keyframe(0.52F, new Transform(0.00F, 0.04F, 0.05F, -4.0F, 0.0F, 4.0F)),
			new Keyframe(0.64F, new Transform(0.00F, -0.06F, 0.01F, 8.0F, 0.0F, -6.0F)),
			new Keyframe(0.78F, new Transform(0.00F, 0.02F, 0.00F, -2.0F, 0.0F, 2.0F)),
			new Keyframe(1.00F, Transform.IDENTITY)
		)
	);

	private static final AnimationClip DRAW_ARM_SWING = new AnimationClip(
		"draw_swing_arm",
		DRAW_TICKS,
		List.of(
			new Keyframe(0.00F, new Transform(0.0F, -7.0F, 0.0F, 20.0F, 0.0F, 0.0F)),
			new Keyframe(0.26F, new Transform(0.0F, -3.0F, 0.0F, 10.0F, 0.0F, 0.0F)),
			new Keyframe(0.44F, new Transform(0.0F, 2.0F, 0.0F, -8.0F, 0.0F, 0.0F)),
			new Keyframe(0.60F, new Transform(0.0F, -1.0F, 0.0F, 3.0F, 0.0F, 0.0F)),
			new Keyframe(0.78F, new Transform(0.0F, 0.0F, 0.0F, -1.0F, 0.0F, 0.0F)),
			new Keyframe(1.00F, Transform.IDENTITY)
		)
	);

	private static final AnimationClip DRAW_SIDE_ITEM = new AnimationClip(
		"draw_side",
		DRAW_TICKS,
		List.of(
			new Keyframe(0.00F, new Transform(-0.28F, -0.18F, -0.16F, 42.0F, 0.0F, 74.0F)),
			new Keyframe(0.12F, new Transform(-0.30F, -0.13F, -0.12F, 30.0F, 0.0F, 58.0F)),
			new Keyframe(0.30F, new Transform(-0.16F, 0.02F, 0.04F, -6.0F, 0.0F, 8.0F)),
			new Keyframe(0.44F, new Transform(0.06F, 0.10F, 0.12F, -18.0F, 0.0F, -22.0F)),
			new Keyframe(0.56F, new Transform(-0.02F, 0.02F, 0.04F, -2.0F, 0.0F, -4.0F)),
			new Keyframe(0.70F, new Transform(0.02F, -0.05F, 0.00F, 7.0F, 0.0F, 7.0F)),
			new Keyframe(0.84F, new Transform(-0.01F, 0.01F, 0.00F, -2.0F, 0.0F, -3.0F)),
			new Keyframe(1.00F, Transform.IDENTITY)
		)
	);

	private static final AnimationClip DRAW_ARM_SIDE = new AnimationClip(
		"draw_side_arm",
		DRAW_TICKS,
		List.of(
			new Keyframe(0.00F, new Transform(-10.0F, -2.0F, -2.0F, 0.0F, -28.0F, 12.0F)),
			new Keyframe(0.30F, new Transform(-4.0F, 0.0F, 0.0F, 0.0F, -12.0F, 5.0F)),
			new Keyframe(0.50F, new Transform(2.0F, 1.0F, 0.0F, 0.0F, 7.0F, -3.0F)),
			new Keyframe(0.70F, new Transform(-1.0F, 0.0F, 0.0F, 0.0F, -3.0F, 1.0F)),
			new Keyframe(1.00F, Transform.IDENTITY)
		)
	);

	private static final AnimationClip DRAW_PULL_ITEM = new AnimationClip(
		"draw_pull",
		DRAW_TICKS,
		List.of(
			new Keyframe(0.00F, new Transform(0.07F, -0.34F, -0.10F, 74.0F, 0.0F, 0.0F)),
			new Keyframe(0.12F, new Transform(0.06F, -0.26F, -0.07F, 64.0F, 0.0F, 0.0F)),
			new Keyframe(0.30F, new Transform(0.03F, -0.18F, 0.00F, 24.0F, 0.0F, 0.0F)),
			new Keyframe(0.44F, new Transform(0.00F, 0.14F, 0.08F, -16.0F, 0.0F, 0.0F)),
			new Keyframe(0.58F, new Transform(0.00F, 0.02F, 0.03F, 2.0F, 0.0F, 0.0F)),
			new Keyframe(0.72F, new Transform(0.00F, -0.07F, 0.00F, 9.0F, 0.0F, 0.0F)),
			new Keyframe(0.86F, new Transform(0.00F, 0.02F, 0.00F, -2.0F, 0.0F, 0.0F)),
			new Keyframe(1.00F, Transform.IDENTITY)
		)
	);

	private static final AnimationClip DRAW_ARM_PULL = new AnimationClip(
		"draw_pull_arm",
		DRAW_TICKS,
		List.of(
			new Keyframe(0.00F, new Transform(0.0F, -9.0F, -1.0F, 20.0F, 0.0F, 0.0F)),
			new Keyframe(0.30F, new Transform(0.0F, -4.0F, 0.0F, 11.0F, 0.0F, 0.0F)),
			new Keyframe(0.50F, new Transform(0.0F, 2.0F, 0.0F, -9.0F, 0.0F, 0.0F)),
			new Keyframe(0.70F, new Transform(0.0F, -1.0F, 0.0F, 3.0F, 0.0F, 0.0F)),
			new Keyframe(1.00F, Transform.IDENTITY)
		)
	);

	private static final AnimationClip DRAW_DROP_ITEM = new AnimationClip(
		"draw_drop",
		DRAW_TICKS,
		List.of(
			// 起始高度只到画面顶边外一点。原来 0.72 格，前两帧整把武器完全在画外，
			// 看起来像"动作没播"而不是"从上面掉下来"。
			new Keyframe(0.00F, new Transform(0.00F, 0.38F, -0.06F, -55.0F, 0.0F, 18.0F)),
			new Keyframe(0.12F, new Transform(0.00F, 0.27F, -0.05F, -42.0F, 0.0F, 13.0F)),
			new Keyframe(0.30F, new Transform(0.00F, 0.07F, 0.02F, -13.0F, 0.0F, 5.0F)),
			new Keyframe(0.44F, new Transform(0.00F, -0.12F, 0.08F, 16.0F, 0.0F, -10.0F)),
			new Keyframe(0.58F, new Transform(0.00F, 0.02F, 0.03F, -3.0F, 0.0F, 3.0F)),
			new Keyframe(0.72F, new Transform(0.00F, 0.06F, 0.00F, -7.0F, 0.0F, 2.0F)),
			new Keyframe(0.86F, new Transform(0.00F, -0.02F, 0.00F, 2.0F, 0.0F, -1.0F)),
			new Keyframe(1.00F, Transform.IDENTITY)
		)
	);

	private static final AnimationClip DRAW_ARM_DROP = new AnimationClip(
		"draw_drop_arm",
		DRAW_TICKS,
		List.of(
			new Keyframe(0.00F, new Transform(0.0F, 4.0F, 1.0F, -14.0F, 0.0F, 0.0F)),
			new Keyframe(0.30F, new Transform(0.0F, 3.0F, 0.0F, -7.0F, 0.0F, 0.0F)),
			new Keyframe(0.50F, new Transform(0.0F, -2.0F, 0.0F, 6.0F, 0.0F, 0.0F)),
			new Keyframe(0.70F, new Transform(0.0F, 1.0F, 0.0F, -2.0F, 0.0F, 0.0F)),
			new Keyframe(1.00F, Transform.IDENTITY)
		)
	);

	// ==================== 检视 ====================

	/** 共用的主手轨道：把刀端稳，配合镜头的轻微起伏。 */
	private static final AnimationClip INSPECT_ARM_HOLD = new AnimationClip(
		"inspect_arm_hold",
		INSPECT_TICKS,
		List.of(
			new Keyframe(0.00F, Transform.IDENTITY),
			new Keyframe(0.06F, new Transform(0.0F, 2.0F, 1.0F, -3.0F, 0.0F, -5.0F)),
			new Keyframe(0.20F, new Transform(0.0F, 3.0F, 2.0F, -5.0F, 0.0F, -8.0F)),
			new Keyframe(0.50F, new Transform(0.0F, 2.0F, 2.0F, -4.0F, 0.0F, -6.0F)),
			new Keyframe(0.80F, new Transform(0.0F, 2.0F, 1.0F, -3.0F, 0.0F, -5.0F)),
			new Keyframe(1.00F, Transform.IDENTITY)
		)
	);

	/** 整圈翻转：抬起 → 慢转 → 中段停顿 → 继续转完 → 收回。 */
	private static final AnimationClip INSPECT_SPIN_ITEM = new AnimationClip(
		"inspect_spin",
		INSPECT_TICKS,
		List.of(
			new Keyframe(0.00F, Transform.IDENTITY),
			new Keyframe(0.05F, new Transform(-0.06F, 0.10F, 0.12F, -8.0F, 0.0F, -10.0F)),
			new Keyframe(0.11F, new Transform(-0.16F, 0.18F, 0.21F, -16.0F, 0.0F, -30.0F)),
			new Keyframe(0.16F, new Transform(-0.18F, 0.20F, 0.23F, -18.0F, -2.0F, -40.0F)),
			new Keyframe(0.24F, new Transform(-0.20F, 0.21F, 0.24F, -20.0F, -6.0F, -78.0F)),
			new Keyframe(0.32F, new Transform(-0.22F, 0.22F, 0.24F, -24.0F, -10.0F, -116.0F)),
			new Keyframe(0.40F, new Transform(-0.20F, 0.20F, 0.23F, -18.0F, -8.0F, -156.0F)),
			new Keyframe(0.47F, new Transform(-0.16F, 0.24F, 0.22F, -32.0F, -6.0F, -204.0F)),
			new Keyframe(0.55F, new Transform(-0.14F, 0.22F, 0.22F, -26.0F, -4.0F, -242.0F)),
			new Keyframe(0.61F, new Transform(-0.16F, 0.22F, 0.22F, -24.0F, -4.0F, -250.0F)),
			new Keyframe(0.69F, new Transform(-0.18F, 0.24F, 0.22F, -32.0F, -6.0F, -300.0F)),
			new Keyframe(0.77F, new Transform(-0.14F, 0.18F, 0.18F, -18.0F, 0.0F, -330.0F)),
			new Keyframe(0.86F, new Transform(-0.08F, 0.12F, 0.12F, -10.0F, 0.0F, -350.0F)),
			new Keyframe(0.94F, new Transform(-0.03F, 0.05F, 0.05F, -4.0F, 0.0F, -357.0F)),
			new Keyframe(1.00F, new Transform(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, -360.0F))
		)
	);

	/** 端详：转成竖持，左右各倾一次看刀锋，再收回。 */
	private static final AnimationClip INSPECT_EXAMINE_ITEM = new AnimationClip(
		"inspect_examine",
		INSPECT_TICKS,
		List.of(
			new Keyframe(0.00F, Transform.IDENTITY),
			new Keyframe(0.05F, new Transform(-0.05F, 0.08F, 0.10F, -6.0F, 0.0F, -6.0F)),
			new Keyframe(0.11F, new Transform(-0.13F, 0.16F, 0.19F, -10.0F, 0.0F, -62.0F)),
			new Keyframe(0.17F, new Transform(-0.15F, 0.18F, 0.21F, -8.0F, 0.0F, -84.0F)),
			new Keyframe(0.23F, new Transform(-0.16F, 0.18F, 0.22F, -6.0F, 0.0F, -88.0F)),
			new Keyframe(0.31F, new Transform(-0.20F, 0.18F, 0.22F, -2.0F, 0.0F, -98.0F)),
			new Keyframe(0.38F, new Transform(-0.16F, 0.18F, 0.22F, -14.0F, 0.0F, -80.0F)),
			new Keyframe(0.45F, new Transform(-0.20F, 0.20F, 0.22F, 4.0F, 0.0F, -100.0F)),
			new Keyframe(0.52F, new Transform(-0.16F, 0.18F, 0.22F, -8.0F, 0.0F, -86.0F)),
			new Keyframe(0.60F, new Transform(-0.19F, 0.20F, 0.22F, -18.0F, 0.0F, -92.0F)),
			new Keyframe(0.69F, new Transform(-0.17F, 0.17F, 0.20F, -6.0F, 0.0F, -80.0F)),
			new Keyframe(0.79F, new Transform(-0.14F, 0.15F, 0.18F, -14.0F, 0.0F, -62.0F)),
			new Keyframe(0.88F, new Transform(-0.09F, 0.10F, 0.13F, -9.0F, 0.0F, -38.0F)),
			new Keyframe(0.95F, new Transform(-0.03F, 0.04F, 0.05F, -3.0F, 0.0F, -14.0F)),
			new Keyframe(1.00F, Transform.IDENTITY)
		)
	);

	/** 擦拭：刀端稳不动，靠左手横向来回抹。 */
	private static final AnimationClip INSPECT_WIPE_ITEM = new AnimationClip(
		"inspect_wipe",
		INSPECT_TICKS,
		List.of(
			new Keyframe(0.00F, Transform.IDENTITY),
			new Keyframe(0.05F, new Transform(-0.05F, 0.08F, 0.10F, -8.0F, 0.0F, -12.0F)),
			new Keyframe(0.11F, new Transform(-0.15F, 0.15F, 0.19F, -15.0F, 0.0F, -26.0F)),
			new Keyframe(0.16F, new Transform(-0.19F, 0.16F, 0.21F, -17.0F, 0.0F, -30.0F)),
			new Keyframe(0.24F, new Transform(-0.20F, 0.16F, 0.21F, -17.0F, 0.0F, -31.0F)),
			new Keyframe(0.32F, new Transform(-0.19F, 0.16F, 0.21F, -17.0F, 0.0F, -30.0F)),
			new Keyframe(0.40F, new Transform(-0.20F, 0.17F, 0.21F, -17.0F, 0.0F, -31.0F)),
			new Keyframe(0.48F, new Transform(-0.19F, 0.16F, 0.21F, -17.0F, 0.0F, -30.0F)),
			new Keyframe(0.56F, new Transform(-0.20F, 0.17F, 0.21F, -17.0F, 0.0F, -31.0F)),
			new Keyframe(0.64F, new Transform(-0.19F, 0.16F, 0.21F, -16.0F, 0.0F, -30.0F)),
			new Keyframe(0.72F, new Transform(-0.20F, 0.17F, 0.21F, -18.0F, 0.0F, -31.0F)),
			new Keyframe(0.82F, new Transform(-0.16F, 0.15F, 0.19F, -15.0F, 0.0F, -27.0F)),
			new Keyframe(0.91F, new Transform(-0.09F, 0.09F, 0.12F, -9.0F, 0.0F, -17.0F)),
			new Keyframe(1.00F, Transform.IDENTITY)
		)
	);

	/**
	 * 左手：伸进来，沿刀身来回擦，再退出去。
	 *
	 * <p>位移单位是 1/16 格，轴是<b>相机轴</b>（+x 屏幕右、+y 屏幕上、+z 朝镜头）——
	 * 左手在 mixin 里是在 45° 偏航之前加这一段位移的。原点 (0,0,0) 就是左手自然下垂
	 * 的握持点，屏幕坐标约 (238, 381)，也就是画面左下角；向右上推到 (15, 5) 就到了
	 * 剑身旁边，屏幕约 (551, 291)。
	 */
	private static final AnimationClip INSPECT_WIPE_OFF_ARM = new AnimationClip(
		"inspect_wipe_offarm",
		INSPECT_TICKS,
		List.of(
			new Keyframe(0.00F, new Transform(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F)),
			new Keyframe(0.13F, new Transform(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F)),
			new Keyframe(0.18F, new Transform(8.0F, 2.0F, 0.0F, 0.0F, 0.0F, -6.0F)),
			new Keyframe(0.26F, new Transform(15.0F, 5.0F, 0.0F, 0.0F, 0.0F, -10.0F)),
			new Keyframe(0.34F, new Transform(9.0F, 3.0F, 0.0F, 0.0F, 0.0F, -4.0F)),
			new Keyframe(0.42F, new Transform(15.0F, 5.0F, 0.0F, 0.0F, 0.0F, -10.0F)),
			new Keyframe(0.50F, new Transform(9.0F, 3.0F, 0.0F, 0.0F, 0.0F, -4.0F)),
			new Keyframe(0.58F, new Transform(15.0F, 5.0F, 0.0F, 0.0F, 0.0F, -10.0F)),
			new Keyframe(0.66F, new Transform(9.0F, 3.0F, 0.0F, 0.0F, 0.0F, -4.0F)),
			new Keyframe(0.74F, new Transform(5.0F, 1.0F, 0.0F, 0.0F, 0.0F, -2.0F)),
			new Keyframe(0.80F, new Transform(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F)),
			new Keyframe(1.00F, new Transform(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F))
		)
	);

	/** 掂量：抛起半格、空中转大半圈、落下接住，再端稳两秒。 */
	private static final AnimationClip INSPECT_TOSS_ITEM = new AnimationClip(
		"inspect_toss",
		INSPECT_TICKS,
		List.of(
			new Keyframe(0.00F, Transform.IDENTITY),
			new Keyframe(0.05F, new Transform(-0.06F, 0.10F, 0.12F, -10.0F, 0.0F, -14.0F)),
			new Keyframe(0.12F, new Transform(-0.10F, 0.18F, 0.15F, -18.0F, 0.0F, -34.0F)),
			new Keyframe(0.20F, new Transform(-0.08F, 0.34F, 0.13F, -24.0F, 0.0F, -80.0F)),
			new Keyframe(0.28F, new Transform(-0.06F, 0.46F, 0.11F, -30.0F, 0.0F, -150.0F)),
			new Keyframe(0.36F, new Transform(-0.05F, 0.52F, 0.10F, -32.0F, 0.0F, -216.0F)),
			new Keyframe(0.44F, new Transform(-0.07F, 0.40F, 0.12F, -24.0F, 0.0F, -280.0F)),
			new Keyframe(0.51F, new Transform(-0.09F, 0.24F, 0.14F, -16.0F, 0.0F, -330.0F)),
			new Keyframe(0.57F, new Transform(-0.10F, 0.10F, 0.16F, -8.0F, 0.0F, -352.0F)),
			new Keyframe(0.63F, new Transform(-0.11F, 0.16F, 0.16F, -14.0F, 0.0F, -360.0F)),
			new Keyframe(0.70F, new Transform(-0.10F, 0.10F, 0.16F, -8.0F, 0.0F, -360.0F)),
			new Keyframe(0.80F, new Transform(-0.12F, 0.14F, 0.17F, -14.0F, 0.0F, -360.0F)),
			new Keyframe(0.90F, new Transform(-0.08F, 0.08F, 0.11F, -8.0F, 0.0F, -360.0F)),
			new Keyframe(1.00F, new Transform(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, -360.0F))
		)
	);

	private static final AnimationClip INSPECT_TOSS_ARM = new AnimationClip(
		"inspect_toss_arm",
		INSPECT_TICKS,
		List.of(
			new Keyframe(0.00F, Transform.IDENTITY),
			new Keyframe(0.12F, new Transform(0.0F, 3.0F, 1.0F, -8.0F, 0.0F, 0.0F)),
			new Keyframe(0.28F, new Transform(0.0F, -5.0F, 0.0F, 11.0F, 0.0F, 0.0F)),
			new Keyframe(0.44F, new Transform(0.0F, -4.0F, 0.0F, 9.0F, 0.0F, 0.0F)),
			new Keyframe(0.57F, new Transform(0.0F, 4.0F, 1.0F, -11.0F, 0.0F, 0.0F)),
			new Keyframe(0.70F, new Transform(0.0F, 1.0F, 0.0F, -3.0F, 0.0F, 0.0F)),
			new Keyframe(1.00F, Transform.IDENTITY)
		)
	);

	/** 翻面：翻到背面停住端详两秒，再翻回来。 */
	private static final AnimationClip INSPECT_FLIP_ITEM = new AnimationClip(
		"inspect_flip",
		INSPECT_TICKS,
		List.of(
			new Keyframe(0.00F, Transform.IDENTITY),
			new Keyframe(0.05F, new Transform(-0.05F, 0.08F, 0.10F, -8.0F, 0.0F, -10.0F)),
			new Keyframe(0.12F, new Transform(-0.13F, 0.15F, 0.18F, -18.0F, 0.0F, -30.0F)),
			new Keyframe(0.20F, new Transform(-0.16F, 0.18F, 0.20F, -26.0F, 0.0F, -80.0F)),
			new Keyframe(0.28F, new Transform(-0.17F, 0.19F, 0.20F, -32.0F, 0.0F, -140.0F)),
			new Keyframe(0.35F, new Transform(-0.17F, 0.18F, 0.20F, -14.0F, 0.0F, -180.0F)),
			new Keyframe(0.44F, new Transform(-0.16F, 0.18F, 0.20F, -8.0F, 0.0F, -180.0F)),
			new Keyframe(0.52F, new Transform(-0.17F, 0.19F, 0.20F, -6.0F, 0.0F, -180.0F)),
			new Keyframe(0.60F, new Transform(-0.16F, 0.18F, 0.20F, -10.0F, 0.0F, -186.0F)),
			new Keyframe(0.69F, new Transform(-0.16F, 0.18F, 0.20F, -20.0F, 0.0F, -250.0F)),
			new Keyframe(0.78F, new Transform(-0.13F, 0.16F, 0.18F, -26.0F, 0.0F, -310.0F)),
			new Keyframe(0.87F, new Transform(-0.09F, 0.11F, 0.13F, -16.0F, 0.0F, -344.0F)),
			new Keyframe(0.95F, new Transform(-0.03F, 0.04F, 0.05F, -5.0F, 0.0F, -357.0F)),
			new Keyframe(1.00F, new Transform(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, -360.0F))
		)
	);

	// ==================== 组合动作：抛刀 → 接住 → 左手抚摸剑身 ====================

	/** 剑专属。8 秒。抬刀 → 抛起两圈 → 接住 → 左手沿剑身抹一道 → 收势。 */
	private static final AnimationClip COMBO_ITEM = new AnimationClip(
		"combo_toss_stroke",
		INSPECT_TICKS,
		List.of(
			new Keyframe(0.00F, Transform.IDENTITY),
			new Keyframe(0.05F, new Transform(-0.06F, 0.10F, 0.12F, -10.0F, 0.0F, -14.0F)),
			new Keyframe(0.12F, new Transform(-0.10F, 0.16F, 0.17F, -18.0F, 0.0F, -30.0F)),
			new Keyframe(0.19F, new Transform(-0.08F, 0.50F, 0.13F, -22.0F, 0.0F, -96.0F)),
			new Keyframe(0.26F, new Transform(-0.06F, 0.72F, 0.11F, -26.0F, 0.0F, -190.0F)),
			new Keyframe(0.33F, new Transform(-0.05F, 0.86F, 0.10F, -28.0F, 0.0F, -290.0F)),
			new Keyframe(0.40F, new Transform(-0.06F, 0.74F, 0.11F, -24.0F, 0.0F, -390.0F)),
			new Keyframe(0.47F, new Transform(-0.08F, 0.48F, 0.13F, -16.0F, 0.0F, -480.0F)),
			new Keyframe(0.53F, new Transform(-0.10F, 0.18F, 0.15F, -10.0F, 0.0F, -660.0F)),
			new Keyframe(0.57F, new Transform(-0.10F, 0.08F, 0.16F, -8.0F, 0.0F, -672.0F)),
			new Keyframe(0.61F, new Transform(-0.11F, 0.14F, 0.16F, -14.0F, 0.0F, -676.0F)),
			new Keyframe(0.66F, new Transform(-0.13F, 0.17F, 0.17F, -20.0F, 0.0F, -678.0F)),
			new Keyframe(0.72F, new Transform(-0.14F, 0.18F, 0.18F, -20.0F, 0.0F, -679.0F)),
			new Keyframe(0.84F, new Transform(-0.14F, 0.18F, 0.18F, -20.0F, 0.0F, -679.0F)),
			new Keyframe(0.90F, new Transform(-0.10F, 0.13F, 0.13F, -14.0F, 0.0F, -700.0F)),
			new Keyframe(0.96F, new Transform(-0.04F, 0.05F, 0.05F, -6.0F, 0.0F, -714.0F)),
			new Keyframe(1.00F, new Transform(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, -720.0F))
		)
	);

	/** 主手：抛出后下沉，落下时抬手接住。抛刀时手臂不跟随武器，所以权重是 0。 */
	private static final AnimationClip COMBO_MAIN_ARM = new AnimationClip(
		"combo_toss_stroke_arm",
		INSPECT_TICKS,
		List.of(
			new Keyframe(0.00F, Transform.IDENTITY),
			new Keyframe(0.12F, new Transform(0.0F, 2.0F, 1.0F, -10.0F, 0.0F, 0.0F)),
			new Keyframe(0.26F, new Transform(0.0F, -8.0F, 0.0F, 14.0F, 0.0F, 0.0F)),
			new Keyframe(0.42F, new Transform(0.0F, -7.0F, 0.0F, 12.0F, 0.0F, 0.0F)),
			new Keyframe(0.53F, new Transform(0.0F, 4.0F, 1.0F, -12.0F, 0.0F, 0.0F)),
			new Keyframe(0.60F, new Transform(0.0F, 1.0F, 0.0F, -4.0F, 0.0F, 0.0F)),
			new Keyframe(0.70F, Transform.IDENTITY),
			new Keyframe(1.00F, Transform.IDENTITY)
		)
	);

	/**
	 * 副手（左手）：抛刀接住之后，左手从下往上抹一下剑身。
	 *
	 * <p>同样用相机轴。接刀在 0.66 左右完成，所以左手从那一刻开始抬起来，
	 * 0.78 到 0.90 之间在剑身上来回抹两下，0.94 收回去。
	 */
	private static final AnimationClip COMBO_OFF_ARM = new AnimationClip(
		"combo_toss_stroke_offarm",
		INSPECT_TICKS,
		List.of(
			new Keyframe(0.00F, new Transform(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F)),
			new Keyframe(0.66F, new Transform(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F)),
			new Keyframe(0.72F, new Transform(8.0F, 2.0F, 0.0F, 0.0F, 0.0F, -6.0F)),
			new Keyframe(0.78F, new Transform(15.0F, 4.0F, 0.0F, 0.0F, 0.0F, -10.0F)),
			new Keyframe(0.84F, new Transform(10.0F, 3.0F, 0.0F, 0.0F, 0.0F, -5.0F)),
			new Keyframe(0.90F, new Transform(15.0F, 4.0F, 0.0F, 0.0F, 0.0F, -10.0F)),
			new Keyframe(0.94F, new Transform(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F)),
			new Keyframe(1.00F, new Transform(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F))
		)
	);

	// ==================== 动作池 ====================

	public static final Clip COMBO_TOSS_STROKE = Clip
		.detached(COMBO_ITEM, COMBO_MAIN_ARM, COMBO_OFF_ARM)
		.withCamera(CAMERA_HEAVY);

	/** 切刀候选池。每次切换工具时从中随机取一个。 */
	public static final List<Clip> DRAWS = List.of(
		Clip.withMainArm(DRAW_SWING_ITEM, DRAW_ARM_SWING).withCamera(CAMERA_DRAW),
		Clip.withMainArm(DRAW_SIDE_ITEM, DRAW_ARM_SIDE).withCamera(CAMERA_DRAW),
		Clip.withMainArm(DRAW_PULL_ITEM, DRAW_ARM_PULL).withCamera(CAMERA_DRAW),
		Clip.withMainArm(DRAW_DROP_ITEM, DRAW_ARM_DROP).withCamera(CAMERA_DRAW)
	);

	/** 检视候选池（通用）。 */
	public static final List<Clip> INSPECTS = List.of(
		Clip.withMainArm(INSPECT_SPIN_ITEM, INSPECT_ARM_HOLD).withCamera(CAMERA_SOFT),
		Clip.withBothArms(INSPECT_WIPE_ITEM, INSPECT_ARM_HOLD, INSPECT_WIPE_OFF_ARM).withCamera(CAMERA_SOFT),
		Clip.withMainArm(INSPECT_EXAMINE_ITEM, INSPECT_ARM_HOLD).withCamera(CAMERA_SOFT),
		Clip.withMainArm(INSPECT_TOSS_ITEM, INSPECT_TOSS_ARM).withCamera(CAMERA_SOFT),
		Clip.withMainArm(INSPECT_FLIP_ITEM, INSPECT_ARM_HOLD).withCamera(CAMERA_SOFT)
	);

	/** 剑专属的检视池：通用的几套 + 带手臂的组合动作。 */
	/** 全部动画，调试时按 id 查找用。 */
	public static List<Clip> all() {
		java.util.LinkedHashSet<Clip> set = new java.util.LinkedHashSet<>();
		set.addAll(DRAWS);
		set.addAll(INSPECTS);
		set.addAll(INSPECTS_SWORD);
		return List.copyOf(set);
	}

	public static final List<Clip> INSPECTS_SWORD = List.of(
		Clip.withMainArm(INSPECT_SPIN_ITEM, INSPECT_ARM_HOLD).withCamera(CAMERA_SOFT),
		Clip.withBothArms(INSPECT_WIPE_ITEM, INSPECT_ARM_HOLD, INSPECT_WIPE_OFF_ARM).withCamera(CAMERA_SOFT),
		Clip.withMainArm(INSPECT_EXAMINE_ITEM, INSPECT_ARM_HOLD).withCamera(CAMERA_SOFT),
		Clip.withMainArm(INSPECT_TOSS_ITEM, INSPECT_TOSS_ARM).withCamera(CAMERA_SOFT),
		Clip.withMainArm(INSPECT_FLIP_ITEM, INSPECT_ARM_HOLD).withCamera(CAMERA_SOFT),
		COMBO_TOSS_STROKE
	);

	// ===================== 攻击动作 =====================
	// 原版挥击的进度来自 LivingEntity#getAttackAnim（0..1，由攻击冷却驱动），
	// 所以这几段不是按刻数播，而是按进度采样。曲线都做成"先蓄力、再出击"：
	// 前 1/4 往后上方拉，中段甩出去，后段带一点回弹，收势时已经基本归位。

	/** 剑：从右后上方斜劈到左下方。 */
	public static final AnimationClip ATTACK_SWORD = new AnimationClip(
		"attack_sword", 1.0F, List.of(
			new Keyframe(0.00F, Transform.IDENTITY),
			// 位移给足、旋转不再加大：位移不会把剑转出画面，但能让"整条手臂扫出去"
			// 的感觉成立；旋转一过 -62°，剑尖就开始出镜。
			new Keyframe(0.12F, new Transform(0.20F, 0.11F, 0.05F, -4.0F, 7.0F, 34.0F)),
			new Keyframe(0.28F, new Transform(-0.24F, -0.09F, -0.03F, 3.0F, -9.0F, -50.0F)),
			new Keyframe(0.42F, new Transform(-0.38F, -0.16F, -0.06F, 7.0F, -14.0F, -62.0F)),
			new Keyframe(0.64F, new Transform(-0.14F, -0.05F, -0.01F, 2.0F, -4.0F, -20.0F)),
			new Keyframe(1.00F, Transform.IDENTITY)
		)
	);

	/** 斧：高举过顶，竖直劈下。 */
	public static final AnimationClip ATTACK_AXE = new AnimationClip(
		"attack_axe", 1.0F, List.of(
			new Keyframe(0.00F, Transform.IDENTITY),
			new Keyframe(0.14F, new Transform(0.04F, 0.27F, 0.09F, -40.0F, 0.0F, 6.0F)),
			new Keyframe(0.30F, new Transform(0.01F, -0.25F, -0.04F, 44.0F, 0.0F, -5.0F)),
			new Keyframe(0.46F, new Transform(0.00F, -0.11F, 0.00F, 15.0F, 0.0F, 0.0F)),
			new Keyframe(0.72F, new Transform(0.00F, -0.02F, 0.00F, 3.0F, 0.0F, 0.0F)),
			new Keyframe(1.00F, Transform.IDENTITY)
		)
	);

	/** 镐：斜着往左下方砸，带一点横向偏移，和斧区分开。 */
	public static final AnimationClip ATTACK_PICKAXE = new AnimationClip(
		"attack_pickaxe", 1.0F, List.of(
			new Keyframe(0.00F, Transform.IDENTITY),
			new Keyframe(0.14F, new Transform(0.03F, 0.24F, 0.07F, -34.0F, 0.0F, 5.0F)),
			new Keyframe(0.30F, new Transform(-0.07F, -0.22F, -0.04F, 38.0F, -7.0F, -9.0F)),
			new Keyframe(0.48F, new Transform(-0.03F, -0.10F, 0.00F, 13.0F, -2.0F, -2.0F)),
			new Keyframe(0.74F, new Transform(0.00F, -0.02F, 0.00F, 3.0F, 0.0F, 0.0F)),
			new Keyframe(1.00F, Transform.IDENTITY)
		)
	);

	/** 锄：贴得比较平，横向挥过去。 */
	public static final AnimationClip ATTACK_HOE = new AnimationClip(
		"attack_hoe", 1.0F, List.of(
			new Keyframe(0.00F, Transform.IDENTITY),
			new Keyframe(0.12F, new Transform(0.13F, 0.05F, 0.04F, 0.0F, 5.0F, 22.0F)),
			new Keyframe(0.28F, new Transform(-0.15F, -0.02F, -0.02F, 0.0F, -6.0F, -34.0F)),
			new Keyframe(0.44F, new Transform(-0.23F, -0.07F, -0.04F, 0.0F, -9.0F, -50.0F)),
			new Keyframe(0.68F, new Transform(-0.08F, -0.02F, -0.01F, 0.0F, -2.0F, -12.0F)),
			new Keyframe(1.00F, Transform.IDENTITY)
		)
	);

	/** 按手持物品选攻击动作。 */
	public static AnimationClip attackFor(net.minecraft.world.item.ItemStack stack) {
		if (stack.is(net.minecraft.tags.ItemTags.SWORDS)) {
			return ATTACK_SWORD;
		}
		if (stack.is(net.minecraft.tags.ItemTags.AXES)) {
			return ATTACK_AXE;
		}
		if (stack.is(net.minecraft.tags.ItemTags.PICKAXES)) {
			return ATTACK_PICKAXE;
		}
		if (stack.is(net.minecraft.tags.ItemTags.HOES)) {
			return ATTACK_HOE;
		}
		return null;
	}
}
