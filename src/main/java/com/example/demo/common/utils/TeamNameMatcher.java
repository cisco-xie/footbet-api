package com.example.demo.common.utils;

import cn.hutool.core.text.TextSimilarity;
import cn.hutool.core.util.StrUtil;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 球队名称匹配工具类
 * 处理：音译差异、缩写、后缀、梯队、女足、特殊符号等
 */
public class TeamNameMatcher {

    // ==================== 配置阈值 ====================
    private static final double STRICT_THRESHOLD = 0.95;
    private static final double NORMAL_THRESHOLD = 0.85;
    private static final double LOOSE_THRESHOLD = 0.75;
    private static final double DEFAULT_THRESHOLD = 0.85;

    // ==================== 缩写映射（短名称 → 全称） ====================
    private static final Map<String, String> ABBREVIATION_MAP = new HashMap<>();

    // ==================== 音译归一化映射 ====================
    private static final Map<String, String> TRANSLITERATION_MAP = new HashMap<>();

    // ==================== 需要移除的后缀 ====================
    private static final List<String> REMOVE_SUFFIXES = new ArrayList<>();

    static {
        // ---------- 初始化缩写映射 ----------
        // 中国球队
        ABBREVIATION_MAP.put("国安", "北京国安");
        ABBREVIATION_MAP.put("上港", "上海上港");
        ABBREVIATION_MAP.put("恒大", "广州恒大");
        ABBREVIATION_MAP.put("鲁能", "山东鲁能");
        ABBREVIATION_MAP.put("申花", "上海申花");
        ABBREVIATION_MAP.put("苏宁", "江苏苏宁");
        ABBREVIATION_MAP.put("泰达", "天津泰达");
        ABBREVIATION_MAP.put("建业", "河南建业");
        ABBREVIATION_MAP.put("富力", "广州富力");
        ABBREVIATION_MAP.put("亚泰", "长春亚泰");
        ABBREVIATION_MAP.put("人和", "北京人和");
        ABBREVIATION_MAP.put("绿城", "杭州绿城");
        ABBREVIATION_MAP.put("宏运", "辽宁宏运");
        ABBREVIATION_MAP.put("力帆", "重庆力帆");
        ABBREVIATION_MAP.put("永昌", "石家庄永昌");
        ABBREVIATION_MAP.put("延边", "延边富德");
        ABBREVIATION_MAP.put("华夏", "河北华夏幸福");
        ABBREVIATION_MAP.put("权健", "天津权健");
        ABBREVIATION_MAP.put("一方", "大连一方");
        ABBREVIATION_MAP.put("斯威", "重庆斯威");
        ABBREVIATION_MAP.put("当代", "重庆当代");
        ABBREVIATION_MAP.put("黄海", "青岛黄海");
        ABBREVIATION_MAP.put("卓尔", "武汉卓尔");
        ABBREVIATION_MAP.put("永昌", "石家庄永昌");
        ABBREVIATION_MAP.put("兴城", "成都兴城");
        ABBREVIATION_MAP.put("绿城", "浙江绿城");
        ABBREVIATION_MAP.put("梅州", "梅州客家");
        ABBREVIATION_MAP.put("贵州", "贵州恒丰");
        ABBREVIATION_MAP.put("黑龙江", "黑龙江火山鸣泉");
        ABBREVIATION_MAP.put("陕西", "陕西长安竞技");
        ABBREVIATION_MAP.put("四川", "四川九牛");
        ABBREVIATION_MAP.put("昆山", "昆山fc");
        ABBREVIATION_MAP.put("南通", "南通支云");
        ABBREVIATION_MAP.put("新疆", "新疆天山雪豹");
        ABBREVIATION_MAP.put("内蒙古", "内蒙古中优");
        ABBREVIATION_MAP.put("江西", "江西联盛");
        ABBREVIATION_MAP.put("淄博", "淄博蹴鞠");
        ABBREVIATION_MAP.put("沈阳", "沈阳城市建设");
        ABBREVIATION_MAP.put("深圳", "深圳佳兆业");
        ABBREVIATION_MAP.put("北京", "北京中赫国安");
        ABBREVIATION_MAP.put("上海", "上海上港");
        ABBREVIATION_MAP.put("广州", "广州恒大淘宝");
        ABBREVIATION_MAP.put("天津", "天津泰达");
        ABBREVIATION_MAP.put("重庆", "重庆斯威");
        ABBREVIATION_MAP.put("河北", "河北华夏幸福");
        ABBREVIATION_MAP.put("山东", "山东鲁能泰山");
        ABBREVIATION_MAP.put("江苏", "江苏苏宁");
        ABBREVIATION_MAP.put("武汉", "武汉卓尔");
        ABBREVIATION_MAP.put("大连", "大连一方");
        ABBREVIATION_MAP.put("浙江", "浙江绿城");
        ABBREVIATION_MAP.put("青岛", "青岛黄海");

        // 欧洲球队
        ABBREVIATION_MAP.put("曼联", "曼彻斯特联");
        ABBREVIATION_MAP.put("曼城", "曼彻斯特城");
        ABBREVIATION_MAP.put("皇马", "皇家马德里");
        ABBREVIATION_MAP.put("巴萨", "巴塞罗那");
        ABBREVIATION_MAP.put("拜仁", "拜仁慕尼黑");
        ABBREVIATION_MAP.put("国米", "国际米兰");
        ABBREVIATION_MAP.put("米兰", "ac米兰");
        ABBREVIATION_MAP.put("尤文", "尤文图斯");
        ABBREVIATION_MAP.put("阿森纳", "阿森纳");
        ABBREVIATION_MAP.put("切尔西", "切尔西");
        ABBREVIATION_MAP.put("利物浦", "利物浦");
        ABBREVIATION_MAP.put("热刺", "托特纳姆热刺");
        ABBREVIATION_MAP.put("多特", "多特蒙德");
        ABBREVIATION_MAP.put("巴黎", "巴黎圣日耳曼");
        ABBREVIATION_MAP.put("马竞", "马德里竞技");
        ABBREVIATION_MAP.put("本菲卡", "本菲卡");
        ABBREVIATION_MAP.put("波尔图", "波尔图");
        ABBREVIATION_MAP.put("阿贾克斯", "阿贾克斯");
        ABBREVIATION_MAP.put("埃因霍温", "埃因霍温");
        ABBREVIATION_MAP.put("罗马", "罗马");
        ABBREVIATION_MAP.put("那不勒斯", "那不勒斯");
        ABBREVIATION_MAP.put("拉齐奥", "拉齐奥");
        ABBREVIATION_MAP.put("佛罗伦萨", "佛罗伦萨");
        ABBREVIATION_MAP.put("亚特兰大", "亚特兰大");
        ABBREVIATION_MAP.put("塞维利亚", "塞维利亚");
        ABBREVIATION_MAP.put("瓦伦西亚", "瓦伦西亚");
        ABBREVIATION_MAP.put("比利亚雷亚尔", "比利亚雷亚尔");
        ABBREVIATION_MAP.put("皇家社会", "皇家社会");
        ABBREVIATION_MAP.put("毕尔巴鄂", "毕尔巴鄂竞技");
        ABBREVIATION_MAP.put("西班牙人", "西班牙人");
        ABBREVIATION_MAP.put("赫塔菲", "赫塔菲");
        ABBREVIATION_MAP.put("莱万特", "莱万特");
        ABBREVIATION_MAP.put("格拉纳达", "格拉纳达");
        ABBREVIATION_MAP.put("奥萨苏纳", "奥萨苏纳");
        ABBREVIATION_MAP.put("塞尔塔", "塞尔塔");
        ABBREVIATION_MAP.put("阿拉维斯", "阿拉维斯");
        ABBREVIATION_MAP.put("贝蒂斯", "皇家贝蒂斯");
        ABBREVIATION_MAP.put("莱比锡", "莱比锡红牛");
        ABBREVIATION_MAP.put("勒沃库森", "勒沃库森");
        ABBREVIATION_MAP.put("门兴", "门兴格拉德巴赫");
        ABBREVIATION_MAP.put("法兰克福", "法兰克福");
        ABBREVIATION_MAP.put("沃尔夫斯堡", "沃尔夫斯堡");
        ABBREVIATION_MAP.put("不来梅", "云达不莱梅");
        ABBREVIATION_MAP.put("斯图加特", "斯图加特");
        ABBREVIATION_MAP.put("汉堡", "汉堡");
        ABBREVIATION_MAP.put("沙尔克", "沙尔克04");
        ABBREVIATION_MAP.put("科隆", "科隆");

        // ---------- 初始化音译分组 ----------
        List<Set<String>> transliterationGroups = Arrays.asList(
                // 元音/辅音变体
                new HashSet<>(Arrays.asList("洛", "罗", "诺", "骆", "络", "劳")),
                new HashSet<>(Arrays.asList("特", "德", "达", "塔", "泰", "太", "戴", "代", "特尔", "特雷", "德里")),
                new HashSet<>(Arrays.asList("斯", "思", "丝", "士", "什", "施", "史", "斯特", "斯坦", "斯蒂")),
                new HashSet<>(Arrays.asList("奇", "琪", "琦", "其", "齐", "祈", "基")),
                new HashSet<>(Arrays.asList("沃", "卧", "渥", "沃尔", "沃特", "沃德")),
                new HashSet<>(Arrays.asList("尔", "而", "耳", "埃尔", "艾尔", "阿尔")),
                new HashSet<>(Arrays.asList("夫", "弗", "芙", "富", "福", "弗尔", "弗雷")),
                new HashSet<>(Arrays.asList("克", "赫", "科尔", "卡尔", "克尔", "克森", "克利")),
                new HashSet<>(Arrays.asList("格", "格尔", "戈尔", "格莱", "格雷", "格里")),
                new HashSet<>(Arrays.asList("布", "布鲁", "布尔", "布拉", "布莱", "布朗", "布什")),
                new HashSet<>(Arrays.asList("兰", "蓝", "兰德", "朗", "兰斯")),
                new HashSet<>(Arrays.asList("伯", "博", "波", "布尔", "鲍", "伯特", "伯恩")),
                new HashSet<>(Arrays.asList("尼", "妮", "纳", "奈", "尼尔", "尼克", "尼姆")),
                new HashSet<>(Arrays.asList("卡", "咔", "卡尔", "卡里", "卡特", "卡文")),
                new HashSet<>(Arrays.asList("巴", "吧", "巴尔", "巴里", "巴西", "巴特")),
                new HashSet<>(Arrays.asList("马", "玛", "马尔", "马里", "马克", "马丁")),
                new HashSet<>(Arrays.asList("阿", "啊", "阿尔", "阿里", "阿拉", "阿斯")),
                new HashSet<>(Arrays.asList("伊", "依", "艾", "埃尔", "易", "伊尔")),
                new HashSet<>(Arrays.asList("奥", "澳", "奥尔", "奥斯", "奥拉", "奥特")),
                new HashSet<>(Arrays.asList("文", "温", "文德", "温特", "文森")),
                new HashSet<>(Arrays.asList("森", "申", "森特", "桑", "森德")),
                new HashSet<>(Arrays.asList("纳", "那", "纳尔", "纳斯", "纳特", "纳什")),
                new HashSet<>(Arrays.asList("索", "所", "索尔", "索斯", "索特", "索尼")),
                new HashSet<>(Arrays.asList("利", "里", "力", "莱", "雷", "赖", "勒", "雷斯")),
                new HashSet<>(Arrays.asList("姆", "穆", "穆尔", "穆勒")),
                new HashSet<>(Arrays.asList("普", "普尔", "普利", "普莱", "普斯")),
                new HashSet<>(Arrays.asList("拉", "腊", "拉尔", "拉斯", "拉特", "拉姆")),
                new HashSet<>(Arrays.asList("蒙", "门", "蒙特", "蒙德", "蒙斯")),
                new HashSet<>(Arrays.asList("萨", "沙", "萨尔", "萨姆", "萨特", "萨拉")),
                new HashSet<>(Arrays.asList("维", "威", "维尔", "维斯", "维克", "维奇")),
                new HashSet<>(Arrays.asList("迪", "蒂", "迪尔", "迪恩", "狄", "迪亚")),
                new HashSet<>(Arrays.asList("安", "昂", "安德", "安东", "安特")),
                new HashSet<>(Arrays.asList("托", "陶", "托尔", "托尼", "托特")),
                new HashSet<>(Arrays.asList("乔", "约", "乔治", "乔丹", "乔尔")),
                new HashSet<>(Arrays.asList("福", "夫", "富", "弗", "福德")),
                new HashSet<>(Arrays.asList("哈", "汉", "哈里", "哈特", "哈德")),
                new HashSet<>(Arrays.asList("贝", "倍", "贝尔", "贝利", "贝特")),
                new HashSet<>(Arrays.asList("塞", "西", "塞尔", "塞斯", "塞维")),
                new HashSet<>(Arrays.asList("丹", "但", "丹尼", "丹特", "丹斯")),
                new HashSet<>(Arrays.asList("菲", "费", "菲尔", "菲特", "费尔")),
                new HashSet<>(Arrays.asList("加", "嘉", "加尔", "加里", "加特")),
                new HashSet<>(Arrays.asList("科", "柯", "科尔", "科特", "科恩")),
                new HashSet<>(Arrays.asList("帕", "帕尔", "帕克", "帕特", "帕拉")),
                new HashSet<>(Arrays.asList("贾", "杰", "吉尔", "杰里", "杰斯")),
                new HashSet<>(Arrays.asList("梅", "美", "梅尔", "梅特", "梅斯")),
                new HashSet<>(Arrays.asList("甘", "冈", "甘特", "甘比", "甘德")),
                new HashSet<>(Arrays.asList("伦", "轮", "伦德", "伦纳", "伦斯")),
                new HashSet<>(Arrays.asList("韦", "维", "韦尔", "韦斯", "韦德")),
                new HashSet<>(Arrays.asList("米", "迷", "米尔", "米特", "米斯")),
                new HashSet<>(Arrays.asList("恩", "嗯", "恩德", "恩特", "恩斯")),
                new HashSet<>(Arrays.asList("莫", "摩", "摩尔", "莫特", "莫斯")),
                new HashSet<>(Arrays.asList("弗", "夫", "福", "弗尔", "弗雷", "弗里")),
                new HashSet<>(Arrays.asList("克", "赫", "格尔", "克尔", "克森", "克利")),
                new HashSet<>(Arrays.asList("彻", "切", "车", "席", "奇", "切尔", "切斯")),
                new HashSet<>(Arrays.asList("赫", "黑", "赫尔", "赫特", "赫斯")),
                new HashSet<>(Arrays.asList("希", "西", "希尔", "希特", "希斯")),
                new HashSet<>(Arrays.asList("霍", "赫", "霍尔", "霍特", "霍斯")),
                new HashSet<>(Arrays.asList("舒", "苏", "舒尔", "舒特", "舒斯")),
                new HashSet<>(Arrays.asList("鲁", "卢", "鲁尔", "鲁斯", "鲁特")),
                new HashSet<>(Arrays.asList("戈", "哥", "戈尔", "戈特", "戈斯")),
                new HashSet<>(Arrays.asList("耶", "也", "耶尔", "耶特")),
                new HashSet<>(Arrays.asList("瓦", "娃", "瓦尔", "瓦特", "瓦斯")),
                new HashSet<>(Arrays.asList("迈", "麦", "迈尔", "迈特", "迈克")),
                new HashSet<>(Arrays.asList("德", "特", "德尔", "德斯", "德克")),
                new HashSet<>(Arrays.asList("穆", "姆", "穆尔", "穆斯")),
                new HashSet<>(Arrays.asList("吉", "基", "吉尔", "吉斯")),
                new HashSet<>(Arrays.asList("尼", "妮", "尼尔", "尼科")),
                new HashSet<>(Arrays.asList("约", "乔", "约尔", "约特")),
                new HashSet<>(Arrays.asList("埃", "艾", "埃尔", "埃特", "埃斯")),
                new HashSet<>(Arrays.asList("沃", "瓦尔", "沃特", "沃克")),
                new HashSet<>(Arrays.asList("伯", "博", "伯特", "伯恩", "伯克")),
                new HashSet<>(Arrays.asList("克", "克尔", "克特", "克斯")),
                new HashSet<>(Arrays.asList("格", "格尔", "格特", "格斯")),
                new HashSet<>(Arrays.asList("斯", "斯特", "斯坦", "斯蒂", "斯顿")),
                new HashSet<>(Arrays.asList("姆", "穆", "姆斯", "姆特")),
                new HashSet<>(Arrays.asList("库", "古", "库尔", "库特")),
                new HashSet<>(Arrays.asList("尼", "妮", "尼尔", "尼特")),
                new HashSet<>(Arrays.asList("布", "布鲁", "布特", "布斯"))
        );

        // 构建音译映射
        for (Set<String> group : transliterationGroups) {
            String target = group.iterator().next();
            for (String source : group) {
                TRANSLITERATION_MAP.put(source, target);
            }
        }

        // ---------- 初始化移除后缀 ----------
        REMOVE_SUFFIXES.addAll(Arrays.asList(
                // 俱乐部/球队类型
                "足球俱乐部", "篮球俱乐部", "排球俱乐部", "体育俱乐部", "俱乐部",
                "足球队", "篮球队", "排球队", "球队", "队",
                "体育会", "竞技会", "体育协会", "竞技",
                "体育中心", "体育场", "训练基地",

                // 性别
                "女足", "女子", "women", "wfc", "lfc", "lady", "ladies",
                "男足", "男子",

                // 梯队/预备队
                "u23", "u22", "u21", "u20", "u19", "u18", "u17", "u16", "u15",
                "under23", "under21", "reserve", "reserves",
                "青年队", "预备队", "后备队", "希望队", "梯队",
                "二队", "三队", "四队", "五队", "六队", "七队", "八队", "九队", "十队",
                "b队", "c队", "d队", "e队",
                "b", "c", "d", "e", "2队", "3队", "4队", "5队",

                // 中文后缀（球队）
                "联队", "联合", "城", "镇", "市", "郡",
                "竞技", "森林", "公园", "花园", "广场",
                "流浪者", "漫游者", "旅行者",
                "热刺", "维拉", "玫瑰", "白玫瑰", "红玫瑰",
                "飞翼", "飞翔", "天马",
                "黑猫", "喜鹊", "金丝雀", "雄鸡", "蓝鸟",
                "红魔", "蓝军", "枪手", "兵工厂",
                "红军", "红队", "蓝队", "白队",

                // 英文后缀
                "fc", "f.c.", "sc", "s.c.", "afc", "a.f.c.", "ac", "cf", "ufc",
                "bsc", "brsc", "csc", "nsc", "ssc", "ysc",
                "united", "utd", "utd.",
                "city", "town", "ville", "boro", "borough",
                "athletic", "athl.", "wanderers", "wands", "rovers", "ro",
                "albion", "hotspur", "spurs", "villa", "forest", "park",
                "college", "university", "school", "academy",
                "old", "new", "north", "south", "east", "west",
                "central", "metro", "rangers", "celtic",
                "tigers", "lions", "hawks", "eagles", "bears", "wolves", "foxes",
                "bulls", "sharks", "dolphins", "falcons", "panthers",

                // 英文队名常见词
                "eagles", "eagle", "hawks", "hawk", "lions", "lion", "tigers", "tiger",
                "bears", "bear", "wolves", "wolf", "foxes", "fox", "bulls", "bull",
                "sharks", "shark", "dolphins", "dolphin", "falcons", "falcon",
                "panthers", "panther", "jaguars", "jaguar", "leopards", "leopard",
                "cheetahs", "cheetah", "vipers", "viper", "cobras", "cobra",
                "dragons", "dragon", "knights", "knight", "warriors", "warrior",
                "gladiators", "gladiator", "spartans", "spartan", "vikings", "viking",

                // 中文动物名
                "伊格斯", "伊格尔斯", "老鹰", "雄鹰", "鹰",
                "狮子", "老虎", "熊", "狼", "狐狸", "公牛", "鲨鱼", "海豚",
                "猎鹰", "黑豹", "美洲豹", "豹", "龙", "骑士", "勇士",
                "角斗士", "斯巴达", "维京", "海盗",

                // 地名/方向词
                "北", "南", "东", "西", "中", "上", "下",
                "north", "south", "east", "west", "central",
                "北部", "南部", "东部", "西部", "中部",

                // 其他常见词
                "联赛", "杯赛", "锦标赛", "冠军赛", "邀请赛",
                "league", "cup", "championship", "tournament", "invitational",
                "精英", "精英队", "顶级", "职业", "业余",
                "elite", "pro", "professional", "amateur",
                "学院", "学院队", "青年", "青少年",
                "academy", "youth", "junior", "senior",
                "预备", "预备役", "候补",
                "一线队", "一线", "主力", "替补",
                "发展", "发展队", "进步", "未来",
                "development", "future", "rising", "star",
                "明星", "之星", "明星队", "全明星",

                // 中文音译后缀
                "联", "合", "会", "社", "团",
                "者", "家", "人", "民",

                // 粤语/方言后缀
                "仔", "佬", "隊", "會", "聯",

                // 数字
                "one", "two", "three", "four", "five",
                "一", "二", "三", "四", "五",
                "第1", "第2", "第3", "第4", "第5"
        ));
    }

