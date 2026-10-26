package com.thysummer.coopcraft.client;

import java.io.File;
import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;
import com.thysummer.coopcraft.CoopCraft;
import com.thysummer.coopcraft.client.anim.ToolAnimationController;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import org.lwjgl.glfw.GLFW;

/**
 * 把画面写成文件，让"看不见"的一方也能看到实际效果。
 *
 * <p>没有这个之前，所有第一人称的位置、角度、大小都只能靠描述来回猜——改一次构建一次，
 * 一次几十秒，而且改得对不对完全取决于对方怎么形容。现在按一下键，游戏会把连续若干帧
 * 存进 {@code run/screenshots/}，日志里同时记下每一帧对应的是哪段动画、第几刻，
 * 于是一个动画的完整过程就变成了一组可以逐一查看的图片。
 *
 * <p>实现上只是转发 {@link Screenshot#grab}，没有自己读帧缓冲，所以分辨率和正常截图一致。
 * 挂在世界渲染结束时抓，因此不含 HUD——只有手和武器，正好是调姿态要看的东西。
 */
public final class CaptureHelper {
	/** 手动按 F8 时一次连拍多少帧。 */
	private static final int FRAMES = 12;
	/** 手动连拍的帧间隔（刻）。10 刻 = 0.5 秒。 */
	private static final int INTERVAL_TICKS = 10;

	private static final KeyMapping captureKey = KeyBindingHelper.registerKeyBinding(
		new KeyMapping("key.coopcraft.capture", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "category.coopcraft")
	);

	// ---- 连拍状态 ----
	private static int remaining = 0;
	private static int interval = INTERVAL_TICKS;
	private static int cooldown = 0;
	private static int index = 0;
	private static String stamp = "";
	private static String label = "";

	// ---- 自动演示状态 ----
	/** -1 表示没启用。 */
	private static int demoPhase = -1;
	private static int demoTimer = 0;

	private CaptureHelper() {
	}

	/** 客户端初始化时调用一次。 */
	public static void initialize() {
		// 触碰一下静态字段，让按键在初始化阶段完成注册。
		KeyMapping key = captureKey;
		if (key == null) {
			throw new IllegalStateException("capture key not registered");
		}
		if (System.getenv("COOPCRAFT_DEMO") != null) {
			demoPhase = 0;
			demoTimer = 0;
			CoopCraft.LOGGER.info("[demo] auto demo armed");
		}
	}

	/** 每个客户端 tick 检查按键并推进演示。 */
	public static void tick() {
		while (captureKey.consumeClick()) {
			startBurst(FRAMES, INTERVAL_TICKS, "manual");
		}

		if (cooldown > 0) {
			cooldown--;
		}

		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}

