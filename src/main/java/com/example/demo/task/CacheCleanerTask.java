package com.example.demo.task;

import cn.hutool.core.date.DatePattern;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.AdminService;
import com.example.demo.common.constants.RedisConstants;
import com.example.demo.common.utils.KeyUtil;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.dto.sweepwater.SweepwaterDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class CacheCleanerTask {

    @Resource
    private AdminService adminService;

    @Resource(name = "businessPlatformRedissonClient")
    private RedissonClient businessPlatformRedissonClient;

    /**
     * 清理超过4小时的扫水记录
     */
    @Scheduled(fixedDelay = 10 * 60 * 1000) // 每10分钟执行
    public void cleanExpiredSweepwaterDTO() {
        // 获取所有系统用户
        List<AdminLoginDTO> adminUsers = adminService.getUsers(null);
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DatePattern.NORM_DATETIME_PATTERN);
        for (AdminLoginDTO admin : adminUsers) {
            String username = admin.getUsername();
            String key = KeyUtil.genKey(RedisConstants.SWEEPWATER_PREFIX, username);
            RList<String> sweepList = businessPlatformRedissonClient.getList(key);

            if (sweepList == null || sweepList.isEmpty()) continue;

            List<String> toRemove = new ArrayList<>();
            for (String json : sweepList) {
                try {
                    SweepwaterDTO dto = JSONUtil.toBean(json, SweepwaterDTO.class);
                    LocalDateTime createTime = LocalDateTime.parse(dto.getCreateTime(), formatter);
                    // 清理超过4小时的扫水记录
                    if (createTime.plusHours(4).isBefore(now)) {
                        toRemove.add(json);
                    }
                } catch (Exception e) {
                    log.warn("清理扫水数据失败，无法解析 JSON: {}", json);
                }
            }

            if (!toRemove.isEmpty()) {
                sweepList.removeAll(toRemove);
                log.info("清理扫水数据：用户{}，清除记录{}条", username, toRemove.size());
            }
        }
    }

}
