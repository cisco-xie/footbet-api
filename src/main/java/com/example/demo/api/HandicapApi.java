package com.example.demo.api;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.date.TimeInterval;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.common.constants.RedisConstants;
import com.example.demo.common.enmu.*;
import com.example.demo.common.utils.KeyUtil;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.core.factory.WebsiteApiFactory;
import com.example.demo.core.factory.WebsiteFactoryManager;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.vo.*;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class HandicapApi {

    @Resource(name = "defaultRedissonClient")
    private RedissonClient redisson;

    @Resource(name = "businessPlatformRedissonClient")
    private RedissonClient businessPlatformRedissonClient;

    @Resource
    private WebsiteFactoryManager factoryManager;

    @Resource
    private WebsiteService websiteService;

    @Resource
    private AdminService adminService;

    @Resource
    private ConfigAccountService accountService;

    /**
     * 所有盘口账号自动登录
     */
    public void login() {
        List<AdminLoginDTO> users = adminService.getEnableUsers();
        ConcurrentHashMap<String, ExecutorService> userExecutorMap = new ConcurrentHashMap<>();

        // ✅ 定义本地内存的 retryMap，仅在当前方法内生效（不持久）
        ConcurrentHashMap<String, Integer> retryMap = new ConcurrentHashMap<>();

        for (AdminLoginDTO user : users) {
            ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
            userExecutorMap.put(user.getUsername(), executorService);

            List<String> jsonList = businessPlatformRedissonClient.getList(KeyUtil.genKey(RedisConstants.PLATFORM_WEBSITE_ALL_PREFIX, user.getUsername()));
            if (jsonList == null || jsonList.isEmpty()) {
                continue;
            }

            List<WebsiteVO> websites = jsonList.stream()
                    .map(json -> JSONUtil.toBean(json, WebsiteVO.class))
                    .filter(websiteVO -> websiteVO.getEnable() == 1)
                    .toList();

            for (WebsiteVO website : websites) {
                List<ConfigAccountVO> accounts = accountService.getAccount(user.getUsername(), website.getId());
                List<ConfigAccountVO> enabledAccounts = accounts.stream()
                        .filter(account -> account.getEnable() == 1 && account.getAutoLogin() == 1)
                        .toList();

                if (enabledAccounts.isEmpty()) {
                    continue;
                }

                try {
                    List<CompletableFuture<Void>> futures = enabledAccounts.stream()
                            .map(account -> CompletableFuture.runAsync(() ->
                                    processAccountLogin(account, user.getUsername(), website.getId(), retryMap),
                                    executorService))
                            .toList();

                    CompletableFuture<Void> allTasks = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
                    allTasks.get(60, TimeUnit.SECONDS);
                } catch (InterruptedException | ExecutionException e) {
                    log.error("执行任务时出现异常", e);
                    Thread.currentThread().interrupt();
                } catch (TimeoutException e) {
                    log.error("任务执行超时", e);
                } catch (Exception e) {
                    log.error("未知异常", e);
                }
            }
        }

        // 关闭所有用户的线程池
        userExecutorMap.forEach((username, executor) -> {
            logThreadPoolStatus((ThreadPoolExecutor) executor, "用户 " + username + " 登录线程池");
            shutdownExecutor(executor);
        });
    }

    /**
     * 根据平台用户和指定网站登录
     * 不限制网站或者账户状态是否开启
     * @param username
     * @param websiteId
     */
    public void loginByWebsite(String username, String websiteId) {
        List<ConfigAccountVO> accounts = accountService.getAccount(username, websiteId);
        if (accounts.isEmpty()) {
            return;
        }

        // 仅保留 enable = 1 的账号
        List<ConfigAccountVO> enabledAccounts = accounts.stream()
                .filter(account -> account.getEnable() != null && account.getEnable() == 1)
                .toList();

        if (enabledAccounts.isEmpty()) {
            log.info("【登录】没有启用的账号，username={}, websiteId={}", username, websiteId);
            return;
        }

        // ✅ 定义本地内存的 retryMap，仅在当前方法内生效（不持久）
        ConcurrentHashMap<String, Integer> retryMap = new ConcurrentHashMap<>();

        int cpuCoreCount = Runtime.getRuntime().availableProcessors();
        int threadPoolUserSize = Math.min(enabledAccounts.size(), cpuCoreCount * 2);
        ExecutorService executorLoginService = new ThreadPoolExecutor(
                threadPoolUserSize, // 核心线程数
                enabledAccounts.size(), // 最大线程数
                0L, TimeUnit.SECONDS, // 空闲线程超时时间
                new LinkedBlockingQueue<>(10), // 队列大小
                new ThreadFactoryBuilder().setNameFormat("loginByWebsite-pool-%d").build(),
                new ThreadPoolExecutor.AbortPolicy() // 拒绝策略：抛出异常，便于发现问题
        );

        try {
            List<CompletableFuture<Void>> futures = enabledAccounts.stream()
                    .map(account -> CompletableFuture.runAsync(() ->
                            // ✅ 将 retryMap 传入 processAccountLogin 方法
                            processAccountLogin(account, username, websiteId, retryMap),
                            executorLoginService))
                    .toList();
            // 等待所有平台用户的任务完成
            CompletableFuture<Void> allTasks = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            allTasks.get(60, TimeUnit.SECONDS); // 等待所有任务完成，设置 60 秒超时
        } catch (Exception e) {
            log.error("执行任务时出现异常", e);
        }
        logThreadPoolStatus((ThreadPoolExecutor) executorLoginService, "指定网站账户登录线程池");
        // 执行完所有任务后关闭线程池
        shutdownExecutor(executorLoginService);
    }

    /**
     * 单个登录 - 根据平台用户和指定网站账号登录
     * 不限制网站或者账户状态是否开启
     * @param username
     * @param websiteId
     */
    public void singleLogin(String username, String websiteId, String accountId) {
        ConfigAccountVO account = accountService.getAccountById(username, websiteId, accountId);
        Map<String, Integer> retryMap = new HashMap<>();
        if (account == null) {
            throw new BusinessException(SystemError.USER_1017);
        }

        try {
            processAccountLogin(account, username, websiteId, retryMap);
        } catch (Exception e) {
            log.error("执行单个登入任务时出现异常", e);
            throw new BusinessException(SystemError.USER_1006);
        }
    }

    // 运行登录工厂
    public void processAccountLogin(ConfigAccountVO account, String username, String websiteId, Map<String, Integer> retryMap) {
        TimeInterval timer = DateUtil.timer();
        String key = websiteId + ":" + username;

        // 当前失败次数
        int retryCount = retryMap.getOrDefault(key, 0);
        if (retryCount >= 5) {
            log.warn("登录失败次数达到上限（仅本次任务内），username={}, websiteId={}", username, websiteId);
            return;
        }

        WebsiteApiFactory factory = factoryManager.getFactory(websiteId);
        ApiHandler apiHandler = factory.getLoginHandler();
        if (apiHandler == null) {
            return;
        }
        JSONObject params = new JSONObject();
        params.putOpt("adminUsername", username);
        params.putOpt("websiteId", websiteId);
        // 根据不同站点传入不同的参数
        if (WebsiteType.PINGBO.getId().equals(websiteId)) {
            params.putOpt("loginId", account.getAccount());
            params.putOpt("password", account.getPassword());
        } else if (WebsiteType.ZHIBO.getId().equals(websiteId) || WebsiteType.XINBAO.getId().equals(websiteId)) {
            params.putOpt("username", account.getAccount());
            params.putOpt("password", account.getPassword());
        } else if (WebsiteType.SBO.getId().equals(websiteId)) {
            params.putOpt("username", account.getAccount());
            params.putOpt("password", account.getPassword());
        }
        JSONObject result = apiHandler.execute(account, params);
        log.info("登录账号,网站:{}, 账号:{}, 登录结果：{}", WebsiteType.getById(websiteId).getDescription(), account.getAccount(), result);
        account.setIsTokenValid(result.getBool("success") ? 1 : 0);
        account.setToken(result);
        account.setExecuteMsg(result.get("msg") + "：" + timer.interval() + " ms");
        accountService.saveAccount(username, websiteId, account);
        if (result.getBool("success")) {
            retryMap.remove(key); // 成功则清除失败记录
            // 登录成功就执行获取额度
            balanceByAccount(username, websiteId, account);
        } else {
            Integer code = result.getInt("code");
            if (code != null && code == 106) {
                // 修改密码
                retryCount++;
                retryMap.put(key, retryCount);

                log.warn("登录失败，第{}次（本次流程内），username={}, websiteId={}", retryCount, username, websiteId);

                if (retryCount >= 5) {
                    log.warn("登录失败达到上限，不再尝试。username={}, websiteId={}", username, websiteId);
                    return;
                }
                String uid = null;
                if (result.containsKey("serverresponse") && result.getJSONObject("serverresponse").containsKey("uid")) {
                    uid = result.getJSONObject("serverresponse").getStr("uid");
                }
                String accountName = null;
                if (result.containsKey("serverresponse") && result.getJSONObject("serverresponse").containsKey("username")) {
                    accountName = result.getJSONObject("serverresponse").getStr("username");
                }
                // 执行修改密码
                changePwd(username, account, websiteId, uid, accountName, retryMap, result);
            } else if (code != null && code == 109) {
                // 修改账户名
                retryCount++;
                retryMap.put(key, retryCount);

                log.warn("登录失败，第{}次（本次流程内），username={}, websiteId={}", retryCount, username, websiteId);

                if (retryCount >= 5) {
                    log.warn("登录失败达到上限，不再尝试。username={}, websiteId={}", username, websiteId);
                    return;
                }

                String uid = null;
                if (result.containsKey("serverresponse") && result.getJSONObject("serverresponse").containsKey("uid")) {
                    uid = result.getJSONObject("serverresponse").getStr("uid");
                }
                String accountName = null;
                if (result.containsKey("serverresponse") && result.getJSONObject("serverresponse").containsKey("username")) {
                    accountName = result.getJSONObject("serverresponse").getStr("username");
                }
                // 执行检测修改账户名
                checkUsername(username, account, websiteId, uid, accountName, retryMap);
            } else if (code != null && code == 110) {
                // 执行同意协议
                retryCount++;
                retryMap.put(key, retryCount);

                log.warn("登录失败，第{}次（本次流程内），username={}, websiteId={}", retryCount, username, websiteId);

                if (retryCount >= 5) {
                    log.warn("登录失败达到上限，不再尝试。username={}, websiteId={}", username, websiteId);
                    return;
                }

                // 执行同意协议
                accept(username, account, websiteId, retryMap, result);
            } else if (code != null && code == 111) {
                // 修改偏好设置
                retryCount++;
                retryMap.put(key, retryCount);

                log.warn("登录失败，第{}次（本次流程内），username={}, websiteId={}", retryCount, username, websiteId);

                if (retryCount >= 5) {
                    log.warn("登录失败达到上限，不再尝试。username={}, websiteId={}", username, websiteId);
                    return;
                }

                // 修改偏好设置
                preferences(username, websiteId, account);
            }
        }
    }

    // 运行修改密码工厂
    public void changePwd(String username, ConfigAccountVO accountVO, String websiteId, String uid, String account, Map<String, Integer> retryMap, JSONObject resultLogin) {
        TimeInterval timer = DateUtil.timer();
        // 修改密码
        String password = RandomUtil.randomString(6) + RandomUtil.randomNumbers(2);
        WebsiteApiFactory factory = factoryManager.getFactory(websiteId);
        ApiHandler apiHandler = factory.changePwd();
        if (apiHandler == null) {
            return;
        }
        JSONObject params = new JSONObject();
        params.putOpt("adminUsername", username);
        params.putOpt("websiteId", websiteId);
        // 根据不同站点传入不同的参数
        if (WebsiteType.PINGBO.getId().equals(websiteId)) {
            params.putOpt("oldPassword", accountVO.getPassword());
            params.putOpt("newPassword", password);
            params.putOpt("chgPassword", password);
            params.putAll(accountVO.getToken().getJSONObject("tokens"));
        } else if (WebsiteType.ZHIBO.getId().equals(websiteId)) {
            params.putOpt("token", "Bearer " + accountVO.getToken().getStr("token"));
            params.putOpt("oldPassword", accountVO.getPassword());
            params.putOpt("newPassword", password);
            params.putOpt("chgPassword", password);
        } else if (WebsiteType.XINBAO.getId().equals(websiteId)) {
            params.putOpt("uid", uid);
            params.putOpt("username", account);
            params.putOpt("newPassword", password);
            params.putOpt("chgPassword", password);
        } else if (WebsiteType.SBO.getId().equals(websiteId)) {
            params.putOpt("uid", uid);
            params.putOpt("username", account);
            params.putOpt("oldPassword", accountVO.getPassword());
            params.putOpt("newPassword", password);
            params.putOpt("chgPassword", password);
            params.putOpt("cookie", resultLogin.getJSONObject("token").getStr("cookie"));
        }
        JSONObject result = apiHandler.execute(accountVO, params);
        if (result != null && result.getBool("success")) {
            accountVO.setPassword(password);
            accountVO.setExecuteMsg(result.get("msg") + "：" + timer.interval() + " ms");
            accountService.saveAccount(username, websiteId, accountVO);
            // 修改成功就执行登录
            processAccountLogin(accountVO, username, websiteId, retryMap);
        }
    }

    // 运行检测账户名工厂
    public void checkUsername(String username, ConfigAccountVO accountVO, String websiteId, String uid, String account, Map<String, Integer> retryMap) {
        TimeInterval timer = DateUtil.timer();
        WebsiteApiFactory factory = factoryManager.getFactory(websiteId);
        ApiHandler apiHandler = factory.checkUsername();
        if (apiHandler == null) {
            return;
        }

        String chkName = account + "A"; // 初始尝试名
        JSONObject params = new JSONObject();
        params.putOpt("adminUsername", username);
        params.putOpt("websiteId", websiteId);

        if (WebsiteType.PINGBO.getId().equals(websiteId)) {
            log.warn("暂不支持平博网站自动修改账户名：{}", websiteId);
            return;
        } else if (WebsiteType.ZHIBO.getId().equals(websiteId)) {
            params.putOpt("token", "Bearer " + accountVO.getToken().getStr("token"));
            String prefix = accountVO.getAccount().substring(0, 6);
            String suffix = RandomUtil.randomNumbers(3);
            chkName = prefix + suffix + "A";
            params.putOpt("loginName", chkName);
        } else if (WebsiteType.XINBAO.getId().equals(websiteId)) {
            params.putOpt("uid", uid);
            params.putOpt("username", account);
            params.putOpt("chkName", chkName);
        }

        JSONObject result = apiHandler.execute(accountVO, params);

        if (result != null && result.getBool("success")) {
            // 检测成功就执行修改账户名
            changeUsername(username, accountVO, websiteId, uid, account, chkName, retryMap);
        } else {
            // 检测失败，尝试使用新用户名重试
            String randomSuffix = RandomUtil.randomNumbers(3);
            String newChkName = account + randomSuffix;
            log.warn("首次检测用户名失败，尝试使用新用户名：{}", newChkName);

            params.putOpt("chkName", newChkName);
            result = apiHandler.execute(accountVO, params);

            if (result != null && result.getBool("success")) {
                changeUsername(username, accountVO, websiteId, uid, account, newChkName, retryMap);
            } else {
                log.warn("检测用户名失败，放弃修改。account={}, 尝试用户名={}", account, newChkName);
            }
        }
    }

    // 运行修改账户名工厂
    public void changeUsername(String username, ConfigAccountVO accountVO, String websiteId, String uid, String account, String chkName, Map<String, Integer> retryMap) {
        TimeInterval timer = DateUtil.timer();
        WebsiteApiFactory factory = factoryManager.getFactory(websiteId);
        ApiHandler apiHandler = factory.changeUsername();
        if (apiHandler == null) {
            return;
        }
        JSONObject params = new JSONObject();
        params.putOpt("adminUsername", username);
        params.putOpt("websiteId", websiteId);
        // 根据不同站点传入不同的参数
        if (WebsiteType.PINGBO.getId().equals(websiteId)) {
            log.warn("暂不支持平博网站自动修改密码：{}", websiteId);
            return;
        } else if (WebsiteType.ZHIBO.getId().equals(websiteId)) {
            params.putOpt("token", "Bearer " + accountVO.getToken().getStr("token"));
            params.putOpt("loginName", chkName);
        } else if (WebsiteType.XINBAO.getId().equals(websiteId)) {
            params.putOpt("uid", uid);
            params.putOpt("username", account);
            params.putOpt("chkName", chkName);
        }
        JSONObject result = apiHandler.execute(accountVO, params);
        if (result != null && result.getBool("success")) {
            accountVO.setAccount(chkName);
            accountVO.setExecuteMsg(result.get("msg") + "：" + timer.interval() + " ms");
            accountService.saveAccount(username, websiteId, accountVO);
            // 修改成功就执行登录
            processAccountLogin(accountVO, username, websiteId, retryMap);
        }
    }

    // 运行同意协议工厂
    public void accept(String username, ConfigAccountVO account, String websiteId, Map<String, Integer> retryMap, JSONObject resultLogin) {
        // 同意协议
        WebsiteApiFactory factory = factoryManager.getFactory(websiteId);
        ApiHandler apiHandler = factory.accept();
        if (apiHandler == null) {
            return;
        }
        JSONObject params = new JSONObject();
        params.putOpt("adminUsername", username);
        params.putOpt("websiteId", websiteId);
        // 根据不同站点传入不同的参数
        if (WebsiteType.PINGBO.getId().equals(websiteId)) {
            params.putAll(account.getToken().getJSONObject("tokens"));
        } else if (WebsiteType.ZHIBO.getId().equals(websiteId)) {
            params.putOpt("token", "Bearer " + account.getToken().getStr("token"));
        } else if (WebsiteType.XINBAO.getId().equals(websiteId)) {
            log.warn("暂不支持新二网站自动同意协议：{}", websiteId);
            return;
        } else if (WebsiteType.SBO.getId().equals(websiteId)) {
            params.putOpt("cookie", resultLogin.getJSONObject("token").getStr("cookie"));
        }
        JSONObject result = apiHandler.execute(account, params);
        if (result.getBool("success")) {
            // 同意成功就执行登录
            processAccountLogin(account, username, websiteId, retryMap);
        }
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

    private void logThreadPoolStatus(ThreadPoolExecutor executor, String poolName) {
        log.info("{} 状态 - 核心线程数: {}, 最大线程数: {}, 当前线程数: {}, 活跃线程数: {}, 队列大小: {}, 已完成任务数: {}",
                poolName,
                executor.getCorePoolSize(),
                executor.getMaximumPoolSize(),
                executor.getPoolSize(),
                executor.getActiveCount(),
                executor.getQueue().size(),
                executor.getCompletedTaskCount());
    }

    /**
     * 批量获取所有用户额度信息
     */
    public void info() {
        List<AdminLoginDTO> users = adminService.getEnableUsers();
        for (AdminLoginDTO user : users) {
            List<String> jsonList = businessPlatformRedissonClient.getList(KeyUtil.genKey(RedisConstants.PLATFORM_WEBSITE_ALL_PREFIX, user.getUsername()));
            if (jsonList == null || jsonList.isEmpty()) {
                break;
            }
            // 将 List 中的 JSON 字符串反序列化为 WebSiteVO 列表
            List<WebsiteVO> websites =  jsonList.stream()
                    .map(json -> JSONUtil.toBean(json, WebsiteVO.class))
                    .filter(websiteVO -> websiteVO.getEnable() == 1)
                    .toList();
            for (WebsiteVO website : websites) {
                List<ConfigAccountVO> accounts = accountService.getAccount(user.getUsername(), website.getId());
                accounts = accounts.stream().filter(account -> account.getEnable() == 1 && account.getAutoLogin() == 1).toList();
                if (accounts.isEmpty()) {
                    break;
                }
                for (ConfigAccountVO account : accounts) {
                    if (account.getIsTokenValid() == 0) {
                        // 未登录直接跳过
                        continue;
                    }
                    TimeInterval timer = DateUtil.timer();
                    WebsiteApiFactory factory = factoryManager.getFactory(website.getId());

                    ApiHandler apiHandler = factory.getInfoHandler();
                    if (apiHandler == null) {
                        break;
                    }
                    JSONObject params = new JSONObject();
                    params.putOpt("adminUsername", user.getUsername());
                    params.putOpt("websiteId", website.getId());
                    // 根据不同站点传入不同的参数
                    if (WebsiteType.PINGBO.getId().equals(website.getId())) {
                        params.putAll(account.getToken().getJSONObject("tokens"));
                    } else if (WebsiteType.ZHIBO.getId().equals(website.getId())) {
                        params.putOpt("token", "Bearer " + account.getToken().getStr("token"));
                    } else if (WebsiteType.XINBAO.getId().equals(website.getId())) {
                        params.putAll(account.getToken().getJSONObject("serverresponse"));
                    }
                    JSONObject result = apiHandler.execute(account, params);
                    account.setBetCredit(result.getBigDecimal("betCredit"));
                    account.setExecuteMsg(result.get("msg") + "：" + timer.interval() + " ms");
                    accountService.saveAccount(user.getUsername(), website.getId(), account);
                }
            }
        };
    }

    /**
     * 指定账户更新额度
     * @param username
     * @param websiteId
     * @param account
     */
    public Object balanceByAccount(String username, String websiteId, ConfigAccountVO account) {
        if (account.getIsTokenValid() == 0) {
            // 未登录直接跳过
            return null;
        }
        WebsiteApiFactory factory = factoryManager.getFactory(websiteId);

        ApiHandler apiHandler = factory.getInfoHandler();
        if (apiHandler == null) {
            return null;
        }
        JSONObject params = new JSONObject();
        params.putOpt("adminUsername", username);
        params.putOpt("websiteId", websiteId);
        // 根据不同站点传入不同的参数
        if (WebsiteType.PINGBO.getId().equals(websiteId)) {
            params.putAll(account.getToken().getJSONObject("tokens"));
        } else if (WebsiteType.ZHIBO.getId().equals(websiteId)) {
            params.putOpt("token", "Bearer " + account.getToken().getStr("token"));
        } else if (WebsiteType.XINBAO.getId().equals(websiteId)) {
            params.putAll(account.getToken().getJSONObject("serverresponse"));
        } else if (WebsiteType.SBO.getId().equals(websiteId)) {
            params.putOpt("cookie", account.getToken().getJSONObject("token").getStr("cookie"));
        }
        JSONObject result = apiHandler.execute(account, params);
        account.setBetCredit(result.getBigDecimal("betCredit"));
        account.setExecuteMsg(result.get("msg") + "：" + result.getLong("durationMs") + " ms");
        accountService.saveAccount(username, websiteId, account);
        return result;
    }

    /**
     * 根据用户和网站获取赛事列表
     * @param username
     * @param websiteId
     * @param showType
     * @return
     */
    public Object eventList(String username, String websiteId, int showType, ConfigAccountVO accountVo) {
        WebsiteVO websiteVO = websiteService.getWebsite(username, websiteId);
        Integer oddsType = websiteVO.getOddsType();
        List<ConfigAccountVO> accounts = accountService.getAccount(username, websiteId);
        for (ConfigAccountVO account : accounts) {
            if (account.getIsTokenValid() == 0) {
                // 未登录直接跳过
                continue;
            }
            if (accountVo != null && !account.getId().equals(accountVo.getId())) {
                // 如果指定了请求用户,则必须通过指定的请求用户请求赛事列表
                continue;
            }
            WebsiteApiFactory factory = factoryManager.getFactory(websiteId);

            ApiHandler apiHandler = factory.getEventListHandler();
            if (apiHandler == null) {
                continue;
            }
            JSONObject params = new JSONObject();
            params.putOpt("adminUsername", username);
            params.putOpt("websiteId", websiteId);
            params.putOpt("showType", showType);
            try {
                // 根据不同站点传入不同的参数
                if (WebsiteType.PINGBO.getId().equals(websiteId)) {
                    params.putAll(account.getToken().getJSONObject("tokens"));
                    // 转换赔率类型
                    int oddsFormatType = 0;
                    if (oddsType == 1) {
                        // 平台设置的马来盘
                        oddsFormatType = PingBoOddsFormatType.RM.getId();
                    } else if (oddsType == 2) {
                        // 平台设置的香港盘
                        oddsFormatType = PingBoOddsFormatType.HKC.getId();
                    } else {
                        // 默认马来盘
                        oddsFormatType = PingBoOddsFormatType.RM.getId();
                    }
                    params.putOpt("oddsFormatType", oddsFormatType);
                } else if (WebsiteType.ZHIBO.getId().equals(websiteId)) {
                    params.putOpt("token", "Bearer " + account.getToken().getStr("token"));
                    // 转换赔率类型
                    int oddsFormatType = 0;
                    if (oddsType == 1) {
                        // 平台设置的马来盘
                        oddsFormatType = ZhiBoOddsFormatType.RM.getId();
                    } else if (oddsType == 2) {
                        // 平台设置的香港盘
                        oddsFormatType = ZhiBoOddsFormatType.HKC.getId();
                    } else {
                        // 默认马来盘
                        oddsFormatType = ZhiBoOddsFormatType.RM.getId();
                    }
                    params.putOpt("oddsFormatType", oddsFormatType);
                } else if (WebsiteType.XINBAO.getId().equals(websiteId)) {
                    params.putAll(account.getToken().getJSONObject("serverresponse"));
                    // 转换赔率类型
                    String oddsFormatType = "";
                    if (oddsType == 1) {
                        // 平台设置的马来盘
                        oddsFormatType = XinBaoOddsFormatType.RM.getCurrencyCode();
                    } else if (oddsType == 2) {
                        // 平台设置的香港盘
                        oddsFormatType = XinBaoOddsFormatType.HKC.getCurrencyCode();
                    } else {
                        // 默认马来盘
                        oddsFormatType = XinBaoOddsFormatType.RM.getCurrencyCode();
                    }
                    params.putOpt("oddsFormatType", oddsFormatType);
                } else if (WebsiteType.SBO.getId().equals(websiteId)) {
                    params.putOpt("token", account.getToken().getJSONObject("token").getStr("authToken"));
                    params.putOpt("oddsToken", account.getToken().getJSONObject("token").getStr("oddsToken"));
                }
            } catch (Exception e) {
                continue;
            }
            JSONObject result = apiHandler.execute(account, params);

            if (result.getBool("success")) {
                return result.get("leagues");
            }
        }
        return null;
    }

    /**
     * 根据用户和网站获取赛事列表
     * @param username
     * @param websiteId
     * @param showType
     * @return
     */
    public Object events(String username, String websiteId, int showType) {
        WebsiteVO websiteVO = websiteService.getWebsite(username, websiteId);
        Integer oddsType = websiteVO.getOddsType();
        List<ConfigAccountVO> accounts = accountService.getAccount(username, websiteId);
        for (ConfigAccountVO account : accounts) {
            if (account.getIsTokenValid() == 0) {
                // 未登录直接跳过
                continue;
            }
            WebsiteApiFactory factory = factoryManager.getFactory(websiteId);

            ApiHandler apiHandler = factory.getEventsHandler();
            if (apiHandler == null) {
                continue;
            }
            JSONObject params = new JSONObject();
            params.putOpt("adminUsername", username);
            params.putOpt("websiteId", websiteId);
            params.putOpt("showType", showType);
            try {
                // 根据不同站点传入不同的参数
                if (WebsiteType.PINGBO.getId().equals(websiteId)) {
                    params.putAll(account.getToken().getJSONObject("tokens"));
                    // 转换赔率类型
                    int oddsFormatType = 0;
                    if (oddsType == 1) {
                        // 平台设置的马来盘
                        oddsFormatType = PingBoOddsFormatType.RM.getId();
                    } else if (oddsType == 2) {
                        // 平台设置的香港盘
                        oddsFormatType = PingBoOddsFormatType.HKC.getId();
                    } else {
                        // 默认马来盘
                        oddsFormatType = PingBoOddsFormatType.RM.getId();
                    }
                    params.putOpt("oddsFormatType", oddsFormatType);
                } else if (WebsiteType.ZHIBO.getId().equals(websiteId)) {
                    params.putOpt("token", "Bearer " + account.getToken().getStr("token"));
                    // 转换赔率类型
                    int oddsFormatType = 0;
                    if (oddsType == 1) {
                        // 平台设置的马来盘
                        oddsFormatType = ZhiBoOddsFormatType.RM.getId();
                    } else if (oddsType == 2) {
                        // 平台设置的香港盘
                        oddsFormatType = ZhiBoOddsFormatType.HKC.getId();
                    } else {
                        // 默认马来盘
                        oddsFormatType = ZhiBoOddsFormatType.RM.getId();
                    }
                    params.putOpt("oddsFormatType", oddsFormatType);
                } else if (WebsiteType.XINBAO.getId().equals(websiteId)) {
                    params.putAll(account.getToken().getJSONObject("serverresponse"));
                } else if (WebsiteType.SBO.getId().equals(websiteId)) {
                    params.putOpt("token", account.getToken().getJSONObject("token").getStr("authToken"));
                }
            } catch (Exception e) {
                continue;
            }
            JSONObject result = apiHandler.execute(account, params);

            if (result.getBool("success")) {
                return result.get("leagues");
            }
        }
        return null;
    }

    // 放在类字段中，建议作为单例缓存维护
    private final Map<String, Long> accountCooldownMap = new ConcurrentHashMap<>();
    // 冷却期，单位毫秒（如2秒内不重复调用同一账号）
    private final static long cooldownMillis = 2000;
    // 类成员变量，可加 @Component 单例管理
    private final ConcurrentHashMap<String, AtomicInteger> accountIndexMap = new ConcurrentHashMap<>();

    /**
     * 根据用户和网站获取赛事列表数据-带赔率
     * @param username
     * @param websiteId
     * @return
     */
    public Object eventsOdds(String username, String websiteId, String lid, String ecid) {
        log.info("执行eventsOdds方法-获取赛事列表,平台用户:{},网站:{}", username, WebsiteType.getById(websiteId).getDescription());
        WebsiteVO websiteVO = websiteService.getWebsite(username, websiteId);
        Integer oddsType = websiteVO.getOddsType();
        List<ConfigAccountVO> accounts = accountService.getAccount(username, websiteId);
        if (CollUtil.isEmpty(accounts)) {
            return null;
        }
        // 账号数量
        int size = accounts.size();
        String key = username + ":" + websiteId;
        // 使用随机起点初始化轮询索引,避免每次都从0开始，防止短时间内让多个用户请求打在同一个账号上
        AtomicInteger indexRef = accountIndexMap.computeIfAbsent(key, k -> new AtomicInteger(RandomUtil.randomInt(size)));

        for (int i = 0; i < size; i++) {
            // int idx = Math.abs(indexRef.getAndIncrement() % size);
            // 防止 AtomicInteger 溢出
            int idx = indexRef.getAndUpdate(val -> (val + 1) % size);
            ConfigAccountVO account = accounts.get(idx);
            String accountName = account.getAccount();
            // 检查冷却时间
            long now = System.currentTimeMillis();
            long lastUsed = accountCooldownMap.getOrDefault(accountName, 0L);

            if (now - lastUsed < cooldownMillis) {
                log.info("获取赛事列表,平台用户:{},网站:{},账号 [{}] 正在冷却中，跳过", username, WebsiteType.getById(websiteId).getDescription(), accountName);
                continue;
            }

            log.info("获取赛事列表,平台用户:{},网站:{},idx:{},账号:{}", username, WebsiteType.getById(websiteId).getDescription(), idx, accountName);

            if (account.getIsTokenValid() == 0) {
                // 未登录直接跳过
                continue;
            }
            WebsiteApiFactory factory = factoryManager.getFactory(websiteId);

            ApiHandler apiHandler = factory.getEventsOddsHandler();
            if (apiHandler == null) {
                continue;
            }
            JSONObject params = new JSONObject();
            params.putOpt("adminUsername", username);
            params.putOpt("websiteId", websiteId);
            try {
                // 根据不同站点传入不同的参数
                if (WebsiteType.PINGBO.getId().equals(websiteId)) {
                    params.putAll(account.getToken().getJSONObject("tokens"));
                    // 转换赔率类型
                    int oddsFormatType = 0;
                    if (oddsType == 1) {
                        // 平台设置的马来盘
                        oddsFormatType = PingBoOddsFormatType.RM.getId();
                    } else if (oddsType == 2) {
                        // 平台设置的香港盘
                        oddsFormatType = PingBoOddsFormatType.HKC.getId();
                    } else {
                        // 默认马来盘
                        oddsFormatType = PingBoOddsFormatType.RM.getId();
                    }
                    params.putOpt("oddsFormatType", oddsFormatType);
                } else if (WebsiteType.ZHIBO.getId().equals(websiteId)) {
                    params.putOpt("token", "Bearer " + account.getToken().getStr("token"));
                    // 转换赔率类型
                    int oddsFormatType = 0;
                    if (oddsType == 1) {
                        // 平台设置的马来盘
                        oddsFormatType = ZhiBoOddsFormatType.RM.getId();
                    } else if (oddsType == 2) {
                        // 平台设置的香港盘
                        oddsFormatType = ZhiBoOddsFormatType.HKC.getId();
                    } else {
                        // 默认马来盘
                        oddsFormatType = ZhiBoOddsFormatType.RM.getId();
                    }
                    params.putOpt("oddsFormatType", oddsFormatType);
                } else if (WebsiteType.XINBAO.getId().equals(websiteId)) {
                    params.putAll(account.getToken().getJSONObject("serverresponse"));
                    params.putOpt("lid", lid);
                    params.putOpt("ecid", ecid);
                    // 转换赔率类型
                    String oddsFormatType = "";
                    if (oddsType == 1) {
                        // 平台设置的马来盘
                        oddsFormatType = XinBaoOddsFormatType.RM.getCurrencyCode();
                    } else if (oddsType == 2) {
                        // 平台设置的香港盘
                        oddsFormatType = XinBaoOddsFormatType.HKC.getCurrencyCode();
                    } else {
                        // 默认马来盘
                        oddsFormatType = XinBaoOddsFormatType.RM.getCurrencyCode();
                    }
                    params.putOpt("oddsFormatType", oddsFormatType);
                    params.putOpt("showType", ZhiBoSchedulesType.LIVESCHEDULE.getId());
                } else if (WebsiteType.SBO.getId().equals(websiteId)) {
                    params.putOpt("token", account.getToken().getJSONObject("token").getStr("authToken"));
                    params.putOpt("oddsToken", account.getToken().getJSONObject("token").getStr("oddsToken"));
                    params.putOpt("eventId", ecid);
                    // 转换赔率类型
                    String oddsFormatType = "";
                    if (oddsType == 1) {
                        // 平台设置的马来盘
                        oddsFormatType = XinBaoOddsFormatType.RM.getCurrencyCode();
                    } else if (oddsType == 2) {
                        // 平台设置的香港盘
                        oddsFormatType = XinBaoOddsFormatType.HKC.getCurrencyCode();
                    } else {
                        // 默认马来盘
                        oddsFormatType = XinBaoOddsFormatType.RM.getCurrencyCode();
                    }
                    params.putOpt("oddsFormatType", oddsFormatType);
                    params.putOpt("showType", ZhiBoSchedulesType.LIVESCHEDULE.getId());
                }
            } catch (Exception e) {
                continue;
            }
            long durationMs = 0;
            try {
                JSONObject result = apiHandler.execute(account, params);
                log.info("获取赛事列表,平台用户:{},网站:{}, 账号:{}, code:{}, success:{}", username, WebsiteType.getById(websiteId).getDescription(), accountName, result.get("code"), result.getBool("success"));
                // ✅ 更新调用时间（不论成功与否）
                accountCooldownMap.put(accountName, System.currentTimeMillis());
                if (result.getBool("success") && result.get("leagues") != null) {
                    log.info("获取赛事列表,平台用户:{},网站:{}, 账号:{}, lid:{}, ecid:{} 获取赛事成功", username, WebsiteType.getById(websiteId).getDescription(), accountName, lid, ecid);
                    return result.get("leagues");
                } else {
                    if (result.containsKey("code") && result.getInt("code") == 429) {
                        // 被盘口限流了，再延迟个500毫秒
                        log.info("获取赛事列表,平台用户:{},网站:{}, 账号 [{}] 被限流，lid:{}, ecid:{}，延迟等待", username, WebsiteType.getById(websiteId).getDescription(), accountName, lid, ecid);
                        Thread.sleep(500);
                    }
                }
                durationMs = result.containsKey("durationMs") ? result.getLong("durationMs") : 0;
                log.info("获取赛事列表,平台用户:{},网站:{}, 账号:{}, lid:{}, ecid:{}, 获取赛事失败,获取结果:{}", username, WebsiteType.getById(websiteId).getDescription(), accountName, lid, ecid, result);
            } catch (Exception e) {
                log.error("获取赛事列表,平台用户:{},网站:{}, 账号 {} 获取赛事异常", username, WebsiteType.getById(websiteId).getDescription(), accountName, e);
            } finally {
                log.info("获取赛事列表,平台用户:{},网站:{}, 账号:{}, lid:{}, ecid:{} 赔率 耗时: {}ms", username, WebsiteType.getById(websiteId).getDescription(), accountName, lid, ecid, durationMs);
            }
        }
        return null;
    }

    /**
     * 根据用户和网站获取指定赛事详情
     * @param username
     * @param websiteId
     * @return
     */
    public Object eventDetail(String username, String websiteId) {
        List<ConfigAccountVO> accounts = accountService.getAccount(username, websiteId);
        for (ConfigAccountVO account : accounts) {
            if (account.getIsTokenValid() == 0) {
                // 未登录直接跳过
                continue;
            }
            WebsiteApiFactory factory = factoryManager.getFactory(websiteId);

            ApiHandler apiHandler = factory.getEventsHandler();
            if (apiHandler == null) {
                continue;
            }
            JSONObject params = new JSONObject();
            params.putOpt("adminUsername", username);
            params.putOpt("websiteId", websiteId);
            // 根据不同站点传入不同的参数
            if (WebsiteType.PINGBO.getId().equals(websiteId)) {
                params.putAll(account.getToken().getJSONObject("tokens"));
                params.putOpt("me", "");
            } else if (WebsiteType.ZHIBO.getId().equals(websiteId)) {
                params.putOpt("token", "Bearer " + account.getToken().getStr("token"));
            } else if (WebsiteType.XINBAO.getId().equals(websiteId)) {
                params.putAll(account.getToken().getJSONObject("serverresponse"));
            }
            JSONObject result = apiHandler.execute(account, params);

            if (result.getBool("success")) {
                return result.get("leagues");
            }
        }
        return null;
    }

    /**
     * 根据用户和网站获取指定赛事赔率详情
     * @param username
     * @param websiteId
     * @return
     */
    public Object oddsInfo(String username, String websiteId, String lid, String ecid) {
        List<ConfigAccountVO> accounts = accountService.getAccount(username, websiteId);
        WebsiteVO websiteVO = websiteService.getWebsite(username, websiteId);
        Integer oddsType = websiteVO.getOddsType();
        for (ConfigAccountVO account : accounts) {
            if (account.getIsTokenValid() == 0) {
                // 未登录直接跳过
                continue;
            }
            WebsiteApiFactory factory = factoryManager.getFactory(websiteId);

            ApiHandler apiHandler = factory.getEventsOddsInfoHandler();
            if (apiHandler == null) {
                continue;
            }
            JSONObject params = new JSONObject();
            params.putOpt("adminUsername", username);
            params.putOpt("websiteId", websiteId);
            // 根据不同站点传入不同的参数
            if (WebsiteType.PINGBO.getId().equals(websiteId)) {
                params.putAll(account.getToken().getJSONObject("tokens"));
                params.putOpt("me", "");
            } else if (WebsiteType.ZHIBO.getId().equals(websiteId)) {
                params.putOpt("token", "Bearer " + account.getToken().getStr("token"));
            } else if (WebsiteType.XINBAO.getId().equals(websiteId)) {
                params.putAll(account.getToken().getJSONObject("serverresponse"));
                params.putOpt("lid", lid);
                params.putOpt("ecid", ecid);
                // 转换赔率类型
                String oddsFormatType = "";
                if (oddsType == 1) {
                    // 平台设置的马来盘
                    oddsFormatType = XinBaoOddsFormatType.RM.getCurrencyCode();
                } else if (oddsType == 2) {
                    // 平台设置的香港盘
                    oddsFormatType = XinBaoOddsFormatType.HKC.getCurrencyCode();
                } else {
                    // 默认马来盘
                    oddsFormatType = XinBaoOddsFormatType.RM.getCurrencyCode();
                }
                params.putOpt("oddsFormatType", oddsFormatType);
                params.putOpt("showType", ZhiBoSchedulesType.LIVESCHEDULE.getId());
            }
            JSONObject result = apiHandler.execute(account, params);

            if (result.getBool("success")) {
                return result.get("leagues");
            }
        }
        return null;
    }

    /**
     * 指定网站和账户获取账户账目
     * @param username
     * @param websiteId
     * @return
     */
    public Object statement(String username, String websiteId, String accountId) {
        ConfigAccountVO account = accountService.getAccountById(username, websiteId, accountId);
        if (account.getIsTokenValid() == 0) {
            // 未登录直接跳过
            return null;
        }
        WebsiteApiFactory factory = factoryManager.getFactory(websiteId);

        ApiHandler apiHandler = factory.getStatementsHandler();
        if (apiHandler == null) {
            return null;
        }
        JSONObject params = new JSONObject();
        params.putOpt("adminUsername", username);
        params.putOpt("websiteId", websiteId);
        // 根据不同站点传入不同的参数
        if (WebsiteType.PINGBO.getId().equals(websiteId)) {
            params.putAll(account.getToken().getJSONObject("tokens"));
        } else if (WebsiteType.ZHIBO.getId().equals(websiteId)) {
            params.putOpt("token", "Bearer " + account.getToken().getStr("token"));
        } else if (WebsiteType.XINBAO.getId().equals(websiteId)) {
            params.putAll(account.getToken().getJSONObject("serverresponse"));
        } else if (WebsiteType.SBO.getId().equals(websiteId)) {
            params.putOpt("cookie", account.getToken().getJSONObject("token").getStr("cookie"));
        }
        JSONObject result = apiHandler.execute(account, params);

        if (result.getBool("success")) {
            return result.get("data");
        }
        return null;
    }

    /**
     * 指定网站和账户获取账户未结算投注
     * @param username
     * @param websiteId
     * @return
     */
    public Object unsettled(String username, String websiteId, String accountId) {
        ConfigAccountVO account = accountService.getAccountById(username, websiteId, accountId);
        if (account.getIsTokenValid() == 0) {
            // 未登录直接跳过
            return null;
        }
        WebsiteApiFactory factory = factoryManager.getFactory(websiteId);

        ApiHandler apiHandler = factory.getBetUnsettledHandler();
        if (apiHandler == null) {
            return null;
        }
        JSONObject params = new JSONObject();
        params.putOpt("adminUsername", username);
        params.putOpt("websiteId", websiteId);
        // 根据不同站点传入不同的参数
        if (WebsiteType.PINGBO.getId().equals(websiteId)) {
            params.putOpt("s", "OPEN");     // 未结算
            params.putOpt("type", "EVENT"); // 未结算
            params.putAll(account.getToken().getJSONObject("tokens"));
        } else if (WebsiteType.ZHIBO.getId().equals(websiteId)) {
            params.putOpt("token", "Bearer " + account.getToken().getStr("token"));
        } else if (WebsiteType.XINBAO.getId().equals(websiteId)) {
            params.putAll(account.getToken().getJSONObject("serverresponse"));
        } else if (WebsiteType.SBO.getId().equals(websiteId)) {
            params.putOpt("cookie", account.getToken().getJSONObject("token").getStr("cookie"));
        }
        JSONObject result = apiHandler.execute(account, params);

        if (result.getBool("success")) {
            return result.get("data");
        }
        return null;
    }

    /**
     * 指定网站和账户获取账户已结算投注
     * @param username
     * @param websiteId
     * @return
     */
    public Object settled(String username, String websiteId, String accountId, String date) {
        ConfigAccountVO account = accountService.getAccountById(username, websiteId, accountId);
        if (account.getIsTokenValid() == 0) {
            // 未登录直接跳过
            return null;
        }
        WebsiteApiFactory factory = factoryManager.getFactory(websiteId);

        ApiHandler apiHandler = factory.getBetSettledHandler();
        if (apiHandler == null) {
            return null;
        }
        if (StringUtils.isBlank(date)) {
            date = LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATE_PATTERN);
        }
        JSONObject params = new JSONObject();
        params.putOpt("adminUsername", username);
        params.putOpt("websiteId", websiteId);
        // 根据不同站点传入不同的参数
        if (WebsiteType.PINGBO.getId().equals(websiteId)) {
            params.putOpt("date", date);
            params.putOpt("s", "SETTLED");  // 已结算
            params.putOpt("type", "WAGER"); // 已结算
            params.putAll(account.getToken().getJSONObject("tokens"));
        } else if (WebsiteType.ZHIBO.getId().equals(websiteId)) {
            params.putOpt("token", "Bearer " + account.getToken().getStr("token"));
        } else if (WebsiteType.XINBAO.getId().equals(websiteId)) {
            params.putOpt("date", date);
            params.putAll(account.getToken().getJSONObject("serverresponse"));
        } else if (WebsiteType.SBO.getId().equals(websiteId)) {
            params.putOpt("cookie", account.getToken().getJSONObject("token").getStr("cookie"));
        }
        JSONObject result = apiHandler.execute(account, params);

        if (result.getBool("success")) {
            return result.get("data");
        }
        return null;
    }

    /**
     * 偏好设置
     * @param username
     * @param websiteId
     * @return
     */
    public Object preferences(String username, String websiteId, ConfigAccountVO accountVO) {
        WebsiteVO websiteVO = websiteService.getWebsite(username, websiteId);
        Integer oddsType = websiteVO.getOddsType();
        List<ConfigAccountVO> accounts = accountService.getAccount(username, websiteId);
        for (ConfigAccountVO account : accounts) {
            if (accountVO != null && !accountVO.getId().equals(account.getId())) {
                continue;
            }
            if (accountVO == null && account.getIsTokenValid() == 0) {
                // 指定的账号为空并且列表中的账号未登录直接跳过
                continue;
            }
            WebsiteApiFactory factory = factoryManager.getFactory(websiteId);

            ApiHandler apiHandler = factory.preferences();
            if (apiHandler == null) {
                continue;
            }
            JSONObject params = new JSONObject();
            params.putOpt("adminUsername", username);
            params.putOpt("websiteId", websiteId);
            // 根据不同站点传入不同的参数
            if (WebsiteType.PINGBO.getId().equals(websiteId)) {
                params.putAll(account.getToken().getJSONObject("tokens"));
                // 转换赔率类型
                int oddsFormatType = 0;
                if (oddsType == 1) {
                    // 平台设置的马来盘
                    oddsFormatType = PingBoOddsFormatType.RM.getId();
                } else if (oddsType == 2) {
                    // 平台设置的香港盘
                    oddsFormatType = PingBoOddsFormatType.HKC.getId();
                } else {
                    // 默认马来盘
                    oddsFormatType = PingBoOddsFormatType.RM.getId();
                }
                params.putOpt("oddsFormatType", oddsFormatType);
            } else if (WebsiteType.ZHIBO.getId().equals(websiteId)) {
                params.putOpt("token", "Bearer " + account.getToken().getStr("token"));
                // 转换赔率类型
                int oddsFormatType = 0;
                if (oddsType == 1) {
                    // 平台设置的马来盘
                    oddsFormatType = ZhiBoOddsFormatType.RM.getId();
                } else if (oddsType == 2) {
                    // 平台设置的香港盘
                    oddsFormatType = ZhiBoOddsFormatType.HKC.getId();
                } else {
                    // 默认马来盘
                    oddsFormatType = ZhiBoOddsFormatType.RM.getId();
                }
                params.putOpt("oddsFormatType", oddsFormatType);
            } else if (WebsiteType.XINBAO.getId().equals(websiteId)) {
                params.putAll(account.getToken().getJSONObject("serverresponse"));
                // 转换赔率类型
                String oddsFormatType;
                if (oddsType == 1) {
                    // 平台设置的马来盘
                    oddsFormatType = XinBaoOddsFormatType.RM.getCurrencyCode();
                } else if (oddsType == 2) {
                    // 平台设置的香港盘
                    oddsFormatType = XinBaoOddsFormatType.HKC.getCurrencyCode();
                } else {
                    // 默认马来盘
                    oddsFormatType = XinBaoOddsFormatType.RM.getCurrencyCode();
                }
                params.putOpt("oddsFormatType", oddsFormatType);
            }
            JSONObject result = apiHandler.execute(account, params);

            if (result.getBool("success")) {
                return result;
            }
        }
        return null;
    }

    /**
     * 投注前下单预览 - 目前平博和新二网站需要此前置操作
     * @param username
     * @param websiteId
     * @param odds
     * @return
     */
    public Object betPreview(String username, String websiteId, JSONObject odds) {
        WebsiteVO websiteVO = websiteService.getWebsite(username, websiteId);
        Integer oddsType = websiteVO.getOddsType();
        List<ConfigAccountVO> accounts = accountService.getAccount(username, websiteId);
        // 账号数量
        int size = accounts.size();
        String key = username + ":" + websiteId;
        // 使用随机起点初始化轮询索引,避免每次都从0开始，防止短时间内让多个用户请求打在同一个账号上
        AtomicInteger indexRef = accountIndexMap.computeIfAbsent(key, k -> new AtomicInteger(RandomUtil.randomInt(size)));

        for (int i = 0; i < size; i++) {
            int idx = Math.abs(indexRef.getAndIncrement() % size);
            ConfigAccountVO account = accounts.get(idx);
            log.info("获取赛事列表,网站:{},idx:{},账号:{}", WebsiteType.getById(websiteId).getDescription(), idx, account.getAccount());

            if (account.getIsTokenValid() == 0) {
                // 未登录直接跳过
                continue;
            }
            WebsiteApiFactory factory = factoryManager.getFactory(websiteId);

            ApiHandler apiHandler = factory.betPreview();
            if (apiHandler == null) {
                continue;
            }
            JSONObject params = new JSONObject();
            params.putOpt("adminUsername", username);
            params.putOpt("websiteId", websiteId);
            try {
                // 根据不同站点传入不同的参数
                if (WebsiteType.PINGBO.getId().equals(websiteId)) {
                    params.putAll(account.getToken().getJSONObject("tokens"));
                    params.putOpt("oddsId", odds.getStr("oddsId"));
                    params.putOpt("selectionId", odds.getStr("selectionId"));
                    // 转换赔率类型
                    int oddsFormatType = 0;
                    if (oddsType == 1) {
                        // 平台设置的马来盘
                        oddsFormatType = PingBoOddsFormatType.RM.getId();
                    } else if (oddsType == 2) {
                        // 平台设置的香港盘
                        oddsFormatType = PingBoOddsFormatType.HKC.getId();
                    } else {
                        // 默认马来盘
                        oddsFormatType = PingBoOddsFormatType.RM.getId();
                    }
                    params.putOpt("oddsFormatType", oddsFormatType);
                } else if (WebsiteType.ZHIBO.getId().equals(websiteId)) {
                    params.putOpt("token", "Bearer " + account.getToken().getStr("token"));
                    params.putOpt("marketSelectionId", odds.getStr("marketSelectionId"));
                    // 转换赔率类型
                    int oddsFormatType = 0;
                    if (oddsType == 1) {
                        // 平台设置的马来盘
                        oddsFormatType = ZhiBoOddsFormatType.RM.getId();
                    } else if (oddsType == 2) {
                        // 平台设置的香港盘
                        oddsFormatType = ZhiBoOddsFormatType.HKC.getId();
                    } else {
                        // 默认马来盘
                        oddsFormatType = ZhiBoOddsFormatType.RM.getId();
                    }
                    params.putOpt("oddsFormatType", oddsFormatType);
                } else if (WebsiteType.XINBAO.getId().equals(websiteId)) {
                    params.putAll(account.getToken().getJSONObject("serverresponse"));

                    params.putOpt("gid", odds.getStr("gid"));
                    params.putOpt("gtype", odds.getStr("gtype"));
                    params.putOpt("wtype", odds.getStr("wtype"));
                    params.putOpt("choseTeam", odds.getStr("choseTeam"));

                    // 转换赔率类型
                    String oddsFormatType;
                    if (oddsType == 1) {
                        // 平台设置的马来盘
                        oddsFormatType = XinBaoOddsFormatType.RM.getCurrencyCode();
                    } else if (oddsType == 2) {
                        // 平台设置的香港盘
                        oddsFormatType = XinBaoOddsFormatType.HKC.getCurrencyCode();
                    } else {
                        // 默认马来盘
                        oddsFormatType = XinBaoOddsFormatType.RM.getCurrencyCode();
                    }
                    params.putOpt("oddsFormatType", oddsFormatType);
                } else if (WebsiteType.SBO.getId().equals(websiteId)) {

                    params.putOpt("cookie", account.getToken().getJSONObject("token").getStr("cookie"));
                    params.putOpt("eventId", odds.getStr("eventId"));
                    params.putOpt("isLive", odds.getStr("isLive"));
                    params.putOpt("marketType", odds.getStr("marketType"));
                    params.putOpt("oddsId", odds.getStr("oddsId"));
                    params.putOpt("option", odds.getStr("option"));

                    // 转换赔率类型
                    String oddsFormatType;
                    if (oddsType == 1) {
                        // 平台设置的马来盘
                        oddsFormatType = XinBaoOddsFormatType.RM.getCurrencyCode();
                    } else if (oddsType == 2) {
                        // 平台设置的香港盘
                        oddsFormatType = XinBaoOddsFormatType.HKC.getCurrencyCode();
                    } else {
                        // 默认马来盘
                        oddsFormatType = XinBaoOddsFormatType.RM.getCurrencyCode();
                    }
                    params.putOpt("oddsFormatType", oddsFormatType);
                }
            } catch (Exception e) {
                continue;
            }
            JSONObject result = apiHandler.execute(account, params);

            // 保存记录投注账号id
            result.putOpt("account", account.getAccount());
            result.putOpt("accountId", account.getId());
            return result;
            /*if (result.getBool("success")) {
                // 保存记录投注账号id
                result.putOpt("account", account.getAccount());
                result.putOpt("accountId", account.getId());
                return result;
            }*/
        }
        return null;
    }

    /**
     * 投注
     * @param username
     * @param websiteId
     * @return
     */
    public Object bet(String username, String websiteId, JSONObject odds, JSONObject betPreviewInfo, JSONObject betPreviewJson) {
        WebsiteVO websiteVO = websiteService.getWebsite(username, websiteId);
        Integer oddsType = websiteVO.getOddsType();
        List<ConfigAccountVO> accounts = accountService.getAccount(username, websiteId);
        // 账号数量
        int size = accounts.size();
        String key = username + ":" + websiteId;
        // 使用随机起点初始化轮询索引,避免每次都从0开始，防止短时间内让多个用户请求打在同一个账号上
        AtomicInteger indexRef = accountIndexMap.computeIfAbsent(key, k -> new AtomicInteger(RandomUtil.randomInt(size)));

        String targeAccount = betPreviewJson.getStr("account");
        for (int i = 0; i < size; i++) {
            int idx = Math.abs(indexRef.getAndIncrement() % size);
            ConfigAccountVO account = accounts.get(idx);
            if (!account.getAccount().equals(targeAccount)) {
                log.info("投注操作,网站:{},idx:{},账号:{}不是投注预览账号:{}", WebsiteType.getById(websiteId).getDescription(), idx, account.getAccount(), betPreviewJson.getStr("account"));
                continue;
            }
            log.info("投注操作,网站:{},idx:{},账号:{}", WebsiteType.getById(websiteId).getDescription(), idx, account.getAccount());

            if (account.getEnable() == 0 || account.getIsTokenValid() == 0) {
                // 未启用或未登录直接跳过
                continue;
            }
            WebsiteApiFactory factory = factoryManager.getFactory(websiteId);

            ApiHandler apiHandler = factory.bet();
            if (apiHandler == null) {
                continue;
            }
            double multiple = account.getMultiple();
            JSONObject params = new JSONObject();
            params.putOpt("adminUsername", username);
            params.putOpt("websiteId", websiteId);
            BigDecimal maxBet = BigDecimal.ZERO;
            BigDecimal minBet = BigDecimal.ZERO;
            BigDecimal betAmount = BigDecimal.ZERO;
            try {
                // 根据不同站点传入不同的参数
                if (WebsiteType.PINGBO.getId().equals(websiteId)) {
                    params.putAll(account.getToken().getJSONObject("tokens"));
                    // Object betPreview = betPreview(username, websiteId, odds);
                    // 转换赔率类型
                    int oddsFormatType = 0;
                    if (oddsType == 1) {
                        // 平台设置的马来盘
                        oddsFormatType = PingBoOddsFormatType.RM.getId();
                    } else if (oddsType == 2) {
                        // 平台设置的香港盘
                        oddsFormatType = PingBoOddsFormatType.HKC.getId();
                    } else {
                        // 默认马来盘
                        oddsFormatType = PingBoOddsFormatType.RM.getId();
                    }
                    params.putOpt("oddsFormatType", oddsFormatType);
                    JSONArray data = betPreviewJson.getJSONArray("data");
                    if (data == null || data.isEmpty()) {
                        return null;
                    }
                    // 通过当前盘口账户的倍数计算实际投注金额
                    BigDecimal stake = odds.getBigDecimal("stake");

                    betAmount = stake
                            .multiply(BigDecimal.valueOf(multiple))         // 相乘
                            .setScale(2, RoundingMode.HALF_UP);    // 保留两位小数，四舍五入

                    JSONArray selections = new JSONArray();
                    for (Object obj : data) {
                        JSONObject objJson = JSONUtil.parseObj(obj);
                        JSONObject betInfo = new JSONObject();
                        JSONObject selection = new JSONObject();
                        selection.putOpt("stake", betAmount);
                        selection.putOpt("odds", objJson.getStr("odds"));
                        selection.putOpt("oddsId", objJson.getStr("oddsId"));
                        selection.putOpt("selectionId", objJson.getStr("selectionId"));
                        // 保存级别的投注信息联赛、球队等信息,如果投注失败就可以从这里获取投注记录
                        betInfo.putOpt("league", objJson.getStr("league"));
                        betInfo.putOpt("team", objJson.getStr("homeTeam") + " -vs- " + objJson.getStr("awayTeam"));
                        betInfo.putOpt("marketTypeName", "");
                        betInfo.putOpt("marketName", objJson.getStr("selection"));
                        betInfo.putOpt("odds", objJson.getStr("selection") + " " + objJson.getStr("handicap"));
                        betInfo.putOpt("handicap", objJson.getStr("handicap"));
                        betInfo.putOpt("amount", odds.getStr("stake"));
                        betInfo.putOpt("betTeamName", betPreviewInfo.getStr("betTeamName"));
                        selection.putOpt("betInfo", betInfo);
                        selections.add(selection);
                        maxBet = objJson.getBigDecimal("maxStake");
                        minBet = objJson.getBigDecimal("minStake");
                    }
                    params.putOpt("selections", selections);
                } else if (WebsiteType.ZHIBO.getId().equals(websiteId)) {
                    params.putOpt("token", "Bearer " + account.getToken().getStr("token"));
                    // 通过当前盘口账户的倍数计算实际投注金额
                    BigDecimal stake = odds.getBigDecimal("stake");

                    betAmount = stake
                            .multiply(BigDecimal.valueOf(multiple))         // 相乘
                            .setScale(0, RoundingMode.HALF_UP);    // 保留整数，四舍五入

                    JSONObject data = betPreviewJson.getJSONObject("data");
                    JSONObject betTicket = data.getJSONObject("betTicket");
                    JSONObject betInfo = new JSONObject();
                    params.putOpt("stake", betAmount);
                    params.putOpt("odds", betTicket.getStr("odds"));
                    params.putOpt("decimalOdds", betTicket.getStr("decimalOdds"));
                    params.putOpt("handicap", betTicket.getStr("handicap"));
                    params.putOpt("score", betTicket.getStr("score"));
                    params.putOpt("oddsFormatId", betTicket.getStr("oddsFormatId"));
                    params.putOpt("marketSelectionId", betTicket.getStr("marketSelectionId"));
                    // 保存级别的投注信息联赛、球队等信息,如果投注失败就可以从这里获取投注记录
                    betInfo.putOpt("league", data.getStr("leagueName"));
                    betInfo.putOpt("team", data.getStr("eventName"));
                    betInfo.putOpt("marketTypeName", data.getStr("marketTypeName"));
                    betInfo.putOpt("marketName", data.getStr("name"));
                    betInfo.putOpt("odds", data.getStr("name") + " " + betTicket.getStr("handicap"));
                    betInfo.putOpt("handicap", data.getStr("handicap"));
                    betInfo.putOpt("amount", betAmount);
                    params.putOpt("betInfo", betInfo);
                } else if (WebsiteType.XINBAO.getId().equals(websiteId)) {
                    params.putAll(account.getToken().getJSONObject("serverresponse"));
                    // 通过当前盘口账户的倍数计算实际投注金额
                    BigDecimal golds = odds.getBigDecimal("golds");

                    betAmount = golds
                            .multiply(BigDecimal.valueOf(multiple))         // 相乘
                            .setScale(0, RoundingMode.HALF_UP);    // 保留整数，四舍五入

                    JSONObject betInfo = new JSONObject();
                    // JSONObject betPreviewJson = JSONUtil.parseObj(betPreview);
                    JSONObject serverresponse = betPreviewJson.getJSONObject("data");
                    params.putOpt("gid", odds.getStr("gid"));
                    params.putOpt("golds", betAmount);
                    params.putOpt("gtype", odds.getStr("gtype"));
                    params.putOpt("wtype", odds.getStr("wtype"));
                    params.putOpt("rtype", odds.getStr("rtype"));
                    params.putOpt("choseTeam", odds.getStr("choseTeam"));
                    params.putOpt("ioratio", serverresponse.getStr("ioratio"));
                    params.putOpt("con", serverresponse.getStr("con"));
                    params.putOpt("ratio", serverresponse.getStr("ratio"));
                    params.putOpt("autoOdd", odds.getStr("autoOdd"));

                    // 保存级别的投注信息联赛、球队等信息,如果投注失败就可以从这里获取投注记录
                    String fastCheck = serverresponse.getStr("fast_check");
                    String marketName = "";
                    String marketTypeName = "";
                    if (fastCheck.contains("REH")) {
                        marketName = serverresponse.getStr("team_name_h");
                        marketTypeName = "让球盘";
                    } else if (fastCheck.contains("REC")) {
                        marketName = serverresponse.getStr("team_name_c");
                        marketTypeName = "让球盘";
                    } else if (fastCheck.contains("ROUC")) {
                        marketName = "大盘";
                        marketTypeName = "大小盘";
                    } else if (fastCheck.contains("ROUH")) {
                        marketName = "小盘";
                        marketTypeName = "大小盘";
                    }
                    betInfo.putOpt("league", serverresponse.getStr("league_name"));
                    betInfo.putOpt("team", serverresponse.getStr("team_name_h") + " -vs- " + serverresponse.getStr("team_name_c"));
                    betInfo.putOpt("marketTypeName", marketTypeName);
                    betInfo.putOpt("marketName", marketName);
                    betInfo.putOpt("odds", marketName + " " + serverresponse.getStr("spread"));
                    betInfo.putOpt("handicap", serverresponse.getStr("spread"));
                    betInfo.putOpt("amount", betAmount);
                    betInfo.putOpt("betTeamName", betPreviewInfo.getStr("betTeamName"));
                    params.putOpt("betInfo", betInfo);

                    // 转换赔率类型
                    String oddsFormatType;
                    if (oddsType == 1) {
                        // 平台设置的马来盘
                        oddsFormatType = XinBaoOddsFormatType.RM.getCurrencyCode();
                    } else if (oddsType == 2) {
                        // 平台设置的香港盘
                        oddsFormatType = XinBaoOddsFormatType.HKC.getCurrencyCode();
                    } else {
                        // 默认马来盘
                        oddsFormatType = XinBaoOddsFormatType.RM.getCurrencyCode();
                    }
                    params.putOpt("oddsFormatType", oddsFormatType);
                    maxBet = serverresponse.getBigDecimal("gold_gmax");
                    minBet = serverresponse.getBigDecimal("gold_gmin");
                } else if (WebsiteType.SBO.getId().equals(websiteId)) {

                    // 通过当前盘口账户的倍数计算实际投注金额
                    BigDecimal stake = odds.getBigDecimal("stake");

                    betAmount = stake
                            .multiply(BigDecimal.valueOf(multiple))         // 相乘
                            .setScale(2, RoundingMode.HALF_UP);    // 保留两位小数，四舍五入

                    JSONObject oddsInfo = betPreviewJson.getJSONObject("data").getJSONObject("oddsInfo");
                    String firstKey = oddsInfo.keySet().iterator().next();  // 拿第一个 key
                    JSONObject oddInfo = oddsInfo.getJSONObject(firstKey);

                    params.putOpt("cookie", account.getToken().getJSONObject("token").getStr("cookie"));
                    params.putOpt("eventId", odds.getStr("eventId"));
                    params.putOpt("isLive", odds.getStr("isLive"));
                    params.putOpt("liveHomeScore", oddInfo.getStr("liveHomeScore"));
                    params.putOpt("liveAwayScore", oddInfo.getStr("liveAwayScore"));
                    params.putOpt("marketType", odds.getStr("marketType"));
                    params.putOpt("oddsId", oddInfo.getStr("oddsId"));
                    params.putOpt("option", odds.getStr("option"));
                    params.putOpt("point", oddInfo.getStr("point"));
                    params.putOpt("stake", betAmount);
                    params.putOpt("uid", oddInfo.getStr("uid"));
                    params.putOpt("betInfo", betPreviewInfo);

                    // 转换赔率类型
                    String oddsFormatType;
                    if (oddsType == 1) {
                        // 平台设置的马来盘
                        oddsFormatType = XinBaoOddsFormatType.RM.getCurrencyCode();
                    } else if (oddsType == 2) {
                        // 平台设置的香港盘
                        oddsFormatType = XinBaoOddsFormatType.HKC.getCurrencyCode();
                    } else {
                        // 默认马来盘
                        oddsFormatType = XinBaoOddsFormatType.RM.getCurrencyCode();
                    }
                    params.putOpt("oddsFormatType", oddsFormatType);
                    maxBet = betPreviewJson.getJSONObject("data").getBigDecimal("maxBet");
                    minBet = betPreviewJson.getJSONObject("data").getBigDecimal("minBet");
                }
            } catch (Exception e) {
                log.info("配置投注参数失败, 网站: {}",  WebsiteType.getById(websiteId).getDescription(), e);
                continue;
            }

            // ================== 投注与拆分逻辑 ==================
            List<JSONObject> splitResults = new ArrayList<>();
            List<JSONObject> failedSplits = new ArrayList<>();

            if (betAmount.compareTo(maxBet) <= 0) {
                log.info("投注金额小于等于最大投注金额，直接投注，网站:{}，params={}", WebsiteType.getById(websiteId).getDescription(), params);
                JSONObject result = apiHandler.execute(account, params);
                if (result != null && result.getBool("success")) {
                    result.putOpt("account", account.getAccount());
                    result.putOpt("accountId", account.getId());
                    splitResults.add(result);
                } else {
                    failedSplits.add(params);
                }
            } else {
                int splitCount = betAmount.divide(maxBet, 0, RoundingMode.UP).intValue();
                for (int s = 0; s < splitCount; s++) {
                    BigDecimal thisAmount = (s < splitCount - 1)
                            ? maxBet
                            : betAmount.subtract(maxBet.multiply(BigDecimal.valueOf(splitCount - 1)));

                    JSONObject paramsCopy = JSONUtil.parseObj(params.toString());
                    switch (WebsiteType.getById(websiteId)) {
                        case PINGBO -> {
                            JSONArray selections = paramsCopy.getJSONArray("selections");
                            for (Object obj : selections) {
                                JSONObject sel = (JSONObject) obj;
                                sel.putOpt("stake", thisAmount);
                            }
                        }
                        case ZHIBO -> paramsCopy.putOpt("stake", thisAmount);
                        case XINBAO -> paramsCopy.putOpt("golds", thisAmount);
                        case SBO -> paramsCopy.putOpt("stake", thisAmount);
                    }
                    log.info("投注金额大于最大投注金额，网站:{} ,账号 {} 第 {} 次拆分投注，金额 {}, paramsCopy={}", WebsiteType.getById(websiteId).getDescription(), account.getAccount(), s + 1, thisAmount, paramsCopy);
                    JSONObject result = apiHandler.execute(account, paramsCopy);
                    if (result == null) {
                        log.info("网站:{} 账号 {} 第 {} 次拆分投注失败，金额 {}", WebsiteType.getById(websiteId).getDescription(), account.getAccount(), s + 1, thisAmount);
                        paramsCopy.putOpt("splitIndex", s + 1);
                        paramsCopy.putOpt("splitAmount", thisAmount);
                        failedSplits.add(paramsCopy);
                        continue;
                    }
                    result.putOpt("splitIndex", s + 1);
                    result.putOpt("splitAmount", thisAmount);
                    result.putOpt("account", account.getAccount());
                    result.putOpt("accountId", account.getId());
                    splitResults.add(result);
                }
            }

            // ================== 失败补投逻辑 只有拆分投注的才进行补单 ==================
            if (betAmount.compareTo(maxBet) > 0 && !failedSplits.isEmpty()) {
                log.info("网站:{} 账号 {} 投注失败，开始补投，失败次数 {}", WebsiteType.getById(websiteId).getDescription(), account.getAccount(), failedSplits.size());
                for (JSONObject failedParam : failedSplits) {
                    boolean retried = false;
                    for (ConfigAccountVO otherAcc : accounts) {
                        if (otherAcc.getId().equals(account.getId())) continue;
                        if (otherAcc.getEnable() == 0 || otherAcc.getIsTokenValid() == 0) continue;

                        WebsiteApiFactory otherFactory = factoryManager.getFactory(websiteId);
                        ApiHandler otherHandler = otherFactory.bet();
                        JSONObject retryResult = otherHandler.execute(otherAcc, failedParam);
                        if (retryResult != null) {
                            retryResult.putOpt("retry", true);
                            retryResult.putOpt("account", otherAcc.getAccount());
                            retryResult.putOpt("accountId", otherAcc.getId());
                            splitResults.add(retryResult);
                            retried = true;
                            log.info("网站:{} 投注补单成功，仍未成功，参数={}", WebsiteType.getById(websiteId).getDescription(), failedParam);
                            break;
                        }
                    }
                    if (!retried) {
                        log.info("网站:{} 投注补单失败，仍未成功，参数={}", WebsiteType.getById(websiteId).getDescription(), failedParam);
                    }
                }
            }

            // ================== 返回处理 ==================
            if (splitResults.isEmpty()) {
                log.info("网站:{} 账号 {} 投注失败，直接尝试下一个账号投注", WebsiteType.getById(websiteId).getDescription(), account.getAccount());
                /*try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {}*/
            } else if (splitResults.size() == 1) {
                return splitResults.get(0);
            } else {
                JSONObject summaryResult = new JSONObject();
                summaryResult.putOpt("code", 200);
                summaryResult.putOpt("message", "拆分投注成功，共" + splitResults.size() + "次");
                summaryResult.putOpt("splitCount", splitResults.size());
                summaryResult.putOpt("totalAmount", betAmount);
                summaryResult.putOpt("splitDetails", splitResults);
                summaryResult.putOpt("account", account.getAccount());
                summaryResult.putOpt("accountId", account.getId());
                return summaryResult;
            }
        }
        return null;
    }

}
