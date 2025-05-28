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
import com.example.demo.config.HttpProxyConfig;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 智博网站 - 账户账目 API具体实现
 */
@Component
public class WebsitePingBoStatementHandler implements ApiHandler {

    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsitePingBoStatementHandler(WebsiteService websiteService, ApiUrlService apiUrlService) {
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
            res.putOpt("msg", "获取账目失败");
            return res;
        }
        // 解析响应
        JSONObject result = new JSONObject();
        JSONArray responseJsonList = new JSONArray();
        JSONArray responseJson = new JSONArray(response.body());
        responseJson.forEach(json -> {
            JSONArray jsonArray = (JSONArray) json;
            JSONObject jsonObject = new JSONObject();
            String statementDateFormatted = jsonArray.getStr(0);
            String wallet = jsonArray.getStr(10).equals("Main Wallet") ? "主钱包" : "钱包";
            String remark = jsonArray.getStr(3).equals("OPEN_BAL") ? "可用余额" : "钱包";
            Double statement = jsonArray.getDouble(4);
            Double payment = jsonArray.getDouble(5);
            Double commission = jsonArray.getDouble(6);
            Double balance = jsonArray.getDouble(7);
            jsonObject.putOpt("statementDateFormatted", statementDateFormatted);
            jsonObject.putOpt("wallet", wallet);
            jsonObject.putOpt("remark", remark);
            jsonObject.putOpt("statement", statement);
            jsonObject.putOpt("payment", payment);
            jsonObject.putOpt("commission", commission);
            jsonObject.putOpt("balance", balance);
            responseJsonList.add(jsonObject);
        });
        result.putOpt("success", true);
        result.putOpt("data", responseJsonList);
        result.putOpt("msg", "获取账目成功");
        return result;
    }

    /**
     * 发送账户额度请求并返回结果
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
        HttpEntity<String> requestBody = buildRequest(params);

        // 构造请求体
        String queryParams = String.format("wallet=All&date=MONTH&count=0&timezone=GMT-4&fromDate=%s&toDate=%s&_=%s&locale=zh_CN",
                LocalDateTimeUtil.format(LocalDateTime.now().plusDays(-1), "dd/MM/yyyy"),
                LocalDateTimeUtil.format(LocalDateTime.now().plusDays(-1), "dd/MM/yyyy"),
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
