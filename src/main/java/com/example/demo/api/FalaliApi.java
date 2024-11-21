package com.example.demo.api;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.common.constants.RedisConstants;
import com.example.demo.common.enmu.GameType;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.common.utils.KeyUtil;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.core.model.UserConfig;
import com.example.demo.model.dto.LoginDTO;
import com.example.demo.model.vo.LoginVO;
import com.example.demo.model.vo.OrderVO;
import com.example.demo.model.vo.TokenVo;
import com.example.demo.model.vo.ConfigUserVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Component
public class FalaliApi {

    @Resource
    private RedissonClient redisson;

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

        // String fileName = "d://code-" + uuid + ".jpg";
        String fileName = "/usr/local/resources/projects/falali/code-" + uuid + ".jpg";
        resultRes.writeBody(fileName);

        try {
            // 确认下载文件是否是有效图片
            File image = new File(fileName);
            if (ImageIO.read(image) == null) {
                log.error("下载的文件不是有效图片: {}", fileName);
                return "0000";
            }

            // 设置动态库路径（确保动态库可加载）
            System.setProperty("jna.library.path", "/lib/x86_64-linux-gnu");

            // OCR 处理
            Tesseract tesseract = new Tesseract();
            // tesseract.setDatapath("d://tessdata");
            tesseract.setDatapath("/usr/local/resources/projects/falali/tessdata");
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

    /**
     * 单个账号登录
     * @param login
     * @return
     */
    public LoginDTO singleLogin(LoginVO login) {
        LoginDTO loginDTO = new LoginDTO();
        loginDTO.setAccount(login.getAccount());
        int retryCount = 0;
        int maxRetries = 3;
        String token = null;
        while (retryCount < maxRetries) {
            Map<String, String> headers = new HashMap<>();
            String url = "https://3575978705.tcrax4d8j.com/login";
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

            if (StringUtils.isNotBlank(token)) {
                // token 不为空，成功
                loginDTO.setToken(token);
                return loginDTO;
            }

            retryCount++;
            ThreadUtil.sleep(300); // 延迟重试
        }
        if (StringUtils.isBlank(token) && retryCount == maxRetries) {
            // throw new BusinessException(SystemError.USER_1001);
            log.warn("账号 {} 获取 token 失败，已达到最大重试次数", login.getAccount());
        }
        return loginDTO;
    }

    /**
     * 批量登录
     *
     * @return token
     */
    public List<LoginDTO> batchLogin(List<LoginVO> logins) {
        List<LoginDTO> results = Collections.synchronizedList(new ArrayList<>());
        Set<String> tokenSet = ConcurrentHashMap.newKeySet();

        // 线程池大小可以根据任务量调整
        ExecutorService executorService = Executors.newFixedThreadPool(10);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        logins.forEach(login -> {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                LoginDTO loginDTO;
                int retryCount = 0;
                final int maxRetries = 3;

                while (retryCount < maxRetries) {
                    loginDTO = singleLogin(login); // 调用单个登录逻辑
                    String token = loginDTO.getToken();

                    if (StringUtils.isNotBlank(token) && tokenSet.add(token)) {
                        // token 不为空且未重复，成功
                        results.add(loginDTO);
                        return;
                    }

                    retryCount++;
                    log.warn("账号 {} 的 token {} 已重复，重新尝试获取 (重试次数: {})", login.getAccount(), token, retryCount);
                }

                log.error("账号 {} 最终获取 token 失败，重试次数已达上限", login.getAccount());
                results.add(new LoginDTO(login.getAccount(), null)); // 失败标记
            }, executorService);
            futures.add(future);
        });

        // 等待所有任务完成
        futures.forEach(CompletableFuture::join);
        executorService.shutdown();
        results.forEach(result -> {
            redisson.getBucket(KeyUtil.genKey(RedisConstants.USER_TOKEN_PREFIX, result.getAccount())).set(JSONUtil.toJsonStr(result), 30, TimeUnit.DAYS);
        });

        return results;
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
    public String account(String token) {
        String url = "https://3575978705.tcrax4d8j.com/member/accounts";
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("accept-language", "zh-CN,zh;q=0.9");
        headers.put("cookie", "defaultSetting=5%2C10%2C20%2C50%2C100%2C200%2C500%2C1000; settingChecked=0; index=; index2=; oid=3a9cad1bfcc69f05fd202bb3ddbc9df05b3bc062; defaultLT=PK10JSC; page=lm; ssid1=e4ac3642c6b2ea8a51d3a12fc4994ba7; random=4671; __nxquid=nKDBJcL6SZckLHDCiGlHdK0vbTqqcA==0013; token=" + token + "");
        headers.put("priority", "u=1, i");
        headers.put("x-requested-with", "XMLHttpRequest");
        String result = HttpRequest.get(url)
                .addHeaders(headers)
                .execute().body();
        result = result.isBlank() ? null : result;
        System.out.println(result);
        return result;
    }

    /**
     * 获取期数
     *
     * @return 期数
     */
    public String period(String token, String lottery) {
        GameType gameType = GameType.getByLottery(lottery);
        String url = "https://3575978705.tcrax4d8j.com/member/period?lottery="+lottery+"&games="+gameType.getGames()+"&_="+System.currentTimeMillis();
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
     * 获取账号信息
     *
     * @return 结果
     */
    public String odds(String account, String token, String lottery) {
        GameType gameType = GameType.getByLottery(lottery);
        String url = "https://3575978705.tcrax4d8j.com/member/odds?lottery=" + lottery + "&games="+gameType.getGames()+"&_=" + System.currentTimeMillis();
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
        UserConfig userConfig = JSONUtil.toBean(JSONUtil.parseObj(redisson.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, account)).get()), UserConfig.class);
        // 动态设置代理类型
        Proxy proxy = null;
        if (BeanUtil.isNotEmpty(userConfig) && null != userConfig.getProxyType()) {
            if (userConfig.getProxyType() == 1) {
                proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(userConfig.getProxyHost(), userConfig.getProxyPort()));
            } else if (userConfig.getProxyType() == 2) {
                proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(userConfig.getProxyHost(), userConfig.getProxyPort()));
            }
            request.setProxy(proxy);

            // 设置代理认证
            if (StringUtils.isNotBlank(userConfig.getProxyUsername()) && StringUtils.isNotBlank(userConfig.getProxyPassword())) {
                request.basicProxyAuth(userConfig.getProxyUsername(), userConfig.getProxyPassword());
            }

        }
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
    public JSONObject bet(OrderVO order) {
        String url = "https://3575978705.tcrax4d8j.com/member/bet";
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("accept-language", "zh-CN,zh;q=0.9");
        headers.put("cookie", "defaultSetting=5%2C10%2C20%2C50%2C100%2C200%2C500%2C1000; settingChecked=0; index=; index2=; oid=3a9cad1bfcc69f05fd202bb3ddbc9df05b3bc062; defaultLT="+order.getLottery()+"; page=lm; token=" + order.getToken());
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

    /**
     * 获取两周报表历史流水记录
     *
     * @return 结果
     */
    public String history(TokenVo token, String lottery) {
        String url = "https://3575978705.tcrax4d8j.com/member/history";
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("accept-language", "zh-CN,zh;q=0.9");
        headers.put("cookie", "defaultSetting=5%2C10%2C20%2C50%2C100%2C200%2C500%2C1000; settingChecked=0; page=lm; index=; index2=; defaultLT="+lottery+"; token=" + token.getToken());
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
