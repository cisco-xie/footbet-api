package com.example.demo.core.sites.sbo;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.constants.Constants;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.config.OkHttpProxyDispatcher;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 盛帆网站 - 登录 API具体实现
 */
@Slf4j
@Component
public class WebsiteSboLoginHandler implements ApiHandler {

    private final OkHttpProxyDispatcher dispatcher;
    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteSboLoginHandler(OkHttpProxyDispatcher dispatcher,
                                  WebsiteService websiteService,
                                  ApiUrlService apiUrlService) {
        this.dispatcher = dispatcher;
        this.websiteService = websiteService;
        this.apiUrlService = apiUrlService;
    }

    /**
     * 严格按浏览器请求构建请求头
     */
    @Override
    public Map<String, String> buildHeaders(JSONObject params) {
        Map<String, String> headers = new HashMap<>();
        // 直接从curl复制过来的headers
        headers.put("accept", "application/json, text/plain, */*");
        headers.put("accept-language", "zh-CN,zh;q=0.9,pt-BR;q=0.8,pt;q=0.7");
        headers.put("cache-control", "no-cache");
        headers.put("origin", "https://www.u16888.com");
        headers.put("pragma", "no-cache");
        headers.put("priority", "u=1, i");
        headers.put("referer", "https://www.u16888.com/");
        headers.put("sec-ch-ua", Constants.SEC_CH_UA);
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
        headers.put("sec-fetch-dest", "empty");
        headers.put("sec-fetch-mode", "cors");
        headers.put("sec-fetch-site", "same-site");

        // 动态添加XSRF令牌（从参数获取）
        if (params.containsKey("xsrfToken")) {
            headers.put("x-xsrf-token", params.getStr("xsrfToken"));
        }

        // 动态添加Cookie（从参数获取）
        if (params.containsKey("cookie")) {
            headers.put("cookie", params.getStr("cookie"));
        }

        // 动态添加Content-Type（从参数获取）
        if (params.containsKey("contentType")) {
            headers.put("content-type", params.getStr("contentType"));
        }

        return headers;
    }

    /**
     * 构建登录请求体
     */
    @Override
    public String buildRequest(JSONObject params) {
        if (!params.containsKey("isLoginRequest") || !params.getBool("isLoginRequest")) {
            return null;
        }

        return new StringBuilder()
                .append("------WebKitFormBoundary9cClcrnMgMBfTU89\r\n")
                .append("Content-Disposition: form-data; name=\"Username\"\r\n\r\n")
                .append(params.getStr("username")).append("\r\n")
                .append("------WebKitFormBoundary9cClcrnMgMBfTU89\r\n")
                .append("Content-Disposition: form-data; name=\"Password\"\r\n\r\n")
                .append(params.getStr("password")).append("\r\n") // 明文密码
                .append("------WebKitFormBoundary9cClcrnMgMBfTU89\r\n")
                .append("Content-Disposition: form-data; name=\"Version\"\r\n\r\n")
                .append("1\r\n")
                .append("------WebKitFormBoundary9cClcrnMgMBfTU89\r\n")
                .append("Content-Disposition: form-data; name=\"DeviceType\"\r\n\r\n")
                .append("0\r\n")
                .append("------WebKitFormBoundary9cClcrnMgMBfTU89--\r\n")
                .toString();
    }

    /**
     * 构建第六步特定的headers
     */
    private Map<String, String> buildStep6Headers(JSONObject params) {
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
        headers.put("accept-language", "zh-CN,zh;q=0.9,pt-BR;q=0.8,pt;q=0.7");
        headers.put("cache-control", "no-cache");
        headers.put("pragma", "no-cache");
        headers.put("priority", "u=0, i");
        headers.put("referer", "https://www.u16888.com/");
        headers.put("sec-ch-ua", Constants.SEC_CH_UA);
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
        headers.put("sec-fetch-dest", "document");
        headers.put("sec-fetch-mode", "navigate");
        headers.put("sec-fetch-site", "same-site");
        headers.put("upgrade-insecure-requests", "1");

        if (params.containsKey("cookie")) {
            headers.put("cookie", params.getStr("cookie"));
        }

        return headers;
    }