    // ==================== 核心匹配方法 ====================

    private static final double BET_SAME_TEAM_THRESHOLD = 0.35;
    /** 判定两场为同一对阵时，主队名称最低相似度 */
    private static final double EVENT_HOME_MATCH_THRESHOLD = 0.5;

    /**
     * 判断 A/B 两侧是否实际投注同一支球队（让球盘对冲场景）。
     * <p>不使用 isHome 槽位（跨站可能出现主客对调）。校验顺序：</p>
     * <ol>
     *   <li>投注队名字符串相似度</li>
     *   <li>赛事上下文：识别同一对阵（含主客对调），再判断两侧是否投注同一物理球队</li>
     * </ol>
     */
    public static boolean isBettingSamePhysicalTeam(String betTeamA, String betTeamB,
                                                    String eventNameA, String eventNameB) {
        if (StringUtils.isBlank(betTeamA) || StringUtils.isBlank(betTeamB)) {
            return false;
        }
        if (betTeamA.equals(betTeamB)) {
            return true;
        }

        if (calculateSimilarity(betTeamA, betTeamB) >= BET_SAME_TEAM_THRESHOLD) {
            return true;
        }

        if (StringUtils.isNotBlank(eventNameA) && StringUtils.isNotBlank(eventNameB)) {
            return isSamePhysicalTeamInMatchedEvents(eventNameA, betTeamA, eventNameB, betTeamB);
        }

        return false;
    }

