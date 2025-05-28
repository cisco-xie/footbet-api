package com.example.demo.core.sites.zhibo;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.config.HttpProxyConfig;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 智博网站 - 账户投注单列表-未结算 API具体实现
 */
@Component
public class WebsiteZhiBoBetUnsettledHandler implements ApiHandler {

    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteZhiBoBetUnsettledHandler(WebsiteService websiteService, ApiUrlService apiUrlService) {
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

        return new HttpEntity<>(headers);
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
            res.putOpt("msg", "获取投注历史失败");
            return res;
        }
        // 解析响应
        JSONObject result = new JSONObject();
        JSONArray responseArray = new JSONArray();
        JSONArray responseJson = new JSONArray(response.body());
        for (Object obj : responseJson) {
            JSONObject jsonObject = (JSONObject) obj;
            JSONObject betInfo = new JSONObject();
            betInfo.putOpt("betId", jsonObject.getStr("betId"));
            betInfo.putOpt("amount", jsonObject.getStr("stake"));
            betInfo.putOpt("league", jsonObject.getJSONObject("selectionDetail").getStr("leagueName"));
            betInfo.putOpt("team", jsonObject.getJSONObject("selectionDetail").getStr("eventName"));
            betInfo.putOpt("odds", jsonObject.getJSONObject("selectionDetail").getStr("name") + " " + jsonObject.getJSONObject("selectionDetail").getStr("handicap") + " @ " + jsonObject.getJSONObject("selectionDetail").getStr("odds"));
            responseArray.add(betInfo);
        }
        result.putOpt("success", true);
        result.putOpt("data", responseArray);
        result.putOpt("msg", "获取投注历史成功");
        return result;
    }

    /**
     * 发送账户额度请求
     * @param params 请求参数
     * @return 结果
     */
    @Override
    public JSONObject execute(ConfigAccountVO userConfig, JSONObject params) {

        // 获取 完整API 路径
        String username = params.getStr("adminUsername");
        String siteId = params.getStr("websiteId");
        String baseUrl = websiteService.getWebsiteBaseUrl(username, siteId);
        String apiUrl = apiUrlService.getApiUrl(siteId, "unsettled");
        String date = LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.SIMPLE_MONTH_PATTERN);
        // 构建请求
        HttpEntity<String> requestBody = buildRequest(params);

        // 构造请求体
        String queryParams = String.format("_=%s",
                System.currentTimeMillis()
        );

        // 拼接完整的 URL
        String fullUrl = String.format("%s%s?%s", baseUrl, apiUrl, date, queryParams);

        // 发送请求
        HttpResponse response = null;
        HttpRequest request = HttpRequest.get(fullUrl)
                .addHeaders(requestBody.getHeaders().toSingleValueMap());
        // 引入配置代理
        HttpProxyConfig.configureProxy(request, userConfig);
        response = request.execute();

        // 解析响应
        return parseResponse(params, response);
    }
}
