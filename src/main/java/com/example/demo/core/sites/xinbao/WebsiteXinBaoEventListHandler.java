package com.example.demo.core.sites.xinbao;

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
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import javax.xml.xpath.XPathConstants;
import java.util.*;

/**
 * 智博网站 - 赛事列表 API具体实现 用于操作页面查看赛事列表
 */
@Slf4j
@Component
public class WebsiteXinBaoEventListHandler implements ApiHandler {

    private final OkHttpProxyDispatcher dispatcher;
    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteXinBaoEventListHandler(OkHttpProxyDispatcher dispatcher, WebsiteService websiteService, ApiUrlService apiUrlService) {
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
     * 解析响应体
     * @param response 响应内容
     * @return 解析后的数据
     */
    @Override
    public JSONObject parseResponse(JSONObject params, OkHttpProxyDispatcher.HttpResult response) {
        // 检查响应状态
        if (response.getStatus() != 200) {
            JSONObject res = new JSONObject();
            res.putOpt("success", false);
            res.putOpt("msg", "账户登录失效");
            return res;
        }

        // 解析响应
        Document docResult = XmlUtil.readXML(response.getBody());
        JSONObject responseJson = new JSONObject(response.getBody());
        Object original = XmlUtil.getByXPath("//serverresponse/original", docResult, XPathConstants.STRING);
        if (ObjectUtil.isEmpty(original)) {
            responseJson.putOpt("success", false);
            responseJson.putOpt("msg", "获取账户赛事失败");
            return responseJson;
        }
        // 获取平台设置的赔率类型
        String oddsFormatType = params.getStr("oddsFormatType");
        JSONArray result = new JSONArray();
        JSONObject originalJson = JSONUtil.parseObj(original);

        // 全场让球key
        String ratioFull;          // 让球分
        String keyHomeOddsFullLetBall;
        String keyAwayOddsFullLetBall;
        // 全场大小key
        String homeRatioFullOverSize;  // 大小球分
        String awayRatioFullOverSize;  // 大小球分
        String keyHomeOddsFullOverSize;
        String keyAwayOddsFullOverSize;
        // 半场让球key
        String ratioFirst;          // 让球分
        String keyHomeOddsFirstLetBall;
        String keyAwayOddsFirstLetBall;
        // 半场大小key
        String homeRatioFirstOverSize;  // 大小球分
        String awayRatioFirstOverSize;  // 大小球分
        String keyHomeOddsFirstOverSize;
        String keyAwayOddsFirstOverSize;
        if (ZhiBoSchedulesType.LIVESCHEDULE.getId() == params.getInt("showType")) {
            ratioFull = "RATIO_RE";
            ratioFirst = "RATIO_HRE";
            homeRatioFullOverSize = "RATIO_ROUO";
            awayRatioFullOverSize = "RATIO_ROUU";
            homeRatioFirstOverSize = "RATIO_HROUO";
            awayRatioFirstOverSize = "RATIO_HROUU";

            keyHomeOddsFullLetBall = "IOR_REH";    // 滚球类型获取赔率字段key中有E字符
            keyAwayOddsFullLetBall = "IOR_REC";    // 滚球类型获取赔率字段key中有E字符

            keyHomeOddsFullOverSize = "IOR_ROUC";    // 滚球类型获取赔率字段key中有R字符
            keyAwayOddsFullOverSize = "IOR_ROUH";    // 滚球类型获取赔率字段key中有R字符

            keyHomeOddsFirstLetBall = "IOR_HREH";    // 滚球类型获取赔率字段key中有E字符
            keyAwayOddsFirstLetBall = "IOR_HREC";    // 滚球类型获取赔率字段key中有E字符

            keyHomeOddsFirstOverSize = "IOR_HROUC";    // 滚球类型获取赔率字段key中有R字符
            keyAwayOddsFirstOverSize = "IOR_HROUH";    // 滚球类型获取赔率字段key中有R字符
        } else {
            ratioFull = "RATIO_R";
            ratioFirst = "RATIO_HR";
            homeRatioFullOverSize= "RATIO_OUO";
            awayRatioFullOverSize = "RATIO_OUU";
            homeRatioFirstOverSize = "RATIO_HOUO";
            awayRatioFirstOverSize = "RATIO_HOUU";

            keyHomeOddsFullLetBall = "IOR_RH";     // 赛前类型获取赔率字段key中没有E字符
            keyAwayOddsFullLetBall = "IOR_RC";    // 赛前类型获取赔率字段key

            keyHomeOddsFullOverSize = "IOR_OUC";    // 赛前类型获取赔率字段key
            keyAwayOddsFullOverSize = "IOR_OUH";    // 赛前类型获取赔率字段key

            keyHomeOddsFirstLetBall = "IOR_HRH";    // 赛前类型获取赔率字段key
            keyAwayOddsFirstLetBall = "IOR_HRC";    // 赛前类型获取赔率字段key

            keyHomeOddsFirstOverSize = "IOR_HOUC";    // 滚球类型获取赔率字段key
            keyAwayOddsFirstOverSize = "IOR_HOUH";    // 滚球类型获取赔率字段key
        }

        String finalRatioFull = ratioFull;
        String finalRatioOverSize = homeRatioFullOverSize;
        String finalAwayRatioOverSize = awayRatioFullOverSize;
        originalJson.forEach(json -> {
            JSONObject gameJson = JSONUtil.parseObj(json.getValue());
            String lid = gameJson.getStr("LID");        // 联赛LID
            String gid = gameJson.getStr("GID");        // 比赛队伍GID
            String ecid = gameJson.getStr("ECID");      // 联赛ECID
            String league = gameJson.getStr("LEAGUE");  // 联赛名称
            String PTYPE = gameJson.getStr("PTYPE");   // 队名别称类型，如果有则不显示此队伍
            if (StringUtils.isNotBlank(PTYPE)) {
                return;
            }

            String session = gameJson.getStr("NOW_MODEL"); // 赛事类型
            String reTimeSet = gameJson.getStr("RETIMESET");
            String midfield = gameJson.getStr("MIDFIELD"); // 中场休息标记
            int reTime = 0;
            String half = ""; // 上半场/下半场/中场休息标记

            if (reTimeSet != null && !reTimeSet.isEmpty()) {
                String[] parts = reTimeSet.split("\\^");
                if (parts.length > 0) {
                    half = parts[0]; // 提取 "1H"、"2H" 或 "HT"
                }

                if ("1H".equals(half) || "2H".equals(half)) {
                    if (parts.length > 1 && parts[1].contains(":")) {
                        String[] timeParts = parts[1].split(":");
                        String minuteStr = timeParts[0].replaceAll("\\D", ""); // 防止有非数字
                        if (!minuteStr.isEmpty()) {
                            try {
                                reTime = Integer.parseInt(minuteStr);
                            } catch (NumberFormatException e) {
                                // 日志记录也可以加上
                                log.error("新二网站获取比赛时长解析异常:", e);
                            }
                        }
                    }
                }
            }
            if (StringUtils.isNotBlank(midfield) && "Y".equals(midfield)) {
                // 现在是中场休息
                half = "HT";
            }

            String type;
            if ("HT".equals(session)) {
                type = "firstHalf";
            } else {
                type = "fullCourt";
            }
            String score = "";
            if (!gameJson.isNull("SCORE_H") && !gameJson.isNull("SCORE_C")) {
                score = gameJson.getStr("SCORE_H") + "-" + gameJson.getStr("SCORE_C");
            }
            // 全场让球
            if (gameJson.containsKey(keyHomeOddsFullLetBall) && StringUtils.isNotBlank(gameJson.getStr(keyHomeOddsFullLetBall))) {
                type = "fullCourt";
                JSONObject leagueJson = buildBaseLeagueJson(gameJson, lid, gid, ecid, league, session, half, reTime, type, score);
                String homeOdds = calculateOddsValue(oddsFormatType, gameJson.getDouble(keyHomeOddsFullLetBall));
                String awayOdds = calculateOddsValue(oddsFormatType, gameJson.getDouble(keyAwayOddsFullLetBall));
                String homeHandicap = "";
                if ("0".equals(gameJson.getStr(finalRatioFull))) {
                    homeHandicap = gameJson.getStr(finalRatioFull);
                } else {
                    homeHandicap = "-" + gameJson.getStr(finalRatioFull);
                }
                leagueJson.putOpt("handicapType", "letBall");       // 盘口类型
                leagueJson.putOpt("homeOdds", homeOdds); // 投注赔率
                leagueJson.putOpt("homeOddFType", oddsFormatType);
                leagueJson.putOpt("homeGtype", gameJson.getStr("MT_GTYPE"));
                // 理论上wtype和rtype也需要根据滚球或者赛前类型进行对应修改，但是因为只投注滚球，所有这就不改了
                leagueJson.putOpt("homeWtype", "RE");
                leagueJson.putOpt("homeRtype", "REH");
                leagueJson.putOpt("homeChoseTeam", "H");
                leagueJson.putOpt("homeCon", getMiddleValue(gameJson.getStr(finalRatioFull)));
                int ratioHome = getRatio(gameJson.getStr(finalRatioFull), "主队");
                leagueJson.putOpt("homeRatio", ratioHome);
                leagueJson.putOpt("homeHandicap", homeHandicap);
                leagueJson.putOpt("homeWall", "hanging");   // 主队上盘，客队下盘，hanging=上盘,foot=下盘

                leagueJson.putOpt("awayOdds", awayOdds); // 投注赔率
                leagueJson.putOpt("awayOddFType", oddsFormatType);
                leagueJson.putOpt("awayGtype", gameJson.getStr("MT_GTYPE"));
                leagueJson.putOpt("awayWtype", "RE");
                leagueJson.putOpt("awayRtype", "REC");
                leagueJson.putOpt("awayChoseTeam", "C");
                leagueJson.putOpt("awayCon", -Math.abs(getMiddleValue(gameJson.getStr(finalRatioFull))));
                int ratioAway = getRatio(gameJson.getStr(finalRatioFull), "客队");
                leagueJson.putOpt("awayRatio", ratioAway);
                leagueJson.putOpt("awayHandicap", gameJson.getInt(finalRatioFull));
                leagueJson.putOpt("awayWall", "foot");   // 主队上盘，客队下盘，hanging=上盘,foot=下盘
                // 将所有合并后的联赛放入 result 数组中
                result.add(leagueJson);
            }
            // 全场大小
            if (gameJson.containsKey(keyHomeOddsFullOverSize) && StringUtils.isNotBlank(gameJson.getStr(keyHomeOddsFullOverSize))) {
                type = "fullCourt";
                JSONObject leagueJson = buildBaseLeagueJson(gameJson, lid, gid, ecid, league, session, half, reTime, type, score);
                String homeOdds = calculateOddsValue(oddsFormatType, gameJson.getDouble(keyHomeOddsFullOverSize));
                String awayOdds = calculateOddsValue(oddsFormatType, gameJson.getDouble(keyAwayOddsFullOverSize));

                leagueJson.putOpt("handicapType", "overSize");       // 盘口类型
                leagueJson.putOpt("homeOdds", homeOdds); // 投注赔率
                leagueJson.putOpt("homeOddFType", oddsFormatType);
                leagueJson.putOpt("homeGtype", gameJson.getStr("MT_GTYPE"));
                leagueJson.putOpt("homeWtype", "ROU");
                leagueJson.putOpt("homeRtype", "ROUC");
                leagueJson.putOpt("homeChoseTeam", "C");
                leagueJson.putOpt("homeCon", getMiddleValue(gameJson.getStr(finalRatioOverSize)));
                leagueJson.putOpt("homeRatio", getRatio(gameJson.getStr(finalRatioOverSize), "大"));
                leagueJson.putOpt("homeHandicap", gameJson.getStr(finalRatioOverSize));

                leagueJson.putOpt("awayOdds", awayOdds); // 投注赔率
                leagueJson.putOpt("awayOddFType", oddsFormatType);
                leagueJson.putOpt("awayGtype", gameJson.getStr("MT_GTYPE"));
                leagueJson.putOpt("awayWtype", "ROU");
                leagueJson.putOpt("awayRtype", "ROUH");
                leagueJson.putOpt("awayChoseTeam", "H");
                leagueJson.putOpt("awayCon", -Math.abs(getMiddleValue(gameJson.getStr(finalAwayRatioOverSize))));
                leagueJson.putOpt("awayRatio", getRatio(gameJson.getStr(finalAwayRatioOverSize), "小"));
                leagueJson.putOpt("awayHandicap", gameJson.getStr(finalAwayRatioOverSize));
                // 将所有合并后的联赛放入 result 数组中
                result.add(leagueJson);
            }

            // 半场让球
            if (gameJson.containsKey(keyHomeOddsFirstLetBall) && StringUtils.isNotBlank(gameJson.getStr(keyHomeOddsFirstLetBall))) {
                type = "firstHalf";
                JSONObject leagueJson = buildBaseLeagueJson(gameJson, lid, gid, ecid, league, session, half, reTime, type, score);
                String homeOdds = calculateOddsValue(oddsFormatType, gameJson.getDouble(keyHomeOddsFirstLetBall));
                String awayOdds = calculateOddsValue(oddsFormatType, gameJson.getDouble(keyAwayOddsFirstLetBall));

                String homeHandicap = "";
                if ("0".equals(gameJson.getStr(ratioFirst))) {
                    homeHandicap = gameJson.getStr(ratioFirst);
                } else {
                    homeHandicap = "-" + gameJson.getStr(ratioFirst);
                }
                leagueJson.putOpt("handicapType", "letBall");       // 盘口类型
                leagueJson.putOpt("homeOdds", homeOdds); // 投注赔率
                leagueJson.putOpt("oddFType", oddsFormatType);
                leagueJson.putOpt("homeGtype", gameJson.getStr("MT_GTYPE"));
                leagueJson.putOpt("homeWtype", "HRE");
                leagueJson.putOpt("homeRtype", "HREH");
                leagueJson.putOpt("homeChoseTeam", "H");
                leagueJson.putOpt("homeCon", getMiddleValue(gameJson.getStr(ratioFirst)));
                int ratioHome = getRatio(gameJson.getStr(ratioFirst), "主队");
                leagueJson.putOpt("homeRatio", ratioHome);
                leagueJson.putOpt("homeHandicap", homeHandicap);
                leagueJson.putOpt("homeWall", "hanging");   // 主队上盘，客队下盘，hanging=上盘,foot=下盘

                leagueJson.putOpt("awayOdds", awayOdds); // 投注赔率
                leagueJson.putOpt("awayOddFType", oddsFormatType);
                leagueJson.putOpt("awayGtype", gameJson.getStr("MT_GTYPE"));
                leagueJson.putOpt("awayWtype", "HRE");
                leagueJson.putOpt("awayRtype", "HREC");
                leagueJson.putOpt("awayChoseTeam", "C");
                leagueJson.putOpt("awayCon", -Math.abs(getMiddleValue(gameJson.getStr(ratioFirst))));
                int ratioAway = getRatio(gameJson.getStr(ratioFirst), "客队");
                leagueJson.putOpt("awayRatio", ratioAway);
                leagueJson.putOpt("awayHandicap", gameJson.getStr(ratioFirst));
                leagueJson.putOpt("awayWall", "hanging");   // 主队上盘，客队下盘，hanging=上盘,foot=下盘
                // 将所有合并后的联赛放入 result 数组中
                result.add(leagueJson);
            }

            // 半场大小
            if (gameJson.containsKey(keyHomeOddsFirstOverSize) && StringUtils.isNotBlank(gameJson.getStr(keyHomeOddsFirstOverSize))) {
                type = "firstHalf";
                JSONObject leagueJson = buildBaseLeagueJson(gameJson, lid, gid, ecid, league, session, half, reTime, type, score);
                String homeOdds = calculateOddsValue(oddsFormatType, gameJson.getDouble(keyHomeOddsFirstOverSize));
                String awayOdds = calculateOddsValue(oddsFormatType, gameJson.getDouble(keyAwayOddsFirstOverSize));

                leagueJson.putOpt("handicapType", "overSize");       // 盘口类型
                leagueJson.putOpt("homeOdds", homeOdds); // 投注赔率
                leagueJson.putOpt("homeGtype", gameJson.getStr("MT_GTYPE"));
                leagueJson.putOpt("homeWtype", "HROU");
                leagueJson.putOpt("homeRtype", "HROUC");
                leagueJson.putOpt("homeChoseTeam", "C");
                leagueJson.putOpt("homeCon", getMiddleValue(gameJson.getStr(homeRatioFirstOverSize)));
                leagueJson.putOpt("homeRatio", getRatio(gameJson.getStr(homeRatioFirstOverSize), "大"));
                leagueJson.putOpt("homeHandicap", gameJson.getStr(homeRatioFirstOverSize));

                leagueJson.putOpt("awayOdds", awayOdds); // 投注赔率
                leagueJson.putOpt("awayGtype", gameJson.getStr("MT_GTYPE"));
                leagueJson.putOpt("awayWtype", "HROU");
                leagueJson.putOpt("awayRtype", "HROUH");
                leagueJson.putOpt("awayChoseTeam", "H");
                leagueJson.putOpt("awayCon", -Math.abs(getMiddleValue(gameJson.getStr(awayRatioFirstOverSize))));
                leagueJson.putOpt("awayRatio", getRatio(gameJson.getStr(awayRatioFirstOverSize), "小"));
                leagueJson.putOpt("awayHandicap", gameJson.getStr(awayRatioFirstOverSize));
                // 将所有合并后的联赛放入 result 数组中
                result.add(leagueJson);
            }

        });

        // 对 result 数组进行排序，按照联赛名称和盘口类型进行排序
        List<JSONObject> sorted = new ArrayList<>();
        for (Object obj : result) {
            sorted.add((JSONObject) obj);
        }

        sorted.sort(Comparator
                .comparing((JSONObject o) -> o.getStr("league"), Comparator.nullsLast(String::compareTo))
                .thenComparing(o -> {
                    // 优先 firstHalf，后 fullCourt
                    String type = o.getStr("type");
                    return "firstHalf".equals(type) ? 0 : 1;
                })
                .thenComparing(o -> {
                    // 优先 letBall，后 overSize
                    String handicapType = o.getStr("handicapType");
                    if ("letBall".equals(handicapType)) return 0;
                    if ("overSize".equals(handicapType)) return 1;
                    return 2;
                })
        );

        // 构造最终结果
        JSONArray finalResult = new JSONArray();
        finalResult.addAll(sorted);

        responseJson.putOpt("success", true);
        responseJson.putOpt("leagues", finalResult);
        responseJson.putOpt("msg", "获取账户赛事成功");
        return responseJson;
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

    // 公共字段抽取方法
    private JSONObject buildBaseLeagueJson(JSONObject gameJson, String lid, String gid, String ecid, String league, String session, String half, int reTime, String type, String score) {
        JSONObject base = new JSONObject();
        base.putOpt("id", lid);
        base.putOpt("league", league);
        base.putOpt("type", type);
        base.putOpt("session", session);
        base.putOpt("reTime", reTime);
        base.putOpt("eventId", lid);
        base.putOpt("gid", gid);
        base.putOpt("ecid", ecid);
        base.putOpt("homeTeam", gameJson.getStr("TEAM_H"));
        base.putOpt("awayTeam", gameJson.getStr("TEAM_C"));
        base.putOpt("score", score);
        return base;
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
     * 计算handicap
     * @param handicap
     * @return
     */
    public String getHandicapRange(String handicap) {
        return handicap.replaceAll(" / ", "-");
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
