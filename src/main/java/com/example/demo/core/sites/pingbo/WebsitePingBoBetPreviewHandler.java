package com.example.demo.core.sites.pingbo;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.enmu.ZhiBoOddsFormatType;
import com.example.demo.core.factory.ApiHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * 智博网站 - 投注前预览 API具体实现
 */
@Slf4j
@Component
public class WebsitePingBoBetPreviewHandler implements ApiHandler {

    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsitePingBoBetPreviewHandler(WebsiteService websiteService, ApiUrlService apiUrlService) {
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
        headers.add("content-type", "application/json");
        headers.add("x-custid", custid);
        headers.add("x-lcu", lcu);
        headers.add("x-slid", slid);
        headers.add("x-u", params.getStr("X-U"));
        headers.add("cookie", cookie);

        // 构造请求体
        JSONObject requestBody = new JSONObject();
        JSONObject selections = new JSONObject();
        JSONArray oddsSelections = new JSONArray();
        selections.putOpt("oddsFormat", ZhiBoOddsFormatType.RM.getId());
        selections.putOpt("oddsId", params.getStr("oddsId"));
        selections.putOpt("selectionId", params.getStr("selectionId"));
        selections.putOpt("oddsSelectionsType", "NORMAL");
        oddsSelections.add(selections);
        requestBody.putOpt("oddsSelections", oddsSelections);

        return new HttpEntity<>(requestBody.toString(), headers);
    }

    /**
     * 解析响应体
     * @param response 响应内容
     * @return 解析后的数据
     */
    @Override
    public JSONObject parseResponse(HttpResponse response) {
        JSONObject result = new JSONObject();

        int status = response.getStatus();

        // 1. 检查 HTTP 响应状态码
        if (status != 200) {
            log.warn("[平博][投注][失败][状态码={}]", status);

            result.putOpt("code", status);
            result.putOpt("success", false);
            result.putOpt("msg", status == 403 ? "账户登录失败" : "投注预览失败");
            return result;
        }

        // 2. 解析响应体为 JSONArray
        JSONArray responseArray;
        String responseBody = response.body();
        try {
            if (responseBody.trim().startsWith("[")) {
                responseArray = new JSONArray(responseBody);
            } else {
                JSONObject obj = new JSONObject(responseBody);
                log.error("[平博][投注][响应为对象非数组][body={}]", obj);
                result.putOpt("code", 500);
                result.putOpt("success", false);
                result.putOpt("msg", "投注预览返回格式错误（应为数组）");
                return result;
            }
        } catch (Exception e) {
            log.error("[平博][投注][响应解析异常][body={}][异常={}]", responseBody, e.getMessage(), e);
            result.putOpt("code", 500);
            result.putOpt("success", false);
            result.putOpt("msg", "投注预览解析失败");
            return result;
        }


        log.info("[平博][投注预览响应]{}", responseArray);

        // 3. 空数组直接返回失败
        if (responseArray.isEmpty()) {
            result.putOpt("code", status);
            result.putOpt("success", false);
            result.putOpt("msg", "投注预览失败，结果为空");
            return result;
        }

        // 4. 过滤掉无效项（ODDS_CHANGE / UNAVAILABLE）
        JSONArray filteredArray = new JSONArray();
        for (Object obj : responseArray) {
            JSONObject resObj = JSONUtil.parseObj(obj);
            String itemStatus = resObj.getStr("status");

            if ("ODDS_CHANGE".equals(itemStatus)) {
                log.info("[平博][投注预览][赔率已变更][{}]", resObj);
                continue;
            }
            if ("UNAVAILABLE".equals(itemStatus)) {
                log.info("[平博][投注预览][注单暂时无效][{}]", resObj);
                continue;
            }
            if ("PROCESSED_WITH_ERROR".equals(itemStatus)) {
                if ("BELOW_MIN_BET_AMOUNT".equals(resObj.getStr("errorCode"))) {
                    log.info("[平博][投注预览][低于最低限额][{}]", resObj);
                    continue;
                }
                log.info("[平博][投注预览][注单内容处理错误][{}]", resObj);
                continue;
            }

            filteredArray.add(resObj);
        }

        // 5. 构造最终结果
        result.putOpt("code", 200);
        result.putOpt("success", true);
        result.putOpt("data", filteredArray);
        result.putOpt("msg", "投注预览成功");

        return result;
    }

    /**
     * 发送投注预览请求并返回结果
     * @param params 请求参数
     * @return 请求结果
     */
    @Override
    public JSONObject execute(JSONObject params) {
        // 获取 完整API 路径
        String username = params.getStr("adminUsername");
        String siteId = params.getStr("websiteId");
        String baseUrl = websiteService.getWebsiteBaseUrl(username, siteId);
        String apiUrl = apiUrlService.getApiUrl(siteId, "betPreview");
        // 构建请求
        HttpEntity<String> request = buildRequest(params);

        // 构造请求体
        String queryParams = String.format("locale=zh_CN&_=%s&withCredentials=true",
                System.currentTimeMillis()
        );

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