    /**
     * 构建第七步特定的headers
     */
    private Map<String, String> buildStep7Headers(JSONObject params) {
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
        headers.put("accept-language", "zh-CN,zh;q=0.9,pt-BR;q=0.8,pt;q=0.7");
        headers.put("cache-control", "no-cache");
        headers.put("pragma", "no-cache");
        headers.put("priority", "u=0, i");
        headers.put("referer", "https://www.u16888.com/");
        headers.put("sec-ch-ua", Constants.SEC_CH_UA);
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
        headers.put("sec-fetch-dest", "document");
        headers.put("sec-fetch-mode", "navigate");
        headers.put("sec-fetch-site", "same-site");
        headers.put("upgrade-insecure-requests", "1");

        if (params.containsKey("cookie")) {
            headers.put("cookie", params.getStr("cookie"));
        }

        return headers;
    }

    /**
     * 直接解析原始响应，不做额外处理
     */
    @Override
    public JSONObject parseResponse(JSONObject params, OkHttpProxyDispatcher.HttpResult response) {
        JSONObject result = new JSONObject();

        // 状态码检查
        if (response.getStatus() >= 500) {
            return result.set("success", false)
                    .set("code", response.getStatus())
                    .set("msg", "HTTP请求失败: " + response.getStatus());
        }

        try {
            // 直接返回原始响应体
            return JSONUtil.parseObj(response);
        } catch (Exception e) {
            log.error("响应解析异常", e);
            return result.set("success", false)
                    .set("code", 500)
                    .set("msg", "响应解析失败");
        }
    }

