package com.thysummer.coopcraft.client.anim;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import org.lwjgl.glfw.GLFW;

/**
 * 决定"什么时候播哪段动画"，并在渲染时给出当前时刻的各条轨道。
 *
 * <p>每帧开始时由渲染入口调用一次 {@link #beginFrame}，把当前动作和进度算好存起来，
 * 之后物品和手臂的渲染都读这份状态——保证同一帧里刀和手用的是同一个时间点，
 * 不会出现手比刀慢一拍的情况。
 */
public final class ToolAnimationController {
	public static final ToolAnimationController INSTANCE = new ToolAnimationController();

	/** 当前挥击用的动画；不在挥击时为 null。 */
	private AnimationClip activeAttack;
	/** 挥击进度 0..1。 */
	private float attackProgress;

	/**
	 * 拿着剑斧镐锄时是否一直显示主手。
	 *
	 * <p>原版第一人称持物时<b>完全不画手臂</b>（只有空手才画），所以要不要一直显示
	 * 是个风格选择：开着更像参考模组那种"视图模型"，但手臂会一直占着画面下缘；
	 * 关掉的话只有播动画时才出现，平时和原版一样。
	 */
	public static final boolean SHOW_ARM_ALWAYS = true;

	private final KeyMapping inspectKey = KeyBindingHelper.registerKeyBinding(
		new KeyMapping("key.coopcraft.inspect", InputConstants.Type.KEYSYM, InputConstants.KEY_V, "category.coopcraft")
	);

	private final ShuffleBag<Clip> drawBag = new ShuffleBag<>(Animations.DRAWS, 0xC0FFEE);
	private final ShuffleBag<Clip> inspectBagCommon = new ShuffleBag<>(Animations.INSPECTS, 0xBEEF);
	private final ShuffleBag<Clip> inspectBagSword = new ShuffleBag<>(Animations.INSPECTS_SWORD, 0x5A0D);

	/** 顺序播放（按住 Shift）时的游标。 */
	private int orderedIndex = 0;

	// ---- 每帧计算出来的状态 ----
	private Clip activeClip;
	private float activeElapsed;

	/** 当前正在播放的动画，null 表示没有。 */
	private Clip current;
	/** 动画开始时刻（玩家 tickCount）。 */
	private int startTick;
	/** 上一 tick 主手物品，用来检测"切换了物品"。 */
	private Item lastMainHandItem = null;
	/** 上一 tick 主手物品是否属于剑/斧/镐/锄。 */
	private boolean lastWasTool = false;
	/** 本帧的部分刻（0~1）。 */
	private float partialTick = 0.0F;

	private ToolAnimationController() {
	}

	public KeyMapping inspectKey() {
		return this.inspectKey;
	}

	public void setPartialTick(float partialTick) {
		this.partialTick = partialTick;
	}

	/** 当前帧的部分刻，渲染层算晃动时要用。 */
	public float partialTick() {
		return this.partialTick;
	}

	/**
	 * 每帧渲染前调用一次，算好当前动作和进度。
	 *
	 * @param player 当前玩家，可能为 null（加载界面等）
	 */
	public void beginFrame(LocalPlayer player) {
		this.activeClip = null;
		this.activeAttack = null;

		if (player == null) {
			return;
		}

		// 挥击：用原版的攻击进度驱动，而不是自己计时——
		// 这样动作长度会跟着攻击冷却走，和别人对砍时节奏才对得上。
		if (player.swinging) {
			this.activeAttack = Animations.attackFor(player.getMainHandItem());
			this.attackProgress = player.getAttackAnim(this.partialTick);
		}

		if (this.current == null) {
			return;
		}

		float elapsed = player.tickCount + this.partialTick - this.startTick;
		if (elapsed >= this.current.effectiveDurationTicks()) {
			this.current = null;
			return;
		}

		this.activeClip = this.current;
		this.activeElapsed = Math.max(0.0F, elapsed);
	}

	/** 物品轨道。返回 null 表示这一帧不做任何变换。 */
	public Transform itemTransform(ItemDisplayContext context, ItemStack stack) {
		if (this.activeClip == null) {
			return null;
		}

		// 只处理主手：副手渲染时直接放过。
		if (context != ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
			&& context != ItemDisplayContext.FIRST_PERSON_LEFT_HAND) {
			return null;
		}

		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return null;
		}

