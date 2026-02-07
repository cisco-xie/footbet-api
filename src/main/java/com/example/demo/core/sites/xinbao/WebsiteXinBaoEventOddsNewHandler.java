package com.example.demo.core.sites.xinbao;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.XmlUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.constants.Constants;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.common.enmu.XinBaoOddsFormatType;
import com.example.demo.common.enmu.ZhiBoSchedulesType;
import com.example.demo.config.OkHttpProxyDispatcher;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import javax.xml.xpath.XPathConstants;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * 智博网站 - 赛事列表 API具体实现 用于扫水时获取最新的赔率
 */
@Slf4j
@Component
public class WebsiteXinBaoEventOddsNewHandler implements ApiHandler {

    private final OkHttpProxyDispatcher dispatcher;
    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteXinBaoEventOddsNewHandler(OkHttpProxyDispatcher dispatcher, WebsiteService websiteService, ApiUrlService apiUrlService) {
        this.dispatcher = dispatcher;
        this.websiteService = websiteService;
        this.apiUrlService = apiUrlService;
    }

    /**
     * 构建请求头
     * @param params 请求参数
     * @return HttpEntity 请求体
     */
    @Override
    public Map<String, String> buildHeaders(JSONObject params) {
        // 构造请求头
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("content-type", "application/x-www-form-urlencoded");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,pt-BR;q=0.8,pt;q=0.7");
        headers.put("Connection", "keep-alive");
        headers.put("Sec-Fetch-Dest", "empty");
        headers.put("Sec-Fetch-Mode", "cors");
        headers.put("Sec-Fetch-Site", "same-origin");
        headers.put("sec-ch-ua", Constants.SEC_CH_UA);
        headers.put("User-Agent", Constants.USER_AGENT);
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");

        return headers;
    }

    /**
     * 构建请求体
     * @param params 请求参数
     * @return HttpEntity 请求体
     */
    @Override
    public String buildRequest(JSONObject params) {
        String showType;  // 滚球赛事
        String rType;  // 滚球赛事
        String filter;
        if (ZhiBoSchedulesType.LIVESCHEDULE.getId() == params.getInt("showType")) {
            showType = "live";  // 滚球赛事
            rType = "rrnou";  // 滚球赛事
            filter = "RB";
        } else {
            showType = "today";  // 今日赛事
            rType = "rnou";  // 今日赛事
            filter = "FT";
        }
        // 构造请求体
        return String.format("p=get_game_list&uid=%s&ver=%s&langx=zh-cn&gtype=ft&showtype=%s&rtype=%s&ltype=3&filter=%s&cupFantasy=N&sorttype=L&isFantasy=N&ts=%s",
                params.getStr("uid"),
                Constants.VER,
                showType,
                rType,
                filter,
                System.currentTimeMillis()
        );
    }
    /**
     * 从比赛时间字符串中提取分钟数或中场标识。
     *
     * 示例：
     *  - "1H^02:54" -> "02"
     *  - "2H^62:30" -> "62"
     *  - "MTIME^HT" -> "中场"
     *  - "FT^90:00" -> "90"
     *
     * 说明：
     * 1. 若为中场（包含 "MTIME" 或 "HT"），返回 "中场"
     * 2. 否则提取 "^" 后时间的分钟部分（冒号前的数字）
     * 3. 非法或空值返回空字符串 ""
     */
    public static String extractMatchMinute(String reTimeSet) {
        if (reTimeSet == null || reTimeSet.isEmpty()) {
            return "";
        }

        // 统一格式
        String s = reTimeSet.trim().toUpperCase(Locale.ROOT);
        if (s.contains("MTIME") || s.endsWith("^HT") || s.contains("^HT")) {
            return "中场";
        }

        // 正常格式：1H^02:54 或 2H^62:30
        String[] parts = s.split("\\^");
        if (parts.length > 1) {
            String[] timeParts = parts[1].split(":");
            if (timeParts.length > 0) {
                // 只保留数字部分（去除可能的非数字字符）
                String minuteStr = timeParts[0].replaceAll("\\D", "");
                return minuteStr;
            }
        }

        return "";
    }

    // ===== 示例测试 =====
    public static void main(String[] args) {
        String[] tests = {
                "1H^02:54",
                "2H^62:30",
                "MTIME^HT",
                "FT^90:00",
                "HT",
                "MTIME^ht",
                "1H^--:--",
                "1H^"
        };

        for (String t : tests) {
            System.out.printf("%-12s -> %s%n", t, extractMatchMinute(t));
        }

        System.out.println(getMiddleValue("3/3.5"));
        System.out.println(getRatio("3/3.5", "主队"));
    }

