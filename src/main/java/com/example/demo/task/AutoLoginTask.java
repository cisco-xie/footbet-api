package com.example.demo.task;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.AdminService;
import com.example.demo.api.ConfigAccountService;
import com.example.demo.api.HandicapApi;
import com.example.demo.common.enmu.WebsiteType;
import com.example.demo.common.enmu.ZhiBoSchedulesType;
import com.example.demo.config.PriorityTaskExecutor;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.vo.ConfigAccountVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * @Description: 自动登录会员账号任务
 * @Author: 谢诗宏
 * @Date: 2024年11月27日
 * @Version 1.0
 */
@Slf4j
@Component
public class AutoLoginTask {

    @Resource
    private HandicapApi handicapApi;

    @Resource
    private AdminService adminService;

    @Resource
    private ConfigAccountService accountService;

    @Value("${sweepwater.server.count}")
    private int serverCount;

    @Async("loginTaskExecutor") // ✅ 独立线程池执行
    // 上一次任务完成后再延迟 10 分钟后执行
    @Scheduled(fixedDelay = 2 * 60 * 1000)
    public void autoLogin() {
        long startTime = System.currentTimeMillis();
        try {
            log.info("开始执行 自动登录...");

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
                                log.info("检查账户登录情况,网站:{},账户:{}", type.getDescription(), userConfig.getAccount());
                                try {
                                    boolean isValid = true;
                                    userConfig = accountService.getAccountById(adminUser.getUsername(), type.getId(), userConfig.getId());
                                    if (userConfig.getIsTokenValid() != 1) {
                                        isValid = false;
                                    } else {
                                        if (type == WebsiteType.SBO) {
                                            // 盛帆网站通过获取赛事列表进行判断当前token是否有效
                                            Object eventLive = handicapApi.eventList(adminUser.getUsername(), type.getId(), ZhiBoSchedulesType.TODAYSCHEDULE.getId(), userConfig);
                                            if (eventLive == null) {
                                                isValid = false;
                                            }
                                        } else {
                                            Object resultBalance = handicapApi.balanceByAccount(adminUser.getUsername(), type.getId(), userConfig);
                                            if (resultBalance != null) {
                                                JSONObject result = JSONUtil.parseObj(resultBalance);
                                                if (!result.getBool("success")) {
                                                    isValid = false;
                                                }
                                            } else {
                                                isValid = false;
                                            }
                                        }
                                    }

                                    if (!isValid) {
                                        // 当前账号token无效，进行下线操作更新token状态
                                        accountService.logoutByWebsiteAndAccountId(adminUser.getUsername(), type.getId(), userConfig.getId());
                                        if (userConfig.getAutoLogin() == 1) {
                                            log.info("自动登录-平台用户:{}, 账号{} 当前token无效,进行自动登录", adminUser.getUsername(), adminUser.getUsername());
                                            // 当前账号token无效，调用盘口api登录
                                            handicapApi.processAccountLogin(userConfig, adminUser.getUsername(), type.getId(), retryMap);
                                        }
                                    } else {
                                        log.info("自动登录-平台用户:{}, 账号{} 当前token有效无需登录", adminUser.getUsername(), adminUser.getUsername());
                                    }

                                    // 可选延时，防止过快请求被限流
                                    // Thread.sleep(200);

                                } catch (Exception e) {
                                    log.error("自动登录任务异常,网站:{},账户:{},跳过当前账户进行下一个账户登录操作",
                                            type.getDescription(), userConfig.getAccount(), e);
                                }
                            }
                        }, websiteExecutor);

                        websiteFutures.add(websiteFuture);
                    }

                    // 等待当前 adminUser 下所有网站任务完成
                    CompletableFuture.allOf(websiteFutures.toArray(new CompletableFuture[0])).join();
                    PriorityTaskExecutor.shutdownExecutor(websiteExecutor);

                    log.info("系统账号:{} 所有网站登录任务完成", adminUser.getUsername());

                }, adminExecutor);

                adminFutures.add(adminFuture);
            }

            // 等待所有管理员任务完成
            CompletableFuture.allOf(adminFutures.toArray(new CompletableFuture[0])).join();
            PriorityTaskExecutor.shutdownExecutor(adminExecutor);

        } catch (Exception e) {
            log.error("自动登录 执行异常", e);
        } finally {
            long endTime = System.currentTimeMillis();
            long costTime = (endTime - startTime) / 1000;
            log.info("此轮自动登录任务执行花费 {}s", costTime);
            if (costTime > 20) {
                log.warn("自动登录 执行时间过长");
            }
        }
    }


}
