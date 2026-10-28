package com.moyu.infinitystones.item;

/**
 * 六颗无限宝石的枚举定义。
 * id 同时用于注册名（&lt;id&gt;_stone）与语言文件键。
 */
public enum InfinityStone {

    SPACE("space", 0x3B6FE0),
    MIND("mind", 0xF3C519),
    REALITY("reality", 0xD63A2F),
    POWER("power", 0x9B30D9),
    TIME("time", 0x2FC96F),
    SOUL("soul", 0xFF7B1A);

    private final String id;
    private final int color;

    InfinityStone(String id, int color) {
        this.id = id;
        this.color = color;
    }

    public String getId() {
        return this.id;
    }

    /** 十六进制 RGB，用于粒子与提示文字上色。 */
    public int getColor() {
        return this.color;
    }

    public String getItemName() {
        return this.id + "_stone";
    }

    public String getDisplayKey() {
        return "stone.infinitystones." + this.id;
    }

    public String getDescriptionKey() {
        return "stone.infinitystones." + this.id + ".desc";
    }
}