    /**
     * 解析响应体
     * @param response 响应内容
     * @return 解析后的数据
     */
    @Override
    public JSONObject parseResponse(JSONObject params, OkHttpProxyDispatcher.HttpResult response) {
        // 中文注释：解析响应并返回目标格式（league -> events -> event）
        JSONObject responseJson = new JSONObject();
        if (response.getStatus() != 200) {
            responseJson.putOpt("success", false);
            responseJson.putOpt("msg", "账户登录失效");
            return responseJson;
        }

        Document docResult = XmlUtil.readXML(response.getBody());
        Object original = XmlUtil.getByXPath("//serverresponse/original", docResult, XPathConstants.STRING);
        if (ObjectUtil.isEmpty(original)) {
            responseJson.putOpt("success", false);
            responseJson.putOpt("msg", "获取账户赛事失败");
            return responseJson;
        }

        // oddsFormatType 由上游 params 提供（用于赔率转换）
        String oddsFormatType = params.getStr("oddsFormatType");
        JSONObject originalJson = JSONUtil.parseObj(original);
        Map<String, JSONObject> leagueMap = new LinkedHashMap<>(); // 保持联赛顺序

        // 遍历原始数据，每条条目生成一个 event（即一场比赛）
        originalJson.entrySet().stream()
            .sorted(Comparator.comparing(Map.Entry::getKey)) // 按 key 升序
            .forEach(entry -> {
                JSONObject gameJson = JSONUtil.parseObj(entry.getValue());

                String lid = gameJson.getStr("LID");
                String gid = gameJson.getStr("GID");    // 全场盘口 id (用于 letBall / overSize 的 id 字段)
                String hgid = gameJson.getStr("HGID");  // 半场盘口 id
                String ecid = gameJson.getStr("ECID");  // 事件唯一 id（示例中用于 event.id）
                String leagueName = gameJson.getStr("LEAGUE");
                String home = gameJson.getStr("TEAM_H");
                String away = gameJson.getStr("TEAM_C");
                String score = gameJson.getStr("SCORE_H", "") + "-" + gameJson.getStr("SCORE_C", "");
                String session = gameJson.getStr("NOW_MODEL");
                String midfield = gameJson.getStr("MIDFIELD");
                String reTimeSet = gameJson.getStr("RETIMESET");

                // session -> 显示值映射（HT/FT/其它）
                String sessionValue;
                if ("HT".equals(session)) {
                    sessionValue = "1H";
                } else if ("FT".equals(session)) {
                    sessionValue = "2H";
                } else {
                    sessionValue = "HT";
                }
                if ("Y".equals(midfield)) sessionValue = "HT";

                // 获取或创建联赛容器（保持顺序）
                JSONObject league = leagueMap.computeIfAbsent(lid, k -> {
                    JSONObject obj = new JSONObject();
                    obj.putOpt("id", lid);
                    obj.putOpt("ecid", ecid);
                    obj.putOpt("league", leagueName);
                    obj.putOpt("events", new JSONArray());
                    return obj;
                });

                // 查找是否已存在同 ecid 的 event（合并逻辑）
                JSONArray events = league.getJSONArray("events");
                JSONObject event = findEventById(events, ecid);

                if (event == null) {
                    // 不存在则创建新 event（注意：不在此处无条件覆盖 fullCourt / firstHalf）
                    event = new JSONObject();
                    event.putOpt("id", ecid);             // 使用 ecid 作为 event.id（可按需改）
                    event.putOpt("name", home + " -vs- " + away);
                    event.putOpt("homeTeam", home);
                    event.putOpt("awayTeam", away);
                    event.putOpt("score", score);
                    event.putOpt("session", sessionValue);
                    event.putOpt("reTime", extractMatchMinute(reTimeSet));
                    // 不要用 new JSONObject() 覆盖已有节点，parseOddsBlock 会按需创建子容器
                    // event.putOpt("fullCourt", new JSONObject());
                    // event.putOpt("firstHalf", new JSONObject());

                    events.add(event);
                }

                // 解析盘口并写入 event（包含 fullCourt 与 firstHalf）
                parseOddsBlock(gameJson, gid, hgid, oddsFormatType, event);

            });

        // 构造最终响应
        JSONArray data = new JSONArray();
        data.addAll(leagueMap.values());

        responseJson.putOpt("durationMs", response.getDurationMs());
        responseJson.putOpt("success", true);
        responseJson.putOpt("code", 0);
        responseJson.putOpt("msg", "成功");
        responseJson.putOpt("leagues", data);
        return responseJson;
    }

