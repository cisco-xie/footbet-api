package com.example.demo.core.sites.sbo;

import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.common.utils.ToDayRangeUtil;
import com.example.demo.config.OkHttpProxyDispatcher;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 盛帆网站 - 账户账目 API具体实现
 */
@Slf4j
@Component
public class WebsiteSboStatementHandler implements ApiHandler {

    private final OkHttpProxyDispatcher dispatcher;
    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteSboStatementHandler(OkHttpProxyDispatcher dispatcher, WebsiteService websiteService, ApiUrlService apiUrlService) {
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
        // 构造请求头
        headers.put("accept", "application/json, text/plain, */*");
        headers.put("accept-language", "zh-CN,zh;q=0.9");
        headers.put("cookie", params.getStr("cookie"));

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
        return "";
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
            JSONObject res = new JSONObject();
            res.putOpt("success", false);
            if (response.getStatus() == 403) {
                res.putOpt("msg", "账户登录失效");
                return res;
            }
            res.putOpt("msg", "获取账目失败");
            return res;
        }
        // 解析响应
        JSONObject result = new JSONObject();
        JSONArray responseJsonList = new JSONArray();
        JSONArray responseJson = new JSONArray(response.getBody());
        responseJson.forEach(json -> {
            JSONObject resJson = (JSONObject) json;
            JSONObject jsonObject = new JSONObject();
            String remark;
            if (resJson.getStr("remark").equals("OpeningBalance")) {
                remark = "前期余额";
            } else if (resJson.getStr("remark").equals("Betting")) {
                remark = "投注 (体育博彩)";
            } else {
                remark = resJson.getStr("remark");
            }
            jsonObject.putOpt("statementDate", resJson.getStr("statementDate"));    // 账目日期
            jsonObject.putOpt("remark", remark);
            jsonObject.putOpt("winlost", resJson.getDouble("winlost"));             // 总输赢
            jsonObject.putOpt("commission", resJson.getDouble("commission"));       // 佣金
            jsonObject.putOpt("runningTotal", resJson.getDouble("runningTotal"));   // 运行总计
            responseJsonList.add(jsonObject);
        });
        result.putOpt("success", true);
        result.putOpt("data", responseJsonList);
        result.putOpt("msg", "获取账目成功");
        return result;
    }

    /**
     * 发送请求并返回结果
     * @param params 请求参数
     * @return 结果
     */
    @Override
    public JSONObject execute(ConfigAccountVO userConfig, JSONObject params) {
        // 获取 完整API 路径
        String username = params.getStr("adminUsername");
        String siteId = params.getStr("websiteId");
        String baseUrl = websiteService.getWebsiteBaseUrl(username, siteId);
        String apiUrl = apiUrlService.getApiUrl(siteId, "statement");
        // 构建请求
        Map<String, String> requestHeaders = buildHeaders(params);
        List<LocalDate> dates = ToDayRangeUtil.getLast10Days();
        // 构造请求体
        String queryParams = String.format("from=%s&to=%s",
                dates.get(0),
                dates.get(1)
        );

        String apiHomeUrl = insertSubdomain(baseUrl, "api-home");
        // 拼接完整的 URL
        String fullUrl = String.format("%s%s?%s", apiHomeUrl, apiUrl, queryParams);

        // 使用代理发起 请求
        OkHttpProxyDispatcher.HttpResult resultHttp;
        try {
            resultHttp = dispatcher.execute("GET", fullUrl, null, requestHeaders, userConfig, false);
        } catch (Exception e) {
            log.error("请求异常，用户:{}, 账号:{}, 参数:{}, 错误:{}", username, userConfig.getAccount(), null, e.getMessage(), e);
            throw new BusinessException(SystemError.SYS_400);
        }
        // 解析响应并返回
        return parseResponse(params, resultHttp);
    }

    public static String insertSubdomain(String baseUrl, String subdomain) {
        URI uri = URI.create(baseUrl);
        String host = uri.getHost();  // u16888.com
        String newHost = subdomain + "." + host;
        return baseUrl.replaceFirst(host, newHost);
    }
}
