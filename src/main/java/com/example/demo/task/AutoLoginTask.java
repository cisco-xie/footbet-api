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

    // 上一次任务完成后再延迟 30 秒执行
    @Scheduled(fixedDelay = 30000)
    public void autoLogin() {
        long startTime = System.currentTimeMillis();
        try {
            log.info("开始执行 自动登录...");

            List<AdminLoginDTO> adminUsers = adminService.getUsers(null);
            int cpuCoreCount = Runtime.getRuntime().availableProcessors();

            // 创建管理所有任务的线程池
            ExecutorService executorAdminUserService = Executors.newFixedThreadPool(Math.min(adminUsers.size(), cpuCoreCount * 2));

            // 存储所有 CompletableFuture
            List<CompletableFuture<Void>> adminFutures = new ArrayList<>();

            // ✅ 定义本地内存的 retryMap，仅在当前方法内生效（不持久）
            ConcurrentHashMap<String, Integer> retryMap = new ConcurrentHashMap<>();

            for (AdminLoginDTO adminUser : adminUsers) {
                for (WebsiteType type : WebsiteType.values()) {
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        List<ConfigAccountVO> userList = accountService.getAccount(adminUser.getUsername(), type.getId());

                        // **在方法外部创建线程池**
                        ExecutorService executorAccountService = Executors.newFixedThreadPool(Math.min(userList.size(), cpuCoreCount * 2));

                        try {
                            List<CompletableFuture<Void>> accountFutures = userList.stream()
                                    .map(userConfig -> CompletableFuture.runAsync(() -> {
                                        boolean isValid = true;
                                        if (userConfig.getIsTokenValid() != 1) {
                                            isValid = false;
                                        } else {
                                            // 当前账号存在token，调用盘口api检测token是否真实有效
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
                                    }, executorAccountService))
                                    .toList();

                            // **等待所有账号任务完成**
                            CompletableFuture.allOf(accountFutures.toArray(new CompletableFuture[0])).join();
                        } finally {
                            // **正确关闭线程池**
                            PriorityTaskExecutor.shutdownExecutor(executorAccountService);
                        }
                    }, executorAdminUserService);
                    adminFutures.add(future);
                }
            }

            // **等待所有管理员任务完成**
            CompletableFuture.allOf(adminFutures.toArray(new CompletableFuture[0])).join();
            // **确保 executorAdminUserService 正确关闭**
            PriorityTaskExecutor.shutdownExecutor(executorAdminUserService);
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