    /**
     * 将盘口写入到 event 的 fullCourt / firstHalf 节点中，输出结构与你提供的格式一致。
     *
     * 关键规则：
     *  - letBall.up = 上盘（让球方），letBall.down = 下盘（受让方）
     *  - 判定哪方为上盘：以 handicap 字符串是否包含前缀 "-" 为准（例如 "-0.5" 或 "-0.5 / 1"）
     *    带 "-" 的一方视为让球方（上盘），不带 "-" 的一方视为受让方（下盘）。
     *  - overSize.big / small 保持原有逻辑（主队通常映射到 small，客队映射到 big，沿用原有代码习惯）
     */
    private void parseOddsBlock(JSONObject gameJson, String gid, String hgid, String oddsFormatType, JSONObject event) {
        // 原始字段 key（来源于上游数据）
        String ratioFull = "RATIO_RE";
        String keyHomeOddsFullLetBall = "IOR_REH";
        String keyAwayOddsFullLetBall = "IOR_REC";

        String homeRatioFullOverSize = "RATIO_ROUO";
        String awayRatioFullOverSize = "RATIO_ROUU";
        String keyHomeOddsFullOverSize = "IOR_ROUC";
        String keyAwayOddsFullOverSize = "IOR_ROUH";

        String ratioFirst = "RATIO_HRE";
        String keyHomeOddsFirstLetBall = "IOR_HREH";
        String keyAwayOddsFirstLetBall = "IOR_HREC";

        String homeRatioFirstOverSize = "RATIO_HROUO";
        String awayRatioFirstOverSize = "RATIO_HROUU";
        String keyHomeOddsFirstOverSize = "IOR_HROUC";
        String keyAwayOddsFirstOverSize = "IOR_HROUH";

        // ---------- 确保 fullCourt / firstHalf 容器存在（若已存在则复用） ----------
        JSONObject fullCourt;
        if (event.containsKey("fullCourt") && event.get("fullCourt") instanceof JSONObject) {
            fullCourt = event.getJSONObject("fullCourt");
        } else {
            fullCourt = new JSONObject();
            event.putOpt("fullCourt", fullCourt);
        }

        JSONObject firstHalf;
        if (event.containsKey("firstHalf") && event.get("firstHalf") instanceof JSONObject) {
            firstHalf = event.getJSONObject("firstHalf");
        } else {
            firstHalf = new JSONObject();
            event.putOpt("firstHalf", firstHalf);
        }

        // ---------------- 全场让球：letBall.up/up 放置规则（以 handicap 是否带 '-' 判定上/下盘） ----------------
        if ((gameJson.containsKey(keyHomeOddsFullLetBall) && StringUtils.isNotBlank(gameJson.getStr(keyHomeOddsFullLetBall)))
                || (gameJson.containsKey(keyAwayOddsFullLetBall) && StringUtils.isNotBlank(gameJson.getStr(keyAwayOddsFullLetBall)))) {

            // 先计算主/客的 handicap 表示（保留原逻辑，根据 STRONG 决定谁让球）
            String homeHandicap;
            String awayHandicap;
            if ("0".equals(gameJson.getStr(ratioFull))) {
                homeHandicap = gameJson.getStr(ratioFull);
                awayHandicap = gameJson.getStr(ratioFull);
            } else if ("H".equals(gameJson.getStr("STRONG"))) {
                homeHandicap = "-" + gameJson.getStr(ratioFull);
                awayHandicap = gameJson.getStr(ratioFull);
            } else if ("C".equals(gameJson.getStr("STRONG"))) {
                homeHandicap = gameJson.getStr(ratioFull);
                awayHandicap = "-" + gameJson.getStr(ratioFull);
            } else {
                homeHandicap = gameJson.getStr(ratioFull);
                awayHandicap = gameJson.getStr(ratioFull);
            }

            // 确保 fullCourt.letBall.up/down 容器存在
            JSONObject letBall = (JSONObject) fullCourt.computeIfAbsent("letBall", k -> new JSONObject());
            JSONObject up = (JSONObject) letBall.computeIfAbsent("up", k -> new JSONObject());     // 上盘（让球方）
            JSONObject down = (JSONObject) letBall.computeIfAbsent("down", k -> new JSONObject()); // 下盘（受让方）

            // 处理主队 letBall（如果有赔率）
            if (gameJson.containsKey(keyHomeOddsFullLetBall) && StringUtils.isNotBlank(gameJson.getStr(keyHomeOddsFullLetBall))) {
                String odds = calculateOddsValue(oddsFormatType, gameJson.getDouble(keyHomeOddsFullLetBall));
                JSONObject item = new JSONObject();
                item.putOpt("id", gid);
                item.putOpt("odds", odds);
                item.putOpt("oddFType", oddsFormatType);
                item.putOpt("gtype", gameJson.getStr("MT_GTYPE"));
                item.putOpt("wtype", "RE");
                item.putOpt("rtype", "REH");           // 主队对应 REH（示例惯例）
                item.putOpt("choseTeam", "H");         // 主队为 H
                // con 与 ratio 的符号约定：让球方为正（getMiddleValue），受让方为负（-Math.abs(...)）
                item.putOpt("con", getMiddleValue(homeHandicap));
                item.putOpt("ratio", getRatio(ratioFull, "主队"));
                item.putOpt("handicap", homeHandicap);

                // wall：上盘/下盘的展示习惯（上盘用 hanging，下盘用 foot）
                // 如果 homeHandicap 带 '-'，说明主队是上盘（让球方），放入 up；否则放入 down
                /*if (homeHandicap.equals("0") && "C".equals(gameJson.getStr("STRONG"))) {
                    item.putOpt("wall", "hanging");
                    up.putOpt(getHandicapRange(homeHandicap), item);
                } else if (homeHandicap.equals("0") && "H".equals(gameJson.getStr("STRONG"))) {
                    item.putOpt("wall", "foot");
                    down.putOpt(getHandicapRange(homeHandicap), item);
                }*/
                if (homeHandicap.equals("0")) {
                    item.putOpt("wall", "foot");
                    up.putOpt(getHandicapRange(homeHandicap), item);
                } else if (homeHandicap.startsWith("-")) {
                    item.putOpt("wall", "hanging");
                    up.putOpt(getHandicapRange(homeHandicap), item);
                } else {
                    item.putOpt("wall", "foot");
                    // 主队为受让方，con/ratio 需要为负表示受让（覆盖前面 con 的正值）
                    item.putOpt("con", -Math.abs(getMiddleValue(homeHandicap)));
                    item.putOpt("ratio", -getRatio(ratioFull, "主队"));
                    down.putOpt(getHandicapRange(homeHandicap), item);
                }
            }

            // 处理客队 letBall（如果有赔率）
            if (gameJson.containsKey(keyAwayOddsFullLetBall) && StringUtils.isNotBlank(gameJson.getStr(keyAwayOddsFullLetBall))) {
                String odds = calculateOddsValue(oddsFormatType, gameJson.getDouble(keyAwayOddsFullLetBall));
                JSONObject item = new JSONObject();
                item.putOpt("id", gid);
                item.putOpt("odds", odds);
                item.putOpt("oddFType", oddsFormatType);
                item.putOpt("gtype", gameJson.getStr("MT_GTYPE"));
                item.putOpt("wtype", "RE");
                item.putOpt("rtype", "REC");           // 客队对应 REC（示例惯例）
                item.putOpt("choseTeam", "C");         // 客队为 C
                item.putOpt("con", getMiddleValue(awayHandicap));
                item.putOpt("ratio", getRatio(ratioFull, "客队"));
                item.putOpt("handicap", awayHandicap);

                // 如果 awayHandicap 带 '-'，说明客队是上盘（让球方），放入 up；否则放入 down
                /*if (awayHandicap.equals("0") && "C".equals(gameJson.getStr("STRONG"))) {
                    item.putOpt("wall", "foot");
                    down.putOpt(getHandicapRange(awayHandicap), item);
                } else if (awayHandicap.equals("0") && "H".equals(gameJson.getStr("STRONG"))) {
                    item.putOpt("wall", "hanging");
                    up.putOpt(getHandicapRange(awayHandicap), item);
                } */
                if (homeHandicap.equals("0")) {
                    item.putOpt("wall", "foot");
                    down.putOpt(getHandicapRange(awayHandicap), item);
                } else if (awayHandicap.startsWith("-")) {
                    item.putOpt("wall", "hanging");
                    up.putOpt(getHandicapRange(awayHandicap), item);
                } else {
                    item.putOpt("wall", "foot");
                    item.putOpt("con", -Math.abs(getMiddleValue(awayHandicap)));
                    item.putOpt("ratio", -getRatio(ratioFull, "客队"));
                    down.putOpt(getHandicapRange(awayHandicap), item);
                }
            }
        }

        // ---------------- 全场大小：overSize.big / small ----------------
        if ((gameJson.containsKey(keyHomeOddsFullOverSize) && StringUtils.isNotBlank(gameJson.getStr(keyHomeOddsFullOverSize)))
                || (gameJson.containsKey(keyAwayOddsFullOverSize) && StringUtils.isNotBlank(gameJson.getStr(keyAwayOddsFullOverSize)))) {

            JSONObject overSize = (JSONObject) fullCourt.computeIfAbsent("overSize", k -> new JSONObject());
            JSONObject big = (JSONObject) overSize.computeIfAbsent("big", k -> new JSONObject());
            JSONObject small = (JSONObject) overSize.computeIfAbsent("small", k -> new JSONObject());

            // 主队大小 -> big（示例中主队对应 big）
            if (gameJson.containsKey(keyHomeOddsFullOverSize) && StringUtils.isNotBlank(gameJson.getStr(keyHomeOddsFullOverSize))) {
                String ratio = gameJson.getStr(homeRatioFullOverSize);
                String odds = calculateOddsValue(oddsFormatType, gameJson.getDouble(keyHomeOddsFullOverSize));
                JSONObject item = new JSONObject();
                item.putOpt("id", gid);
                item.putOpt("odds", odds);
                item.putOpt("oddFType", oddsFormatType);
                item.putOpt("gtype", gameJson.getStr("MT_GTYPE"));
                item.putOpt("wtype", "ROU");
                item.putOpt("rtype", "ROUH");
                item.putOpt("choseTeam", "C");
                item.putOpt("con", -Math.abs(getMiddleValue(ratio)));
                item.putOpt("ratio", -getRatio(ratio, "大"));
                item.putOpt("handicap", ratio);

                big.putOpt(getHandicapRange(ratio), item);
            }

            // 客队大小 -> small（示例中客队对应 small）
            if (gameJson.containsKey(keyAwayOddsFullOverSize) && StringUtils.isNotBlank(gameJson.getStr(keyAwayOddsFullOverSize))) {
                String ratio = gameJson.getStr(awayRatioFullOverSize);
                String odds = calculateOddsValue(oddsFormatType, gameJson.getDouble(keyAwayOddsFullOverSize));
                JSONObject item = new JSONObject();
                item.putOpt("id", gid);
                item.putOpt("odds", odds);
                item.putOpt("oddFType", oddsFormatType);
                item.putOpt("gtype", gameJson.getStr("MT_GTYPE"));
                item.putOpt("wtype", "ROU");
                item.putOpt("rtype", "ROUC");
                item.putOpt("choseTeam", "H");
                item.putOpt("con", getMiddleValue(ratio));
                item.putOpt("ratio", getRatio(ratio, "小"));
                item.putOpt("handicap", ratio);

                small.putOpt(getHandicapRange(ratio), item);
            }
        }

        // ---------------- 半场让球：firstHalf.letBall.up/down（规则同全场，让/受以 '-' 判定） ----------------
        if ((gameJson.containsKey(keyHomeOddsFirstLetBall) && StringUtils.isNotBlank(gameJson.getStr(keyHomeOddsFirstLetBall)))
                || (gameJson.containsKey(keyAwayOddsFirstLetBall) && StringUtils.isNotBlank(gameJson.getStr(keyAwayOddsFirstLetBall)))) {

            // 计算半场主/客 handicap（根据 HSTRONG 字段）
            String homeHandicap;
            String awayHandicap;
            if ("0".equals(gameJson.getStr(ratioFirst))) {
                homeHandicap = gameJson.getStr(ratioFirst);
                awayHandicap = gameJson.getStr(ratioFirst);
            } else if ("H".equals(gameJson.getStr("HSTRONG"))) {
                homeHandicap = "-" + gameJson.getStr(ratioFirst);
                awayHandicap = gameJson.getStr(ratioFirst);
            } else if ("C".equals(gameJson.getStr("HSTRONG"))) {
                homeHandicap = gameJson.getStr(ratioFirst);
                awayHandicap = "-" + gameJson.getStr(ratioFirst);
            } else {
                homeHandicap = gameJson.getStr(ratioFirst);
                awayHandicap = gameJson.getStr(ratioFirst);
            }

            JSONObject letBall = (JSONObject) firstHalf.computeIfAbsent("letBall", k -> new JSONObject());
            JSONObject up = (JSONObject) letBall.computeIfAbsent("up", k -> new JSONObject());
            JSONObject down = (JSONObject) letBall.computeIfAbsent("down", k -> new JSONObject());

            // 主队半场 letBall
            if (gameJson.containsKey(keyHomeOddsFirstLetBall) && StringUtils.isNotBlank(gameJson.getStr(keyHomeOddsFirstLetBall))) {
                String odds = calculateOddsValue(oddsFormatType, gameJson.getDouble(keyHomeOddsFirstLetBall));
                JSONObject item = new JSONObject();
                item.putOpt("id", hgid);
                item.putOpt("odds", odds);
                item.putOpt("oddFType", oddsFormatType);
                item.putOpt("gtype", gameJson.getStr("MT_GTYPE"));
                item.putOpt("wtype", "HRE");
                item.putOpt("rtype", "HREH");
                item.putOpt("choseTeam", "H");
                item.putOpt("con", getMiddleValue(homeHandicap));
                item.putOpt("ratio", getRatio(gameJson.getStr(ratioFirst), "主队"));
                item.putOpt("handicap", homeHandicap);

                /*if (homeHandicap.equals("0") && "C".equals(gameJson.getStr("STRONG"))) {
                    item.putOpt("wall", "hanging");
                    up.putOpt(getHandicapRange(homeHandicap), item);
                } else if (homeHandicap.equals("0") && "H".equals(gameJson.getStr("STRONG"))) {
                    item.putOpt("wall", "foot");
                    down.putOpt(getHandicapRange(homeHandicap), item);
                }*/
                if (homeHandicap.equals("0")) {
                    item.putOpt("wall", "foot");
                    up.putOpt(getHandicapRange(homeHandicap), item);
                } else if (homeHandicap.startsWith("-")) {
                    item.putOpt("wall", "hanging");
                    up.putOpt(getHandicapRange(homeHandicap), item);
                } else {
                    item.putOpt("wall", "foot");
                    item.putOpt("con", -Math.abs(getMiddleValue(homeHandicap)));
                    item.putOpt("ratio", -getRatio(gameJson.getStr(ratioFirst), "主队"));
                    down.putOpt(getHandicapRange(homeHandicap), item);
                }
            }

            // 客队半场 letBall
            if (gameJson.containsKey(keyAwayOddsFirstLetBall) && StringUtils.isNotBlank(gameJson.getStr(keyAwayOddsFirstLetBall))) {
                String odds = calculateOddsValue(oddsFormatType, gameJson.getDouble(keyAwayOddsFirstLetBall));
                JSONObject item = new JSONObject();
                item.putOpt("id", hgid);
                item.putOpt("odds", odds);
                item.putOpt("oddFType", oddsFormatType);
                item.putOpt("gtype", gameJson.getStr("MT_GTYPE"));
                item.putOpt("wtype", "HRE");
                item.putOpt("rtype", "HREC");
                item.putOpt("choseTeam", "C");
                item.putOpt("con", getMiddleValue(awayHandicap));
                item.putOpt("ratio", getRatio(gameJson.getStr(ratioFirst), "客队"));
                item.putOpt("handicap", awayHandicap);

                /*if (awayHandicap.equals("0") && "C".equals(gameJson.getStr("STRONG"))) {
                    item.putOpt("wall", "foot");
                    down.putOpt(getHandicapRange(awayHandicap), item);
                } else if (awayHandicap.equals("0") && "H".equals(gameJson.getStr("STRONG"))) {
                    item.putOpt("wall", "hanging");
                    up.putOpt(getHandicapRange(awayHandicap), item);
                }*/
                if (homeHandicap.equals("0")) {
                    item.putOpt("wall", "foot");
                    down.putOpt(getHandicapRange(awayHandicap), item);
                } else if (awayHandicap.startsWith("-")) {
                    item.putOpt("wall", "hanging");
                    up.putOpt(getHandicapRange(awayHandicap), item);
                } else {
                    item.putOpt("wall", "foot");
                    item.putOpt("con", -Math.abs(getMiddleValue(awayHandicap)));
                    item.putOpt("ratio", -getRatio(gameJson.getStr(ratioFirst), "客队"));
                    down.putOpt(getHandicapRange(awayHandicap), item);
                }
            }
        }

        // ---------------- 半场大小（firstHalf.overSize.big / small） ----------------
        if ((gameJson.containsKey(keyHomeOddsFirstOverSize) && StringUtils.isNotBlank(gameJson.getStr(keyHomeOddsFirstOverSize)))
                || (gameJson.containsKey(keyAwayOddsFirstOverSize) && StringUtils.isNotBlank(gameJson.getStr(keyAwayOddsFirstOverSize)))) {

            JSONObject overSize = (JSONObject) firstHalf.computeIfAbsent("overSize", k -> new JSONObject());
            JSONObject big = (JSONObject) overSize.computeIfAbsent("big", k -> new JSONObject());
            JSONObject small = (JSONObject) overSize.computeIfAbsent("small", k -> new JSONObject());

            // 主队半场大小 -> big
            if (gameJson.containsKey(keyHomeOddsFirstOverSize) && StringUtils.isNotBlank(gameJson.getStr(keyHomeOddsFirstOverSize))) {
                String ratio = gameJson.getStr(homeRatioFirstOverSize);
                String odds = calculateOddsValue(oddsFormatType, gameJson.getDouble(keyHomeOddsFirstOverSize));
                JSONObject item = new JSONObject();
                item.putOpt("id", hgid);
                item.putOpt("odds", odds);
                item.putOpt("oddFType", oddsFormatType);
                item.putOpt("gtype", gameJson.getStr("MT_GTYPE"));
                item.putOpt("wtype", "HROU");
                item.putOpt("rtype", "HROUH");
                item.putOpt("choseTeam", "C");
                item.putOpt("con", -Math.abs(getMiddleValue(ratio)));
                item.putOpt("ratio", -getRatio(ratio, "大"));
                item.putOpt("handicap", ratio);

                big.putOpt(getHandicapRange(ratio), item);
            }

            // 客队半场大小 -> small
            if (gameJson.containsKey(keyAwayOddsFirstOverSize) && StringUtils.isNotBlank(gameJson.getStr(keyAwayOddsFirstOverSize))) {
                String ratio = gameJson.getStr(awayRatioFirstOverSize);
                String odds = calculateOddsValue(oddsFormatType, gameJson.getDouble(keyAwayOddsFirstOverSize));
                JSONObject item = new JSONObject();
                item.putOpt("id", hgid);
                item.putOpt("odds", odds);
                item.putOpt("oddFType", oddsFormatType);
                item.putOpt("gtype", gameJson.getStr("MT_GTYPE"));
                item.putOpt("wtype", "HROU");
                item.putOpt("rtype", "HROUC");
                item.putOpt("choseTeam", "H");
                item.putOpt("con", getMiddleValue(ratio));
                item.putOpt("ratio", getRatio(ratio, "小"));
                item.putOpt("handicap", ratio);

                small.putOpt(getHandicapRange(ratio), item);
            }
        }
    }

