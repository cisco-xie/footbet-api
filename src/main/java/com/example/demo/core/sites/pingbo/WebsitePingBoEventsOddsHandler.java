package com.example.demo.core.sites.pingbo;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.core.factory.ApiHandler;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 智博网站 - 赛事列表-带赔率 API具体实现
 * 返回的结构概览如下
 * {
 *             "id": "197646",                      // 赛事ID
 *             "league": "英格兰 - 乙级联赛 U21",      // 赛事名称
 *             "events": [
 *                 {
 *                     "id": "1604558081",          // 赛事ID或队伍ID
 *                     "name": "埃弗顿",             // 队伍名称
 *                     "fullCourt": {               // 赛程-全场
 *                         "letBall": {             // 让球盘
 *                             "0-0.5": "2.130",
 *                             "0.5": "1.884",
 *                             "0.5-1": "1.671"
 *                         },
 *                         "overSize": {            // 大小盘
 *                             "3-3.5": "2.180",
 *                             "3.0": "1.909",
 *                             "2.5-3": "1.680"
 *                         },
 "                          win": "4.290",          // 平局盘平手盘 胜
 "                          draw": "3.840"          // 平局盘平手盘 平
 *                     },
 *                     "firstHalf": {               // 赛程-上半场
 *                         "letBall": {
 *                             "0-0.5": "1.609",
 *                             "0.0": "2.190"
 *                         },
 *                         "overSize": {
 *                             "1-1.5": "2.680",
 *                             "1.0": "2.100",
 *                             "0.5-1": "1.657"
 *                         },
 "                         win": "4.290",          // 平局盘平手盘 胜
 "                         draw": "3.840"          // 平局盘平手盘 平
 *                     }
 *                 },
 *                 {
 *                     "id": "1604558081",
 *                     "name": "南安普顿",
 *                     "fullCourt": {
 *                         "letBall": {
 *                             "0-0.5": "1.714",
 *                             "0.5": "1.934",
 *                             "0.5-1": "2.200"
 *                         },
 *                         "overSize": {
 *                             "3-3.5": "1.671",
 *                             "3.0": "1.892",
 *                             "2.5-3": "2.160"
 *                         },
 "                         win": "4.290",          // 平局盘平手盘 胜
 "                         draw": "3.840"          // 平局盘平手盘 平
 *                     },
 *                     "firstHalf": {
 *                         "letBall": {
 *                             "0-0.5": "2.290",
 *                             "0.0": "1.662"
 *                         },
 *                         "overSize": {
 *                             "1-1.5": "1.452",
 *                             "1.0": "1.704",
 *                             "0.5-1": "2.180"
 *                         },
 "                         win": "4.290",          // 平局盘平手盘 胜
 "                         draw": "3.840"          // 平局盘平手盘 平
 *                     }
 *                 }
 *             ]
 *         }
 */

/**
 * 平博网站 - 赛事赔率 API具体实现
 */
@Component
public class WebsitePingBoEventsOddsHandler implements ApiHandler {

    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsitePingBoEventsOddsHandler(WebsiteService websiteService, ApiUrlService apiUrlService) {
        this.websiteService = websiteService;
        this.apiUrlService = apiUrlService;
    }