    private enum MatchAlign { NORMAL, SWAPPED }

    /**
     * 同一对阵（含主客对调）下，判断 A/B 是否投注同一物理球队。
     * <ul>
     *   <li>主客一致：两侧同为「主队侧」或同为「客队侧」→ 同队</li>
     *   <li>主客对调：一侧主队、另一侧客队 → 同队（同一队在 A 为主、在 B 为客）</li>
     * </ul>
     */
    private static boolean isSamePhysicalTeamInMatchedEvents(String eventNameA, String betTeamA,
                                                             String eventNameB, String betTeamB) {
        EventPair pairA = parseEventPair(eventNameA);
        EventPair pairB = parseEventPair(eventNameB);
        if (pairA == null || pairB == null) {
            return false;
        }

        BetSide sideA = resolveBetSide(pairA, betTeamA);
        BetSide sideB = resolveBetSide(pairB, betTeamB);
        if (sideA == null || sideB == null) {
            return false;
        }

        MatchAlign align = detectMatchAlignment(pairA, pairB);
        if (align == null) {
            return false;
        }

        if (align == MatchAlign.NORMAL) {
            return sideA == sideB;
        }
        // 主客对调：A 的主 = B 的客，投注「A主/B客」或「A客/B主」为同一物理队
        return (sideA == BetSide.HOME && sideB == BetSide.AWAY)
                || (sideA == BetSide.AWAY && sideB == BetSide.HOME);
    }