    /**
     * 发送账户额度请求并返回结果
     * @param params 请求参数
     * @return 结果
     */
    @Override
    public JSONObject execute(ConfigAccountVO userConfig, JSONObject params) {
        // 获取 完整API 路径
        String username = params.getStr("adminUsername");
        String siteId = params.getStr("websiteId");
        String baseUrl = websiteService.getWebsiteBaseUrl(username, siteId);
        String apiUrl = apiUrlService.getApiUrl(siteId, "events");
        // 构建请求
        Map<String, String> requestHeaders = buildHeaders(params);
        String requestBody = buildRequest(params);

        // 构造请求体
        String queryParams = String.format("ver=%s",
                Constants.VER
        );

        // 拼接完整的 URL
        String fullUrl = String.format("%s%s?%s", baseUrl, apiUrl, queryParams);

        // 发送请求
        OkHttpProxyDispatcher.HttpResult resultHttp;
        try {
            resultHttp = dispatcher.executeFull("POST", fullUrl, requestBody, requestHeaders, userConfig);
        } catch (Exception e) {
            log.error("请求异常，用户:{}, 账号:{}, 参数:{}, 错误:{}", username, userConfig.getAccount(), requestBody, e.getMessage(), e);
            throw new BusinessException(SystemError.SYS_400);
        }
        // 解析响应并返回
        return parseResponse(params, resultHttp);
    }

