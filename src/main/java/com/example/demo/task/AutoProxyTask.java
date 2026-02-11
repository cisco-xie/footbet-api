package com.example.demo.task;

import com.example.demo.api.ConfigAccountService;
import com.example.demo.common.constants.Constants;
import com.example.demo.common.enmu.WebsiteType;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.vo.ConfigAccountVO;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @Description: 自动更新账号代理任务
 * @Author: 谢诗宏
 * @Date: 2026年2月6日
 * @Version 1.0
 */
@Slf4j
@Component
public class AutoProxyTask {

    @Resource
    @Lazy
    private ConfigAccountService accountService;

    private static final int SUCCESS_DELAY_MIN = 350;
    private static final int FAIL_RETRY_SECONDS = 10;
    private static final int MAX_FAIL_RETRY = 5;

    // 端口范围
    private static final int PORT_MIN = 443;
    private static final int PORT_MAX = 3000;

    // 全局端口游标（线程安全）
    private final AtomicInteger proxyPortCursor = new AtomicInteger(PORT_MIN);

    private ScheduledExecutorService proxyExecutor;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> accountTaskMap = new ConcurrentHashMap<>();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build();
    private final ConcurrentHashMap<String, AtomicInteger> failRetryCountMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        proxyExecutor = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors() * 2);
    }

    /**
     * 获取下一个端口
     * @return
     */
    private int nextProxyPort() {
        while (true) {
            int current = proxyPortCursor.get();
            int next = (current >= PORT_MAX) ? PORT_MIN : current + 1;
            if (proxyPortCursor.compareAndSet(current, next)) {
                return current;
            }
        }
    }

    public void updateAccountTask(ConfigAccountVO account, String websiteId, AdminLoginDTO adminLogin) {
        String taskKey = getTaskKey(adminLogin.getUsername(), websiteId, account.getId());

        if (account.getEnable() != 1 || account.getProxyType() != 3 || adminLogin.getStopBet() == 1) {
            cancelTask(taskKey);
            return;
        }

        // 已存在任务 → 不重复创建
        if (accountTaskMap.containsKey(taskKey)) { return; }

        long now = System.currentTimeMillis();
        // ⭐ 检查代理是否已经过期（或即将过期）
        if (account.getProxyExpireTime() != null && now < account.getProxyExpireTime()) {
            long delayMillis = account.getProxyExpireTime() - now;
            log.info("自动获取代理-代理未过期,延迟 {} ms 后初始化账户任务,账户={},网站={},用户名={}",
                    delayMillis, account.getId(), websiteId, adminLogin.getUsername());
            ScheduledFuture<?> future = proxyExecutor.schedule(
                    () -> fetchHttpProxy(adminLogin, websiteId, account),
                    delayMillis,
                    TimeUnit.MILLISECONDS
            );
            accountTaskMap.put(taskKey, future);
            return;
        }

        log.info("自动获取代理-初始化账户代理任务,账户={},网站={},用户名={}",
                account.getId(), websiteId, adminLogin.getUsername());

        // ⭐ 第一次：立即执行（不走 delay）
        proxyExecutor.execute(() ->
                fetchHttpProxy(adminLogin, websiteId, account)
        );
    }

    private void cancelTask(String taskKey) {
        ScheduledFuture<?> future = accountTaskMap.remove(taskKey);
        if (future != null) {
            future.cancel(false);
            log.info("自动获取代理-取消账户代理拉取任务: {}", taskKey);
        }
    }

    private String getTaskKey(String username, String websiteId, String accountId) {
        return username + "-" + websiteId + "-" + accountId;
    }

    private void fetchHttpProxy(AdminLoginDTO adminLogin,
                                String websiteId,
                                ConfigAccountVO account) {

        String taskKey = getTaskKey(adminLogin.getUsername(), websiteId, account.getId());

        try {
            ConfigAccountVO latestAccount = accountService.getAccountById(
                    adminLogin.getUsername(),
                    websiteId,
                    account.getId()
            );

            if (latestAccount.getEnable() != 1
                    || latestAccount.getProxyType() != 3
                    || adminLogin.getStopBet() == 1) {

                cancelTask(taskKey);
                log.info("自动获取代理-账户不再需要自动代理拉取,取消任务: {}", taskKey);
                return;
            }

            int port = nextProxyPort();   // ⭐ 获取原子端口
            Request request = new Request.Builder()
                    .url("https://ipapi.cliproxy.com/start?key=i6qkhiajwcutp9bs&port=" + port + "&num=1&country=HK&state=&type=1")
                    .get()
                    .addHeader("User-Agent", Constants.USER_AGENT)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("自动获取代理-请求失败,状态码:" + response.code());
                }

                String proxyStr = response.body() != null
                        ? response.body().string().trim()
                        : null;
                log.info("自动获取代理-API请求结果={}", proxyStr);

                if (proxyStr == null || proxyStr.isEmpty()) {
                    throw new IOException("自动获取代理-返回内容为空");
                }

                String[] arr = proxyStr.split(":");
                if (arr.length != 2) {
                    throw new IllegalArgumentException("自动获取代理-代理格式非法: " + proxyStr);
                }

                latestAccount.setProxyHost(arr[0]);
                latestAccount.setProxyPort(Integer.parseInt(arr[1]));
                latestAccount.setProxyUsername(null);
                latestAccount.setProxyPassword(null);
                latestAccount.setProxyExpireTime(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(SUCCESS_DELAY_MIN));

                accountService.saveAccount(
                        adminLogin.getUsername(),
                        websiteId,
                        latestAccount
                );

                // ✅ 成功 → 350 分钟后再调度
                reschedule(taskKey, adminLogin, websiteId, latestAccount, SUCCESS_DELAY_MIN, TimeUnit.MINUTES);
                // 成功保存代理后清零
                failRetryCountMap.remove(taskKey);

                log.info("自动获取代理-账户代理更新成功,账户={},网站={},用户名={}",
                        latestAccount.getId(),
                        websiteId,
                        adminLogin.getUsername());
            }

        } catch (Exception e) {
            log.error("自动获取代理-账户代理拉取失败,账户={},网站={},用户名={}",
                    account.getId(), websiteId, adminLogin.getUsername(), e);
            AtomicInteger retryCount = failRetryCountMap.computeIfAbsent(taskKey, k -> new AtomicInteger(0));

            int currentRetry = retryCount.incrementAndGet();

            if (currentRetry > MAX_FAIL_RETRY) {
                log.error("自动获取代理-失败次数超过上限({}),暂停账户代理任务: {}", MAX_FAIL_RETRY, taskKey);
                cancelTask(taskKey);
                failRetryCountMap.remove(taskKey);
                return;
            }

            // ❗失败 → 短延迟重试（比如 10 秒）
            reschedule(taskKey, adminLogin, websiteId, account, FAIL_RETRY_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * 拉取成功 调度下一次代理拉取（350 分钟）
     */
    private void reschedule(
            String taskKey,
            AdminLoginDTO adminLogin,
            String websiteId,
            ConfigAccountVO account,
            long delay,
            TimeUnit unit
    ) {
        cancelTask(taskKey);

        ScheduledFuture<?> future = proxyExecutor.schedule(
                () -> fetchHttpProxy(adminLogin, websiteId, account),
                delay,
                unit
        );

        accountTaskMap.put(taskKey, future);
    }

    /***
     * 在用户投注开关变化时调用
     */
    public void handleUserBetChange(AdminLoginDTO adminLogin) {
        for (WebsiteType websiteType : WebsiteType.values()) {
            List<ConfigAccountVO> allAccounts = accountService.getAccount(adminLogin.getUsername(), websiteType.getId());
            for (ConfigAccountVO account : allAccounts) {
                updateAccountTask(account, websiteType.getId(), adminLogin);
            }
        }
    }
}
