package com.example.demo.api;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.collection.IterUtil;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.date.TimeInterval;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.jwt.JWTUtil;
import com.example.demo.common.constants.RedisConstants;
import com.example.demo.common.enmu.GameType;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.common.utils.KeyUtil;
import com.example.demo.common.utils.ToDayRangeUtil;
import com.example.demo.config.PriorityTaskExecutor;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.core.model.UserConfig;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.dto.LoginDTO;
import com.example.demo.model.vo.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.redisson.api.*;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Slf4j
@Component
public class FalaliApi {

    @Resource(name = "defaultRedissonClient")
    private RedissonClient redisson;

    @Resource(name = "businessUserRedissonClient")
    private RedissonClient businessUserRedissonClient;

    @Resource
    private ConfigService configService;

    // 本地缓存赔率数据
    private final Map<String, String> localOddsCache = new ConcurrentHashMap<>();

    /**
     * 设置账号代理
     * @param userProxy
     */
    public void config(ConfigUserVO userProxy) {
        UserConfig proxy = BeanUtil.copyProperties(userProxy, UserConfig.class);
        if (BeanUtil.isNotEmpty(proxy)) {
            businessUserRedissonClient.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, userProxy.getAccount())).set(JSONUtil.toJsonStr(proxy));
        }
    }

    /**
     * 获取登录验证码信息
     *
     * @return 验证码id
     */
    public Map<String, String> code(String uuid, UserConfig userConfig) {
        Map<String, String> resultMap = new HashMap<>();
        String url = userConfig.getBaseUrl() + "code?_=" + System.currentTimeMillis();
        Map<String, String> headers = new HashMap<>();
        headers.put("priority", "u=0, i");
        headers.put("sec-fetch-user", "?1");
        headers.put("upgrade-insecure-requests", "1");
        headers.put("Cookie", "2a29530a2306="+uuid);

//        String fileName = "d://code-" + IdUtil.randomUUID() + ".jpg";
        String fileName = "/usr/local/resources/projects/falali/code-" + IdUtil.randomUUID() + ".jpg";
        // 最大重试次数
        int maxRetries = 6;
        // 重试间隔（毫秒）
        int retryDelay = 100;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("尝试请求验证码: 第{}次尝试", attempt);

                // 创建请求对象
                HttpRequest request = HttpRequest.get(url)
                        .addHeaders(headers)
                        .timeout(10000);

                // 引入代理配置
                configureProxy(request, userConfig);

                // 执行请求并获取结果
                HttpResponse resultRes = request.execute();

                if (resultRes.body().contains("416 Range Not Satisfiable")) {
                    log.error("代理请求失败：出现416代理异常信息：{}", resultRes.body());
                    resultMap.put("code", "0000");
                    return resultMap;
                }
                // 保存验证码图片
                resultRes.writeBody(fileName);

                // 验证文件是否是有效图片
                File image = new File(fileName);
                if (ImageIO.read(image) == null) {
                    log.error("下载的文件不是有效图片: {}", fileName);
                    resultMap.put("code", "0000");
                    return resultMap;
                }

                // 设置动态库路径
                System.setProperty("jna.library.path", "/lib/x86_64-linux-gnu");

                // OCR 处理
                Tesseract tesseract = new Tesseract();
//                tesseract.setDatapath("D://tessdata");
                tesseract.setDatapath("/usr/local/resources/projects/falali/tessdata");
                tesseract.setLanguage("eng");
                String result = tesseract.doOCR(image).trim();
                log.info("验证码解析结果: {}", result);
                if (!NumberUtil.isNumber(result) && result.length() == 4) {
                    log.info("验证码解析结果: {},不是纯4位数字", result);
                    continue;
                }
                resultMap.put("code", result);
                resultMap.put("2a29530a2306", resultRes.getCookieValue("2a29530a2306"));
                return resultMap;

            } catch (Exception e) {

                // 等待一段时间再重试
                log.warn("验证码第{}次尝试失败，等待{}毫秒后重试。异常信息：{}", attempt, retryDelay, e.getMessage());
                // 如果达到最大重试次数，抛出异常
                if (attempt == maxRetries) {
                    log.error("验证码达到最大重试次数，抛出异常");
                    throw new BusinessException(SystemError.USER_1009, userConfig.getAccount(), e);
                }

                Throwable cause = e.getCause();
                if (cause instanceof UnknownHostException) {
                    log.error("代理请求失败：主机未知。可能是域名解析失败或代理地址有误。异常信息：{}", e.getMessage(), e);
                    throw new BusinessException(SystemError.SYS_419, userConfig.getAccount(), "代理请求失败");
                } else if (cause instanceof ConnectException) {
                    log.error("代理请求失败：连接异常。可能是代理服务器未开启或网络不可达。异常信息：{}", e.getMessage(), e);
                    throw new BusinessException(SystemError.SYS_419, userConfig.getAccount(), "连接异常");
                } else if (cause instanceof SocketTimeoutException) {
                    log.error("代理请求失败：连接超时。可能是网络延迟过高或代理服务器响应缓慢。异常信息：{}", e.getMessage(), e);
                    throw new BusinessException(SystemError.SYS_419, userConfig.getAccount(), "连接超时");
                } else if (cause instanceof IOException) {
                    log.error("代理请求失败：IO异常。可能是数据传输错误或代理配置不正确。异常信息：{}", e.getMessage(), e);
                    throw new BusinessException(SystemError.SYS_419, userConfig.getAccount(), "IO异常");
                } else {
                    log.error("代理请求失败：未知异常。异常信息：{}", e.getMessage(), e);
                    throw new BusinessException(SystemError.SYS_419, userConfig.getAccount(), "未知异常");
                }

                // try {
                //    Thread.sleep(retryDelay);
                //} catch (InterruptedException interruptedException) {
                //    Thread.currentThread().interrupt();
                //    log.error("线程中断", interruptedException);
                //}
            } finally {
                // 删除临时文件
                try {
                    Files.deleteIfExists(Paths.get(fileName));
                } catch (IOException e) {
                    log.warn("临时文件删除失败: {}", fileName, e);
                }
            }
        }

        // 如果循环结束后仍未成功，返回失败标识
        resultMap.put("code", "0000");
        return resultMap;
    }

    /**
     * 登录
     *
     * @return token
     */
    public String login(String params, String uuid) {
        String url = "https://3575978705.tcrax4d8j.com/login";
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("accept-language", "zh-CN,zh;q=0.9");
        headers.put("content-type", "application/x-www-form-urlencoded");
        HttpResponse resultRes = HttpRequest.post(url)
                .addHeaders(headers)
                .cookie("2a29530a2306=" + uuid)
                .body(params)
                .execute();
        return resultRes.getCookieValue("token");
    }

    /**
     * 批量登录
     *
     * @return token
     */
