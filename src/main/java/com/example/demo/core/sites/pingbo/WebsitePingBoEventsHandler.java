package com.example.demo.core.sites.pingbo;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.enmu.ZhiBoSchedulesType;
import com.example.demo.config.HttpProxyConfig;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * 智博网站 - 账户额度 API具体实现
 */
@Component
public class WebsitePingBoEventsHandler implements ApiHandler {

    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsitePingBoEventsHandler(WebsiteService websiteService, ApiUrlService apiUrlService) {
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
    public JSONObject parseResponse(JSONObject params, HttpResponse response) {

        // 检查响应状态
        if (response.getStatus() != 200) {
            JSONObject res = new JSONObject();
            res.putOpt("success", false);
            if (response.getStatus() == 403) {
                res.putOpt("msg", "账户登录失效");
                return res;
            }
            res.putOpt("code", response.getStatus());
            res.putOpt("msg", "获取赛事失败");
            return res;
        }
        // 解析响应
        JSONArray result = new JSONArray();
        JSONObject responseJson = new JSONObject(response.body());
        // key为l的则是滚球列表数据，key为e的则是指定联赛的详情数据
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
                            // 主队
                            JSONObject eventHomeJson = new JSONObject();
                            // 客队
                            JSONObject eventAwayJson = new JSONObject();
                            eventHomeJson.putOpt("id", JSONUtil.parseArray(team).getStr(0));
                            eventHomeJson.putOpt("name", JSONUtil.parseArray(team).getStr(1));
                            eventHomeJson.putOpt("isHome", true);
                            eventAwayJson.putOpt("id", JSONUtil.parseArray(team).getStr(0));
                            eventAwayJson.putOpt("name", JSONUtil.parseArray(team).getStr(2));
                            eventAwayJson.putOpt("isHome", false);
                            teamsJson.put(eventHomeJson);
                            teamsJson.put(eventAwayJson);
                        });
                        leagueJson.putOpt("events", teamsJson);
                    }
                    result.put(leagueJson);
                });
            }
        }
        // key为的则是今日列表数据，key为e的则是指定联赛的详情数据
        JSONArray n = responseJson.getJSONArray("n");
        if (!n.isEmpty()) {
            JSONArray events = n.getJSONArray(0).getJSONArray(2);
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
                            // 主队
                            JSONObject eventHomeJson = new JSONObject();
                            // 客队
                            JSONObject eventAwayJson = new JSONObject();
                            eventHomeJson.putOpt("id", JSONUtil.parseArray(team).getStr(0));
                            eventHomeJson.putOpt("name", JSONUtil.parseArray(team).getStr(1));
                            eventHomeJson.putOpt("isHome", true);
                            eventAwayJson.putOpt("id", JSONUtil.parseArray(team).getStr(0));
                            eventAwayJson.putOpt("name", JSONUtil.parseArray(team).getStr(2));
                            eventAwayJson.putOpt("isHome", false);
                            teamsJson.put(eventHomeJson);
                            teamsJson.put(eventAwayJson);
                        });
                        leagueJson.putOpt("events", teamsJson);
                    }
                    result.put(leagueJson);
                });
            }
        }
        responseJson.putOpt("success", true);
        responseJson.putOpt("leagues", result);
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
        if (ZhiBoSchedulesType.TODAYSCHEDULE.getId() == params.getInt("showType")) {
            // 查询今日赛事可以直接退出不用再次调用接口查询，因为平博的赛事列表接口会同时返回滚球和今日赛事
            return new JSONObject()
                    .putOpt("code", 200)
                    .putOpt("success", false)
                    .putOpt("msg", "无需查询");
        }
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
        HttpEntity<String> requestBody = buildRequest(params);

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
        HttpResponse response = null;
        HttpRequest request = HttpRequest.get(fullUrl)
                .addHeaders(requestBody.getHeaders().toSingleValueMap())
                .body(requestBody.getBody());
        // 引入配置代理
        HttpProxyConfig.configureProxy(request, userConfig);
        response = request.execute();

        // 解析响应并返回
        return parseResponse(params, response);
    }
}