    /**
     * 识别两场是否同一对阵。支持主客对调；客队译名差异时仅要求主队侧匹配（如 瓦埃勒 vs 维积利）。
     */
    private static MatchAlign detectMatchAlignment(EventPair a, EventPair b) {
        double hh = calculateSimilarity(a.home, b.home);
        double aa = calculateSimilarity(a.away, b.away);
        double hab = calculateSimilarity(a.home, b.away);
        double ahb = calculateSimilarity(a.away, b.home);

        if (hh >= EVENT_HOME_MATCH_THRESHOLD && aa >= EVENT_HOME_MATCH_THRESHOLD) {
            return MatchAlign.NORMAL;
        }
        if (hab >= EVENT_HOME_MATCH_THRESHOLD && ahb >= EVENT_HOME_MATCH_THRESHOLD) {
            return MatchAlign.SWAPPED;
        }
        // 主队侧已匹配、客队侧译名不同（如 瓦埃勒/维积利）仍视为同一对阵
        if (hh >= EVENT_HOME_MATCH_THRESHOLD && hab < EVENT_HOME_MATCH_THRESHOLD) {
            return MatchAlign.NORMAL;
        }
        // 仅 homeA~awayB 匹配 → 主客对调
        if (hab >= EVENT_HOME_MATCH_THRESHOLD && hh < EVENT_HOME_MATCH_THRESHOLD) {
            return MatchAlign.SWAPPED;
        }
        return null;
    }