		if (demoPhase >= 0) {
			tickDemo(client);
		}
	}

	/**
	 * 自动演示：进世界后自己发命令拿武器，然后依次播放每段动画并整段采样。
	 *
	 * <p>这样一次启动就能拿到全套对照画面，不需要有人守在屏幕前按按键。
	 * 由环境变量 {@code COOPCRAFT_DEMO} 触发，正常游玩时完全不生效。
	 */
	private static void tickDemo(Minecraft client) {
		demoTimer++;

		// 演示期间强行关掉暂停菜单。
		// 后台运行游戏很容易失焦，一旦弹出暂停菜单世界就停了，而截图仍然在拍——
		// 结果是一整段连拍全是同一张图，看上去像"动画没动"，其实是根本没在跑。
		client.options.pauseOnLostFocus = false;
		if (client.screen instanceof net.minecraft.client.gui.screens.PauseScreen) {
			client.setScreen(null);
		}

		// 依次换上四种工具各拍一张，检查每类 3D 模型在手里的样子。
		String[][] plan = {
			{"minecraft:diamond_sword", "sword"},
			{"minecraft:iron_axe", "axe"},
			{"minecraft:iron_pickaxe", "pickaxe"},
			{"minecraft:iron_hoe", "hoe"},
		};

		int index = demoPhase / 2 * 0 + demoPhase;
		switch (demoPhase) {
			case 0 -> {
				if (demoTimer > 30) {
					command(client, "gamemode creative");
					command(client, "time set noon");
					command(client, "weather clear");
					command(client, "item replace entity @s weapon.mainhand with " + plan[0][0]);
					demoPhase = 1;
					demoTimer = 0;
				}
			}
			case 1, 3, 5, 7 -> {
				int slot = (demoPhase - 1) / 2;
				if (demoTimer > 35 && !isBusy()) {
					startBurst(2, 4, plan[slot][1]);
					demoPhase++;
					demoTimer = 0;
				}
			}
			case 2, 4, 6 -> {
				int next = demoPhase / 2;
				if (demoTimer > 15 && !isBusy()) {
					command(client, "item replace entity @s weapon.mainhand with " + plan[next][0]);
					demoPhase++;
					demoTimer = 0;
				}
			}
			case 8 -> {
				if (demoTimer > 15 && !isBusy()) {
					command(client, "item replace entity @s weapon.mainhand with minecraft:diamond_sword");
					demoPhase = 9;
					demoTimer = 0;
				}
			}
			case 9 -> {
				if (demoTimer > 20 && !isBusy()) {
					CoopCraft.LOGGER.info("[demo] phase 9: attack swing");
					client.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
					startBurst(8, 1, "attack");
					demoPhase = 10;
				}
			}
			// 四个切刀变体逐个采样。之前只拍过 draw_drop，另外三个从没进过画面。
			case 10 -> draw(client, "draw_swing", "drawswing", 11);
			case 11 -> draw(client, "draw_side", "drawside", 12);
			case 12 -> draw(client, "draw_pull", "drawpull", 13);
			case 13 -> draw(client, "draw_drop", "drawdrop", 14);
			case 14 -> {
				if (demoTimer > 45 && !isBusy()) {
					CoopCraft.LOGGER.info("[demo] phase 14: inspect animation");
					com.thysummer.coopcraft.client.anim.ToolAnimationController.INSTANCE
						.debugPlay("inspect_toss", client.player);
					startBurst(12, 6, "inspect");
					demoPhase = 15;
					demoTimer = 0;
				}
			}
			// 抛刀 → 接住 → 左手抚摸剑身。这是整套动作里最复杂的一段。
			case 15 -> {
				if (demoTimer > 45 && !isBusy()) {
					CoopCraft.LOGGER.info("[demo] phase 15: toss + stroke combo");
					com.thysummer.coopcraft.client.anim.ToolAnimationController.INSTANCE
						.debugPlay(com.thysummer.coopcraft.client.anim.Animations.COMBO_TOSS_STROKE, client.player);
					startBurst(14, 8, "combo");
					demoPhase = 16;
					demoTimer = 0;
				}
			}
			// 斧 / 镐 / 锄的挥击：这三套曲线一直没被单独看过。
			case 16 -> equip(client, "minecraft:iron_axe", 17);
			case 17 -> swing(client, "axehit", 18);
			case 18 -> equip(client, "minecraft:iron_pickaxe", 19);
			case 19 -> swing(client, "pickaxehit", 20);
			case 20 -> equip(client, "minecraft:iron_hoe", 21);
			case 21 -> swing(client, "hoehit", 22);
			// 真实按键路径：模拟按下 V。demo 里其余动作都走 debugPlay，
			// 只有这里验证"玩家按 V 真的会播检视"这条从按键到动画的完整链路。
			case 22 -> {
				if (demoTimer > 30 && !isBusy()) {
					CoopCraft.LOGGER.info("[demo] press the inspect key");
					net.minecraft.client.KeyMapping.click(
						com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(
							com.mojang.blaze3d.platform.InputConstants.KEY_V
						)
					);
					startBurst(6, 12, "inspectkey");
					demoPhase = -1;
					demoTimer = 0;
				}
			}
			default -> {
			}
		}
	}

	/** 换主手物品，然后进入下一阶段。 */
	private static void equip(Minecraft client, String itemId, int nextPhase) {
		if (demoTimer > 20 && !isBusy()) {
			command(client, "item replace entity @s weapon.mainhand with " + itemId);
			demoPhase = nextPhase;
			demoTimer = 0;
		}
	}

	/** 挥一下并连拍，然后进入下一阶段。 */
	private static void swing(Minecraft client, String tag, int nextPhase) {
		if (demoTimer > 20 && !isBusy()) {
			CoopCraft.LOGGER.info("[demo] swing {}", tag);
			client.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
			startBurst(8, 1, tag);
			demoPhase = nextPhase;
			demoTimer = 0;
		}
	}

	/** 播一个切刀变体并连拍，然后进入下一阶段。 */
	private static void draw(Minecraft client, String clipId, String tag, int nextPhase) {
		if (demoTimer > 40 && !isBusy()) {
			CoopCraft.LOGGER.info("[demo] play {}", clipId);
			com.thysummer.coopcraft.client.anim.ToolAnimationController.INSTANCE
				.debugPlay(clipId, client.player);
			startBurst(10, 2, tag);
			demoPhase = nextPhase;
			demoTimer = 0;
		}
	}

	private static void command(Minecraft client, String cmd) {
		if (client.player != null && client.player.connection != null) {
			client.player.connection.sendCommand(cmd);
		}
	}

	/** 开始一次连拍。 */
	public static void startBurst(int frames, int intervalTicks, String tag) {
		remaining = frames;
		interval = Math.max(1, intervalTicks);
		cooldown = 0;
		index = 0;
		label = tag;
		stamp = new java.text.SimpleDateFormat("MMdd-HHmmss").format(new java.util.Date());
		CoopCraft.LOGGER.info("[cap] burst '{}' start, {} frames every {} ticks", tag, frames, interval);
	}

	/**
	 * 在第一人称手部绘制之后调用（见 {@code GameRendererMixin}）。
	 *
	 * <p>必须在这个时刻抓：早了手还没画进去，晚了 GUI 会盖上来。
	 */
	public static void onAfterHands() {
		if (remaining <= 0 || cooldown > 0) {
			return;
		}

		Minecraft client = Minecraft.getInstance();
		ToolAnimationController controller = ToolAnimationController.INSTANCE;
		String clip = controller.activeClipId();
		float progress = controller.activeProgressTicks();

		// 文件名必须自带 .png——原版 F2 那套是外部拼的名字，这里我们直接给定全名。
		String name = String.format(Locale.ROOT, "coop-%s-%s-f%02d.png", label, stamp, index);
		Screenshot.grab(client.gameDirectory, name, client.getMainRenderTarget(), message -> {
		});

		CoopCraft.LOGGER.info(
			"[cap] {} clip={} t={} tick",
			name, clip, String.format(Locale.ROOT, "%.1f", progress)
		);

		index++;
		remaining--;
		cooldown = interval;
	}

	/** 供外部判断当前是否在连拍，避免重复触发。 */
	public static boolean isBusy() {
		return remaining > 0;
	}

	/** 截图目录，方便直接去翻。 */
	public static File directory() {
		return new File(Minecraft.getInstance().gameDirectory, Screenshot.SCREENSHOT_DIR);
	}
}
