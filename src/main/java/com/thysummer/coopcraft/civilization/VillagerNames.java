package com.thysummer.coopcraft.civilization;

import java.util.Map;
import java.util.UUID;

import com.thysummer.coopcraft.civilization.simulation.Person;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.npc.Villager;

public final class VillagerNames {
	private static final String[] GIVEN_NAMES = {
		"阿岩", "青禾", "石生", "禾苗", "安", "宁", "丰", "实", "勤", "巧", "勇", "韧",
		"乐", "平", "山", "川", "河", "溪", "泉", "林", "森", "阳", "月", "星",
		"晨", "暮", "春", "夏", "秋", "冬", "风", "云", "雨", "雪", "岚", "野",
		"芒", "桑", "榆", "柏", "竹", "桃", "杏", "麦", "苗", "秀", "英", "兰",
		"梅", "莲", "菊", "松", "鹤", "鹿", "鸿", "燕", "鹭", "牛", "羊", "鱼",
		"贝", "陶", "炭", "犁", "穗", "井", "田", "牧", "歌", "织", "锦", "书",
		"画", "琴", "棋", "晨露", "晚星", "清风", "明月", "远山", "望舒", "听雨", "知夏", "念秋",
		"春华", "秋实", "木生", "水生", "土生", "金生", "石根", "石柱", "青苗", "小满", "谷雨", "立夏",
		"白露", "霜降", "大雪", "小雪", "冬至", "夏至"
	};
	private static final String[] FAMILY_NAMES = {
		"李", "王", "张", "刘", "陈", "杨", "赵", "黄", "周", "吴", "徐", "孙",
		"胡", "朱", "高", "林", "何", "郭", "马", "罗", "梁", "宋", "郑", "谢",
		"韩", "唐", "冯", "于", "董", "萧", "程", "曹", "袁", "邓", "许", "傅",
		"沈", "曾", "彭", "吕", "苏", "卢", "蒋", "蔡", "贾", "丁", "魏", "薛",
		"叶", "阎", "余", "潘", "杜", "戴", "夏", "钟", "汪", "田", "任", "姜",
		"范", "方", "石", "姚", "谭", "廖", "邹", "熊", "金", "陆", "郝", "孔",
		"白", "崔", "康", "毛", "邱", "秦", "江", "史", "顾", "侯", "邵", "孟"
	};
	private static final String[] LEGACY_GIVEN_NAMES = {
		"Aren", "Bela", "Cato", "Dara", "Eli", "Fara", "Galen", "Hana",
		"Ivo", "Jora", "Kai", "Lena", "Mira", "Nero", "Orin", "Pia",
		"Quin", "Rhea", "Soren", "Tara", "Ulla", "Vero", "Wren", "Yara", "Zeno"
	};
	private static final String[] LEGACY_FAMILY_NAMES = {
		"Ash", "Birch", "Brook", "Clay", "Dale", "Flint", "Grove", "Hollow",
		"Marsh", "Reed", "Stone", "Vale", "Willow", "Wren", "Field", "Frost"
	};

	private VillagerNames() {
	}

	public static String fullName(UUID villagerId) {
		return familyName(villagerId) + givenName(villagerId);
	}

	public static String givenName(UUID villagerId) {
		long seed = villagerId.getMostSignificantBits() ^ Long.rotateLeft(villagerId.getLeastSignificantBits(), 17);
		return GIVEN_NAMES[hash(seed, GIVEN_NAMES.length)];
	}

	public static String familyName(UUID villagerId) {
		long seed = Long.rotateLeft(villagerId.getMostSignificantBits(), 13) ^ villagerId.getLeastSignificantBits();
		return FAMILY_NAMES[hash(seed, FAMILY_NAMES.length)];
	}

	public static String nameFor(SettlementSavedData data, Settlement settlement, UUID villagerId) {
		UUID parentVillagerId = firstParentVillagerId(data, settlement, villagerId);
		String family = parentVillagerId == null ? familyName(villagerId) : familyName(parentVillagerId);
		return family + givenName(villagerId);
	}

	static void assignOrMigrate(SettlementSavedData data, Settlement settlement, Villager villager) {
		Component currentName = villager.getCustomName();
		if (currentName != null && !isLegacyGeneratedName(data, settlement, villager.getUUID(), currentName.getString())) {
			return;
		}
		villager.setCustomName(Component.literal(nameFor(data, settlement, villager.getUUID())));
	}

	static boolean isLegacyGeneratedName(SettlementSavedData data, Settlement settlement, UUID villagerId, String name) {
		return legacyNameFor(data, settlement, villagerId).equals(name);
	}

	static boolean isLegacyGeneratedName(UUID villagerId, String name) {
		return legacyFullName(villagerId).equals(name);
	}

	static String legacyFullName(UUID villagerId) {
		return legacyGivenName(villagerId) + " " + legacyFamilyName(villagerId);
	}

	static String legacyNameFor(SettlementSavedData data, Settlement settlement, UUID villagerId) {
		UUID parentVillagerId = firstParentVillagerId(data, settlement, villagerId);
		String family = parentVillagerId == null ? legacyFamilyName(villagerId) : legacyFamilyName(parentVillagerId);
		return legacyGivenName(villagerId) + " " + family;
	}

	private static String legacyGivenName(UUID villagerId) {
		long seed = villagerId.getMostSignificantBits() ^ Long.rotateLeft(villagerId.getLeastSignificantBits(), 17);
		return LEGACY_GIVEN_NAMES[legacyHash(seed, LEGACY_GIVEN_NAMES.length)];
	}

	private static String legacyFamilyName(UUID villagerId) {
		long seed = Long.rotateLeft(villagerId.getMostSignificantBits(), 13) ^ villagerId.getLeastSignificantBits();
		return LEGACY_FAMILY_NAMES[legacyHash(seed, LEGACY_FAMILY_NAMES.length)];
	}

	private static UUID firstParentVillagerId(SettlementSavedData data, Settlement settlement, UUID villagerId) {
		SettlementSimulationState state = data.simulationOf(settlement).orElse(null);
		if (state == null) {
			return null;
		}
		String personId = state.residentId(villagerId).orElse(null);
		if (personId == null) {
			return null;
		}
		Person person = state.world().person(personId);
		if (person.parents().isEmpty()) {
			return null;
		}
		String parentPersonId = person.parents().getFirst();
		return state.residentMappings().entrySet().stream()
			.filter(entry -> entry.getValue().equals(parentPersonId))
			.map(Map.Entry::getKey)
			.findFirst()
			.orElse(null);
	}

	private static int hash(long seed, int bound) {
		long mixed = seed + 0x9E3779B97F4A7C15L;
		mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
		mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
		mixed ^= mixed >>> 31;
		return (int) Long.remainderUnsigned(mixed, bound);
	}

	private static int legacyHash(long seed, int bound) {
		int mixed = (int) (seed ^ (seed >>> 32));
		mixed ^= mixed >>> 16;
		return Math.floorMod(mixed, bound);
	}
}
