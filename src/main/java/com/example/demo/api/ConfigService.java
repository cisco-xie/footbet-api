package com.example.demo.api;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.example.demo.common.constants.RedisConstants;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.common.utils.KeyUtil;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.core.model.UserConfig;
import com.example.demo.model.vo.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class ConfigService {

    @Resource
    private RedissonClient redisson;

    /**
     * 设置账号代理
     * @param userProxy
     */
    public void editAccount(String username, ConfigUserVO userProxy) {
        // 将 VO 转换为实体类
        UserConfig proxy = BeanUtil.copyProperties(userProxy, UserConfig.class);

        // Redis 键值
        String redisKey = KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, userProxy.getAccount());

        // 判断 Redis 中是否存在该用户数据
        boolean exists = redisson.getBucket(redisKey).isExists();

        // 校验 baseUrl 的最后一个字符是否为 "/"
        String baseUrl = userProxy.getBaseUrl();
        if (baseUrl != null && !baseUrl.endsWith("/")) {
            baseUrl += "/";
            userProxy.setBaseUrl(baseUrl);
            proxy.setBaseUrl(baseUrl); // 确保实体类同步
        }
        if ("add".equalsIgnoreCase(userProxy.getOperationType())) {
            // 新增逻辑
            if (exists) {
                log.warn("用户 [{}] 已存在，无法新增。", userProxy.getAccount());
                throw new BusinessException(SystemError.USER_1003, userProxy.getAccount());
            } else {
                redisson.getBucket(redisKey).set(JSONUtil.toJsonStr(proxy));
                log.info("用户 [{}] 新增成功。", userProxy.getAccount());
            }
        } else if ("update".equalsIgnoreCase(userProxy.getOperationType())) {
            // 修改逻辑
            if (exists) {
                // token保持，防止更新代理时把token置为空
                UserConfig userConfig = JSONUtil.toBean(JSONUtil.parseObj(redisson.getBucket(redisKey).get()), UserConfig.class);
                proxy.setToken(userConfig.getToken());
                redisson.getBucket(redisKey).set(JSONUtil.toJsonStr(proxy));
                log.info("用户 [{}] 修改成功。", userProxy.getAccount());
            } else {
                log.warn("用户 [{}] 不存在，无法修改。", userProxy.getAccount());
                throw new BusinessException(SystemError.USER_1004, userProxy.getAccount());
            }
        } else {
            throw new BusinessException(SystemError.SYS_400);
        }
    }

    /**
     * 删除账号
     * @param account
     */
    public void deleteAccount(String username, String account) {
        redisson.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, account)).delete();
    }

    /**
     * 获取所有账号
     */
    public List<UserConfig> accounts(String username, String account) {
        // 使用通配符获取所有匹配的键
        Iterable<String> keys = redisson.getKeys().getKeysByPattern(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, StringUtils.isNotBlank(account) ? account : "*"));

        List<UserConfig> userList = new ArrayList<>();
        for (String key : keys) {
            // 根据键值获取数据
            String userJson = (String) redisson.getBucket(key).get();
            if (userJson != null) {
                // 将 JSON 转换为对象
                UserConfig userConfig = JSONUtil.toBean(userJson, UserConfig.class);
                userList.add(userConfig);
            }
        }
        // 返回所有用户列表
        return userList;
    }

    /**
     * 设置账号方案
     * @param plan
     */
    public void plan(String username, ConfigPlanVO plan) {
        String key = null;
        if (BeanUtil.isNotEmpty(plan)) {
            // 设置自增 ID 的 Redis key
            String idKey = KeyUtil.genKey(RedisConstants.USER_PLAN_ID_PREFIX, username);
            ConfigPlanVO configPlan = new ConfigPlanVO();
            if (plan.getId() == null) {
                // 如果传入的 ID 为空，表示新增
                long newId = redisson.getAtomicLong(idKey).incrementAndGet(); // 生成自增 ID
                if (newId > Integer.MAX_VALUE) {
                    log.error("生成的 ID 超出 Integer 范围: {}", newId);
                    throw new BusinessException(SystemError.SYS_400);
                }
                plan.setId((int) newId); // 将 long 转为 Integer 类型
            }
            // 更新对象
            configPlan = plan;
            key = KeyUtil.genKey(RedisConstants.USER_PLAN_PREFIX, username, plan.getLottery(), String.valueOf(plan.getId()));
            // 将更新后的列表保存回 Redis
            redisson.getBucket(key).set(JSONUtil.toJsonStr(configPlan));
        }
    }

    /**
     * 删除配置
     * @param plans
     */
    public void planDel(String username, List<ConfigPlanVO> plans) {
        if (CollUtil.isEmpty(plans)) {
            throw new BusinessException(SystemError.SYS_402);
        }
        plans.forEach(plan -> {
            // 检查传入计划对象是否为空
            if (BeanUtil.isEmpty(plan) || plan.getId() == null) {
                throw new BusinessException(SystemError.SYS_402);
            }

            // 生成 Redis 键
            String key = KeyUtil.genKey(RedisConstants.USER_PLAN_PREFIX, username, String.valueOf(plan.getId()));
            redisson.getBucket(key).delete();
        });
    }


    /**
     * 获取用户的所有方案
     */
    public List<ConfigPlanVO> getAllPlans(String username, String lottery) {
        // 匹配所有用户和 lottery 的 Redis Key
        String pattern = KeyUtil.genKey(RedisConstants.USER_PLAN_PREFIX, StringUtils.isNotBlank(username) ? username : "*", StringUtils.isNotBlank(lottery) ? lottery : "*", "*");

        // 使用 Redisson 执行扫描操作
        RKeys keys = redisson.getKeys();
        Iterable<String> iterableKeys = keys.getKeysByPattern(pattern);

        List<String> keysList = new ArrayList<>();
        iterableKeys.forEach(keysList::add);

        // 批量获取所有方案
        List<ConfigPlanVO> plans = new ArrayList<>();
        for (String key : keysList) {
            // 使用 Redisson 获取每个 key 的数据
            String json = (String) redisson.getBucket(key).get();
            if (json != null) {
                // 解析 JSON 为 ConfigPlanVO 对象
                plans.add(JSONUtil.toBean(json, ConfigPlanVO.class));
            }
        }

        return plans;
    }






}