    /**
     * 执行完整的四步登录流程
     */
    @Override
    public JSONObject execute(ConfigAccountVO userConfig, JSONObject params) {
        String siteId = params.getStr("websiteId");
        // 获取基础URL
        String baseUrl = websiteService.getWebsiteBaseUrl(
                params.getStr("adminUsername"),
                siteId
        );
        // String apiUrl = apiUrlService.getApiUrl(siteId, "login");
        // 拼接完整的 URL
        // String fullUrl = String.format("%s%s", baseUrl, apiUrl);
        // 构建API和账户URL
        String apiHomeUrl = insertSubdomain(baseUrl, "api-home");
        String accountsUrl = insertSubdomain(baseUrl, "accounts");
        String sportsBookUrl = insertSubdomain(baseUrl, "sportsbook");

        // 初始化cookie存储
        StringBuilder cookieStore = new StringBuilder();

        try {
            // 第一步：调用GetCurrentAsiProduct
            JSONObject step1Result = executeStep1(userConfig, apiHomeUrl, params, cookieStore);
            if (!step1Result.getBool("success", false)) {
                return step1Result;
            }

            // 从第一步响应中获取Location（如果有重定向）
            String step2Url = extractLocation(step1Result.get("response", OkHttpProxyDispatcher.HttpResult.class), params);
            if (step2Url == null) {
                // 如果没有Location头，使用默认的authorize URL
                step2Url = accountsUrl + "/connect/authorize?client_id=sbo&redirect_uri=https%3A%2F%2Fapi-home.u16888.com%2Fsignin-sbo%3FReferer%3Dhttps%253a%252f%252fwww.u16888.com%252f&response_type=code&scope=openid%20profile%20offline_access&code_challenge=eF_epIDiInR3wwUnz7fWUD_HNw5C3b7qXOXZmbDC7wE&code_challenge_method=S256&nonce=638911218224136837.YTE3MjI1MjEtYzNhNC00YWQ4LTgxMDYtOGNlMzA5MTM5YjlmMTE5YjIwODEtMTM2MS00NjIzLWIwMjUtZTYwY2U1Y2EyNjY0&state=CfDJ8APwHHDRm8xJpOAuGwNLolkXgkS2YANy7Kqzqc_MG2x0zjSCVsNm9oTTWmdfd1WAjLL_-npAhpcz6oTO0Qq0Bg2ufx-s9IhNthxHSZd5GlImwC7fxDKSPO2eZEOKNskEkTMFQtnea7iME41-USXGMkgUgGeLU5ch1z97sy7wCVc5uTcU9JPSG0RAVSgxYs8ucY7Cnl3Dz5x0nS2VwOlu6INdIjZRWCYjG6DP_XAUex6-wBRcl6ZZfUpZQbHuY3JWnl8Otm5QY3BLtmyzGqOePEEzy_tY64nqIwv-CTQIslfWb1qPelQThdRYHcihDka_jgp0CgZkVTVTpMQKcpmbt9D1ojlRncCooH2VSyI6YAw-aEpYhNseMutG-cwfGEhUyXGZkBCQM6IC-ahQgX8N1bRiaP9sTRx7wZ_sTtJHHiTiAPjSpfZZqHEt7ykiayLDUD6M4FVQrrb__2Ysmt2iAwWvYm4ig0_5nZ8h_8gTXcIY";
            }

            // 第二步：调用authorize接口
            JSONObject step2Result = executeStep2(userConfig, step2Url, params, cookieStore);
            if (!step2Result.getBool("success", false)) {
                return step2Result;
            }

            // 从第二步响应中获取Location
            String step3Url = extractLocation(step2Result.get("response", OkHttpProxyDispatcher.HttpResult.class), params);
            if (step3Url == null) {
                return new JSONObject().set("success", false).set("msg", "第二步未返回Location头");
            }

            // 第三步：调用login页面获取XSRF令牌
            JSONObject step3Result = executeStep3(userConfig, step3Url, params, cookieStore);
            if (!step3Result.getBool("success", false)) {
                return step3Result;
            }

            String xsrfToken = step3Result.getStr("xsrfToken");
            if (xsrfToken == null) {
                return new JSONObject().set("success", false).set("msg", "获取XSRF令牌失败");
            }

            // 第四步：执行登录（使用第三步的URL，因为通常是同一个登录端点）
            JSONObject step4Result = executeStep4(userConfig, step3Url, params, cookieStore, xsrfToken);

            // 从第四步响应中获取Location
            String step5Url = extractLocation(step4Result.get("response", OkHttpProxyDispatcher.HttpResult.class), params);
            if (step5Url == null) {
                return new JSONObject().set("success", false).set("msg", "第四步未返回Location头");
            }
            if (!step5Url.startsWith(accountsUrl)) {
                step5Url = accountsUrl + step5Url;
            }
            // 第五步：执行callback
            JSONObject step5Result = executeStep5(userConfig, step5Url, params, cookieStore, xsrfToken);
            if (!step5Result.getBool("success", false)) {
                return step5Result;
            }

            // 从第五步响应中获取Location
            String step6Url = extractLocation(step5Result.get("response", OkHttpProxyDispatcher.HttpResult.class), params);
            if (step6Url == null) {
                return new JSONObject().set("success", false).set("msg", "第五步未返回Location头");
            }
            // 第六步：调用signin-sbo回调接口
            JSONObject step6Result = executeStep6(userConfig, step6Url, params, cookieStore);
            if (!step6Result.getBool("success", false)) {
                return step6Result;
            }
            // 从第六步响应中获取Location
            String step7Url = extractLocation(step6Result.get("response", OkHttpProxyDispatcher.HttpResult.class), params);
            if (step7Url == null) {
                return new JSONObject().set("success", false).set("msg", "第六步未返回Location头");
            }

            if (!step7Url.startsWith(apiHomeUrl)) {
                step7Url = apiHomeUrl + step7Url;
            }
            // 第七步：重新调用GetCurrentAsiProduct接口
            JSONObject step7Result = executeStep7(userConfig, step7Url, params, cookieStore);
            if (!step7Result.getBool("success", false)) {
                return step7Result;
            }
            // 从第六步响应中获取Location
            String step8Url = extractLocation(step7Result.get("response", OkHttpProxyDispatcher.HttpResult.class), params);
            if (step8Url == null) {
                return new JSONObject().set("success", false).set("msg", "第七步未返回Location头");
            }

            // 第八步
            JSONObject step8Result = executeStep8(userConfig, step8Url, params, cookieStore);
            if (!step8Result.getBool("success", false)) {
                return step8Result;
            }

            // 第八步：调用CheckLoginBrand接口
            JSONObject step9Result = executeStep9(userConfig, apiHomeUrl, params, cookieStore);
            if (!step9Result.getBool("success", false)) {
                return step8Result;
            }

            // 第九步：调用getCustomerInfo接口
            JSONObject step10Result = executeStep10(userConfig, sportsBookUrl, params, cookieStore);
            if (!step10Result.getBool("success", false)) {
                return step9Result;
            }

            // 第十步：调用getTokens接口
            JSONObject step11Result = executeStep11(userConfig, sportsBookUrl, params, cookieStore);
            if (!step11Result.getBool("success", false) && 200 != step11Result.getInt("status")) {
                return new JSONObject().set("success", false).set("msg", "获取token失败");
            }
            JSONObject responseJson = new JSONObject();

            JSONObject step12Result = executeStep12(userConfig, apiHomeUrl, params, cookieStore);
            if (!step12Result.getBool("hasReadTermAndCondition")) {
                // 需要同意协议
                responseJson.putOpt("code", 110);
                responseJson.putOpt("success", false);
                responseJson.putOpt("msg", "需接受账户协议");
                return responseJson;
            }
            if (!step12Result.getBool("hasPasswordExpired")) {
                // 需要修改密码
                responseJson.putOpt("code", 106);
                responseJson.putOpt("success", false);
                responseJson.putOpt("msg", "需要修改账户密码");
                return responseJson;
            }
            responseJson.putOpt("success", true);
            responseJson.putOpt("token", new JSONObject(JSONUtil.parseObj(step11Result.getStr("body"))).putOpt("cookie", cookieStore));
            responseJson.putOpt("msg", "账户登录成功");
            return responseJson;

        } catch (Exception e) {
            log.error("登录流程执行异常", e);
            throw new BusinessException(SystemError.USER_1006);
        }
    }

