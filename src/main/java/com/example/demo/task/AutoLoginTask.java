package com.example.demo.task;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.AdminService;
import com.example.demo.api.ConfigAccountService;
import com.example.demo.api.HandicapApi;
import com.example.demo.common.enmu.WebsiteType;
import com.example.demo.config.PriorityTaskExecutor;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.vo.ConfigAccountVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
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

    // @Async("loginTaskExecutor") // ✅ 独立线程池执行
    // 上一次任务完成后再延迟 30 秒执行
    @Scheduled(fixedDelay = 30000)
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

            for (AdminLoginDTO adminUser : adminUsers) {
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

                                    if (userConfig.getIsTokenValid() != 1) {
                                        isValid = false;
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

                                    if (!isValid) {
                                        // 当前账号token无效，进行下线操作更新token状态
                                        accountService.logoutByWebsiteAndAccountId(adminUser.getUsername(), type.getId(), userConfig.getId());
                                        if (userConfig.getAutoLogin() == 1) {
                                            // 当前账号token无效，调用盘口api登录
                                            handicapApi.processAccountLogin(userConfig, adminUser.getUsername(), type.getId(), retryMap);
                                        }
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