    /**
     * 替换handicap符号
     * @param handicap
     * @return
     */
    public static String getHandicapRange(String handicap) {
        // 先移除负号
        String withoutNegative = handicap.replace("-", "");
        // 再替换斜杠
        return withoutNegative.replaceAll(" / ", "-");
    }

    /**
     * 将现有解析后的 leagues 数据进行球队合并
     */
    public JSONArray mergeTeamsByTeamId(JSONArray originalLeagues) {
        JSONArray result = new JSONArray();

        for (Object leagueObj : originalLeagues) {
            JSONObject league = (JSONObject) leagueObj;

            // 新 league 对象
            JSONObject newLeague = new JSONObject();
            newLeague.putOpt("id", league.getStr("id"));
            newLeague.putOpt("ecid", league.getStr("ecid"));
            newLeague.putOpt("league", league.getStr("league"));

            JSONArray newEvents = new JSONArray();
            Map<String, JSONObject> teamMap = new LinkedHashMap<>();

            JSONArray events = league.getJSONArray("events");
            for (Object eventObj : events) {
                JSONObject team = (JSONObject) eventObj;
                String teamId = team.getStr("id") + "_" + team.getStr("name");

                // 合并逻辑
                JSONObject existing = teamMap.get(teamId);
                if (existing == null) {
                    // 初次出现，放入 map
                    teamMap.put(teamId, JSONUtil.parseObj(team));
                } else {
                    // 合并 fullCourt
                    mergeHandicap(existing.getJSONObject("fullCourt"), team.getJSONObject("fullCourt"));
                    mergeHandicap(existing.getJSONObject("firstHalf"), team.getJSONObject("firstHalf"));
                }
            }

            // 添加合并后的队伍
            newEvents.addAll(teamMap.values());
            newLeague.putOpt("events", newEvents);
            result.add(newLeague);
        }

        return result;
    }

