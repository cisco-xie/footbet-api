package com.example.demo.core.sites.pingbo;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.enmu.PingBoOddsFormatType;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.config.OkHttpProxyDispatcher;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 智博网站 - 账户投注单列表-未结算 API具体实现
 * 平博未结注单列表响应格式说明（不一定百分百准确）
 * | 下标    | 示例值                   | 字段说明                               |
 * | ----- | --------------------- | ---------------------------------- |
 * | 0     | 0                     | 固定值，暂无实际用途                         |
 * | 1     | 0                     | 固定值，暂无实际用途                         |
 * | 2     | 0                     | 固定值，暂无实际用途                         |
 * | 3     | "103.100..."          | 下单时的 IP 地址                         |
 * | 4     | 3359329398            | **投注单 ID**，唯一标识一条投注                |
 * | 5     | 0                     | 固定值，暂无实际用途                         |
 * | 6     | "英格兰 -vs- 荷兰"         | **赛事对阵信息**（主队 vs 客队）               |
 * | 7     | 1                     | 固定值，可能表示注单来源平台                     |
 * | 8     | "2025-07-09 04:57:35" | **投注时间**                           |
 * | 9     | "2025-07-09"          | 投注日期（仅年月日）                         |
 * | 10    | "0.806"               | **赔率**                             |
 * | 11    | 1752076800000         | 比赛的毫秒级时间戳                          |
 * | 12    | "OPEN"                | **注单状态**（OPEN 表示进行中，非 OPEN 可能为已结算） |
 * | 13    | 1752051455000         | 注单创建时间（毫秒时间戳）                      |
 * | 14    | "英格兰"                 | **主队名称（中文）**                       |
 * | 15    | "荷兰"                  | **客队名称（中文）**                       |
 * | 16    | "大盘"                  | **投注对象**（可为球队名称或盘口方向如“大盘”“小盘”）     |
 * | 17    | 3                     | **盘口类型**（1 = 让球盘，3 = 大小盘）          |
 * | 18    | 2.5                   | **盘口值**（如让球 -0.75，大小 2.5）          |
 * | 19    | 0.806                 | **原始赔率（float 类型）**                 |
 * | 20    | 4                     | 固定值                                |
 * | 21    | 3                     | 固定值                                |
 * | 22    | "欧足联 - 女子欧洲杯"         | **联赛名称**                           |
 * | 23    | 150                   | **投注金额（原始值）**（未含手续费）               |
 * | 24    | 29                    | 固定值，可能是货币类型 ID                     |
 * | 25    | "Soccer"              | **体育类型**（Soccer 表示足球）              |
 * | 26    | 0                     | 固定值                                |
 * | 27    | "HKD"                 | **投注货币**（港币）                       |
 * | 28    | 0                     | 固定值                                |
 * | 29    | 0                     | 固定值                                |
 * | 30    | "" / "1-0"            | **比赛实时比分**（可能为空）                   |
 * | 31    | 0                     | 固定值                                |
 * | 32    | 0                     | 固定值                                |
 * | 33    | "常规赛"                 | 比赛类型                               |
 * | 34    | "BASE"                | 固定值，可能为盘口基础类型                      |
 * | 35    | 120.9                 | **预计赢利（收益金额）**                     |
 * | 36    | 150                   | **投注金额（确认值）**（可能等于原始投注金额）          |
 * | 37    | "SB"                  | 投注渠道（如 SB）                         |
 * | 38–43 | "" / 0 / null         | 预留字段                               |
 * | 44    | 0.806                 | 再次出现赔率                             |
 * | 45–47 | "" / 0                | 保留字段                               |
 * | 48    | 0                     | 保留字段                               |
 * | 49    | 0                     | 保留字段                               |
 * | 50    | 0                     | 保留字段                               |
 * | 51    | 0.806                 | 第三次出现赔率                            |
 * | 52    | "0.806"               | 第四次出现赔率（字符串）                       |
 * | 53    | 0                     | 保留字段                               |
 * | 54    | 0                     | 保留字段                               |
 * | 55    | "1969-12-31 20:00:00" | 无效时间戳（默认占位时间）                      |
 * | 56    | "EVENT"               | 注单类型（EVENT 表示比赛投注）                 |
 * | 57    | 99                    | 保留字段                               |
 * | 58    | 0                     | 保留字段                               |
 * | 59    | ""                    | 保留字段                               |
 * | 60    | 0                     | 保留字段                               |
 * | 61    | "2025-07-09 12:00:00" | **比赛开赛时间**（本地时间）                   |
 * | 62    | "Games"               | 保留字段                               |
 * | 63    | 0                     | 保留字段                               |
 * | 64    | 1610522307            | 注单来源用户 ID 或 Hash ID？（整型）           |
 * | 65–67 | 0                     | 保留字段                               |
 * | 68    | "England"             | **主队英文名**                          |
 * | 69    | "Regular"             | **比赛性质**（Regular = 常规赛）            |
 */
@Slf4j
@Component
public class WebsitePingBoBetUnsettledHandler implements ApiHandler {

