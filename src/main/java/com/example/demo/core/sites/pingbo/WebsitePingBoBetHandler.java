package com.example.demo.core.sites.pingbo;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.config.OkHttpProxyDispatcher;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 智博网站 - 投注 API具体实现
 */
@Slf4j
@Component
public class WebsitePingBoBetHandler implements ApiHandler {

    private final OkHttpProxyDispatcher dispatcher;
    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsitePingBoBetHandler(OkHttpProxyDispatcher dispatcher, WebsiteService websiteService, ApiUrlService apiUrlService) {
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
        headers.put("content-type", "application/json");
        headers.put("x-custid", custid);
        headers.put("x-lcu", lcu);
        headers.put("x-slid", slid);
        headers.put("x-u", params.getStr("X-U"));
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
        JSONObject requestBody = new JSONObject();
        JSONArray selections = new JSONArray();
        requestBody.putOpt("acceptBetterOdds", true);
        requestBody.putOpt("oddsFormat", params.getInt("oddsFormatType"));
        requestBody.putOpt("selections", selections);
        for (Object selectionObj : params.getJSONArray("selections")) {
            JSONObject selectionJson = JSONUtil.parseObj(selectionObj);
            JSONObject selection = new JSONObject();
            JSONObject betLocationTracking = new JSONObject();
            selection.putOpt("odds", selectionJson.getStr("odds"));
            selection.putOpt("oddsId", selectionJson.getStr("oddsId"));
            selection.putOpt("selectionId", selectionJson.getStr("selectionId"));
            selection.putOpt("stake", selectionJson.getDouble("stake"));
            selection.putOpt("uniqueRequestId", IdUtil.fastUUID());
            selection.putOpt("wagerType", "NORMAL");
            selection.putOpt("betLocationTracking", betLocationTracking);
            selections.add(selection);

            betLocationTracking.putOpt("view", "NEW_ASIAN_VIEW");
            betLocationTracking.putOpt("navigation", "SPORTS");
            betLocationTracking.putOpt("device", "DESKTOP");
            betLocationTracking.putOpt("reuseSelection", false);
            betLocationTracking.putOpt("mainPages", "SPORT");
            betLocationTracking.putOpt("marketTab", "TODAY");
            betLocationTracking.putOpt("market", "MATCHES");
            betLocationTracking.putOpt("oddsContainerCategory", "MAIN");
            betLocationTracking.putOpt("oddsContainerTitle", "LIVE");
            betLocationTracking.putOpt("language", "zh_CN");
            betLocationTracking.putOpt("displayMode", "LIGHT");
            betLocationTracking.putOpt("marketType", "_3LINES");
            betLocationTracking.putOpt("eventSorting", "LEAGUE");
            betLocationTracking.putOpt("pageType", "DOUBLE");
            betLocationTracking.putOpt("timeZone", "Asia/Shanghai");
            betLocationTracking.putOpt("defaultPage", "TODAY");
        }
        return requestBody.toString();
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
            log.info("[平博][投注][失败]{}", response.getStatus());
            JSONObject res = new JSONObject();
            if (response.getStatus() == 403) {
                res.putOpt("code", 403);
                res.putOpt("success", false);
                res.putOpt("msg", "账户登录失败");
                return res;
            }
            res.putOpt("code", response.getStatus());
            res.putOpt("betInfo", params.getJSONObject("betInfo"));
            res.putOpt("success", false);
            res.putOpt("msg", "投注失败");
            return res;
        }
        // 解析响应
        JSONObject responseJson = new JSONObject(response.getBody());
        log.info("[平博][投注]{}", responseJson);
        // 如果响应中包含错误信息，抛出异常或者其他处理
        if (responseJson.containsKey("errorCode")) {
            responseJson.putOpt("code", response.getStatus());
            responseJson.putOpt("success", false);
            responseJson.putOpt("betInfo", params.getJSONObject("betInfo"));
            responseJson.putOpt("msg", responseJson.getStr("errorMessage"));
            return responseJson;
        }
        int sucNum = 0;
        int failedNum = 0;
        int num = 0;
        boolean success = true;
        if (!responseJson.isNull("response")) {
            JSONArray responseArray = responseJson.getJSONArray("response");
            num = responseArray.size();
            for (Object res : responseArray) {
                JSONObject resObj = (JSONObject) res;
                String itemStatus = resObj.getStr("status");
                if (!"ACCEPTED".equals(itemStatus) && !"PENDING_ACCEPTANCE".equals(itemStatus)) {
                    // 投注失败
                    failedNum++;
                    success = false;
                    log.info("[平博][投注][失败]{}", resObj);
                    continue;
                }
                sucNum++;
                log.info("[平博][投注][成功]{}", resObj);
            };
        } else {
            success = false;
        }
        log.info("[平博][投注][投注结束][共投注{}个][投注成功{}个][投注失败{}个]", num, sucNum, failedNum);
        responseJson.putOpt("success", success);
        responseJson.putOpt("betInfo", params.getJSONObject("betInfo"));
        responseJson.putOpt("msg", "投注结束");
        return responseJson;
    }

    /**
     * 发送登录请求并返回结果
     * @param params 请求参数
     * @return 登录结果
     */
    @Override
    public JSONObject execute(ConfigAccountVO userConfig, JSONObject params) {
        if (null == params.getJSONArray("selections") || params.getJSONArray("selections").isEmpty()) {
            return null;
        }
        // 获取 完整API 路径
        String username = params.getStr("adminUsername");
        String siteId = params.getStr("websiteId");
        String baseUrl = websiteService.getWebsiteBaseUrl(username, siteId);
        String apiUrl = apiUrlService.getApiUrl(siteId, "bet");
        // 构建请求
        Map<String, String> requestHeaders = buildHeaders(params);
        String requestBody = buildRequest(params);

        // 构造请求体
        String queryParams = String.format("uniqueRequestId=%s&locale=zh_CN&_=%s&withCredentials=true",
                IdUtil.fastUUID(),
                System.currentTimeMillis()
        );

        // 拼接完整的 URL
        String fullUrl = String.format("%s%s?%s", baseUrl, apiUrl, queryParams);

        // 发送请求
        OkHttpProxyDispatcher.HttpResult resultHttp;
        try {
            resultHttp = dispatcher.execute("POST", fullUrl, requestBody, requestHeaders, userConfig, false);
        } catch (Exception e) {
            log.error("请求异常，用户:{}, 账号:{}, 参数:{}, 错误:{}", username, userConfig.getAccount(), requestBody, e.getMessage(), e);
            throw new BusinessException(SystemError.SYS_400);
        }
        // 解析响应并返回
        return parseResponse(params, resultHttp);
    }
}