    private static EventPair parseEventPair(String eventName) {
        if (StringUtils.isBlank(eventName)) {
            return null;
        }
        String[] separators = {" -vs- ", " -VS- ", " vs ", " VS "};
        for (String sep : separators) {
            int idx = eventName.indexOf(sep);
            if (idx >= 0) {
                EventPair pair = new EventPair();
                pair.home = eventName.substring(0, idx).trim();
                pair.away = eventName.substring(idx + sep.length()).trim();
                return pair;
            }
        }
        return null;
    }

    private static BetSide resolveBetSide(EventPair pair, String betTeam) {
        if (pair == null || StringUtils.isBlank(betTeam)) {
            return null;
        }
        if (isNameMatch(betTeam, pair.home)) {
            return BetSide.HOME;
        }
        if (isNameMatch(betTeam, pair.away)) {
            return BetSide.AWAY;
        }

        double homeSim = calculateSimilarity(betTeam, pair.home);
        double awaySim = calculateSimilarity(betTeam, pair.away);
        if (homeSim >= BET_SAME_TEAM_THRESHOLD && homeSim >= awaySim) {
            return BetSide.HOME;
        }
        if (awaySim >= BET_SAME_TEAM_THRESHOLD && awaySim > homeSim) {
            return BetSide.AWAY;
        }
        return null;
    }