    /**
     * 根据 id 在 events 数组中查找已有 event，找不到返回 null
     */
    private JSONObject findEventById(JSONArray events, String id) {
        if (events == null || id == null) return null;
        for (int i = 0; i < events.size(); i++) {
            JSONObject e = events.getJSONObject(i);
            if (id.equals(e.getStr("id"))) {
                return e;
            }
        }
        return null;
    }

    private void mergeHandicap(JSONObject target, JSONObject source) {
        for (String key : source.keySet()) {
            JSONObject targetGroup = (JSONObject) target.computeIfAbsent(key, k -> new JSONObject());
            JSONObject sourceGroup = source.getJSONObject(key);
            for (String handicapKey : sourceGroup.keySet()) {
                targetGroup.putOpt(handicapKey, sourceGroup.get(handicapKey));
            }
        }
    }

    /**
     * 根据盘口类型和盘口值计算得出ratio赔率
     * @param handicap
     * @param type
     * @return
     */
    public static int getRatio(String handicap, String type) {
        boolean isRange = handicap.contains("/");
        boolean isHome = type.equals("主队");
        boolean isAway = type.equals("客队");
        boolean isOver = type.equals("大");
        boolean isUnder = type.equals("小");

        if (isHome || isAway) {
            // 让球盘逻辑
            if (!isRange) {
                return isHome ? 100 : -100;
            } else {
                String[] parts = handicap.split("/");
                double start = Double.parseDouble(parts[0].trim());
                return isHome ? (start < 0 ? -50 : 50) : (start < 0 ? 50 : -50);
            }
        } else if (isOver || isUnder) {
            // 大小盘逻辑
            if (!isRange) {
                return isOver ? 100 : -100;
            } else {
                String[] parts = handicap.split("/");
                double end = Double.parseDouble(parts[1].trim());
                if (isOver) {
                    return 50;
                } else {
                    return (end == 3.0) ? -100 : 50;
                }
            }
        }
        return 0;
    }

