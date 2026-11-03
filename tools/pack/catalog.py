"""整合包构建的公共常量：目标版本、模组清单、分类。"""

GAME = "1.21.1"
LOADER = "fabric"
FABRIC_LOADER = "0.20.1"

# 客户端整合包的模组（按 Modrinth slug）
SEEDS = [
    # 性能优化
    "sodium", "lithium", "iris", "modernfix", "ferrite-core", "immediatelyfast",
    "entityculling", "moreculling", "ebe", "continuity", "krypton",
    "c2me-fabric", "noisium", "badoptimizations", "debugify", "dynamic-fps",
    "fastquit", "modelfix", "optigui", "threadtweak", "particle-core",
    "clumps", "chunky", "spark",
    # 界面与提示
    "modmenu", "zoomify", "appleskin", "jade", "xaeros-minimap", "xaeros-world-map",
    "chat-heads", "betterf3", "controlling", "shulkerboxtooltip",
    "inventory-profiles-next", "libipn", "morechathistory", "detail-armor-bar",
    # 实用功能
    "rei", "i18nupdatemod", "lambdynamiclights", "not-enough-animations", "3dskinlayers",
    "sodium-extra",
]

YANGLAO_CONTENT = [
    "advanced-netherite", "aether-villages", "amendments", "another-furniture",
    "basicweapons", "better-archeology", "betternether",
    "charm-of-undying", "ct-overhaul-village", "comforts", "deeperdarker",
    "double-doors", "easy-anvils", "easy-magic", "elytra-slot", "ends-delight",
    "farmers-delight-refabricated", "frights-delight", "geophilic", "handcrafted",
    "hearths", "immersive-aircraft", "immersive-armors", "mes-moogs-end-structures",
    "mss-moogs-soaring-structures", "naturalist", "natures-compass", "oceans-delight",
    "philips-ruins", "polymorph", "regions-unexplored", "snowy-spirit", "spelunkery",
    "storage-delight", "supplementaries", "tectonic", "terralith", "aether",
    "tidal-towns", "towns-and-towers", "ubes-delight", "visual-workbench", "waystones",
    "yungs-api", "yungs-better-desert-temples", "yungs-better-dungeons",
    "yungs-better-end-island", "yungs-better-jungle-temples", "yungs-better-mineshafts",
    "yungs-better-nether-fortresses", "yungs-better-ocean-monuments",
    "yungs-better-strongholds", "yungs-better-witch-huts", "yungs-bridges",
    "yungs-cave-biomes", "yungs-extras",
]

YANGLAO_DEPENDENCIES = [
    "balm", "bclib", "collective", "cristel-lib", "forge-config-api-port", "geckolib",
    "lithostitched", "moogs-structure-lib", "moonlight", "owo-lib",
    "puzzles-lib", "resourceful-config", "resourceful-lib", "terrablender", "trinkets",
    "worldweaver",
]

SEEDS += YANGLAO_CONTENT + YANGLAO_DEPENDENCIES

# 只在服务端用，不随客户端整合包下发
EXCLUDE_CLIENT = {"chunky", "spark", "c2me-fabric", "noisium"}

# 服务端额外建议安装的模组（含客户端包里也有的）
SERVER_ONLY = ["lithium", "ferrite-core", "modernfix", "krypton", "clumps",
               "c2me-fabric", "noisium", "chunky", "spark"]

SERVER_PURPOSE = {
    "lithium": "实体/流体/AI 优化",
    "ferrite-core": "内存占用",
    "modernfix": "启动与内存",
    "krypton": "网络栈",
    "clumps": "经验球合并",
    "c2me-fabric": "区块并行加载（alpha）",
    "noisium": "地形生成",
    "chunky": "预生成区块",
    "spark": "性能采样",
}

CATEGORIES = {
    "性能优化": ["sodium", "lithium", "modernfix", "ferrite-core", "immediatelyfast",
             "entityculling", "moreculling", "ebe", "continuity", "krypton",
             "badoptimizations", "debugify", "dynamic-fps", "fastquit", "modelfix",
             "optigui", "threadtweak", "particle-core", "clumps", "sodium-extra", "iris"],
    "界面与提示": ["modmenu", "appleskin", "jade", "xaeros-minimap", "xaeros-world-map",
              "chat-heads", "betterf3", "controlling", "shulkerboxtooltip",
              "detail-armor-bar", "morechathistory", "zoomify", "inventory-profiles-next"],
    "实用功能": ["rei", "i18nupdatemod", "lambdynamiclights", "not-enough-animations",
             "3dskinlayers"],
    "养老内容": YANGLAO_CONTENT,
    "依赖库": ["fabric-api", "cloth-config", "yacl", "architectury-api", "libipn",
            "searchables", "placeholder-api", "fabric-language-kotlin", "fzzy-config"]
            + YANGLAO_DEPENDENCIES,
}
