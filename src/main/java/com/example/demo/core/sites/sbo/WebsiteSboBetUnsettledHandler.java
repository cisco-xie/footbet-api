package com.example.demo.core.sites.sbo;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.enmu.SboOddsFormatType;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.config.OkHttpProxyDispatcher;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 盛帆网站 - 账户投注记录 API具体实现
 */
@Slf4j
@Component
public class WebsiteSboBetUnsettledHandler implements ApiHandler {

    private final OkHttpProxyDispatcher dispatcher;
    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteSboBetUnsettledHandler(OkHttpProxyDispatcher dispatcher, WebsiteService websiteService, ApiUrlService apiUrlService) {
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
        JSONObject responseJson = new JSONObject(response.getBody());
        JSONArray runningBets = responseJson.getJSONArray("runningBets");
        runningBets.forEach(json -> {
            JSONObject resJson = (JSONObject) json;
            JSONObject jsonObject = new JSONObject();
            String handicapType = "";
            String oddsOptionType = "";
            if (1 == resJson.getInt("oddsOptionType")) {
                // 让球盘-主队
                handicapType = "让球盘";
                oddsOptionType = resJson.getStr("homeTeamName");
            } else if (2 == resJson.getInt("oddsOptionType")) {
                // 让球盘-客队
                handicapType = "让球盘";
                oddsOptionType = resJson.getStr("awayTeamName");
            } else if (3 == resJson.getInt("oddsOptionType")) {
                // 大小盘-大
                handicapType = "大小盘";
                oddsOptionType = "大";
            } else if (4 == resJson.getInt("oddsOptionType")) {
                // 大小盘-小
                handicapType = "大小盘";
                oddsOptionType = "小";
            }
            jsonObject.putOpt("betId", resJson.getStr("id"));    // 注单ID
            jsonObject.putOpt("product", resJson.getStr("sportName"));  // 体育
            jsonObject.putOpt("league", resJson.getStr("leagueName")); // 联赛名称
            jsonObject.putOpt("team", resJson.getStr("homeTeamName") + " -vs- "+resJson.getStr("awayTeamName"));    // 主队 -vs- 客队
            jsonObject.putOpt("odds", oddsOptionType + " @ " + resJson.getStr("point") + " @ " + resJson.getJSONObject("betLiveScore").getInt("home") + ":" + resJson.getJSONObject("betLiveScore").getInt("away"));  // 投注选项 + 赔率 + 比分
            jsonObject.putOpt("oddsValue", resJson.getStr("odds"));  // 赔率
            jsonObject.putOpt("oddsTypeName", SboOddsFormatType.getById(resJson.getInt("oddsStyle")).getDescription()); // 盘口类型（如：香港盘）
            jsonObject.putOpt("amount", resJson.getStr("stake"));   // 投注金额
            jsonObject.putOpt("handicapType", handicapType);  // 投注类型，例如：(滚球) 让球盘
            // 解析投注时间,盛帆网站的时间是GMT-4，所以得加8+4小时
            LocalDateTime localDateTime = LocalDateTimeUtil.parse(resJson.getStr("transactionDate").replace("-04:00", ""));
            jsonObject.putOpt("betTime", LocalDateTimeUtil.format(localDateTime.plusHours(12), DatePattern.NORM_DATETIME_PATTERN)); // 投注时间
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
        String apiUrl = apiUrlService.getApiUrl(siteId, "unsettled");
        // 构建请求
        Map<String, String> requestHeaders = buildHeaders(params);
        // 构造请求体
        String queryParams = "c=true";

        String sportsBookUrl = insertSubdomain(baseUrl, "sportsbook");
        // 拼接完整的 URL
        String fullUrl = String.format("%s%s?%s", sportsBookUrl, apiUrl, queryParams);

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
