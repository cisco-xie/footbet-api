package com.example.demo.core.sites.xinbao;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.XmlUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.constants.Constants;
import com.example.demo.core.factory.ApiHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import javax.xml.xpath.XPathConstants;
import java.util.HashMap;
import java.util.Map;

/**
 * 智博网站 - 账户额度 API具体实现
 */
@Component
public class WebsiteXinBaoBetUnsettledHandler implements ApiHandler {

    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteXinBaoBetUnsettledHandler(WebsiteService websiteService, ApiUrlService apiUrlService) {
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
        // 构造请求头
        HttpHeaders headers = new HttpHeaders();
        headers.add("accept", "*/*");
        headers.add("content-type", "application/x-www-form-urlencoded");

        // 构造请求体
        String requestBody = String.format("p=get_today_wagers&uid=%s&ver=%s&langx=zh-cn&LS=g&selGtype=ALL&chk_cw=N&ts=%s&format=json",
                params.getStr("uid"),
                Constants.VER,
                System.currentTimeMillis()
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
            res.putOpt("msg", "账户登录失效");
            return res;
        }

        // 解析响应
        JSONArray result = new JSONArray();
        JSONObject responseJson = new JSONObject(response.body());
        JSONArray wagers = responseJson.getJSONArray("wagers");
        if (wagers == null || wagers.isEmpty()) {
            responseJson.putOpt("success", true);
            responseJson.putOpt("data", null);
            responseJson.putOpt("msg", "获取账户投注历史成功");
            return responseJson;
        }
        wagers.forEach(json -> {
            JSONObject wager = new JSONObject();
            JSONObject wagerJson = (JSONObject) json;
            wager.putOpt("betId", wagerJson.getStr("w_id"));
            wager.putOpt("product", wagerJson.getStr("gtype")+wagerJson.getStr("wtype")+"("+wagerJson.getStr("score")+")");
            wager.putOpt("league", wagerJson.getStr("league"));
            wager.putOpt("team", wagerJson.getStr("team_h_show") + " -vs- "+wagerJson.getStr("team_c_show"));
            wager.putOpt("odds", wagerJson.getStr("result") + " @ " + wagerJson.getStr("ioratio"));
            wager.putOpt("detail", wagerJson.getStr("league") + " - " + wagerJson.getStr("team_h_show") + " v "+wagerJson.getStr("team_c_show"));
            wager.putOpt("result", wagerJson.getStr("result"));
            // wager.putOpt("odds", wagerJson.getStr("ioratio"));
            wager.putOpt("amount", wagerJson.getStr("gold"));
            result.add(wager);
        });
        responseJson.putOpt("success", true);
        responseJson.putOpt("data", result);
        responseJson.putOpt("msg", "获取账户投注历史成功");
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
        String apiUrl = apiUrlService.getApiUrl(siteId, "unsettled");
        // 构建请求
        HttpEntity<String> request = buildRequest(params);

        // 构造请求体
        String queryParams = String.format("ver=%s",
                Constants.VER
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
