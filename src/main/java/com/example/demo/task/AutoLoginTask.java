package com.example.demo.task;

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
import java.util.concurrent.Semaphore;


/**
 * @Description: 自动登录会员账号任务
 * @Author: 谢诗宏
 * @Date: 2024年11月27日
 * @Version 1.0
 */
@Slf4j
@Component
public class AutoLoginTask {

    /** 全局同时登录账户数上限，避免与扫水叠加打爆代理网关 */
    private static final Semaphore LOGIN_SEMAPHORE = new Semaphore(3);
    /** 账户间间隔，避免毫秒级连续 CONNECT */
    private static final long ACCOUNT_INTERVAL_MS = 500;

    @Resource
    private HandicapApi handicapApi;

    @Resource
    private AdminService adminService;

    @Resource
    private ConfigAccountService accountService;

    @Value("${sweepwater.server.count}")
    private int serverCount;

    @Async("loginTaskExecutor") // ✅ 独立线程池执行
    // 上一次任务完成后再延迟 5 分钟执行
    @Scheduled(fixedDelay = 5 * 60 * 1000)
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

                    // 网站串行处理，降低同一时刻的代理 CONNECT 峰值
                    for (WebsiteType type : WebsiteType.values()) {
                        List<ConfigAccountVO> userList = accountService.getAccount(adminUser.getUsername(), type.getId());

                        for (ConfigAccountVO userConfig : userList) {
                            log.info("检查账户登录情况,网站:{},平台用户:{},账户:{}", type.getDescription(), adminUser.getUsername(), userConfig.getAccount());
                            if (userConfig.getEnable() == 0) {
                                continue;
                            }
                            try {
                                boolean isValid = true;
                                userConfig = accountService.getAccountById(adminUser.getUsername(), type.getId(), userConfig.getId());
                                if (userConfig.getIsTokenValid() != 1) {
                                    isValid = false;
                                } else {
                                    if (!type.getId().equals(WebsiteType.SBO.getId())) {
                                        // 统一用 balance 校验 token，比 eventList 更轻量
                                        Object resultBalance = handicapApi.balanceByAccount(adminUser.getUsername(), type.getId(), userConfig);
                                        if (resultBalance != null) {
                                            JSONObject result = JSONUtil.parseObj(resultBalance);
                                            if (!result.getBool("success")) {
                                                isValid = false;
                                            }
                                        } else {
                                            isValid = false;
                                        }
                                    } else {
                                        // 盛帆账户通过赛事列表来判断账户是否token失效
                                        Object eventLive = handicapApi.events(adminUser.getUsername(), type.getId(), ZhiBoSchedulesType.TODAYSCHEDULE.getId(), userConfig.getId());
                                        log.info("自动登录-平台用户:{},网站:{}, 账号{}, eventLive:{}", adminUser.getUsername(), type.getDescription(), userConfig.getAccount(), eventLive);
                                        if (eventLive == null) {
                                            isValid = false;
                                        }
                                    }
                                }

                                if (!isValid) {
                                    accountService.logoutByWebsiteAndAccountId(adminUser.getUsername(), type.getId(), userConfig.getId());
                                    if (userConfig.getAutoLogin() == 1) {
                                        log.info("自动登录-平台用户:{},网站:{}, 账号{} 当前token无效,进行自动登录", adminUser.getUsername(), type.getDescription(), userConfig.getAccount());
                                        LOGIN_SEMAPHORE.acquire();
                                        try {
                                            handicapApi.processAccountLogin(userConfig, adminUser.getUsername(), type.getId(), retryMap);
                                        } finally {
                                            LOGIN_SEMAPHORE.release();
                                        }
                                    }
                                } else {
                                    log.info("自动登录-平台用户:{},网站:{}, 账号{} 当前token有效无需登录", adminUser.getUsername(), type.getDescription(), userConfig.getAccount());
                                }

                                Thread.sleep(ACCOUNT_INTERVAL_MS);

                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                log.warn("自动登录任务被中断,网站:{},账户:{}", type.getDescription(), userConfig.getAccount());
                            } catch (Exception e) {
                                log.error("自动登录任务异常,网站:{},账户:{},跳过当前账户进行下一个账户登录操作",
                                        type.getDescription(), userConfig.getAccount(), e);
                            }
                        }
                    }

                    log.info("系统账号:{} 所有网站登录任务完成", adminUser.getUsername());

                }, adminExecutor);

                adminFutures.add(adminFuture);
            }

            CompletableFuture.allOf(adminFutures.toArray(new CompletableFuture[0])).join();
            PriorityTaskExecutor.shutdownExecutor(adminExecutor);

        } catch (Exception e) {
            log.error("自动登录 执行异常", e);
        } finally {
            long endTime = System.currentTimeMillis();
            long costTime = (endTime - startTime) / 1000;
            log.info("此轮自动登录任务执行花费 {}s", costTime);
        }
    }


}