    /** 名称相等或包含（用于赛事字符串中的主客队解析） */
    private static boolean isNameMatch(String betTeam, String eventTeam) {
        if (StringUtils.isBlank(eventTeam)) {
            return false;
        }
        if (betTeam.equals(eventTeam)) {
            return true;
        }
        return betTeam.contains(eventTeam) || eventTeam.contains(betTeam);
    }

    private enum BetSide { HOME, AWAY }

    private static class EventPair {
        String home;
        String away;
    }

    /**
     * 判断两个球队名称是否匹配
     */
    public static boolean isSameTeam(String name1, String name2) {
        return isSameTeam(name1, name2, LOOSE_THRESHOLD);
    }

    /**
     * 判断两个球队名称是否匹配（指定阈值）
     */
    public static boolean isSameTeam(String name1, String name2, double threshold) {
        if (name1 == null || name2 == null) {
            return false;
        }
        if (name1.equals(name2)) {
            return true;
        }

        double similarity = calculateSimilarity(name1, name2);
        return similarity >= threshold;
    }

    /**
     * 计算相似度
     */
    public static double calculateSimilarity(String name1, String name2) {
        if (name1 == null || name2 == null) {
            return 0.0;
        }
        if (name1.equals(name2)) {
            return 1.0;
        }

        // 1. 先展开缩写
        String expanded1 = expandAbbreviation(name1);
        String expanded2 = expandAbbreviation(name2);

        // 2. 规范化
        String norm1 = normalizeTeamName(expanded1);
        String norm2 = normalizeTeamName(expanded2);

        // 3. 标准化后完全相同
        if (norm1.equals(norm2)) {
            return 1.0;
        }

        // 4. 包含关系（带长度校验）
        if (containsWithCheck(norm1, norm2)) {
            return 0.95;
        }

        // 5. 首字匹配 + 长度校验（处理"曼 vs 曼彻斯特联"这类情况）
        if (hasFirstCharMatch(norm1, norm2) && Math.abs(norm1.length() - norm2.length()) > 3) {
            double baseSimilarity = TextSimilarity.similar(norm1, norm2);
            return Math.max(baseSimilarity, 0.75);
        }

        // 6. 编辑距离相似度
        double similarity = TextSimilarity.similar(norm1, norm2);

        // 7. 如果相似度较低，尝试高级匹配
        if (similarity < 0.7) {
            similarity = advancedMatch(norm1, norm2, similarity);
        }

        return similarity;
    }

    // ==================== 规范化方法 ====================

    /**
     * 展开缩写
     */
    private static String expandAbbreviation(String name) {
        if (StringUtils.isBlank(name)) {
            return name;
        }
        return ABBREVIATION_MAP.getOrDefault(name, name);
    }

    /**
     * 球队名称规范化
     */
    private static String normalizeTeamName(String name) {
        if (StringUtils.isBlank(name)) {
            return "";
        }

        String result = name
                .toLowerCase()
                .trim();

        // 1. 音译归一化
        result = normalizeTransliteration(result);

        // 2. 移除前缀
        result = removePrefixes(result);

        // 3. 移除后缀（按长度从长到短排序）
        result = removeSuffixes(result);

        // 4. 移除特殊符号
        result = result
                .replaceAll("[\\s\\-()（）\\[\\]{}〈〉《》「」『』]", "")
                .replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9]", "");

        // 5. 移除单字符（保留至少2个字符）
        if (result.length() >= 2) {
            result = result.replaceAll("\\b[a-z]\\b", "");
        }

        // 6. 去重连续相同字符
        result = result.replaceAll("(.)\\1{2,}", "$1$1");