		boolean isRightContext = context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
		boolean isRightArm = player.getMainArm() == HumanoidArm.RIGHT;
		if (isRightContext != isRightArm) {
			return null;
		}

		if (!isTool(stack)) {
			return null;
		}

		return this.activeClip.sampleItem(this.activeElapsed);
	}

	/**
	 * 当前武器自身的动画变换，不做任何"哪只手 / 是不是工具"的过滤。
	 *
	 * <p>手臂渲染要拿它来让手跟着武器一起动，所以不能走 {@link #itemTransform}——
	 * 那个方法会在副手、非工具等情况下返回 null。
	 */
	public Transform rawItemTransform() {
		return this.activeClip == null ? null : this.activeClip.sampleItem(this.activeElapsed);
	}

	/** 主手手臂轨道的额外偏移；null 表示只有"跟随武器"这一层。 */
	public Transform mainArmTransform() {
		return this.activeClip == null ? null : this.activeClip.sampleMainArm(this.activeElapsed);
	}

	/** 攻击动作的物品变换；没在挥击时返回 null。 */
	public Transform attackItemTransform() {
		return this.activeAttack == null ? null : this.activeAttack.sampleProgress(this.attackProgress);
	}

	/** 这一帧是否在挥击。 */
	/** 当前这段动画有没有编排左手。 */
	public boolean hasActiveOffArm() {
		return this.activeClip != null && this.activeClip.hasOffArm();
	}

	public boolean isAttacking() {
		return this.activeAttack != null;
	}

	/** 手臂跟随武器的权重。 */
	public float armFollow() {
		// 挥击时手必须死死咬住武器：这时候动作幅度最大，跟得不紧就会脱手。
		if (this.activeAttack != null) {
			return 1.0F;
		}
		return this.activeClip == null ? 0.0F : this.activeClip.armFollow();
	}

	/** 副手是否要在这一帧出现。 */
	public boolean offArmVisible() {
		return this.activeClip != null && this.activeClip.hasOffArm();
	}

	public Transform offArmTransform() {
		return this.activeClip == null ? null : this.activeClip.sampleOffArm(this.activeElapsed);
	}

	public boolean isAnimating() {
		return this.activeClip != null;
	}

	/** 当前动作的名字，没有动作时返回 "idle"。给调试截图打标记用。 */
	public String activeClipId() {
		if (this.activeClip != null) {
			return this.activeClip.id();
		}
		return this.current == null ? "idle" : this.current.id() + "*";
	}

	/** 当前动作已经播放了多少刻。 */
	public float activeProgressTicks() {
		if (this.current == null) {
			return 0.0F;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return 0.0F;
		}
		return Math.max(0.0F, player.tickCount + this.partialTick - this.startTick);
	}

	/**
	 * 这一帧整个视图模型相对镜头的晃动。
	 *
	 * <p>播动画时用动画自带的 camera 轨道（对应参考模组的 camera 骨）；
	 * 平时用一个极缓慢的呼吸式摆动，并且随移动速度加大——站着时几乎察觉不到，
	 * 跑起来会有轻微起伏，这是"东西有重量"的主要来源。
	 *
	 * @return null 表示这一帧不晃
	 */
	public Transform swayTransform(LocalPlayer player, float partialTick) {
		if (this.activeClip != null) {
			return this.activeClip.sampleCamera(this.activeElapsed);
		}

		return idleSway(player, partialTick);
	}

	private static Transform idleSway(LocalPlayer player, float partialTick) {
		if (player == null) {
			return null;
		}

		// 每刻位移 × 20 = 每秒方块数。走/跑分别对应 4.3 / 5.6，用 4.5 做满幅阈值。
		float speed = (float) (player.getDeltaMovement().horizontalDistance() * 20.0);
		float moving = Math.min(1.0F, speed / 4.5F);

		float t = player.tickCount + partialTick;
		float amp = 0.28F + 1.10F * moving;

		float rx = Mth.sin(t * 0.21F + 1.3F) * amp;
		float ry = Mth.sin(t * 0.17F + 2.7F) * amp * 0.55F;
		float rz = Mth.sin(t * 0.35F) * amp * 0.90F;
		float dy = Mth.sin(t * 0.70F) * (0.004F + 0.012F * moving);

		return new Transform(0.0F, dy, 0.0F, rx, ry, rz);
	}

	/** 每个客户端 tick 末尾调用。 */
	public void tick() {
		// 无论有没有玩家都要把按键队列排空，否则等在菜单里按下的次数会攒着。
		boolean inspectRequested = false;
		while (this.inspectKey.consumeClick()) {
			inspectRequested = true;
		}

		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			this.current = null;
			this.lastMainHandItem = null;
			this.lastWasTool = false;
			return;
		}

		ItemStack held = player.getMainHandItem();
		Item item = held.getItem();
		boolean holdingTool = isTool(held);

		if (item != this.lastMainHandItem) {
			boolean touchingTool = holdingTool || this.lastWasTool;
			this.lastMainHandItem = item;
			this.lastWasTool = holdingTool;

			if (touchingTool) {
				if (holdingTool) {
					this.play(this.drawBag.next(), player);
				} else {
					this.current = null;
				}
			}
		}

		if (inspectRequested && holdingTool) {
			if (this.current != null) {
				// 检视有 8 秒，再按一次直接取消，不用硬等。
				this.current = null;
			} else {
				boolean ordered = InputConstants.isKeyDown(
					Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT
				) || InputConstants.isKeyDown(
					Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT
				);

				Clip clip = ordered ? this.nextOrdered(held) : this.poolFor(held).next();
				this.play(clip, player);
			}
		}

		// 挥击或使用物品时立刻中断，不然动作会和原版的手部动画打架。
		if (this.current != null && (player.swinging || player.isUsingItem())) {
			this.current = null;
		}
	}

	/** 剑有专属动作池（带手臂的组合动作），其他工具用通用池。 */
	private ShuffleBag<Clip> poolFor(ItemStack stack) {
		return stack.is(ItemTags.SWORDS) ? this.inspectBagSword : this.inspectBagCommon;
	}

	/**
	 * 调试用：按 id 直接播一段，不看手上拿什么。
	 *
	 * <p>自动化视觉验证需要"精确地播这一段"，随机抽取和手持物品判断都会碍事，
	 * 所以留一个直通入口。返回 false 表示没有这个 id。
	 */
	public boolean debugPlay(String clipId, LocalPlayer player) {
		if (player == null) {
			return false;
		}
		for (Clip clip : Animations.all()) {
			if (clip.id().equals(clipId)) {
				this.play(clip, player);
				return true;
			}
		}
		return false;
	}

	/**
	 * 调试用：直接播一个具体的 Clip。
	 *
	 * <p>同一个物品轨可以拼出多个 Clip（{@code inspect_toss} 既有单手的，也有"抛刀 + 左手抚剑"的
	 * 组合版），它们的 id 相同，按 id 找不到后者，所以这里留一个按对象播的入口。
	 */
	public void debugPlay(Clip clip, LocalPlayer player) {
		if (player != null && clip != null) {
			this.play(clip, player);
		}
	}

	private Clip nextOrdered(ItemStack stack) {
		var pool = stack.is(ItemTags.SWORDS) ? Animations.INSPECTS_SWORD : Animations.INSPECTS;
		Clip clip = pool.get(this.orderedIndex % pool.size());
		this.orderedIndex++;
		return clip;
	}

	private void play(Clip clip, LocalPlayer player) {
		this.current = clip;
		this.startTick = player.tickCount;
	}

	/**
	 * 剑、斧、镐、锄都算。
	 *
	 * <p>用原版物品标签而不是判断具体类：这样以后装的模组武器只要打了对应标签，
	 * 也会自动带上动画，不需要改代码。
	 */
	public static boolean isTool(ItemStack stack) {
		return stack.is(ItemTags.SWORDS)
			|| stack.is(ItemTags.AXES)
			|| stack.is(ItemTags.PICKAXES)
			|| stack.is(ItemTags.HOES);
	}
}
