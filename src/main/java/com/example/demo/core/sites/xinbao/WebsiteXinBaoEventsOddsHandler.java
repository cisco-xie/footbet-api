package com.example.demo.core.sites.xinbao;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.constants.Constants;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.common.enmu.XinBaoOddsFormatType;
import com.example.demo.config.OkHttpProxyDispatcher;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 新2网站 - 赛事详情赔率 API具体实现
 */
@Slf4j
@Component
public class WebsiteXinBaoEventsOddsHandler implements ApiHandler {

    private final OkHttpProxyDispatcher dispatcher;
    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteXinBaoEventsOddsHandler(OkHttpProxyDispatcher dispatcher, WebsiteService websiteService, ApiUrlService apiUrlService) {
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
        String showType = "live";  // 滚球赛事
//        String showType = "today";  // 今日赛事

        // 构造请求体
        return String.format("p=get_game_more&uid=%s&ver=%s&langx=zh-cn&gtype=ft&showtype=%s&ltype=3&isRB=Y&lid=%s&specialClick=&mode=NORMAL&from=game_more&filter=Main&ecid=%s&ts=%s",
                params.getStr("uid"),
                Constants.VER,
                showType,
                params.getStr("lid"),
                params.getStr("ecid"),
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
            res.putOpt("code", response.getStatus());
            res.putOpt("success", false);
            res.putOpt("msg", "获取赔率详情失败");
            return res;
        }

        String username = params.getStr("adminUsername");
        // 解析响应
        String responseBody = response.getBody().trim();
        JSONObject responseJson;

        // 判断是否为 JSON 格式
        try {
            responseJson = JSONUtil.parseObj(responseBody);
        } catch (Exception e) {
            log.error("[新2][获取赔率失败][JSON解析异常][body={}]", responseBody, e);
            return new JSONObject()
                    .putOpt("success", false)
                    .putOpt("msg", "赔率返回格式错误（JSON 解析失败）");
        }
        if (!responseJson.getJSONObject("serverresponse").getStr("code").equals("617")) {
            JSONObject res = new JSONObject();
            res.putOpt("success", false);
            res.putOpt("msg", "获取赔率详情失败");
            return res;
        }
        if (!responseJson.getJSONObject("serverresponse").containsKey("game")) {
            responseJson.putOpt("success", false);
            responseJson.putOpt("msg", "获取赔率详情为空");
            return responseJson;
        }
        String gameObject = String.valueOf(responseJson.getJSONObject("serverresponse").get("game"));
        if (StringUtils.isBlank(gameObject)) {
            responseJson.putOpt("success", false);
            responseJson.putOpt("msg", "获取赔率详情为空");
            return responseJson;
        }
        JSONArray games = new JSONArray();
        if (JSONUtil.isTypeJSONObject(gameObject)) {
            games.add(JSONUtil.parseObj(gameObject));
        } else if (JSONUtil.isTypeJSONArray(gameObject)) {
            games = JSONUtil.parseArray(gameObject);
        }
        // 获取平台设置的赔率类型
        String oddsFormatType = params.getStr("oddsFormatType");
        // 结果存储，用于合并相同的 lid
        String lid = games.getJSONObject(0).getStr("lid");                          // 联赛ID
        String ecid = responseJson.getJSONObject("serverresponse").getStr("ecid");  // 联赛ID
        String league = games.getJSONObject(0).getStr("league");                    // 联赛名称
        JSONArray result = new JSONArray();
        JSONObject leagueJson = new JSONObject();
        leagueJson.putOpt("id", lid);
        leagueJson.putOpt("ecid", ecid);
        leagueJson.putOpt("league", league);
        // 初始化比赛事件列表
        JSONArray events = new JSONArray();
        // 主队数据
        JSONObject homeTeam = new JSONObject();
        // 获取当前比分
        String score = games.getJSONObject(0).getStr("score_h","0") + "-" + games.getJSONObject(0).getStr("score_c","0");
        homeTeam.putOpt("id", games.getJSONObject(0).getStr("gnum_h")); // 主队ID
        homeTeam.putOpt("name", games.getJSONObject(0).getStr("team_h")); // 主队名称
        homeTeam.putOpt("isHome", true); // 是否是主队
        homeTeam.putOpt("score", score); // 当前比分

        // 客队数据
        JSONObject awayTeam = new JSONObject();
        awayTeam.putOpt("id", games.getJSONObject(0).getStr("gnum_c")); // 客队ID
        awayTeam.putOpt("name", games.getJSONObject(0).getStr("team_c")); // 客队名称
        awayTeam.putOpt("score", score); // 当前比分
        awayTeam.putOpt("isHome", false); // 是否是主队

        // 处理全场数据
        JSONObject homeFullCourt = new JSONObject();
        JSONObject awayFullCourt = new JSONObject();
        JSONObject homeFirstHalf = new JSONObject();
        JSONObject awayFirstHalf = new JSONObject();

        JSONObject homeLetBall = new JSONObject();
        JSONObject awayLetBall = new JSONObject();

        JSONObject homeOverSize = new JSONObject();
        JSONObject awayOverSize = new JSONObject();

        JSONObject firstHalfHomeLetBall = new JSONObject();
        JSONObject firstHalfAwayLetBall = new JSONObject();
        JSONObject homeFirstHomeOverSize = new JSONObject();
        JSONObject homeFirstAwayOverSize = new JSONObject();
        // 是否已获取主队的上下盘
        AtomicReference<String> wallHome = new AtomicReference<>();
        // 是否已获取客队的上下盘
        AtomicReference<String> wallAway = new AtomicReference<>();

        // 遍历所有比赛
        games.forEach(gameObj -> {
            JSONObject game = (JSONObject) gameObj;
            String session = game.getStr("re_time");
            int reTime = 0;
            String half = ""; // 上半场/下半场/中场休息标记

            if (session != null && !session.isEmpty()) {
                String[] parts = session.split("\\^");
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
            homeTeam.putOpt("session", half);
            homeTeam.putOpt("reTime", reTime);
            awayTeam.putOpt("session", half);
            awayTeam.putOpt("reTime", reTime);
            // 全场让球
            if (game.containsKey("ior_REH") && 0 != game.getDouble("ior_REH")) {
                String homeOdds = calculateOddsValue(oddsFormatType, game.getDouble("ior_REH"));
                String awayOdds = calculateOddsValue(oddsFormatType, game.getDouble("ior_REC"));

                JSONObject homeOddsJson = new JSONObject();
                homeOddsJson.putOpt("id", game.getStr("gid"));                              // 投注id
                homeOddsJson.putOpt("odds", homeOdds); // 投注赔率
                homeOddsJson.putOpt("oddFType", oddsFormatType);
                homeOddsJson.putOpt("gtype", game.getStr("gtype"));
                homeOddsJson.putOpt("wtype", "RE");
                homeOddsJson.putOpt("rtype", "REH");
                homeOddsJson.putOpt("choseTeam", "H");
                homeOddsJson.putOpt("con", getMiddleValue(game.getStr("ratio_re")));
                int ratioHome = getRatio(game.getStr("ratio_re"), "主队");
                homeOddsJson.putOpt("ratio", ratioHome);
                homeOddsJson.putOpt("handicap", "-" + game.getStr("ratio_re"));
                if (StringUtils.isBlank(wallHome.get())) {
                    if (ratioHome > 0) {
                        // 上盘
                        wallHome.set("hanging");
                    } else if (ratioHome < 0) {
                        // 下盘
                        wallHome.set("foot");
                    }
                }
                homeOddsJson.putOpt("wall", wallHome.get());   // hanging=上盘,foot=下盘

                JSONObject awayOddsJson = new JSONObject();
                awayOddsJson.putOpt("id", game.getStr("gid"));                              // 投注id
                awayOddsJson.putOpt("odds", awayOdds); // 投注赔率
                awayOddsJson.putOpt("oddFType", oddsFormatType);
                awayOddsJson.putOpt("gtype", game.getStr("gtype"));
                awayOddsJson.putOpt("wtype", "RE");
                awayOddsJson.putOpt("rtype", "REC");
                awayOddsJson.putOpt("choseTeam", "C");
                awayOddsJson.putOpt("con", -Math.abs(getMiddleValue(game.getStr("ratio_re"))));
                int ratioAway = getRatio(game.getStr("ratio_re"), "客队");
                awayOddsJson.putOpt("ratio", ratioAway);
                awayOddsJson.putOpt("handicap", game.getInt("ratio_re"));
                if (StringUtils.isBlank(wallAway.get())) {
                    if (ratioAway > 0) {
                        // 上盘
                        wallAway.set("hanging");
                    } else if (ratioAway < 0) {
                        // 下盘
                        wallAway.set("foot");
                    }
                }
                awayOddsJson.putOpt("wall", wallAway.get());   // hanging=上盘,foot=下盘

                homeLetBall.putOpt(getHandicapRange(game.getStr("ratio_re")), homeOddsJson);
                awayLetBall.putOpt(getHandicapRange(game.getStr("ratio_re")), awayOddsJson);

                // 记录主队的赔率
                apiUrlService.updateOddsCache(username, game.getStr("gid")+"H", Double.parseDouble(homeOdds));
                // 记录客队的赔率
                apiUrlService.updateOddsCache(username, game.getStr("gid")+"C", Double.parseDouble(awayOdds));
            }

            // 全场大小
            if (game.containsKey("ior_ROUH") && 0 != game.getDouble("ior_ROUH")) {
                String homeOdds = calculateOddsValue(oddsFormatType, game.getDouble("ior_ROUC"));
                String awayOdds = calculateOddsValue(oddsFormatType, game.getDouble("ior_ROUH"));

                JSONObject homeOverSizeOddsJson = new JSONObject();
                homeOverSizeOddsJson.putOpt("id", game.getStr("gid"));                              // 投注id
                homeOverSizeOddsJson.putOpt("odds", homeOdds); // 投注赔率
                homeOverSizeOddsJson.putOpt("oddFType", oddsFormatType);
                homeOverSizeOddsJson.putOpt("gtype", game.getStr("gtype"));
                homeOverSizeOddsJson.putOpt("wtype", "ROU");
                homeOverSizeOddsJson.putOpt("rtype", "ROUC");
                homeOverSizeOddsJson.putOpt("choseTeam", "C");
                homeOverSizeOddsJson.putOpt("con", getMiddleValue(game.getStr("ratio_rouo")));
                homeOverSizeOddsJson.putOpt("ratio", getRatio(game.getStr("ratio_rouo"), "大"));
                homeOverSizeOddsJson.putOpt("handicap", game.getStr("ratio_rouo"));

                JSONObject awayOverSizeOddsJson = new JSONObject();
                awayOverSizeOddsJson.putOpt("id", game.getStr("gid"));                              // 投注id
                awayOverSizeOddsJson.putOpt("odds", awayOdds); // 投注赔率
                awayOverSizeOddsJson.putOpt("oddFType", oddsFormatType);
                awayOverSizeOddsJson.putOpt("gtype", game.getStr("gtype"));
                awayOverSizeOddsJson.putOpt("wtype", "ROU");
                awayOverSizeOddsJson.putOpt("rtype", "ROUH");
                awayOverSizeOddsJson.putOpt("choseTeam", "H");
                awayOverSizeOddsJson.putOpt("con", -Math.abs(getMiddleValue(game.getStr("ratio_rouu"))));
                awayOverSizeOddsJson.putOpt("ratio", getRatio(game.getStr("ratio_rouu"), "小"));
                awayOverSizeOddsJson.putOpt("handicap", game.getStr("ratio_rouu"));

                homeOverSize.putOpt(getHandicapRange(game.getStr("ratio_rouo")), homeOverSizeOddsJson);
                awayOverSize.putOpt(getHandicapRange(game.getStr("ratio_rouu")), awayOverSizeOddsJson);

                // 记录主队的赔率
                apiUrlService.updateOddsCache(username, game.getStr("gid")+"C", Double.parseDouble(homeOdds));
                // 记录客队的赔率
                apiUrlService.updateOddsCache(username, game.getStr("gid")+"H", Double.parseDouble(awayOdds));
            }

            // 全场胜平负
            if (game.containsKey("ior_RMH") && 0 != game.getDouble("ior_RMH")) {
                JSONObject homeOddsJson = new JSONObject();
                homeOddsJson.putOpt("id", game.getStr("gid"));          // 投注id
                homeOddsJson.putOpt("odds", game.getStr("ior_RMH"));      // 投注赔率
                JSONObject awayOddsJson = new JSONObject();
                awayOddsJson.putOpt("id", game.getStr("gid"));         // 投注id
                awayOddsJson.putOpt("odds", game.getStr("ior_RMC"));      // 投注赔率
                JSONObject drawOddsJson = new JSONObject();
                drawOddsJson.putOpt("id", game.getStr("gid"));         // 投注id
                drawOddsJson.putOpt("odds", game.getStr("ior_RMN"));      // 投注赔率
                homeFullCourt.putOpt("win", homeOddsJson);
                homeFullCourt.putOpt("draw", drawOddsJson);
                awayFullCourt.putOpt("win", awayOddsJson);
                awayFullCourt.putOpt("draw", drawOddsJson);
                // 记录主队的赔率
                apiUrlService.updateOddsCache(username, game.getStr("gid")+"H", game.getDouble("ior_RMH"));
                // 记录客队的赔率
                apiUrlService.updateOddsCache(username, game.getStr("gid")+"C", game.getDouble("ior_RMC"));
                // 记录和的赔率
                apiUrlService.updateOddsCache(username, game.getStr("gid")+"N", game.getDouble("ior_RMN"));
            }

            // 半场让球
            if (game.containsKey("ior_HREH") && 0 != game.getDouble("ior_HREH")) {
                String homeOdds = calculateOddsValue(oddsFormatType, game.getDouble("ior_HREH"));
                String awayOdds = calculateOddsValue(oddsFormatType, game.getDouble("ior_HREC"));

                JSONObject homeOddsJson = new JSONObject();
                homeOddsJson.putOpt("id", game.getStr("gid"));                              // 投注id
                homeOddsJson.putOpt("odds", homeOdds); // 投注赔率
                homeOddsJson.putOpt("oddFType", oddsFormatType);
                homeOddsJson.putOpt("gtype", game.getStr("gtype"));
                homeOddsJson.putOpt("wtype", "HRE");
                homeOddsJson.putOpt("rtype", "HREH");
                homeOddsJson.putOpt("choseTeam", "H");
                homeOddsJson.putOpt("con", getMiddleValue(game.getStr("ratio_hre")));
                int ratioHome = getRatio(game.getStr("ratio_hre"), "主队");
                homeOddsJson.putOpt("ratio", ratioHome);
                homeOddsJson.putOpt("handicap", "-" + game.getStr("ratio_hre"));
                if (StringUtils.isBlank(wallHome.get())) {
                    if (ratioHome > 0) {
                        // 上盘
                        wallHome.set("hanging");
                    } else if (ratioHome < 0) {
                        // 下盘
                        wallHome.set("foot");
                    }
                }
                homeOddsJson.putOpt("wall", wallHome.get());   // hanging=上盘,foot=下盘

                JSONObject awayOddsJson = new JSONObject();
                awayOddsJson.putOpt("id", game.getStr("gid"));                              // 投注id
                awayOddsJson.putOpt("odds", awayOdds); // 投注赔率
                awayOddsJson.putOpt("oddFType", oddsFormatType);
                awayOddsJson.putOpt("gtype", game.getStr("gtype"));
                awayOddsJson.putOpt("wtype", "HRE");
                awayOddsJson.putOpt("rtype", "HREC");
                awayOddsJson.putOpt("choseTeam", "C");
                awayOddsJson.putOpt("con", -Math.abs(getMiddleValue(game.getStr("ratio_hre"))));
                int ratioAway = getRatio(game.getStr("ratio_hre"), "客队");
                awayOddsJson.putOpt("ratio", ratioAway);
                awayOddsJson.putOpt("handicap", game.getStr("ratio_hre"));
                if (StringUtils.isBlank(wallAway.get())) {
                    if (ratioAway > 0) {
                        // 上盘
                        wallAway.set("hanging");
                    } else if (ratioAway < 0) {
                        // 下盘
                        wallAway.set("foot");
                    }
                }
                awayOddsJson.putOpt("wall", wallAway.get());   // hanging=上盘,foot=下盘

                firstHalfHomeLetBall.putOpt(getHandicapRange(game.getStr("ratio_hre")), homeOddsJson);
                firstHalfAwayLetBall.putOpt(getHandicapRange(game.getStr("ratio_hre")), awayOddsJson);
                homeFirstHalf.putOpt("letBall", firstHalfHomeLetBall);
                awayFirstHalf.putOpt("letBall", firstHalfAwayLetBall);

                // 记录主队的赔率
                apiUrlService.updateOddsCache(username, game.getStr("gid")+"H", Double.parseDouble(homeOdds));
                // 记录客队的赔率
                apiUrlService.updateOddsCache(username, game.getStr("gid")+"C", Double.parseDouble(awayOdds));
            }

            // 半场大小
            if (game.containsKey("ior_HROUH") && 0 != game.getDouble("ior_HROUH")) {
                String homeOdds = calculateOddsValue(oddsFormatType, game.getDouble("ior_HROUC"));
                String awayOdds = calculateOddsValue(oddsFormatType, game.getDouble("ior_HROUH"));

                JSONObject homeOddsJson = new JSONObject();
                homeOddsJson.putOpt("id", game.getStr("gid"));                              // 投注id
                homeOddsJson.putOpt("odds", homeOdds); // 投注赔率
                homeOddsJson.putOpt("gtype", game.getStr("gtype"));
                homeOddsJson.putOpt("wtype", "HROU");
                homeOddsJson.putOpt("rtype", "HROUC");
                homeOddsJson.putOpt("choseTeam", "C");
                homeOddsJson.putOpt("con", getMiddleValue(game.getStr("ratio_hrouo")));
                homeOddsJson.putOpt("ratio", getRatio(game.getStr("ratio_hrouo"), "大"));
                homeOddsJson.putOpt("handicap", game.getStr("ratio_hrouo"));

                JSONObject awayOddsJson = new JSONObject();
                awayOddsJson.putOpt("id", game.getStr("gid"));                              // 投注id
                awayOddsJson.putOpt("odds", awayOdds); // 投注赔率
                awayOddsJson.putOpt("gtype", game.getStr("gtype"));
                awayOddsJson.putOpt("wtype", "HROU");
                awayOddsJson.putOpt("rtype", "HROUH");
                awayOddsJson.putOpt("choseTeam", "H");
                awayOddsJson.putOpt("con", -Math.abs(getMiddleValue(game.getStr("ratio_hrouu"))));
                awayOddsJson.putOpt("ratio", getRatio(game.getStr("ratio_hrouu"), "小"));
                awayOddsJson.putOpt("handicap", game.getStr("ratio_hrouu"));

                homeFirstHomeOverSize.putOpt(getHandicapRange(game.getStr("ratio_hrouo")), homeOddsJson);
                homeFirstAwayOverSize.putOpt(getHandicapRange(game.getStr("ratio_hrouu")), awayOddsJson);
                homeFirstHalf.putOpt("overSize", homeFirstHomeOverSize);
                awayFirstHalf.putOpt("overSize", homeFirstAwayOverSize);
                // 记录主队的赔率
                apiUrlService.updateOddsCache(username, game.getStr("gid")+"C", Double.parseDouble(homeOdds));
                // 记录客队的赔率
                apiUrlService.updateOddsCache(username, game.getStr("gid")+"H", Double.parseDouble(awayOdds));
            }

            // 半场胜平负
            if (game.containsKey("ior_HRMH") && 0 != game.getDouble("ior_HRMH")) {
                JSONObject homeOddsJson = new JSONObject();
                homeOddsJson.putOpt("id", game.getStr("gid"));          // 投注id
                homeOddsJson.putOpt("odds", game.getStr("ior_HRMH"));      // 投注赔率
                JSONObject awayOddsJson = new JSONObject();
                awayOddsJson.putOpt("id", game.getStr("gid"));         // 投注id
                awayOddsJson.putOpt("odds", game.getStr("ior_HRMC"));      // 投注赔率
                JSONObject drawOddsJson = new JSONObject();
                drawOddsJson.putOpt("id", game.getStr("gid"));         // 投注id
                drawOddsJson.putOpt("odds", game.getStr("ior_HRMN"));      // 投注赔率
                homeFirstHalf.putOpt("win", homeOddsJson);
                homeFirstHalf.putOpt("draw", drawOddsJson);
                awayFirstHalf.putOpt("win", awayOddsJson);
                awayFirstHalf.putOpt("draw", drawOddsJson);
                // 记录主队的赔率
                apiUrlService.updateOddsCache(username, game.getStr("gid")+"H", game.getDouble("ior_HRMH"));
                // 记录客队的赔率
                apiUrlService.updateOddsCache(username, game.getStr("gid")+"C", game.getDouble("ior_HRMC"));
                // 记录和的赔率
                apiUrlService.updateOddsCache(username, game.getStr("gid")+"N", game.getDouble("ior_HRMN"));
            }

        });

        homeFullCourt.putOpt("letBall", homeLetBall);
        awayFullCourt.putOpt("letBall", awayLetBall);

        homeFullCourt.putOpt("overSize", homeOverSize);
        awayFullCourt.putOpt("overSize", awayOverSize);

        // 组装主队和客队数据
        homeTeam.putOpt("fullCourt", homeFullCourt);
        homeTeam.putOpt("firstHalf", homeFirstHalf);
        awayTeam.putOpt("fullCourt", awayFullCourt);
        awayTeam.putOpt("firstHalf", awayFirstHalf);

        // 添加到事件列表
        events.add(homeTeam);
        events.add(awayTeam);
        leagueJson.putOpt("events", events);
        // 将事件列表添加到结果中
        result.add(leagueJson);

        responseJson.putOpt("success", true);
        responseJson.putOpt("leagues", result);
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

    /**
     * 替换handicap符号
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

    private void buildMarketData(JSONObject homeTeam, JSONObject awayTeam, JSONObject game) {
        // 全场数据处理
        JSONObject homeFull = new JSONObject();
        JSONObject awayFull = new JSONObject();
        // 让球盘
        processHandicap(homeFull, awayFull, game,
                "sw_ROUH", "ratio_rouho",
                "ior_ROUHO", "ior_ROUHU");

        // 大小盘
        processOverUnder(homeFull, awayFull, game,
                "sw_RE", "ratio_re",
                "ior_REH", "ior_REC");

        // 胜平负
        processWinDraw(homeFull, awayFull, game,
                "sw_RWM", "ior_RWMH1", "ior_RWMN", "ior_RWMC1");

        // 半场数据处理
        JSONObject homeHalf = new JSONObject();
        JSONObject awayHalf = new JSONObject();

        // 半场让球
        processHandicap(homeHalf, awayHalf, game,
                "sw_HRUH", "ratio_hruho",
                "ior_HRUHO", "ior_HRUHU");

        // 半场大小
        processOverUnder(homeHalf, awayHalf, game,
                "sw_HRE", "ratio_hre",
                "ior_HREH", "ior_HREC");

        // 半场胜平负
        processWinDraw(homeHalf, awayHalf, game,
                "sw_HRM", "ior_HRMH", "ior_HRMN", "ior_HRMC");

        homeTeam.putOpt("fullCourt", homeFull);
        homeTeam.putOpt("firstHalf", homeHalf);
        awayTeam.putOpt("fullCourt", awayFull);
        awayTeam.putOpt("firstHalf", awayHalf);
    }

    private void processHandicap(JSONObject home, JSONObject away, JSONObject game,
                                 String switchKey, String ratioKey,
                                 String homeOddsKey, String awayOddsKey) {
        if ("Y".equals(game.getStr(switchKey))) {
            JSONObject homeLet = new JSONObject();
            JSONObject awayLet = new JSONObject();

            // 解析盘口值（示例处理逻辑，根据实际数据结构调整）
            String[] ratios = game.getStr(ratioKey).split("/");
            for (String ratio : ratios) {
                JSONObject homeOddsJson = new JSONObject();
                homeOddsJson.putOpt("id", game.getStr("gid"));          // 投注id
                homeOddsJson.putOpt("odds", game.getStr(homeOddsKey));      // 投注赔率
                JSONObject awayOddsJson = new JSONObject();
                awayOddsJson.putOpt("id", game.getStr("gid"));         // 投注id
                awayOddsJson.putOpt("odds", game.getStr(awayOddsKey));      // 投注赔率
                String trimmedRatio = ratio.trim();
                homeLet.putOpt(trimmedRatio, homeOddsJson);
                awayLet.putOpt(trimmedRatio, awayOddsJson);
            }

            home.putOpt("letBall", homeLet);
            away.putOpt("letBall", awayLet);
        }
    }

    private void processOverUnder(JSONObject home, JSONObject away, JSONObject game,
                                  String switchKey, String ratioKey,
                                  String overOddsKey, String underOddsKey) {
        if ("Y".equals(game.getStr(switchKey))) {
            JSONObject homeOver = new JSONObject();
            JSONObject awayOver = new JSONObject();

            // 解析大小盘盘口
            String[] ouRatios = game.getStr(ratioKey).split("/");
            for (String ratio : ouRatios) {
                JSONObject homeOddsJson = new JSONObject();
                homeOddsJson.putOpt("id", game.getStr("gid"));          // 投注id
                homeOddsJson.putOpt("odds", game.getStr(overOddsKey));      // 投注赔率
                JSONObject awayOddsJson = new JSONObject();
                awayOddsJson.putOpt("id", game.getStr("gid"));         // 投注id
                awayOddsJson.putOpt("odds", game.getStr(underOddsKey));      // 投注赔率
                String trimmedRatio = ratio.trim();
                homeOver.putOpt(trimmedRatio, homeOddsJson);
                awayOver.putOpt(trimmedRatio, awayOddsJson);
            }

            home.putOpt("overSize", homeOver);
            away.putOpt("overSize", awayOver);
        }
    }

    private void processWinDraw(JSONObject home, JSONObject away, JSONObject game,
                                String switchKey, String homeWinKey,
                                String drawKey, String awayWinKey) {
        if ("Y".equals(game.getStr(switchKey))) {
            JSONObject homeOddsJson = new JSONObject();
            homeOddsJson.putOpt("id", game.getStr("gid"));          // 投注id
            homeOddsJson.putOpt("odds", game.getStr(homeWinKey));      // 投注赔率
            JSONObject awayOddsJson = new JSONObject();
            awayOddsJson.putOpt("id", game.getStr("gid"));         // 投注id
            awayOddsJson.putOpt("odds", game.getStr(awayWinKey));      // 投注赔率
            JSONObject drawOddsJson = new JSONObject();
            drawOddsJson.putOpt("id", game.getStr("gid"));         // 投注id
            drawOddsJson.putOpt("odds", game.getStr(drawKey));      // 投注赔率
            home.putOpt("win", homeOddsJson);
            home.putOpt("draw", drawOddsJson);
            away.putOpt("win", awayOddsJson);
            away.putOpt("draw", drawOddsJson);
        }
    }
    
}