    /**
     * 第一步：获取GetCurrentAsiProduct
     */
    private JSONObject executeStep1(ConfigAccountVO userConfig, String apiHomeUrl,
                                    JSONObject params, StringBuilder cookieStore) {
        String step1Url = apiHomeUrl + "/api/Product/GetCurrentAsiProduct?product=Sports";

        // 更新cookie
        params.set("cookie", cookieStore.toString());

        OkHttpProxyDispatcher.HttpResult response;
        try {
            response = dispatcher.execute("GET", step1Url, null, buildHeaders(params), userConfig, false);

            // 保存cookie
            updateCookieStore(response, cookieStore);

            JSONObject result = parseResponse(params, response);
            result.set("success", true);        // 请求成功
            result.set("response", response); // 保存原始响应对象以便提取Location
            return result;
        } catch (Exception e) {
            log.error("第一步请求异常", e);
            return new JSONObject().set("success", false).set("msg", "第一步请求失败");
        }
    }

    /**
     * 第二步：调用authorize接口
     */
    private JSONObject executeStep2(ConfigAccountVO userConfig, String step2Url,
                                    JSONObject params, StringBuilder cookieStore) {
        // 更新cookie
        params.set("cookie", cookieStore.toString());

        OkHttpProxyDispatcher.HttpResult response;
        try {
            response = dispatcher.execute("GET", step2Url, null, buildHeaders(params), userConfig, false);

            // 保存cookie
            updateCookieStore(response, cookieStore);

            JSONObject result = parseResponse(params, response);
            result.set("success", true);        // 请求成功
            result.set("response", response); // 保存原始响应对象以便提取Location
            return result;
        } catch (Exception e) {
            log.error("第二步请求异常", e);
            return new JSONObject().set("success", false).set("msg", "第二步请求失败");
        }
    }

