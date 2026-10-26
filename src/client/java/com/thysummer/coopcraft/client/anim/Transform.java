package com.thysummer.coopcraft.client.anim;

/**
 * 相对原版手持姿态的一组变换：位移（单位是方块，1.0 = 一格）+ 欧拉角旋转（度）。
 *
 * <p>坐标系沿用第一人称渲染时的相机空间：+X 向右，+Y 向上，-Z 朝向屏幕里。
 * 也就是说要把物品"拉近镜头"，应该让 z 变大（往 0 靠）。
 */
public record Transform(float x, float y, float z, float rotX, float rotY, float rotZ) {
	public static final Transform IDENTITY = new Transform(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);

	/**
	 * 在两组变换之间线性插值。旋转直接对欧拉角插值——因为下面的关键帧角度都
	 * 控制在较小范围（没有跨 ±180 的跳变），不需要走四元数。
	 */
	public Transform lerp(Transform other, float t) {
		return new Transform(
			x + (other.x - x) * t,
			y + (other.y - y) * t,
			z + (other.z - z) * t,
			rotX + (other.rotX - rotX) * t,
			rotY + (other.rotY - rotY) * t,
			rotZ + (other.rotZ - rotZ) * t
		);
	}
}
