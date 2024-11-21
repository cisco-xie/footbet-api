package com.example.demo.api;

import cn.hutool.core.bean.BeanUtil;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ConfigService {

    @Resource
    private RedissonClient redisson;
    @Autowired
    private RedisTemplate<Object, Object> redisTemplate;

    /**
     * 设置账号代理
     * @param userProxy
     */
    public void user(ConfigUserVO userProxy) {
        UserConfig proxy = BeanUtil.copyProperties(userProxy, UserConfig.class);
        if (BeanUtil.isNotEmpty(proxy)) {
            redisson.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, userProxy.getAccount())).set(JSONUtil.toJsonStr(proxy));
        }
    }

    /**
     * 设置账号代理
     * @param plan
     */
    public void plan(ConfigPlanVO plan) {
        if (BeanUtil.isNotEmpty(plan)) {
            String key = KeyUtil.genKey(RedisConstants.USER_PLAN_PREFIX, plan.getAccount(), plan.getLottery());

            // 获取自增 ID 的 Redis key
            String idKey = KeyUtil.genKey(RedisConstants.USER_PLAN_ID_PREFIX, plan.getAccount(), plan.getLottery());

            // 从 Redis 获取现有的数组
            RBucket<String> bucket = redisson.getBucket(key);
            String jsonArray = bucket.get();
            List<ConfigPlanVO> planList = new ArrayList<>();

            if (StringUtils.isNotBlank(jsonArray)) {
                // 将 JSON 数组转为对象列表
                planList = JSONUtil.toList(jsonArray, ConfigPlanVO.class);
            }

            if (plan.getId() == null) {
                // 如果传入的 ID 为空，表示新增
                long newId = redisson.getAtomicLong(idKey).incrementAndGet(); // 生成自增 ID
                if (newId > Integer.MAX_VALUE) {
                    log.error("生成的 ID 超出 Integer 范围: {}", newId);
                    throw new BusinessException(SystemError.SYS_400);
                }
                plan.setId((int) newId); // 将 long 转为 Integer 类型
                planList.add(plan);
            } else {
                // 如果传入的 ID 不为空，表示修改
                boolean updated = false;
                for (int i = 0; i < planList.size(); i++) {
                    ConfigPlanVO existingPlan = planList.get(i);
                    if (existingPlan.getId().equals(plan.getId())) {
                        // 更新已有对象
                        planList.set(i, plan);
                        updated = true;
                        break;
                    }
                }

                if (!updated) {
                    log.warn("更新失败：未找到 ID 为 {} 的计划对象", plan.getId());
                    return; // 找不到对应 ID，直接返回
                }
            }

            // 将更新后的列表保存回 Redis
            bucket.set(JSONUtil.toJsonStr(planList));
        }
    }

    /**
     * 获取所有用户的所有方案
     */
    public void getPlan() {
        redisson.getBucket(KeyUtil.genKey(RedisConstants.USER_PLAN_PREFIX));
    }
    public List<List<ConfigPlanVO>> getAllPlans() {
        // 匹配所有用户和 lottery 的 Redis Key
        String pattern = KeyUtil.genKey(RedisConstants.USER_PLAN_PREFIX, "*", "*");

        // 使用 Redisson 执行扫描操作
        RKeys keys = redisson.getKeys();
        Iterable<String> iterableKeys = keys.getKeysByPattern(pattern);

        List<String> keysList = new ArrayList<>();
        iterableKeys.forEach(keysList::add);

        // 批量获取所有方案
        List<List<ConfigPlanVO>> plans = new ArrayList<>();
        for (String key : keysList) {
            // 使用 Redisson 获取每个 key 的数据
            String json = (String) redisson.getBucket(key).get();
            if (json != null) {
                // 解析 JSON 为 ConfigPlanVO 对象
                List<ConfigPlanVO> plan = JSONUtil.toList(json, ConfigPlanVO.class);
                plans.add(plan);
            }
        }

        return plans;
    }






}
