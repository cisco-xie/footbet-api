package com.example.demo.task;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.AdminService;
import com.example.demo.api.ConfigAccountService;
import com.example.demo.api.HandicapApi;
import com.example.demo.common.constants.RedisConstants;
import com.example.demo.common.enmu.WebsiteType;
import com.example.demo.common.enmu.ZhiBoSchedulesType;
import com.example.demo.common.utils.KeyUtil;
import com.example.demo.config.PriorityTaskExecutor;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.vo.ConfigAccountVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * @Description: 检测是否存在非正常注单任务
 * @Author: 谢诗宏
 * @Date: 2024年11月27日
 * @Version 1.0
 */
@Slf4j
@Component
public class CheckAbnormalTask {

    @Resource
    private HandicapApi handicapApi;

    @Resource
    private AdminService adminService;

    @Resource
    private ConfigAccountService accountService;

    @Resource(name = "businessPlatformRedissonClient")
    private RedissonClient businessPlatformRedissonClient;

    @Value("${sweepwater.server.count}")
    private int serverCount;

    // 上一次任务完成后再延迟 20 分钟后执行
    // @Scheduled(fixedDelay = 20 * 60 * 1000)
    public void check() {
        long startTime = System.currentTimeMillis();
        try {
            log.info("开始执行 检测非正常投注...");

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
            log.info("当前服务器serverIndex:{}, 检测非正常投注用户:{}", serverIndex, myUsers);

            for (AdminLoginDTO adminUser : myUsers) {
                CompletableFuture<Void> adminFuture = CompletableFuture.runAsync(() -> {

                    // 中层并发线程池 - 处理当前 adminUser 下的多个网站并行
                    ExecutorService websiteExecutor = Executors.newFixedThreadPool(Math.min(WebsiteType.values().length, cpuCoreCount));

                    List<CompletableFuture<Void>> websiteFutures = new ArrayList<>();

                    for (WebsiteType type : WebsiteType.values()) {
                        if (!WebsiteType.XINBAO.getId().equals(type.getId())) {
                            // 只检测新二网站的
                            continue;
                        }
                        CompletableFuture<Void> websiteFuture = CompletableFuture.runAsync(() -> {
                            List<ConfigAccountVO> userList = accountService.getAccount(adminUser.getUsername(), type.getId());

                            // 内层串行处理账号列表
                            for (ConfigAccountVO userConfig : userList) {
                                if (userConfig.getEnable() == 0 || userConfig.getIsTokenValid() == 0) {
                                    continue;
                                }
                                try {
                                    String date = LocalDateTimeUtil.format(LocalDate.now().minusDays(1), DatePattern.NORM_DATE_PATTERN);
                                    Object settledObj = handicapApi.settled(adminUser.getUsername(), type.getId(), userConfig.getId(), date);
                                    if (settledObj != null) {
                                        JSONArray settledArray = JSONUtil.parseArray(settledObj);
                                        List<String> accounts = new ArrayList<>();
                                        List<String> betIds = new ArrayList<>();
                                        for (Object obj : settledArray) {
                                            JSONObject jsonObject = (JSONObject) obj;
                                            String status = jsonObject.getStr("status");
                                            if (StringUtils.isNotBlank(status) && status.contains("非正常投注")) {
                                                // 出现非正常投注
                                                betIds.add(jsonObject.getStr("betId"));
                                                if (!accounts.contains(userConfig.getAccount())) {
                                                    accounts.add(userConfig.getAccount());
                                                }
                                            }
                                        }
                                        if (!betIds.isEmpty()) {
                                            String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTLED_NORMAL_PREFIX, adminUser.getUsername(), date);
                                            Object abnormalRedis = businessPlatformRedissonClient.getBucket(key).get();
                                            if (abnormalRedis == null) {
                                                JSONObject abnormal = new JSONObject();
                                                abnormal.putOpt("website", type.getDescription());
                                                abnormal.putOpt("accounts", accounts);
                                                abnormal.putOpt("betIds", betIds);
                                                abnormal.putOpt("read", false);
                                                businessPlatformRedissonClient.getBucket(key).set(abnormal, Duration.ofDays(1));
                                            } else {
                                                JSONObject abnormal = JSONUtil.parseObj(abnormalRedis);
                                                Object betIdsObj = abnormal.get("betIds");
                                                List<String> betIdsList = JSONUtil.toList(JSONUtil.toJsonStr(betIdsObj), String.class);
                                                // 旧的已结算非正常注单是否包含所有新的已结算非正常注单
                                                if (!CollUtil.containsAll(betIdsList, betIds)) {
                                                    // 不包含，则需要更新
                                                    abnormal.putOpt("accounts", accounts);
                                                    abnormal.putOpt("betIds", betIds);
                                                    abnormal.putOpt("read", false);
                                                    businessPlatformRedissonClient.getBucket(key).set(abnormal);
                                                }
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    log.error("检测非正常投注异常,网站:{},账户:{},跳过当前账户进行下一个账户检测操作",
                                            type.getDescription(), userConfig.getAccount(), e);
                                }
                            }
                        }, websiteExecutor);

                        websiteFutures.add(websiteFuture);
                    }

                    // 等待当前 adminUser 下所有网站任务完成
                    CompletableFuture.allOf(websiteFutures.toArray(new CompletableFuture[0])).join();
                    PriorityTaskExecutor.shutdownExecutor(websiteExecutor);

                }, adminExecutor);

                adminFutures.add(adminFuture);
            }

            // 等待所有管理员任务完成
            CompletableFuture.allOf(adminFutures.toArray(new CompletableFuture[0])).join();
            PriorityTaskExecutor.shutdownExecutor(adminExecutor);

        } catch (Exception e) {
            log.error("检测非正常投注 执行异常", e);
        } finally {
            long endTime = System.currentTimeMillis();
            long costTime = (endTime - startTime) / 1000;
            log.info("此轮检测非正常投注任务执行花费 {}s", costTime);
            if (costTime > 20) {
                log.warn("检测非正常投注 执行时间过长");
            }
        }
    }


}
