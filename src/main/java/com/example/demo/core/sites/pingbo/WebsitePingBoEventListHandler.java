package com.example.demo.core.sites.pingbo;

import cn.hutool.core.convert.Convert;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.common.enmu.ZhiBoSchedulesType;
import com.example.demo.config.HttpProxyConfig;
import com.example.demo.config.OkHttpProxyDispatcher;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 智博网站 - 赛事列表 API具体实现 用于操作页面查看赛事列表
 */
@Slf4j
@Component
public class WebsitePingBoEventListHandler implements ApiHandler {

    private final OkHttpProxyDispatcher dispatcher;
    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsitePingBoEventListHandler(OkHttpProxyDispatcher dispatcher, WebsiteService websiteService, ApiUrlService apiUrlService) {
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
        String custid = params.getStr("X-Custid");
        String browser = params.getStr("X-Browser-Session-Id");
        String slid = params.getStr("X-SLID");
        String lcu = params.getStr("X-Lcu");
        String cookie = "JSESSIONID=60080FF6CE15EA2EAE4212CCBE25C58E; pctag=48737fbc-cfb0-4199-b54b-32a6a57fc64e; dgRH0=6Zn5gZ2NOAamUly; skin=ps3838; b-user-id=86848c3d-24b8-fa15-e0c4-c26ae9df3b9a; _gid=GA1.2.1677352228.1736944373; _ga=GA1.2.1445030965.1736944373; PCTR=1894710782467; u=" + lcu + "; lcu=" + lcu + "; custid=" + custid + "; BrowserSessionId=" + browser + "; _og=QQ==; _ulp=KzhkT2JESFJ1US9xbC9rZkxDaHJZb3V2YVZlTCtKN2ViYnBYdGNCY0U2SzB4bnZpTVZaQWVwamhPQW5FSkNqS3xiOGZmZmEzZGNlM2Y0MGJiMmRlNDE2ZTEwYTMzMWM3Mg==; uoc=be97830afef253f33d2a502d243b8c37; _userDefaultView=COMPACT; SLID=" + slid + "; _sig=Tcy1OV014TnpZeVl6RTROek0wTXpjNE5nOjNjZWtOQmp0eUczZGhEVE5TcHZzYWVHRmU6MTcxMzE1ODI0NDo3MzY5NDQzODI6bm9uZTpXb2U1NlZ6M3Uw; _apt=Woe56Vz3u0; _ga_DXNRHBHDY9=GS1.1.1736944373.1.1.1736944383.50.0.1813848857; _ga_1YEJQEHQ55=GS1.1.1736944373.1.1.1736944383.50.0.0; _vid=dde4ede6a2ad88833c20148ab7cecb52; __prefs=W251bGwsMSwxLDAsMSxudWxsLGZhbHNlLDAuMDAwMCxmYWxzZSx0cnVlLCJfM0xJTkVTIiwxLG51bGwsdHJ1ZSx0cnVlLGZhbHNlLGZhbHNlLG51bGwsbnVsbCx0cnVlXQ==; lang=zh_CN; _lastView=eyJoNjEwMDAwMDAxIjoiQ09NUEFDVCJ9";
        // 构造请求头
        headers.put("accept", "*/*");
        headers.put("content-type", "application/x-www-form-urlencoded");
        headers.put("x-custid", custid);
        headers.put("cookie", cookie);

        return headers;
    }

    /**
     * 构建请求体
     * @param params 请求参数
     * @return HttpEntity 请求体
     */
    @Override
    public String buildRequest(JSONObject params) {
        // 构造请求体
        return null;
    }