    private final OkHttpProxyDispatcher dispatcher;
    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsitePingBoBetUnsettledHandler(OkHttpProxyDispatcher dispatcher, WebsiteService websiteService, ApiUrlService apiUrlService) {
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
        headers.put("content-type", "application/x-www-form-urlencoded");
        headers.put("x-custid", custid);
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
        String type = "EVENT";  // 未结算
//        String type = "WAGER";  // 已结算

        String s = "OPEN";  // 未结算
//        String s = "SETTLED";  // 已结算
        // 构造请求体
        return String.format("f=%s&t=%s&d=-1&s=%s&sd=false&type=%s&product=SB&timezone=GMT-4&sportId=&leagueId=",
                LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATE_PATTERN + " 00:00:00"),
                LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATE_PATTERN + " 00:00:00"),
                s,
                type
        );
    }

    /**
     * 解析响应体
     * @param response 响应内容
     *
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
            res.putOpt("msg", "获取投注历史失败");
            return res;
        }
        // 解析响应
        JSONObject result = new JSONObject();
        JSONArray responseJsonList = new JSONArray();
        JSONArray responseJson = new JSONArray(response.getBody());
        log.info("原始未结单列表数据:{}", responseJson);
        DecimalFormat df = new DecimalFormat("###.00");
        responseJson.forEach(json -> {
            JSONArray jsonArray = (JSONArray) json;
            JSONObject jsonObject = new JSONObject();

            jsonObject.putOpt("betId", jsonArray.getStr(4));                // 注单编号
            jsonObject.putOpt("product", "体育博彩");                         // 固定产品类型
            jsonObject.putOpt("league", jsonArray.getStr(22));              // 联赛名称
            jsonObject.putOpt("team", jsonArray.getStr(6));                // 比赛对阵信息
            jsonObject.putOpt("odds", jsonArray.getStr(16) + " " + jsonArray.getStr(18) + " @ " + jsonArray.getStr(10));  // 投注内容+盘口+赔率
            jsonObject.putOpt("amount", df.format(jsonArray.getBigDecimal(23))); // 投注金额
            jsonObject.putOpt("win", df.format(jsonArray.getBigDecimal(35)));    // 可赢金额
            jsonObject.putOpt("status", "OPEN".equals(jsonArray.getStr(12)) ? "进行中" : "已结算"); // 注单状态
            jsonObject.putOpt("betTime", jsonArray.getStr(8));              // 投注时间
            jsonObject.putOpt("matchTime", jsonArray.getStr(61));           // 比赛开赛时间
            jsonObject.putOpt("homeTeam", jsonArray.getStr(14));            // 主队中文名
            jsonObject.putOpt("awayTeam", jsonArray.getStr(15));            // 客队中文名
            jsonObject.putOpt("score", jsonArray.getStr(30));               // 实时比分
            jsonObject.putOpt("handicap", jsonArray.getStr(18));            // 盘口数值
            jsonObject.putOpt("oddsValue", jsonArray.getStr(10));           // 赔率数值
            jsonObject.putOpt("betOption", jsonArray.getStr(16));           // 投注选项（如：大盘、英格兰）
            jsonObject.putOpt("homeTeamEN", jsonArray.getStr(68));          // 主队英文名
            jsonObject.putOpt("matchNature", jsonArray.getStr(69));         // 比赛性质（如：Regular）
            // 赔率类型判断（index 26）
            int oddsTypeId = jsonArray.getInt(20); // 赔率类型 ID
            PingBoOddsFormatType oddsFormat = PingBoOddsFormatType.getById(oddsTypeId);
            String oddsType = oddsFormat != null ? oddsFormat.name() : "UNKNOWN"; // 例如 RM 表示马来盘
            String oddsTypeDesc = oddsFormat != null ? oddsFormat.getDescription() : "未知盘口";

            jsonObject.putOpt("oddsType", oddsType);       // 如 RM、HKC 等
            jsonObject.putOpt("oddsTypeName", oddsTypeDesc); // 如 "马来盘"

            int letType = jsonArray.getInt(17);  // 让球方字段
            String betTeam = jsonArray.getStr(16); // 投注队伍字段
            String handicapType;
            if (letType == 3 || "大盘".equals(betTeam) || "小盘".equals(betTeam)) {
                handicapType = "overSize"; // 大小盘
            } else {
                handicapType = "letBall";  // 让分盘
            }
            jsonObject.putOpt("handicapType", handicapType);

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
    public JSONObject execute(ConfigAccountVO userConfig, JSONObject params) {
        // 获取 完整API 路径
        String username = params.getStr("adminUsername");
        String siteId = params.getStr("websiteId");
        String baseUrl = websiteService.getWebsiteBaseUrl(username, siteId);
        String apiUrl = apiUrlService.getApiUrl(siteId, "unsettled");
        // 构建请求
        Map<String, String> requestHeaders = buildHeaders(params);
        String requestBody = buildRequest(params);

        // 构造请求体
        String queryParams = "locale=zh_CN";

        // 拼接完整的 URL
        String fullUrl = String.format("%s%s?%s", baseUrl, apiUrl, queryParams);

        // 使用代理发起 GET 请求
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
