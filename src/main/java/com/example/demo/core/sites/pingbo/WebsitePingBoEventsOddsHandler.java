package com.example.demo.core.sites.pingbo;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.core.factory.ApiHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

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
     * @param response 响应内容
     * @return 解析后的数据
     */
    @Override
    public JSONObject parseResponse(HttpResponse response) {

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
                            // 解析当前场次
                            JSONObject course = JSONUtil.parseArray(team).getJSONObject(8);
                            // 全场
                            JSONObject fullCCourt = new JSONObject();
                            JSONObject fullHCourt = new JSONObject();
                            // 上半场
                            JSONObject firstHalfCCourt = new JSONObject();
                            JSONObject firstHalfHCourt = new JSONObject();

                            JSONObject eventCJson = new JSONObject();
                            JSONObject eventHJson = new JSONObject();

                            if (course.containsKey("0")) {
                                /** 全场 start */
                                // 让球盘赔率
                                JSONArray letBallJson = course.getJSONArray("0").getJSONArray(0);
                                // 大小盘赔率
                                JSONArray sizeBallJson = course.getJSONArray("0").getJSONArray(1);
                                // 平手盘赔率
                                JSONArray drawBallJson = course.getJSONArray("0").getJSONArray(2);

                                JSONObject letCJson = new JSONObject();
                                JSONObject letHJson = new JSONObject();
                                letBallJson.forEach(letBall -> {
                                    JSONArray letBallJsonArr = (JSONArray) letBall;
                                    letCJson.putOpt(letBallJsonArr.getStr(2), letBallJsonArr.getStr(3));
                                    letHJson.putOpt(letBallJsonArr.getStr(2), letBallJsonArr.getStr(4));
                                });
                                JSONObject sizeCJson = new JSONObject();
                                JSONObject sizeHJson = new JSONObject();
                                sizeBallJson.forEach(sizeBall -> {
                                    JSONArray sizeBallJsonArr = (JSONArray) sizeBall;
                                    sizeCJson.putOpt(sizeBallJsonArr.getStr(0), sizeBallJsonArr.getStr(2));
                                    sizeHJson.putOpt(sizeBallJsonArr.getStr(0), sizeBallJsonArr.getStr(3));
                                });
                                /** 全场 end */
                                // 主
                                fullCCourt.putOpt("letBall", letCJson);
                                fullCCourt.putOpt("overSize", sizeCJson);
                                fullCCourt.putOpt("win", drawBallJson.getStr(1));   // 主胜 - 全场
                                fullCCourt.putOpt("draw", drawBallJson.getStr(2));  // 平 - 全场
                                // 客
                                fullHCourt.putOpt("letBall", letHJson);
                                fullHCourt.putOpt("overSize", sizeHJson);
                                fullHCourt.putOpt("win", drawBallJson.getStr(0));   // 客胜 - 全场
                                fullHCourt.putOpt("draw", drawBallJson.getStr(2));  // 平 - 全场
                            }

                            if (course.containsKey("1")) {
                                /** 上半场 start */
                                // 让球盘赔率
                                JSONArray firstHalfLetBallJson = course.getJSONArray("1").getJSONArray(0);
                                // 大小盘赔率
                                JSONArray firstHalfSizeBallJson = course.getJSONArray("1").getJSONArray(1);
                                // 平手盘赔率
                                JSONArray firstHalfDrawBallJson = course.getJSONArray("1").getJSONArray(2);

                                JSONObject firstHalfLetCJson = new JSONObject();
                                JSONObject firstHalfLetHJson = new JSONObject();
                                firstHalfLetBallJson.forEach(letBall -> {
                                    JSONArray letBallJsonArr = (JSONArray) letBall;
                                    firstHalfLetCJson.putOpt(letBallJsonArr.getStr(2), letBallJsonArr.getStr(3));
                                    firstHalfLetHJson.putOpt(letBallJsonArr.getStr(2), letBallJsonArr.getStr(4));
                                });
                                JSONObject firstHalfSizeCJson = new JSONObject();
                                JSONObject firstHalfSizeHJson = new JSONObject();
                                firstHalfSizeBallJson.forEach(sizeBall -> {
                                    JSONArray sizeBallJsonArr = (JSONArray) sizeBall;
                                    firstHalfSizeCJson.putOpt(sizeBallJsonArr.getStr(0), sizeBallJsonArr.getStr(2));
                                    firstHalfSizeHJson.putOpt(sizeBallJsonArr.getStr(0), sizeBallJsonArr.getStr(3));
                                });
                                /** 上半场 end */
                                // 主
                                firstHalfCCourt.putOpt("letBall", firstHalfLetCJson);
                                firstHalfCCourt.putOpt("overSize", firstHalfSizeCJson);
                                firstHalfCCourt.putOpt("win", firstHalfDrawBallJson.getStr(1));
                                firstHalfCCourt.putOpt("draw", firstHalfDrawBallJson.getStr(2));
                                // 客
                                firstHalfHCourt.putOpt("letBall", firstHalfLetHJson);
                                firstHalfHCourt.putOpt("overSize", firstHalfSizeHJson);
                                firstHalfHCourt.putOpt("win", firstHalfDrawBallJson.getStr(0));
                                firstHalfHCourt.putOpt("draw", firstHalfDrawBallJson.getStr(2));
                            }

                            eventCJson.putOpt("id", JSONUtil.parseArray(team).getStr(0));
                            eventCJson.putOpt("name", JSONUtil.parseArray(team).getStr(1));
                            eventCJson.putOpt("fullCourt", fullCCourt);
                            eventCJson.putOpt("firstHalf", firstHalfCCourt);

                            eventHJson.putOpt("id", JSONUtil.parseArray(team).getStr(0));
                            eventHJson.putOpt("name", JSONUtil.parseArray(team).getStr(2));
                            eventHJson.putOpt("fullCourt", fullHCourt);
                            eventHJson.putOpt("firstHalf", firstHalfHCourt);
                            teamsJson.put(eventCJson);
                            teamsJson.put(eventHJson);
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
        String queryParams = String.format("btg=1&c=%s&cl=3&d=&ec=&ev=&g=QQ==&hle=%s&ic=false&inl=false&l=3&lang=&lg=&lv=&me=%s&mk=%s&more=%s&o=%s&ot=1&pa=0&pimo=0,1,8,39,2,3,6,7,4,5&pn=-1&pv=1&sp=%s&tm=0&v=0&locale=zh_CN&_=%s&withCredentials=true",
                c,
                hle,
                me,
                mk,
                more,
                o,
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
        return parseResponse(response);
    }
}
