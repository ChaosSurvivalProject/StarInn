package com.starinn;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.java.JavaPlugin;

// ===== 内置更新模块（mc-autoupdate） =====
import com.starinn.update.UpdateCommand;
import com.starinn.update.UpdateConfig;
import com.starinn.update.UpdateManager;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 星穹旅驿 · 新手引导插件（单插件融合版）。
 *
 * <p>唯一主类：配置读取、SQLite 持久化、节点图、会话状态机、Java/基岩双端渲染、
 * 资源包自动生成与内置 HTTP 下发，全部在本类中以嵌套类/record 实现。</p>
 *
 * <p>红线约束：不联网（HTTP 服务只监听本机端口对外发放本地文件，属服务端被动服务，
 * 不是对外请求）、零第三方插件依赖、22 条台词全部来自本地 yml（阵营落地选择已移交独立阵营插件）、
 * 资源包端口绝不写死（一律从 config.yml 读取）、
 * 彩色立绘固化成真实 PNG 打进 jar（运行时不再用 BufferedImage 现画）、
 * 任何模块失败只降级该功能，绝不影响服务器整体、绝不导致插件启动失败。</p>
 */
public final class StarInn extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    // ============================================================
    // 常量区
    // ============================================================

    /** 日志统一前缀 */
    private static final String LOG_PREFIX = "[星穹旅驿] ";

    /**
     * 主引导流程的节点顺序（第五部分节点图）。
     * 台词内容本身来自 dialogues/*.yml，这里只定义“先播哪条后播哪条”的骨架。
     */
    private static final List<String> MAIN_FLOW = List.of(
            "S0_1", "S0_2", "S0_3", "S0_4", "S0_5", "S0_6", "S0_7",
            "S1",
            "S2_1", "S2_2", "S2_3", "S2_4",
            "S3",
            "S4_1", "S4_2", "S4_3", "S4_4",
            "S5_1", "S5_2", "S5_3", "S5_4");

    // 主线以 S5_4 收尾（无选项）：原 S6_1/S6_2（晨·炽壤落地）与 S7_1/S7_2（黯·渊域落地）
    // 四条节点已删除——阵营选择移交独立阵营插件（玩家进服 10 分钟后由该插件弹窗选择），
    // 引导不再落地、不再判阵营；S5_4 播完点继续即写 COMPLETED。

    /** 结束哨兵值 */
    private static final String NODE_END = "END";
    /** 老玩家问候节点 */
    private static final String GREETING_ID = "GREETING";

    /** 全部 22 条台词的校验清单（缺一条启动即报错） */
    private static final List<String> REQUIRED_IDS = List.of(
            "S0_1", "S0_2", "S0_3", "S0_4", "S0_5", "S0_6", "S0_7",   // 芜 开场 7
            "S1", "S3", "S5_1", "S5_2", "S5_3", "S5_4", "GREETING",   // 芜 其余 7（合计 14）
            "S2_1", "S2_2", "S2_3", "S2_4",                           // 晨 4
            "S4_1", "S4_2", "S4_3", "S4_4");                          // 黯 4

    /** 无操作超时后芜的追问（UI 提示语，不属于 22 条剧情台词） */
    private static final String NUDGE_TEXT = "不说话？那我就当你不打算选了。";

    /** Java 端操作提示（逐字播放中 / 播完 / 选项） */
    private static final String JAVA_TYPE_HINT = "[左键地面继续] [Shift 跳过]";
    private static final String JAVA_DONE_HINT = "[空格继续]";
    /** 基岩端等待继续提示 */
    private static final String BEDROCK_WAIT_HINT = "[点击屏幕继续] [输入 skip 跳过]";

    /** 资源包 zip 文件名（与 config.yml 的 serve-path 末段一致） */
    private static final String PACK_ZIP_NAME = "StarInnResourcePack.zip";

    /**
     * 打进 jar 的固化资源包文件清单（src/main/resources/resourcepack/ 下的相对路径）。
     * 彩色立绘已固化为真实 PNG，运行时不再用 BufferedImage 现画；
     * 全部贴图宽高均为 2 的幂、标准 PNG、总大小远小于 5MB（基岩 Geyser 转码硬性要求）。
     * 资源包内不含字体目录 / shader / optifine 目录。
     */
    private static final List<String> RESOURCEPACK_FILES = List.of(
            "pack.mcmeta",
            "pack.png",
            "assets/starinn/textures/gui/dialogue_bg.png",
            "assets/starinn/textures/gui/wu.png",
            "assets/starinn/textures/gui/chen.png",
            "assets/starinn/textures/gui/an.png",
            "assets/starinn/textures/gui/bg_inn_dusk.png",
            "assets/starinn/textures/gui/bg_chirang_day.png",
            "assets/starinn/textures/gui/bg_yuanyu_night.png");

    /** 服主正式立绘覆盖表：plugins/StarInn/textures/<文件名> -> 资源包内路径（同名 PNG 优先） */
    private static final Map<String, String> TEXTURE_OVERRIDES = Map.ofEntries(
            Map.entry("pack.png", "pack.png"),
            Map.entry("dialogue_bg.png", "assets/starinn/textures/gui/dialogue_bg.png"),
            Map.entry("wu.png", "assets/starinn/textures/gui/wu.png"),
            Map.entry("chen.png", "assets/starinn/textures/gui/chen.png"),
            Map.entry("an.png", "assets/starinn/textures/gui/an.png"),
            Map.entry("bg_inn_dusk.png", "assets/starinn/textures/gui/bg_inn_dusk.png"),
            Map.entry("bg_chirang_day.png", "assets/starinn/textures/gui/bg_chirang_day.png"),
            Map.entry("bg_yuanyu_night.png", "assets/starinn/textures/gui/bg_yuanyu_night.png"));

    /** 资源包体积上限（基岩端转码友好线，5MB；这是字节数不是端口） */
    private static final long MAX_PACK_BYTES = 5L * 1024L * 1024L;

    /** 这些方块的点击不推进引导（避免误开容器） */
    private static final EnumSet<Material> INTERACTIVE_BLOCKS = EnumSet.of(
            Material.CHEST, Material.TRAPPED_CHEST, Material.ENDER_CHEST, Material.BARREL,
            Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER, Material.DROPPER,
            Material.DISPENSER, Material.JUKEBOX, Material.NOTE_BLOCK, Material.BEACON,
            Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL,
            Material.GRINDSTONE, Material.SMITHING_TABLE, Material.STONECUTTER,
            Material.LECTERN, Material.CAULDRON);

    /** 匹配 config.yml 中 sha256 所在行，用于保留注释的原地回填 */
    private static final Pattern SHA_LINE_PATTERN =
            Pattern.compile("^(\\s*sha256\\s*:\\s*).*$");

    // ============================================================
    // 字段
    // ============================================================

    private Config config;
    private Script script;
    private Database database;
    private PackBuilder packBuilder;
    private PackServer packServer;
    private SelfCheck selfCheck;

    /** 进行中的引导会话（内存维护，玩家退出/完成时写库并移除） */
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    /** 空格(跳跃)推进检测的全局轻量计时器（仅在有会话时做事，不掉 TPS） */
    private BukkitTask jumpTask;

    /** classpath 中是否存在 Geyser（可选组件，仅探测一次） */
    private boolean geyserOnClasspath;

    // ============================================================
    // 嵌套 record：台词 / 选项 / 节点
    // ============================================================

    /** 单个选项（label + 目标节点） */
    record Choice(String label, String next) {}

    /** 单条台词：id / npc / expression / background / text / choices */
    record Dialogue(String id, String npc, String expression,
                    String background, String text, List<Choice> choices) {}

    /** 节点：台词 + 下一节点 或 选项列表 */
    record Node(Dialogue dialogue, String next, List<Choice> choices) {
        boolean isChoiceNode() {
            return choices != null && !choices.isEmpty();
        }
    }

    /** 会话阶段 */
    private enum Phase { NONE, TYPING, WAITING, AWAIT_CHOICE }

    // ============================================================
    // 生命周期
    // ============================================================

    @Override
    public void onEnable() {
        // 内置更新：自检一次 + 每周四 12:00 定时；异常只关闭更新功能。
        try {
            UpdateConfig uc = new UpdateConfig(
                "https://raw.githubusercontent.com/ChaosSurvivalProject/StarInn/main/updates/latest.json",
                getDescription().getVersion(), "1.21", "stable", true, true);
            UpdateManager um = new UpdateManager(this, uc);
            um.checkAndSchedule(() -> um.check(false));
            if (getCommand("sinnupdate") != null) {
                getCommand("sinnupdate").setExecutor(new UpdateCommand(um));
                getCommand("sinnupdate").setTabCompleter(new UpdateCommand(um));
            }
        } catch (Throwable t) {
            getLogger().warning("[StarInn] 更新模块初始化失败（不影响主功能）：" + t.getMessage());
        }

        // 释放默认 config.yml（保留全部中文注释）
        saveDefaultConfig();
        selfCheck = new SelfCheck();
        // 自检项1：配置解析。格式错误 -> 内置默认值兜底，日志打印 SnakeYAML 的具体行列号
        config = loadConfigSafe();

        // 自检项6：Geyser 探测（未装/接口不符 -> 全按 Java 端，照常运作）
        geyserOnClasspath = detectGeyser();
        info("启动中：Purpur 1.21.11 / Java 21，单插件、零第三方插件依赖、零对外网络请求。");

        getServer().getPluginManager().registerEvents(this, this);
        PluginCommandBridge.bind(this);

        if (!config.enabled) {
            warn("enabled=false，引导功能整体关闭（命令仍可用）。");
            selfCheck.printNow();
            return;
        }

        // 首次运行释放三个对话文件（不再向 textures/ 释放默认贴图：该目录专用于服主正式立绘覆盖）
        saveDefaultDialogues();

        // 自检项2：脚本加载。缺台词/文件坏 -> 跳过缺失节点、引导继续，绝不抛异常拖垮插件
        script = new Script();
        script.load();
        if (script.dialogues.isEmpty()) {
            severe("无可用台词，引导暂不触发（服务器不受影响）；修好 dialogues/*.yml 后 /starinn reload。");
        } else {
            info("脚本加载完成：台词 " + script.dialogues.size() + " 条，节点 " + script.nodes.size() + " 个。");
        }

        // 自检项3/4：数据库与资源包都是异步项，先登记再结算
        selfCheck.expectAsync();
        selfCheck.expectAsync();

        // 数据库：100% 异步初始化；失败 -> 降级内存进度（不持久化），引导继续
        database = new Database(new File(getDataFolder(), config.databaseFile));
        database.initAsync(() -> selfCheck.settleAsync("数据库持久化",
                !database.isDegraded(),
                "SQLite 不可用，已降级为内存进度（引导不受影响，重启服务器后进度需重判）"));

        packBuilder = new PackBuilder();
        packServer = new PackServer();
        // 资源包：异步线程完成「释放固化 PNG -> 服主贴图覆盖 -> 打 zip -> SHA-256 回填」
        if (config.packAutoGenerate) {
            runAsync(() -> {
                final boolean ok = packBuilder.buildAndPublish(false);
                Bukkit.getScheduler().runTask(this, () -> selfCheck.settleAsync(
                        "资源包生成", ok, "zip 打包失败：已关闭资源包，引导照常（仅无立绘）"));
            }, t -> Bukkit.getScheduler().runTask(this, () -> selfCheck.settleAsync(
                    "资源包生成", false, "打包异常：" + t)));
        } else if (packFile().isFile()) {
            // 关闭自动生成但已有 zip：装载既有校验值并照常下发
            try {
                config.packSha256 = sha256Hex(packFile());
                selfCheck.settleAsync("资源包生成", true, null);
            } catch (IOException e) {
                selfCheck.settleAsync("资源包生成", false, "读取既有资源包失败：" + e.getMessage());
            }
        } else {
            selfCheck.settleAsync("资源包生成", false,
                    "auto-generate=false 且无现成 zip，资源包暂不下发");
        }

        // 自检项5：内置 HTTP 服务（host/端口/路径全部来自 config.yml；端口占用只关本功能）
        if (config.packBuiltinPort < 1 || config.packBuiltinPort > 65535) {
            severe("config.yml 的 resource-pack.builtin-port 未配置或无效（" + config.packBuiltinPort
                    + "），资源包下发已关闭；服主端口申请下来后改该项并 /starinn reload。");
            selfCheck.recordSync("HTTP 端口下发", false,
                    "builtin-port 未配置或无效，资源包下发关闭（引导继续）");
        } else {
            boolean up = packServer.start(config.packBuiltinHost,
                    config.packBuiltinPort, config.packServePath);
            selfCheck.recordSync("HTTP 端口下发", up,
                    "端口被占用或绑定失败，资源包下发关闭（引导继续）");
        }

        // 「[空格继续]」真正生效：检测跳跃统计量推进会话；也是自检汇总的兜底打印
        jumpTask = Bukkit.getScheduler().runTaskTimer(this, this::tickJumpAdvance, 10L, 5L);
        Bukkit.getScheduler().runTaskLater(this, selfCheck::printNow, 600L);

        info("资源包下发方式：" + describePackSource());
    }

    /** 空格（跳跃）推进检测：仅等待推进状态生效，点击推进依旧可用 */
    private void tickJumpAdvance() {
        if (sessions.isEmpty()) {
            return;
        }
        for (Session s : new ArrayList<>(sessions.values())) {
            s.checkJump();
        }
    }

    @Override
    public void onDisable() {
        if (jumpTask != null) {
            jumpTask.cancel();
            jumpTask = null;
        }
        // 关闭所有会话并落库
        for (Session s : new ArrayList<>(sessions.values())) {
            s.closeNow();
        }
        sessions.clear();
        if (packServer != null) {
            packServer.stop(); // 必须关闭 HttpServer 释放端口
        }
        if (database != null) {
            database.close();
        }
        info("已停止：会话清理、内置 HTTP 服务关闭（端口已释放）、数据库连接释放。");
    }

    /** 命令桥接：把执行器/Tab 补全绑回主类（不另建类，但用一个小工具方法封装判空） */
    private static final class PluginCommandBridge {
        static void bind(StarInn plugin) {
            if (plugin.getCommand("starinn") != null) {
                plugin.getCommand("starinn").setExecutor(plugin);
                plugin.getCommand("starinn").setTabCompleter(plugin);
            }
        }
    }

    // ============================================================
    // SelfCheck 嵌套类：启动自检登记 + 自动降级汇总
    // ============================================================

    /**
     * 逐项自检并记录。任何一项失败只关闭该功能，绝不影响服务器其他部分、
     * 绝不导致插件启动失败。全部项（含异步项）结算后打印汇总：
     * [星穹旅驿] 自检完成：正常 X 项 / 降级 Y 项
     */
    private final class SelfCheck {
        private final Map<String, String> items = new LinkedHashMap<>();
        private int pendingAsync;
        private boolean printed;

        synchronized void ok(String name) {
            items.put(name, null);
        }

        synchronized void degraded(String name, String reason) {
            items.put(name, reason);
        }

        synchronized void recordSync(String name, boolean good, String degradeReason) {
            items.put(name, good ? null : degradeReason);
        }

        synchronized void expectAsync() {
            pendingAsync++;
        }

        synchronized void settleAsync(String name, boolean good, String reason) {
            items.put(name, good ? null : reason);
            pendingAsync = Math.max(0, pendingAsync - 1);
            if (pendingAsync == 0) {
                print();
            }
        }

        /** 兜底打印（异步项迟迟未结算时也会输出汇总） */
        synchronized void printNow() {
            print();
        }

        private void print() {
            if (printed) {
                return;
            }
            printed = true;
            int degraded = 0;
            for (String v : items.values()) {
                if (v != null) {
                    degraded++;
                }
            }
            info("自检完成：" + (items.size() - degraded) + " 项正常 / " + degraded + " 项降级");
            for (Map.Entry<String, String> e : items.entrySet()) {
                if (e.getValue() != null) {
                    warn("降级项：" + e.getKey() + " -> " + e.getValue()
                            + "（仅关闭该功能，引导与服务器其余部分不受影响）");
                }
            }
        }
    }

    // ============================================================
    // Config 嵌套类：读取 config.yml 全部字段
    // ============================================================

    /** config.yml 的强类型快照；任何端口/地址/节奏参数只能从这里来，代码里不写死端口。 */
    private static final class Config {
        boolean enabled;
        // 资源包下发（四项严格生效：builtin-port / builtin-host / serve-path / public-host）
        int packBuiltinPort;
        String packBuiltinHost;
        String packServePath;
        String packPublicHost;
        boolean packAutoSend;
        boolean packAutoGenerate;
        /** SHA-256 校验值（插件生成后回填，也回写到 config.yml 文本） */
        String packSha256;
        // 引导节奏
        int delayOnJoin;
        int charInterval;
        int idleTimeout;
        int idleTimeout2;
        int greetingDuration;
        // 数据库 / 命令 / 日志
        String databaseFile;
        String permReload;
        String permReset;
        boolean debug;

        /**
         * 全部从 FileConfiguration 读取；配置格式错误时传入空配置，
         * 即所有项落到“内置默认值”（端口默认无效值 0 -> 下发关闭，绝不用写死端口兜底）。
         */
        Config(FileConfiguration c) {
            enabled = c.getBoolean("enabled", true);

            // 端口没有代码默认值：缺失即为 0（无效），提示服主去配置，而不是写死兜底
            packBuiltinPort = c.getInt("resource-pack.builtin-port", 0);
            packBuiltinHost = c.getString("resource-pack.builtin-host", "0.0.0.0").trim();
            String sp = c.getString("resource-pack.serve-path", "/StarInnResourcePack.zip").trim();
            packServePath = sp.startsWith("/") ? sp : "/" + sp; // 路径兜底补斜杠，保证 URL 拼装正确
            packPublicHost = c.getString("resource-pack.public-host", "").trim();
            packAutoSend = c.getBoolean("resource-pack.auto-send", true);
            packAutoGenerate = c.getBoolean("resource-pack.auto-generate", true);
            packSha256 = c.getString("resource-pack.sha256", "").trim();

            delayOnJoin = Math.max(1, c.getInt("guide.delay-on-join", 40));
            charInterval = Math.max(1, c.getInt("guide.char-interval", 2));
            idleTimeout = Math.max(5, c.getInt("guide.idle-timeout", 300));
            idleTimeout2 = Math.max(5, c.getInt("guide.idle-timeout-2", 120));
            greetingDuration = Math.max(10, c.getInt("guide.greeting-duration", 40));

            databaseFile = c.getString("database.file", "guides.db");
            permReload = c.getString("command.reload-permission", "starinn.reload");
            permReset = c.getString("command.reset-permission", "starinn.reset");
            debug = c.getBoolean("debug", false);
        }

        boolean portValid() {
            return packBuiltinPort >= 1 && packBuiltinPort <= 65535;
        }
    }

    /**
     * 自检项1：手动 UTF-8 解析 config.yml。
     * 格式错误 -> 日志打印 SnakeYAML 的具体行列号，并用内置默认值兜底继续启动（绝不抛异常）。
     */
    private Config loadConfigSafe() {
        File f = new File(getDataFolder(), "config.yml");
        if (!f.isFile()) {
            saveDefaultConfig();
        }
        YamlConfiguration y = new YamlConfiguration();
        try (Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            y.load(r);
            selfCheck.ok("配置文件（config.yml）");
        } catch (Exception e) {
            // 原样带上 SnakeYAML 的行列信息（line X, column Y），方便服主定位
            severe("配置文件格式错误：plugins/StarInn/config.yml -> " + e.getMessage()
                    + "；已用内置默认值兜底（端口项默认无效 => 资源包下发临时关闭），"
                    + "请修正格式后 /starinn reload");
            y = new YamlConfiguration();
            selfCheck.degraded("配置文件（config.yml）",
                    "格式错误，全部配置项已用内置默认值兜底");
        }
        return new Config(y);
    }

    // ============================================================
    // Script 嵌套类：加载三个 yml，构建完整节点图
    // ============================================================

    private final class Script {
        final Map<String, Dialogue> dialogues = new LinkedHashMap<>();
        final Map<String, Node> nodes = new LinkedHashMap<>();
        final Map<String, String> npcNames = new HashMap<>();

        /** 加载并自检（自检项2）。缺台词/文件损坏只记录并跳过，绝不抛异常拖垮插件启动。 */
        void load() {
            dialogues.clear();
            nodes.clear();
            npcNames.clear();
            for (String fn : List.of("wu.yml", "chen.yml", "an.yml")) {
                File f = new File(getDataFolder(), "dialogues/" + fn);
                if (!f.exists()) {
                    saveResource("dialogues/" + fn, false);
                }
                YamlConfiguration y = loadYamlUtf8(f, "dialogues/" + fn);
                if (y == null) {
                    continue; // 格式错误：loadYamlUtf8 已打印行列号并登记降级
                }
                ConfigurationSection npcSec = y.getConfigurationSection("npc");
                if (npcSec != null) {
                    npcNames.put(npcSec.getString("id", "?"), npcSec.getString("name", "无名者"));
                } else {
                    warn("对话文件 " + fn + " 缺少 npc 段，NPC 名称用内置兜底。");
                }
                for (Map<?, ?> raw : y.getMapList("dialogues")) {
                    Dialogue d;
                    try {
                        d = new Dialogue(
                                str(raw.get("id")), str(raw.get("npc")),
                                str(raw.get("expression")), str(raw.get("background")),
                                str(raw.get("text")), parseChoices(raw.get("choices")));
                    } catch (Exception bad) {
                        warn(fn + " 存在一条解析失败的台词（缺 id/text），该条已跳过：" + bad.getMessage());
                        continue;
                    }
                    if (d.id().isEmpty() || d.text().isEmpty()) {
                        warn(fn + " 存在缺少 id 或 text 的台词，该条已跳过。");
                        continue;
                    }
                    dialogues.put(d.id(), d);
                }
            }
            buildGraph();
            validate();
        }

        private List<Choice> parseChoices(Object rawObj) {
            List<Choice> out = new ArrayList<>();
            if (rawObj instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        out.add(new Choice(str(m.get("label")), str(m.get("next"))));
                    }
                }
            }
            return List.copyOf(out);
        }

        /** 构图：缺台词直接跳过，把前一条接到下一条“存在的”节点上，保证引导永不卡住。 */
        private void buildGraph() {
            // 主线：按 MAIN_FLOW 顺序把存在的节点串接；S5_4 已无选项，作为最后一条自然收尾
            linkChain(MAIN_FLOW);
            // 问候节点：独立单条
            Dialogue g = dialogues.get(GREETING_ID);
            if (g != null) {
                nodes.put(GREETING_ID, new Node(g, null, List.of()));
            }
        }

        /** 把存在的 id 串成链：选项节点靠选项跳转不设 next；缺失的直接跳过 */
        private void linkChain(List<String> ids) {
            List<String> present = new ArrayList<>();
            for (String id : ids) {
                if (dialogues.containsKey(id)) {
                    present.add(id);
                }
            }
            for (int i = 0; i < present.size(); i++) {
                Dialogue d = dialogues.get(present.get(i));
                if (!d.choices().isEmpty()) {
                    nodes.put(d.id(), new Node(d, null, d.choices()));
                } else {
                    String next = (i + 1 < present.size()) ? present.get(i + 1) : null;
                    nodes.put(d.id(), new Node(d, next, List.of()));
                }
            }
        }

        /** 台词完整性硬校验（以 REQUIRED_IDS 为准）：缺条 -> 记录清单并降级，运行时跳过缺失节点，引导继续 */
        private void validate() {
            List<String> missing = new ArrayList<>();
            for (String id : REQUIRED_IDS) {
                if (!dialogues.containsKey(id)) {
                    missing.add(id);
                }
            }
            int expect = REQUIRED_IDS.size();
            String label = "对话台词（" + expect + " 条齐全）";
            if (missing.isEmpty() && dialogues.size() == expect) {
                selfCheck.ok(label);
                return;
            }
            if (!missing.isEmpty()) {
                severe("对话台词不完整（" + dialogues.size() + "/" + expect + "），缺：" + missing
                        + "；已跳过缺失节点，其余引导照常。");
                selfCheck.degraded(label, "缺失 " + missing.size() + " 条：" + missing);
            } else {
                warn("台词总数为 " + dialogues.size() + "，与标准 " + expect + " 条不一致，请检查对话文件是否被改动。");
                selfCheck.degraded(label, "条数为 " + dialogues.size() + "，非标准 " + expect + " 条");
            }
        }

        /** 主线第一个可播节点 id（缺台词时取幸存的第一条）；一个都不存在则返回 null */
        String firstPlayable() {
            for (String id : MAIN_FLOW) {
                if (nodes.containsKey(id)) {
                    return id;
                }
            }
            return null;
        }

        /** NPC -> BossBar 颜色：芜=白、晨=金、黯=紫（switch 模式匹配；金色即 BarColor.YELLOW） */
        BarColor npcColor(String npcId) {
            return switch (npcId == null ? "" : npcId) {
                case "chen" -> BarColor.YELLOW;
                case "an" -> BarColor.PURPLE;
                default -> BarColor.WHITE;
            };
        }

        String npcName(String npcId) {
            String n = npcNames.get(npcId);
            return n == null ? "芜" : n;
        }
    }

    // ============================================================
    // Database 嵌套类：SQLite 进度存取（全异步）
    // ============================================================

    /** 一行进度：state=NEW/COMPLETED/SKIPPED，node=当前节点，faction 恒为空（阵营选择已移交独立插件，列保留兼容旧库） */
    record Progress(String state, String node, String faction) {}

    /**
     * SQLite 持久化。所有操作 100% 异步（runTaskAsynchronously），返回 CompletableFuture；
     * 锁库重试 3 次仍失败则整体降级为内存进度并告警（绝不阻塞主线程）。
     */
    private final class Database {
        private final File dbFile;
        private Connection conn;
        private volatile boolean degraded;
        /** 异步初始化是否已结束（成功或明确失败），防止玩家进服撞上建库窗口 */
        private volatile boolean initFinished;
        private final Map<UUID, Progress> memory = new ConcurrentHashMap<>();

        Database(File dbFile) {
            this.dbFile = dbFile;
        }

        /**
         * 异步初始化；onSettled 回调必定执行（成功/失败都回调），用于自检项 3 结算。
         * 失败 -> degraded=true，降级为内存进度，日志告警，绝不抛出。
         */
        void initAsync(Runnable onSettled) {
            runAsync(() -> {
                try {
                    // 驱动由 Purpur 服务端自带（org.sqlite.JDBC），这里只做类探测，不引入第三方 jar
                    Class.forName("org.sqlite.JDBC");
                    conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
                    try (Statement st = conn.createStatement()) {
                        st.executeUpdate("""
                                CREATE TABLE IF NOT EXISTS guide_progress (
                                  uuid TEXT PRIMARY KEY,
                                  state TEXT NOT NULL,
                                  current_node TEXT NOT NULL,
                                  chosen_faction TEXT,
                                  updated_at INTEGER NOT NULL
                                )""");
                    }
                    info("SQLite 就绪：" + dbFile.getAbsolutePath());
                } catch (Throwable t) {
                    degraded = true;
                    warn("SQLite 不可用（" + t.getMessage()
                            + "），已降级为内存进度：重启服务器后进度会丢失，但引导功能不受影响。");
                } finally {
                    initFinished = true;
                }
            }, t -> degraded = true);
            if (onSettled != null) {
                Bukkit.getScheduler().runTaskAsynchronously(StarInn.this, () -> {
                    awaitInit();
                    Bukkit.getScheduler().runTask(StarInn.this, onSettled);
                });
            }
        }

        CompletableFuture<Optional<Progress>> load(UUID id) {
            if (degraded) {
                return CompletableFuture.completedFuture(Optional.ofNullable(memory.get(id)));
            }
            CompletableFuture<Optional<Progress>> f = new CompletableFuture<>();
            runAsync(() -> {
                Progress p = withRetry("读取进度", () -> {
                    synchronized (this) {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "SELECT state, current_node, chosen_faction FROM guide_progress WHERE uuid = ?")) {
                            ps.setString(1, id.toString());
                            try (ResultSet rs = ps.executeQuery()) {
                                if (rs.next()) {
                                    return new Progress(rs.getString(1), rs.getString(2),
                                            rs.getString(3) == null ? "" : rs.getString(3));
                                }
                                return null;
                            }
                        }
                    }
                });
                // 库异常时 withRetry 已降级，用内存兜底
                f.complete(Optional.ofNullable(p != null ? p : memory.get(id)));
            }, f::completeExceptionally);
            return f;
        }

        CompletableFuture<Void> save(UUID id, Progress p) {
            memory.put(id, p); // 内存兜底始终记录，供降级/锁库回退使用
            if (degraded) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> f = new CompletableFuture<>();
            runAsync(() -> withRetry("写入进度", () -> {
                synchronized (this) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO guide_progress(uuid,state,current_node,chosen_faction,updated_at) "
                                    + "VALUES(?,?,?,?,?) "
                                    + "ON CONFLICT(uuid) DO UPDATE SET state=excluded.state, "
                                    + "current_node=excluded.current_node, chosen_faction=excluded.chosen_faction, "
                                    + "updated_at=excluded.updated_at")) {
                        ps.setString(1, id.toString());
                        ps.setString(2, p.state());
                        ps.setString(3, p.node());
                        ps.setString(4, p.faction());
                        ps.setLong(5, System.currentTimeMillis());
                        ps.executeUpdate();
                    }
                    return null;
                }
            }), f::completeExceptionally);
            return f;
        }

        /** 等待异步建库结束（最多 5 秒），避免玩家进服太快撞上初始化窗口 */
        private void awaitInit() {
            long deadline = System.currentTimeMillis() + 5000L;
            while (!initFinished && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        /** 数据库锁定重试 3 次；仍失败 -> 只用内存进度 + 告警（第十一部分异常处理） */
        private Progress withRetry(String op, SqlWork<Progress> work) {
            SQLException last = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    awaitInit();
                    if (degraded || conn == null || conn.isClosed()) {
                        throw new SQLException("连接已断开");
                    }
                    return work.run();
                } catch (SQLException e) {
                    last = e;
                    String msg = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
                    boolean locked = msg.contains("locked") || msg.contains("busy");
                    if (!locked || attempt == 3) {
                        break;
                    }
                    try {
                        Thread.sleep(200L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    debugLog("数据库锁定，" + op + " 第 " + attempt + " 次重试…");
                }
            }
            if (!degraded) {
                degraded = true;
                warn("数据库" + op + "连续失败（" + (last == null ? "unknown" : last.getMessage())
                        + "），本次运行改用内存进度，重启后需重新判定新老玩家。");
            }
            return null;
        }

        private interface SqlWork<T> {
            T run() throws SQLException;
        }

        void close() {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    debugLog("关闭数据库连接异常：" + e.getMessage());
                }
                conn = null;
            }
        }

        boolean isDegraded() {
            return degraded;
        }
    }

    // ============================================================
    // Session 嵌套类：单个玩家的进行中会话
    // ============================================================

    /**
     * 一次引导会话：持有当前节点、台词游标与逐字/闲置计时器。
     * 所有状态推进只在主线程执行（异步事件先 hop 回主线程）。
     */
    private final class Session {
        final Player player;
        final UUID uuid;
        final boolean bedrock;
        final Renderer renderer;
        String nodeId = MAIN_FLOW.get(0);
        Node node;
        int cursor;
        Phase phase = Phase.NONE;
        boolean nudged;
        /** 阵营选择已移交独立阵营插件：本字段恒为空串，仅保留写库兼容旧表结构 */
        String faction = "";
        /** 进入等待推进状态时的跳跃统计量基线（用于“空格继续”检测） */
        private int jumpBaseline;
        private BukkitTask typeTask;
        private BukkitTask idleTask;
        private BukkitTask restoreTask;

        Session(Player player, boolean bedrock) {
            this.player = player;
            this.uuid = player.getUniqueId();
            this.bedrock = bedrock;
            this.renderer = bedrock ? new ActionBarRenderer(player) : new BossBarRenderer(player);
        }

        void begin(String startNode) {
            goTo(startNode);
        }

        /** 进入某节点：从头播放该节点台词，并持久化 NEW 进度 */
        private void goTo(String id) {
            Node n = script.nodes.get(id);
            if (n == null) {
                if (NODE_END.equals(id)) {
                    complete();
                } else {
                    warn("找不到节点 " + id + "，引导结束。");
                    complete();
                }
                return;
            }
            cancelTasks();
            this.nodeId = id;
            this.node = n;
            this.cursor = 0;
            this.nudged = false;
            this.phase = Phase.NONE;
            startLine();
            database.save(uuid, new Progress("NEW", id, faction));
        }

        /** 开始播本节点台词：Java 逐字，基岩整条 */
        private void startLine() {
            Dialogue d = node.dialogue();
            BarColor color = script.npcColor(d.npc());
            String name = script.npcName(d.npc());
            if (bedrock) {
                // 基岩端不用 BossBar：ActionBar 直接显示完整台词
                renderer.line(name, color, d.text(), 1.0);
                finishLine();
            } else {
                phase = Phase.TYPING;
                renderer.hint(JAVA_TYPE_HINT);
                int len = d.text().length();
                typeTask = Bukkit.getScheduler().runTaskTimer(StarInn.this, () -> {
                    cursor++;
                    renderer.line(name, color, d.text().substring(0, cursor),
                            (double) cursor / len);
                    if (cursor >= len) {
                        if (typeTask != null) {
                            typeTask.cancel();
                            typeTask = null;
                        }
                        finishLine();
                    }
                }, 1L, Math.max(1, config.charInterval));
            }
        }

        /** 台词播完：等待推进或等待选择 */
        private void finishLine() {
            jumpBaseline = player.getStatistic(Statistic.JUMP);
            if (node.isChoiceNode()) {
                phase = Phase.AWAIT_CHOICE;
                showChoiceHint();
            } else {
                phase = Phase.WAITING;
                renderer.hint(bedrock ? BEDROCK_WAIT_HINT : JAVA_DONE_HINT);
            }
            restartIdle();
        }

        /** 选项呈现：Java 用按键映射；基岩优先 Geyser SimpleForm，失败降级聊天序号 */
        private void showChoiceHint() {
            List<Choice> ch = node.choices();
            if (bedrock) {
                List<String> labels = new ArrayList<>();
                for (Choice c : ch) {
                    labels.add(c.label());
                }
                labels.add("跳过引导");
                final int choiceSize = ch.size();
                // 表单标题用说话 NPC 的名字（此处是芜给的选项）
                String formTitle = "「" + script.npcName(node.dialogue().npc()) + "」";
                boolean ok = tryGeyserForm(player, formTitle,
                        node.dialogue().text(), labels, idx -> {
                            if (idx < choiceSize) {
                                choose(idx);
                            } else {
                                skip();
                            }
                        });
                if (!ok) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < ch.size(); i++) {
                        sb.append("[输入 ").append(i + 1).append("：").append(ch.get(i).label()).append("] ");
                    }
                    sb.append("[输入 skip 跳过]");
                    renderer.hint(sb.toString());
                }
            } else {
                // Java：左键/右键/F 对应第一/二/三个选项
                String[] keys = {"左键", "右键", "F"};
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < ch.size() && i < 3; i++) {
                    sb.append("[").append(keys[i]).append("：").append(ch.get(i).label()).append("] ");
                }
                sb.append("[Shift 跳过]");
                renderer.hint(sb.toString());
            }
        }

        /** 点击推进（Java：左键=0/右键=1/F=2；基岩：任意点击只用于“继续”） */
        void onClick(int slot) {
            if (!player.isOnline()) {
                return;
            }
            touch();
            if (phase == Phase.TYPING) {
                // 未播完：立即补全整条台词
                if (typeTask != null) {
                    typeTask.cancel();
                    typeTask = null;
                }
                Dialogue d = node.dialogue();
                cursor = d.text().length();
                renderer.line(script.npcName(d.npc()), script.npcColor(d.npc()), d.text(), 1.0);
                finishLine();
                return;
            }
            if (phase == Phase.AWAIT_CHOICE) {
                if (bedrock) {
                    return; // 基岩选择走表单/聊天，避免误触
                }
                if (slot >= 0 && slot < node.choices().size()) {
                    choose(slot);
                }
                return;
            }
            if (phase == Phase.WAITING) {
                goNext();
            }
        }

        void choose(int idx) {
            if (phase != Phase.AWAIT_CHOICE) {
                return;
            }
            touch();
            Choice c = node.choices().get(idx);
            String target = c.next();
            // 阵营选择已移交独立阵营插件：本插件不再判阵营、不再写 CHIRANG/YUANYU，
            // chosen_faction 恒为空串（字段保留仅为兼容旧库表结构）。
            if (NODE_END.equals(target)) {
                complete();
            } else if (!script.nodes.containsKey(target)) {
                // 选项指向的节点缺失（台词被删/改坏）：就地体面收尾，绝不留人悬在选项界面
                warn("选项目标节点 " + target + " 不存在，引导就此收尾（不放空、不卡人）。");
                complete();
            } else {
                goTo(target);
            }
        }

        private void goNext() {
            String next = node.next();
            if (next == null || NODE_END.equals(next)) {
                complete();
            } else {
                goTo(next);
            }
        }

        /** 引导正常完成：写 COMPLETED */
        private void complete() {
            cancelTasks();
            database.save(uuid, new Progress("COMPLETED", NODE_END, faction));
            renderer.hint("[星穹旅驿] 引导完成。愿星光照你赶路。");
            sessions.remove(uuid, this);
            Bukkit.getScheduler().runTaskLater(StarInn.this, renderer::hide, 60L);
            debugLog(uuid + " 完成引导（阵营选择已移交独立插件，本插件不记录阵营）");
        }

        /** 跳过：写 SKIPPED（Java=Shift；基岩=表单按钮/聊天 skip） */
        void skip() {
            if (phase == Phase.NONE && node == null) {
                return;
            }
            cancelTasks();
            database.save(uuid, new Progress("SKIPPED", nodeId, faction));
            renderer.hint("[星穹旅驿] 已跳过引导。");
            sessions.remove(uuid, this);
            Bukkit.getScheduler().runTaskLater(StarInn.this, renderer::hide, 40L);
            debugLog(uuid + " 跳过引导，停在 " + nodeId);
        }

        /** 断线/卸载：只回收资源，不再改写进度（进度每节点都已落库） */
        void closeNow() {
            cancelTasks();
            renderer.hide();
        }

        /** 空格（跳跃）推进检测：仅 WAITING 状态生效；与点击推进并存，双保险不卡人 */
        void checkJump() {
            if (phase == Phase.WAITING && player.isOnline()
                    && player.getStatistic(Statistic.JUMP) > jumpBaseline) {
                onClick(0);
            }
        }

        // ---- 闲置计时：idle-timeout 追问一句，再 idle-timeout-2 自动关闭并记 SKIPPED ----
        private void restartIdle() {
            if (idleTask != null) {
                idleTask.cancel();
            }
            long ticks = 20L * (nudged ? config.idleTimeout2 : config.idleTimeout);
            idleTask = Bukkit.getScheduler().runTaskLater(StarInn.this, () -> {
                if (phase != Phase.WAITING && phase != Phase.AWAIT_CHOICE) {
                    return;
                }
                if (!nudged) {
                    nudged = true;
                    // NPC 追问一句（覆盖提示，稍后恢复原提示）
                    renderer.hint("[芜] " + NUDGE_TEXT);
                    if (restoreTask != null) {
                        restoreTask.cancel();
                    }
                    restoreTask = Bukkit.getScheduler().runTaskLater(StarInn.this,
                            () -> {
                                if (phase == Phase.WAITING || phase == Phase.AWAIT_CHOICE) {
                                    showChoiceHintOrDone();
                                }
                            }, 200L);
                    restartIdle();
                } else {
                    skip();
                }
            }, ticks);
        }

        private void showChoiceHintOrDone() {
            if (phase == Phase.AWAIT_CHOICE) {
                showChoiceHint();
            } else if (phase == Phase.WAITING) {
                renderer.hint(bedrock ? BEDROCK_WAIT_HINT : JAVA_DONE_HINT);
            }
        }

        private void touch() {
            nudged = false;
            restartIdle();
        }

        private void cancelTasks() {
            if (typeTask != null) {
                typeTask.cancel();
                typeTask = null;
            }
            if (idleTask != null) {
                idleTask.cancel();
                idleTask = null;
            }
            if (restoreTask != null) {
                restoreTask.cancel();
                restoreTask = null;
            }
        }
    }

    // ============================================================
    // Renderer 双端渲染：接口 + Java BossBar 实现 + 基岩 ActionBar 实现
    // ============================================================

    /** 双端渲染统一接口 */
    private interface Renderer {
        void line(String npcName, BarColor color, String text, double progress);

        void hint(String msg);

        void hide();
    }

    /** Java 端：BossBar 显示逐字台词 + ActionBar 显示操作提示 */
    private final class BossBarRenderer implements Renderer {
        private final Player player;
        private BossBar bar;
        private String lastLine = "";
        private String lastHint = "";

        BossBarRenderer(Player player) {
            this.player = player;
        }

        @Override
        public void line(String npcName, BarColor color, String text, double progress) {
            if (bar == null) {
                bar = Bukkit.createBossBar("「" + npcName + "」", color, BarStyle.SEGMENTED_10);
                bar.setProgress(1.0);
                bar.addPlayer(player);
            }
            bar.setColor(color);
            bar.setTitle("「" + npcName + "」 " + text);
            bar.setProgress(Math.min(1.0, Math.max(0.05, progress)));
            lastLine = text;
        }

        @Override
        public void hint(String msg) {
            lastHint = msg;
            player.sendActionBar(msg);
        }

        @Override
        public void hide() {
            if (bar != null) {
                bar.removePlayer(player);
                bar.setVisible(false);
                bar = null;
            }
            player.sendActionBar("");
            lastLine = "";
            lastHint = "";
        }
    }

    /**
     * 基岩端：绝不用 BossBar（Geyser 的 BossBar 不显示文字），台词与提示合并走 ActionBar。
     * 注意：中文全部按 UTF-8 字符串处理（Bukkit 字符串本身即 Unicode）。
     */
    private final class ActionBarRenderer implements Renderer {
        private final Player player;
        private String lastLine = "";
        private String lastHint = "";

        ActionBarRenderer(Player player) {
            this.player = player;
        }

        private void push() {
            String msg = lastLine.isEmpty() ? lastHint
                    : (lastHint.isEmpty() ? lastLine : lastLine + "  " + lastHint);
            if (msg.length() > 120) {
                // ActionBar 过长会被客户端裁切，基岩端优先保台词
                msg = msg.substring(0, 119) + "…";
            }
            player.sendActionBar(msg);
        }

        @Override
        public void line(String npcName, BarColor color, String text, double progress) {
            lastLine = "「" + npcName + "」 " + text;
            push();
        }

        @Override
        public void hint(String msg) {
            lastHint = msg;
            push();
        }

        @Override
        public void hide() {
            lastLine = "";
            lastHint = "";
            player.sendActionBar("");
        }
    }

    // ============================================================
    // PackBuilder 嵌套类：资源包自动生成 + SHA-256
    // ============================================================

    private final class PackBuilder {

        /**
         * 固化资源包流水线（只做四步，绝不运行时现画）：
         * 1) 从 jar 释放 resourcepack/（固化 PNG + pack.mcmeta）到临时目录；
         * 2) plugins/StarInn/textures/ 下有服主正式立绘同名 PNG 则覆盖；
         * 3) 打包为 plugins/StarInn/StarInnResourcePack.zip；
         * 4) 计算 SHA-256 并原位回填 config.yml 的 resource-pack.sha256。
         *
         * @param force true=强制重建（/starinn pack regen）；false 时 zip 存在且 hash
         *              与配置一致则跳过生成（后续启动零成本复用）
         * @return 是否成功；失败只关闭资源包功能，插件照常启用、引导照常进行
         */
        boolean buildAndPublish(boolean force) {
            File zip = packFile();
            try {
                if (!force && zip.isFile() && !config.packSha256.isEmpty()) {
                    String actual = sha256Hex(zip);
                    if (actual.equalsIgnoreCase(config.packSha256)) {
                        info("资源包与配置校验值一致，跳过生成（" + zip.length() + " 字节）："
                                + zip.getAbsolutePath());
                        return true;
                    }
                }
                build(zip);
                String hex = sha256Hex(zip);
                config.packSha256 = hex;
                writeShaToConfigText(hex); // 文本级原位回填，中文注释全保留
                info("资源包已生成：" + zip.getAbsolutePath() + "（" + zip.length() + " 字节），sha256=" + hex);
                if (zip.length() > MAX_PACK_BYTES) {
                    warn("资源包超过 5MB（" + zip.length() + " 字节），基岩端 Geyser 转码可能偏慢；"
                            + "请检查 plugins/StarInn/textures/ 下服主贴图的尺寸。");
                }
                return true;
            } catch (Exception e) {
                warn("资源包生成失败（插件继续启动、引导照常，仅无立绘）：" + e);
                return false;
            }
        }

        /** 释放 -> 覆盖 -> 打包 zip（先写 .tmp 再原子替换，避免半截 zip 被下发） */
        private void build(File zip) throws IOException {
            Path stage = new File(getDataFolder(), "resourcepack.staging").toPath();
            deleteTree(stage);
            try {
                // 1) 从 jar 释放固化资源包文件（彩色立绘打进 jar，运行时不再现画）
                for (String name : RESOURCEPACK_FILES) {
                    try (InputStream in = getResource("resourcepack/" + name)) {
                        if (in == null) {
                            throw new IOException("jar 内缺少固化资源文件：resourcepack/" + name);
                        }
                        Path out = stage.resolve(name);
                        Files.createDirectories(out.getParent());
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                // 2) 服主正式立绘同名覆盖（必须标准 PNG 且宽高 2 的幂，否则忽略保基岩兼容）
                File texDir = texturesDir();
                for (Map.Entry<String, String> e : TEXTURE_OVERRIDES.entrySet()) {
                    File custom = new File(texDir, e.getKey());
                    if (!custom.isFile()) {
                        continue;
                    }
                    if (!isPngPowerOfTwo(custom)) {
                        warn("服主贴图 " + e.getKey() + " 不是标准 PNG 或宽高不是 2 的幂"
                                + "（基岩端 Geyser 会转码失败），本次忽略，仍用 jar 内固化贴图。");
                        continue;
                    }
                    Files.copy(custom.toPath(), stage.resolve(e.getValue()),
                            StandardCopyOption.REPLACE_EXISTING);
                    info("已使用服主贴图覆盖：" + e.getKey());
                }
                // 3) 打包（条目顺序固定，与 jar 内清单一致）
                File tmp = new File(getDataFolder(), PACK_ZIP_NAME + ".tmp");
                try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmp.toPath(),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
                    zos.setLevel(9);
                    for (String name : RESOURCEPACK_FILES) {
                        zos.putNextEntry(new ZipEntry(name));
                        Files.copy(stage.resolve(name), zos);
                        zos.closeEntry();
                    }
                }
                Files.move(tmp.toPath(), zip.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally {
                deleteTree(stage); // 临时目录用完即清
            }
        }

        /**
         * 标准 PNG 且宽高均为 2 的幂（基岩端 Geyser 转码硬性要求）。
         * 直接读 PNG 头判定，不引入 AWT，headless 服务器零风险。
         */
        private static boolean isPngPowerOfTwo(File f) {
            byte[] h = new byte[24];
            try (InputStream in = Files.newInputStream(f.toPath())) {
                if (in.read(h) < 24) {
                    return false;
                }
            } catch (IOException e) {
                return false;
            }
            if (!(h[0] == (byte) 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G'
                    && h[4] == 0x0D && h[5] == 0x0A && h[6] == 0x1A && h[7] == 0x0A)) {
                return false; // PNG 签名不符
            }
            if (h[12] != 'I' || h[13] != 'H' || h[14] != 'D' || h[15] != 'R') {
                return false; // 第一块必须是 IHDR
            }
            int w = (h[16] & 0xFF) << 24 | (h[17] & 0xFF) << 16 | (h[18] & 0xFF) << 8 | (h[19] & 0xFF);
            int hh = (h[20] & 0xFF) << 24 | (h[21] & 0xFF) << 16 | (h[22] & 0xFF) << 8 | (h[23] & 0xFF);
            return isPowerOfTwo(w) && isPowerOfTwo(hh);
        }

        private static boolean isPowerOfTwo(int v) {
            return v > 0 && (v & (v - 1)) == 0;
        }

        /** 递归删除临时释放目录（尽力而为，删不掉也无害） */
        private void deleteTree(Path root) {
            if (!Files.exists(root)) {
                return;
            }
            try (var walk = Files.walk(root)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 单个文件删不掉不影响后续
                    }
                }
            } catch (IOException ignored) {
                // 同上
            }
        }
    }

    // ============================================================
    // PackServer 嵌套类：内置 HTTP 文件服务（JDK HttpServer，端口读配置）
    // ============================================================

    /**
     * 内置资源包服务：只“被动发放本地文件”，不向任何外部地址发起请求。
     * 监听地址/端口/路径全部来自 config.yml；发放的文件名固定 packFile()，
     * 重建 zip 后无需重启服务即可下发新包。onDisable 必须 stop() 释放端口。
     */
    private final class PackServer {
        private HttpServer server;
        private Executor executor;

        boolean start(String host, int port, String path) {
            if (server != null) {
                return true; // 已在运行
            }
            try {
                server = HttpServer.create(new InetSocketAddress(host, port), 0);
                executor = Executors.newFixedThreadPool(2, r -> {
                    Thread t = new Thread(r, "StarInn-PackServer");
                    t.setDaemon(true);
                    return t;
                });
                server.setExecutor(executor);
                server.createContext(path, this::serve);
                server.start();
                info("内置资源包服务已启动：监听 " + host + ":" + port + path
                        + "（防火墙/安全组记得放行该端口）");
                String url = packDownloadUrl();
                info("资源包下发地址：" + (url == null ? "（public-host/端口配置异常，暂不可用）" : url));
                return true;
            } catch (BindException be) {
                server = null;
                severe("端口 " + port + " 被占用，请在 config.yml 修改 resource-pack.builtin-port"
                        + " 后执行 /starinn reload（引导继续，仅资源包下发关闭）");
                return false;
            } catch (IOException e) {
                server = null;
                severe("内置资源包服务启动失败：" + e.getMessage() + "（引导继续，不阻塞）");
                return false;
            }
        }

        private void serve(HttpExchange ex) throws IOException {
            try {
                File f = packFile(); // 每次现取：pack regen 后立即生效
                if (f == null || !f.isFile()) {
                    ex.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] data = Files.readAllBytes(f.toPath());
                ex.getResponseHeaders().set("Content-Type", "application/zip");
                ex.getResponseHeaders().set("Content-Disposition",
                        "attachment; filename=\"" + PACK_ZIP_NAME + "\"");
                boolean head = "HEAD".equalsIgnoreCase(ex.getRequestMethod());
                ex.sendResponseHeaders(200, head ? -1 : data.length);
                if (!head) {
                    ex.getResponseBody().write(data);
                }
                debugLog("资源包已发送给 " + ex.getRemoteAddress());
            } finally {
                ex.close();
            }
        }

        boolean isRunning() {
            return server != null;
        }

        void stop() {
            if (server != null) {
                server.stop(0); // 立即停并释放端口
                server = null;
                if (executor instanceof java.util.concurrent.ExecutorService es) {
                    es.shutdownNow();
                }
                executor = null;
                info("内置资源包服务已关闭，端口已释放。");
            }
        }
    }

    // ============================================================
    // 事件监听
    // ============================================================

    /** 进服：异步读库判定新老 -> 下发资源包 -> 延迟后开始引导 / 闪问候 */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!config.enabled || script == null || script.dialogues.isEmpty()) {
            return; // 无可用台词时不触发任何 UI，绝不卡住玩家
        }
        Player p = event.getPlayer();
        final String firstNode = script.firstPlayable();
        if (firstNode == null) {
            return;
        }

        // 资源包下发：异步不等待结果（SUCCESS/DECLINED 由状态事件记录）
        if (config.packAutoSend) {
            sendPack(p);
        }

        final UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(this, () ->
                database.load(id).whenComplete((opt, err) -> {
                    if (err != null) {
                        warn("读取进度失败：" + err.getMessage() + "（按新玩家处理）");
                    }
                    final Optional<Progress> safe = opt == null ? Optional.empty() : opt;
                    Bukkit.getScheduler().runTask(StarInn.this, () -> {
                        if (!p.isOnline()) {
                            return;
                        }
                        Progress pr = safe.orElse(null);
                        if (pr == null) {
                            // 新玩家：完整引导（可跳过）
                            startSession(p, firstNode);
                        } else {
                            switch (pr.state()) {
                                case "COMPLETED", "SKIPPED" -> flashGreeting(p);
                                case "NEW" -> {
                                    // 断线重连：从库里的 current_node 继续；选项节点则重显该选项
                                    String resume = script.nodes.containsKey(pr.node())
                                            ? pr.node() : firstNode;
                                    startSession(p, resume);
                                }
                                default -> startSession(p, firstNode);
                            }
                        }
                    });
                }), config.delayOnJoin);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session s = sessions.remove(event.getPlayer().getUniqueId());
        if (s != null) {
            s.closeNow(); // 进度已逐节点持久化，重连自动续
        }
    }

    /** Java/基岩点击推进（空气与方块左/右键都接；容器类方块除外，防误开） */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!config.enabled) {
            return;
        }
        Action a = event.getAction();
        boolean left;
        if (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) {
            left = true;
        } else if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) {
            left = false;
        } else {
            return;
        }
        if (a == Action.LEFT_CLICK_BLOCK || a == Action.RIGHT_CLICK_BLOCK) {
            if (event.getClickedBlock() != null
                    && INTERACTIVE_BLOCKS.contains(event.getClickedBlock().getType())) {
                return; // 让玩家正常开箱，不打断也不误推进
            }
        }
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s == null) {
            return;
        }
        // Java：左键=0 右键=1（等待状态两者都推进）；基岩：任意点击=继续
        s.onClick(left ? 0 : 1);
        if (s.phase == Phase.AWAIT_CHOICE && !s.bedrock) {
            event.setCancelled(true); // 选项界面下吞掉点击，防误放方块
        }
    }

    /** Java 端 Shift 跳过（基岩端 Shift 不生效：跳过走表单按钮或聊天 skip） */
    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!config.enabled || !event.isSneaking()) {
            return;
        }
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s != null && !s.bedrock) {
            s.skip();
        }
    }

    /** Java 端按 F（交换双手物品）选择第三个选项（通用机制保留；当前主线已无选项节点） */
    @EventHandler
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        if (!config.enabled) {
            return;
        }
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s != null && !s.bedrock && s.phase == Phase.AWAIT_CHOICE
                && s.node.choices().size() >= 3) {
            event.setCancelled(true);
            s.choose(2);
        }
    }

    /** 基岩端降级方案：聊天输入 1/2/3 选项、skip 跳过 */
    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (!config.enabled) {
            return;
        }
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s == null || !s.bedrock) {
            return;
        }
        String msg = event.getMessage().trim().toLowerCase(Locale.ROOT);
        if (msg.equals("skip") || msg.equals("跳过")) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(this, s::skip);
            return;
        }
        if (s.phase == Phase.AWAIT_CHOICE) {
            int idx = switch (msg) {
                case "1" -> 0;
                case "2" -> 1;
                case "3" -> 2;
                default -> -1;
            };
            if (idx >= 0 && idx < s.node.choices().size()) {
                event.setCancelled(true);
                final int fi = idx;
                Bukkit.getScheduler().runTask(this, () -> s.choose(fi));
            }
        }
    }

    /** 资源包状态：无论成功与否都只记录日志并放行，绝不因资源包踢玩家（8.3 / 基岩专项 六.4） */
    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        // 用 name() 匹配，避免不同小版本枚举项差异导致的编译/运行问题
        String status = event.getStatus().name();
        Player p = event.getPlayer();
        switch (status) {
            case "SUCCESS", "ACCEPTED", "DOWNLOADED" ->
                    debugLog(p.getName() + " 资源包状态：" + status);
            case "DECLINED" ->
                    info(p.getName() + " 拒绝了资源包下载，引导继续（只是没有立绘贴图）。");
            case "FAILED_DOWNLOAD" ->
                    warn(p.getName() + "（可能为基岩/Geyser 玩家）资源包下载或转码失败：请检查下发地址可达、"
                            + "resource-pack.builtin-port（当前 " + config.packBuiltinPort
                            + "）已在防火墙/安全组放行；本插件只记录日志并放行，绝不因此踢出玩家，引导照常。");
            default ->
                    debugLog(p.getName() + " 资源包状态：" + status);
        }
    }

    // ============================================================
    // 命令处理（onCommand 在主类，不另建类）
    // ============================================================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("starinn")) {
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§e/starinn reload §7| §ereset <玩家> §7| §estatus §7| §epack regen");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                if (!sender.hasPermission(config.permReload)) {
                    sender.sendMessage("§c没有权限（" + config.permReload + "）。");
                    return true;
                }
                doReload(sender);
            }
            case "reset" -> {
                if (!sender.hasPermission(config.permReset)) {
                    sender.sendMessage("§c没有权限（" + config.permReset + "）。");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§c用法：/starinn reset <玩家名>");
                    return true;
                }
                doReset(sender, args[1]);
            }
            case "status" -> {
                if (!sender.hasPermission(config.permReload)) {
                    sender.sendMessage("§c没有权限（" + config.permReload + "）。");
                    return true;
                }
                doStatus(sender);
            }
            case "pack" -> {
                if (!sender.hasPermission(config.permReload)) {
                    sender.sendMessage("§c没有权限（" + config.permReload + "）。");
                    return true;
                }
                if (args.length >= 2 && args[1].equalsIgnoreCase("regen")) {
                    doPackRegen(sender);
                } else {
                    sender.sendMessage("§c用法：/starinn pack regen（强制重建资源包并重启下发服务）");
                }
            }
            default -> sender.sendMessage("§c未知子命令：/starinn <reload|reset|status|pack regen>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("reload", "reset", "status", "pack"), args[0]);
        }
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("reset")) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    names.add(p.getName());
                }
                return filter(names, args[1]);
            }
            if (args[0].equalsIgnoreCase("pack")) {
                return filter(List.of("regen"), args[1]);
            }
        }
        return List.of();
    }

    private List<String> filter(List<String> all, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : all) {
            if (s.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(s);
            }
        }
        return out;
    }

    /** 补齐懒建组件：启动时 enabled=false、随后 reload 打开的场景不要求重启服务器 */
    private void ensureLiveComponents() {
        if (script == null) {
            script = new Script();
        }
        if (database == null) {
            database = new Database(new File(getDataFolder(), config.databaseFile));
            database.initAsync(null);
        }
        if (packBuilder == null) {
            packBuilder = new PackBuilder();
        }
        if (packServer == null) {
            packServer = new PackServer();
        }
    }

    /** 热加载：配置 + 对话，不踢人；端口/地址变化时自动重启内置服务并打印新地址 */
    private void doReload(CommandSender sender) {
        Config old = config;
        // 自检项1 重跑：格式错误时打印行列号并内置默认值兜底（不抛异常）
        config = loadConfigSafe();
        if (!config.enabled) {
            for (Session s : new ArrayList<>(sessions.values())) {
                s.skip();
            }
            if (packServer != null) {
                packServer.stop();
            }
            sender.sendMessage("§e已热加载：enabled=false，引导关闭，进行中的会话已按跳过落库。");
            return;
        }
        // 若插件启动时是关闭状态，这里补齐懒建组件（不要求重启服务器）
        ensureLiveComponents();
        // 重新加载脚本：缺台词不再抛异常，跳过缺失节点、保留旧图继续（内部已登记自检）
        script.load();
        // 进行中的会话重绑到新节点图（节点没了就自然结束，不踢人）
        int rebound = 0;
        for (Session s : new ArrayList<>(sessions.values())) {
            if (script.nodes.containsKey(s.nodeId)) {
                s.goTo(s.nodeId);
                rebound++;
            } else {
                s.closeNow();
                sessions.remove(s.uuid, s);
            }
        }
        // 内置服务：端口无效即停；参数变化或未运行则重启到新端口
        if (!config.portValid()) {
            packServer.stop();
            severe("resource-pack.builtin-port 未配置或无效（" + config.packBuiltinPort
                    + "），资源包下发已关闭；修正后再次 reload。");
        } else {
            boolean paramChanged = old.packBuiltinPort != config.packBuiltinPort
                    || !old.packBuiltinHost.equals(config.packBuiltinHost)
                    || !old.packServePath.equals(config.packServePath)
                    || !old.packPublicHost.equals(config.packPublicHost);
            if (paramChanged || !packServer.isRunning()) {
                packServer.stop();
                packServer.start(config.packBuiltinHost, config.packBuiltinPort, config.packServePath);
            }
        }
        // 资源包：hash 与配置不一致才重新组装（贴图 IO 全在异步线程，不掉 TPS）
        if (config.packAutoGenerate) {
            runAsync(() -> packBuilder.buildAndPublish(false), null);
        }
        sender.sendMessage("§a已热加载：配置、对话（重绑 " + rebound + " 个进行中会话）、资源包服务。");
        info("当前下发地址：" + describePackSource());
    }

    /**
     * /starinn pack regen：删除旧 zip -> 强制重新组装 -> 重算并回填 sha256
     * -> 按当前配置重启 HTTP 服务 -> 提示新地址。打包全程异步，不掉 TPS。
     */
    private void doPackRegen(CommandSender sender) {
        sender.sendMessage("§e开始强制重建资源包（异步线程执行，不掉 TPS）…");
        File zip = packFile();
        if (zip.isFile() && !zip.delete()) {
            warn("旧资源包 zip 删除失败（可能被占用），将直接覆盖重建。");
        }
        runAsync(() -> {
            final boolean ok = packBuilder.buildAndPublish(true);
            Bukkit.getScheduler().runTask(StarInn.this, () -> {
                // 重启 HTTP 服务：端口/host/路径全部按当前配置重新读取
                packServer.stop();
                if (config.portValid()) {
                    packServer.start(config.packBuiltinHost, config.packBuiltinPort, config.packServePath);
                } else {
                    severe("resource-pack.builtin-port 未配置或无效，HTTP 服务未重启。");
                }
                if (ok) {
                    String url = packDownloadUrl();
                    sender.sendMessage("§a资源包已重建，sha256 已回填 config.yml。新下发地址：§f"
                            + (url == null ? "（端口/地址配置异常）" : url));
                } else {
                    sender.sendMessage("§c资源包重建失败（引导与其他功能不受影响），详见服务器日志。");
                }
            });
        }, t -> Bukkit.getScheduler().runTask(this,
                () -> sender.sendMessage("§c资源包重建异常：" + t.getMessage())));
    }

    /** 清除指定玩家进度 -> 下次进服重新引导 */
    private void doReset(CommandSender sender, String name) {
        if (database == null) {
            sender.sendMessage("§c插件当前未启用（enabled=false），无进度可重置。");
            return;
        }
        OfflinePlayer op = Bukkit.getOfflinePlayer(name);
        UUID id = op.getUniqueId();
        Session s = sessions.remove(id);
        if (s != null) {
            s.closeNow();
        }
        database.save(id, new Progress("NEW", MAIN_FLOW.get(0), "")).whenComplete((v, err) ->
                Bukkit.getScheduler().runTask(this, () -> {
                    if (err != null) {
                        sender.sendMessage("§c重置失败：" + err.getMessage());
                    } else {
                        sender.sendMessage("§a已重置 " + name + " 的引导进度，下次进服会重新开始。");
                    }
                }));
    }

    /** 插件状态：重点展示当前下发端口/地址（端口值只可能来自配置） */
    private void doStatus(CommandSender sender) {
        sender.sendMessage("§6== 星穹旅驿 v" + getDescription().getVersion() + " ==");
        sender.sendMessage("§7启用：§f" + config.enabled
                + " §7| Geyser 组件：§f" + (geyserOnClasspath ? "检测到（可选）" : "未安装（按 Java 端处理）"));
        sender.sendMessage("§7内置服务：§f" + (packServer != null && packServer.isRunning() ? "运行中" : "未运行")
                + " §7监听 §f" + config.packBuiltinHost + ":" + config.packBuiltinPort
                + config.packServePath);
        sender.sendMessage("§7对外主机 public-host：§f"
                + (config.packPublicHost.isEmpty() ? "（空，自动取服务器 IP）" : config.packPublicHost));
        sender.sendMessage("§7当前下发地址：§f" + describePackSource());
        File zip = packFile();
        sender.sendMessage("§7资源包文件：§f" + (zip.isFile() ? zip.length() + " 字节" : "尚未生成")
                + " §7| sha256：§f" + (config.packSha256.isEmpty() ? "空" :
                config.packSha256.substring(0, Math.min(16, config.packSha256.length())) + "…"));
        sender.sendMessage("§7数据库：§f" + (database == null ? "未初始化"
                : (database.isDegraded() ? "内存降级（sqlite 不可用）" : "sqlite 正常")));
        long bedrockCount = sessions.values().stream().filter(s -> s.bedrock).count();
        sender.sendMessage("§7进行中会话：§f" + sessions.size() + "（基岩 " + bedrockCount + "）");
        sender.sendMessage("§7台词总数：§f" + (script == null ? 0 : script.dialogues.size())
                + " / " + REQUIRED_IDS.size());
    }

    // ============================================================
    // 通用小工具
    // ============================================================

    private void startSession(Player p, String node) {
        Session old = sessions.remove(p.getUniqueId());
        if (old != null) {
            old.closeNow();
        }
        Session s = new Session(p, isBedrock(p));
        sessions.put(p.getUniqueId(), s);
        s.begin(node);
    }

    /** 老玩家/已跳过玩家：只闪一句问候，greeting-duration 后消失 */
    private void flashGreeting(Player p) {
        Dialogue g = script.dialogues.get(GREETING_ID);
        if (g == null) {
            return;
        }
        String text = "「" + script.npcName(g.npc()) + "」 " + g.text();
        if (isBedrock(p)) {
            p.sendActionBar(text);
            Bukkit.getScheduler().runTaskLater(this, () -> p.sendActionBar(""), config.greetingDuration);
        } else {
            BossBar bar = Bukkit.createBossBar(text, script.npcColor(g.npc()), BarStyle.SOLID);
            bar.setProgress(1.0);
            bar.addPlayer(p);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                bar.removePlayer(p);
                bar.setVisible(false);
            }, config.greetingDuration);
        }
    }

    /** 下发资源包：URL 由配置拼装（public-host + builtin-port + serve-path），代码里无端口字面量 */
    private void sendPack(Player p) {
        String url = packDownloadUrl();
        if (url == null) {
            debugLog("builtin-port 未配置/无效，跳过资源包下发。");
            return;
        }
        // 服务没起来（端口占用）或 zip 还没生成时，不给玩家推死地址
        if (packServer == null || !packServer.isRunning() || !packFile().isFile()) {
            debugLog("资源包下发未就绪（服务未运行或 zip 未生成），本次跳过。");
            return;
        }
        try {
            if (config.packSha256 != null && !config.packSha256.isBlank()) {
                p.setResourcePack(url, config.packSha256);
            } else {
                p.setResourcePack(url);
            }
            debugLog("已向 " + p.getName() + " 下发资源包：" + url);
        } catch (IllegalArgumentException badHash) {
            // 校验值格式非法（例如手改错了），去掉 hash 再发一次
            p.setResourcePack(url);
            warn("sha256 校验值格式非法，已不带校验下发：" + badHash.getMessage());
        } catch (RuntimeException e) {
            warn("资源包下发异常（引导继续）：" + e.getMessage());
        }
    }

    /** 当前实际下发 URL：http://<public-host>:<builtin-port><serve-path>，全部取自 Config */
    private String packDownloadUrl() {
        if (config == null || !config.portValid()) {
            return null;
        }
        String host = config.packPublicHost.isEmpty() ? resolveServerHost() : config.packPublicHost;
        return "http://" + host + ":" + config.packBuiltinPort + config.packServePath;
    }

    private String describePackSource() {
        String url = packDownloadUrl();
        if (url == null) {
            return "无（resource-pack.builtin-port 未配置/无效，下发已关闭；引导不受影响）";
        }
        return "内置服务 " + url
                + (config.packPublicHost.isEmpty() ? "（public-host 留空，自动取服务器 IP）" : "（public-host 指定）");
    }

    /** public-host 留空时的兜底主机：服务器绑定 IP -> 本机地址（均为本地取值，无对外网络请求） */
    private String resolveServerHost() {
        String bound = getServer().getIp();
        if (bound != null && !bound.isBlank() && !bound.equals("0.0.0.0")) {
            return bound;
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private File packFile() {
        return new File(getDataFolder(), PACK_ZIP_NAME);
    }

    /** 服主正式立绘目录：固定 plugins/StarInn/textures/（空目录起步，不放默认图；同名 PNG 覆盖生效） */
    private File texturesDir() {
        File f = new File(getDataFolder(), "textures");
        if (!f.isDirectory()) {
            f.mkdirs();
        }
        return f;
    }

    private void saveDefaultDialogues() {
        for (String fn : List.of("wu.yml", "chen.yml", "an.yml")) {
            File f = new File(getDataFolder(), "dialogues/" + fn);
            if (!f.exists()) {
                saveResource("dialogues/" + fn, false);
            }
        }
    }

    /**
     * 显式 UTF-8 读取 yml（防基岩端中文乱码）。
     * 格式错误：日志原样带上 SnakeYAML 的行列信息，登记自检降级，返回 null 由调用方跳过该文件
     * ——绝不抛异常拖垮插件启动。
     */
    private YamlConfiguration loadYamlUtf8(File f, String display) {
        try (Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            YamlConfiguration y = new YamlConfiguration();
            y.load(r);
            return y;
        } catch (Exception e) {
            severe("对话文件格式错误：" + display + " -> " + e.getMessage()
                    + "（含具体行列号）；该文件本次跳过，其余台词照常播放。");
            if (selfCheck != null) {
                selfCheck.degraded("对话台词（" + REQUIRED_IDS.size() + " 条齐全）", "文件解析异常：" + display);
            }
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String sha256Hex(File f) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256 实现", e);
        }
        try (InputStream in = Files.newInputStream(f.toPath())) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 文本级原位回填 sha256，保住 config.yml 的所有中文注释 */
    private void writeShaToConfigText(String hex) {
        Path cfg = new File(getDataFolder(), "config.yml").toPath();
        try {
            List<String> lines = Files.readAllLines(cfg, StandardCharsets.UTF_8);
            boolean changed = false;
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = SHA_LINE_PATTERN.matcher(lines.get(i));
                if (m.matches()) {
                    String repl = m.group(1) + "\"" + hex + "\"";
                    if (!repl.equals(lines.get(i))) {
                        lines.set(i, repl);
                        changed = true;
                    }
                    break;
                }
            }
            if (changed) {
                Files.write(cfg, lines, StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (IOException e) {
            warn("sha256 回填 config.yml 失败（不影响运行）：" + e.getMessage());
        }
    }

    // ============================================================
    // 基岩端检测与 Geyser SimpleForm（全部反射，缺 Geyser 自动降级）
    // ============================================================

    private boolean detectGeyser() {
        boolean found;
        try {
            Class.forName("org.geysermc.geyser.GeyserImpl");
            info("检测到 Geyser（可选组件），基岩端将启用 ActionBar/表单适配。");
            found = true;
        } catch (ClassNotFoundException e) {
            info("未检测到 Geyser：全部按 Java 端渲染（插件照常工作）。");
            found = false;
        }
        selfCheck.recordSync("Geyser 基岩桥接", found,
                "Geyser API 不可用，已按 Java 端渲染（基岩表单停用，功能不缺）");
        return found;
    }

    /**
     * 基岩玩家判定（第四部分给定骨架）：反射调用 Geyser，Geyser 未装/接口变动都返回 false。
     */
    private boolean isBedrock(Player p) {
        if (!geyserOnClasspath) {
            return false;
        }
        try {
            Class<?> geyser = Class.forName("org.geysermc.geyser.GeyserImpl");
            Object inst = geyser.getMethod("getInstance").invoke(null);
            return geyserConnection(inst, p.getUniqueId()) != null;
        } catch (Exception e) {
            return false; // Geyser 未安装或接口不匹配，全部按 Java 端处理
        }
    }

    /** 兼容新旧方法名取 GeyserConnection：getConnectionByUuid / connectionByUuid */
    private Object geyserConnection(Object geyserInstance, UUID id) {
        for (String name : List.of("getConnectionByUuid", "connectionByUuid")) {
            try {
                Method m = geyserInstance.getClass().getMethod(name, UUID.class);
                return m.invoke(geyserInstance, id);
            } catch (Exception ignored) {
                // 继续试下一个名字
            }
        }
        return null;
    }

    /**
     * 尝试用 Geyser SimpleForm 弹按钮组（全反射，任何一步失败即返回 false 降级）。
     * Geyser 表单 API 在不同版本间有出入，因此不做编译期依赖，也满足“零前置”红线。
     */
    private boolean tryGeyserForm(Player p, String title, String content,
                                  List<String> buttons, IntConsumer onSelect) {
        if (!geyserOnClasspath) {
            return false;
        }
        try {
            Class<?> impl = Class.forName("org.geysermc.geyser.GeyserImpl");
            Object inst = impl.getMethod("getInstance").invoke(null);
            Object conn = geyserConnection(inst, p.getUniqueId());
            if (conn == null) {
                return false;
            }
            Class<?> simpleForm = Class.forName("org.geysermc.geyser.api.form.SimpleForm");
            Object builder = simpleForm.getMethod("builder").invoke(null);

            builder = chain(builder, "title", String.class, title);
            if (content != null) {
                try {
                    builder = chain(builder, "content", String.class, content);
                } catch (NoSuchMethodException ignore) {
                    // 个别版本无 content 方法：仅标题也可用
                }
            }
            Class<?> responder = Class.forName("org.geysermc.geyser.api.form.SimpleForm$Responder");
            for (int i = 0; i < buttons.size(); i++) {
                final int idx = i;
                Object handler = Proxy.newProxyInstance(responder.getClassLoader(),
                        new Class<?>[]{responder}, (proxy, m, callArgs) -> {
                            if (m.getName().startsWith("respond") || m.getName().startsWith("click")) {
                                Bukkit.getScheduler().runTask(StarInn.this, () -> onSelect.accept(idx));
                            }
                            Class<?> rt = m.getReturnType();
                            if (rt == boolean.class) {
                                return false;
                            }
                            if (rt.isPrimitive() && rt != void.class) {
                                return 0;
                            }
                            return null;
                        });
                builder = chain(builder, "button", String.class, buttons.get(i),
                        responder, handler);
            }
            Object form = builder.getClass().getMethod("build").invoke(builder);
            Class<?> formIface = Class.forName("org.geysermc.geyser.api.form.Form");
            Method show = null;
            for (Method m : conn.getClass().getMethods()) {
                if (m.getName().equals("showForm") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(formIface)) {
                    show = m;
                    break;
                }
            }
            if (show == null) {
                return false;
            }
            show.invoke(conn, form);
            return true;
        } catch (Throwable t) {
            debugLog("Geyser 表单不可用（" + t + "），降级为 ActionBar + 聊天序号。");
            return false;
        }
    }

    /** 反射链式调用 builder 方法（兼容“返回 this”的链式风格） */
    private Object chain(Object builder, String method, Class<?> type1, Object arg1,
                         Class<?> type2, Object arg2) throws ReflectiveOperationException {
        Method m = builder.getClass().getMethod(method, type1, type2);
        Object out = m.invoke(builder, arg1, arg2);
        return out == null ? builder : out;
    }

    private Object chain(Object builder, String method, Class<?> type, Object arg)
            throws ReflectiveOperationException {
        Method m = builder.getClass().getMethod(method, type);
        Object out = m.invoke(builder, arg);
        return out == null ? builder : out;
    }

    // ============================================================
    // 异步与日志小助手
    // ============================================================

    /** 把一段工作扔到异步线程；失败回调可选（onError 传 null 则吞掉并打日志） */
    private CompletableFuture<Void> runAsync(Runnable work, java.util.function.Consumer<Throwable> onError) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                work.run();
                f.complete(null);
            } catch (Throwable t) {
                if (onError != null) {
                    onError.accept(t);
                } else {
                    warn("异步任务异常：" + t.getMessage());
                }
                f.complete(null); // 任务失败不向上传播，引导永不因此中断
            }
        });
        return f;
    }

    private void info(String msg) {
        getLogger().info(LOG_PREFIX + msg);
    }

    private void warn(String msg) {
        getLogger().warning(LOG_PREFIX + msg);
    }

    private void severe(String msg) {
        getLogger().severe(LOG_PREFIX + msg);
    }

    private void debugLog(String msg) {
        if (config != null && config.debug) {
            info("(debug) " + msg);
        }
    }
}