    /**
     * 第三步：获取login页面和XSRF令牌
     */
    private JSONObject executeStep3(ConfigAccountVO userConfig, String step3Url,
                                    JSONObject params, StringBuilder cookieStore) {
        // 更新cookie
        params.set("cookie", cookieStore.toString());

        OkHttpProxyDispatcher.HttpResult response;
        try {
            response = dispatcher.execute("GET", step3Url, null, buildHeaders(params), userConfig, false);

            // 保存cookie
            updateCookieStore(response, cookieStore);

            // 从Set-Cookie头提取XSRF-TOKEN
            String xsrfToken = extractXsrfToken(response);

            JSONObject result = parseResponse(params, response);
            result.set("success", true);        // 请求成功
            result.set("response", response); // 保存原始响应对象
            result.set("xsrfToken", xsrfToken);
            return result;

        } catch (Exception e) {
            log.error("第三步请求异常", e);
            return new JSONObject().set("success", false).set("msg", "第三步请求失败");
        }
    }

    /**
     * 第四步：执行登录
     */
    private JSONObject executeStep4(ConfigAccountVO userConfig, String step4Url,
                                    JSONObject params, StringBuilder cookieStore, String xsrfToken) {
        // 更新cookie和设置登录相关参数
        params.set("cookie", cookieStore.toString());
        params.set("xsrfToken", xsrfToken);
        params.set("contentType", "multipart/form-data; boundary=----WebKitFormBoundary9cClcrnMgMBfTU89");
        params.set("isLoginRequest", true);

        OkHttpProxyDispatcher.HttpResult response;
        try {
            response = dispatcher.execute("POST", step4Url, buildRequest(params), buildHeaders(params), userConfig, false);

            // 保存cookie
            updateCookieStore(response, cookieStore);

            JSONObject result = parseResponse(params, response);
            result.set("success", true);        // 请求成功
            result.set("response", response); // 保存原始响应对象
            return result;
        } catch (Exception e) {
            log.error("第四步请求异常", e);
            return new JSONObject().set("success", false).set("msg", "第四步请求失败");
        }
    }

    /**
     * 第五步：callback
     */
    private JSONObject executeStep5(ConfigAccountVO userConfig, String step5Url,
                                    JSONObject params, StringBuilder cookieStore, String xsrfToken) {
        // 更新cookie和设置登录相关参数
        params.set("cookie", cookieStore.toString());
        params.set("xsrfToken", xsrfToken);

        OkHttpProxyDispatcher.HttpResult response;
        try {
            response = dispatcher.execute("GET", step5Url, null, buildHeaders(params), userConfig, false);

            // 保存cookie
            updateCookieStore(response, cookieStore);

            JSONObject result = parseResponse(params, response);
            result.set("success", true);        // 请求成功
            result.set("response", response); // 保存原始响应对象
            return result;
        } catch (Exception e) {
            log.error("第五步请求异常", e);
            return new JSONObject().set("success", false).set("msg", "第五步请求失败");
        }
    }

    /**
     * 第六步：调用signin-sbo回调接口
     */
    private JSONObject executeStep6(ConfigAccountVO userConfig, String step6Url,
                                    JSONObject params, StringBuilder cookieStore) {

        // 更新cookie
        params.set("cookie", cookieStore.toString());

        // 设置第六步特定的headers
        Map<String, String> step6Headers = buildStep6Headers(params);

        // 记录调试信息
        log.info("第六步signin-sbo URL: {}", step6Url);
        log.info("第六步Cookie: {}", cookieStore.toString());

        OkHttpProxyDispatcher.HttpResult response;
        try {
            response = dispatcher.execute("GET", step6Url, null, step6Headers, userConfig, false);

            // 记录响应信息
            log.info("第六步响应状态码: {}", response.getStatus());
            log.info("第六步响应头: {}", response.getHeaders());
            log.info("第六步响应体: {}", response.getBody());

            // 保存cookie
            updateCookieStore(response, cookieStore);

            JSONObject result = parseResponse(params, response);
            result.set("success", true);        // 请求成功
            result.set("response", response);
            return result;
        } catch (Exception e) {
            log.error("第六步请求异常", e);
            return new JSONObject().set("success", false).set("msg", "第六步signin-sbo请求失败");
        }
    }

