package com.example.demo.api;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.TimeInterval;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.common.constants.RedisConstants;
import com.example.demo.common.utils.KeyUtil;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.core.factory.WebsiteApiFactory;
import com.example.demo.core.factory.WebsiteFactoryManager;
import com.example.demo.core.model.UserConfig;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.vo.*;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

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
    private AdminService adminService;

    @Resource
    private ConfigAccountService accountService;

    /**
     * 所有盘口账号自动登录
     */
    public void login() {
        List<AdminLoginDTO> users = adminService.getEnableUsers();
        ConcurrentHashMap<String, ExecutorService> userExecutorMap = new ConcurrentHashMap<>();

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
                            .map(account -> CompletableFuture.runAsync(() -> processAccountLogin(account, user.getUsername(), website.getId()), executorService))
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
        int cpuCoreCount = Runtime.getRuntime().availableProcessors();
        int threadPoolUserSize = Math.min(accounts.size(), cpuCoreCount * 2);
        ExecutorService executorLoginService = new ThreadPoolExecutor(
                threadPoolUserSize, // 核心线程数
                accounts.size(), // 最大线程数
                0L, TimeUnit.SECONDS, // 空闲线程超时时间
                new LinkedBlockingQueue<>(10), // 队列大小
                new ThreadFactoryBuilder().setNameFormat("loginByWebsite-pool-%d").build(),
                new ThreadPoolExecutor.AbortPolicy() // 拒绝策略：抛出异常，便于发现问题
        );

        try {
            List<CompletableFuture<Void>> futures = accounts.stream()
                    .map(account -> CompletableFuture.runAsync(() -> processAccountLogin(account, username, websiteId), executorLoginService))
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

    // 运行登录工厂
    private void processAccountLogin(ConfigAccountVO account, String username, String websiteId) {
        TimeInterval timer = DateUtil.timer();
        WebsiteApiFactory factory = factoryManager.getFactory(websiteId);
        ApiHandler apiHandler = factory.getLoginHandler();
        if (apiHandler == null) {
            return;
        }
        JSONObject params = new JSONObject();
        params.putOpt("adminUsername", username);
        params.putOpt("websiteId", websiteId);
        // 根据不同站点传入不同的参数
        if ("1874805533324103680".equals(websiteId)) {
            params.putOpt("loginId", account.getAccount());
            params.putOpt("password", account.getPassword());
        } else if ("1874804932787851264".equals(websiteId) || "1877702689064243200".equals(websiteId)) {
            params.putOpt("username", account.getAccount());
            params.putOpt("password", account.getPassword());
        }
        JSONObject result = apiHandler.execute(params);
        account.setIsTokenValid(result.getBool("success") ? 1 : 0);
        account.setToken(result);
        account.setExecuteMsg(result.get("msg") + "：" + timer.interval() + " ms");
        accountService.saveAccount(username, websiteId, account);
        if (result.getBool("success")) {
            // 登录成功就执行获取额度
            balanceByAccount(username, websiteId, account);
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
                    if ("1874805533324103680".equals(website.getId())) {
                        params.putAll(account.getToken().getJSONObject("tokens"));
                    } else if ("1874804932787851264".equals(website.getId())) {
                        params.putOpt("token", "Bearer " + account.getToken().getStr("token"));
                    } else if ("1877702689064243200".equals(website.getId())) {
                        params.putAll(account.getToken().getJSONObject("serverresponse"));
                    }
                    JSONObject result = apiHandler.execute(params);
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
    public void balanceByAccount(String username, String websiteId, ConfigAccountVO account) {
        TimeInterval timer = DateUtil.timer();
        WebsiteApiFactory factory = factoryManager.getFactory(websiteId);

        ApiHandler apiHandler = factory.getInfoHandler();
        if (apiHandler == null) {
            return;
        }
        JSONObject params = new JSONObject();
        params.putOpt("adminUsername", username);
        params.putOpt("websiteId", websiteId);
        // 根据不同站点传入不同的参数
        if ("1874805533324103680".equals(websiteId)) {
            params.putAll(account.getToken().getJSONObject("tokens"));
        } else if ("1874804932787851264".equals(websiteId)) {
            params.putOpt("token", "Bearer " + account.getToken().getStr("token"));
        } else if ("1877702689064243200".equals(websiteId)) {
            params.putAll(account.getToken().getJSONObject("serverresponse"));
        }
        JSONObject result = apiHandler.execute(params);
        account.setBetCredit(result.getBigDecimal("betCredit"));
        account.setExecuteMsg(result.get("msg") + "：" + timer.interval() + " ms");
        accountService.saveAccount(username, websiteId, account);
    }

    /**
     * 根据用户和网站获取赛事列表
     * @param username
     * @param websiteId
     * @return
     */
    public Object events(String username, String websiteId) {
        List<ConfigAccountVO> accounts = accountService.getAccount(username, websiteId);
        for (ConfigAccountVO account : accounts) {
            WebsiteApiFactory factory = factoryManager.getFactory(websiteId);

            ApiHandler apiHandler = factory.getEventsHandler();
            if (apiHandler == null) {
                continue;
            }
            JSONObject params = new JSONObject();
            params.putOpt("adminUsername", username);
            params.putOpt("websiteId", websiteId);
            // 根据不同站点传入不同的参数
            if ("1874805533324103680".equals(websiteId)) {
                params.putAll(account.getToken().getJSONObject("tokens"));
            } else if ("1874804932787851264".equals(websiteId)) {
                params.putOpt("token", "Bearer " + account.getToken().getStr("token"));
            } else if ("1877702689064243200".equals(websiteId)) {
                params.putAll(account.getToken().getJSONObject("serverresponse"));
            }
            JSONObject result = apiHandler.execute(params);

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
        WebsiteApiFactory factory = factoryManager.getFactory(websiteId);

        ApiHandler apiHandler = factory.getStatementsHandler();
        if (apiHandler == null) {
            return null;
        }
        JSONObject params = new JSONObject();
        params.putOpt("adminUsername", username);
        params.putOpt("websiteId", websiteId);
        // 根据不同站点传入不同的参数
        if ("1874805533324103680".equals(websiteId)) {
            params.putAll(account.getToken().getJSONObject("tokens"));
        } else if ("1874804932787851264".equals(websiteId)) {
            params.putOpt("token", "Bearer " + account.getToken().getStr("token"));
        } else if ("1877702689064243200".equals(websiteId)) {
            params.putAll(account.getToken().getJSONObject("serverresponse"));
        }
        JSONObject result = apiHandler.execute(params);

        if (result.getBool("success")) {
            return result.get("data");
        }
        return null;
    }

}
