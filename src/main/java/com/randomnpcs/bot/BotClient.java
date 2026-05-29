package com.randomnpcs.bot;

import com.randomnpcs.RandomBotsPlugin;
import com.randomnpcs.net.MinecraftProtocol;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One fake-player bot.
 *
 * TCP connection: handles ONLY KeepAlive + Confirm Teleport (passive).
 * Chat + actions: executed via Paper API on the server-side Player object.
 *
 * Successful connection config (host:port → protocol version) is stored in a
 * static cache so subsequent bots skip the protocol query entirely.
 *
 * Chat messages are generated dynamically: bots pick a random topic category
 * each time and produce a varied, natural-sounding line, optionally mentioning
 * another online bot by name to simulate player interaction.
 */
public class BotClient {

    private final RandomBotsPlugin plugin;
    private final BotManager       manager;
    private final String           name;
    private final String           host;
    private final int              port;
    private final UUID             uuid;
    private final Random           random = new Random();

    private Socket            socket;
    private MinecraftProtocol proto;
    private volatile boolean  connected = false;
    private volatile boolean  alive     = true;

    private volatile boolean probeFailedReconnect = false;

    private BukkitTask chatTask;
    private BukkitTask actionTask;

    private double x, y, z;
    private float  yaw, pitch;

    // ── Static cache: successful protocol version per server ──────────────
    // Key: "host:port"  Value: protocol version int
    private static final ConcurrentHashMap<String, Integer> PROTO_CACHE =
            new ConcurrentHashMap<>();

    // ── Chat topic engine ─────────────────────────────────────────────────
    // Each topic is a list of message templates.
    // {other} is replaced by a random online player/bot name when available.
    private static final List<List<String>> TOPIC_POOL = buildTopicPool();

    // Per-bot recently-used topic indices (avoid repeating the same category
    // too many times in a row)
    private final Deque<Integer> recentTopics = new ArrayDeque<>();
    private static final int RECENT_TOPIC_MEMORY = 4;

    public BotClient(RandomBotsPlugin plugin, BotManager manager,
                     String name, String host, int port) {
        this.plugin  = plugin;
        this.manager = manager;
        this.name    = name;
        this.host    = host;
        this.port    = port;
        this.uuid    = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Connect
    // ═══════════════════════════════════════════════════════════════════════

    public void connect() throws IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(60_000);

        DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(socket.getOutputStream(), 8192));
        DataInputStream in = new DataInputStream(
                new BufferedInputStream(socket.getInputStream(), 8192));

        proto = new MinecraftProtocol(in, out, plugin.getLogger(), name, host, port);

        String cacheKey = host + ":" + port;
        Integer cachedProto = PROTO_CACHE.get(cacheKey);
        if (cachedProto != null) {
            proto.setProtocolVersion(cachedProto);
            plugin.getLogger().fine("[" + name + "] Using cached protocol: " + cachedProto);
        } else {
            int detectedProto = MinecraftProtocol.queryProtocolVersion(host, port, plugin.getLogger());
            if (detectedProto > 0) {
                proto.setProtocolVersion(detectedProto);
                PROTO_CACHE.put(cacheKey, detectedProto);
                plugin.getLogger().fine("[" + name + "] Protocol " + detectedProto + " cached");
            } else {
                plugin.getLogger().fine("[" + name + "] Could not detect protocol, using fallback");
            }
        }

        proto.initiateLogin(host, port, name, uuid);

        if (!proto.completeLogin(name)) {
            socket.close();
            throw new IOException("Login rejected for: " + name);
        }

        connected = true;
        plugin.getLogger().info(name + " joined the game");