    /**
     * 第七步：重新调用GetCurrentAsiProduct接口
     */
    private JSONObject executeStep7(ConfigAccountVO userConfig, String step7Url,
                                    JSONObject params, StringBuilder cookieStore) {

        // 更新cookie
        params.set("cookie", cookieStore.toString());

        // 设置第七步特定的headers
        Map<String, String> step7Headers = buildStep7Headers(params);

        // 记录调试信息
        log.info("第七步GetCurrentAsiProduct URL: {}", step7Url);
        log.info("第七步Cookie: {}", cookieStore.toString());

        OkHttpProxyDispatcher.HttpResult response;
        try {
            response = dispatcher.execute("GET", step7Url, null, step7Headers, userConfig, false);

            // 记录响应信息
            log.info("第七步响应状态码: {}", response.getStatus());
            log.info("第七步响应头: {}", response.getHeaders());
            log.info("第七步响应体: {}", response.getBody());

            // 保存cookie
            updateCookieStore(response, cookieStore);

            JSONObject result = parseResponse(params, response);
            result.set("success", true);        // 请求成功
            result.set("response", response);
            return result;
        } catch (Exception e) {
            log.error("第七步请求异常", e);
            return new JSONObject().set("success", false).set("msg", "第七步GetCurrentAsiProduct请求失败");
        }
    }

    /**
     * 第八步：
     * 进入 https://sportsbook.u16888.com
     */
    private JSONObject executeStep8(ConfigAccountVO userConfig, String step8Url,
                                    JSONObject params, StringBuilder cookieStore) {

        // 更新cookie
        params.set("cookie", cookieStore.toString());

        // 设置第七步特定的headers
        Map<String, String> step7Headers = buildStep7Headers(params);

        // 记录调试信息
        log.info("第八步GetCurrentAsiProduct URL: {}", step8Url);
        log.info("第八步Cookie: {}", cookieStore.toString());

        OkHttpProxyDispatcher.HttpResult response;
        try {
            response = dispatcher.execute("GET", step8Url, null, step7Headers, userConfig, false);

            // 记录响应信息
            log.info("第八步响应状态码: {}", response.getStatus());
            log.info("第八步响应头: {}", response.getHeaders());
            log.info("第八步响应体: {}", response.getBody());

            // 保存cookie
            updateCookieStore(response, cookieStore);

            JSONObject result = parseResponse(params, response);
            result.set("success", true);        // 请求成功
            result.set("response", response);
            return result;
        } catch (Exception e) {
            log.error("第八步请求异常", e);
            return new JSONObject().set("success", false).set("msg", "第八步GetCurrentAsiProduct请求失败");
        }
    }

    /**
     * 第九步：调用CheckLoginBrand接口
     */
    private JSONObject executeStep9(ConfigAccountVO userConfig, String apiHomeUrl,
                                    JSONObject params, StringBuilder cookieStore) {
        String step9Url = apiHomeUrl + "/api/Login/CheckLoginBrand";

        // 更新cookie和设置JSON content-type
        params.set("cookie", cookieStore.toString());
        params.set("contentType", "application/json");

        // 记录调试信息
        log.info("第九步CheckLoginBrand URL: {}", step9Url);
        log.info("第九步Cookie: {}", cookieStore.toString());

        OkHttpProxyDispatcher.HttpResult response;
        try {
            String requestBody = buildRequestCheckLoginBrand(params);
            log.info("第九步请求体: {}", requestBody);

            response = dispatcher.execute("POST", step9Url, requestBody, buildHeaders(params), userConfig, false);

            // 记录响应信息
            log.info("第九步响应状态码: {}", response.getStatus());
            log.info("第九步响应头: {}", response.getHeaders());
            log.info("第九步响应体: {}", response.getBody());

            // 保存cookie
            updateCookieStore(response, cookieStore);

            JSONObject result = parseResponse(params, response);
            result.set("success", true);        // 请求成功
            result.set("response", response);
            return result;
        } catch (Exception e) {
            log.error("第九步请求异常", e);
            return new JSONObject().set("success", false).set("msg", "第九步CheckLoginBrand请求失败");
        }
    }