    /**
     * 构建请求体
     * @param params 请求参数
     * @return HttpEntity 请求体
     */
    @Override
    public HttpEntity<String> buildRequest(JSONObject params) {
        String custid = params.getStr("X-Custid");
        String browser = params.getStr("X-Browser-Session-Id");
        String slid = params.getStr("X-SLID");
        String lcu = params.getStr("X-Lcu");
        String cookie = "JSESSIONID=60080FF6CE15EA2EAE4212CCBE25C58E; pctag=48737fbc-cfb0-4199-b54b-32a6a57fc64e; dgRH0=6Zn5gZ2NOAamUly; skin=ps3838; b-user-id=86848c3d-24b8-fa15-e0c4-c26ae9df3b9a; _gid=GA1.2.1677352228.1736944373; _ga=GA1.2.1445030965.1736944373; PCTR=1894710782467; u=" + lcu + "; lcu=" + lcu + "; custid=" + custid + "; BrowserSessionId=" + browser + "; _og=QQ==; _ulp=KzhkT2JESFJ1US9xbC9rZkxDaHJZb3V2YVZlTCtKN2ViYnBYdGNCY0U2SzB4bnZpTVZaQWVwamhPQW5FSkNqS3xiOGZmZmEzZGNlM2Y0MGJiMmRlNDE2ZTEwYTMzMWM3Mg==; uoc=be97830afef253f33d2a502d243b8c37; _userDefaultView=COMPACT; SLID=" + slid + "; _sig=Tcy1OV014TnpZeVl6RTROek0wTXpjNE5nOjNjZWtOQmp0eUczZGhEVE5TcHZzYWVHRmU6MTcxMzE1ODI0NDo3MzY5NDQzODI6bm9uZTpXb2U1NlZ6M3Uw; _apt=Woe56Vz3u0; _ga_DXNRHBHDY9=GS1.1.1736944373.1.1.1736944383.50.0.1813848857; _ga_1YEJQEHQ55=GS1.1.1736944373.1.1.1736944383.50.0.0; _vid=dde4ede6a2ad88833c20148ab7cecb52; __prefs=W251bGwsMSwxLDAsMSxudWxsLGZhbHNlLDAuMDAwMCxmYWxzZSx0cnVlLCJfM0xJTkVTIiwxLG51bGwsdHJ1ZSx0cnVlLGZhbHNlLGZhbHNlLG51bGwsbnVsbCx0cnVlXQ==; lang=zh_CN; _lastView=eyJoNjEwMDAwMDAxIjoiQ09NUEFDVCJ9";
        // 构造请求头
        HttpHeaders headers = new HttpHeaders();
        headers.add("accept", "*/*");
        headers.add("content-type", "application/x-www-form-urlencoded");
        headers.add("x-custid", custid);
        headers.add("cookie", cookie);

        return new HttpEntity<>(headers);
    }

