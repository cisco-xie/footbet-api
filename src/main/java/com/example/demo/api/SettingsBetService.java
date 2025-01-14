package com.example.demo.api;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import com.example.demo.common.constants.RedisConstants;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.common.utils.KeyUtil;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.model.dto.settings.*;
import com.example.demo.model.vo.WebsiteVO;
import com.example.demo.model.vo.settings.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class SettingsBetService {

    @Resource(name = "defaultRedissonClient")
    private RedissonClient redisson;

    @Resource(name = "businessPlatformRedissonClient")
    private RedissonClient businessPlatformRedissonClient;

    /**
     * 获取常规设置-投注限制
     * @param username
     * @return
     */
    public LimitDTO getLimit(String username) {

        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_BET_LIMIT_PREFIX, username);

        // 从 Redis 中获取 List 数据
        String json = (String) businessPlatformRedissonClient.getBucket(key).get();

        if (StringUtils.isBlank(json)) {
            return null;
        }
        return JSONUtil.toBean(json, LimitDTO.class);
    }

    /**
     * 修改常规设置-投注限制
     * @param username
     * @return
     */
    public void saveLimit(String username, LimitVO limitVO) {
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_BET_LIMIT_PREFIX, username);
        // 从 Redis 中获取数据
        businessPlatformRedissonClient.getBucket(key).set(JSONUtil.toJsonStr(limitVO));
    }

    /**
     * 获取常规设置-投注间隔时间
     * @param username
     * @return
     */
    public IntervalDTO getInterval(String username) {

        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_BET_INTERVAL_PREFIX, username);

        // 从 Redis 中获取 List 数据
        String json = (String) businessPlatformRedissonClient.getBucket(key).get();

        if (StringUtils.isBlank(json)) {
            return null;
        }
        return JSONUtil.toBean(json, IntervalDTO.class);
    }

    /**
     * 修改常规设置-投注间隔时间
     * @param username
     * @return
     */
    public void saveInterval(String username, IntervalVO intervalVO) {
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_BET_INTERVAL_PREFIX, username);
        // 从 Redis 中获取数据
        businessPlatformRedissonClient.getBucket(key).set(JSONUtil.toJsonStr(intervalVO));
    }

    /**
     * 获取常规设置-盘口类型过滤选项
     * @param username
     * @return
     */
    public TypeFilterDTO getTypeFilter(String username) {

        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_BET_TYPEFILTER_PREFIX, username);

        // 从 Redis 中获取 List 数据
        String json = (String) businessPlatformRedissonClient.getBucket(key).get();

        if (StringUtils.isBlank(json)) {
            return null;
        }
        return JSONUtil.toBean(json, TypeFilterDTO.class);
    }

    /**
     * 修改常规设置-盘口类型过滤选项
     * @param username
     * @return
     */
    public void saveTypeFilter(String username, TypeFilterVO typeFilterVO) {
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_BET_TYPEFILTER_PREFIX, username);
        // 从 Redis 中获取数据
        businessPlatformRedissonClient.getBucket(key).set(JSONUtil.toJsonStr(typeFilterVO));
    }

    /**
     * 获取常规设置-针对性优化设定
     * @param username
     * @return
     */
    public OptimizingDTO getOptimizing(String username) {

        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_BET_OPTIMIZING_PREFIX, username);

        // 从 Redis 中获取 List 数据
        String json = (String) businessPlatformRedissonClient.getBucket(key).get();

        if (StringUtils.isBlank(json)) {
            return null;
        }
        return JSONUtil.toBean(json, OptimizingDTO.class);
    }

    /**
     * 修改常规设置-针对性优化设定
     * @param username
     * @return
     */
    public void saveOptimizing(String username, OptimizingVO optimizingVO) {
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_BET_OPTIMIZING_PREFIX, username);
        // 从 Redis 中获取数据
        businessPlatformRedissonClient.getBucket(key).set(JSONUtil.toJsonStr(optimizingVO));
    }
}