    /**
     * 第十步：调用getCustomerInfo接口
     */
    private JSONObject executeStep10(ConfigAccountVO userConfig, String sportsBookUrl,
                                    JSONObject params, StringBuilder cookieStore) {
        String step7Url = sportsBookUrl + "/api/account/getCustomerInfo";

        // 更新cookie
        params.set("cookie", cookieStore.toString());

        // 记录调试信息
        log.info("第十步getCustomerInfo URL: {}", step7Url);
        log.info("第十步Cookie: {}", cookieStore.toString());

        OkHttpProxyDispatcher.HttpResult response;
        try {
            response = dispatcher.execute("GET", step7Url, null, buildHeaders(params), userConfig, false);

            // 记录响应信息
            log.info("第十步响应状态码: {}", response.getStatus());
            log.info("第十步响应头: {}", response.getHeaders());
            log.info("第十步响应体: {}", response.getBody());

            // 保存cookie
            updateCookieStore(response, cookieStore);

            JSONObject result = parseResponse(params, response);
            result.set("success", true);        // 请求成功
            result.set("response", response);
            return result;
        } catch (Exception e) {
            log.error("第十步请求异常", e);
            return new JSONObject().set("success", false).set("msg", "第十步getCustomerInfo请求失败");
        }
    }

    /**
     * 第十一步：调用getTokens接口
     */
    private JSONObject executeStep11(ConfigAccountVO userConfig, String sportsBookUrl,
                                    JSONObject params, StringBuilder cookieStore) {
        String step8Url = sportsBookUrl + "/api/oddsApi/getTokens";

        // 更新cookie
        params.set("cookie", cookieStore.toString());

        // 记录调试信息
        log.info("第十一步getTokens URL: {}", step8Url);
        log.info("第十一步Cookie: {}", cookieStore.toString());

        OkHttpProxyDispatcher.HttpResult response;
        try {
            response = dispatcher.execute("GET", step8Url, null, buildHeaders(params), userConfig, false);

            // 记录响应信息
            log.info("第十一步响应状态码: {}", response.getStatus());
            log.info("第十一步响应头: {}", response.getHeaders());
            log.info("第十一步响应体: {}", response.getBody());

            // 保存cookie
            updateCookieStore(response, cookieStore);

            JSONObject result = parseResponse(params, response);
            result.set("success", true);        // 请求成功
            result.set("response", response);
            return result;
        } catch (Exception e) {
            log.error("第十一步请求异常", e);
            return new JSONObject().set("success", false).set("msg", "第十一步getTokens请求失败");
        }
    }

    /**
     * 第十二步：调用查询用户信息接口-用于检查用户是否需要同意协议和修改密码
     */
    private JSONObject executeStep12(ConfigAccountVO userConfig, String apiHomeUrl,
                                     JSONObject params, StringBuilder cookieStore) {
        String step8Url = apiHomeUrl + "/api/user/Get";

        // 更新cookie
        params.set("cookie", cookieStore.toString());

        // 记录调试信息
        log.info("第十二步getTokens URL: {}", step8Url);
        log.info("第十二步Cookie: {}", cookieStore.toString());

        OkHttpProxyDispatcher.HttpResult response;
        try {
            response = dispatcher.execute("GET", step8Url, null, buildHeaders(params), userConfig, false);

            // 记录响应信息
            log.info("第十二步响应状态码: {}", response.getStatus());
            log.info("第十二步响应头: {}", response.getHeaders());
            log.info("第十二步响应体: {}", response.getBody());

            JSONObject result = parseResponse(params, response);
            result.set("success", true);        // 请求成功
            result.set("response", response);
            return result;
        } catch (Exception e) {
            log.error("第十二步请求异常", e);
            return new JSONObject().set("success", false).set("msg", "第十二步getTokens请求失败");
        }
    }