        startReaderThread();
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (connected && alive) {
                scheduleChatTask();
                scheduleActionTask();
            }
        }, 60L);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Reader thread
    // ═══════════════════════════════════════════════════════════════════════

    private void startReaderThread() {
        Thread t = new Thread(() -> {
            try {
                while (connected && alive && !socket.isClosed()) {
                    proto.readAndHandle(this);
                }
            } catch (IOException e) {
                if (!connected) return;
                String msg = e.getMessage() != null ? e.getMessage() : "";

                if (msg.startsWith("Kicked:")) {
                    // Paper already logs the quit message natively

                } else if (isProbeDecodeError(msg)) {
                    int nextId = proto.nextProbeCandidate();
                    plugin.getLogger().fine("[" + name + "] Probe 0x"
                            + Integer.toHexString(proto.getProbeCandidate() - 1)
                            + " rejected → trying 0x" + Integer.toHexString(nextId));
                    probeFailedReconnect = true;

                } else {
                    // Paper already logs the quit message natively
                }
            } finally {
                handleDisconnect();
            }
        }, "RandomBot-" + name);
        t.setDaemon(true);
        t.start();
    }

    private static boolean isProbeDecodeError(String msg) {
        return msg.contains("Failed to decode packet")
                || msg.contains("Received unknown packet id")
                || msg.contains("was larger than I expected");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Behaviours
    // ═══════════════════════════════════════════════════════════════════════

    private void scheduleChatTask() {
        int min = plugin.getConfig().getInt("chat-interval-min", 15);
        int max = plugin.getConfig().getInt("chat-interval-max", 45);
        long ticks = (min + random.nextInt(max - min + 1)) * 20L;
        chatTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!connected || !alive) return;
            sendChatViaApi();
            scheduleChatTask();
        }, ticks);
    }

    private void scheduleActionTask() {
        int min = plugin.getConfig().getInt("action-interval-min", 5);
        int max = plugin.getConfig().getInt("action-interval-max", 15);
        long ticks = (min + random.nextInt(max - min + 1)) * 20L;
        actionTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!connected || !alive) return;
            performActionViaApi();
            scheduleActionTask();
        }, ticks);
    }

    // ── Chat ──────────────────────────────────────────────────────────────

    private void sendChatViaApi() {
        // Config messages take priority if present; otherwise use dynamic topics
        List<String> configMessages = plugin.getConfig().getStringList("chat-messages");
        String msg;
        if (!configMessages.isEmpty()) {
            msg = configMessages.get(random.nextInt(configMessages.size()));
        } else {
            msg = generateChatMessage();
        }

        Player player = Bukkit.getPlayerExact(name);
        if (player == null || !player.isOnline()) return;

        final String finalMsg = msg;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayerExact(name);
            if (p != null && p.isOnline()) {
                p.chat(finalMsg);
            }
        });
    }

    /**
     * Pick a topic category (avoiding recent repeats) and return a message.
     * Optionally addresses another online player by name.
     */
    private String generateChatMessage() {
        // Choose a topic index not used recently
        int topicIdx;
        int attempts = 0;
        do {
            topicIdx = random.nextInt(TOPIC_POOL.size());
            attempts++;
        } while (recentTopics.contains(topicIdx) && attempts < 20);

        recentTopics.addLast(topicIdx);
        if (recentTopics.size() > RECENT_TOPIC_MEMORY) recentTopics.removeFirst();

        List<String> lines = TOPIC_POOL.get(topicIdx);
        String line = lines.get(random.nextInt(lines.size()));

        // Replace {other} with a random online player name (not self)
        if (line.contains("{other}")) {
            String other = pickOtherPlayerName();
            line = line.replace("{other}", other);
        }
        return line;
    }

    /** Pick another online player's name, falling back to a generic "大家" */
    private String pickOtherPlayerName() {
        List<String> others = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getName().equals(name)) others.add(p.getName());
        }
        if (others.isEmpty()) return "大家";
        return others.get(random.nextInt(others.size()));
    }

    // ── Actions ───────────────────────────────────────────────────────────

    private void performActionViaApi() {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null || !player.isOnline()) return;
        int action = random.nextInt(4);
        switch (action) {
            case 0 -> {
                Location loc = player.getLocation();
                double dx = (random.nextDouble() - 0.5) * 3;
                double dz = (random.nextDouble() - 0.5) * 3;
                Location target = loc.clone().add(dx, 0, dz);
                target.setWorld(loc.getWorld());
                if (target.getWorld() != null && target.getChunk().isLoaded()) {
                    target.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                    target.setPitch(0);
                    player.teleport(target);
                }
            }
            case 1 -> {
                Location loc = player.getLocation();
                loc.setYaw(random.nextFloat() * 360f - 180f);
                loc.setPitch(random.nextFloat() * 60f - 30f);
                player.teleport(loc);
            }
            case 2 -> player.swingMainHand();
            case 3 -> {
                player.setSneaking(!player.isSneaking());
                if (player.isSneaking()) {
                    Bukkit.getScheduler().runTaskLater(plugin,
                            () -> { Player p = Bukkit.getPlayerExact(name);
                                    if (p != null) p.setSneaking(false); }, 20L);
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Topic pool — random, natural Chinese chat lines
    // Each inner list is one topic category; {other} → random player name
    // ═══════════════════════════════════════════════════════════════════════

    private static List<List<String>> buildTopicPool() {
        List<List<String>> pool = new ArrayList<>();

        // 0 挖矿 & 资源
        pool.add(Arrays.asList(
            "刚挖到钻石！今天手气不错",
            "深层矿好难找，挖了半天就几块铁矿",
            "有人知道钻石层在y多少吗",
            "我刚从-58层挖回来，收获满满",
            "古城下面全是深板岩，好难挖啊",
            "{other} 你去挖矿记得带够火把",
            "用附魔了时运3的镐子，矿石翻倍爽歪歪",
            "挖矿前一定要备好食物，不然饿死在地下",
            "发现一个废弃矿井，箱子里有不少好东西"
        ));

        // 1 建筑 & 装修
        pool.add(Arrays.asList(
            "我在建一个中式大宅，感觉要建很久",
            "有人给我推荐个好看的屋顶设计吗",
            "刚学会用楼梯块拼屋顶，效果还不错",
            "我家门口种了一排樱花树，好看！",
            "{other} 你那个房子外墙用的什么材料？",
            "圆形建筑好难搭，总是不对称",
            "我打算做个地下密室，装满宝箱那种",
            "用混凝土盖房子比石头好看多了",
            "玻璃天窗真的很好看，推荐大家试试"
        ));

        // 2 生存技巧
        pool.add(Arrays.asList(
            "新手提示：床一定要放在家里，不然重生点丢了很惨",
            "记得给你的装备附魔耐久，省得经常修",
            "农场一定要早建，食物多了才不慌",
            "带水桶可以救命，掉进岩浆旁边直接泼",
            "{other} 你会用末影箱吗？存贵重物品必备",
            "进地狱前备好金苹果，僵尸猪灵惹不起",
            "睡觉跳过夜晚真的很重要，不然幻翼来了烦死",
            "钓鱼有概率钓到附魔书，白嫖装备神器",
            "村庄里的铁傀儡可以帮你打怪，别杀他"
        ));

        // 3 战斗 & PVE
        pool.add(Arrays.asList(
            "末影龙终于被我打败了！！",
            "凋零boss好难打，差点被我打死",
            "袭击事件来了，快集合！",
            "{other} 帮我打女巫吗？老是被我喝药解",
            "守卫者伤害太高了，打海底神殿要做好准备",
            "劫掠兽被我骑着绕圈圈，哈哈哈",
            "堡垒遗迹里的猪灵蛮横，进去前换金甲",
            "末地城探索完毕，鞘翅到手！",
            "骷髅弓箭手比僵尸麻烦多了，要先躲柱子"
        ));

        // 4 红石 & 自动化
        pool.add(Arrays.asList(
            "我做了一个全自动甘蔗农场，一晚上收了好多",
            "红石真的学不会，看教程也搞不懂",
            "{other} 你懂活塞门吗？我的门总是卡住",
            "自动熔炉真的省事，丢进去人就走",
            "鸡蛋孵化机我搭好了，鸡肉管够",
            "村民交易大厅今天开工了，好期待",
            "铁傀儡农场有点复杂，但铁矿产量太香了",
            "末影人农场的效率是真的高，经验刷得飞快",
            "凋零骷髅头自动收集机太难了，放弃了"
        ));

        // 5 探险 & 地形
        pool.add(Arrays.asList(
            "发现一个蘑菇岛！这里没有怪物真舒服",
            "我跑了好远才找到樱花树林，值了",
            "古城里面真的好可怕，小心幽匿尖啸体",
            "废墟portal找到了，但还没凑齐眼睛",
            "{other} 你见过深海遗迹吗？里面有海绵",
            "丛林神庙机关差点把我杀了，要小心",
            "沙漠神殿宝箱有tnt陷阱，别直接挖",
            "我找到了一个有好多村庄连在一起的地方",
            "末地探索回来了，带了好多紫珀矿"
        ));

        // 6 游戏心得 & 感想
        pool.add(Arrays.asList(
            "我觉得MC最好玩的就是完全自由，想干啥干啥",
            "每次新开档都有不一样的感觉",
            "玩了这么久还是觉得第一天最刺激",
            "{other} 你平时喜欢建筑还是生存？",
            "多人联机比单人有意思多了，热闹",
            "我一到晚上就犯困，不是游戏里是现实",
            "服务器人多就是好，感觉很有活力",
            "有时候就喜欢在游戏里发呆看风景",
            "第一次见到极光的时候真的被震惊到了"
        ));

        // 7 新闻 & 版本更新
        pool.add(Arrays.asList(
            "新版本加了好多新东西，还没全探索完",
            "听说下个版本要加新的群系，期待",
            "铜灯泡加进来了，建筑党狂喜",
            "试验性玩法里有些东西挺好玩的",
            "{other} 你用快照版玩过吗？",
            "风弹弓真的好玩，弹人弹怪都厉害",
            "盔甲纹饰系统我还没搞明白",
            "考古刷子真的很解压，慢慢挖",
            "新加的骆驼坐两人真的很方便"
        ));

        // 8 生活闲聊
        pool.add(Arrays.asList(
            "今天现实好热，来游戏里吹空调",
            "作业写完了终于可以玩了！",
            "下班后直接上线，这才是生活",
            "{other} 你今天过得怎么样",
            "最近睡眠不太好，打游戏放松一下",
            "一边听歌一边挖矿，太爽了",
            "今天吃了好好吃的饭，心情很好",
            "周末就应该在游戏里度过",
            "最近工作好忙，终于有时间上来玩了"
        ));

        // 9 食物 & 农业
        pool.add(Arrays.asList(
            "我的小麦田今天大丰收",
            "有人知道怎么高效养牛吗",
            "甜浆果真的好烦，走路经过一直掉血",
            "{other} 你有多余的南瓜种子吗",
            "蜂蜜可以解毒来着？我记得好像是",
            "发酵蜘蛛眼千万别乱吃",
            "可疑炖菜配方我还差几个没集齐",
            "烤猪排真的好用，回血量很高",
            "我种了一大片竹子，大熊猫要来我家了"
        ));

        // 10 装备 & 附魔
        pool.add(Arrays.asList(
            "终于凑齐了全套钻石装备！",
            "保护4的胸甲真的抗打",
            "锋利5的剑对上普通怪太简单了",
            "{other} 你的弓附魔了吗？无限附魔好用",
            "时运3真的是挖矿必备啊",
            "精准采集有时候也很有用，保留原始方块",
            "经验修补和耐久3叠加，装备用很久",
            "三叉戟附魔好多好好玩，忠诚回旋",
            "头盔加水下呼吸，打海底神殿舒服多了"
        ));

        // 11 问路 & 求助
        pool.add(Arrays.asList(
            "有人知道村庄在哪个方向吗",
            "{other} 你能借我点石头吗？我没材料了",
            "谁能告诉我地狱要怎么进",
            "有没有人一起去探险？",
            "我迷路了，谁来救救我",
            "有人有多余的钻石吗，能换点东西",
            "请问这里可以建房子吗",
            "{other} 能帮我看看我家门口的红石电路哪里接错了吗",
            "附近有没有铁矿？我的工具快没了"
        ));

        return Collections.unmodifiableList(pool);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Callbacks
    // ═══════════════════════════════════════════════════════════════════════

    public void updatePosition(double nx, double ny, double nz, float nyaw, float npitch) {
        this.x = nx; this.y = ny; this.z = nz;
        this.yaw = nyaw; this.pitch = npitch;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    public void handleDisconnect() {
        if (!alive) return;
        alive     = false;
        connected = false;
        cancelTasks();
        closeSocket();

        if (probeFailedReconnect) {
            plugin.getLogger().fine("[" + name + "] Probe reconnect in progress");
            probeFailedReconnect = false;
            manager.onBotProbeReconnect(name);
        } else {
            plugin.getLogger().fine("[" + name + "] Scheduling respawn");
            manager.onBotDied(name);
        }
    }

    public void disconnect(String reason) {
        alive     = false;
        connected = false;
        cancelTasks();
        closeSocket();
        manager.removeBot(name);
    }

    private void cancelTasks() {
        if (chatTask   != null) { chatTask.cancel();   chatTask   = null; }
        if (actionTask != null) { actionTask.cancel(); actionTask = null; }
    }

    private void closeSocket() {
        try { if (socket != null && !socket.isClosed()) socket.close(); }
        catch (Exception ignored) {}
    }

    public String  getBotName()  { return name;      }
    public boolean isConnected() { return connected; }
}