    /**
     * 解析响应体
     * 获取赔率后解析计算下注请求需要的oddsId和selectionId
     * 例子：
     * oddsId=1609168865|0|2|0|1|-1.75              {比赛id}|{全场=0;上半场=1}|{输赢盘=1;让球盘=2;大小盘=3}|{主队(输赢盘=0,让球盘=0,大小盘=3);客队=(输赢盘=1,让球盘=1,大小盘=4);平局=2}|{好像是按照比分，不好描述，可边查看平博网站滚球列表页面对应，每个比赛的3个数据，上=1，中=0，下=1}|{赔率}
     * selectionId=1609168865|0|2|0|1|-1.75|1      oddsId|{主队=0,客队=1}
     * @param response 响应内容
     * @return 解析后的数据
     */
    @Override
    public JSONObject parseResponse(JSONObject params, HttpResponse response) {

        // 检查响应状态
        if (response.getStatus() != 200) {
            JSONObject res = new JSONObject();
            res.putOpt("success", false);
            if (response.getStatus() == 403) {
                res.putOpt("msg", "账户登录失效");
                return res;
            }
            res.putOpt("msg", "获取赛事失败");
            return res;
        }
        // 解析响应
        JSONArray result = new JSONArray();
        JSONObject responseJson = new JSONObject(response.body());
        // key为l的则是列表数据，key为e的则是指定联赛的详情数据
        JSONArray l = responseJson.getJSONArray("l");
        if (!l.isEmpty()) {
            JSONArray events = l.getJSONArray(0).getJSONArray(2);
            if (!events.isEmpty()) {
                events.forEach(event -> {
                    JSONArray league = JSONUtil.parseArray(event);
                    JSONObject leagueJson = new JSONObject();
                    leagueJson.putOpt("id", league.getStr(0));
                    leagueJson.putOpt("league", league.getStr(1));
                    JSONArray teams = league.getJSONArray(2);
                    if (!teams.isEmpty()) {
                        JSONArray teamsJson = new JSONArray();
                        teams.forEach(team -> {
                            String session = "";
                            int reTime = 0;
                            int reTimeIndex = 0;    // 比赛时长索引下标
                            JSONArray teamArray = JSONUtil.parseArray(team);
                            // 因为teamArray长度会变动，无法直接通过固定下标获取到比赛时长，所以通过遍历取校验获取
                            for (int i = 0; i < teamArray.size(); i++) {
                                System.out.println("Index " + i + ": " + teamArray.get(i));
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
                            // 全场
                            JSONObject fullHomeCourt = new JSONObject();
                            JSONObject fullAwayCourt = new JSONObject();
                            // 上半场
                            JSONObject firstHalfHomeCourt = new JSONObject();
                            JSONObject firstHalfAwayCourt = new JSONObject();

                            // 主队
                            JSONObject eventHomeJson = new JSONObject();
                            // 客队
                            JSONObject eventAwayJson = new JSONObject();

                            AtomicReference<String> wallHome = new AtomicReference<>();
                            AtomicReference<String> wallAway = new AtomicReference<>();
                            if (course.containsKey("0")) {
                                /** 全场 start */
                                // 让球盘赔率
                                JSONArray letBallJson = course.getJSONArray("0").getJSONArray(0);
                                // 大小盘赔率
                                JSONArray sizeBallJson = course.getJSONArray("0").getJSONArray(1);
                                // 平手盘赔率
                                JSONArray drawBallJson = course.getJSONArray("0").getJSONArray(2);

                                JSONObject letHomeJson = new JSONObject();
                                JSONObject letAwayJson = new JSONObject();

                                int positionLetBall = 0;
                                for (Object letBall : letBallJson) {
                                    JSONArray letBallJsonArr = (JSONArray) letBall;
                                    JSONObject homeOddsJson = new JSONObject();

                                    int homeOddsIdIndex1 = 0;   // 全场=0;上半场=1
                                    int homeOddsIdIndex2 = 2;   // 输赢盘=1;让球盘=2;大小盘=3
                                    int homeOddsIdIndex3 = 0;   // 主队(输赢盘=0,让球盘=0,大小盘=3);客队=(输赢盘=1,让球盘=1,大小盘=4);平局=2
                                    int homeOddsIdIndex4 = letBallJsonArr.getInt(8);   // 好像是按照比分，不好描述，可边查看平博网站滚球列表页面对应，每个比赛的3个数据，上=1，中=0，下=1

                                    int awayOddsIdIndex1 = 0;   // 全场=0;上半场=1
                                    int awayOddsIdIndex2 = 2;   // 输赢盘=1;让球盘=2;大小盘=3
                                    int awayOddsIdIndex3 = 1;   // 主队(输赢盘=0,让球盘=0,大小盘=3);客队=(输赢盘=1,让球盘=1,大小盘=4);平局=2
                                    int awayOddsIdIndex4 = letBallJsonArr.getInt(8);   // 好像是按照比分，不好描述，可边查看平博网站滚球列表页面对应，每个比赛的3个数据，上=1，中=0，下=1

                                    String homeOddsId = JSONUtil.parseArray(team).getStr(0) + "|"+homeOddsIdIndex1+"|"+homeOddsIdIndex2+"|"+homeOddsIdIndex3+"|"+homeOddsIdIndex4+"|" + letBallJsonArr.getStr(1);           // 主队投注id
                                    homeOddsJson.putOpt("id", homeOddsId);  // 投注id
                                    if (positionLetBall == 1) {
                                        homeOddsJson.putOpt("selectionId", letBallJsonArr.getStr(7) + "|" + homeOddsId + "|0");  // 投注id
                                    } else {
                                        homeOddsJson.putOpt("selectionId", letBallJsonArr.getStr(7) + "|" + homeOddsId + "|0");  // 投注id
                                    }
                                    homeOddsJson.putOpt("odds", letBallJsonArr.getStr(3));                    // 投注赔率
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
                                    homeOddsJson.putOpt("wall", wallHome.get());    // hanging=上盘,foot=下盘

                                    JSONObject awayOddsJson = new JSONObject();
                                    String awayOddsId = JSONUtil.parseArray(team).getStr(0) + "|"+awayOddsIdIndex1+"|"+awayOddsIdIndex2+"|"+awayOddsIdIndex3+"|"+awayOddsIdIndex4+"|" + letBallJsonArr.getStr(1);           // 客队投注id
                                    awayOddsJson.putOpt("id", awayOddsId);  // 投注id
                                    if (positionLetBall == 1) {
                                        awayOddsJson.putOpt("selectionId", letBallJsonArr.getStr(7) + "|" + awayOddsId + "|1");  // 投注id
                                    } else {
                                        awayOddsJson.putOpt("selectionId", letBallJsonArr.getStr(7) + "|" + awayOddsId + "|1");  // 投注id
                                    }
                                    awayOddsJson.putOpt("odds", letBallJsonArr.getStr(4));                    // 投注赔率
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
                                    awayOddsJson.putOpt("wall", wallAway.get());    // hanging=上盘,foot=下盘

                                    String letKey = "0.0".equals(letBallJsonArr.getStr(2)) ? "0" : letBallJsonArr.getStr(2);
                                    letHomeJson.putOpt(letKey, homeOddsJson);
                                    letAwayJson.putOpt(letKey, awayOddsJson);
                                    positionLetBall++;
                                };
                                JSONObject sizeHomeJson = new JSONObject();
                                JSONObject sizeAwayJson = new JSONObject();
                                int positionSizeBall = 0;
                                for (Object sizeBall : sizeBallJson) {
                                    JSONArray sizeBallJsonArr = (JSONArray) sizeBall;
                                    JSONObject homeOddsJson = new JSONObject();

                                    int homeOddsIdIndex1 = 0;   // 全场=0;上半场=1
                                    int homeOddsIdIndex2 = 3;   // 输赢盘=1;让球盘=2;大小盘=3
                                    int homeOddsIdIndex3 = 3;   // 主队(输赢盘=0,让球盘=0,大小盘=3);客队=(输赢盘=1,让球盘=1,大小盘=4);平局=2
                                    int homeOddsIdIndex4 = sizeBallJsonArr.getInt(5);   // 好像是按照比分，不好描述，可边查看平博网站滚球列表页面对应，每个比赛的3个数据，上=1，中=0，下=1

                                    int awayOddsIdIndex1 = 0;   // 全场=0;上半场=1
                                    int awayOddsIdIndex2 = 3;   // 输赢盘=1;让球盘=2;大小盘=3
                                    int awayOddsIdIndex3 = 4;   // 主队(输赢盘=0,让球盘=0,大小盘=3);客队=(输赢盘=1,让球盘=1,大小盘=4);平局=2
                                    int awayOddsIdIndex4 = sizeBallJsonArr.getInt(5);   // 好像是按照比分，不好描述，可边查看平博网站滚球列表页面对应，每个比赛的3个数据，上=1，中=0，下=1

                                    String homeOddsId = JSONUtil.parseArray(team).getStr(0) + "|"+homeOddsIdIndex1+"|"+homeOddsIdIndex2+"|"+homeOddsIdIndex3+"|"+homeOddsIdIndex4+"|" + sizeBallJsonArr.getStr(1);           // 主队投注id
                                    homeOddsJson.putOpt("id", homeOddsId);      // 投注id
                                    if (positionSizeBall == 1) {
                                        homeOddsJson.putOpt("selectionId", sizeBallJsonArr.getStr(4) + "|" + homeOddsId + "|0");  // 投注id
                                    } else {
                                        homeOddsJson.putOpt("selectionId", sizeBallJsonArr.getStr(4) + "|" + homeOddsId + "|0");  // 投注id
                                    }
                                    homeOddsJson.putOpt("odds", sizeBallJsonArr.getStr(2));                         // 投注赔率
                                    JSONObject awayOddsJson = new JSONObject();
                                    String awayOddsId = JSONUtil.parseArray(team).getStr(0) + "|"+awayOddsIdIndex1+"|"+awayOddsIdIndex2+"|"+awayOddsIdIndex3+"|"+awayOddsIdIndex4+"|" + sizeBallJsonArr.getStr(1);           // 客队投注id
                                    awayOddsJson.putOpt("id", awayOddsId);      // 投注id
                                    if (positionSizeBall == 1) {
                                        awayOddsJson.putOpt("selectionId", sizeBallJsonArr.getStr(4) + "|" + awayOddsId + "|1");  // 投注id
                                    } else {
                                        awayOddsJson.putOpt("selectionId", sizeBallJsonArr.getStr(4) + "|" + awayOddsId + "|1");  // 投注id
                                    }
                                    awayOddsJson.putOpt("odds", sizeBallJsonArr.getStr(3));                         // 投注赔率
                                    String sizeKey = "0.0".equals(sizeBallJsonArr.getStr(0)) ? "0" : sizeBallJsonArr.getStr(0);
                                    sizeHomeJson.putOpt(sizeKey, homeOddsJson);
                                    sizeAwayJson.putOpt(sizeKey, awayOddsJson);
                                    positionSizeBall++;
                                }
                                /** 全场 end */
                                JSONObject homeOddsJson = new JSONObject();
                                homeOddsJson.putOpt("id", JSONUtil.parseArray(team).getStr(0));                 // 投注id
                                homeOddsJson.putOpt("odds", drawBallJson == null ? 0 : drawBallJson.getStr(1)); // 投注赔率
                                JSONObject awayOddsJson = new JSONObject();
                                awayOddsJson.putOpt("id", JSONUtil.parseArray(team).getStr(0));                 // 投注id
                                awayOddsJson.putOpt("odds", drawBallJson == null ? 0 : drawBallJson.getStr(2)); // 投注赔率
                                JSONObject drawOddsJson = new JSONObject();
                                drawOddsJson.putOpt("id", JSONUtil.parseArray(team).getStr(0));                 // 投注id
                                drawOddsJson.putOpt("odds", drawBallJson == null ? 0 : drawBallJson.getStr(0)); // 投注赔率
                                // 主
                                fullHomeCourt.putOpt("letBall", letHomeJson);
                                fullHomeCourt.putOpt("overSize", sizeHomeJson);
                                fullHomeCourt.putOpt("win", homeOddsJson);   // 主胜 - 全场
                                fullHomeCourt.putOpt("draw", awayOddsJson);  // 平 - 全场
                                // 客
                                fullAwayCourt.putOpt("letBall", letAwayJson);
                                fullAwayCourt.putOpt("overSize", sizeAwayJson);
                                fullAwayCourt.putOpt("win", drawOddsJson);   // 客胜 - 全场
                                fullAwayCourt.putOpt("draw", awayOddsJson);  // 平 - 全场
                            }

                            if (course.containsKey("1")) {
                                /** 上半场 start */
                                // 让球盘赔率
                                JSONArray firstHalfLetBallJson = course.getJSONArray("1").getJSONArray(0);
                                // 大小盘赔率
                                JSONArray firstHalfSizeBallJson = course.getJSONArray("1").getJSONArray(1);
                                // 平手盘赔率
                                JSONArray firstHalfDrawBallJson = course.getJSONArray("1").getJSONArray(2);

                                JSONObject firstHalfLetHomeJson = new JSONObject();
                                JSONObject firstHalfLetAwayJson = new JSONObject();
                                int positionLetBall = 0;
                                for(Object letBall : firstHalfLetBallJson) {
                                    JSONArray letBallJsonArr = (JSONArray) letBall;
                                    JSONObject homeOddsJson = new JSONObject();

                                    int homeOddsIdIndex1 = 1;   // 全场=0;上半场=1
                                    int homeOddsIdIndex2 = 2;   // 输赢盘=1;让球盘=2;大小盘=3
                                    int homeOddsIdIndex3 = 0;   // 主队(输赢盘=0,让球盘=0,大小盘=3);客队=(输赢盘=1,让球盘=1,大小盘=4);平局=2
                                    int homeOddsIdIndex4 = letBallJsonArr.getInt(8);   // 好像是按照比分，不好描述，可边查看平博网站滚球列表页面对应，每个比赛的3个数据，上=1，中=0，下=1

                                    int awayOddsIdIndex1 = 1;   // 全场=0;上半场=1
                                    int awayOddsIdIndex2 = 2;   // 输赢盘=1;让球盘=2;大小盘=3
                                    int awayOddsIdIndex3 = 1;   // 主队(输赢盘=0,让球盘=0,大小盘=3);客队=(输赢盘=1,让球盘=1,大小盘=4);平局=2
                                    int awayOddsIdIndex4 = letBallJsonArr.getInt(8);   // 好像是按照比分，不好描述，可边查看平博网站滚球列表页面对应，每个比赛的3个数据，上=1，中=0，下=1

                                    String homeOddsId = JSONUtil.parseArray(team).getStr(0) + "|"+homeOddsIdIndex1+"|"+homeOddsIdIndex2+"|"+homeOddsIdIndex3+"|"+homeOddsIdIndex4+"|" + letBallJsonArr.getStr(1);           // 主队投注id
                                    homeOddsJson.putOpt("id", homeOddsId);                              // 投注id
                                    if (positionLetBall == 1) {
                                        homeOddsJson.putOpt("selectionId", letBallJsonArr.getStr(7) + "|" + homeOddsId + "|0");  // 投注id
                                    } else {
                                        homeOddsJson.putOpt("selectionId", letBallJsonArr.getStr(7) + "|" + homeOddsId + "|0");  // 投注id
                                    }
                                    homeOddsJson.putOpt("odds", letBallJsonArr.getStr(3)); // 投注赔率
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
                                    homeOddsJson.putOpt("wall", wallAway.get());    // hanging=上盘,foot=下盘
                                    JSONObject awayOddsJson = new JSONObject();
                                    String awayOddsId = JSONUtil.parseArray(team).getStr(0) + "|"+awayOddsIdIndex1+"|"+awayOddsIdIndex2+"|"+awayOddsIdIndex3+"|"+awayOddsIdIndex4+"|" + letBallJsonArr.getStr(1);           // 客队投注id
                                    awayOddsJson.putOpt("id", awayOddsId);                              // 投注id
                                    if (positionLetBall == 1) {
                                        awayOddsJson.putOpt("selectionId", letBallJsonArr.getStr(7) + "|" + awayOddsId + "|1");  // 投注id
                                    } else {
                                        awayOddsJson.putOpt("selectionId", letBallJsonArr.getStr(7) + "|" + awayOddsId + "|1");  // 投注id
                                    }
                                    awayOddsJson.putOpt("odds", letBallJsonArr.getStr(4)); // 投注赔率
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
                                    awayOddsJson.putOpt("wall", wallAway.get());    // hanging=上盘,foot=下盘

                                    String letKey = "0.0".equals(letBallJsonArr.getStr(2)) ? "0" : letBallJsonArr.getStr(2);
                                    firstHalfLetHomeJson.putOpt(letKey, homeOddsJson);
                                    firstHalfLetAwayJson.putOpt(letKey, awayOddsJson);
                                    positionLetBall++;
                                };
                                JSONObject firstHalfSizeHomeJson = new JSONObject();
                                JSONObject firstHalfSizeAwayJson = new JSONObject();
                                int positionSizeBall = 0;
                                for(Object sizeBall : firstHalfSizeBallJson) {
                                    JSONArray sizeBallJsonArr = (JSONArray) sizeBall;
                                    JSONObject homeOddsJson = new JSONObject();

                                    int homeOddsIdIndex1 = 1;   // 全场=0;上半场=1
                                    int homeOddsIdIndex2 = 3;   // 输赢盘=1;让球盘=2;大小盘=3
                                    int homeOddsIdIndex3 = 3;   // 主队(输赢盘=0,让球盘=0,大小盘=3);客队=(输赢盘=1,让球盘=1,大小盘=4);平局=2
                                    int homeOddsIdIndex4 = sizeBallJsonArr.getInt(5);   // 好像是按照比分，不好描述，可边查看平博网站滚球列表页面对应，每个比赛的3个数据，上=1，中=0，下=1

                                    int awayOddsIdIndex1 = 1;   // 全场=0;上半场=1
                                    int awayOddsIdIndex2 = 3;   // 输赢盘=1;让球盘=2;大小盘=3
                                    int awayOddsIdIndex3 = 4;   // 主队(输赢盘=0,让球盘=0,大小盘=3);客队=(输赢盘=1,让球盘=1,大小盘=4);平局=2
                                    int awayOddsIdIndex4 = sizeBallJsonArr.getInt(5);   // 好像是按照比分，不好描述，可边查看平博网站滚球列表页面对应，每个比赛的3个数据，上=1，中=0，下=1

                                    String homeOddsId = JSONUtil.parseArray(team).getStr(0) + "|"+homeOddsIdIndex1+"|"+homeOddsIdIndex2+"|"+homeOddsIdIndex3+"|"+homeOddsIdIndex4+"|" + sizeBallJsonArr.getStr(1);           // 主队投注id
                                    homeOddsJson.putOpt("id", homeOddsId);                              // 投注id
                                    if (positionSizeBall == 1) {
                                        homeOddsJson.putOpt("selectionId", sizeBallJsonArr.getStr(4) + "|" + homeOddsId + "|0");  // 投注id
                                    } else {
                                        homeOddsJson.putOpt("selectionId", sizeBallJsonArr.getStr(4) + "|" + homeOddsId + "|0");  // 投注id
                                    }
                                    homeOddsJson.putOpt("odds", sizeBallJsonArr.getStr(2)); // 投注赔率
                                    JSONObject awayOddsJson = new JSONObject();
                                    String awayOddsId = JSONUtil.parseArray(team).getStr(0) + "|"+awayOddsIdIndex1+"|"+awayOddsIdIndex2+"|"+awayOddsIdIndex3+"|"+awayOddsIdIndex4+"|" + sizeBallJsonArr.getStr(1);           // 客队投注id
                                    awayOddsJson.putOpt("id", awayOddsId);                              // 投注id
                                    if (positionSizeBall == 1) {
                                        awayOddsJson.putOpt("selectionId", sizeBallJsonArr.getStr(4) + "|" + awayOddsId + "|1");  // 投注id
                                    } else {
                                        homeOddsJson.putOpt("selectionId", sizeBallJsonArr.getStr(4) + "|" + awayOddsId + "|1");  // 投注id
                                    }
                                    awayOddsJson.putOpt("odds", sizeBallJsonArr.getStr(3)); // 投注赔率
                                    String sizeKey = "0.0".equals(sizeBallJsonArr.getStr(0)) ? "0" : sizeBallJsonArr.getStr(0);
                                    firstHalfSizeHomeJson.putOpt(sizeKey, homeOddsJson);
                                    firstHalfSizeAwayJson.putOpt(sizeKey, awayOddsJson);
                                    positionSizeBall++;
                                }
                                /** 上半场 end */
                                JSONObject homeOddsJson = new JSONObject();
                                homeOddsJson.putOpt("id", JSONUtil.parseArray(team).getStr(0));                                     // 投注id
                                homeOddsJson.putOpt("odds", firstHalfDrawBallJson == null ? 0 : firstHalfDrawBallJson.getStr(1));   // 投注赔率
                                JSONObject awayOddsJson = new JSONObject();
                                awayOddsJson.putOpt("id", JSONUtil.parseArray(team).getStr(0));                                     // 投注id
                                awayOddsJson.putOpt("odds", firstHalfDrawBallJson == null ? 0 : firstHalfDrawBallJson.getStr(2));   // 投注赔率
                                JSONObject drawOddsJson = new JSONObject();
                                drawOddsJson.putOpt("id", JSONUtil.parseArray(team).getStr(0));                                     // 投注id
                                drawOddsJson.putOpt("odds", firstHalfDrawBallJson == null ? 0 : firstHalfDrawBallJson.getStr(0));   // 投注赔率
                                // 主
                                firstHalfHomeCourt.putOpt("letBall", firstHalfLetHomeJson);
                                firstHalfHomeCourt.putOpt("overSize", firstHalfSizeHomeJson);
                                firstHalfHomeCourt.putOpt("win", homeOddsJson);
                                firstHalfHomeCourt.putOpt("draw", awayOddsJson);
                                // 客
                                firstHalfAwayCourt.putOpt("letBall", firstHalfLetAwayJson);
                                firstHalfAwayCourt.putOpt("overSize", firstHalfSizeAwayJson);
                                firstHalfAwayCourt.putOpt("win", drawOddsJson);
                                firstHalfAwayCourt.putOpt("draw", awayOddsJson);
                            }

                            // 获取当前比分
                            JSONArray scoreArray = JSONUtil.parseArray(team).getJSONArray(9);
                            String scoreStr = scoreArray.getInt(0) + "-" + scoreArray.getInt(1);

                            eventHomeJson.putOpt("id", JSONUtil.parseArray(team).getStr(0));
                            eventHomeJson.putOpt("name", JSONUtil.parseArray(team).getStr(1));
                            eventHomeJson.putOpt("session", session);
                            eventHomeJson.putOpt("reTime", reTime);
                            eventHomeJson.putOpt("isHome", true);
                            eventHomeJson.putOpt("score", scoreStr);
                            eventHomeJson.putOpt("fullCourt", fullHomeCourt);
                            eventHomeJson.putOpt("firstHalf", firstHalfHomeCourt);

                            eventAwayJson.putOpt("id", JSONUtil.parseArray(team).getStr(0));
                            eventAwayJson.putOpt("name", JSONUtil.parseArray(team).getStr(2));
                            eventAwayJson.putOpt("session", session);
                            eventAwayJson.putOpt("reTime", reTime);
                            eventAwayJson.putOpt("isHome", false);
                            eventAwayJson.putOpt("score", scoreStr);
                            eventAwayJson.putOpt("fullCourt", fullAwayCourt);
                            eventAwayJson.putOpt("firstHalf", firstHalfAwayCourt);
                            teamsJson.put(eventHomeJson);
                            teamsJson.put(eventAwayJson);
                        });
                        leagueJson.putOpt("events", teamsJson);
                    }
                    result.put(leagueJson);
                });
            }
            responseJson.putOpt("leagues", events);
        }
        responseJson.putOpt("success", true);
        responseJson.putOpt("leagues", result);
        responseJson.putOpt("msg", "获取账户额度成功");
        return responseJson;
    }

    /**
     * 发送账户额度请求并返回结果
     * @param params 请求参数
     * @return 结果
     */
    @Override
    public JSONObject execute(JSONObject params) {
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
        HttpEntity<String> request = buildRequest(params);

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

        // 发送请求
        HttpResponse response = HttpRequest.get(fullUrl)
                .addHeaders(request.getHeaders().toSingleValueMap())
                .body(request.getBody())
                .execute();

        // 解析响应并返回
        return parseResponse(params, response);
    }
}
