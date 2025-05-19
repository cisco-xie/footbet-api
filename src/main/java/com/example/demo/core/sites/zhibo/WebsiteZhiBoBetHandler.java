package com.example.demo.core.sites.zhibo;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
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
 * 智博网站 - 投注 API具体实现
 */
@Slf4j
@Component
public class WebsiteZhiBoBetHandler implements ApiHandler {

    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteZhiBoBetHandler(WebsiteService websiteService, ApiUrlService apiUrlService) {
        this.websiteService = websiteService;
        this.apiUrlService = apiUrlService;
    }

    @Override
    public HttpEntity<String> buildRequest(JSONObject params) {
        // 构造请求头
        HttpHeaders headers = new HttpHeaders();
        headers.add("accept", "*/*");
        headers.add("content-type", "application/json");
        headers.add("locale", "zh_CN");
        headers.add("authorization", params.getStr("token"));

        // 构造请求体
        JSONObject body = new JSONObject();
        body.putOpt("requestId", System.currentTimeMillis());
        body.putOpt("marketSelectionId", params.getStr("marketSelectionId"));
        body.putOpt("stake", params.getStr("stake"));
        body.putOpt("oddsFormatId", params.getStr("oddsFormatId"));
        body.putOpt("odds", params.getStr("odds"));
        body.putOpt("decimalOdds", params.getStr("decimalOdds"));
        body.putOpt("handicap", params.getStr("handicap"));
        body.putOpt("score", params.getStr("score"));
        body.putOpt("eventPitcherId", 0);

        return new HttpEntity<>(body.toString(), headers);
    }

    @Override
    public JSONObject parseResponse(JSONObject params, HttpResponse response) {
        // 检查响应状态
        if (response.getStatus() != 200) {
            JSONObject res = new JSONObject();
            res.putOpt("success", false);
            if (response.getStatus() == 403) {
                res.putOpt("code", 403);
                res.putOpt("msg", "账户登录失效");
                return res;
            }
            res.putOpt("msg", "投注失败");
            return res;
        }
        // 解析响应
        JSONObject result = new JSONObject();
        JSONObject responseJson = new JSONObject(response.body());
        log.info("[智博][投注]{}", responseJson);
        if (0 != responseJson.getInt("responseCode")) {
            JSONObject res = new JSONObject();
            res.putOpt("success", false);
            res.putOpt("msg", "投注失败:"+responseJson.getStr("responseMessage"));
            return res;
        }
        result.putOpt("success", true);
        result.putOpt("data", responseJson);
        result.putOpt("msg", "投注成功");
        return result;
    }

    /**
     * 发送账户额度请求
     * @param params 请求参数
     * @return 结果
     */
    @Override
    public JSONObject execute(JSONObject params) {

        // 获取 完整API 路径
        String username = params.getStr("adminUsername");
        String siteId = params.getStr("websiteId");
        String baseUrl = websiteService.getWebsiteBaseUrl(username, siteId);
        String apiUrl = apiUrlService.getApiUrl(siteId, "bet");
        // 构建请求
        HttpEntity<String> request = buildRequest(params);

        // 拼接完整的 URL
        String fullUrl = String.format("%s%s", baseUrl, apiUrl);

        // 发送请求
        HttpResponse response = HttpRequest.post(fullUrl)
                .addHeaders(request.getHeaders().toSingleValueMap())
                .body(request.getBody())
                .execute();

        // 解析响应
        return parseResponse(params, response);
    }
}