        return result.trim();
    }

    /**
     * 音译归一化
     */
    private static String normalizeTransliteration(String text) {
        if (StringUtils.isBlank(text)) {
            return text;
        }

        StringBuilder result = new StringBuilder();
        for (char c : text.toCharArray()) {
            String key = String.valueOf(c);
            result.append(TRANSLITERATION_MAP.getOrDefault(key, key));
        }
        return result.toString();
    }

    /**
     * 移除前缀
     */
    private static String removePrefixes(String text) {
        if (StringUtils.isBlank(text)) {
            return text;
        }

        String result = text;
        List<String> prefixes = Arrays.asList("皇家", "real", "皇家马德里", "巴塞罗那");
        for (String prefix : prefixes) {
            if (result.startsWith(prefix.toLowerCase())) {
                result = result.substring(prefix.length()).trim();
            }
        }
        return result;
    }

    /**
     * 移除后缀
     */
    private static String removeSuffixes(String text) {
        if (StringUtils.isBlank(text)) {
            return text;
        }

        String result = text;

        // 按长度从长到短排序
        List<String> sortedSuffixes = REMOVE_SUFFIXES.stream()
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .collect(Collectors.toList());

        for (String suffix : sortedSuffixes) {
            String suffixLower = suffix.toLowerCase();
            if (result.endsWith(suffixLower)) {
                result = result.substring(0, result.length() - suffixLower.length()).trim();
            }
            if (result.endsWith(" " + suffixLower)) {
                result = result.substring(0, result.length() - suffixLower.length() - 1).trim();
            }
        }

        return result;
    }

    // ==================== 匹配辅助方法 ====================

    /**
     * 带长度校验的包含关系
     */
    private static boolean containsWithCheck(String s1, String s2) {
        if (s1.length() < 2 || s2.length() < 2) {
            return false;
        }

        if (s1.contains(s2)) {
            return Math.abs(s1.length() - s2.length()) <= 2;
        }
        if (s2.contains(s1)) {
            return Math.abs(s1.length() - s2.length()) <= 2;
        }

        return false;
    }

    /**
     * 首字符匹配
     */
    private static boolean hasFirstCharMatch(String s1, String s2) {
        if (s1.isEmpty() || s2.isEmpty()) {
            return false;
        }
        return s1.charAt(0) == s2.charAt(0);
    }

    /**
     * 高级匹配
     */
    private static double advancedMatch(String s1, String s2, double currentSimilarity) {
        // 1. 提取核心词
        String core1 = extractCoreName(s1);
        String core2 = extractCoreName(s2);

        if (!core1.isEmpty() && !core2.isEmpty()) {
            double coreSimilarity = TextSimilarity.similar(core1, core2);
            if (coreSimilarity > currentSimilarity) {
                return coreSimilarity;
            }
        }

        // 2. 缩写匹配
        if (isAbbreviationMatch(s1, s2)) {
            return Math.max(currentSimilarity, 0.85);
        }

        // 3. 音译差异
        if (isOnlyTransliterationDifference(s1, s2)) {
            return 0.85;
        }

        return currentSimilarity;
    }

    /**
     * 提取核心名称
     */
    private static String extractCoreName(String name) {
        String result = name;
        for (String suffix : REMOVE_SUFFIXES) {
            result = result.replaceAll(suffix, "");
        }
        return result.trim();
    }

    /**
     * 缩写匹配
     */
    private static boolean isAbbreviationMatch(String s1, String s2) {

        String abbr1 = getAbbreviation(s1);
        String abbr2 = getAbbreviation(s2);

        if (StrUtil.isBlank(abbr1) || StrUtil.isBlank(abbr2)) {
            return false;
        }

        return abbr1.equals(abbr2)
                || s1.equals(abbr2)
                || s2.equals(abbr1);
    }

    private static String getAbbreviation(String text) {
        StringBuilder abbr = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (Character.isUpperCase(c)) {
                abbr.append(c);
            }
        }
        return abbr.toString().toLowerCase();
    }

    /**
     * 音译差异检查
     */
    private static boolean isOnlyTransliterationDifference(String s1, String s2) {
        if (Math.abs(s1.length() - s2.length()) > 2) {
            return false;
        }

        if (Math.abs(s1.length() - s2.length()) == 1) {
            String shorter = s1.length() < s2.length() ? s1 : s2;
            String longer = s1.length() > s2.length() ? s1 : s2;
            return longer.startsWith(shorter) || longer.substring(1).equals(shorter);
        }

        return false;
    }

    // ==================== 批量匹配 ====================

    /**
     * 查找最佳匹配
     */
    public static String findBestMatch(String target, List<String> candidates) {
        if (target == null || candidates == null || candidates.isEmpty()) {
            return null;
        }

        String bestMatch = null;
        double bestScore = 0.0;

        for (String candidate : candidates) {
            double score = calculateSimilarity(target, candidate);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = candidate;
            }
        }

        return bestScore >= NORMAL_THRESHOLD ? bestMatch : null;
    }

    /**
     * 查找所有匹配
     */
    public static List<String> findAllMatches(String target, List<String> candidates) {
        return findAllMatches(target, candidates, NORMAL_THRESHOLD);
    }

    public static List<String> findAllMatches(String target, List<String> candidates, double threshold) {
        if (target == null || candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }

        return candidates.stream()
                .filter(c -> isSameTeam(target, c, threshold))
                .collect(Collectors.toList());
    }

    // ==================== 测试 ====================

    public static void main(String[] args) {
        // 测试用例
        testMatch("迪康塞普森", "康塞普西翁体育", 0.85);
        testMatch("扯犊子吧", "拉斐拉竞技", 0.85);
        testMatch("啊啊是的", "拉斐拉竞技", 0.85);
        testMatch("奥达斯", "奥达克斯意大利人", 0.85);
        testMatch("昆士兰狮队", "昆士兰狮队足球俱乐部", 1.0);
        testMatch("卡帕拉巴(女)", "卡帕拉巴女足", 1.0);
        testMatch("上海海港B队", "上海海港二队", 1.0);
        testMatch("埃奇沃斯伊格斯", "埃奇沃思", 0.85);
        testMatch("帕洛特", "帕罗", 0.85);
        testMatch("曼联", "曼彻斯特联", 0.85);
        testMatch("曼城", "曼彻斯特城", 0.85);
        testMatch("皇家马德里", "皇马", 0.85);
        testMatch("切尔西", "车路士", 0.85);
        testMatch("巴萨", "巴塞罗那", 0.85);
        testMatch("拜仁", "拜仁慕尼黑", 0.85);
        testMatch("国米", "国际米兰", 0.85);
        testMatch("尤文", "尤文图斯", 0.85);
        testMatch("热刺", "托特纳姆热刺", 0.85);
        testMatch("马竞", "马德里竞技", 0.85);
        testMatch("多特", "多特蒙德", 0.85);
        testMatch("巴黎", "巴黎圣日耳曼", 0.85);
        testMatch("蒙特维多国民队(后备)", "国民足球俱乐部", 0.85);
        testMatch("象牙海岸", "科特迪瓦", 0.85);
        testMatch("古亚伯MT", "库亚巴", 0.85);
        testMatch(" 隆迪那PR", "隆德里纳", 0.85);
        testMatch(" IFK史柯瓦德", "IFK舍夫德", 0.85);
        testMatch(" 尼科平斯", "尼雪平", 0.85);
        testMatch(" 纳卡伊利里亚", "纳卡", 0.85);
        testMatch("瓦埃勒", "维积利", 0.25);
        // 同队音译差异 + 主客一致
        boolean sameNormal = isBettingSamePhysicalTeam("瓦埃勒", "维积利",
                "埃斯比约 -vs- 瓦埃勒", "埃斯比约(中) -vs- 维积利");
        double simi = calculateSimilarity("瓦埃勒", "维积利");
        System.out.print(simi);
        System.out.printf("瓦埃勒 vs 维积利（主客一致）同队检测: %s (期望 true)%n", sameNormal);
        // 主客对调：同一物理队在 A 为客、在 B 为主 → 应识别为同队
        boolean sameSwapped = isBettingSamePhysicalTeam("瓦埃勒", "维积利",
                "埃斯比约 -vs- 瓦埃勒", "维积利 -vs- 埃斯比约(中)");
        System.out.printf("瓦埃勒 vs 维积利（主客对调）同队检测: %s (期望 true)%n", sameSwapped);
        // 正确对冲（主客对调）：A 客 Y、B 主 Y 的对手 X → 不应判同队
        boolean correctHedge = isBettingSamePhysicalTeam("瓦埃勒", "埃斯比约(中)",
                "埃斯比约 -vs- 瓦埃勒", "维积利 -vs- 埃斯比约(中)");
        System.out.printf("主客对调正确对冲: %s (期望 false)%n", correctHedge);


        boolean sameNormal1 = isBettingSamePhysicalTeam("武汉三镇", "Wuhan Three Towns",
                "北京国安 -vs- 武汉三镇", "北京国安 -vs- Wuhan Three Towns");
        System.out.printf("北京国安 vs Wuhan Three Towns（主客一致）同队检测: %s (期望 true)%n", sameNormal1);
        boolean sameNormal2 = isBettingSamePhysicalTeam("武汉三镇", "Wuhan Three Towns",
                "北京国安 -vs- 武汉三镇", "Wuhan Three Towns -vs- 北京国安");
        System.out.printf("北京国安 vs Wuhan Three Towns（主客一致）同队检测: %s (期望 true)%n", sameNormal1);

        boolean sameNormal3 = isBettingSamePhysicalTeam("霍森斯", "洛斯查兰特(中)",
                "斯查兰特 -vs- 霍森斯", "洛斯查兰特(中) -vs- AC侯森斯");
        System.out.printf("斯查兰特 -vs- 霍森斯（主客一致）同队检测: %s (期望 true)%n", sameNormal1);
    }

    private static void testMatch(String name1, String name2, double expected) {
        double similarity = calculateSimilarity(name1, name2);
        boolean isMatch = isSameTeam(name1, name2);
        String status = isMatch ? "✅" : "❌";
        System.out.printf("%s 「%s」vs「%s」: 相似度=%.4f, 匹配=%s, 期望>=%.2f%n%n",
                status, name1, name2, similarity, isMatch, expected);
    }
}