    /**
     * 用于第六步的请求参数 - CheckLoginBrand
     */
    public String buildRequestCheckLoginBrand(JSONObject params) {
        JSONObject requestBody = new JSONObject();
        requestBody.set("loginName", params.getStr("username"));
        requestBody.set("language", "zh-cn");
        return requestBody.toString();
    }

    /**
     * 从响应头中提取Location
     */
    private String extractLocation(OkHttpProxyDispatcher.HttpResult response, JSONObject params) {
        if (response == null) {
            return null;
        }

        List<String> locationHeaders = response.getHeaders().get("location");
        if (locationHeaders != null && !locationHeaders.isEmpty()) {
            String location = locationHeaders.get(0);
            // 处理特殊的前缀：去掉 https://www.u16888.com/#!/
            if (location.contains("/#!/")) {
                // 提取路径部分（去掉前面的域名和/#!/）
                String path = location.substring(location.indexOf("/#!/") + 3); // +3 保留斜杠
                // 构建完整的URL（使用accounts子域名）
                String baseUrl = websiteService.getWebsiteBaseUrl(
                        params.getStr("adminUsername"),
                        params.getStr("websiteId")
                );
                String accountsUrl = insertSubdomain(baseUrl, "accounts");
                return accountsUrl + path;
            }
            return location;
        }
        return null;
    }

    /**
     * 从响应中提取XSRF令牌
     */
    private String extractXsrfToken(OkHttpProxyDispatcher.HttpResult response) {
        List<String> cookies = response.getHeaders().get("Set-Cookie");
        if (cookies != null) {
            for (String cookie : cookies) {
                if (cookie.startsWith("XSRF-TOKEN=")) {
                    return cookie.split(";")[0].substring(11);
                }
            }
        }
        return null;
    }

    /**
     * 更新cookie存储
     */
    private void updateCookieStore(OkHttpProxyDispatcher.HttpResult response, StringBuilder cookieStore) {
        List<String> cookies = response.getHeaders().get("Set-Cookie");
        if (cookies != null) {
            for (String cookie : cookies) {
                String cookieName = cookie.split("=")[0];
                String cookieValue = cookie.split(";")[0];

                // 如果cookieStore中已存在该cookie，则替换
                if (cookieStore.indexOf(cookieName + "=") != -1) {
                    String currentCookies = cookieStore.toString();
                    String[] cookieArray = currentCookies.split("; ");
                    StringBuilder newCookieStore = new StringBuilder();

                    for (String existingCookie : cookieArray) {
                        if (!existingCookie.startsWith(cookieName + "=")) {
                            if (newCookieStore.length() > 0) {
                                newCookieStore.append("; ");
                            }
                            newCookieStore.append(existingCookie);
                        }
                    }

                    if (newCookieStore.length() > 0) {
                        newCookieStore.append("; ");
                    }
                    newCookieStore.append(cookieValue);
                    cookieStore.setLength(0);
                    cookieStore.append(newCookieStore.toString());
                } else {
                    // 添加新cookie
                    if (cookieStore.length() > 0) {
                        cookieStore.append("; ");
                    }
                    cookieStore.append(cookieValue);
                }
            }
        }

        // 确保 lang=zh-cn 存在
        if (cookieStore.indexOf("lang=") == -1) {
            if (cookieStore.length() > 0) {
                cookieStore.append("; ");
            }
            cookieStore.append("lang=zh-cn");
        }

        // 确保 LanguageType=ZH_CN 存在
        if (cookieStore.indexOf("LanguageType=") == -1) {
            if (cookieStore.length() > 0) {
                cookieStore.append("; ");
            }
            cookieStore.append("LanguageType=ZH_CN");
        }
    }

    public static String insertSubdomain(String baseUrl, String subdomain) {
        URI uri = URI.create(baseUrl);
        String host = uri.getHost();  // u16888.com
        String newHost = subdomain + "." + host;
        return baseUrl.replaceFirst(host, newHost);
    }
}