package com.example.demo.task;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.AdminService;
import com.example.demo.api.ConfigAccountService;
import com.example.demo.api.HandicapApi;
import com.example.demo.common.constants.Constants;
import com.example.demo.common.enmu.WebsiteType;
import com.example.demo.common.enmu.ZhiBoSchedulesType;
import com.example.demo.config.OkHttpProxyDispatcher;
import com.example.demo.config.PriorityTaskExecutor;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.vo.ConfigAccountVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private HandicapApi handicapApi;

    @Resource
    private AdminService adminService;

    @Resource
    private ConfigAccountService accountService;

    @Resource
    private OkHttpProxyDispatcher dispatcher;

    @Value("${sweepwater.server.count}")
    private int serverCount;

    @Async("proxyTaskExecutor") // ✅ 独立线程池执行
    // 上一次任务完成后再延迟 5 分钟后执行
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void autoLogin() {
        long startTime = System.currentTimeMillis();
        try {
            log.info("开始执行 自动更新代理...");

            List<AdminLoginDTO> adminUsers = adminService.getUsers(null);
            int cpuCoreCount = Runtime.getRuntime().availableProcessors();

            // 外层并发线程池 - 处理多个 adminUser
            ExecutorService adminExecutor = Executors.newFixedThreadPool(Math.min(adminUsers.size(), cpuCoreCount));

            ConcurrentHashMap<String, Integer> retryMap = new ConcurrentHashMap<>();

            List<CompletableFuture<Void>> adminFutures = new ArrayList<>();

            // 获取本机标识（机器ID、IP、或自定义ID）
            String serverId = System.getProperty("server.id", "0");
            int serverIndex = Integer.parseInt(serverId); // 直接用数字索引

            // 分配账户：比如通过 hash 或 Redis 列表分片
            List<AdminLoginDTO> myUsers = adminUsers.stream()
                    .filter(u -> Math.abs(u.getUsername().hashCode()) % serverCount == serverIndex)
                    .toList();

            if (myUsers.isEmpty()) {
                log.info("当前服务器分片没有用户，serverId={}", serverId);
                return;
            }
            log.info("当前服务器serverIndex:{}, 分片扫水用户:{}", serverIndex, myUsers);
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .callTimeout(10, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(false)
                    .build();
            Map<String, String> requestHeaders = new HashMap<>();
            for (AdminLoginDTO adminUser : myUsers) {
                CompletableFuture<Void> adminFuture = CompletableFuture.runAsync(() -> {

                    // 中层并发线程池 - 处理当前 adminUser 下的多个网站并行
                    ExecutorService websiteExecutor = Executors.newFixedThreadPool(Math.min(WebsiteType.values().length, cpuCoreCount));

                    List<CompletableFuture<Void>> websiteFutures = new ArrayList<>();

                    for (WebsiteType type : WebsiteType.values()) {
                        CompletableFuture<Void> websiteFuture = CompletableFuture.runAsync(() -> {
                            List<ConfigAccountVO> userList = accountService.getAccount(adminUser.getUsername(), type.getId());

                            // 内层串行处理账号列表
                            for (ConfigAccountVO userConfig : userList) {
                                if (userConfig.getEnable() == 0 || userConfig.getProxyType() != 3) {
                                    continue;
                                }
                                // 构建请求
                                Request request = new Request.Builder()
                                        .url("https://api.cliproxy.io/white/api?region=HK&num=1&time=15&format=n&type=txt")
                                        .get()
                                        .addHeader("User-Agent", Constants.USER_AGENT)
                                        .build();

                                // 执行请求
                                try (Response response = client.newCall(request).execute()) {
                                    if (!response.isSuccessful()) {
                                        throw new IOException("请求失败，状态码：" + response.code());
                                    }

                                    String proxyStr = response.body() != null ? response.body().string().trim() : null;
                                    if (proxyStr == null || proxyStr.isEmpty()) {
                                        throw new IOException("返回内容为空");
                                    }

                                    log.info("自动更新代理 获取的代理配置:{}", proxyStr);

                                    String[] arr = proxyStr.split(":");
                                    if (arr.length != 2) {
                                        throw new IllegalArgumentException("代理格式非法: " + proxyStr);
                                    }

                                    String proxyHost = arr[0];
                                    int proxyPort = Integer.parseInt(arr[1]);

                                    userConfig.setProxyHost(proxyHost);
                                    userConfig.setProxyPort(proxyPort);
                                    userConfig.setProxyUsername(null);
                                    userConfig.setProxyPassword(null);

                                    log.info("自动更新账户代理,网站:{},账户:{}", type.getDescription(), userConfig.getAccount());

                                    accountService.saveAccount(adminUser.getUsername(), type.getId(), userConfig);
                                } catch (Exception e) {
                                    log.error("自动更新代理任务异常,网站:{},账户:{}",
                                            type.getDescription(), userConfig.getAccount(), e);
                                }
                            }
                        }, websiteExecutor);

                        websiteFutures.add(websiteFuture);
                    }

                    // 等待当前 adminUser 下所有网站任务完成
                    CompletableFuture.allOf(websiteFutures.toArray(new CompletableFuture[0])).join();
                    PriorityTaskExecutor.shutdownExecutor(websiteExecutor);

                    log.info("系统账号:{} 所有账户自动更新代理任务完成", adminUser.getUsername());

                }, adminExecutor);

                adminFutures.add(adminFuture);
            }

            // 等待所有管理员任务完成
            CompletableFuture.allOf(adminFutures.toArray(new CompletableFuture[0])).join();
            PriorityTaskExecutor.shutdownExecutor(adminExecutor);

        } catch (Exception e) {
            log.error("自动更新代理 执行异常", e);
        } finally {
            long endTime = System.currentTimeMillis();
            long costTime = (endTime - startTime) / 1000;
            log.info("此轮自动更新代理任务执行花费 {}s", costTime);
            if (costTime > 20) {
                log.warn("自动更新代理 执行时间过长");
            }
        }
    }

}