    /**
     * 解析响应体
     * @param result 响应内容
     * @return 解析后的数据
     */
    @Override
    public JSONObject parseResponse(JSONObject params, OkHttpProxyDispatcher.HttpResult result) {

        // 检查响应状态
        if (result == null || result.getStatus() != 200) {
            JSONObject res = new JSONObject();
            int status = result != null ? result.getStatus() : -1;
            res.putOpt("code", status);
            res.putOpt("success", false);
            res.putOpt("msg", status == 403 ? "账户登录失效" : "获取赛事失败");
            return res;
        }
        // 解析响应
        JSONArray resultJson = new JSONArray();
        JSONObject responseJson = new JSONObject(result.getBody());
        String key = "";
        if (ZhiBoSchedulesType.LIVESCHEDULE.getId() == params.getInt("showType")) {
            // key为l的则是滚球列表数据，key为e的则是指定联赛的详情数据
            key = "l";
        } else {
            // key为的则是今日列表数据，key为e的则是指定联赛的详情数据
            key = "n";
        }
        // key为l的则是滚球列表数据，key为e的则是指定联赛的详情数据
        JSONArray l = responseJson.getJSONArray(key);
        if (!l.isEmpty()) {
            JSONArray events = l.getJSONArray(0).getJSONArray(2);
            if (!events.isEmpty()) {
                events.forEach(event -> {
                    JSONArray league = JSONUtil.parseArray(event);
                    JSONArray teams = league.getJSONArray(2);
                    if (!teams.isEmpty()) {
                        teams.forEach(team -> {
                            String session = "";
                            int reTime = 0;
                            int reTimeIndex = 0;    // 比赛时长索引下标
                            JSONArray teamArray = JSONUtil.parseArray(team);
                            // 因为teamArray长度会变动，无法直接通过固定下标获取到比赛时长，所以通过遍历取校验获取
                            for (int i = 0; i < teamArray.size(); i++) {
                                String value = teamArray.getStr(i).trim();
                                if ("1H".equals(value) || "2H".equals(value) || "HT".equals(value)) {
                                    session = value;
                                    // 时长下标是在场次上一个
                                    reTimeIndex = i - 1;
                                    break;
                                }
                            }

                            // 解析当前场次
                            JSONObject course = teamArray.getJSONObject(8);
                            // 开赛时长
                            String timeStr = "";
                            Object rawValue = teamArray.get(reTimeIndex);
                            if (rawValue != null) {
                                timeStr = rawValue.toString().replace("'", "");
                            }
                            reTime = StringUtils.isBlank(timeStr) ? 0 : Integer.parseInt(timeStr);
                            if ("HT".equalsIgnoreCase(session)) {
                                // 场间休息
                            } else if ("1H".equalsIgnoreCase(session)) {
                                // 上半场
                            } else if ("2H".equalsIgnoreCase(session)) {
                                // 下半场（即全场）
                                // 下半场时长需要加上上半场45分钟(固定加45分钟，不用管上半场有没有附加赛之类的)
                                reTime += 45;
                            }

                            // 获取当前比分
                            JSONArray scoreArray = JSONUtil.parseArray(team).getJSONArray(9);
                            String scoreStr = scoreArray.getInt(0) + "-" + scoreArray.getInt(1);

                            AtomicReference<String> wallHome = new AtomicReference<>();
                            AtomicReference<String> wallAway = new AtomicReference<>();
                            // 全场
                            if (course.containsKey("0")) {
                                /** 全场 start */
                                // 让球盘赔率
                                JSONArray letBallJson = course.getJSONArray("0").getJSONArray(0);
                                // 大小盘赔率
                                JSONArray sizeBallJson = course.getJSONArray("0").getJSONArray(1);
                                // 平手盘赔率
                                JSONArray drawBallJson = course.getJSONArray("0").getJSONArray(2);

                                // 获取让球盘赔率
                                for (Object letBall : letBallJson) {
                                    JSONObject leagueJson = new JSONObject();
                                    leagueJson.putOpt("id", league.getStr(0));
                                    leagueJson.putOpt("league", league.getStr(1));
                                    leagueJson.putOpt("type", "fullCourt");             // 赛事类型
                                    leagueJson.putOpt("handicapType", "letBall");       // 盘口类型
                                    leagueJson.putOpt("reTime", reTime);                // 时间
                                    leagueJson.putOpt("eventId", JSONUtil.parseArray(team).getStr(0));
                                    leagueJson.putOpt("homeTeam", JSONUtil.parseArray(team).getStr(1));
                                    leagueJson.putOpt("awayTeam", JSONUtil.parseArray(team).getStr(2));
                                    leagueJson.putOpt("score", scoreStr);               // 比分

                                    JSONArray letBallJsonArr = JSONUtil.parseArray(letBall);

                                    int homeOddsIdIndex1 = 0;   // 全场=0;上半场=1
                                    int homeOddsIdIndex2 = 2;   // 输赢盘=1;让球盘=2;大小盘=3
                                    int homeOddsIdIndex3 = 0;   // 主队(输赢盘=0,让球盘=0,大小盘=3);客队=(输赢盘=1,让球盘=1,大小盘=4);平局=2
                                    int homeOddsIdIndex4 = letBallJsonArr.getInt(8);   // 好像是按照比分，不好描述，可边查看平博网站滚球列表页面对应，每个比赛的3个数据，上=1，中=0，下=1

                                    int awayOddsIdIndex1 = 0;   // 全场=0;上半场=1
                                    int awayOddsIdIndex2 = 2;   // 输赢盘=1;让球盘=2;大小盘=3
                                    int awayOddsIdIndex3 = 1;   // 主队(输赢盘=0,让球盘=0,大小盘=3);客队=(输赢盘=1,让球盘=1,大小盘=4);平局=2
                                    int awayOddsIdIndex4 = letBallJsonArr.getInt(8);   // 好像是按照比分，不好描述，可边查看平博网站滚球列表页面对应，每个比赛的3个数据，上=1，中=0，下=1

                                    String homeOddsId = JSONUtil.parseArray(team).getStr(0) + "|"+homeOddsIdIndex1+"|"+homeOddsIdIndex2+"|"+homeOddsIdIndex3+"|"+homeOddsIdIndex4+"|" + letBallJsonArr.getStr(1);           // 主队投注id
                                    leagueJson.putOpt("homeBetId", homeOddsId);  // 投注id
                                    leagueJson.putOpt("homeSelectionId", letBallJsonArr.getStr(7) + "|" + homeOddsId + "|0");  // 投注id
                                    leagueJson.putOpt("homeHandicap", letBallJsonArr.get(0));           // 盘口
                                    leagueJson.putOpt("homeOdds", letBallJsonArr.getStr(3));       // 投注赔率
                                    double handicapHome = letBallJsonArr.getDouble(0);
                                    if (StringUtils.isBlank(wallHome.get())) {
                                        if (handicapHome < 0) {
                                            // 让球，上盘
                                            wallHome.set("hanging");
                                        } else if (handicapHome > 0) {
                                            // 被让球，下盘
                                            wallHome.set("foot");
                                        }
                                    }
                                    leagueJson.putOpt("homeWall", wallHome.get());    // hanging=上盘,foot=下盘

                                    String awayOddsId = JSONUtil.parseArray(team).getStr(0) + "|"+awayOddsIdIndex1+"|"+awayOddsIdIndex2+"|"+awayOddsIdIndex3+"|"+awayOddsIdIndex4+"|" + letBallJsonArr.getStr(1);           // 客队投注id
                                    leagueJson.putOpt("awayBetId", awayOddsId);  // 投注id
                                    leagueJson.putOpt("awaySelectionId", letBallJsonArr.getStr(7) + "|" + awayOddsId + "|1");  // 投注id
                                    leagueJson.putOpt("awayHandicap", letBallJsonArr.get(1));
                                    leagueJson.putOpt("awayOdds", letBallJsonArr.getStr(4));                    // 投注赔率
                                    double handicapAway = letBallJsonArr.getDouble(1);
                                    if (StringUtils.isBlank(wallAway.get())) {
                                        if (handicapAway < 0) {
                                            // 让球，上盘
                                            wallAway.set("hanging");
                                        } else if (handicapAway > 0) {
                                            // 被让球，下盘
                                            wallAway.set("foot");
                                        }
                                    }
                                    leagueJson.putOpt("awayWall", wallAway.get());    // hanging=上盘,foot=下盘
                                    resultJson.put(leagueJson);
                                }

                                // 获取大小球赔率
                                for (Object sizeBall : sizeBallJson) {
                                    JSONObject leagueJson = new JSONObject();
                                    leagueJson.putOpt("id", league.getStr(0));
                                    leagueJson.putOpt("league", league.getStr(1));
                                    leagueJson.putOpt("type", "fullCourt");             // 赛事类型
                                    leagueJson.putOpt("handicapType", "overSize");      // 盘口类型
                                    leagueJson.putOpt("reTime", reTime);                // 时间
                                    leagueJson.putOpt("eventId", JSONUtil.parseArray(team).getStr(0));
                                    leagueJson.putOpt("homeTeam", JSONUtil.parseArray(team).getStr(1));
                                    leagueJson.putOpt("awayTeam", JSONUtil.parseArray(team).getStr(2));
                                    leagueJson.putOpt("score", scoreStr);               // 比分

                                    JSONArray sizeBallJsonArr = JSONUtil.parseArray(sizeBall);

                                    int homeOddsIdIndex1 = 0;   // 全场=0;上半场=1
                                    int homeOddsIdIndex2 = 3;   // 输赢盘=1;让球盘=2;大小盘=3
                                    int homeOddsIdIndex3 = 3;   // 主队(输赢盘=0,让球盘=0,大小盘=3);客队=(输赢盘=1,让球盘=1,大小盘=4);平局=2
                                    int homeOddsIdIndex4 = sizeBallJsonArr.getInt(5);   // 好像是按照比分，不好描述，可边查看平博网站滚球列表页面对应，每个比赛的3个数据，上=1，中=0，下=1

                                    int awayOddsIdIndex1 = 0;   // 全场=0;上半场=1
                                    int awayOddsIdIndex2 = 3;   // 输赢盘=1;让球盘=2;大小盘=3
                                    int awayOddsIdIndex3 = 4;   // 主队(输赢盘=0,让球盘=0,大小盘=3);客队=(输赢盘=1,让球盘=1,大小盘=4);平局=2
                                    int awayOddsIdIndex4 = sizeBallJsonArr.getInt(5);   // 好像是按照比分，不好描述，可边查看平博网站滚球列表页面对应，每个比赛的3个数据，上=1，中=0，下=1

                                    String homeOddsId = JSONUtil.parseArray(team).getStr(0) + "|"+homeOddsIdIndex1+"|"+homeOddsIdIndex2+"|"+homeOddsIdIndex3+"|"+homeOddsIdIndex4+"|" + sizeBallJsonArr.getStr(1);           // 主队投注id
                                    leagueJson.putOpt("homeBetId", homeOddsId);      // 投注id
                                    leagueJson.putOpt("homeSelectionId", sizeBallJsonArr.getStr(4) + "|" + homeOddsId + "|0");  // 投注id
                                    leagueJson.putOpt("homeHandicap", sizeBallJsonArr.get(1));
                                    leagueJson.putOpt("homeOdds", sizeBallJsonArr.getStr(2));                         // 投注赔率

                                    String awayOddsId = JSONUtil.parseArray(team).getStr(0) + "|"+awayOddsIdIndex1+"|"+awayOddsIdIndex2+"|"+awayOddsIdIndex3+"|"+awayOddsIdIndex4+"|" + sizeBallJsonArr.getStr(1);           // 客队投注id
                                    leagueJson.putOpt("awayBetId", awayOddsId);      // 投注id
                                    leagueJson.putOpt("awaySelectionId", sizeBallJsonArr.getStr(4) + "|" + awayOddsId + "|1");  // 投注id
                                    leagueJson.putOpt("awayHandicap", sizeBallJsonArr.get(1));
                                    leagueJson.putOpt("awayOdds", sizeBallJsonArr.getStr(3));                         // 投注赔率
                                    resultJson.put(leagueJson);
                                }
                            }

                            // 上半场
                            if (course.containsKey("1")) {
                                // 让球盘赔率
                                JSONArray firstHalfLetBallJson = course.getJSONArray("1").getJSONArray(0);
                                // 大小盘赔率
                                JSONArray firstHalfSizeBallJson = course.getJSONArray("1").getJSONArray(1);
                                // 平手盘赔率
                                JSONArray firstHalfDrawBallJson = course.getJSONArray("1").getJSONArray(2);
                                // 上半场-让球
                                for(Object letBall : firstHalfLetBallJson) {
                                    JSONObject leagueJson = new JSONObject();
                                    leagueJson.putOpt("id", league.getStr(0));
                                    leagueJson.putOpt("league", league.getStr(1));
                                    leagueJson.putOpt("type", "firstHalf");             // 赛事类型
                                    leagueJson.putOpt("handicapType", "letBall");       // 盘口类型
                                    leagueJson.putOpt("reTime", reTime);                // 时间
                                    leagueJson.putOpt("eventId", JSONUtil.parseArray(team).getStr(0));
                                    leagueJson.putOpt("homeTeam", JSONUtil.parseArray(team).getStr(1));
                                    leagueJson.putOpt("awayTeam", JSONUtil.parseArray(team).getStr(2));
                                    leagueJson.putOpt("score", scoreStr);               // 比分
                                    JSONArray letBallJsonArr = JSONUtil.parseArray(letBall);

                                    int homeOddsIdIndex1 = 1;   // 全场=0;上半场=1
                                    int homeOddsIdIndex2 = 2;   // 输赢盘=1;让球盘=2;大小盘=3
                                    int homeOddsIdIndex3 = 0;   // 主队(输赢盘=0,让球盘=0,大小盘=3);客队=(输赢盘=1,让球盘=1,大小盘=4);平局=2
                                    int homeOddsIdIndex4 = letBallJsonArr.getInt(8);   // 好像是按照比分，不好描述，可边查看平博网站滚球列表页面对应，每个比赛的3个数据，上=1，中=0，下=1

                                    int awayOddsIdIndex1 = 1;   // 全场=0;上半场=1
                                    int awayOddsIdIndex2 = 2;   // 输赢盘=1;让球盘=2;大小盘=3
                                    int awayOddsIdIndex3 = 1;   // 主队(输赢盘=0,让球盘=0,大小盘=3);客队=(输赢盘=1,让球盘=1,大小盘=4);平局=2
                                    int awayOddsIdIndex4 = letBallJsonArr.getInt(8);   // 好像是按照比分，不好描述，可边查看平博网站滚球列表页面对应，每个比赛的3个数据，上=1，中=0，下=1

                                    String homeOddsId = JSONUtil.parseArray(team).getStr(0) + "|"+homeOddsIdIndex1+"|"+homeOddsIdIndex2+"|"+homeOddsIdIndex3+"|"+homeOddsIdIndex4+"|" + letBallJsonArr.getStr(1);           // 主队投注id
                                    leagueJson.putOpt("homeBetId", homeOddsId);                              // 投注id
                                    leagueJson.putOpt("homeSelectionId", letBallJsonArr.getStr(7) + "|" + homeOddsId + "|0");  // 投注id
                                    leagueJson.putOpt("homeHandicap", letBallJsonArr.get(0));
                                    leagueJson.putOpt("homeOdds", letBallJsonArr.getStr(3)); // 投注赔率
                                    double handicapHome = letBallJsonArr.getDouble(0);
                                    if (StringUtils.isBlank(wallAway.get())) {
                                        if (handicapHome < 0) {
                                            // 让球，上盘
                                            wallAway.set("hanging");
                                        } else if (handicapHome > 0) {
                                            // 被让球，下盘
                                            wallAway.set("foot");
                                        }
                                    }
                                    leagueJson.putOpt("homeWall", wallAway.get());    // hanging=上盘,foot=下盘

                                    String awayOddsId = JSONUtil.parseArray(team).getStr(0) + "|"+awayOddsIdIndex1+"|"+awayOddsIdIndex2+"|"+awayOddsIdIndex3+"|"+awayOddsIdIndex4+"|" + letBallJsonArr.getStr(1);           // 客队投注id
                                    leagueJson.putOpt("awayBetId", awayOddsId);                              // 投注id
                                    leagueJson.putOpt("awaySelectionId", letBallJsonArr.getStr(7) + "|" + awayOddsId + "|1");  // 投注id
                                    leagueJson.putOpt("awayHandicap", letBallJsonArr.get(1));
                                    leagueJson.putOpt("awayOdds", letBallJsonArr.getStr(4)); // 投注赔率
                                    double handicapAway = letBallJsonArr.getDouble(1);
                                    if (StringUtils.isBlank(wallAway.get())) {
                                        if (handicapAway < 0) {
                                            // 让球，上盘
                                            wallAway.set("hanging");
                                        } else if (handicapAway > 0) {
                                            // 被让球，下盘
                                            wallAway.set("foot");
                                        }
                                    }
                                    leagueJson.putOpt("awayWall", wallAway.get());    // hanging=上盘,foot=下盘
                                    resultJson.put(leagueJson);
                                }
                                // 上半场-大小球
                                for(Object sizeBall : firstHalfSizeBallJson) {
                                    JSONObject leagueJson = new JSONObject();
                                    leagueJson.putOpt("id", league.getStr(0));
                                    leagueJson.putOpt("league", league.getStr(1));
                                    leagueJson.putOpt("type", "firstHalf");             // 赛事类型
                                    leagueJson.putOpt("handicapType", "overSize");      // 盘口类型
                                    leagueJson.putOpt("reTime", reTime);                // 时间
                                    leagueJson.putOpt("eventId", JSONUtil.parseArray(team).getStr(0));
                                    leagueJson.putOpt("homeTeam", JSONUtil.parseArray(team).getStr(1));
                                    leagueJson.putOpt("awayTeam", JSONUtil.parseArray(team).getStr(2));
                                    leagueJson.putOpt("score", scoreStr);               // 比分
                                    JSONArray sizeBallJsonArr = (JSONArray) sizeBall;

                                    int homeOddsIdIndex1 = 1;   // 全场=0;上半场=1
                                    int homeOddsIdIndex2 = 3;   // 输赢盘=1;让球盘=2;大小盘=3
                                    int homeOddsIdIndex3 = 3;   // 主队(输赢盘=0,让球盘=0,大小盘=3);客队=(输赢盘=1,让球盘=1,大小盘=4);平局=2
                                    int homeOddsIdIndex4 = sizeBallJsonArr.getInt(5);   // 好像是按照比分，不好描述，可边查看平博网站滚球列表页面对应，每个比赛的3个数据，上=1，中=0，下=1

                                    int awayOddsIdIndex1 = 1;   // 全场=0;上半场=1
                                    int awayOddsIdIndex2 = 3;   // 输赢盘=1;让球盘=2;大小盘=3
                                    int awayOddsIdIndex3 = 4;   // 主队(输赢盘=0,让球盘=0,大小盘=3);客队=(输赢盘=1,让球盘=1,大小盘=4);平局=2
                                    int awayOddsIdIndex4 = sizeBallJsonArr.getInt(5);   // 好像是按照比分，不好描述，可边查看平博网站滚球列表页面对应，每个比赛的3个数据，上=1，中=0，下=1

                                    String homeOddsId = JSONUtil.parseArray(team).getStr(0) + "|"+homeOddsIdIndex1+"|"+homeOddsIdIndex2+"|"+homeOddsIdIndex3+"|"+homeOddsIdIndex4+"|" + sizeBallJsonArr.getStr(1);           // 主队投注id
                                    leagueJson.putOpt("homeBetId", homeOddsId);                              // 投注id
                                    leagueJson.putOpt("homeSelectionId", sizeBallJsonArr.getStr(4) + "|" + homeOddsId + "|0");  // 投注id
                                    leagueJson.putOpt("homeHandicap", sizeBallJsonArr.get(1));
                                    leagueJson.putOpt("homeOdds", sizeBallJsonArr.getStr(2)); // 投注赔率

                                    String awayOddsId = JSONUtil.parseArray(team).getStr(0) + "|"+awayOddsIdIndex1+"|"+awayOddsIdIndex2+"|"+awayOddsIdIndex3+"|"+awayOddsIdIndex4+"|" + sizeBallJsonArr.getStr(1);           // 客队投注id
                                    leagueJson.putOpt("awayBetId", awayOddsId);                              // 投注id
                                    leagueJson.putOpt("awaySelectionId", sizeBallJsonArr.getStr(4) + "|" + awayOddsId + "|1");  // 投注id
                                    leagueJson.putOpt("awayHandicap", sizeBallJsonArr.get(1));
                                    leagueJson.putOpt("awayOdds", sizeBallJsonArr.getStr(3)); // 投注赔率
                                    resultJson.put(leagueJson);
                                }
                            }
                        });
                    }
                });
            }
        }
        responseJson.putOpt("success", true);
        responseJson.putOpt("leagues", resultJson);
        responseJson.putOpt("msg", "获取赛事列表成功");
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
        // 默认me为0表示查询所有联赛
        int me = 0;
        String c = "";
        boolean hle = false;
        int mk = 1;
        boolean more = false;
        int o = 1;
        int ot = params.getInt("oddsFormatType");     // 赔率类型
        String sp = "29";
        if (params.containsKey("me")) {
            // 存在me参数即表示查询的是指定联赛而不是列表
            me = params.getInt("me");
            c = "Others";
            hle = true;
            mk = 3;
            more = true;
            o = 0;
            sp = "";
        }
        // 构建请求
        Map<String, String> requestHeaders = buildHeaders(params);
        String requestBody = buildRequest(params);

        // 构造请求体
        String queryParams = String.format("btg=1&c=%s&cl=3&d=&ec=&ev=&g=QQ==&hle=%s&ic=false&inl=false&l=3&lang=&lg=&lv=&me=%s&mk=%s&more=%s&o=%s&ot=%s&pa=0&pimo=0,1,8,39,2,3,6,7,4,5&pn=-1&pv=1&sp=%s&tm=0&v=0&locale=zh_CN&_=%s&withCredentials=true",
                c,
                hle,
                me,
                mk,
                more,
                o,
                ot,
                sp,
                System.currentTimeMillis()
        );

        // 拼接完整的 URL
        String fullUrl = String.format("%s%s?%s", baseUrl, apiUrl, queryParams);

        // 使用代理发起 GET 请求
        OkHttpProxyDispatcher.HttpResult resultHttp;
        try {
            resultHttp = dispatcher.execute("GET", fullUrl, requestBody, requestHeaders, userConfig, false);
        } catch (Exception e) {
            log.error("请求异常，用户:{}, 账号:{}, 参数:{}, 错误:{}", username, userConfig.getAccount(), requestBody, e.getMessage(), e);
            throw new BusinessException(SystemError.SYS_400);
        }
        // 解析响应并返回
        return parseResponse(params, resultHttp);
    }
}
