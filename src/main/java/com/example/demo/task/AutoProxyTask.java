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

    private ScheduledExecutorService proxyExecutor;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> accountTaskMap = new ConcurrentHashMap<>();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build();

    @PostConstruct
    public void init() {
        proxyExecutor = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors() * 2);
    }

    public void updateAccountTask(ConfigAccountVO account, String websiteId, AdminLoginDTO adminLogin) {
        String taskKey = getTaskKey(adminLogin.getUsername(), websiteId, account.getId());

        if (account.getEnable() != 1 || account.getProxyType() != 3 || adminLogin.getStopBet() == 1) {
            cancelTask(taskKey);
            return;
        }

        // 已存在任务 → 不重复创建
        if (accountTaskMap.containsKey(taskKey)) { return; }

        // 1小时更换一次
        ScheduledFuture<?> future = proxyExecutor.schedule(
                () -> fetchHttpProxy(adminLogin, websiteId, account),
                60,
                TimeUnit.MINUTES
        );

        accountTaskMap.put(taskKey, future);
        log.info("调度账户代理拉取任务,账户={},网站={},用户名={}",
                account.getId(), websiteId, adminLogin.getUsername());
    }

    private void cancelTask(String taskKey) {
        ScheduledFuture<?> future = accountTaskMap.remove(taskKey);
        if (future != null) {
            future.cancel(false);
            log.info("取消账户代理拉取任务: {}", taskKey);
        }
    }

    private String getTaskKey(String username, String websiteId, String accountId) {
        return username + "-" + websiteId + "-" + accountId;
    }

    private void fetchHttpProxy(AdminLoginDTO adminLogin, String websiteId, ConfigAccountVO account) {
        String taskKey = getTaskKey(adminLogin.getUsername(), websiteId, account.getId());

        ConfigAccountVO latestAccount = accountService.getAccountById(
                adminLogin.getUsername(),
                websiteId,
                account.getId()
        );

        if (latestAccount.getProxyType() != 3 || adminLogin.getStopBet() == 1) {
            accountTaskMap.remove(taskKey);
            log.info("账户不再需要自动代理拉取,取消任务: {}", taskKey);
            return;
        }

        try {
            Request request = new Request.Builder()
                    .url("https://api.cliproxy.io/white/api?region=HK&num=1&time=15&format=n&type=txt")
                    .get()
                    .addHeader("User-Agent", Constants.USER_AGENT)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) throw new IOException("请求失败,状态码:" + response.code());

                String proxyStr = response.body() != null ? response.body().string().trim() : null;
                if (proxyStr == null || proxyStr.isEmpty()) throw new IOException("返回内容为空");

                String[] arr = proxyStr.split(":");
                if (arr.length != 2) throw new IllegalArgumentException("代理格式非法: " + proxyStr);

                latestAccount.setProxyHost(arr[0]);
                latestAccount.setProxyPort(Integer.parseInt(arr[1]));
                latestAccount.setProxyUsername(null);
                latestAccount.setProxyPassword(null);

                accountService.saveAccount(adminLogin.getUsername(), latestAccount.getWebsiteId(), latestAccount);
                log.info("账户代理更新成功,账户={},网站={},用户名={}", latestAccount.getId(), latestAccount.getWebsiteId(), adminLogin.getUsername());

                // 再次调度下一轮任务
                updateAccountTask(latestAccount, websiteId, adminLogin);
            }
        } catch (Exception e) {
            log.error("账户代理拉取失败,账户={},网站={},用户名={}", account.getId(), account.getWebsiteId(), adminLogin.getUsername(), e);
            // 失败后也重新调度下一轮
            updateAccountTask(account, websiteId, adminLogin);
        }
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