    /**
     * 数值计算优化
     * 新2网站的马来盘，返回的赔率需要处理一下，大于1的赔率需要减2才是正确的赔率
     * 如果是香港盘，则不需要计算
     */
    private String calculateOddsValue(String oddsType, double value) {
        if (oddsType.equals(XinBaoOddsFormatType.RM.getCurrencyCode())) {
            return value > 1.0 ?
                    String.format("%.3f", value - 2.0) :
                    String.format("%.3f", value);
        } else {
            return String.format("%.3f", value);
        }
    }

    /**
     * 获取投注时的请求参数con
     * 计算逻辑是根据实际让球点数四舍五入(Math.round)得到
     * 例如：
     * "0 / 0.5" -> 中间值(就是实际值): 0.25 -> 四舍五入: 0
     * "2" -> 中间值: 2.0 -> 四舍五入: 2
     * "2.5 / 3" -> 中间值: 2.75 -> 四舍五入: 3
     * "4 / 4.5" -> 中间值: 4.25 -> 四舍五入: 4
     * @param handicap
     * @return
     */
    public static int getMiddleValue(String handicap) {
        // 分割字符串
        String[] parts = handicap.split("/");

        // 转换为数值并取绝对值
        double[] numbers = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            numbers[i] = Math.abs(Double.parseDouble(parts[i].trim())); // 取绝对值
        }

        // 计算中间值
        if (numbers.length == 1) {
            return (int) Math.round(numbers[0]);
        } else {
            double sum = 0;
            for (double num : numbers) {
                sum += num;
            }
            return (int) Math.round(sum / numbers.length);
        }
    }
    
}