//    public List<LoginDTO> batchLogin(List<LoginVO> logins) {
//        List<LoginDTO> tokens = new ArrayList<>();
//        final LoginDTO[] loginDTO = {null};
//        logins.forEach(login -> {
//            String token = null;
//            loginDTO[0] = new LoginDTO();
//            loginDTO[0].setAccount(login.getAccount());
//            int retryCount = 0; // 重试次数计数
//            final int maxRetries = 3; // 最大重试次数
//
//            String url = "https://3575978705.tcrax4d8j.com/login";
//            while (retryCount < maxRetries) {
//                try {
//                    Map<String, String> headers = new HashMap<>();
//                    headers.put("accept", "*/*");
//                    headers.put("accept-language", "zh-CN,zh;q=0.9");
//                    headers.put("content-type", "application/x-www-form-urlencoded");
//                    String uuid = IdUtil.randomUUID();
//                    String params = "type=1&account=" + login.getAccount() + "&password=" + login.getPassword() + "&code=" + code(uuid);
//                    HttpResponse resultRes = HttpRequest.post(url)
//                            .addHeaders(headers)
//                            .cookie("2a29530a2306=" + uuid)
//                            .body(params)
//                            .execute();
//                    token = resultRes.getCookieValue("token");
//                    // 如果成功获取到 token，跳出重试循环
//                    if (!token.isBlank()) {
//                        break;
//                    }
//                } catch (Exception e) {
//                    // 捕获异常并打印日志
//                    log.error("账号 {} 获取 token 时出现异常: {}", login.getAccount(), e.getMessage());
//                }
//                retryCount++; // 增加重试次数
//
//                // 在重试前添加短暂延迟，避免过快重复调用
//                try {
//                    Thread.sleep(300); // 延迟 300 毫秒
//                } catch (InterruptedException ex) {
//                    Thread.currentThread().interrupt();
//                }
//            }
//
//            // 如果达到最大重试次数仍未获取到 token，添加占位信息或错误标记
//            if (token == null || token.isEmpty()) {
//                log.warn("账号 {} 获取 token 失败，已达到最大重试次数", login.getAccount());
//                // token = "FAILED";
//            }
//            loginDTO[0].setToken(token);
//            tokens.add(loginDTO[0]);
//        });
//
//        return tokens;
//    }

    public AdminLoginDTO adminLogin(AdminLoginVO login) {

        // Redis 键值
        String redisKey = KeyUtil.genKey(RedisConstants.USER_ADMIN_PREFIX, login.getUsername());

        // 判断 Redis 中是否存在该用户数据
        boolean exists = businessUserRedissonClient.getBucket(redisKey).isExists();
        if (exists) {
            AdminLoginDTO adminLogin = JSONUtil.toBean(JSONUtil.parseObj(businessUserRedissonClient.getBucket(redisKey).get()), AdminLoginDTO.class);
            if (StringUtils.equals(login.getPassword(), adminLogin.getPassword())) {
                String token = JWTUtil.createToken(BeanUtil.beanToMap(adminLogin), "admin".getBytes());
                adminLogin.setAccessToken(token);
                adminLogin.setRefreshToken(token);
                return adminLogin;
            } else {
                throw new BusinessException(SystemError.USER_1008);
            }
        } else {
            throw new BusinessException(SystemError.USER_1008);
        }
    }

    /**
     * 单账号下线
     * @param username
     * @param id
     */
    public void singleLoginOut(String username, String id) {
        UserConfig userConfig = JSONUtil.toBean(JSONUtil.parseObj(businessUserRedissonClient.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, id)).get()), UserConfig.class);
        if (BeanUtil.isNotEmpty(userConfig)) {
            userConfig.setToken("");
            userConfig.setIsAutoLogin(0);
            userConfig.setIsTokenValid(0);
            // 下线清空相关金额
            userConfig.setBalance(null);
            userConfig.setBetting(null);
            userConfig.setAmount(null);
            userConfig.setResult(null);
            userConfig.setUpdateTime(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATETIME_PATTERN));
            businessUserRedissonClient.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, id)).set(JSONUtil.toJsonStr(userConfig));
        }
    }

    /**
     * 批量账号下线
     * @param username
     */
    public void batchLoginOut(String username) {
        List<UserConfig> userConfigs = configService.accounts(username, null);
        userConfigs.forEach(userConfig -> {
            userConfig.setToken(null);
            userConfig.setIsAutoLogin(0);
            userConfig.setIsTokenValid(0);
            // 下线清空相关金额
            userConfig.setBalance(null);
            userConfig.setBetting(null);
            userConfig.setAmount(null);
            userConfig.setResult(null);
            userConfig.setUpdateTime(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATETIME_PATTERN));
            businessUserRedissonClient.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, userConfig.getId())).set(JSONUtil.toJsonStr(userConfig));
        });
    }


    /**
     * 修改初始密码 密码要求：6-20个字符必须由大小写字母和数字组成
     * @param username
     * @param id
     * @param token
     * @param oldPassword
     * @param password
     * @return
     */
    public Boolean changePassword(String username, String id, String token, String oldPassword, String password) {
        List<UserConfig> userConfigs = configService.accounts(username, id);
        if (CollUtil.isEmpty(userConfigs)) {
            throw new BusinessException(SystemError.USER_1007);
        }
        log.info("修改初始密码-获取盘口账号redis信息:{}", userConfigs.get(0));
        String baseUrl = userConfigs.get(0).getBaseUrl();
        String url = baseUrl + "member/changePassword";
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("accept-language", "zh-CN,zh;q=0.9");
        headers.put("cookie", "token=" + token);
        headers.put("priority", "u=1, i");
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
        headers.put("sec-fetch-dest", "empty");
        headers.put("sec-fetch-mode", "cors");
        headers.put("sec-fetch-site", "same-origin");
        headers.put("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
        headers.put("x-requested-with", "XMLHttpRequest");

        String params = "oldPassword="+oldPassword+"&password=" + password;

        // 设置代理和认证
        HttpRequest request = HttpRequest.post(url)
                .addHeaders(headers)
                .body(params);
        // 动态设置代理类型
        configureProxy(request, userConfigs.get(0));

        try {
            // 发起请求
            HttpResponse resultRes = request.execute();
            if (200 == resultRes.getStatus()) {
                JSONObject result = JSONUtil.parseObj(resultRes.body());
                if (result.getBool("success")) {
                    log.info("修改初始密码-盘口账号:{} 旧密码:{} 新密码:{} 修改成功", userConfigs.get(0).getAccount(), oldPassword, password);
                    return true;
                } else {
                    log.error("修改初始密码-盘口账号:{} 旧密码:{} 新密码:{} 修改失败 返回提示:{}", userConfigs.get(0).getAccount(), oldPassword, password, result.getStr("message"));
                    return false;
                }
//                userConfigs.get(0).setIsTokenValid(1);
//                userConfigs.get(0).setPassword(password);
//                userConfigs.get(0).setUpdateTime(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATETIME_PATTERN));
//                redisson.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, userConfigs.get(0).getId())).set(JSONUtil.toJsonStr(userConfigs.get(0)));
            }
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof UnknownHostException) {
                log.error("代理请求失败：主机未知。可能是域名解析失败或代理服务器地址有误。异常信息：{}", e.getMessage(), e);
            } else if (cause instanceof ConnectException) {
                log.error("代理请求失败：连接异常。可能是代理服务器未开启或网络不可达。异常信息：{}", e.getMessage(), e);
            } else if (cause instanceof SocketTimeoutException) {
                log.error("代理请求失败：连接超时。可能是网络延迟过高或代理服务器响应缓慢。异常信息：{}", e.getMessage(), e);
            } else if (cause instanceof IOException) {
                log.error("代理请求失败：IO异常。可能是数据传输错误或代理配置不正确。异常信息：{}", e.getMessage(), e);
            } else {
                log.error("代理请求失败：未知异常。请检查代理配置和网络状态。异常信息：{}", e.getMessage(), e);
            }
        }
        log.error("修改初始密码-盘口账号:{} 旧密码:{} 新密码:{} 修改失败", userConfigs.get(0).getAccount(), oldPassword, password);
        return false;
    }

    /**
     * 单个账号登录
     * @param id
     * @return
     */
    public LoginDTO singleLogin(String username, String id) {
        List<UserConfig> userConfigs = configService.accounts(username, id);
        if (CollUtil.isEmpty(userConfigs)) {
            throw new BusinessException(SystemError.USER_1007);
        }
        String baseUrl = userConfigs.get(0).getBaseUrl();
        LoginDTO loginDTO = new LoginDTO();
        loginDTO.setAccount(userConfigs.get(0).getAccount());

        // 获取代理配置
        UserConfig userConfig = userConfigs.get(0);

        if (StringUtils.isNotBlank(userConfig.getToken())) {
            JSONArray jsonArray = account(username, id);
            if (CollUtil.isNotEmpty(jsonArray)) {
                // 说明当前账号token还有效，不再登录
                loginDTO.setToken(userConfigs.get(0).getToken());
                userConfig.setToken(userConfigs.get(0).getToken());
                userConfig.setIsTokenValid(1);
                userConfig.setUpdateTime(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATETIME_PATTERN));
                businessUserRedissonClient.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, userConfigs.get(0).getId())).set(JSONUtil.toJsonStr(userConfig));
                return loginDTO;
            }
        }

        int retryCount = 0;
        int maxRetries = 3;
        String token = null;

        String uuid = IdUtil.randomUUID();
        if (userConfig.getBaseUrl().contains("www.cq88")) {
            uuid = "ZrbRtM30gQzIhLcEqoKd2YeReMR9zrbldCzNYRN9imOeUpD+VUOtT3vqOe7tT6iE+DCbJuZcF5+AgGPo8BM=";
        }
        while (retryCount < maxRetries) {
            Map<String, String> code = code(uuid, userConfig);
            if ("0000".equals(code.get("code"))) {
                retryCount++;
                continue;
            }
            if (userConfig.getBaseUrl().contains("www.cq88") && code.containsKey("2a29530a2306") && !code.get("2a29530a2306").isBlank()) {
                uuid = code.get("2a29530a2306");
            }
            Map<String, String> headers = new HashMap<>();
            String url = baseUrl + "login";
            headers.put("priority", "u=0, i");
            headers.put("sec-fetch-user", "?1");
            headers.put("upgrade-insecure-requests", "1");
            headers.put("content-type", "application/x-www-form-urlencoded");
            headers.put("Cookie", "2a29530a2306="+uuid);

            String params = "type=1&account=" + userConfig.getAccount() + "&password=" + userConfig.getPassword() + "&code=" + code.get("code");

            log.info("登录请求 账号 {} uuid 为: {} 完整url {} 参数{}", userConfig.getAccount(), uuid, url, params);
            // 创建请求对象
            HttpRequest request = HttpRequest.post(url)
                    .addHeaders(headers)
                    .timeout(10000)
//                    .cookie(cok)
                    .body(params);

            // 配置代理
            configureProxy(request, userConfig);
            try {
                HttpResponse resultRes = request.execute();
                String location = resultRes.header("location");
                token = resultRes.getCookieValue("token");
                if (StringUtils.isNotBlank(location)) {
                    if (location.contains("login?e=3")) {
                        // 验证码错误
                        log.info("登录请求 账号{} 验证码{} 验证码错误", userConfigs.get(0).getAccount(), code.get("code"));
                        retryCount++;
                        continue;
                    } else if (location.contains("login?e=4")) {
                        throw new BusinessException(SystemError.USER_1008);
                    } else if (location.contains("login?e=9")) {
                        throw new BusinessException(SystemError.USER_1012, userConfig.getAccount());
                    } else if (location.contains("login?e=14")) {
                        throw new BusinessException(SystemError.USER_1011, userConfig.getAccount());
                    } else if (location.contains("member/update_password")) {
                        // 初次登录需要修改密码
                        String password = RandomUtil.randomString(6) + RandomUtil.randomNumbers(2);
                        boolean change = changePassword(username, id, token, userConfig.getPassword(), password);
                        if (!change) {
                            throw new BusinessException(SystemError.USER_1010, userConfig.getAccount());
                        }
                        // 更新 userConfig 的密码，并同步到缓存
                        userConfig.setPassword(password);
                        userConfig.setToken(null); // 清理旧 Token
                        userConfig.setIsTokenValid(0);
                        userConfig.setIsTokenValid(0);
                        businessUserRedissonClient.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, userConfig.getId())).set(JSONUtil.toJsonStr(userConfig));
                        // 重新登录以获取新的 token
                        // retryCount--;
                        // continue;
                        // 修改成功后直接跳出不继续登录，因为平台有15s的登录时间限制
                        break;
                    }
                }

                // 开启自动登录
                userConfig.setIsAutoLogin(1);
                // token 不为空，成功
                if (StringUtils.isNotBlank(token)) {
                    // 将获取到的 token 存入缓存
                    loginDTO.setToken(token);
                    userConfig.setToken(token);
                    userConfig.setIsTokenValid(1);
                    userConfig.setUpdateTime(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATETIME_PATTERN));
                    businessUserRedissonClient.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, userConfigs.get(0).getId())).set(JSONUtil.toJsonStr(userConfig));
                    log.info("登录请求 账号 {} 参数{} 获取token成功 token:{}", userConfigs.get(0).getAccount(), params, token);
                    // JSONArray jsonArray = account(username, id);
                    return loginDTO;
                }
                log.info("登录请求 账号 {} 参数{} 获取token失败", userConfigs.get(0).getAccount(), params);

                retryCount++;
                // 延迟重试
                // ThreadUtil.sleep(150);
            } catch (Exception e) {
                log.warn("账号 {} 获取 token 异常", userConfigs.get(0).getAccount());
                Throwable cause = e.getCause();
                if (cause instanceof UnknownHostException) {
                    log.error("代理请求失败：主机未知。可能是域名解析失败或代理地址有误。异常信息：{}", e.getMessage(), e);
                    throw new BusinessException(SystemError.SYS_419, userConfig.getAccount(), "代理请求失败");
                } else if (cause instanceof ConnectException) {
                    log.error("代理请求失败：连接异常。可能是代理服务器未开启或网络不可达。异常信息：{}", e.getMessage(), e);
                    throw new BusinessException(SystemError.SYS_419, userConfig.getAccount(), "连接异常");
                } else if (cause instanceof SocketTimeoutException) {
                    log.error("代理请求失败：连接超时。可能是网络延迟过高或代理服务器响应缓慢。异常信息：{}", e.getMessage(), e);
                    throw new BusinessException(SystemError.SYS_419, userConfig.getAccount(), "连接超时");
                } else if (cause instanceof IOException) {
                    log.error("代理请求失败：IO异常。可能是数据传输错误或代理配置不正确。异常信息：{}", e.getMessage(), e);
                    throw new BusinessException(SystemError.SYS_419, userConfig.getAccount(), "IO异常");
                } else {
                    log.error("代理请求失败：未知异常。异常信息：{}", e.getMessage(), e);
                    // throw new BusinessException(SystemError.SYS_419, userConfig.getAccount(), "未知异常");
                }
            }
        }

        if (StringUtils.isBlank(token) && retryCount == maxRetries) {
            userConfig.setToken(null);
            userConfig.setIsTokenValid(0);
            userConfig.setBalance(null);
            userConfig.setBetting(null);
            userConfig.setAmount(null);
            userConfig.setResult(null);
            userConfig.setUpdateTime(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATETIME_PATTERN));
            businessUserRedissonClient.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, userConfigs.get(0).getId())).set(JSONUtil.toJsonStr(userConfig));
            log.warn("账号 {} 获取 token 失败，已达到最大重试次数", userConfigs.get(0).getAccount());
        }
        return loginDTO;
    }


    /**
     * 批量登录
     *
     * @return token
     */
    public JSONObject batchLogin(String username) {
        List<UserConfig> userConfigs = configService.accounts(username, null);
        List<LoginDTO> results = Collections.synchronizedList(new ArrayList<>());
        Set<String> tokenSet = ConcurrentHashMap.newKeySet();

        // 记录成功和失败的数量
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        // 线程池大小可以根据任务量调整
        ExecutorService executorService = Executors.newFixedThreadPool(10);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        userConfigs.forEach(login -> {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                LoginDTO loginDTO;
                int retryCount = 0;
                final int maxRetries = 3;
                while (retryCount < maxRetries) {
                    loginDTO = singleLogin(username, login.getId()); // 调用单个登录逻辑
                    String token = loginDTO.getToken();
                    log.error("账号{}==登录{}次 login {}", login.getAccount(), retryCount, token);

                    if (StringUtils.isNotBlank(token) && tokenSet.add(token)) {
                        // token 不为空且未重复，成功
                        results.add(loginDTO);
                        successCount.incrementAndGet(); // 成功数量+1
                        return;
                    }

                    retryCount++;
                    log.warn("账号 {} 的 token {} 已重复，重新尝试获取 (重试次数: {})", login.getAccount(), token, retryCount);
                }

                // 登录失败
                log.error("账号 {} 最终获取 token 失败，重试次数已达上限", login.getAccount());
                results.add(new LoginDTO(login.getAccount(), null)); // 失败标记
                failureCount.incrementAndGet(); // 失败数量+1
            }, executorService);
            futures.add(future);
        });

        // 等待所有任务完成
        futures.forEach(CompletableFuture::join);
        executorService.shutdown();

        // 打印成功和失败数量
        log.info("批量登录完成：成功 {} 个，失败 {} 个", successCount.get(), failureCount.get());

        JSONObject result = new JSONObject();
        result.putOpt("successCount", successCount.get());
        result.putOpt("failureCount", failureCount.get());
        return result;
    }

    /**
     * 获取账号余额信息
     *
     * @return 结果
     */
    public JSONArray account(String username, String id) {
        List<UserConfig> userConfigs = configService.accounts(username, id);
        if (CollUtil.isEmpty(userConfigs)) {
            throw new BusinessException(SystemError.USER_1007);
        }
        log.info("获取盘口账号redis信息:{}", userConfigs.get(0));
        UserConfig userConfig = userConfigs.get(0);
        String url = userConfig.getBaseUrl() + "member/accounts";
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("accept-language", "zh-CN,zh;q=0.9");
        headers.put("cookie", "defaultSetting=5%2C10%2C20%2C50%2C100%2C200%2C500%2C1000; settingChecked=0; index=; index2=; oid=3a9cad1bfcc69f05fd202bb3ddbc9df05b3bc062; defaultLT=PK10JSC; page=lm; token=" + userConfig.getToken());
        headers.put("priority", "u=1, i");
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
        headers.put("sec-fetch-dest", "empty");
        headers.put("sec-fetch-mode", "cors");
        headers.put("sec-fetch-site", "same-origin");
        headers.put("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
        headers.put("x-requested-with", "XMLHttpRequest");

        // 设置代理和认证
        HttpRequest request = HttpRequest.get(url)
                .addHeaders(headers);
        // 动态设置代理类型
        configureProxy(request, userConfig);

        String result = "";
        try {
            // 发起请求
            result = request.execute().body();
            result = StringUtils.isNotBlank(result) ? result : null;
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof UnknownHostException) {
                log.error("代理请求失败：主机未知。可能是域名解析失败或代理服务器地址有误。异常信息：{}", e.getMessage(), e);
                throw new BusinessException(SystemError.SYS_419, userConfig.getAccount(), "连接超时");
            } else if (cause instanceof ConnectException) {
                log.error("代理请求失败：连接异常。可能是代理服务器未开启或网络不可达。异常信息：{}", e.getMessage(), e);
                throw new BusinessException(SystemError.SYS_419, userConfig.getAccount(), "连接异常");
            } else if (cause instanceof SocketTimeoutException) {
                log.error("代理请求失败：连接超时。可能是网络延迟过高或代理服务器响应缓慢。异常信息：{}", e.getMessage(), e);
                throw new BusinessException(SystemError.SYS_419, userConfig.getAccount(), "连接超时");
            } else if (cause instanceof IOException) {
                log.error("代理请求失败：IO异常。可能是数据传输错误或代理配置不正确。异常信息：{}", e.getMessage(), e);
                throw new BusinessException(SystemError.SYS_419, userConfig.getAccount(), "IO异常");
            } else {
                log.error("代理请求失败：未知异常。请检查代理配置和网络状态。异常信息：{}", e.getMessage(), e);
                throw new BusinessException(SystemError.SYS_419, userConfig.getAccount(), "未知异常");
            }
        }
        log.info("获取盘口账号{},接口返回信息:{}", userConfig.getAccount(), result);
        JSONArray jsonArray = new JSONArray();
        if (StringUtils.isNotBlank(result) && JSONUtil.isTypeJSONArray(result)) {
            jsonArray = JSONUtil.parseArray(result);
            for (int i = 0; i < jsonArray.size(); i++) {
                JSONObject object = jsonArray.getJSONObject(i);
                // 同步一下余额信息
                if (object.getLong("balance") > 0) {
                    userConfig.setBalance(object.getBigDecimal("balance"));
                    userConfig.setBetting(object.getBigDecimal("betting"));
                    userConfig.setAccountType(getAccountType(object.getInt("type")));
                    userConfig.setUpdateTime(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATETIME_PATTERN));
                    businessUserRedissonClient.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, id)).set(JSONUtil.toJsonStr(userConfig));
                }
                // 添加新字段
                object.putOpt("account", userConfig.getAccount());
                // 替换回原数组
                jsonArray.set(i, object);
            }
        }
        return jsonArray;
    }

    /**
     * 获取token
     * @param id
     * @return
     */
    public String token(String username, String id) {
        UserConfig userConfig = JSONUtil.toBean(JSONUtil.parseObj(businessUserRedissonClient.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, id)).get()), UserConfig.class);
        log.info("获取到账户id:{} Redis数据:{}", id, userConfig);
        if (BeanUtil.isNotEmpty(userConfig)) {
            String token = userConfig.getToken();
            log.info("获取到账户id:{} Redis token数据:{}", id, token);
            JSONArray jsonArray = account(username, id);
            log.info("获取到账户id:{} 校验盘口账号有效性:{}", id, jsonArray);
            if (!jsonArray.isEmpty()) {
                // 当前token有效，顺便同步一下余额信息
                    jsonArray.forEach(balance -> {
                        JSONObject balanceJson = JSONUtil.parseObj(balance);
                        if (balanceJson.getLong("balance") > 0) {
                            userConfig.setBalance(balanceJson.getBigDecimal("balance"));
                            userConfig.setBetting(balanceJson.getBigDecimal("betting"));

                            if (0 == balanceJson.getInt("type")) {
                                userConfig.setAccountType("A");
                            } else if (1 == balanceJson.getInt("type")) {
                                userConfig.setAccountType("B");
                            } else if (2 == balanceJson.getInt("type")) {
                                userConfig.setAccountType("C");
                            } else if (3 == balanceJson.getInt("type")) {
                                userConfig.setAccountType("D");
                            }
                            userConfig.setUpdateTime(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATETIME_PATTERN));
                            businessUserRedissonClient.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, id)).set(JSONUtil.toJsonStr(userConfig));
                        }
                    });
            } else {
                // 当前账号登录失效，进行自动登录操作
                log.info("校验盘口账号失效:{}", userConfig.getAccount());
                if (StringUtils.isBlank(userConfig.getToken())) {
                    log.info("redis中账号:{} token不存在才进行登录操作", userConfig.getAccount());
                    if (userConfig.getIsAutoLogin() == 1) {
                        log.info("盘口账号自动登录:{}", userConfig.getAccount());
                        LoginDTO loginDTO = singleLogin(username, userConfig.getId());
                        if (BeanUtil.isNotEmpty(loginDTO)) {
                            token = loginDTO.getToken();
                            log.info("盘口账号自动登录成功:{}", userConfig.getAccount());
                            userConfig.setIsTokenValid(1);
                            userConfig.setToken(token);
                            userConfig.setUpdateTime(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATETIME_PATTERN));
                            businessUserRedissonClient.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, id)).set(JSONUtil.toJsonStr(userConfig));
                            return token;
                        } else {
                            log.info("盘口账号自动登录失败:{}", userConfig.getAccount());
                            userConfig.setIsTokenValid(0);
                            userConfig.setToken(null);
                            userConfig.setUpdateTime(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATETIME_PATTERN));
                            businessUserRedissonClient.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, id)).set(JSONUtil.toJsonStr(userConfig));
                            return null;
                        }
                    } else {
                        userConfig.setIsTokenValid(0);
                        userConfig.setToken(null);
                        userConfig.setUpdateTime(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATETIME_PATTERN));
                        businessUserRedissonClient.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, id)).set(JSONUtil.toJsonStr(userConfig));
                        return null;
                    }
                }
            }
            return token;
        }
        return null;
    }

    /**
     * 获取期数
     *
     * @return 期数
     */
    public String period(String usrename, String id, String lottery) {
        List<UserConfig> userConfigs = configService.accounts(usrename, id);
        if (CollectionUtil.isEmpty(userConfigs)) {
            throw new BusinessException(SystemError.USER_1007);
        }
        String baseUrl = userConfigs.get(0).getBaseUrl();
        String token = userConfigs.get(0).getToken();

        GameType gameType = GameType.getByLottery(lottery);
        String url = baseUrl+"member/period?lottery="+lottery+"&games="+gameType.getGames()+"&_="+System.currentTimeMillis();
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("accept-language", "zh-CN,zh;q=0.9");
        headers.put("cookie", "defaultSetting=5%2C10%2C20%2C50%2C100%2C200%2C500%2C1000; settingChecked=0; index=; index2=; page=lm; oid=3a9cad1bfcc69f05fd202bb3ddbc9df05b3bc062; defaultLT="+lottery+"; random=4671; token=" + token + "; __nxquid=QebLfcL6SZckLHDCiGltWK0vmD+qcA==0013;");
        headers.put("priority", "u=1, i");
        headers.put("sec-ch-ua", "\"Chromium\";v=\"130\", \"Google Chrome\";v=\"130\", \"Not?A_Brand\";v=\"99\"");
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
        headers.put("sec-fetch-dest", "empty");
        headers.put("sec-fetch-mode", "cors");
        headers.put("sec-fetch-site", "same-origin");
        headers.put("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
        headers.put("x-requested-with", "XMLHttpRequest");

        // 发送 HTTP 请求
        String result = null;
        try {
            HttpRequest request = HttpRequest.get(url)
                    .addHeaders(headers).timeout(5000);
            // 引入配置代理
            configureProxy(request, userConfigs.get(0));
            // 执行请求
            result = request.execute().body();
        } catch (Exception e) {
            Throwable cause = e.getCause(); // 获取原始异常原因
            if (cause instanceof UnknownHostException) {
                log.error("代理请求失败：主机未知。可能是域名解析失败或代理地址有误。异常信息：{}", e.getMessage(), e);
                throw new BusinessException(SystemError.SYS_419, userConfigs.get(0).getAccount(), "代理请求失败");
            } else if (cause instanceof ConnectException) {
                log.error("代理请求失败：连接异常。可能是代理服务器未开启或网络不可达。异常信息：{}", e.getMessage(), e);
                throw new BusinessException(SystemError.SYS_419, userConfigs.get(0).getAccount(), "连接异常");
            } else if (cause instanceof SocketTimeoutException) {
                log.error("代理请求失败：连接超时。可能是网络延迟过高或代理服务器响应缓慢。异常信息：{}", e.getMessage(), e);
                throw new BusinessException(SystemError.SYS_419, userConfigs.get(0).getAccount(), "连接超时");
            } else if (cause instanceof IOException) {
                log.error("代理请求失败：IO异常。可能是数据传输错误或代理配置不正确。异常信息：{}", e.getMessage(), e);
                throw new BusinessException(SystemError.SYS_419, userConfigs.get(0).getAccount(), "IO异常");
            } else {
                log.error("代理请求失败：未知异常。异常信息：{}", e.getMessage(), e);
                throw new BusinessException(SystemError.SYS_419, userConfigs.get(0).getAccount(), "未知异常");
            }
        }
        if (result.isBlank()) {
            throw new BusinessException(SystemError.USER_1001);
        }
        if (result.contains("416 Range Not Satisfiable")) {
            throw new BusinessException(SystemError.SYS_419, userConfigs.get(0).getAccount(), "代理请求失败");
        }
        System.out.println(result);
        return result;
    }

    /**
     * 获取赔率
     *
     * @return 结果
     */
    public String odds(String username, String id, String lottery) {
        List<UserConfig> userConfigs = configService.accounts(username, id);
        if (CollUtil.isEmpty(userConfigs)) {
            throw new BusinessException(SystemError.USER_1007);
        }
        String baseUrl = userConfigs.get(0).getBaseUrl();
        String token = userConfigs.get(0).getToken();

        GameType gameType = GameType.getByLottery(lottery);
        String url = baseUrl+"member/odds?lottery=" + lottery + "&games="+gameType.getGames()+"&_=" + System.currentTimeMillis();
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("accept-language", "zh-CN,zh;q=0.9");
        headers.put("cookie", "defaultSetting=5%2C10%2C20%2C50%2C100%2C200%2C500%2C1000; settingChecked=0; index=; index2=; page=lm; defaultLT="+lottery+"; ssid1=e4ac3642c6b2ea8a51d3a12fc4994ba7; token=" + token);
        headers.put("priority", "u=1, i");
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
        headers.put("sec-fetch-dest", "empty");
        headers.put("sec-fetch-mode", "cors");
        headers.put("sec-fetch-site", "same-origin");
        headers.put("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
        headers.put("x-requested-with", "XMLHttpRequest");

        // 设置代理和认证
        HttpRequest request = HttpRequest.get(url)
                .addHeaders(headers).timeout(5000);
        // 动态设置代理类型
        configureProxy(request, userConfigs.get(0));

        String result;
        try {
            // 发起请求
            result = request.execute().body();
            result = result.isBlank() ? null : result;
        } catch (Exception e) {
            Throwable cause = e.getCause(); // 获取原始异常原因
            if (cause instanceof UnknownHostException) {
                log.error("获取赔率 代理请求失败：主机未知。可能是域名解析失败或代理服务器地址有误。异常信息：{}", e.getMessage(), e);
                throw new BusinessException(SystemError.SYS_419, userConfigs.get(0).getAccount(), "连接超时");
            } else if (cause instanceof ConnectException) {
                log.error("获取赔率 代理请求失败：连接异常。可能是代理服务器未开启或网络不可达。异常信息：{}", e.getMessage(), e);
                throw new BusinessException(SystemError.SYS_419, userConfigs.get(0).getAccount(), "连接异常");
            } else if (cause instanceof SocketTimeoutException) {
                log.error("获取赔率 代理请求失败：连接超时。可能是网络延迟过高或代理服务器响应缓慢。异常信息：{}", e.getMessage(), e);
                throw new BusinessException(SystemError.SYS_419, userConfigs.get(0).getAccount(), "连接超时");
            } else if (cause instanceof IOException) {
                log.error("获取赔率 代理请求失败：IO异常。可能是数据传输错误或代理配置不正确。异常信息：{}", e.getMessage(), e);
                throw new BusinessException(SystemError.SYS_419, userConfigs.get(0).getAccount(), "IO异常");
            } else {
                log.error("获取赔率 代理请求失败：未知异常。请检查代理配置和网络状态。异常信息：{}", e.getMessage(), e);
                throw new BusinessException(SystemError.SYS_419, userConfigs.get(0).getAccount(), "未知异常");
            }
        }
        System.out.println(result);
        return result;
    }

    /**
     * 下单api
     *
     * @return 结果
     */
    public JSONObject bet(String username, String id, OrderVO order) {
        List<UserConfig> userConfigs = configService.accounts(username, id);
        if (CollectionUtil.isEmpty(userConfigs)) {
            throw new BusinessException(SystemError.USER_1007);
        }
        String baseUrl = userConfigs.get(0).getBaseUrl();
        String token = userConfigs.get(0).getToken();
        String url = baseUrl+"member/bet";
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("accept-language", "zh-CN,zh;q=0.9");
        headers.put("cookie", "defaultSetting=5%2C10%2C20%2C50%2C100%2C200%2C500%2C1000; settingChecked=0; index=; index2=; oid=3a9cad1bfcc69f05fd202bb3ddbc9df05b3bc062; defaultLT="+order.getLottery()+"; page=lm; token=" + token);
        headers.put("priority", "u=1, i");
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
        headers.put("sec-fetch-dest", "empty");
        headers.put("sec-fetch-mode", "cors");
        headers.put("sec-fetch-site", "same-origin");
        headers.put("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
        headers.put("x-requested-with", "XMLHttpRequest");

        String result = HttpRequest.post(url)
                .body(JSONUtil.toJsonStr(order))
                .addHeaders(headers)
                .execute().body();
        System.out.println(result);
        JSONObject resultJson = new JSONObject();
        if (StringUtils.isNotBlank(result)) {
            resultJson = JSONUtil.parseObj(result);
            int status = resultJson.getInt("status");
            if (status != 0) {
                throw new BusinessException(SystemError.ORDER_1100);
            }
        }
        return resultJson;
    }

    /**
     * 计算延迟时间
     * @param closeTime
     * @param userConfigCloseTime
     * @return
     */
    private long calculateRemainingTime(long closeTime, Integer userConfigCloseTime) {
        if (userConfigCloseTime == null || userConfigCloseTime == 0) {
            return 0;
        }
        long currentTime = System.currentTimeMillis() / 1000;
        return closeTime - userConfigCloseTime - currentTime;
    }

    /**
     * 获取所有开启下注的平台用户
     * @return
     */
    private List<AdminLoginDTO> getAutoBetAdminUsers() {
        // 匹配所有平台用户的 Redis Key
        String pattern = KeyUtil.genKey(RedisConstants.USER_ADMIN_PREFIX, "*");

        // 使用 Redisson 执行扫描所有平台用户操作
        RKeys keys = businessUserRedissonClient.getKeys();
        Iterator<String> iterableKeys = keys.getKeysByPattern(pattern).iterator();

        // 提取出已开启方案的平台用户和方案 使用线程安全的列表
        List<AdminLoginDTO> adminUsers = new ArrayList<>();

        // 创建批量操作实例
        RBatch batch = businessUserRedissonClient.createBatch();

        // 存储 RBucket 实例
        List<RBucket<String>> buckets = new ArrayList<>();

        // 将每个操作添加到批处理队列
        while (iterableKeys.hasNext()) {
            String key = iterableKeys.next();
            RBucket<String> bucket = businessUserRedissonClient.getBucket(key); // 获取 RBucket 对象
            bucket.getAsync(); // 为每个键添加获取操作
            buckets.add(bucket);
        }
        // 执行所有批量操作
        batch.execute();

        // 处理结果
        buckets.forEach(bucket -> {
            String json = bucket.get();  // 获取每个键的值
            if (json != null) {
                AdminLoginDTO admin = JSONUtil.toBean(json, AdminLoginDTO.class);
                if (null != admin.getAutoBet() && admin.getAutoBet() != 0) {
                    adminUsers.add(admin);
                }
            }
        });
        return adminUsers;
    }

    /**
     * 自动下注
     */
    public void autoBet() {
        // 匹配所有平台用户的 Redis Key
        String pattern = KeyUtil.genKey(RedisConstants.USER_ADMIN_PREFIX, "*");

        // 使用 Redisson 执行扫描所有平台用户操作
        RKeys keys = redisson.getKeys();
        Iterator<String> iterableKeys = keys.getKeysByPattern(pattern).iterator();
        List<String> keysList = new ArrayList<>();
        while (iterableKeys.hasNext()) {
            keysList.add(iterableKeys.next());
        }

        // 计数器，用于统计成功/失败数量
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();
        AtomicInteger reverseCount = new AtomicInteger();

        int keysCount = keysList.size();
        int userConfigsPerKey = 15; // 估算每个 key 的 userConfigs 数量
        int totalTasks = keysCount * userConfigsPerKey; // 总任务数

        // 基于任务总量和 CPU 核数动态调整线程池大小
        int cpuCoreCount = Runtime.getRuntime().availableProcessors();
        int threadPoolSize = Math.min(totalTasks, Math.max(cpuCoreCount * 2, 100));
        log.info("自动投注 cpu核数: {}，配置线程池大小: {}", cpuCoreCount, threadPoolSize);
        ExecutorService executorService = PriorityTaskExecutor.createPriorityThreadPool(threadPoolSize);
        // 初始化线程池（动态线程池，避免任务阻塞）
//        ThreadPoolExecutor executorService = new ThreadPoolExecutor(
//                threadPoolSize,
//                threadPoolSize,
//                60L, TimeUnit.SECONDS,
//                new LinkedBlockingQueue<>(),
//                new ThreadPoolExecutor.CallerRunsPolicy()
//        );
        // 配置 scheduledExecutorService 用于延迟执行任务
        // ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(threadPoolSize);

        // 对每个平台用户的操作并行化
        List<Callable<Void>> tasks = new ArrayList<>();
        for (String key : keysList) {

            AtomicInteger successUserCount = new AtomicInteger();
            AtomicInteger failureUserCount = new AtomicInteger();
            AtomicInteger reverseUserCount = new AtomicInteger();

            // 使用 Redisson 获取每个平台用户的数据
            String json = (String) redisson.getBucket(key).get();
            // 解析 JSON 为 AdminLoginDTO 对象
            AdminLoginDTO admin = JSONUtil.toBean(json, AdminLoginDTO.class);
            String username = admin.getUsername();
            tasks.add(() -> {
                if (json != null) {
                    // 获取所有配置计划
                    List<ConfigPlanVO> configs = configService.getAllPlans(username, null);
                    if (CollUtil.isEmpty(configs)) {
                        log.info("当前平台用户:{}不存在方案，直接跳过", username);
                        return null;
                    }
                    // 过滤掉未启用的配置
                    List<ConfigPlanVO> enabledUserConfigs = configs.stream()
                            .filter(config -> config.getEnable() != 0)
                            .toList();

                    // 获取当前平台用户下的盘口账号
                    List<UserConfig> userConfigs = configService.accounts(admin.getUsername(), null);
                    if (CollUtil.isEmpty(userConfigs)) {
                        log.info("当前平台用户:{}不存在盘口账号，直接跳过", username);
                        return null;
                    }
                    // 过滤掉未登录的盘口账号
                    List<UserConfig> tokenVaildConfigs = userConfigs.stream()
                            .filter(userConfig -> 1 == userConfig.getIsTokenValid() && StringUtils.isNotBlank(userConfig.getToken()))
                            .collect(Collectors.toList());
                    if (CollUtil.isEmpty(tokenVaildConfigs)) {
                        log.info("当前平台用户:{}不存在有效盘口账号，直接跳过", username);
                        return null;
                    }
                    // 对每个配置计划列表进行并行化
                    List<Callable<Void>> planTasks = new ArrayList<>();
                    for (ConfigPlanVO plan : enabledUserConfigs) {
                        planTasks.add(() -> {
                            // 对于每个配置计划，你可以根据需求设置优先级
                            int priority = 1; // 最高优先级
                             Runnable task = () -> {
                                 String period = null;
                                 try {
                                     // 获取最新期数
                                     period = period(username, tokenVaildConfigs.get(0).getId(), plan.getLottery());
                                     if (StringUtils.isBlank(period)) {
                                         log.info("平台用户:{},方案:{},游戏:{},获取期数失败", username, plan.getName(), plan.getLottery());
                                         return;
                                     }
                                 } catch (Exception e) {
                                     log.info("平台用户:{},方案:{},游戏:{},获取期数失败", username, plan.getName(), plan.getLottery());
                                     return;
                                 }
                                JSONObject periodJson = JSONUtil.parseObj(period);
                                String drawNumber = periodJson.getStr("drawNumber");

                                // 校验当前期数是否为可下注状态
                                if (1 != periodJson.getInt("status")) {
                                    log.info("平台用户:{},游戏:{},期数:{},当前期数不可下注", username, plan.getLottery(), drawNumber);
                                    return;
                                }

                                // 校验封盘时间
                                long closeTime = periodJson.getLong("closeTime") / 1000;
                                long currentTime = System.currentTimeMillis() / 1000;
                                long beetTime = closeTime - currentTime;
                                // 如果距离封盘时间小于30秒，跳过不下注
                                if (beetTime < 40) {
                                    log.info("平台用户:{},游戏:{},期数:{},剩余封盘时间:{},即将封盘,不进行下注", username, plan.getLottery(), drawNumber, beetTime);
                                    return;
                                };

                                // 获取期数锁
                                RLock lock = redisson.getLock(KeyUtil.genKey(
                                        RedisConstants.USER_BET_PERIOD_PREFIX,
                                        plan.getLottery(),
                                        drawNumber,
                                        username,
                                        String.valueOf(plan.getId())
                                ));
                                log.info("平台用户:{},游戏:{},期数:{},方案:{},正在尝试加锁", username, plan.getLottery(), drawNumber, plan.getName());
                                // 尝试加锁，设置加锁超时时间为10秒
                                boolean isLocked = false; // 5秒内尝试获取锁，持锁60秒
                                try {
                                    isLocked = lock.tryLock(5, 60, TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    log.info("平台用户:{},游戏:{},期数:{},方案:{},尝试获取锁失败", username, plan.getLottery(), drawNumber, plan.getName());
                                }
                                if (!isLocked) {
                                    log.info("平台用户:{},游戏:{},期数:{},方案:{},当前期数正在执行", username, plan.getLottery(), drawNumber, plan.getName());
                                    return;
                                }
                                String odds = null;
                                // 获取赔率
                                try {
                                    odds = odds(username, tokenVaildConfigs.get(0).getId(), plan.getLottery());
                                } catch (Exception e) {
                                    log.error("获取赔率异常 平台用户:{},游戏:{},期数:{},方案:{},释放当前期数锁", username, plan.getLottery(), drawNumber, plan.getName());
                                    // 确保锁释放
                                    if (lock.isHeldByCurrentThread()) {
                                        lock.unlock();
                                    }
                                    return;
                                }
                                if (StringUtils.isBlank(odds)) {
                                    log.error("获取赔率失败 游戏:{},期数:{},平台用户:{},方案:{},释放当前期数锁", plan.getLottery(), drawNumber, username, plan.getName());
                                    // 确保锁释放
                                    // 防止释放锁后，同一期任务再次进来时因为没有锁导致同一个方案重复下单，暂时先只使用tryLock的超时时间自动释放看看效果如何
                                    if (lock.isHeldByCurrentThread()) {
                                        lock.unlock();
                                    }
                                    return;
                                }
                                JSONObject oddsJson = JSONUtil.parseObj(odds);

                                // 获取正投账号数
                                List<UserConfig> positiveAccounts = getRandomAccount(tokenVaildConfigs, plan.getPositiveAccountNum(), 1);
                                // 获取反投账号数
                                List<UserConfig> reverseAccounts = getRandomAccount(tokenVaildConfigs, plan.getReverseAccountNum(), 2);
                                // 把正反投账号集合
                                List<UserConfig> allAccounts = new ArrayList<>();
                                allAccounts.addAll(positiveAccounts);
                                allAccounts.addAll(reverseAccounts);
                                // 记录成功账号
                                List<UserConfig> successAccounts = new ArrayList<>();
                                // 记录失败账号
                                List<UserConfig> failedAccounts = new ArrayList<>();
                                // 获取正反投的位置 key
                                Map<Integer, Map<String, List<String>>> oddsMap = new HashMap<>();
                                // 获取双面-大小正反投的位置 key
                                List<String> twoSidedDxOdds = new ArrayList<>();
                                // 获取双面-单双正反投的位置 key
                                List<String> twoSidedDsOdds = new ArrayList<>();
                                // 获取双面-龙虎正反投的位置 key
                                List<String> twoSidedLhOdds = new ArrayList<>();
                                // 获取单号
                                List<Integer> positions = plan.getPositions();
                                // 获取大小
                                List<Integer> twoSidedDxPositions = plan.getTwoSidedDxPositions();
                                // 获取单双
                                List<Integer> twoSidedDsPositions = plan.getTwoSidedDsPositions();
                                // 获取龙虎
                                List<Integer> twoSidedLhPositions = plan.getTwoSidedLhPositions();
                                for (int pos : positions) {
                                    String jsonkey = "B" + pos;
                                    List<String> matchedKeys = oddsJson.keySet().stream()
                                            .filter(matchedKey -> matchedKey.contains(jsonkey + "_"))
                                            .collect(Collectors.toList());
                                    Map<String, List<String>> oddKeys = getOdds(matchedKeys, plan.getPositiveNum());
                                    oddsMap.put(pos, oddKeys);
                                }
                                if (null != twoSidedDxPositions) {
                                    for (int pos : twoSidedDxPositions) {
                                        String jsonkey = "DX" + pos;
                                        List<String> matchedKeys = oddsJson.keySet().stream()
                                                .filter(matchedKey -> matchedKey.startsWith(jsonkey + "_"))
                                                .collect(Collectors.toList());
                                        twoSidedDxOdds.addAll(matchedKeys);
                                    }
                                }
                                if (null != twoSidedDsPositions) {
                                    for (int pos : twoSidedDsPositions) {
                                        String jsonkey = "DS" + pos;
                                        List<String> matchedKeys = oddsJson.keySet().stream()
                                                .filter(matchedKey -> matchedKey.startsWith(jsonkey + "_"))
                                                .collect(Collectors.toList());
                                        twoSidedDsOdds.addAll(matchedKeys);
                                    }
                                }
                                if (null != twoSidedLhPositions) {
                                    for (int pos : twoSidedLhPositions) {
                                        String jsonkey = "LH" + pos;
                                        List<String> matchedKeys = oddsJson.keySet().stream()
                                                .filter(matchedKey -> matchedKey.startsWith(jsonkey + "_"))
                                                .collect(Collectors.toList());
                                        twoSidedLhOdds.addAll(matchedKeys);
                                    }
                                }
                                Map<String, Map<String, List<String>>> twoSidedMap = null;
                                JSONObject twoSidedAmountJson = null;
                                if (CollUtil.isNotEmpty(twoSidedDxOdds) || CollUtil.isNotEmpty(twoSidedDsOdds) || CollUtil.isNotEmpty(twoSidedLhOdds)) {
                                    // 分配大小单双龙虎对应正反投
                                    twoSidedMap = assignPositions(positiveAccounts, reverseAccounts, twoSidedDxOdds, twoSidedDsOdds, twoSidedLhOdds);
                                     // 获取双面金额分配
                                    twoSidedAmountJson = twoSidedDistributeAmount(twoSidedMap, positiveAccounts, reverseAccounts, plan);
                                }
                                // 使用 CountDownLatch 等待所有延迟任务完成
                                // CountDownLatch latch = new CountDownLatch(allAccounts.size()); // 初始化为需要执行的任务数
                                JSONObject amountJson = distributeAmount(oddsMap, positiveAccounts, reverseAccounts, plan);
                                for (UserConfig userConfig : allAccounts) {
                                    try {

                                        String isBetRedisKey = KeyUtil.genKey(
                                                RedisConstants.USER_BET_PERIOD_RES_PREFIX,
                                                StringUtils.isNotBlank(plan.getLottery()) ? plan.getLottery() : "*",
                                                ToDayRangeUtil.getToDayRange(),
                                                drawNumber,
                                                "success",
                                                username,
                                                userConfig.getAccount(),
                                                String.valueOf(plan.getId())
                                        );

                                        // 判断是否已下注
                                        boolean exists = redisson.getBucket(isBetRedisKey).isExists();
                                        if (exists) {
                                            log.info("平台用户:{},游戏:{},期数:{},账号:{},方案:{},已下过注,不再重复下注", username, plan.getLottery(), drawNumber, tokenVaildConfigs.get(0).getAccount(), plan.getName());
                                            return;
                                        } // 已下注，跳过

                                        String betTypeStr = userConfig.getBetType() == 1 ? "positive" : "reverse";

//                                        if (amountJson.isEmpty()) {
//                                            log.warn("分配金额失败,平台用户:{},跳过账户: {}", username, userConfig.getAccount());
//                                            failedAccounts.add(userConfig);
//                                            continue;
//                                        }

                                        // 计算延迟时间
                                        long delay = calculateRemainingTime(closeTime, userConfig.getCloseTime());
                                        delay = Math.max(delay, 0); // 确保延迟时间非负数

                                        log.info("平台用户:{},游戏:{},期数:{},封盘时间:{},账号:{},方案:{}, 距离下注时间还剩:{}秒,进行延迟下注", username, plan.getLottery(), drawNumber, closeTime, userConfig.getAccount(), plan.getName(), delay);
                                        // 延迟下注任务
                                        // scheduledExecutorService.schedule(() -> {
                                        // 创建下注请求参数
                                        OrderVO order = createOrder(plan, oddsJson, drawNumber, positions, betTypeStr, amountJson, twoSidedAmountJson, userConfig);
                                        // 提交下注
                                        String result = submitOrder(username, order, userConfig, plan, drawNumber, successAccounts, failedAccounts, successCount, failureCount);
                                        // 结果解析
                                        handleOrderResult(username, result, userConfig, drawNumber, plan, successAccounts, failedAccounts, successCount, failureCount, successUserCount, failureUserCount);
                                        // latch.countDown();
                                        // }, delay, TimeUnit.SECONDS);
                                    } catch (Exception e) {
                                        log.error("处理账户时发生异常,平台用户:{},账号: {}", username, userConfig.getAccount(), e);
                                        failedAccounts.add(userConfig);
                                        failureCount.incrementAndGet();
                                    }
                                }

                                // 等待所有延迟任务完成
                                try {
                                    // latch.await();
                                    // 反补处理
                                    processReverseBet(admin, failedAccounts, successAccounts, plan, drawNumber, reverseCount, reverseUserCount);
                                } catch (Exception e) {
                                    Thread.currentThread().interrupt();
                                    log.error("等待延迟任务完成时出现中断异常 平台用户:{}, 游戏:{},期数:{},方案:{}", username, plan.getLottery(), drawNumber, plan.getName(), e);
                                } finally {
                                    log.info("平台用户:{}, 游戏:{},期数:{},方案:{},释放当前期数锁", username, plan.getLottery(), drawNumber, plan.getName());
                                    // 确保锁释放
                                    // todo 防止释放锁后，同一期任务再次进来时因为没有锁导致同一个方案重复下单，暂时先只使用tryLock的超时时间自动释放看看效果如何
                                    //if (lock.isHeldByCurrentThread()) {
                                    //    lock.unlock();
                                    //}
                                }
                             };
                             executorService.submit(new PriorityTaskExecutor.PriorityTask(task, priority));
                            return null;
                        });
                    }
                    // 提交 planList 中的任务并行处理
                    try {
                        executorService.invokeAll(planTasks);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.error("执行任务时出现中断异常 平台用户:{}", username, e);
                    }
                }
                return null;
            });
            log.info("批量下注完成,平台用户:{}, 成功次数: {}, 失败次数: {}, 反补次数: {}", username, successUserCount.get(), failureUserCount.get(), reverseUserCount.get());
        }
        // 提交平台用户的任务到线程池
        try {
            // 提交所有任务并等待其完成
            executorService.invokeAll(tasks);
            // 等待所有任务在60秒内完成
//            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
//                log.warn("超时，正在强制关闭线程池...");
//                executorService.shutdownNow(); // 超时后强制关闭
//            }
        } catch (InterruptedException e) {
            // 处理线程中断，确保干净地停止所有任务
            Thread.currentThread().interrupt();
            executorService.shutdownNow(); // 中断时强制关闭线程池
            log.error("任务执行被中断，关闭线程池时发生异常", e);
        } catch (Exception e) {
            // 处理其他异常
            executorService.shutdownNow();
            log.error("关闭线程池时发生异常", e);
        } finally {
            // 确保线程池正常关闭
            log.info("关闭线程池");
            if (!executorService.isTerminated()) {
                executorService.shutdown(); // 确保优雅地关闭
            }
        }

        // 打印批量登录的成功和失败次数
        log.info("当前任务批量下注完成，成功次数: {}, 失败次数: {}, 反补次数: {}", successCount.get(), failureCount.get(), reverseCount.get());
    }

    public void autoBetCompletableFuture() {
        TimeInterval timerTotal = DateUtil.timer();
        TimeInterval timerPre = DateUtil.timer();
        log.info("前置操作，获取真正有效的平台用户和方案...");
        TimeInterval timerRedis = DateUtil.timer();
        // 提取出已开启下注的平台用户和方案 使用线程安全的列表
        // 第一阶段：过滤出有效平台用户
        List<AdminLoginDTO> adminUsers = getAutoBetAdminUsers();
        log.info("过滤出开启下注的平台用户花费:{}毫秒", timerRedis.interval());
        // 如果没有有效用户，直接退出
        if (CollUtil.isEmpty(adminUsers)) {
            log.info("所有平台用户都未开启自动下注，直接退出当前任务");
            return;
        }

        // 计数器，用于统计成功/失败数量
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();
        AtomicInteger reverseCount = new AtomicInteger();
        // 计数器，用于统计总的有效方案数量
        AtomicInteger totalTasks = new AtomicInteger(0);
        // 使用一个列表保存需要删除的用户
        List<AdminLoginDTO> toRemoveUsers = new ArrayList<>();
        Map<String, List<ConfigPlanVO>> enabledUserConfigsMap = new HashMap<>();
        Map<String, List<UserConfig>> tokenVaildConfigsMap = new HashMap<>();

        // 第二阶段：并发处理每个有效用户的方案和登录账号
        TimeInterval timerThear = DateUtil.timer();
        int cpuCoreCount = Runtime.getRuntime().availableProcessors();
        ExecutorService executorAdminUserService = Executors.newFixedThreadPool(Math.min(adminUsers.size(), cpuCoreCount * 2));
//        ExecutorService executorAdminUserService = Executors.newCachedThreadPool();

        // 使用 ForkJoinPool 进行更高效的任务执行
//        ForkJoinPool forkJoinPool = new ForkJoinPool(Math.min(adminUsers.size(), cpuCoreCount * 2));
        List<CompletableFuture<Void>> adminFutures = new ArrayList<>();

        // 先请求所有用户的数据
        adminUsers.forEach(admin -> {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                // 批量请求启用方案和有效配置
                CompletableFuture<List<ConfigPlanVO>> enabledUserConfigsFuture = getEnabledConfigPlansAsync(admin.getUsername());
                CompletableFuture<List<UserConfig>> tokenVaildConfigsFuture = getTokenValidConfigsAsync(admin.getUsername());

                CompletableFuture<Void> combinedFuture = CompletableFuture.allOf(
                        enabledUserConfigsFuture,
                        tokenVaildConfigsFuture
                ).thenRun(() -> {
                    try {
                        // 获取启用的配置方案和有效配置
                        List<ConfigPlanVO> enabledUserConfigs = enabledUserConfigsFuture.join();
                        List<UserConfig> tokenVaildConfigs = tokenVaildConfigsFuture.join();

                        // 校验结果并更新
                        if (CollUtil.isEmpty(enabledUserConfigs) || CollUtil.isEmpty(tokenVaildConfigs)) {
                            toRemoveUsers.add(admin);
                            return;
                        }
                        // 校验正反投账号
                        List<UserConfig> validUserConfigs = validUserBetTypeAsync(admin, tokenVaildConfigs).join();
                        if (CollUtil.isEmpty(validUserConfigs)) {
                            toRemoveUsers.add(admin);
                            return;
                        }

                        // 将有效方案添加到 map
                        enabledUserConfigsMap.put(admin.getUsername(), enabledUserConfigs);
                        tokenVaildConfigsMap.put(admin.getUsername(), tokenVaildConfigs);

                        // 更新总有效方案数量
                        totalTasks.addAndGet(enabledUserConfigs.size());
                    } catch (Exception e) {
                        log.error("处理用户 {} 时出错", admin.getUsername(), e);
                    }
                });
                // 使用 ForkJoinPool 提高并发性能
                combinedFuture.join();
            }, executorAdminUserService);

            adminFutures.add(future);
        });

        // 等待所有任务完成
        CompletableFuture<Void> allOf = CompletableFuture.allOf(adminFutures.toArray(new CompletableFuture[0]));
        allOf.join(); // 等待所有任务完成
        shutdownExecutor(executorAdminUserService);
        log.info("过滤出所有开启方案和已登录账号花费:{}", timerThear.interval());
        // 过滤掉需要删除的用户
        adminUsers.removeAll(toRemoveUsers);
        // 如果没有有效用户，直接退出
        if (CollUtil.isEmpty(adminUsers)) {
            log.info("所有平台用户都未开启方案或者不存在有效登录账号，直接退出当前任务");
            return;
        }
        log.info("前置操作结束,当前任务有:{}位平台用户开启方案并且存在有效登录账号,总有效方案数量:{},花费:{}毫秒", adminUsers.size(), totalTasks.get(), timerPre.interval());

        int threadPoolUserSize = Math.min(adminUsers.size(), cpuCoreCount * 2);
        int threadPoolPlanSize = Math.min(totalTasks.get(), Math.max(cpuCoreCount * 2, 100));
        log.info("自动投注 cpu核数: {},平台用户线程池大小: {},用户方案线程池大小: {}", cpuCoreCount, threadPoolUserSize, threadPoolPlanSize);
        // 初始化线程池（动态线程池，避免任务阻塞）
        ExecutorService executorUserService = Executors.newFixedThreadPool(threadPoolUserSize);
        ExecutorService executorPlanService = Executors.newFixedThreadPool(threadPoolPlanSize);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        // 对每个平台用户的操作并行化
        for (AdminLoginDTO admin : adminUsers) {
            String username = admin.getUsername();
            CompletableFuture<Void> future = CompletableFuture.supplyAsync(() -> {
                TimeInterval timerFutures = DateUtil.timer();
                AtomicInteger successUserCount = new AtomicInteger();
                AtomicInteger failureUserCount = new AtomicInteger();
                AtomicInteger reverseUserCount = new AtomicInteger();
                // 获取该用户的启用方案
                List<ConfigPlanVO> enabledUserConfigs = enabledUserConfigsMap.get(admin.getUsername());
                if (CollUtil.isEmpty(enabledUserConfigs)) {
                    log.info("当前平台用户:{}不存在启用方案，直接跳过", username);
                    return null;
                }
                // 过滤掉未登录的盘口账号
                List<UserConfig> tokenVaildConfigs = tokenVaildConfigsMap.get(admin.getUsername());
                if (CollUtil.isEmpty(tokenVaildConfigs)) {
                    log.info("当前平台用户:{}不存在有效盘口账号，直接跳过", username);
                    return null;
                }
                // 对每个配置计划进行异步处理
                List<CompletableFuture<Void>> planFutures = new ArrayList<>();
                for (ConfigPlanVO plan : enabledUserConfigs) {
                    planFutures.add(CompletableFuture.runAsync(() -> {
                        TimeInterval timer = DateUtil.timer();
                        // 对于每个配置计划，你可以根据需求设置优先级
                        String period = null;
                        try {
                            // 获取最新期数
                            period = period(username, tokenVaildConfigs.get(0).getId(), plan.getLottery());
                            if (StringUtils.isBlank(period)) {
                                log.info("平台用户:{},方案:{},游戏:{},获取期数失败", username, plan.getName(), plan.getLottery());
                                return;
                            }
                        } catch (Exception e) {
                            log.info("平台用户:{},方案:{},游戏:{},获取期数失败", username, plan.getName(), plan.getLottery());
                            return;
                        }
                        JSONObject periodJson = JSONUtil.parseObj(period);
                        String drawNumber = periodJson.getStr("drawNumber");

                        // 校验当前期数是否为可下注状态
                        if (1 != periodJson.getInt("status")) {
                            log.info("平台用户:{},游戏:{},期数:{},当前期数不可下注", username, plan.getLottery(), drawNumber);
                            return;
                        }

                        // 校验封盘时间
                        long closeTime = periodJson.getLong("closeTime") / 1000;
                        long currentTime = System.currentTimeMillis() / 1000;
                        long beetTime = closeTime - currentTime;
                        // 如果距离封盘时间小于30秒，跳过不下注
                        if (beetTime < 40) {
                            log.info("平台用户:{},游戏:{},期数:{},剩余封盘时间:{},即将封盘,不进行下注", username, plan.getLottery(), drawNumber, beetTime);
                            return;
                        };

                        // 获取期数锁
                        RLock lock = businessUserRedissonClient.getLock(KeyUtil.genKey(
                                RedisConstants.USER_BET_PERIOD_PREFIX,
                                plan.getLottery(),
                                drawNumber,
                                username,
                                String.valueOf(plan.getId())
                        ));
                        log.info("平台用户:{},游戏:{},期数:{},方案:{},正在尝试获取锁", username, plan.getLottery(), drawNumber, plan.getName());
                        // 尝试加锁，设置加锁超时时间为10秒
                        boolean isLocked = false; // 5秒内尝试获取锁，持锁60秒
                        try {
                            isLocked = lock.tryLock(5, 60, TimeUnit.SECONDS);
                            log.info("平台用户:{},游戏:{},期数:{},方案:{},获取锁成功,锁状态:{}", username, plan.getLottery(), drawNumber, plan.getName(), isLocked);
                        } catch (InterruptedException e) {
                            log.info("平台用户:{},游戏:{},期数:{},方案:{},获取锁失败", username, plan.getLottery(), drawNumber, plan.getName());
                        }
                        if (!isLocked) {
                            log.info("平台用户:{},游戏:{},期数:{},方案:{},当前期数正在执行", username, plan.getLottery(), drawNumber, plan.getName());
                            return;
                        }
                        String odds = null;
                        // 获取赔率
                        try {
                            odds = getOddsFromCache(username, drawNumber, tokenVaildConfigs.get(0).getId(), plan.getLottery(), plan.getName());
                        } catch (Exception e) {
                            log.error("获取赔率异常 平台用户:{},游戏:{},期数:{},方案:{},释放当前期数锁", username, plan.getLottery(), drawNumber, plan.getName());
                            // 确保锁释放
                            if (lock.isHeldByCurrentThread()) {
                                lock.unlock();
                            }
                            return;
                        }
                        if (StringUtils.isBlank(odds)) {
                            log.error("获取赔率失败 游戏:{},期数:{},平台用户:{},方案:{},释放当前期数锁", plan.getLottery(), drawNumber, username, plan.getName());
                            // 确保锁释放
                            if (lock.isHeldByCurrentThread()) {
                                lock.unlock();
                            }
                            return;
                        }
                        JSONObject oddsJson = JSONUtil.parseObj(odds);

                        // 获取正投账号数
                        List<UserConfig> positiveAccounts = getRandomAccount(tokenVaildConfigs, plan.getPositiveAccountNum(), 1);
                        // 获取反投账号数
                        List<UserConfig> reverseAccounts = getRandomAccount(tokenVaildConfigs, plan.getReverseAccountNum(), 2);
                        // 把正反投账号集合
                        List<UserConfig> allAccounts = new ArrayList<>();
                        allAccounts.addAll(positiveAccounts);
                        allAccounts.addAll(reverseAccounts);
                        // 记录成功账号
                        List<UserConfig> successAccounts = new ArrayList<>();
                        // 记录失败账号
                        List<UserConfig> failedAccounts = new ArrayList<>();
                        // 获取正反投的位置 key
                        Map<Integer, Map<String, List<String>>> oddsMap = new HashMap<>();
                        // 获取单号
                        List<Integer> positions = plan.getPositions();
                        // 获取大小
                        List<Integer> twoSidedDxPositions = plan.getTwoSidedDxPositions();
                        // 获取单双
                        List<Integer> twoSidedDsPositions = plan.getTwoSidedDsPositions();
                        // 获取龙虎
                        List<Integer> twoSidedLhPositions = plan.getTwoSidedLhPositions();

                        int positiveNum = plan.getPositiveNum();
                        // 随机方案 -- 随机获取正投数量和反投数量 随机选择单面还是双面进行下注
                        if (null != plan.getPlanType() && plan.getPlanType() == 2) {
                            // 完全随机方案
                            positiveNum = RandomUtil.randomInt(1, 9);
                            if (RandomUtil.randomBoolean()) {
                                // 获取单号
                                positions = RandomUtil.randomEleList(plan.getPositions(), RandomUtil.randomInt(1,10));
                                // 获取大小
                                twoSidedDxPositions = null;
                                // 获取单双
                                twoSidedDsPositions = null;
                                // 获取龙虎
                                twoSidedLhPositions = null;
                            } else {
                                // 获取单号
                                positions = null;
                                // 随机获取大小的位置
                                twoSidedDxPositions = RandomUtil.randomEleList(plan.getTwoSidedDxPositions(), RandomUtil.randomInt(1,10));
                                // 随机获取单双的位置
                                twoSidedDsPositions = RandomUtil.randomEleList(plan.getTwoSidedDsPositions(), RandomUtil.randomInt(1,10));
                                // 随机获取龙虎的位置
                                twoSidedLhPositions = RandomUtil.randomEleList(plan.getTwoSidedLhPositions(), RandomUtil.randomInt(1,5));
                            }
                        } else if (null != plan.getPlanType() && plan.getPlanType() == 3) {
                            // 指定位置数量随机方案
                            positiveNum = RandomUtil.randomInt(1, 9);
                            // 获取单号
                            positions = RandomUtil.randomEleList(plan.getPositions(), plan.getPositionsNum());
                            // 随机获取大小的位置
                            twoSidedDxPositions = RandomUtil.randomEleList(plan.getTwoSidedDxPositions(), plan.getTwoSidedDxNum());
                            // 随机获取单双的位置
                            twoSidedDsPositions = RandomUtil.randomEleList(plan.getTwoSidedDsPositions(), plan.getTwoSidedDsNum());
                            // 随机获取龙虎的位置
                            twoSidedLhPositions = RandomUtil.randomEleList(plan.getTwoSidedLhPositions(), plan.getTwoSidedLhNum());
                        }

                        if (null != positions) {
                            for (int pos : positions) {
                                String jsonkey = "B" + pos;
                                List<String> matchedKeys = oddsJson.keySet().stream()
                                        .filter(matchedKey -> matchedKey.contains(jsonkey + "_"))
                                        .collect(Collectors.toList());
                                Map<String, List<String>> oddKeys = getOdds(matchedKeys, positiveNum);
                                oddsMap.put(pos, oddKeys);
                            }
                        }

                        // 使用 CountDownLatch 等待所有延迟任务完成
                        // CountDownLatch latch = new CountDownLatch(allAccounts.size()); // 初始化为需要执行的任务数
                        JSONObject twoSidedAmountJson = twoSidedAmountJson(oddsJson, twoSidedDxPositions, twoSidedDsPositions, twoSidedLhPositions, positiveAccounts, reverseAccounts, plan);
                        JSONObject amountJson = distributeAmount(oddsMap, positiveAccounts, reverseAccounts, plan);
                        for (UserConfig userConfig : allAccounts) {
                            try {
                                String isBetRedisKey = KeyUtil.genKey(
                                        RedisConstants.USER_BET_PERIOD_RES_PREFIX,
                                        plan.getLottery(),
                                        ToDayRangeUtil.getToDayRange(),
                                        drawNumber,
                                        "success",
                                        username,
                                        userConfig.getAccount(),
                                        String.valueOf(plan.getId())
                                );
                                // 判断是否已下注
                                boolean exists = redisson.getBucket(isBetRedisKey).isExists();
                                if (exists) {
                                    log.info("平台用户:{},游戏:{},期数:{},账号:{},方案:{},已下过注,不再重复下注", username, plan.getLottery(), drawNumber, tokenVaildConfigs.get(0).getAccount(), plan.getName());
                                    return;
                                } // 已下注，跳过

                                String betTypeStr = userConfig.getBetType() == 1 ? "positive" : "reverse";

                                // 计算延迟时间
                                // long delay = calculateRemainingTime(closeTime, userConfig.getCloseTime());
                                // delay = Math.max(delay, 0); // 确保延迟时间非负数

                                log.info("平台用户:{},游戏:{},期数:{},封盘时间:{},账号:{},方案:{}, 及时延迟下注", username, plan.getLottery(), drawNumber, closeTime, userConfig.getAccount(), plan.getName());
                                // 延迟下注任务
                                // scheduledExecutorService.schedule(() -> {
                                // 创建下注请求参数
                                OrderVO order = createOrder(plan, oddsJson, drawNumber, positions, betTypeStr, amountJson, twoSidedAmountJson, userConfig);
                                // 提交下注
                                String result = submitOrder(username, order, userConfig, plan, drawNumber, successAccounts, failedAccounts, successCount, failureCount);
                                // 结果解析
                                handleOrderResult(username, result, userConfig, drawNumber, plan, successAccounts, failedAccounts, successCount, failureCount, successUserCount, failureUserCount);
                                // latch.countDown();
                                // }, delay, TimeUnit.SECONDS);
                            } catch (Exception e) {
                                log.error("处理账户时发生异常,平台用户:{},账号: {}", username, userConfig.getAccount(), e);
                                failedAccounts.add(userConfig);
                                failureCount.incrementAndGet();
                            }
                        }

                        // 等待所有延迟任务完成
                        try {
                            // latch.await();
                            if (CollUtil.isNotEmpty(failedAccounts)) {
                                // 补单处理
                                processReverseBet(admin, failedAccounts, successAccounts, plan, drawNumber, reverseCount, reverseUserCount);
                            }
                        } catch (Exception e) {
                            Thread.currentThread().interrupt();
                            log.error("等待延迟任务完成时出现中断异常 平台用户:{}, 游戏:{},期数:{},方案:{}", username, plan.getLottery(), drawNumber, plan.getName(), e);
                        } finally {
                            log.info("平台用户:{}, 游戏:{},方案:{},期数:{},花费:{}毫秒", username, plan.getLottery(), plan.getName(), drawNumber, timer.interval());
                            // 确保锁释放
                            // todo 防止释放锁后，同一期任务再次进来时因为没有锁导致同一个方案重复下单，暂时先只使用tryLock的超时时间自动释放看看效果如何
                            //if (lock.isHeldByCurrentThread()) {
                            //    lock.unlock();
                            //}
                        }
                    }, executorPlanService));
                }
                // 等待所有配置计划任务完成
                CompletableFuture<Void> allPlans = CompletableFuture.allOf(planFutures.toArray(new CompletableFuture[0]));
                try {
                    allPlans.get();
                } catch (Exception e) {
                    log.error("执行配置计划任务时发生异常 平台用户:{}", username, e);
                }

                log.info("批量下注完成,平台用户:{}, 成功次数: {}, 失败次数: {}, 反补次数: {}, 花费:{}毫秒", username, successUserCount.get(), failureUserCount.get(), reverseUserCount.get(), timerFutures.interval());
                return null;
            }, executorUserService);
            futures.add(future);
        }
        // 等待所有平台用户的任务完成
        CompletableFuture<Void> allTasks = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        try {
            allTasks.get(60, TimeUnit.SECONDS); // 等待所有任务完成，设置 60 秒超时
        } catch (Exception e) {
            log.error("执行任务时出现异常", e);
        }

        // 执行完所有任务后关闭线程池
        shutdownExecutor(executorUserService);
        shutdownExecutor(executorPlanService);
        // 打印批量登录的成功和失败次数
        log.info("当前任务批量下注完成，成功次数: {}, 失败次数: {}, 反补次数: {}, 总花费:{}毫秒", successCount.get(), failureCount.get(), reverseCount.get(), timerTotal.interval());
    }

    /**
     * 优化线程池关闭逻辑
     * @param executorService
     */
    private void shutdownExecutor(ExecutorService executorService) {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                log.error("任务超时，强制关闭线程池");
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 获取已启用的方案
     * @param username
     * @return
     */
    private List<ConfigPlanVO> getEnabledConfigPlans(String username) {
        List<ConfigPlanVO> configs = configService.getAllPlansRBatch(username, null);
        if (CollUtil.isEmpty(configs)) {
            return Collections.emptyList();
        }
        return configs.stream()
                .filter(config -> config.getEnable() != 0)
                .collect(Collectors.toList());
    }

    /**
     * 获取token有效的账号
     * @param username
     * @return
     */
    private List<UserConfig> getTokenValidConfigs(String username) {
        List<UserConfig> userConfigs = configService.accountsRBatch(username, null);

        if (CollUtil.isEmpty(userConfigs)) {
            return Collections.emptyList();
        }

        // 过滤掉未登录的盘口账号，直接返回有效的账户列表
        return userConfigs.stream()
                .filter(userConfig -> 1 == userConfig.getIsTokenValid() && StringUtils.isNotBlank(userConfig.getToken()))
                .collect(Collectors.toList());
    }

    /**
     * 校验list中正反投账号是否都存在
     * @param userConfigs
     * @return
     */
    private List<UserConfig> validUserBetType(AdminLoginDTO admin, List<UserConfig> userConfigs) {
        // 校验正投和反投账户数量是否都大于1
        long positiveCount = userConfigs.stream().filter(userConfig -> userConfig.getBetType() == 1).count();
        long reverseCount = userConfigs.stream().filter(userConfig -> userConfig.getBetType() == 2).count();
        if (positiveCount < 1 || reverseCount < 1) {
            // 如果正投或反投账户数量不满足条件，返回空列表
            // 关闭自动下注
            admin.setAutoBet(0);
            businessUserRedissonClient.getBucket(KeyUtil.genKey(
                    RedisConstants.USER_ADMIN_PREFIX,
                    admin.getUsername()
            )).set(JSONUtil.toJsonStr(admin));
            return Collections.emptyList();
        }
        return userConfigs;
    }

    private CompletableFuture<List<ConfigPlanVO>> getEnabledConfigPlansAsync(String username) {
        return CompletableFuture.supplyAsync(() -> {
            List<ConfigPlanVO> configs = configService.getAllPlansRBatch(username, null);
            if (CollUtil.isEmpty(configs)) {
                return Collections.emptyList();
            }
            return configs.stream()
                    .filter(config -> config.getEnable() != 0)
                    .collect(Collectors.toList());
        });
    }

    private CompletableFuture<List<UserConfig>> getTokenValidConfigsAsync(String username) {
        return CompletableFuture.supplyAsync(() -> {
            List<UserConfig> userConfigs = configService.accountsRBatch(username, null);
            if (CollUtil.isEmpty(userConfigs)) {
                return Collections.emptyList();
            }
            return userConfigs.stream()
                    .filter(userConfig -> 1 == userConfig.getIsTokenValid() && StringUtils.isNotBlank(userConfig.getToken()))
                    .collect(Collectors.toList());
        });
    }

    private CompletableFuture<List<UserConfig>> validUserBetTypeAsync(AdminLoginDTO admin, List<UserConfig> userConfigs) {
        return CompletableFuture.supplyAsync(() -> {
            long positiveCount = userConfigs.stream().filter(userConfig -> userConfig.getBetType() == 1).count();
            long reverseCount = userConfigs.stream().filter(userConfig -> userConfig.getBetType() == 2).count();
            if (positiveCount < 1 || reverseCount < 1) {
                admin.setAutoBet(0);
                businessUserRedissonClient.getBucket(KeyUtil.genKey(
                        RedisConstants.USER_ADMIN_PREFIX,
                        admin.getUsername()
                )).set(JSONUtil.toJsonStr(admin));
                return Collections.emptyList();
            }
            return userConfigs;
        });
    }

    /**
     * 缓存赔率： 可以先尝试从本地缓存中获取赔率数据，只有在缓存没有时再访问 Redis 和外部 API。
     * @param drawNumber
     * @param lottery
     * @return
     */
    private String getOddsFromCache(String username, String drawNumber, String accountId, String lottery, String planName) {
        String key = KeyUtil.genKey(RedisConstants.USER_BET_ODDS_PREFIX, DateUtil.format(DateUtil.date(), "yyyyMMdd"), lottery, drawNumber);
        // 使用 computeIfAbsent 确保只有一个线程获取和设置缓存
        return localOddsCache.computeIfAbsent(key, k -> {
            // 从 盘口 API获取
            log.info("平台用户:{},游戏:{},期数:{},账号:{},方案:{},期数赔率不存在缓存数据,api获取", username, lottery, drawNumber, accountId, planName);
            return odds(username, accountId, lottery); // 返回获取到的赔率
        });
    }

    /**
     * 获取双面分配金额
     * @param oddsJson
     * @param twoSidedDxPositions
     * @param twoSidedDsPositions
     * @param twoSidedLhPositions
     * @param positiveAccounts
     * @param reverseAccounts
     * @param plan
     * @return
     */
    private JSONObject twoSidedAmountJson(JSONObject oddsJson, List<Integer> twoSidedDxPositions, List<Integer> twoSidedDsPositions, List<Integer> twoSidedLhPositions, List<UserConfig> positiveAccounts, List<UserConfig> reverseAccounts, ConfigPlanVO plan) {
        JSONObject twoSidedAmountJson = null;
        if (CollUtil.isNotEmpty(twoSidedDxPositions) || CollUtil.isNotEmpty(twoSidedDsPositions) || CollUtil.isNotEmpty(twoSidedLhPositions)) {
            // 获取双面-大小正反投的位置 key
            List<String> twoSidedDxOdds = new ArrayList<>();
            // 获取双面-单双正反投的位置 key
            List<String> twoSidedDsOdds = new ArrayList<>();
            // 获取双面-龙虎正反投的位置 key
            List<String> twoSidedLhOdds = new ArrayList<>();

            if (null != twoSidedDxPositions) {
                for (int pos : twoSidedDxPositions) {
                    String jsonkey = "DX" + pos;
                    List<String> matchedKeys = oddsJson.keySet().stream()
                            .filter(matchedKey -> matchedKey.startsWith(jsonkey + "_"))
                            .toList();
                    twoSidedDxOdds.addAll(matchedKeys);
                }
            }
            if (null != twoSidedDsPositions) {
                for (int pos : twoSidedDsPositions) {
                    String jsonkey = "DS" + pos;
                    List<String> matchedKeys = oddsJson.keySet().stream()
                            .filter(matchedKey -> matchedKey.startsWith(jsonkey + "_"))
                            .toList();
                    twoSidedDsOdds.addAll(matchedKeys);
                }
            }
            if (null != twoSidedLhPositions) {
                for (int pos : twoSidedLhPositions) {
                    String jsonkey = "LH" + pos;
                    List<String> matchedKeys = oddsJson.keySet().stream()
                            .filter(matchedKey -> matchedKey.startsWith(jsonkey + "_"))
                            .toList();
                    twoSidedLhOdds.addAll(matchedKeys);
                }
            }

            // 分配大小单双龙虎对应正反投
            Map<String, Map<String, List<String>>> twoSidedMap = assignPositions(positiveAccounts, reverseAccounts, twoSidedDxOdds, twoSidedDsOdds, twoSidedLhOdds);
            // 获取双面金额分配
            twoSidedAmountJson = twoSidedDistributeAmount(twoSidedMap, positiveAccounts, reverseAccounts, plan);
        }
        return twoSidedAmountJson;
    }

    /**
     * 封装订单
     * @param plan
     * @param oddsJson
     * @param drawNumber
     * @param positions
     * @param oddsType
     * @return
     */
    private OrderVO createOrder(ConfigPlanVO plan, JSONObject oddsJson, String drawNumber, List<Integer> positions, String oddsType, JSONObject amountJson, JSONObject twoSidedAmountJson, UserConfig userConfig) {
        OrderVO order = new OrderVO();
        List<Bet> bets = new ArrayList<>();
        order.setLottery(plan.getLottery());
        order.setDrawNumber(drawNumber);
        order.setFastBets(false);
        order.setIgnore(false);

        if (null != positions && !positions.isEmpty()) {
            for (int pos : positions) {
                JSONObject posJsonKey = amountJson.getJSONObject(String.valueOf(pos));
                if (StringUtils.equals("positive", oddsType)) {
                    JSONObject posJson = posJsonKey.getJSONObject("positive");
                    posJson.forEach((k, v) -> {
                        List<String> oddsKey = oddsKyeSplitter(k);
                        JSONObject amountValue = JSONUtil.parseObj(v);
                        Bet bet = new Bet();
                        bet.setGame(oddsKey.get(0));
                        bet.setAmount(amountValue.getLong(userConfig.getAccount()));
                        bet.setContents(oddsKey.get(1));
                        bet.setOdds(oddsJson.getDouble(k));
                        bets.add(bet);
                    });
                } else {
                    JSONObject posJson = posJsonKey.getJSONObject("reverse");
                    posJson.forEach((k, v) -> {
                        List<String> oddsKey = oddsKyeSplitter(k);
                        JSONObject amountValue = JSONUtil.parseObj(v);
                        Bet bet = new Bet();
                        bet.setGame(oddsKey.get(0));
                        bet.setAmount(amountValue.getLong(userConfig.getAccount()));
                        bet.setContents(oddsKey.get(1));
                        bet.setOdds(oddsJson.getDouble(k));
                        bets.add(bet);
                    });
                }
            }
        }
        if (null != twoSidedAmountJson && !twoSidedAmountJson.isEmpty()) {
            // 存在双面配置金额
            if (StringUtils.equals("positive", oddsType)) {
                JSONObject posJson = twoSidedAmountJson.getJSONObject("positive");
                posJson.forEach((k, v) -> {
                    List<String> oddsKey = oddsKyeSplitter(k);
                    JSONObject amountValue = JSONUtil.parseObj(v);
                    Bet bet = new Bet();
                    bet.setGame(oddsKey.get(0));
                    bet.setAmount(amountValue.getLong(userConfig.getAccount()));
                    bet.setContents(oddsKey.get(1));
                    bet.setOdds(oddsJson.getDouble(k));
                    bets.add(bet);
                });
            } else {
                JSONObject posJson = twoSidedAmountJson.getJSONObject("reverse");
                posJson.forEach((k, v) -> {
                    List<String> oddsKey = oddsKyeSplitter(k);
                    JSONObject amountValue = JSONUtil.parseObj(v);
                    Bet bet = new Bet();
                    bet.setGame(oddsKey.get(0));
                    bet.setAmount(amountValue.getLong(userConfig.getAccount()));
                    bet.setContents(oddsKey.get(1));
                    bet.setOdds(oddsJson.getDouble(k));
                    bets.add(bet);
                });
            }
        }
        order.setBets(bets);
        return order;
    }

    /**
     * 封装代理
     * @param request
     * @param userConfig
     */
    private void configureProxy(HttpRequest request, UserConfig userConfig) {
        if (BeanUtil.isNotEmpty(userConfig) && userConfig.getProxyType() != null) {
            // 统一设置代理认证
            if (StringUtils.isNotBlank(userConfig.getProxyUsername()) && StringUtils.isNotBlank(userConfig.getProxyPassword())) {
                Authenticator.setDefault(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(
                                userConfig.getProxyUsername(),
                                userConfig.getProxyPassword().toCharArray()
                        );
                    }
                });
            }

            // 设置代理主机和端口
            if (userConfig.getProxyType() == 1) {
                setHttpProxy(userConfig);
            } else if (userConfig.getProxyType() == 2) {
                setSocksProxy(userConfig);
            }

            // 禁用默认的 HTTP 认证隧道设置（针对 SOCKS 代理）
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");

            // 设置代理类型
            Proxy proxy = new Proxy(
                    userConfig.getProxyType() == 1 ? Proxy.Type.HTTP : Proxy.Type.SOCKS,
                    new InetSocketAddress(userConfig.getProxyHost(), userConfig.getProxyPort())
            );
            request.setProxy(proxy);
        }
    }

    private void setHttpProxy(UserConfig userConfig) {
        System.setProperty("http.proxyHost", userConfig.getProxyHost());
        System.setProperty("http.proxyPort", String.valueOf(userConfig.getProxyPort()));
        System.setProperty("https.proxyHost", userConfig.getProxyHost());
        System.setProperty("https.proxyPort", String.valueOf(userConfig.getProxyPort()));
    }

    private void setSocksProxy(UserConfig userConfig) {
        System.setProperty("socksProxyHost", userConfig.getProxyHost());
        System.setProperty("socksProxyPort", String.valueOf(userConfig.getProxyPort()));
    }


    /**
     * 封装发起提交下注
     * @param order
     * @param userConfig
     * @param plan
     * @param drawNumber
     * @return
     */
    private String submitOrder(String username, OrderVO order, UserConfig userConfig, ConfigPlanVO plan, String drawNumber, List<UserConfig> successAccounts, List<UserConfig> failedAccounts, AtomicInteger successCount,AtomicInteger failureCount) {
        log.info("发起下单, 平台用户:{}, 账号:{}, 期数:{}, 方案:{}, 账号token:{}, 订单:{}", username, userConfig.getAccount(), drawNumber, plan.getName(), userConfig.getToken(), order);
        TimeInterval timeBet = DateUtil.timer();
        boolean success = true;
        String url = userConfig.getBaseUrl() + "member/bet";
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("cookie", "defaultLT=" + plan.getLottery() + "; token=" + userConfig.getToken());
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
        headers.put("sec-fetch-dest", "empty");
        headers.put("sec-fetch-mode", "cors");
        headers.put("sec-fetch-site", "same-origin");
        headers.put("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
        headers.put("x-requested-with", "XMLHttpRequest");

        HttpRequest request = HttpRequest.post(url).addHeaders(headers);
        configureProxy(request, userConfig);

        // 记录订单请求
        redisson.getBucket(KeyUtil.genKey(
                RedisConstants.USER_BET_PERIOD_REQ_PREFIX,
                plan.getLottery(),
                ToDayRangeUtil.getToDayRange(),
                drawNumber,
                username,
                userConfig.getAccount(),
                String.valueOf(plan.getId())
        )).set(JSONUtil.toJsonStr(order), Duration.ofHours(24));
        String result = null;
        try {
            result = request.body(JSONUtil.toJsonStr(order)).execute().body();
            log.info("下单结束, 平台用户:{}, 账号:{}, 期数:{}, 方案:{}, 返回信息{}", username, userConfig.getAccount(), drawNumber, plan.getName(), result);
            if (StringUtils.isBlank(result)) {
                log.error("下单失败, 平台用户:{}, 账号:{}, 期数:{}, 方案:{}, 返回信息{}", username, userConfig.getAccount(), drawNumber, plan.getName(), result);
                JSONObject failed = new JSONObject();
                failed.putOpt("status", 1);
                failed.putOpt("lottery", plan.getLottery());
                failed.putOpt("drawNumber", drawNumber);
                failed.putOpt("createTime", LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATETIME_PATTERN));
                failed.putOpt("account", userConfig.getAccount());
                failed.putOpt("isRepair", 0);// 是否为补单 0:否 1:是
                failed.putOpt("message", "账号异常,请检查登录状态以及账号是否正常");
                success = false;
                return JSONUtil.toJsonStr(failed);
            } else if (!JSONUtil.isTypeJSONObject(result)) {
                log.error("下单失败, 平台用户:{}, 账号:{}, 期数:{}, 方案:{}, 返回信息{}", username, userConfig.getAccount(), drawNumber, plan.getName(), result);
                JSONObject failed = new JSONObject();
                failed.putOpt("status", 1);
                failed.putOpt("lottery", plan.getLottery());
                failed.putOpt("drawNumber", drawNumber);
                failed.putOpt("createTime", LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATETIME_PATTERN));
                failed.putOpt("account", userConfig.getAccount());
                failed.putOpt("isRepair", 0);// 是否为补单 0:否 1:是
                failed.putOpt("message", "代理异常");
                success = false;
                return JSONUtil.toJsonStr(failed);
            }
        } catch (Exception e) {
            log.error("下单代理请求失败, 平台用户:{}, 账号:{}, 期数:{}, 方案:{}, 异常信息:", username, userConfig.getAccount(), drawNumber, plan.getName(), e);
            JSONObject failed = new JSONObject();
            failed.putOpt("status", 1);
            failed.putOpt("lottery", plan.getLottery());
            failed.putOpt("drawNumber", drawNumber);
            failed.putOpt("createTime", LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATETIME_PATTERN));
            failed.putOpt("account", userConfig.getAccount());
            failed.putOpt("isRepair", 0);// 是否为补单 0:否 1:是
            failed.putOpt("message", "代理异常");
            success = false;

            Throwable cause = e.getCause();
            if (cause instanceof UnknownHostException) {
            } else if (cause instanceof ConnectException) {
            } else if (cause instanceof SocketTimeoutException) {
                failed.putOpt("message", "代理链接超时");
            } else if (cause instanceof IOException) {
            } else {
                // 未知异常，记录日志并抛出通用错误码
            }

            return JSONUtil.toJsonStr(failed);
        } finally {
            log.info("下单结束, 平台用户:{}, 账号:{}, 期数:{}, 方案:{}, 请求成功:{}, 下单花费:{}毫秒", username, userConfig.getAccount(), drawNumber, plan.getName(), success, timeBet.intervalMs());
        }
        return StringUtils.isEmpty(result) ? null : result;
    }

    /**
     * 封装处理下注结果
     *
     * @param result
     * @param userConfig
     * @param drawNumber
     * @param plan
     * @param successAccounts
     * @param failedAccounts
     */
    private void handleOrderResult(String username, String result, UserConfig userConfig, String drawNumber, ConfigPlanVO plan, List<UserConfig> successAccounts, List<UserConfig> failedAccounts, AtomicInteger successCount, AtomicInteger failureCount, AtomicInteger successUserCount, AtomicInteger failureUserCount) {
        boolean isSuccess = true;
        JSONObject resultJson = JSONUtil.parseObj(result);
        if (!resultJson.isEmpty()) {
            int status = resultJson.getInt("status");
            if (status == 0) {
                // 成功逻辑
                redisson.getBucket(KeyUtil.genKey(
                        RedisConstants.USER_BET_PERIOD_RES_PREFIX,
                        plan.getLottery(),
                        ToDayRangeUtil.getToDayRange(),
                        drawNumber,
                        "success",
                        username,
                        userConfig.getAccount(),
                        String.valueOf(plan.getId())
                )).set(JSONUtil.toJsonStr(result), Duration.ofHours(24));
                successAccounts.add(userConfig);
                successCount.incrementAndGet();
                successUserCount.incrementAndGet();
                log.info("下单成功, 平台用户:{}, 账号:{}, 期数:{}", username, userConfig.getAccount(), drawNumber);
            } else if (status == 2) {
                // 失败逻辑
                isSuccess = false;
                resultJson.putOpt("message", "投注超时");
            } else {
                // 失败逻辑
                isSuccess = false;
            }
        } else {
            isSuccess = false;
        }

        if (!isSuccess) {
            log.error("下单失败, 平台用户:{}, 账号:{}, 期数:{}, 返回信息{}", username, userConfig.getAccount(), drawNumber, resultJson);
            resultJson.putOpt("confirm", 0); // 待确认
            resultJson.putOpt("lottery", plan.getLottery());
            resultJson.putOpt("drawNumber", drawNumber);
            resultJson.putOpt("createTime", LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATETIME_PATTERN));
            resultJson.putOpt("account", userConfig.getAccount());
            resultJson.putOpt("isRepair", 0);// 是否为补单 0:否 1:是
            // 记录失败信息
            businessUserRedissonClient.getBucket(KeyUtil.genKey(
                    RedisConstants.USER_BET_PERIOD_RES_PREFIX,
                    plan.getLottery(),
                    ToDayRangeUtil.getToDayRange(),
                    drawNumber,
                    "failed",
                    username,
                    userConfig.getAccount(),
                    String.valueOf(plan.getId())
            )).set(resultJson, Duration.ofHours(24));
            failedAccounts.add(userConfig);
            failureCount.incrementAndGet();
            failureUserCount.incrementAndGet();
        }
    }

    /**
     * 处理下单失败的反补操作
     * @param failedAccounts
     * @param successAccounts
     * @param plan
     * @param drawNumber
     */
    private void processReverseBet(AdminLoginDTO admin, List<UserConfig> failedAccounts, List<UserConfig> successAccounts, ConfigPlanVO plan, String drawNumber, AtomicInteger reverseCount, AtomicInteger reverseUserCount) {
        if (CollectionUtil.isNotEmpty(failedAccounts)) {
            // 当前方案存在账号下注失败
            failedAccounts.forEach(account -> {
                // 获取到下注失败的请求参数
                String failedReq = (String) redisson.getBucket(KeyUtil.genKey(
                        RedisConstants.USER_BET_PERIOD_REQ_PREFIX,
                        plan.getLottery(),
                        ToDayRangeUtil.getToDayRange(),
                        drawNumber,
                        admin.getUsername(),
                        account.getAccount(),
                        String.valueOf(plan.getId())
                )).get();
                // 通过当前方案账号进行反补
                for (UserConfig sucAccount : successAccounts) {
                    boolean reverseSuccess = handleReverseBet(admin.getUsername(), account, sucAccount, failedReq, plan, drawNumber, reverseCount, reverseUserCount);
                    if (reverseSuccess) {
                        break; // 反补成功退出
                    }
                }
            });
            // 关闭自动下注
            admin.setAutoBet(0);
            businessUserRedissonClient.getBucket(KeyUtil.genKey(
                            RedisConstants.USER_ADMIN_PREFIX,
                    admin.getUsername()
                    )).set(JSONUtil.toJsonStr(admin));
            // 保险起见-再把当前平台用户下的账号全部下线
            // batchLoginOut(username);
            // 把当前平台用户下的所有方案停用
            // List<ConfigPlanVO> configPlans = configService.getAllPlans(admin.getUsername(), null);
            // configPlans.forEach(configPlan -> {
            //     configPlan.setEnable(0);
            //     configService.plan(admin.getUsername(), configPlan);
            // });
        }
    }

    private boolean handleReverseBet(String username, UserConfig failedAccount, UserConfig sucAccount, String failedReq, ConfigPlanVO plan, String drawNumber, AtomicInteger reverseCount, AtomicInteger reverseUserCount) {
        log.info("发起补单, 平台用户:{}, 账号:{}, 账号token:{}, 期数:{}, 方案:{}, 补单参数:{}", username, failedAccount.getAccount(), failedAccount.getToken(), drawNumber, plan.getName(), failedReq);
        // 提交反补订单
        String url = sucAccount.getBaseUrl() + "member/bet";
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("cookie", "defaultLT=" + plan.getLottery() + "; token=" + sucAccount.getToken());
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
        headers.put("sec-fetch-dest", "empty");
        headers.put("sec-fetch-mode", "cors");
        headers.put("sec-fetch-site", "same-origin");
        headers.put("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
        headers.put("x-requested-with", "XMLHttpRequest");

        HttpRequest request = HttpRequest.post(url).addHeaders(headers).timeout(5000);
        configureProxy(request, sucAccount);

        boolean resultBol = false;
        // 记录反补的请求参数
        redisson.getBucket(KeyUtil.genKey(
                RedisConstants.USER_BET_PERIOD_REQ_PREFIX,
                plan.getLottery(),
                ToDayRangeUtil.getToDayRange(),
                drawNumber,
                username,
                sucAccount.getAccount(),
                String.valueOf(plan.getId()),
                "reverse"
        )).set(JSONUtil.toJsonStr(failedReq), Duration.ofHours(24));

        String result = null;
        try {
            result = request.body(JSONUtil.toJsonStr(failedReq)).execute().body();
            log.info("补单结束, 平台用户:{}, 账号:{}, 期数:{}, 返回信息{}", username, failedAccount.getAccount(), drawNumber, result);
            if (!JSONUtil.isTypeJSONObject(result)) {
                log.error("补单失败, 平台用户:{}, 账号:{}, 期数:{}, 返回信息{}", username, failedAccount.getAccount(), drawNumber, result);
                JSONObject failed = new JSONObject();
                failed.putOpt("status", 1);
                failed.putOpt("lottery", plan.getLottery());
                failed.putOpt("drawNumber", drawNumber);
                failed.putOpt("createTime", LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATETIME_PATTERN));
                failed.putOpt("account", failedAccount.getAccount());
                failed.putOpt("message", "代理异常");
                failed.putOpt("isRepair", 1);// 是否为补单 0:否 1:是
                // 反补失败返回参数
                businessUserRedissonClient.getBucket(KeyUtil.genKey(
                        RedisConstants.USER_BET_PERIOD_RES_PREFIX,
                        plan.getLottery(),
                        ToDayRangeUtil.getToDayRange(),
                        drawNumber,
                        "failed",
                        username,
                        sucAccount.getAccount(),
                        String.valueOf(plan.getId())
                )).set(failed, Duration.ofHours(24));
                return resultBol;
            }
        } catch (Exception e) {

            JSONObject failed = new JSONObject();
            failed.putOpt("status", 1);
            failed.putOpt("message", "代理异常");
            failed.putOpt("lottery", plan.getLottery());
            failed.putOpt("drawNumber", drawNumber);
            failed.putOpt("createTime", LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATETIME_PATTERN));
            failed.putOpt("isRepair", 1);// 是否为补单 0:否 1:是

            Throwable cause = e.getCause(); // 获取原始异常原因
            if (cause instanceof UnknownHostException) {
                log.error("反补请求代理异常, 失败账号:{}, 对冲账号:{}, 期数:{}, 异常:{}", failedAccount.getAccount(), sucAccount.getAccount(), drawNumber, e.getMessage());
            } else if (cause instanceof ConnectException) {
                log.error("反补请求代理异常, 失败账号:{}, 对冲账号:{}, 期数:{}, 异常:{}", failedAccount.getAccount(), sucAccount.getAccount(), drawNumber, e.getMessage());
            } else if (cause instanceof SocketTimeoutException) {
                log.error("反补请求代理异常, 失败账号:{}, 对冲账号:{}, 期数:{}, 异常:{}", failedAccount.getAccount(), sucAccount.getAccount(), drawNumber, e.getMessage());
            } else if (cause instanceof IOException) {
                failed.putOpt("message", "代理链接超时");
                log.error("反补请求代理异常, 失败账号:{}, 对冲账号:{}, 期数:{}, 异常:{}", failedAccount.getAccount(), sucAccount.getAccount(), drawNumber, e.getMessage());
            } else {
                // 未知异常，记录日志并抛出通用错误码
                log.error("反补请求代理异常, 失败账号:{}, 对冲账号:{}, 期数:{}, 异常:{}", failedAccount.getAccount(), sucAccount.getAccount(), drawNumber, e.getMessage());
            }
            result = JSONUtil.toJsonStr(failed);
        }
        result = StringUtils.isEmpty(result) ? null : result;
        JSONObject resultJson = JSONUtil.parseObj(result);
        if (!resultJson.isEmpty()) {
            int status = resultJson.getInt("status");
            if (status == 0) {
                log.info("补单成功, 平台用户:{}, 失败账号:{}, 对冲账号:{}, 期数:{}", username, failedAccount.getAccount(), sucAccount.getAccount(), drawNumber);
                // 反补成功返回参数
                redisson.getBucket(KeyUtil.genKey(
                        RedisConstants.USER_BET_PERIOD_RES_PREFIX,
                        plan.getLottery(),
                        ToDayRangeUtil.getToDayRange(),
                        drawNumber,
                        "reverse",
                        username,
                        sucAccount.getAccount(),
                        String.valueOf(plan.getId())
                )).set(JSONUtil.toJsonStr(result), Duration.ofHours(24));

                // 把下单失败的日志加上补单是否成功的信息
                String failedKey = KeyUtil.genKey(
                        RedisConstants.USER_BET_PERIOD_RES_PREFIX,
                        plan.getLottery(),
                        ToDayRangeUtil.getToDayRange(),
                        drawNumber,
                        "failed",
                        username,
                        failedAccount.getAccount(),
                        String.valueOf(plan.getId())
                );
                JSONObject failedJson = JSONUtil.parseObj(businessUserRedissonClient.getBucket(failedKey).get());
                if (null != failedJson && !failedJson.isEmpty()) {
                    failedJson.putOpt("repair", 1);
                    failedJson.putOpt("repairAccount", sucAccount.getAccount());
                    businessUserRedissonClient.getBucket(failedKey).set(failedJson, Duration.ofHours(24));
                }

                reverseCount.incrementAndGet();
                reverseUserCount.incrementAndGet();
                return true; // 反补成功
            } else if (status == 2) {
                // 失败逻辑
                resultJson.putOpt("message", "补单投注超时");
            }
        }
        if (!resultBol) {
            log.error("补单失败, 平台用户:{}, 失败账号:{}, 对冲账号:{}, 期数:{}, 返回信息{}", username, failedAccount.getAccount(), sucAccount.getAccount(), drawNumber, resultJson);
            resultJson.putOpt("confirm", 0); // 待确认
            resultJson.putOpt("lottery", plan.getLottery());
            resultJson.putOpt("drawNumber", drawNumber);
            resultJson.putOpt("createTime", LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATETIME_PATTERN));
            resultJson.putOpt("account", sucAccount.getAccount());
            resultJson.putOpt("isRepair", 1);// 是否为补单 0:否 1:是
            // 反补失败返回参数
            businessUserRedissonClient.getBucket(KeyUtil.genKey(
                    RedisConstants.USER_BET_PERIOD_RES_PREFIX,
                    plan.getLottery(),
                    ToDayRangeUtil.getToDayRange(),
                    drawNumber,
                    "failed",
                    username,
                    sucAccount.getAccount(),
                    String.valueOf(plan.getId())
            )).set(resultJson, Duration.ofHours(24));

            // 把下单失败的日志加上补单是否成功的信息
            String failedKey = KeyUtil.genKey(
                    RedisConstants.USER_BET_PERIOD_RES_PREFIX,
                    plan.getLottery(),
                    ToDayRangeUtil.getToDayRange(),
                    drawNumber,
                    "failed",
                    username,
                    failedAccount.getAccount(),
                    String.valueOf(plan.getId())
            );
            JSONObject failedJson = JSONUtil.parseObj(businessUserRedissonClient.getBucket(failedKey).get());
            if (null != failedJson && !failedJson.isEmpty()) {
                failedJson.putOpt("repair", 0);
                failedJson.putOpt("repairAccount", (StringUtils.isBlank(failedJson.getStr("repairAccount")) ? "" : failedJson.getStr("repairAccount") + ",") + sucAccount.getAccount());
                businessUserRedissonClient.getBucket(failedKey).set(failedJson, Duration.ofHours(24));
            }

        }
        return resultBol;
    }

    /**
     * 根据odds切割key
     * @param input
     * @return
     */
    public static List<String> oddsKyeSplitter(String input) {
        List<String> parts = new ArrayList<>();
        String[] splitPart = input.split("_");
        parts.add(splitPart[0]);// _前的部分
        parts.add(splitPart[1]);// _后的部分
        System.out.println("Before _: " + parts.get(0) + ", After _: " + parts.get(1));
        return parts;
    }

    /**
     * 获取正反投的数字
     * @param betNum
     * @return
     */
    public static Map<String, List<Integer>> generateBetContents(int betNum) {
        Map<String, List<Integer>> contents = new HashMap<>();
        // 用来存放正投和反投的数字
        Set<Integer> positiveContentsSet = new HashSet<>(); // 使用Set来避免重复
        List<Integer> reverseContents = new ArrayList<>();

        // 创建随机数生成器
        Random random = new Random();

        // 生成 betNum 个 1 到 10 之间的随机数，存入Set来避免重复
        while (positiveContentsSet.size() < betNum) {
            int randomNum = random.nextInt(10) + 1;  // 生成1到10之间的随机数
            positiveContentsSet.add(randomNum);  // 添加到Set中
        }

        // 将Set转换为List以便返回
        List<Integer> positiveContents = new ArrayList<>(positiveContentsSet);

        // 计算剩余的数字，用于反投
        for (int i = 1; i <= 10; i++) {
            if (!positiveContentsSet.contains(i)) {
                reverseContents.add(i);  // 如果正投列表中没有此数字，则加入反投列表
            }
        }

        // 将结果添加到Map中
        contents.put("positive", positiveContents);
        contents.put("reverse", reverseContents);

        // 输出结果
        System.out.println("正投数字: " + positiveContents);
        System.out.println("反投数字: " + reverseContents);

        return contents;
    }

    public static void main(String[] args) {

        Map<Integer, Map<String, List<String>>> oddsMap1 = new HashMap<>();
        Map<String, List<String>> map1 = new HashMap<>();
        List<String> pos1 = Arrays.asList("B1_1", "B1_2");
        List<String> rev1 = Arrays.asList("B1_3", "B1_4", "B1_5", "B1_6", "B1_7", "B1_8", "B1_9", "B1_10");
        map1.put("positive", pos1);
        map1.put("reverse", rev1);
        oddsMap1.put(1, map1);
        oddsMap1.put(2, map1);
        List<UserConfig> positiveAccounts1 = new ArrayList<>();
        positiveAccounts1.add(new UserConfig("account1", 2));
        positiveAccounts1.add(new UserConfig("account2", 2));
        positiveAccounts1.add(new UserConfig("account3", 2));
        List<UserConfig> reverseAccounts1 = new ArrayList<>();
        reverseAccounts1.add(new UserConfig("account4", 2));
        reverseAccounts1.add(new UserConfig("account5", 2));
        reverseAccounts1.add(new UserConfig("account6", 2));
        reverseAccounts1.add(new UserConfig("account7", 2));
        ConfigPlanVO plan1 = new ConfigPlanVO();
        plan1.setPositiveNum(2);
        plan1.setPositiveAmount(10);
        plan1.setReverseAmount(10);
        JSONObject resultJson1 = distributeAmount(oddsMap1, positiveAccounts1, reverseAccounts1, plan1);


        // 投注数
        int betNum = 8;
        // 投注金额
        long amount = 5;


        List<Integer> pos = new ArrayList<>();
        pos.add(1);
        pos.add(3);
        pos.add(4);

        JSONObject json = new JSONObject();
        json.putOpt("B1_01", 9.979);
        json.putOpt("B1_02", 9.979);
        json.putOpt("B1_03", 9.979);
        json.putOpt("B1_04", 9.979);
        json.putOpt("B2_01", 9.979);
        json.putOpt("B2_02", 9.979);
        json.putOpt("B2_03", 9.979);
        json.putOpt("B2_04", 9.979);

        int positive = 2; // 随机获取正样本数
        int reverse = 1;  // 随机获取反样本数

        pos.forEach(po -> {
            // 筛选出与当前 po 匹配的键值对
            List<String> matchedKeys = json.keySet().stream()
                    .filter(key -> key.contains("B" + po))
                    .collect(Collectors.toList());

            // 随机抽取正样本
            List<String> positiveKeys = getRandomElements(matchedKeys, positive);

            // 剩余键
            List<String> remainingKeys = new ArrayList<>(matchedKeys);
            remainingKeys.removeAll(positiveKeys);

            // 随机抽取反样本
            List<String> reverseKeys = getRandomElements(remainingKeys, reverse);

            // 打印结果
            System.out.println("Matched keys for B" + po + ": " + matchedKeys);
            System.out.println("Positive keys: " + positiveKeys);
            System.out.println("Reverse keys: " + reverseKeys);
        });

        List<UserConfig> userConfigs = new ArrayList<>();

        userConfigs.add(new UserConfig("1", 1));
        userConfigs.add(new UserConfig("2", 1));
        userConfigs.add(new UserConfig("3", 1));
        userConfigs.add(new UserConfig("4", 1));
        userConfigs.add(new UserConfig("5", 1));
        userConfigs.add(new UserConfig("6", 2));
        userConfigs.add(new UserConfig("7", 2));
        userConfigs.add(new UserConfig("8", 2));
        userConfigs.add(new UserConfig("9", 2));
        userConfigs.add(new UserConfig("10", 2));

        // 获取正投账号数
        List<UserConfig> positiveAccounts = getRandomAccount(userConfigs, 2, 1);
        // 获取反投账号数
        List<UserConfig> reverseAccounts = getRandomAccount(userConfigs, 3, 2);


        // 示例代码，用于测试
        List<String> items = new ArrayList<>();
        // 假设添加一些示例数据
        items.add("Item1");
        items.add("Item2");
        items.add("Item3");
        items.add("Item4");
        items.add("Item5");

        // 输出正反投结果
        Map<String, List<String>> odds = getOdds(items, betNum);
        // 切割赔率key
        oddsKyeSplitter("B1_01");
    }

    /**
     * 随机抽取指定数量的元素
     */
    private static List<String> getRandomElements(List<String> list, int betNum) {
        log.info("betNum:{} list.size:{}",betNum,list.size());
        if (list.size() < betNum) {
            System.out.println("Warning: Requested count (" + betNum + ") exceeds available items (" + list.size() + "). Returning all items.");
        }
        Random random = new Random();
        List<String> result = new ArrayList<>();
        List<String> temp = new ArrayList<>(list);
        for (int i = 0; i < betNum && !temp.isEmpty(); i++) {
            int randomIndex = random.nextInt(temp.size());
            result.add(temp.remove(randomIndex));
        }
        log.info("指定赔率json key:{}",result);
        return result;
    }

    /**
     * 分配正反投位置， 根据betNum随机获取list中的数据赋值给positive，剩余数据则赋予reverse，随机获取的positive不能重复
     * @param list
     * @param betNum
     * @return
     */
    private static Map<String, List<String>> getOdds(List<String> list, int betNum) {
        Map<String, List<String>> odds = new HashMap<>();
        log.info("betNum:{} list.size:{}", betNum, list.size());

        // 如果请求的数量大于列表的大小，直接返回所有元素
        if (list.size() < betNum) {
            System.out.println("Warning: Requested count (" + betNum + ") exceeds available items (" + list.size() + "). Returning all items.");
            odds.put("positive", new ArrayList<>(list));
            odds.put("reverse", new ArrayList<>());
            return odds;
        }

        Random random = new Random();
        List<String> temp = new ArrayList<>(list); // 创建一个临时副本来进行随机选择
        List<String> positive = new ArrayList<>();
        List<String> reverse = new ArrayList<>();

        // 随机选取betNum个元素作为positive
        for (int i = 0; i < betNum; i++) {
            int randomIndex = random.nextInt(temp.size()); // 从剩余的元素中随机选择
            positive.add(temp.remove(randomIndex)); // 移除已选中的元素，避免重复
        }

        // 剩下的元素作为reverse
        reverse.addAll(temp);

        // 将正反投结果存入Map
        odds.put("positive", positive);
        odds.put("reverse", reverse);

        log.info("正投赔率key:{}", positive);
        log.info("反投赔率key:{}", reverse);
        return odds;
    }

    public Map<String, Map<String, List<String>>> assignPositions(List<UserConfig> positiveAccounts, List<UserConfig> reverseAccounts,
                                List<String> twoSidedDxPositions, List<String> twoSidedDsPositions,
                                List<String> twoSidedLhPositions) {
        Random random = new Random();
        Map<String, Map<String, List<String>>> selections = new HashMap<>();
        // 存储正投和反投结果
        Map<String, List<String>> positiveSelections = new HashMap<>();
        Map<String, List<String>> reverseSelections = new HashMap<>();

        // 记录已分配过的位置，避免重复分配
        Set<String> allocatedPositions = new HashSet<>();

        // 处理所有投项
        processPositions(twoSidedDxPositions, positiveAccounts, reverseAccounts, "_D", "_X", positiveSelections, reverseSelections, allocatedPositions, random);
        processPositions(twoSidedDsPositions, positiveAccounts, reverseAccounts, "_D", "_S", positiveSelections, reverseSelections, allocatedPositions, random);
        processPositions(twoSidedLhPositions, positiveAccounts, reverseAccounts, "_L", "_H", positiveSelections, reverseSelections, allocatedPositions, random);

        selections.put("positive", positiveSelections);
        selections.put("reverse", reverseSelections);
        return selections;
    }

    private void processPositions(List<String> positions, List<UserConfig> positiveAccounts, List<UserConfig> reverseAccounts,
                                  String suffixPositiveKey, String suffixReverseKey,
                                  Map<String, List<String>> positiveSelections, Map<String, List<String>> reverseSelections,
                                  Set<String> allocatedPositions, Random random) {

        // 按照基础位置（不含后缀）对投项进行分组
        Map<String, List<String>> groupedPositions = new HashMap<>();
        for (String pos : positions) {
            String basePosition = pos.substring(0, pos.lastIndexOf('_')); // 提取基础位置
            groupedPositions.computeIfAbsent(basePosition, k -> new ArrayList<>()).add(pos);
        }

        // 对每个基础位置的投项进行处理
        for (Map.Entry<String, List<String>> entry : groupedPositions.entrySet()) {
            String basePosition = entry.getKey();
            List<String> positionList = entry.getValue();

            // 确保每个位置只分配一次
            if (allocatedPositions.contains(basePosition)) {
                continue; // 如果该位置已分配，跳过
            }

            // 随机决定正投和反投的后缀
            boolean nextBoolean = random.nextBoolean();
            String selectedPositiveKey = nextBoolean ? suffixPositiveKey : suffixReverseKey;
            String selectedReverseKey = nextBoolean ? suffixReverseKey : suffixPositiveKey;

            // 存储正投和反投的账户列表
            List<String> positiveSelection = new ArrayList<>();
            List<String> reverseSelection = new ArrayList<>();

            // 遍历该位置的所有投项并进行分配
            for (String pos : positionList) {
                // 为正投选择账户
                positiveAccounts.forEach(account -> {
                    if (pos.endsWith(selectedPositiveKey)) {
                        positiveSelection.add(account.getAccount());
                    }
                });

                // 为反投选择账户
                reverseAccounts.forEach(account -> {
                    if (pos.endsWith(selectedReverseKey)) {
                        reverseSelection.add(account.getAccount());
                    }
                });
            }

            // 如果正投选择不为空，则记录该选择
            if (!positiveSelection.isEmpty()) {
                positiveSelections.put(basePosition + selectedPositiveKey, positiveSelection);
            }

            // 如果反投选择不为空，则记录该选择
            if (!reverseSelection.isEmpty()) {
                reverseSelections.put(basePosition + selectedReverseKey, reverseSelection);
            }

            // 标记该基础位置已分配
            allocatedPositions.add(basePosition);
        }
    }

    /**
     * 随机抽取指定数量的投注账户
     */
    private static List<UserConfig> getRandomAccount(List<UserConfig> list, int betNum, int betType) {
        log.info("betNum:{} list.size:{} betType:{}", betNum, list.size(), betType);

        // 过滤出符合betType的UserConfig列表
        List<UserConfig> filteredList = new ArrayList<>();
        for (UserConfig userConfig : list) {
            if (betType == userConfig.getBetType()) {
                filteredList.add(userConfig);
            }
        }

        // 如果过滤后的list的大小小于betNum，则返回整个过滤后的list
        if (filteredList.size() <= betNum) {
            log.info("过滤后的list的大小小于betNum，则返回整个过滤后的list 指定账户json key:{}", filteredList);
            return filteredList;
        }

        Random random = new Random();
        List<UserConfig> result = new ArrayList<>();
        List<UserConfig> temp = new ArrayList<>(filteredList); // 创建一个临时副本来进行随机选择

        // 随机选取betNum个元素
        for (int i = 0; i < betNum; i++) {
            int randomIndex = random.nextInt(temp.size()); // 从剩余的元素中随机选择
            result.add(temp.remove(randomIndex)); // 移除已选中的元素，避免重复
        }

        log.info("指定账户json key:{}", result);
        return result;
    }


    /**
     * 金额分配逻辑，首先遍历oddsMap，获取oddsMap下每个位置key，比如下标1有positive值的B1_1、B1_2、B1_3和reverse值的B1_4、B1_5、B1_6，然后根据userConfigs里的betType对应的账户，然后根据plan的betNum和oddsMap下每个位置key的数量，计算每个账户应该分配的金额，最后将金额分配给每个账户
     *
      */
    public static JSONObject distributeAmount(Map<Integer, Map<String, List<String>>> oddsMap, List<UserConfig> positiveAccounts, List<UserConfig> reverseAccounts, ConfigPlanVO plan) {
        JSONObject resultJson = new JSONObject();

        // 遍历 oddsMap 中的每个 odds 数据
        oddsMap.forEach((oddsKey, v) -> {
            // 分配 positive 账户的金额
            JSONObject posJson = new JSONObject();
            List<String> posOddsKey = v.get("positive");
            posOddsKey.forEach(odds -> {
                JSONObject amountJson = distributeAmountsForAccounts(positiveAccounts, plan.getPositiveAmount());
                posJson.putOpt(odds, amountJson);
            });

            // 分配 reverse 账户的金额
            JSONObject revJson = new JSONObject();
            List<String> revOddsKey = v.get("reverse");
            revOddsKey.forEach(odds -> {
                JSONObject amountJson = distributeAmountsForAccounts(reverseAccounts, plan.getReverseAmount());
                revJson.putOpt(odds, amountJson);
            });

            // 创建一个对象包含 pos 和 rev 的分配结果
            JSONObject posRevJson = new JSONObject();
            posRevJson.putOpt("positive", posJson);
            posRevJson.putOpt("reverse", revJson);

            // 将 oddsKey 对应的结果放入最终的结果中
            resultJson.putOpt(String.valueOf(oddsKey), posRevJson);
        });

        // 输出最终结果
        System.out.println("金额分配逻辑json:"+resultJson);
        return resultJson;
    }

    /**
     * 双面金额分配
     * @param oddsMap
     * @param positiveAccounts
     * @param reverseAccounts
     * @param plan
     * @return
     */
    public static JSONObject twoSidedDistributeAmount(Map<String, Map<String, List<String>>> oddsMap, List<UserConfig> positiveAccounts, List<UserConfig> reverseAccounts, ConfigPlanVO plan) {
        // 遍历 oddsMap 中的每个 odds 数据
        // 分配 positive 账户的金额
        JSONObject posJson = new JSONObject();
        Map<String, List<String>> posOddsKey = oddsMap.get("positive");
        posOddsKey.forEach((odds, accounts) -> {
            JSONObject amountJson = distributeAmountsForAccounts(positiveAccounts, plan.getPositiveAmount());
            posJson.putOpt(odds, amountJson);
        });

        // 分配 reverse 账户的金额
        JSONObject revJson = new JSONObject();
        Map<String, List<String>> revOddsKey = oddsMap.get("reverse");
        revOddsKey.forEach((odds, accounts) -> {
            JSONObject amountJson = distributeAmountsForAccounts(reverseAccounts, plan.getReverseAmount());
            revJson.putOpt(odds, amountJson);
        });

        // 创建一个对象包含 pos 和 rev 的分配结果
        JSONObject posRevJson = new JSONObject();
        posRevJson.putOpt("positive", posJson);
        posRevJson.putOpt("reverse", revJson);

        // 输出最终结果
        System.out.println("双面金额分配逻辑json:"+posRevJson);
        return posRevJson;
    }

    private static JSONObject distributeAmountsForAccounts(List<UserConfig> accounts, int totalAmount) {
        JSONObject amountJson = new JSONObject();
        int betNum = accounts.size();

        if (totalAmount <= 0) {
            System.err.println("投注数量和金额必须大于0");
            return amountJson;
        }

        if (betNum == 0) {
            System.err.println("没有可用的投注账号");
            return amountJson;
        }

        // 初始分配：保证每个账号至少分配1个单位
        int initialShare = 1;
        int remainingAmount = totalAmount - betNum * initialShare;

        if (remainingAmount < 0) {
            System.err.println("金额不足以保证每个投注至少 1 元");
            return amountJson;
        }

        // 随机分配权重
        int[] weights = new int[betNum];
        int totalWeight = 0;
        Random random = new Random();
        for (int i = 0; i < betNum; i++) {
            weights[i] = random.nextInt(100) + 1; // 确保权重为正整数
            totalWeight += weights[i];
        }

        // 分配剩余金额
        int[] distributedAmounts = new int[betNum];
        int distributedSum = 0;
        for (int i = 0; i < betNum; i++) {
            if (i == betNum - 1) {
                // 最后一个账号分配剩余金额，避免误差
                distributedAmounts[i] = remainingAmount - distributedSum;
            } else {
                distributedAmounts[i] = (int) Math.floor((double) remainingAmount * weights[i] / totalWeight);
                distributedSum += distributedAmounts[i];
            }
        }

        // 最终金额分配
        for (int i = 0; i < betNum; i++) {
            int finalAmount = distributedAmounts[i] + initialShare;
            amountJson.putOpt(accounts.get(i).getAccount(), finalAmount);
        }

        // 校验分配结果
        int actualTotal = 0;
        for (int i = 0; i < betNum; i++) {
            actualTotal += amountJson.getInt(accounts.get(i).getAccount());
        }

        if (actualTotal != totalAmount) {
            // 调整差额到最后一个账号
            int adjustment = totalAmount - actualTotal;
            String lastAccount = accounts.get(betNum - 1).getAccount();
            int correctedAmount = amountJson.getInt(lastAccount) + adjustment;
            amountJson.putOpt(lastAccount, correctedAmount);
        }

        return amountJson;
    }

    /**
     * 自动刷新今日汇总 - 用于防止盘口token失效
     */
    public void autoRefreshBalance() {
        TimeInterval timer = DateUtil.timer();
        List<AdminLoginDTO> adminUsers = getAutoBetAdminUsers();
        // 如果没有有效用户，直接退出
        if (CollUtil.isEmpty(adminUsers)) {
            return;
        }

        // 基于任务总量和 CPU 核数动态调整线程池大小
        int cpuCoreCount = Runtime.getRuntime().availableProcessors();
        int threadPoolSize = Math.min(adminUsers.size(), Math.max(cpuCoreCount * 2, 100));
        log.info("自动刷新账号额度 cpu核数: {}，配置线程池大小: {}", cpuCoreCount, threadPoolSize);

        // 使用一个大的线程池来管理所有任务
        ExecutorService executorService = Executors.newFixedThreadPool(threadPoolSize);

        // 创建一个List来存储所有的 CompletableFuture 任务
        List<CompletableFuture<Void>> futures = adminUsers.stream()
                .map(admin -> CompletableFuture.runAsync(() -> {
                    List<UserConfig> accounts = configService.accounts(admin.getUsername(), null);
                    if (CollUtil.isEmpty(accounts)) {
                        return;
                    }
                    List<UserConfig> tokenVaildConfigs = accounts.stream()
                            .filter(userConfig -> 1 == userConfig.getIsTokenValid() && StringUtils.isNotBlank(userConfig.getToken()))
                            .toList();
                    if (CollUtil.isEmpty(tokenVaildConfigs)) {
                        return;
                    }

                    // 处理账户列表的任务
                    processTokenValidConfigs(admin, tokenVaildConfigs, cpuCoreCount);

                }, executorService))  // 提交给线程池
                .toList();

        // 等待所有的 CompletableFuture 完成
        CompletableFuture<Void> allOf = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        allOf.join();  // 阻塞当前线程，直到所有任务完成

        // 关闭线程池
        shutdownExecutor(executorService);
        log.info("自动刷新账号额度 平台用户数:{}，总刷新花费:{}毫秒", adminUsers.size(), timer.interval());
    }

    /**
     * 获取每个 admin 用户下的有效账户额度任务
     * @param admin 当前 admin 用户
     * @param tokenVaildConfigs 该 admin 下的有效账户列表
     * @param cpuCoreCount 系统 CPU 核心数，用来计算线程池大小
     */
    private void processTokenValidConfigs(AdminLoginDTO admin, List<UserConfig> tokenVaildConfigs, int cpuCoreCount) {
        // 基于任务总量和 CPU 核数动态调整线程池大小
        int threadPoolAccountSize = Math.min(tokenVaildConfigs.size(), Math.max(cpuCoreCount * 2, 10));
        ExecutorService executorService = Executors.newFixedThreadPool(threadPoolAccountSize);

        // 为每个有效账户创建 CompletableFuture 任务
        List<CompletableFuture<Void>> futuresAccount = tokenVaildConfigs.stream()
                .map(account -> CompletableFuture.runAsync(() -> {
                    try {
                        // 更新账户信息
                        account(admin.getUsername(), account.getId());
                    } catch (Exception e) {
                        log.error("获取平台用户:{} 账号:{}额度失败", admin.getUsername(), account.getAccount(), e);
                    }
                }, executorService))
                .toList();

        // 等待所有账户的任务完成
        CompletableFuture<Void> allAccount = CompletableFuture.allOf(futuresAccount.toArray(new CompletableFuture[0]));
        allAccount.join();  // 阻塞当前线程，直到所有任务完成

        // 关闭账户线程池
        shutdownExecutor(executorService);
    }

    /**
     * 获取今日汇总
     * @return
     */
    public List<UserConfig> summaryToday(String username) {
        TimeInterval timer = DateUtil.timer();
        List<UserConfig> accounts = configService.accounts(username, null);
        if (CollUtil.isEmpty(accounts)) {
            return Collections.emptyList();
        }
        List<UserConfig> tokenVaildConfigs = accounts.stream()
                .filter(userConfig -> 1 == userConfig.getIsTokenValid() && StringUtils.isNotBlank(userConfig.getToken()))
                .toList();
        if (CollUtil.isEmpty(tokenVaildConfigs)) {
            return Collections.emptyList();
        }
        int keysCount = tokenVaildConfigs.size();
        // 基于任务总量和 CPU 核数动态调整线程池大小
        int cpuCoreCount = Runtime.getRuntime().availableProcessors();
        int threadPoolSize = Math.min(keysCount, Math.max(cpuCoreCount * 2, 10));
        log.info("获取今日汇总 cpu核数: {}，配置线程池大小: {}", cpuCoreCount, threadPoolSize);
        // 初始化线程池（动态线程池，避免任务阻塞）
        ExecutorService executorService = Executors.newFixedThreadPool(threadPoolSize);

        // 创建一个List来存储所有的 CompletableFuture 任务
        List<CompletableFuture<Void>> futures = tokenVaildConfigs.stream()
            .map(account -> CompletableFuture.runAsync(() -> {
            try {
                // 更新余额和未结算金额
                JSONArray balance = account(username, account.getId());
                if (balance.isEmpty()) {
                    account.setBalance(BigDecimal.ZERO);
                    account.setBetting(BigDecimal.ZERO);
                } else {
                    JSONArray jsonArray = JSONUtil.parseArray(balance);
                    for (int i = 0; i < jsonArray.size(); i++) {
                        JSONObject object = jsonArray.getJSONObject(i);
                        // 同步一下余额信息
                        if (object.getLong("balance") > 0) {
                            account.setBalance(object.getBigDecimal("balance"));
                            account.setBetting(object.getBigDecimal("betting"));
                            account.setAccountType(getAccountType(object.getInt("type")));
                        }
                    }
                }
                // 获取账号今日流水
                JSONObject settled = JSONUtil.parseObj(settled(username, account.getId(), true, 1, true));
                JSONArray list = settled.getJSONArray("list");
                if (list.isEmpty()) {
                    account.setAmount(BigDecimal.ZERO);
                    account.setResult(BigDecimal.ZERO);
                } else {
                    list.forEach(object -> {
                        // 获取账号今日汇总
                        JSONObject jsonObject = JSONUtil.parseObj(object);
                        // 获取今日流水和盈亏
                        jsonObject.forEach((key, value) -> {
                            if (jsonObject.containsKey("account") && jsonObject.containsKey("totalAmount")) {
                                // 总金额
                                account.setAmount(jsonObject.getBigDecimal("totalAmount"));
                                account.setResult(jsonObject.getBigDecimal("totalResult"));
                            }
                        });
                    });
                }
                account.setUpdateTime(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATETIME_PATTERN));
                // 更新流水 Redis 缓存
                updateRedisCache(username, account);
            } catch (Exception e) {
                log.error("获取平台用户:{} 账号:{}今日汇总失败", username, account.getAccount(), e);
            }
        }, executorService)).toList();  // 提交给线程池
        // 等待所有的 CompletableFuture 完成
        CompletableFuture<Void> allOf = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        allOf.join();  // 阻塞当前线程，直到所有任务完成
        // 关闭线程池
        shutdownExecutor(executorService);
        log.info("获取今日汇总 平台用户:{}，刷新花费:{}毫秒", username, timer.interval());
        return accounts;
    }

    private void updateRedisCache(String username, UserConfig account) {
        String cacheKey = KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, account.getId());
        businessUserRedissonClient.getBucket(cacheKey).set(JSONUtil.toJsonStr(account));
    }

    private String getAccountType(int type) {
        return switch (type) {
            case 0 -> "A";
            case 1 -> "B";
            case 2 -> "C";
            case 3 -> "D";
            default -> "UNKNOWN";
        };
    }

    public String settled(String username, String id, Boolean settled, Integer pageNo, Boolean isSummary) {
        // 获取用户配置
        List<UserConfig> userConfigs = configService.accounts(username, id);
        if (CollectionUtil.isEmpty(userConfigs)) {
            throw new BusinessException(SystemError.USER_1007);
        }
        String baseUrl = userConfigs.get(0).getBaseUrl();
        String token = userConfigs.get(0).getToken();
        if (null == pageNo) {
            pageNo = 1;
        }
        // 拼接请求 URL
        String url = baseUrl + "member/bets?page=" + pageNo;
        if (settled) {
            url += "&settled=true";
        }

        // 设置请求头
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("accept-language", "zh-CN,zh;q=0.9");
        headers.put("cookie", "defaultSetting=5%2C10%2C20%2C50%2C100%2C200%2C500%2C1000; settingChecked=0; page=lm; index=; index2=; token=" + token);
        headers.put("priority", "u=1, i");
        headers.put("x-requested-with", "XMLHttpRequest");

        // 发送 HTTP 请求
        String result = null;
        try {
            HttpRequest request = HttpRequest.get(url)
                    .addHeaders(headers)
                    .timeout(10000);
            // 引入配置代理
            configureProxy(request, userConfigs.get(0));

            // 执行请求
            result = request.execute().body();
        } catch (Exception e) {
            Throwable cause = e.getCause(); // 获取原始异常原因
            if (cause instanceof UnknownHostException) {
                log.error("代理请求失败：主机未知。可能是域名解析失败或代理地址有误。异常信息：{}", e.getMessage(), e);
                throw new BusinessException(SystemError.SYS_419, userConfigs.get(0).getAccount(), "代理请求失败");
            } else if (cause instanceof ConnectException) {
                log.error("代理请求失败：连接异常。可能是代理服务器未开启或网络不可达。异常信息：{}", e.getMessage(), e);
                throw new BusinessException(SystemError.SYS_419, userConfigs.get(0).getAccount(), "连接异常");
            } else if (cause instanceof SocketTimeoutException) {
                log.error("代理请求失败：连接超时。可能是网络延迟过高或代理服务器响应缓慢。异常信息：{}", e.getMessage(), e);
                throw new BusinessException(SystemError.SYS_419, userConfigs.get(0).getAccount(), "连接超时");
            } else if (cause instanceof IOException) {
                log.error("代理请求失败：IO异常。可能是数据传输错误或代理配置不正确。异常信息：{}", e.getMessage(), e);
                throw new BusinessException(SystemError.SYS_419, userConfigs.get(0).getAccount(), "IO异常");
            } else {
                log.error("代理请求失败：未知异常。异常信息：{}", e.getMessage(), e);
                throw new BusinessException(SystemError.SYS_419, userConfigs.get(0).getAccount(), "未知异常");
            }
        }

        // 解析 HTML 数据
        Document doc = Jsoup.parse(result);
        JSONObject resultJson = new JSONObject();
        JSONArray tableData = new JSONArray();

        // 解析表格内容
        Elements rows = doc.select("table.list tbody tr");
        for (Element row : rows) {
            Elements cols = row.select("td");
            if (cols.size() > 0) {
                // 检测是否为 "暂无数据"
                if (cols.first().hasClass("nodata") && cols.first().text().contains("暂无数据")) {
                    log.info("暂无数据，无需解析");
                    break;
                }

                JSONObject rowData = new JSONObject();
                rowData.putOpt("account", userConfigs.get(0).getAccount() + "(" + (userConfigs.get(0).getBetType() == 1 ? "正" : "反") +")"); // 账号
                rowData.putOpt("orderNo", cols.get(0).text()); // 注单号
                rowData.putOpt("time", cols.get(1).text()); // 时间
                rowData.putOpt("type", cols.get(2).select(".lottery").text()); // 类型
                rowData.putOpt("drawNumber", cols.get(2).select(".draw_number").text()); // 期号
                rowData.putOpt("play", cols.get(3).text()); // 玩法
                rowData.putOpt("range", cols.get(4).text()); // 盘口
                rowData.putOpt("amount", cols.get(5).text()); // 下注金额
                rowData.putOpt("rebate", cols.get(6).text()); // 退水(%)
                rowData.putOpt("result", cols.get(7).text()); // 结果
                tableData.add(rowData);
            }
        }

        if (isSummary) {
            // 解析总计表格内容
            Elements rowsTotal = doc.select("table.list tfoot tr");
            for (Element row : rowsTotal) {
                Elements cols = row.select("td");
                if (cols.size() > 0) {
                    JSONObject rowData = new JSONObject();
                    rowData.putOpt("account", userConfigs.get(0).getAccount()); // 账号
                    rowData.putOpt("totalAmount", cols.get(4).text()); // 总流水
                    rowData.putOpt("totalResult", cols.get(6).text()); // 总盈亏
                    tableData.add(rowData);
                }
            }
        }
        // 添加表格数据
        resultJson.putOpt("list", tableData);

        // 分页数据解析
        Elements pagination = doc.select(".page_info");
        if (!pagination.isEmpty()) {
            JSONObject paginationJson = new JSONObject();
            Element pageInfo = pagination.first();
            Elements pageLinks = pageInfo.select("a");
            for (Element link : pageLinks) {
                if (link.hasClass("active")) {
                    paginationJson.putOpt("currentPage", link.text());
                } else if (link.text().contains("下一页")) {
                    paginationJson.putOpt("nextPage", link.attr("href"));
                } else if (link.text().contains("上一页")) {
                    paginationJson.putOpt("previousPage", link.attr("href"));
                }
            }

            // 总页数和总数据量可以根据分页元素中是否包含这些信息解析
            Element totalElement = pageInfo.selectFirst(".page_info");
            if (totalElement != null) {
                String totalText = totalElement.text();
                // 解析总页数和总数据量（假设文本格式为 "共 x 页/ y 条数据"）
                Pattern pattern = Pattern.compile("共 (\\d+) 笔记录 共 (\\d+) 页");
                Matcher matcher = pattern.matcher(totalText);
                if (matcher.find()) {
                    resultJson.putOpt("total", Integer.valueOf(matcher.group(1)));
                    resultJson.putOpt("pageSize", 15);
                }
            }
        }

        // 返回结果 JSON
        return resultJson.toString();
    }

    /**
     * 获取两周报表历史流水记录
     *
     * @return 结果
     */
    public String history(String username, String id, String lottery) {
        List<UserConfig> userConfigs = configService.accounts(username, id);
        if (CollectionUtil.isEmpty(userConfigs)) {
            throw new BusinessException(SystemError.USER_1007);
        }
        String baseUrl = userConfigs.get(0).getBaseUrl();
        String token = userConfigs.get(0).getToken();

        String url = baseUrl+"member/history";
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("accept-language", "zh-CN,zh;q=0.9");
        headers.put("cookie", "defaultSetting=5%2C10%2C20%2C50%2C100%2C200%2C500%2C1000; settingChecked=0; page=lm; index=; index2=; defaultLT="+lottery+"; token=" + token);
        headers.put("priority", "u=1, i");
        headers.put("x-requested-with", "XMLHttpRequest");
        // 发送 HTTP 请求
        String result = null;
        try {
            HttpRequest request = HttpRequest.get(url)
                    .addHeaders(headers);
            // 引入配置代理
            configureProxy(request, userConfigs.get(0));

            // 执行请求
            result = request.execute().body();
        } catch (Exception e) {
            Throwable cause = e.getCause(); // 获取原始异常原因
            if (cause instanceof UnknownHostException) {
                log.error("代理请求失败：主机未知。可能是域名解析失败或代理地址有误。异常信息：{}", e.getMessage(), e);
                throw new BusinessException(SystemError.SYS_419, userConfigs.get(0).getAccount(), "代理请求失败");
            } else if (cause instanceof ConnectException) {
                log.error("代理请求失败：连接异常。可能是代理服务器未开启或网络不可达。异常信息：{}", e.getMessage(), e);
                throw new BusinessException(SystemError.SYS_419, userConfigs.get(0).getAccount(), "连接异常");
            } else if (cause instanceof SocketTimeoutException) {
                log.error("代理请求失败：连接超时。可能是网络延迟过高或代理服务器响应缓慢。异常信息：{}", e.getMessage(), e);
                throw new BusinessException(SystemError.SYS_419, userConfigs.get(0).getAccount(), "连接超时");
            } else if (cause instanceof IOException) {
                log.error("代理请求失败：IO异常。可能是数据传输错误或代理配置不正确。异常信息：{}", e.getMessage(), e);
                throw new BusinessException(SystemError.SYS_419, userConfigs.get(0).getAccount(), "IO异常");
            } else {
                log.error("代理请求失败：未知异常。异常信息：{}", e.getMessage(), e);
                throw new BusinessException(SystemError.SYS_419, userConfigs.get(0).getAccount(), "未知异常");
            }
        }
        // 解析 HTML
        Document doc = Jsoup.parse(result);
        JSONObject resultJson = new JSONObject();
        // 对应两个表格的键名
        String[] tableKeys = {"lastWeek", "thisWeek"};
        int columnCount = 6; // 表格的列数

        // 处理两个表格
        Elements tables = doc.select("table.list");
        for (int i = 0; i < tableKeys.length; i++) {
            if (i >= tables.size()) break; // 防止表格数量不足

            List<Map<String, String>> tableData = new ArrayList<>();
            Elements rows = tables.get(i).select("tbody tr");

            for (Element row : rows) {
                Elements cols = row.select("td");
                if (cols.size() >= columnCount) {
                    Map<String, String> rowData = new HashMap<>();
                    rowData.put("date", cols.get(0).text());
                    rowData.put("count", cols.get(1).text());
                    rowData.put("betAmount", cols.get(2).text());
                    rowData.put("forceAmount", cols.get(3).text());
                    rowData.put("cm", cols.get(4).text());
                    rowData.put("dividend", cols.get(5).text());
                    tableData.add(rowData);
                }
            }
            resultJson.put(tableKeys[i], tableData);
        }

        // 返回结果 JSON
        System.out.println(resultJson);
        return resultJson.toString();
    }

}
