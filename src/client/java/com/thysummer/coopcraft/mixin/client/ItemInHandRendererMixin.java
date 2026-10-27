package com.thysummer.coopcraft.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.thysummer.coopcraft.client.anim.ToolAnimationController;
import com.thysummer.coopcraft.client.anim.Transform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 接管持有工具时的第一人称渲染。
 *
 * <h2>为什么必须接管</h2>
 *
 * <p>原版<b>从不同时渲染手和手上的物品</b>：{@code renderPlayerArm} 只在空手时调用，
 * 手持物品时那只手根本不画。所以"手和武器"在原版里并不存在任何对齐关系，
 * 把两者各自的变换拼在一起，必然出现手悬在剑旁边、或者插进剑身里。
 *
 * <p>接管之后，物品不再是"另一个独立物体"，而是<b>挂在手骨上的一个附件</b>：
 * 先摆好整条手臂的姿态，再在手掌位置画物品。手怎么动，武器就怎么动，永远不会脱开。
 *
 * <h2>变换顺序</h2>
 *
 * <pre>
 * 相机晃动（camera 骨，包住全部）        ← 武器和手作为整体一起晃
 *   物品轨道的动画变换
 *     原版 renderPlayerArm 的基准姿态     ← 和原版空手完全一致，所以手看起来"对"
 *       手臂轨道的额外动作
 *         手掌位置 → 物品
 *         渲染手臂
 * </pre>
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
	/** 手臂基准位置，和原版 renderPlayerArm 一致。 */
	private static final float ARM_BASE_X = 0.64F;
	private static final float ARM_BASE_Y = -0.6F;
	private static final float ARM_BASE_Z = -0.72F;

	/**
	 * 整个视图模型的旋转中心（相机空间，格）。
	 *
	 * <p>动画里的旋转要绕这个点转，而不是绕相机原点——绕原点转等于把整只手甩着转圈，
	 * 手臂和武器会一圈一圈飞出画面。取在视图模型的视觉中心，转起来才像"在手里翻"。
	 */
	@Unique
	private static final float PIVOT_X = 0.5F;
	@Unique
	private static final float PIVOT_Y = -0.45F;
	@Unique
	private static final float PIVOT_Z = -1.0F;

	/**
	 * 手掌在手臂模型坐标系里的位置（单位：格）。
	 *
	 * <p>玩家模型的右臂枢轴在 {@code (-5, 2, 0)} 像素处，方块从 y=-2 延伸到 y=+10，
	 * 所以手掌中心大约在模型原点上方 10 像素 = 0.625 格、左右 -0.3125 格处。
	 * 这里只取纵向分量，横向由 {@link #HANDLE_X} 单独调。
	 */
	private static final float HANDLE_Y = 0.627F;

	/** 武器握柄相对手掌的横向 / 前后偏移（格）。 */
	@Unique
	private static final float HANDLE_X = -0.42F;
	@Unique
	private static final float HANDLE_Z = -0.168F;

	/**
	 * 武器在手掌里的缩放。原版物品模型是 1 格见方，这里缩到 0.62 格左右，
	 * 和原版第一人称的观感接近。
	 */
	@Unique
	private static final float ITEM_SCALE = 0.78F;

	/**
	 * 武器相对手掌的姿态（度）。绕 Z 轴是让剑在屏幕平面内倾斜，
	 * 绕 Y 轴是让剑面稍微侧过去一点，避免完全正对镜头显得扁平。
	 */
	@Unique
	private static final float ITEM_ROT_X = -95.0F;
	@Unique
	private static final float ITEM_ROT_Y = 35.0F;
	@Unique
	private static final float ITEM_ROT_Z = 72.0F;

	@Unique
	private static final float ITEM_SHIFT_X = 0.10F;
	@Unique
	private static final float ITEM_SHIFT_Y = 0.52F;
	@Unique
	private static final float ITEM_SHIFT_Z = 0.0F;

	/**
	 * 自制手臂的整体缩放。以拳心为中心缩，手始终握在剑柄上，
	 * 只是手臂本身变小、更早从画面右下角出去。
	 */
	@Unique
	private static final float ARM_SCALE = 0.46F;

	/** 手臂绕自身的俯仰：把肘部从"右上"掰到"右下"（由投影实测反解）。 */
	@Unique
	private static final float ARM_PITCH = 135.0F;

	/** 挥击时手臂分担多少旋转（武器本身是全量，所以总幅度是 1 + 这个值）。 */
	@Unique
	private static final float SWING_ARM_SHARE = 0.30F;


	/** 调试投影日志的节流计数。 */
	@Unique
	private static int coopcraft$debugCounter = 0;

	/** 这一帧是否要打日志（同一帧内的多个点都要打出来，方便对比）。 */
	@Unique
	private static boolean coopcraft$debugThisFrame = false;

	/**
	 * 把一个局部点投影到屏幕坐标并打日志。
	 *
	 * <p>调手部姿态时最大的困难是"看不见就调不准"。与其对着截图猜偏移量，
	 * 不如直接把关键点（手掌、物品中心）投影到像素坐标打出来——这样能立刻知道
	 * 物体是在画面外、贴到镜头前，还是被近裁剪面切掉了。
	 */
	@Unique
	private static void coopcraft$logProjection(PoseStack poseStack, String label, float x, float y, float z) {
		if (!coopcraft$debugThisFrame) {
			return;
		}

		org.joml.Matrix4f model = poseStack.last().pose();
		org.joml.Vector4f v = new org.joml.Vector4f(x, y, z, 1.0F);
		new org.joml.Matrix4f(model).transform(v);

		// poseStack 的初始矩阵是"相机→世界"（renderItemInHand 里乘的是 view 的逆），
		// 所以上面得到的还是世界坐标；再乘一次 view 才是真正的相机空间——
		// 相机在原点、看向 -Z，这样才好判断"在镜头前还是在脑后"。
		org.joml.Matrix4f view = new org.joml.Matrix4f(
			(org.joml.Matrix4f) com.mojang.blaze3d.systems.RenderSystem.getModelViewStack()
		);
		view.transform(v);

		float camX = v.x();
		float camY = v.y();
		float camZ = v.z();

		org.joml.Matrix4f projection = com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix();
		projection.transform(v);

		// 局部坐标轴在相机空间里的指向——决定"往哪个方向推才能靠近镜头"就靠这个。
		org.joml.Vector3f axisY = new org.joml.Vector3f(model.m10(), model.m11(), model.m12());
		org.joml.Vector3f axisZ = new org.joml.Vector3f(model.m20(), model.m21(), model.m22());
		view.transformDirection(axisY);
		view.transformDirection(axisZ);
		String axes = String.format(
			java.util.Locale.ROOT,
			"localY=(%.2f,%.2f,%.2f) localZ=(%.2f,%.2f,%.2f)",
			axisY.x, axisY.y, axisY.z, axisZ.x, axisZ.y, axisZ.z
		);

		if (v.w() <= 0.001F) {
			com.thysummer.coopcraft.CoopCraft.LOGGER.info(
				"[proj] {} cam=({}, {}, {}) BEHIND w={} | {}",
				label, fmt(camX), fmt(camY), fmt(camZ), fmt(v.w()), axes
			);
			return;
		}

		float ndcX = v.x() / v.w();
		float ndcY = v.y() / v.w();
		int width = Minecraft.getInstance().getWindow().getWidth();
		int height = Minecraft.getInstance().getWindow().getHeight();
		float screenX = (ndcX * 0.5F + 0.5F) * width;
		float screenY = (1.0F - (ndcY * 0.5F + 0.5F)) * height;

		com.thysummer.coopcraft.CoopCraft.LOGGER.info(
			"[proj] {} cam=({}, {}, {}) ndc=({}, {}) screen=({}, {}) of {}x{} | {}",
			label, fmt(camX), fmt(camY), fmt(camZ), fmt(ndcX), fmt(ndcY),
			fmt(screenX), fmt(screenY), width, height, axes
		);
	}

	@Unique
	private static String fmt(float value) {
		return String.format(java.util.Locale.ROOT, "%.3f", value);
	}

	@Inject(method = "renderHandsWithItems", at = @At("HEAD"), cancellable = true)
	private void coopcraft$beforeHands(
		float partialTick,
		PoseStack poseStack,
		MultiBufferSource.BufferSource bufferSource,
		LocalPlayer player,
		int light,
		CallbackInfo ci
	) {
		ToolAnimationController controller = ToolAnimationController.INSTANCE;
		controller.setPartialTick(partialTick);
		controller.beginFrame(player);

		if (player == null || player.isScoping() || player.isInvisible()) {
			return;
		}

		// 只在拿着剑斧镐锄时接管，空手和拿其他物品时保持原版行为。
		if (!ToolAnimationController.isTool(player.getMainHandItem())) {
			return;
		}

		// 使用物品（喝药、拉弓、举盾）仍交回原版：那条分支是一长串手写变换，
		// 硬套只会打架。挥击不再交出去——攻击动作已经由我们自己驱动。
		if (player.isUsingItem()) {
			return;
		}

		ci.cancel();
		this.coopcraft$renderViewmodel(poseStack, bufferSource, light, player);
	}

	/** 画"手握武器"的整个视图模型。 */
	@Unique
	private void coopcraft$renderViewmodel(
		PoseStack poseStack,
		MultiBufferSource.BufferSource bufferSource,
		int light,
		LocalPlayer player
	) {
		ToolAnimationController controller = ToolAnimationController.INSTANCE;
		Minecraft client = Minecraft.getInstance();

		coopcraft$debugThisFrame = System.getenv("COOPCRAFT_DEBUG") != null
			&& (coopcraft$debugCounter++ % 40) == 0;

		poseStack.pushPose();

		// 视角惯性：原版在 renderHandsWithItems 开头做的一步，转头时手会稍微滞后一点点。
		// cancel 掉原版之后必须自己补上，否则手会硬邦邦地黏在镜头上。
		float partial = controller.partialTick();
		float bobX = net.minecraft.util.Mth.lerp(partial, player.xBobO, player.xBob);
		float bobY = net.minecraft.util.Mth.lerp(partial, player.yBobO, player.yBob);
		poseStack.mulPose(Axis.XP.rotationDegrees((player.getViewXRot(partial) - bobX) * 0.1F));
		poseStack.mulPose(Axis.YP.rotationDegrees((player.getViewYRot(partial) - bobY) * 0.1F));

		// 相机晃动：包在最外层，武器和双手在同一个旋转里，才像一个整体。
		Transform sway = controller.swayTransform(player, controller.partialTick());
		if (sway != null) {
			poseStack.mulPose(Axis.XP.rotationDegrees(sway.rotX()));
			poseStack.mulPose(Axis.YP.rotationDegrees(sway.rotY()));
			poseStack.mulPose(Axis.ZP.rotationDegrees(sway.rotZ()));
			poseStack.translate(sway.x(), sway.y(), sway.z());
		}

		Transform itemAnimation = controller.isAttacking()
			? controller.attackItemTransform()
			: controller.rawItemTransform();

		this.coopcraft$renderArmWithItem(
			poseStack, bufferSource, light, player, client,
			player.getMainHandItem(), player.getMainArm(),
			controller.mainArmTransform(), itemAnimation, controller.isAttacking()
		);

		// 空手也要画左手：抛刀抚剑这类动作的左手是编舞的一部分，
		// 之前只在副手真拿了东西时才画，导致"抚摸剑身"那一段永远看不到手。
		ItemStack offHand = player.getOffhandItem();
		if (!offHand.isEmpty() || controller.hasActiveOffArm()) {
			this.coopcraft$renderArmWithItem(
				poseStack, bufferSource, light, player, client,
				offHand, player.getMainArm().getOpposite(),
				controller.offArmTransform(), itemAnimation, false
			);
		}

		poseStack.popPose();

		// 原版 renderHandsWithItems 的最后一行就是这句。cancel 掉原版却不补这一句，
		// 手臂和武器的顶点会一直躺在缓冲区里没有提交——表现就是"什么都画不出来"。
		bufferSource.endBatch();
	}

	/**
	 * 画一条手臂 + 挂在它手掌上的物品。
	 *
	 * @param armAnimation  这条手臂自己的额外动作（位移单位是 1/16 格）
	 * @param itemAnimation 物品轨道的变换，整条手臂连同武器一起跟着做
	 */
	@Unique
	private static void coopcraft$renderArmWithItem(
		PoseStack poseStack,
		MultiBufferSource.BufferSource bufferSource,
		int light,
		LocalPlayer player,
		Minecraft client,
		ItemStack stack,
		HumanoidArm arm,
		Transform armAnimation,
		Transform itemAnimation,
		boolean attacking
	) {
		boolean right = arm == HumanoidArm.RIGHT;
		float h = right ? 1.0F : -1.0F;

		poseStack.pushPose();

		// 1) 物品轨道的位移：整个视图模型一起挪。
		//    旋转**不**放在这里——手臂有 12 个单位长，让它跟着武器翻一圈会直接糊满屏幕，
		//    所以旋转只作用在武器上，绕手掌转，像手腕转动那样。
		if (itemAnimation != null) {
			poseStack.translate(itemAnimation.x(), itemAnimation.y(), itemAnimation.z());
		}

		// 2) 左手先把位移加上，此时坐标系还是相机轴（+x 屏幕右、+y 屏幕上、+z 朝镜头）。
		//
		//    右手不能这么改：它那一身数值都是在原版"偏航 45° 之后"的轴上一点点试出来的，
		//    换轴等于全部重调。而左手原来也走这个 45° 偏航轴，+x 实际指向镜头，
		//    写"往右 20 像素"得到的是"前冲 0.9 格"，于是手直接穿过近裁剪面消失。
		if (!right && armAnimation != null) {
			poseStack.translate(
				armAnimation.x() / 16.0F, armAnimation.y() / 16.0F, armAnimation.z() / 16.0F
			);
		}

		poseStack.translate(h * ARM_BASE_X, ARM_BASE_Y, ARM_BASE_Z);
		poseStack.mulPose(Axis.YP.rotationDegrees(h * 45.0F));

		// 3) 手臂自己的额外动作。放在 45° 之前，这里的 x/y/z 就是
		//    "屏幕右 / 上 / 靠近镜头"，写动画时不用换算坐标系。
		if (armAnimation != null) {
			if (right) {
				poseStack.translate(
					armAnimation.x() / 16.0F, armAnimation.y() / 16.0F, armAnimation.z() / 16.0F
				);
			}
			poseStack.mulPose(Axis.XP.rotationDegrees(armAnimation.rotX()));
			poseStack.mulPose(Axis.YP.rotationDegrees(armAnimation.rotY()));
			poseStack.mulPose(Axis.ZP.rotationDegrees(armAnimation.rotZ()));
		}

		// 4) 原版剩下的基准旋转，把 12 单位长的方块手臂摆到屏幕右下角。
		//
		//    X 轴那一步（200°）**不能**乘 h。镜像矩阵 S=diag(-1,1,1) 和旋转的关系是
		//    S·R(θ)·S = R(-θ) 只对 Y、Z 成立；绕 X 的旋转不碰 x 分量，所以 S·Rx(θ)·S = Rx(θ)。
		//    也就是说"左右手互为镜像"要求：位移的 x 取反、Y/Z 旋转取反、X 旋转保持不变。
		//    原版这条链恰好就是这个形式，本身就是正确的镜像——之前怀疑它、把它乘上 h，
		//    反而把左手推到相机外。真正错的是下面那条手臂轨道的数值（按错误锚点写的）。
		poseStack.translate(h * -1.0F, 3.6F, 3.5F);
		poseStack.mulPose(Axis.ZP.rotationDegrees(h * 120.0F));
		poseStack.mulPose(Axis.XP.rotationDegrees(200.0F));
		poseStack.mulPose(Axis.YP.rotationDegrees(h * -135.0F));
		poseStack.translate(h * 5.6F, 0.0F, 0.0F);

		// 挥击时整条手臂跟着甩。
		//
		// 只让武器转 = 手腕拧一下，看着没有力量。但直接把旋转加在最外层会绕相机原点转，
		// 手臂会被甩出画面。这里的做法是：先走到拳心，在那里转，再退回手臂空间——
		// 于是旋转中心正好是握持点，小臂跟着荡出去，手却还留在原来的位置。
		poseStack.pushPose();
		if (attacking && itemAnimation != null) {
			poseStack.translate(h * HANDLE_X, HANDLE_Y, HANDLE_Z);
			poseStack.mulPose(Axis.XP.rotationDegrees(itemAnimation.rotX() * SWING_ARM_SHARE));
			poseStack.mulPose(Axis.YP.rotationDegrees(h * itemAnimation.rotY() * SWING_ARM_SHARE));
			poseStack.mulPose(Axis.ZP.rotationDegrees(h * itemAnimation.rotZ() * SWING_ARM_SHARE));
			poseStack.translate(-h * HANDLE_X, -HANDLE_Y, -HANDLE_Z);
		}

		coopcraft$logProjection(poseStack, right ? "arm-right-origin" : "arm-left-origin", 0.0F, 0.0F, 0.0F);

		// 5) 物品挂在手掌上。此时坐标系是玩家模型空间（原点在脚底中心，
		//    单位是格，+Y 向下），手掌在 y = HANDLE_Y 处。
		if (!stack.isEmpty()) {
			poseStack.pushPose();
			poseStack.translate(h * HANDLE_X, HANDLE_Y, HANDLE_Z);
			coopcraft$logProjection(poseStack, "item-center", 0.0F, 0.0F, 0.0F);
			poseStack.mulPose(Axis.XP.rotationDegrees(ITEM_ROT_X));
			poseStack.mulPose(Axis.YP.rotationDegrees(h * ITEM_ROT_Y));
			poseStack.mulPose(Axis.ZP.rotationDegrees(h * ITEM_ROT_Z));

			// 动画旋转放在"已经摆正"之后，这样它绕的就是武器自己的轴：
			// X = 上下翻面，Y = 左右翻面，Z = 在屏幕平面内转。写动画时不用再换坐标系。
			if (itemAnimation != null) {
				poseStack.mulPose(Axis.XP.rotationDegrees(itemAnimation.rotX()));
				poseStack.mulPose(Axis.YP.rotationDegrees(itemAnimation.rotY()));
				poseStack.mulPose(Axis.ZP.rotationDegrees(itemAnimation.rotZ()));
			}

			poseStack.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE);

			// 优先画 3D 模型；没有对应模型（比如其他模组的非工具物品）就退回原版平面渲染。
			if (!com.thysummer.coopcraft.client.render.WeaponRenderer.render(
				poseStack, bufferSource, stack, light
			)) {
				client.getItemRenderer().renderStatic(
					stack,
					ItemDisplayContext.NONE,
					light,
					OverlayTexture.NO_OVERLAY,
					poseStack,
					bufferSource,
					player.level(),
					stack.hashCode()
				);
			}
			poseStack.popPose();
		}

		// 6) 手臂本体。
		//
		// 这里不再调用 renderRightHand 画玩家皮肤的方块手臂——那条手臂没有手掌也没有手指，
		// 近看就是一块色块。换成自制网格，且网格原点和武器共用同一个握持点，
		// 所以"手握在剑柄上"是几何上成立的，不是靠常量凑出来的。
		poseStack.pushPose();
		poseStack.translate(h * HANDLE_X, HANDLE_Y, HANDLE_Z);
		// 手臂朝向不是猜的：把拳心和肘部两点投影到屏幕量出来——
		// 网格 +Y 在屏幕上是"右上"，而小臂应该朝"右下"。
		// 用相机空间正交基反解，方向应落在 local 的 (-Y, +Z) 之间 → 绕 X 转 -45°。
		// 135° 是把拳心、肘部两点投到屏幕上反解出来的。
		// 左手同样用 +135：X 轴旋转在镜像下保持不变（见上面第 4 步的说明）。
		poseStack.mulPose(Axis.XP.rotationDegrees(ARM_PITCH));
		// 缩放围绕拳心（网格原点）进行，所以手不会离开剑柄，只是整条手臂变小、更早出画。
		poseStack.scale(ARM_SCALE, ARM_SCALE, ARM_SCALE);
		// 这两个点决定手臂"朝哪边长"：拳心是锚点，肘部方向是网格 +Y（导出时已翻转）。
		coopcraft$logProjection(poseStack, right ? "fist-R" : "fist-L", 0.0F, 0.0F, 0.0F);
		coopcraft$logProjection(poseStack, right ? "elbow-R" : "elbow-L", 0.0F, 0.6F, 0.0F);
		com.thysummer.coopcraft.client.render.ViewmodelArm.render(
			poseStack, bufferSource, light, !right
		);
		poseStack.popPose();

		// 挥击那一段 push 必须有对应的 pop。漏掉它会在栈上留一层残留，
		// 之后画的左手（以及这一帧后续的所有渲染）都会继承挥击的旋转。
		poseStack.popPose();

		poseStack.popPose();
	}
}
