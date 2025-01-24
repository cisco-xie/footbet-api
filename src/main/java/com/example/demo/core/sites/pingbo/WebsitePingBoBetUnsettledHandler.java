package com.example.demo.core.sites.pingbo;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.LocalDateTimeUtil;
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

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDateTime;

/**
 * 智博网站 - 账户投注单列表-未结算 API具体实现
 */
@Component
public class WebsitePingBoBetUnsettledHandler implements ApiHandler {

    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsitePingBoBetUnsettledHandler(WebsiteService websiteService, ApiUrlService apiUrlService) {
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

        String type = "EVENT";  // 未结算
//        String type = "WAGER";  // 已结算

        String s = "OPEN";  // 未结算
//        String s = "SETTLED";  // 已结算
        // 构造请求体
        String requestBody = String.format("f=%s&t=%s&d=-1&s=%s&sd=false&type=%s&product=SB&timezone=GMT-4&sportId=&leagueId=",
                LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATE_PATTERN + " 00:00:00"),
                LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATE_PATTERN + " 00:00:00"),
                s,
                type
        );

        return new HttpEntity<>(requestBody, headers);
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
            res.putOpt("msg", "获取投注历史失败");
            return res;
        }
        // 解析响应
        JSONObject result = new JSONObject();
        JSONArray responseJsonList = new JSONArray();
        JSONArray responseJson = new JSONArray(response.body());
        DecimalFormat df = new DecimalFormat("###.00");
        responseJson.forEach(json -> {
            JSONArray jsonArray = (JSONArray) json;
            JSONObject jsonObject = new JSONObject();
            jsonObject.putOpt("product", "体育博彩");
            jsonObject.putOpt("detail", jsonArray.getStr(4)+" 足球 "+jsonArray.getStr(8));
            jsonObject.putOpt("team", jsonArray.getStr(22)+jsonArray.getStr(6)+jsonArray.getStr(22));
            jsonObject.putOpt("odds", jsonArray.getStr(10));
            jsonObject.putOpt("amount", df.format(jsonArray.getBigDecimal(36)));
            jsonObject.putOpt("win", df.format(jsonArray.getBigDecimal(35)));
            jsonObject.putOpt("status", "OPEN".equals(jsonArray.getStr(12)) ? "进行中" : "已结算");
            responseJsonList.add(jsonObject);
        });
        result.putOpt("success", true);
        result.putOpt("data", responseJsonList);
        result.putOpt("msg", "获取账户投注历史成功");
        return result;
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
        String apiUrl = apiUrlService.getApiUrl(siteId, "unsettled");
        // 构建请求
        HttpEntity<String> request = buildRequest(params);

        // 构造请求体
        String queryParams = "locale=zh_CN";

        // 拼接完整的 URL
        String fullUrl = String.format("%s%s?%s", baseUrl, apiUrl, queryParams);

        // 发送请求
        HttpResponse response = HttpRequest.post(fullUrl)
                .addHeaders(request.getHeaders().toSingleValueMap())
                .body(request.getBody())
                .execute();

        // 解析响应并返回
        return parseResponse(response);
    }

}
