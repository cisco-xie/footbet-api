package com.example.demo.api;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.IdUtil;
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
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Slf4j
@Component
public class FalaliApi {

    @Resource
    private RedissonClient redisson;

    @Resource
    private ConfigService configService;

    /**
     * 设置账号代理
     * @param userProxy
     */
    public void config(ConfigUserVO userProxy) {
        UserConfig proxy = BeanUtil.copyProperties(userProxy, UserConfig.class);
        if (BeanUtil.isNotEmpty(proxy)) {
            redisson.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, userProxy.getAccount())).set(JSONUtil.toJsonStr(proxy));
        }
    }

    /**
     * 获取登录验证码信息
     *
     * @return 验证码id
     */
    public String code(String uuid) {
        String url = "https://3575978705.tcrax4d8j.com/code?_=" + System.currentTimeMillis();
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("accept-language", "zh-CN,zh;q=0.9");

        HttpResponse resultRes = HttpRequest.get(url)
                .addHeaders(headers)
                .cookie("2a29530a2306=" + uuid)
                .execute();

         String fileName = "d://code-" + uuid + ".jpg";
//        String fileName = "/usr/local/resources/projects/falali/code-" + uuid + ".jpg";
        resultRes.writeBody(fileName);

        try {
            // 确认下载文件是否是有效图片
            File image = new File(fileName);
            if (ImageIO.read(image) == null) {
                log.error("下载的文件不是有效图片: {}", fileName);
                return "0000";
            }

            // 设置动态库路径（确保动态库可加载）
//            System.setProperty("jna.library.path", "/lib/x86_64-linux-gnu");

            // OCR 处理
            Tesseract tesseract = new Tesseract();
             tesseract.setDatapath("d://tessdata");
//            tesseract.setDatapath("/usr/local/resources/projects/falali/tessdata");
            tesseract.setLanguage("eng");
            String result = tesseract.doOCR(image);
            log.info("验证码解析结果: {}", result);
            return result.trim();
        } catch (UnsatisfiedLinkError e) {
            log.error("动态库加载失败，请检查路径是否正确: {}", e.getMessage(), e);
            throw new RuntimeException("动态库加载失败", e);
        } catch (Exception e) {
            log.error("处理验证码失败: {}", e.getMessage(), e);
            throw new RuntimeException("验证码解析失败", e);
        } finally {
            // 删除临时文件
            try {
                Files.deleteIfExists(Paths.get(fileName));
            } catch (IOException e) {
                log.warn("临时文件删除失败: {}", fileName, e);
            }
        }
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
        boolean exists = redisson.getBucket(redisKey).isExists();
        if (exists) {
            AdminLoginDTO adminLogin = JSONUtil.toBean(JSONUtil.parseObj(redisson.getBucket(redisKey).get()), AdminLoginDTO.class);
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
     * @param account
     */
    public void singleLoginOut(String username, String account) {
        UserConfig userConfig = JSONUtil.toBean(JSONUtil.parseObj(redisson.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, account)).get()), UserConfig.class);
        if (BeanUtil.isNotEmpty(userConfig)) {
            userConfig.setToken(null);
            userConfig.setIsAutoLogin(0);
            userConfig.setIsTokenValid(0);
            redisson.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, account)).set(JSONUtil.toJsonStr(userConfig));
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
            redisson.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, userConfig.getAccount())).set(JSONUtil.toJsonStr(userConfig));
        });
    }

    /**
     * 单个账号登录
     * @param login
     * @return
     */
    public LoginDTO singleLogin(String username, LoginVO login) {
        List<UserConfig> userConfigs = configService.accounts(username, login.getAccount());
        if (CollectionUtil.isEmpty(userConfigs)) {
            throw new BusinessException(SystemError.USER_1007);
        }
        String baseUrl = userConfigs.get(0).getBaseUrl();
        LoginDTO loginDTO = new LoginDTO();
        loginDTO.setAccount(login.getAccount());
        int retryCount = 0;
        int maxRetries = 3;
        String token = null;
        while (retryCount < maxRetries) {
            Map<String, String> headers = new HashMap<>();
            String url = baseUrl+"login";
            headers.put("accept", "*/*");
            headers.put("accept-language", "zh-CN,zh;q=0.9");
            headers.put("content-type", "application/x-www-form-urlencoded");

            String uuid = IdUtil.randomUUID();
            String params = "type=1&account=" + login.getAccount() + "&password=" + login.getPassword() + "&code=" + code(uuid);

            log.info("账号 {} uuid 为: {} 完整url {} 参数{}", login.getAccount(), uuid, url, params);
            HttpResponse resultRes = HttpRequest.post(url)
                    .addHeaders(headers)
                    .cookie("2a29530a2306=" + uuid)
                    .body(params)
                    .execute();
            token = resultRes.getCookieValue("token");

            // token 不为空，成功
            if (StringUtils.isNotBlank(token)) {
                UserConfig userConfig = JSONUtil.toBean(JSONUtil.parseObj(redisson.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, login.getAccount())).get()), UserConfig.class);
                userConfig.setToken(token);
                userConfig.setIsAutoLogin(1);
                userConfig.setIsTokenValid(1);
                redisson.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, login.getAccount())).set(JSONUtil.toJsonStr(userConfig));
                return loginDTO;
            }

            retryCount++;
            ThreadUtil.sleep(300); // 延迟重试
        }
        if (StringUtils.isBlank(token) && retryCount == maxRetries) {
            log.warn("账号 {} 获取 token 失败，已达到最大重试次数", login.getAccount());
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
                LoginVO loginVO = null;
                while (retryCount < maxRetries) {
                    loginVO = new LoginVO();
                    loginVO.setAccount(login.getAccount());
                    loginVO.setPassword(login.getPassword());
                    loginDTO = singleLogin(username, loginVO); // 调用单个登录逻辑
                    String token = loginDTO.getToken();

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

//    public List<LoginDTO> batchLogin(List<LoginVO> logins) {
//        List<LoginDTO> results = Collections.synchronizedList(new ArrayList<>());
//        Set<String> tokenSet = ConcurrentHashMap.newKeySet();
//
//        // 动态线程池
//        ThreadPoolExecutor executor = new ThreadPoolExecutor(
//                5, 20, 60L, TimeUnit.SECONDS,
//                new LinkedBlockingQueue<>(100), new ThreadPoolExecutor.CallerRunsPolicy()
//        );
//
//        List<CompletableFuture<Void>> futures = logins.stream()
//                .map(login -> CompletableFuture.runAsync(() -> {
//                    int retryCount = 0;
//                    final int maxRetries = 3;
//
//                    while (retryCount < maxRetries) {
//                        LoginDTO loginDTO = singleLogin(login);
//                        String token = loginDTO.getToken();
//
//                        if (StringUtils.isNotBlank(token) && tokenSet.add(token)) {
//                            results.add(loginDTO);
//                            log.info("账号 {} 登录成功", login.getAccount());
//                            return;
//                        }
//
//                        log.warn("账号 {} 登录失败，token {} 重复，重试次数: {}", login.getAccount(), token, retryCount + 1);
//                        retryCount++;
//                    }
//
//                    log.error("账号 {} 登录失败，重试已达上限", login.getAccount());
//                    results.add(new LoginDTO(login.getAccount(), null)); // 标记登录失败
//                }, executor))
//                .toList();
//
//        // 等待所有任务完成
//        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
//        executor.shutdown();
//
//        log.info("批量登录完成，成功: {}，失败: {}", results.stream().filter(r -> r.getToken() != null).count(),
//                results.stream().filter(r -> r.getToken() == null).count());
//
//        return results;
//    }

    /**
     * 获取账号信息
     *
     * @return 结果
     */
    public JSONArray account(String username, String account) {
        List<UserConfig> userConfigs = configService.accounts(username, account);
        if (CollectionUtil.isEmpty(userConfigs)) {
            throw new BusinessException(SystemError.USER_1007);
        }
        String baseUrl = userConfigs.get(0).getBaseUrl();
        String token = userConfigs.get(0).getToken();
        String url = baseUrl+"member/accounts";
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("accept-language", "zh-CN,zh;q=0.9");
        headers.put("cookie", "defaultSetting=5%2C10%2C20%2C50%2C100%2C200%2C500%2C1000; settingChecked=0; index=; index2=; oid=3a9cad1bfcc69f05fd202bb3ddbc9df05b3bc062; defaultLT=PK10JSC; page=lm; token=" + token);
        headers.put("priority", "u=1, i");
        headers.put("x-requested-with", "XMLHttpRequest");
        String result = HttpRequest.get(url)
                .addHeaders(headers)
                .execute().body();
        System.out.println(result);
        JSONArray jsonArray = new JSONArray();
        if (StringUtils.isNotBlank(result)) {
            jsonArray = JSONUtil.parseArray(result);
            for (int i = 0; i < jsonArray.size(); i++) {
                JSONObject object = jsonArray.getJSONObject(i);
                object.putOpt("account", account); // 添加新字段
                jsonArray.set(i, object); // 替换回原数组
            }
        }
        return jsonArray;
    }

    /**
     * 获取token
     * @param account
     * @return
     */
    public String token(String username, String account) {
        UserConfig userConfig = JSONUtil.toBean(JSONUtil.parseObj(redisson.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, account)).get()), UserConfig.class);
        if (BeanUtil.isNotEmpty(userConfig)) {
            String token = userConfig.getToken();
            JSONArray jsonArray = account(username, account);
            if (!jsonArray.isEmpty()) {
                // 当前token有效，顺便同步一下余额信息
                    jsonArray.forEach(balance -> {
                        JSONObject balanceJson = JSONUtil.parseObj(balance);
                        if (0 == balanceJson.getInt("type")) {
                            userConfig.setBalance(balanceJson.getBigDecimal("balance"));
                            userConfig.setBetting(balanceJson.getBigDecimal("betting"));
                            redisson.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, account)).set(JSONUtil.toJsonStr(userConfig));
                        }
                    });
            } else {
                // 当前账号登录失效，进行自动登录操作
                if (userConfig.getIsAutoLogin() == 1) {
                    LoginVO login = new LoginVO();
                    login.setAccount(account);
                    login.setPassword(userConfig.getPassword());
                    LoginDTO loginDTO = singleLogin(username, login);
                    if (BeanUtil.isNotEmpty(loginDTO)) {
                        token = loginDTO.getToken();
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
    public String period(String usrename, String account, String lottery) {
        List<UserConfig> userConfigs = configService.accounts(usrename, account);
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
        String result = HttpRequest.get(url)
                .addHeaders(headers)
                .execute().body();
        if (result.isBlank()) {
            throw new BusinessException(SystemError.USER_1001);
        }
        System.out.println(result);
        return result;
    }

    /**
     * 获取赔率
     *
     * @return 结果
     */
    public String odds(String username, String account, String lottery) {
        List<UserConfig> userConfigs = configService.accounts(username, account);
        if (CollectionUtil.isEmpty(userConfigs)) {
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
        headers.put("x-requested-with", "XMLHttpRequest");

        // 配置代理认证
//        if (proxy != null && proxyUser != null && proxyPassword != null) {
//            Authenticator.setDefault(new Authenticator() {
//                @Override
//                protected PasswordAuthentication getPasswordAuthentication() {
//                    return new PasswordAuthentication(proxyUser, proxyPassword.toCharArray());
//                }
//            });
//        }

        // 设置代理和认证
        HttpRequest request = HttpRequest.get(url)
                .addHeaders(headers);
//        // 动态设置代理类型
//        Proxy proxy = null;
//        if (BeanUtil.isNotEmpty(userConfig) && null != userConfig.getProxyType()) {
//            if (userConfig.getProxyType() == 1) {
//                proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(userConfig.getProxyHost(), userConfig.getProxyPort()));
//            } else if (userConfig.getProxyType() == 2) {
//                proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(userConfig.getProxyHost(), userConfig.getProxyPort()));
//            }
//            request.setProxy(proxy);
//
//            // 设置代理认证
//            if (StringUtils.isNotBlank(userConfig.getProxyUsername()) && StringUtils.isNotBlank(userConfig.getProxyPassword())) {
//                request.basicProxyAuth(userConfig.getProxyUsername(), userConfig.getProxyPassword());
//            }
//
//        }
        String result;
        try {
            // 发起请求
            result = request.execute().body();
            result = result.isBlank() ? null : result;
        } catch (Exception e) {
            Throwable cause = e.getCause(); // 获取原始异常原因
            if (cause instanceof UnknownHostException) {
                throw new BusinessException(SystemError.USER_1002);
            } else if (cause instanceof ConnectException) {
                throw new BusinessException(SystemError.USER_1002);
            } else if (cause instanceof SocketTimeoutException) {
                throw new BusinessException(SystemError.USER_1002);
            } else if (cause instanceof IOException) {
                throw new BusinessException(SystemError.USER_1002);
            } else {
                // 未知异常，记录日志并抛出通用错误码
                log.error("代理请求失败，异常信息：{}", e.getMessage(), e);
                throw new BusinessException(SystemError.USER_1002);
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
    public JSONObject bet(String username, OrderVO order) {
        List<UserConfig> userConfigs = configService.accounts(username, order.getAccount());
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

    public void autoBet() {
        // 配置线程池
        ExecutorService executorService = Executors.newFixedThreadPool(10);

        // 匹配所有平台用户的 Redis Key
        String pattern = KeyUtil.genKey(RedisConstants.USER_ADMIN_PREFIX, "*");

        // 使用 Redisson 执行扫描所有平台用户操作
        RKeys keys = redisson.getKeys();
        Iterable<String> iterableKeys = keys.getKeysByPattern(pattern);
        List<String> keysList = new ArrayList<>();
        iterableKeys.forEach(keysList::add);

        // 计数器，用于统计成功/失败数量
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();
        AtomicInteger reverseCount = new AtomicInteger();

        // 对每个平台用户的操作并行化
        List<Callable<Void>> tasks = new ArrayList<>();
        for (String key : keysList) {
            tasks.add(() -> {
                // 使用 Redisson 获取每个平台用户的数据
                String json = (String) redisson.getBucket(key).get();
                if (json != null) {
                    // 解析 JSON 为 AdminLoginDTO 对象
                    AdminLoginDTO admin = JSONUtil.toBean(json, AdminLoginDTO.class);
                    String username = admin.getUsername();
                    // 校验是否开启自动下注
                    // Object value = redisson.getBucket(KeyUtil.genKey(
                    //         RedisConstants.USER_BET_AUTO_PREFIX,
                    //         username
                    // )).get();
                    // if (value == null) {
                    //     value = true;
                    // }
                    // boolean isAuto = Boolean.parseBoolean(String.valueOf(value));
                    // 这个平台账号不进行自动下注
                    // if (!isAuto) {
                    //     log.info("平台账号[{}]自动下注已关闭", username);
                    //     return null;
                    // }

                    // 获取所有配置计划
                    List<ConfigPlanVO> configs = configService.getAllPlans(username, null);

                    // 对每个配置计划列表进行并行化
                    List<Callable<Void>> planTasks = new ArrayList<>();
                    for (ConfigPlanVO plan : configs) {
                        planTasks.add(() -> {
                            if (BeanUtil.isEmpty(plan)) return null;

                            // 遍历每个配置项
                            if (plan.getEnable() == 0) return null; // 方案没有启用，跳过

                            // 获取最新期数
                            String period = period(username, plan.getAccount(), plan.getLottery());
                            if (StringUtils.isBlank(period)) return null; // 如果期数为空，跳过

                            JSONObject periodJson = JSONUtil.parseObj(period);
                            String drawNumber = periodJson.getStr("drawNumber");
                            // 校验封盘时间
                            Long closeTime = periodJson.getLong("closeTime") / 1000;
                            Long currentTime = System.currentTimeMillis() / 1000;
                            Long beetTime = closeTime - currentTime;
                            if (beetTime < 20) { log.info("游戏:{},期数:{},即将封盘,不进行下注", plan.getLottery(), drawNumber); return null; }; // 如果距离封盘时间小于20秒，跳过不下注

                            String isBetRedisKey = KeyUtil.genKey(
                                    RedisConstants.USER_BET_PERIOD_PREFIX,
                                    StringUtils.isNotBlank(plan.getLottery()) ? plan.getLottery() : "*",
                                    drawNumber,
                                    "*",
                                    StringUtils.isNotBlank(plan.getAccount()) ? plan.getAccount() : "*",
                                    String.valueOf(plan.getId())
                            );

                            // 判断是否已下注
                            boolean exists = redisson.getBucket(isBetRedisKey).isExists();
                            if (exists) { log.info("游戏:{},期数:{},账号:{},方案:{},已下过注,不再重复下注", plan.getLottery(), drawNumber, plan.getAccount(), plan.getName()); return null; } // 已下注，跳过

                            // 获取赔率
                            String odds = odds(username, plan.getAccount(), plan.getLottery());
                            if (StringUtils.isBlank(odds)) return null;
                            JSONObject oddsJson = JSONUtil.parseObj(odds);

                            List<UserConfig> userConfigs = configService.accounts(admin.getUsername(), null);
                            // 获取正投账号数
                            List<UserConfig> positiveAccounts = getRandomAccount(userConfigs, plan.getPositiveNum(), 1);
                            // 获取反投账号数
                            List<UserConfig> reverseAccounts = getRandomAccount(userConfigs, plan.getReverseNum(), 2);
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
                            List<Integer> positions = plan.getPositions();
                            for (int pos : positions) {
                                String jsonkey = "B" + pos;
                                List<String> matchedKeys = oddsJson.keySet().stream()
                                        .filter(matchedKey -> matchedKey.contains(jsonkey + "_"))
                                        .collect(Collectors.toList());
                                Map<String, List<String>> oddKeys = getOdds(matchedKeys, plan.getPositiveNum());
                                oddsMap.put(pos, oddKeys);
                            }

                            JSONObject amountJson = distributeAmount(oddsMap, positiveAccounts, reverseAccounts, plan);
                            for (UserConfig userConfig : allAccounts) {
                                try {
                                    int betNum = userConfig.getBetType() == 1 ? plan.getPositiveNum() : 10 - plan.getPositiveNum();
                                    long amount = userConfig.getBetType() == 1 ? plan.getPositiveAmount() : plan.getReverseAmount();
                                    String betTypeStr = userConfig.getBetType() == 1 ? "positive" : "reverse";

                                    if (amountJson.isEmpty()) {
                                        log.warn("分配金额失败，跳过账户: {}", userConfig.getAccount());
                                        failedAccounts.add(userConfig);
                                        continue;
                                    }
                                    // 创建下注请求参数
                                    OrderVO order = createOrder(plan, oddsJson, drawNumber, positions, betTypeStr, amountJson, userConfig);
                                    // 提交下注
                                    String result = submitOrder(order, userConfig, plan, drawNumber);
                                    // 结果解析
                                    handleOrderResult(result, userConfig, drawNumber, plan, successAccounts, failedAccounts, successCount, failureCount, reverseCount);

                                } catch (Exception e) {
                                    log.error("处理账户时发生异常，账号: {}", userConfig.getAccount(), e);
                                    failedAccounts.add(userConfig);
                                    failureCount.incrementAndGet();
                                }
                            }
                            // 反补处理
                            processReverseBet(username, failedAccounts, successAccounts, plan, drawNumber, isBetRedisKey, reverseCount);

                            return null;
                        });
                    }

                    // 提交 planList 中的任务并行处理
                    try {
                        executorService.invokeAll(planTasks);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.error("执行任务时出现中断异常", e);
                    }
                }
                return null;
            });
        }

        // 提交平台用户的任务到线程池
        try {
            executorService.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("执行任务时出现中断异常", e);
        } finally {
            // 关闭线程池
            executorService.shutdown();
        }
        // 打印批量登录的成功和失败次数
        log.info("批量下注完成，成功次数: {}, 失败次数: {}, 反补次数: {}", successCount.get(), failureCount.get(), reverseCount.get());
        if (failureCount.get() > 0) {
            throw new BusinessException(SystemError.ORDER_1100);
        }
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
    private OrderVO createOrder(ConfigPlanVO plan, JSONObject oddsJson, String drawNumber, List<Integer> positions, String oddsType, JSONObject amountJson, UserConfig userConfig) {
        OrderVO order = new OrderVO();
        List<Bet> bets = new ArrayList<>();
        order.setLottery(plan.getLottery());
        order.setDrawNumber(drawNumber);
        order.setFastBets(false);
        order.setIgnore(false);

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
            Proxy proxy = new Proxy(
                    userConfig.getProxyType() == 1 ? Proxy.Type.HTTP : Proxy.Type.SOCKS,
                    new InetSocketAddress(userConfig.getProxyHost(), userConfig.getProxyPort())
            );
            request.setProxy(proxy);
            if (StringUtils.isNotBlank(userConfig.getProxyUsername()) && StringUtils.isNotBlank(userConfig.getProxyPassword())) {
                request.basicProxyAuth(userConfig.getProxyUsername(), userConfig.getProxyPassword());
            }
        }
    }

    /**
     * 封装发起提交下注
     * @param order
     * @param userConfig
     * @param plan
     * @param drawNumber
     * @return
     */
    private String submitOrder(OrderVO order, UserConfig userConfig, ConfigPlanVO plan, String drawNumber) {
        String url = userConfig.getBaseUrl() + "member/bet";
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("cookie", "defaultLT=" + plan.getLottery() + "; token=" + userConfig.getToken());

        HttpRequest request = HttpRequest.post(url).addHeaders(headers);
        configureProxy(request, userConfig);

        // 记录订单请求
        redisson.getBucket(KeyUtil.genKey(
                RedisConstants.USER_BET_PERIOD_REQ_PREFIX,
                plan.getLottery(),
                drawNumber,
                userConfig.getAccount(),
                String.valueOf(plan.getId())
        )).set(JSONUtil.toJsonStr(order));

        return request.body(JSONUtil.toJsonStr(order)).execute().body();
    }

    /**
     * 封装处理下注结果
     * @param result
     * @param userConfig
     * @param drawNumber
     * @param plan
     * @param successAccounts
     * @param failedAccounts
     * @return
     */
    private boolean handleOrderResult(String result, UserConfig userConfig, String drawNumber, ConfigPlanVO plan, List<UserConfig> successAccounts, List<UserConfig> failedAccounts, AtomicInteger successCount,AtomicInteger failureCount,AtomicInteger reverseCount) {
        boolean isSuccess = true;
        if (StringUtils.isNotBlank(result)) {
            JSONObject resultJson = JSONUtil.parseObj(result);
            int status = resultJson.getInt("status");
            if (status == 0) {
                // 成功逻辑
                redisson.getBucket(KeyUtil.genKey(
                        RedisConstants.USER_BET_PERIOD_RES_PREFIX,
                        plan.getLottery(),
                        drawNumber,
                        "success",
                        userConfig.getAccount(),
                        String.valueOf(plan.getId())
                )).set(JSONUtil.toJsonStr(result));
                successAccounts.add(userConfig);
                successCount.incrementAndGet();
                log.info("下单成功, 账号:{}, 期数:{}", userConfig.getAccount(), drawNumber);
            } else {
                // 失败逻辑
                isSuccess = false;
                log.error("下单失败, 账号:{}, 期数:{}, 返回信息{}", userConfig.getAccount(), drawNumber, resultJson);
            }
        } else {
            isSuccess = false;
        }

        if (!isSuccess) {
            // 记录失败信息
            redisson.getBucket(KeyUtil.genKey(
                    RedisConstants.USER_BET_PERIOD_RES_PREFIX,
                    plan.getLottery(),
                    drawNumber,
                    "failed",
                    userConfig.getAccount(),
                    String.valueOf(plan.getId())
            )).set(JSONUtil.toJsonStr(result));
            failedAccounts.add(userConfig);
            failureCount.incrementAndGet();
        }

        return isSuccess;
    }

    /**
     * 处理下单失败的反补操作
     * @param failedAccounts
     * @param successAccounts
     * @param plan
     * @param drawNumber
     */
    private void processReverseBet(String username, List<UserConfig> failedAccounts, List<UserConfig> successAccounts, ConfigPlanVO plan, String drawNumber, String isBetRedisKey, AtomicInteger reverseCount) {
        if (CollectionUtil.isNotEmpty(failedAccounts)) {
            // 当前方案存在账号下注失败
            failedAccounts.forEach(account -> {
                // 获取到下注失败的请求参数
                String failedReq = (String) redisson.getBucket(KeyUtil.genKey(
                        RedisConstants.USER_BET_PERIOD_REQ_PREFIX,
                        plan.getLottery(),
                        drawNumber,
                        account.getAccount(),
                        String.valueOf(plan.getId())
                )).get();
                // 通过当前方案账号进行反补
                for (UserConfig sucAccount : successAccounts) {
                    boolean reverseSuccess = handleReverseBet(account, sucAccount, failedReq, plan, drawNumber, isBetRedisKey, reverseCount);
                    if (reverseSuccess) {
                        break; // 反补成功退出
                    }
                }
            });
            // 关闭自动下注
            redisson.getBucket(KeyUtil.genKey(
                    RedisConstants.USER_BET_AUTO_PREFIX,
                    username
            )).set(false);
            // 保险起见-再把当前平台用户下的账号全部下线
            // batchLoginOut(username);
            // 把当前平台用户下的所有方案停用
            List<ConfigPlanVO> configPlans = configService.getAllPlans(username, null);
            configPlans.forEach(configPlan -> {
                configPlan.setEnable(0);
                configService.plan(username, configPlan);
            });
        }
    }

    private boolean handleReverseBet(UserConfig failedAccount, UserConfig sucAccount, String failedReq, ConfigPlanVO plan, String drawNumber, String isBetRedisKey, AtomicInteger reverseCount) {
        // 提交反补订单
        String url = sucAccount.getBaseUrl() + "member/bet";
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("cookie", "defaultLT=" + plan.getLottery() + "; token=" + sucAccount.getToken());
        HttpRequest request = HttpRequest.post(url).addHeaders(headers);
        configureProxy(request, sucAccount);

        // 记录反补的请求参数
        redisson.getBucket(KeyUtil.genKey(
                RedisConstants.USER_BET_PERIOD_REQ_PREFIX,
                plan.getLottery(),
                drawNumber,
                sucAccount.getAccount(),
                String.valueOf(plan.getId()),
                "reverse"
        )).set(JSONUtil.toJsonStr(failedReq));

        String result = null;
        try {
            result = request.body(JSONUtil.toJsonStr(failedReq)).execute().body();
        } catch (Exception e) {
            log.error("反补请求失败, 失败账号:{}, 对冲账号:{}, 期数:{}, 异常:{}", failedAccount.getAccount(), sucAccount.getAccount(), drawNumber, e.getMessage());
        }

        if (StringUtils.isNotBlank(result)) {
            JSONObject resultJson = JSONUtil.parseObj(result);
            int status = resultJson.getInt("status");
            if (status == 0) {
                redisson.getBucket(isBetRedisKey).set("1");
                reverseCount.incrementAndGet();
                log.info("反补下单成功, 失败账号:{}, 对冲账号:{}, 期数:{}", failedAccount.getAccount(), sucAccount.getAccount(), drawNumber);

                // 反补成功返回参数
                redisson.getBucket(KeyUtil.genKey(
                        RedisConstants.USER_BET_PERIOD_RES_PREFIX,
                        StringUtils.isNotBlank(plan.getLottery()) ? plan.getLottery() : "*",
                        drawNumber,
                        "reverse",
                        StringUtils.isNotBlank(sucAccount.getAccount()) ? sucAccount.getAccount() : "*",
                        String.valueOf(plan.getId())
                )).set(JSONUtil.toJsonStr(result));
                return true; // 反补成功
            } else {
                log.error("反补下单失败, 失败账号:{}, 对冲账号:{}, 期数:{}, 返回信息{}", failedAccount.getAccount(), sucAccount.getAccount(), drawNumber, resultJson);
            }
        } else {
            log.error("反补下单失败, 失败账号:{}, 对冲账号:{}, 期数:{}, 返回为空", failedAccount.getAccount(), sucAccount.getAccount(), drawNumber);
        }

        // 反补失败返回参数
        redisson.getBucket(KeyUtil.genKey(
                RedisConstants.USER_BET_PERIOD_RES_PREFIX,
                plan.getLottery(),
                drawNumber,
                "failed",
                sucAccount.getAccount(),
                String.valueOf(plan.getId())
        )).set(JSONUtil.toJsonStr(result));
        return false; // 反补失败
    }

    /**
     * 自动下单api
     *
     * @return 结果
     */
//    public void autoBet() {
//        // 匹配所有平台用户的 Redis Key
//        String pattern = KeyUtil.genKey(RedisConstants.USER_ADMIN_PREFIX, "*");
//
//        // 使用 Redisson 执行扫描所有平台用户操作
//        RKeys keys = redisson.getKeys();
//        Iterable<String> iterableKeys = keys.getKeysByPattern(pattern);
//        List<String> keysList = new ArrayList<>();
//        iterableKeys.forEach(keysList::add);
//        keysList.forEach(key -> {
//            // 使用 Redisson 获取每个 平台用户 的数据
//            String json = (String) redisson.getBucket(key).get();
//            if (json != null) {
//                // 解析 JSON 为 AdminLoginDTO 对象
//                AdminLoginDTO admin = JSONUtil.toBean(json, AdminLoginDTO.class);
//                String username = admin.getUsername();
//                List<List<ConfigPlanVO>> configs = configService.getAllPlans(username, null, null);
//                for (int i = 0; i < configs.size(); i++) {
//                    String account = configs.get(i).get(0).getAccount();
//                    String lottery = configs.get(i).get(0).getLottery();
//
//                    List<UserConfig> userConfigs = configService.accounts(username, account);
//                    if (CollectionUtil.isEmpty(userConfigs)) {
//                        throw new BusinessException(SystemError.USER_1007);
//                    }
//                    String baseUrl = userConfigs.get(0).getBaseUrl();
//
//                    for (int j = 0; j < configs.get(i).size(); j++) {
//                        if (configs.get(i).get(j).getEnable() == 0) {
//                            // 方案没有启用进入下一个方案
//                            continue;
//                        }
//
//                        // 先获取到此账号的token
//                        UserConfig userConfig = JSONUtil.toBean(JSONUtil.parseObj(redisson.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, configs.get(i).get(j).getAccount())).get()), UserConfig.class);
//                        boolean isToken = false;
//                        String token = null;
//                        if (BeanUtil.isNotEmpty(userConfig)) {
//                            token = userConfig.getToken();
//                            JSONArray jsonArray = account(username, configs.get(i).get(j).getAccount());
//                            if (!jsonArray.isEmpty()) {
//                                isToken =  true;
//                            } else {
//                                // token无效则直接退出
//                                break;
//                            }
//                        }
//                        if (isToken) {
//
//                            // 获取指定Lottery的最新期数
//                            String period = period(username, account, lottery);
//                            if (StringUtils.isBlank(period)) {
//                                // 没有获取到期数说明当前可能正在封盘阶段或者账号token失效
//                                continue;
//                            }
//                            // 期数json
//                            JSONObject periodJson = JSONUtil.parseObj(period);
//                            String drawNumber = periodJson.getStr("drawNumber");
//                            String isBetRedisKey = KeyUtil.genKey(RedisConstants.USER_PLAN_PERIOD_PREFIX,
//                                    StringUtils.isNotBlank(lottery) ? lottery : "*",
//                                    drawNumber, StringUtils.isNotBlank(username) ? username : "*",
//                                    StringUtils.isNotBlank(account) ? account : "*",
//                                    String.valueOf(configs.get(i).get(j).getId()));
//
//                            // 判断 Redis 中是否存在该用户的下注数据
//                            boolean exists = redisson.getBucket(isBetRedisKey).isExists();
//                            if (exists) {
//                                // 期数redis存在说明当前用户下的账号在当前游戏期数已经下过注
//                                continue;
//                            }
//
//                            // 获取陪率
//                            String odds = odds(username, account, lottery);
//                            if (StringUtils.isBlank(odds)) {
//                                continue;
//                            }
//                            JSONObject oddsJson = JSONUtil.parseObj(odds);
//
//                            // token存在即进行下单操作
//                            String url = baseUrl+"member/bet";
//                            Map<String, String> headers = new HashMap<>();
//                            headers.put("accept", "*/*");
//                            headers.put("accept-language", "zh-CN,zh;q=0.9");
//                            headers.put("cookie", "defaultSetting=5%2C10%2C20%2C50%2C100%2C200%2C500%2C1000; settingChecked=0; index=; index2=; defaultLT="+lottery+"; page=lm; token=" + token);
//                            headers.put("priority", "u=1, i");
//                            headers.put("x-requested-with", "XMLHttpRequest");
//
//                            // 设置代理和认证
//                            HttpRequest request = HttpRequest.post(url)
//                                    .addHeaders(headers);
//                            // 动态设置代理类型
////                    Proxy proxy = null;
////                    if (BeanUtil.isNotEmpty(userConfig) && null != userConfig.getProxyType()) {
////                        if (userConfig.getProxyType() == 1) {
////                            proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(userConfig.getProxyHost(), userConfig.getProxyPort()));
////                        } else if (userConfig.getProxyType() == 2) {
////                            proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(userConfig.getProxyHost(), userConfig.getProxyPort()));
////                        }
////                        request.setProxy(proxy);
////
////                        // 设置代理认证
////                        if (StringUtils.isNotBlank(userConfig.getProxyUsername()) && StringUtils.isNotBlank(userConfig.getProxyPassword())) {
////                            request.basicProxyAuth(userConfig.getProxyUsername(), userConfig.getProxyPassword());
////                        }
////                    }
//                            // 投注数
//                            int betNum = 0;
//                            // 投注金额
//                            long amount = 0;
//                            if (userConfig.getBetType() == 1) {
//                                // 用户配置的正投
//                                betNum = configs.get(i).get(j).getPositiveNum();
//                                amount = configs.get(i).get(j).getPositiveAmount();
//                            } else if (userConfig.getBetType() == 2) {
//                                // 用户配置的反投
//                                betNum = configs.get(i).get(j).getReverseNum();
//                                amount = configs.get(i).get(j).getReverseAmount();
//                            }
//                            // 随机投注金额 生产betNum数量的1到10的随机数，reverseContents则获取剩下相反的数
//                            // Map<String, List<Integer>> contents = generateBetContents(betNum);
//                            // List<Integer> positiveContents = contents.get("positive");
//                            // List<Integer> reverseContents = contents.get("reverse");
//                            List<Long> amounts = distributeAmount(betNum, amount);
//                            if (CollectionUtil.isEmpty(amounts)) {
//                                continue;
//                            }
//                            OrderVO order = new OrderVO();
//                            List<Bet> bets = new ArrayList<>();
//                            order.setLottery(lottery);
//                            order.setDrawNumber(drawNumber);
//                            order.setFastBets(false);
//                            order.setIgnore(false);
//                            Bet bet = null;
//                            List<Integer> positions = configs.get(i).get(j).getPositions();
//                            for (int k = 0; k < positions.size(); k++) {
//                                String jsonkey = "B" + positions.get(k);
//                                // 筛选出与当前 po 匹配的键值对
//                                List<String> matchedKeys = oddsJson.keySet().stream()
//                                        .filter(matchedKey -> matchedKey.contains(jsonkey+"_"))
//                                        .collect(Collectors.toList());
//
//                                List<String> oddKeys = getRandomElements(matchedKeys, betNum);
//                                for (int l = 0; l < oddKeys.size(); l++) {
//                                    bet = new Bet();
//                                    bet.setGame(jsonkey);
//                                    bet.setAmount(amounts.get(l));
//                                    bet.setContents(oddKeys.get(l).replace(jsonkey+"_", ""));
//                                    // 获取赔率
//                                    bet.setOdds(oddsJson.getDouble(oddKeys.get(l)));
//                                    bets.add(bet);
//                                }
//                            }
//                            order.setBets(bets);
//                            System.out.println(JSONUtil.toJsonStr(order));
//                            String result = request.body(JSONUtil.toJsonStr(order))
//                                    .execute().body();
//                            System.out.println(result);
//                            JSONObject resultJson;
//                            if (StringUtils.isNotBlank(result)) {
//                                resultJson = JSONUtil.parseObj(result);
//                                int status = resultJson.getInt("status");
//                                if (status == 0) {
//                                    // 标记下注成功
//                                    redisson.getBucket(isBetRedisKey).set("1");
//                                    log.error("下单成功,账号号:{}, 期数{}", configs.get(i).get(j).getAccount(), order.getDrawNumber());
//                                } else {
//                                    log.error("下单失败,账号号:{}, 期数{}", configs.get(i).get(j).getAccount(), order.getDrawNumber());
//                                }
//                            }
//                        }
//                    };
//                };
//
//            }
//        });
//
//    }

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
        positiveAccounts1.add(new UserConfig("account1"));
        positiveAccounts1.add(new UserConfig("account2"));
        positiveAccounts1.add(new UserConfig("account3"));
        List<UserConfig> reverseAccounts1 = new ArrayList<>();
        reverseAccounts1.add(new UserConfig("account4"));
        reverseAccounts1.add(new UserConfig("account5"));
        reverseAccounts1.add(new UserConfig("account6"));
        reverseAccounts1.add(new UserConfig("account7"));
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
        userConfigs.add(new UserConfig("1", "1", "1", 1, 1, 1, 1, "123456", 1, "123456", "123456", "123456", null, null));
        userConfigs.add(new UserConfig("2", "2", "2", 1, 1, 1, 1, "123456", 1, "123456", "123456", "123456", null, null));
        userConfigs.add(new UserConfig("3", "3", "3", 1, 1, 1, 1, "123456", 1, "123456", "123456", "123456", null, null));
        userConfigs.add(new UserConfig("4", "3", "3", 1, 1, 1, 1, "123456", 1, "123456", "123456", "123456", null, null));
        userConfigs.add(new UserConfig("5", "3", "3", 1, 1, 1, 1, "123456", 1, "123456", "123456", "123456", null, null));
        userConfigs.add(new UserConfig("6", "3", "3", 1, 1, 2, 1, "123456", 1, "123456", "123456", "123456", null, null));
        userConfigs.add(new UserConfig("7", "3", "3", 1, 1, 2, 1, "123456", 1, "123456", "123456", "123456", null, null));
        userConfigs.add(new UserConfig("8", "3", "3", 1, 1, 2, 1, "123456", 1, "123456", "123456", "123456", null, null));
        userConfigs.add(new UserConfig("9", "3", "3", 1, 1, 2, 1, "123456", 1, "123456", "123456", "123456", null, null));
        userConfigs.add(new UserConfig("10", "3", "3", 1, 1, 2, 1, "123456", 1, "123456", "123456", "123456", null, null));

        // 获取正投账号数
        List<UserConfig> positiveAccounts = getRandomAccount(userConfigs, 2, 1);
        // 获取反投账号数
        List<UserConfig> reverseAccounts = getRandomAccount(userConfigs, 2, 2);


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



    /**
     * 随机抽取指定数量的投注账户
     */
    private static List<UserConfig> getRandomAccount(List<UserConfig> list, int betNum, int betType) {
        log.info("betNum:{} list.size:{} betType:{}", betNum, list.size(), betType);

        // 过滤出符合betType的UserConfig列表
        List<UserConfig> filteredList = new ArrayList<>();
        for (UserConfig userConfig : list) {
            if (betType == userConfig.getBetType()) { // 假设UserConfig有getBetType()方法
                filteredList.add(userConfig);
            }
        }

        // 如果过滤后的list的大小小于betNum，则返回整个过滤后的list
        if (filteredList.size() <= betNum) {
            log.info("Warning: Requested count ({}) exceeds available items ({}) after filtering by betType. Returning all items.", betNum, filteredList.size());
            log.info("指定账户json key:{}", filteredList);
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
     * 修改金额分配逻辑，首先遍历oddsMap，获取oddsMap下每个位置key，比如下标1有positive值的B1_1、B1_2、B1_3和reverse值的B1_4、B1_5、B1_6，然后根据userConfigs里的betType对应的账户，然后根据plan的betNum和oddsMap下每个位置key的数量，计算每个账户应该分配的金额，最后将金额分配给每个账户
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
        System.out.println(resultJson);
        return resultJson;
    }



    private static JSONObject distributeAmountsForAccounts(List<UserConfig> accounts, int totalAmount) {
        JSONObject amountJson = new JSONObject();
        int betNum = accounts.size();

        if (totalAmount <= 0) {
            System.err.println("投注数量和金额必须大于0");
            return amountJson;
        }

        Random random = new Random();
        long initialShare = 1;
        long remainingAmount = totalAmount - betNum * initialShare;

        if (remainingAmount < 0) {
            System.err.println("金额不足以保证每个投注至少 1 元");
            return amountJson;
        }

        // 生成随机权重
        double[] weights = new double[betNum];
        double weightSum = 0;
        for (int i = 0; i < betNum; i++) {
            weights[i] = random.nextDouble();
            weightSum += weights[i];
        }

        // 根据权重分配剩余金额
        List<Long> resultAmounts = new ArrayList<>();
        long totalDistributedAmount = 0;

        for (int i = 0; i < betNum; i++) {
            long share = Math.round(remainingAmount * weights[i] / weightSum);
            resultAmounts.add(share);
            totalDistributedAmount += share;
            amountJson.putOpt(accounts.get(i).getAccount(), share);
        }

        // 将初始化金额 1 加到每个份额
        for (int i = 0; i < betNum; i++) {
            resultAmounts.set(i, resultAmounts.get(i) + initialShare);
            totalDistributedAmount += initialShare;
            amountJson.putOpt(accounts.get(i).getAccount(), resultAmounts.get(i));
        }

        // 调整误差，将剩余金额分配给最后一个份额
        long remainder = totalAmount - totalDistributedAmount;
        if (remainder != 0) {
            resultAmounts.set(betNum - 1, resultAmounts.get(betNum - 1) + remainder);
            amountJson.putOpt(accounts.get(betNum - 1).getAccount(), resultAmounts.get(betNum - 1) + remainder);
        }

        return amountJson;
    }


    public String settled(String username, String account, Boolean settled, Integer pageNo) {
        // 获取用户配置
        List<UserConfig> userConfigs = configService.accounts(username, account);
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
            result = HttpRequest.get(url)
                    .addHeaders(headers)
                    .execute()
                    .body();
        } catch (Exception e) {
            log.error(e.getMessage());
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
                rowData.put("account", userConfigs.get(0).getAccount()); // 账号
                rowData.put("orderNo", cols.get(0).text()); // 注单号
                rowData.put("time", cols.get(1).text()); // 时间
                rowData.put("type", cols.get(2).select(".lottery").text()); // 类型
                rowData.put("drawNumber", cols.get(2).select(".draw_number").text()); // 期号
                rowData.put("play", cols.get(3).text()); // 玩法
                rowData.put("range", cols.get(4).text()); // 盘口
                rowData.put("amount", cols.get(5).text()); // 下注金额
                rowData.put("rebate", cols.get(6).text()); // 退水(%)
                rowData.put("result", cols.get(7).text()); // 结果
                tableData.add(rowData);
            }
        }

        // 添加表格数据
        resultJson.put("list", tableData);

        // 分页数据解析
        Elements pagination = doc.select(".page_info");
        if (!pagination.isEmpty()) {
            JSONObject paginationJson = new JSONObject();
            Element pageInfo = pagination.first();
            Elements pageLinks = pageInfo.select("a");
            for (Element link : pageLinks) {
                if (link.hasClass("active")) {
                    paginationJson.put("currentPage", link.text());
                } else if (link.text().contains("下一页")) {
                    paginationJson.put("nextPage", link.attr("href"));
                } else if (link.text().contains("上一页")) {
                    paginationJson.put("previousPage", link.attr("href"));
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
                    resultJson.put("total", Integer.valueOf(matcher.group(1)));
                    resultJson.put("pageSize", 15);
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
    public String history(String username, String account, String lottery) {
        List<UserConfig> userConfigs = configService.accounts(username, account);
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
        String result = HttpRequest.get(url)
                .addHeaders(headers)
                .execute().body();
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
