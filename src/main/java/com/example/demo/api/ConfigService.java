package com.example.demo.api;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
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
import org.redisson.api.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class ConfigService {

    @Resource
    private RedissonClient redisson;

    /**
     * 设置账号代理
     * @param userProxy
     */
    public String editAccount(String username, ConfigUserVO userProxy) {
        // 校验并修正 baseUrl
        String baseUrl = userProxy.getBaseUrl();
        if (StringUtils.isNotBlank(baseUrl) && !baseUrl.endsWith("/")) {
            baseUrl += "/";
            userProxy.setBaseUrl(baseUrl);
        }

        // VO 转实体
        UserConfig proxy = BeanUtil.copyProperties(userProxy, UserConfig.class);
        proxy.setBaseUrl(baseUrl);

        String id = StringUtils.isNotBlank(proxy.getId()) ? proxy.getId() : IdUtil.getSnowflakeNextIdStr();
        // Redis 键值生成
        String redisKey = KeyUtil.genKey(
                RedisConstants.USER_PROXY_PREFIX,
                username,
                id
        );

        // 操作类型处理
        if ("add".equalsIgnoreCase(userProxy.getOperationType())) {
            if (redisson.getBucket(redisKey).isExists()) {
                log.warn("用户 [{}] 已存在，无法新增。", userProxy.getAccount());
                throw new BusinessException(SystemError.USER_1003, userProxy.getAccount());
            }

            // 设置 ID 并保存到 Redis
            proxy.setId(id);
            redisson.getBucket(redisKey).set(JSONUtil.toJsonStr(proxy));
            log.info("用户 [{}] 新增成功。", userProxy.getAccount());
        } else if ("update".equalsIgnoreCase(userProxy.getOperationType())) {
            if (!redisson.getBucket(redisKey).isExists()) {
                log.warn("用户 [{}] 不存在，无法修改。", userProxy.getAccount());
                throw new BusinessException(SystemError.USER_1004, userProxy.getAccount());
            }

            // 保留 token 并更新
            UserConfig existingConfig = JSONUtil.toBean(
                    JSONUtil.parseObj(redisson.getBucket(redisKey).get()),
                    UserConfig.class
            );
            proxy.setToken(existingConfig.getToken());
            redisson.getBucket(redisKey).set(JSONUtil.toJsonStr(proxy));
            log.info("用户 [{}] 修改成功。", userProxy.getAccount());

        } else {
            throw new BusinessException(SystemError.SYS_400, "不支持的操作类型: " + userProxy.getOperationType());
        }
        return id;
    }

    /**
     * 删除账号
     * @param id
     */
    public void deleteAccount(String username, String id) {
        redisson.getBucket(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, id)).delete();
    }

    /**
     * 获取所有账号
     */
    public List<UserConfig> accounts(String username, String id) {
        // 使用通配符获取所有匹配的键
        Iterable<String> keys = redisson.getKeys().getKeysByPattern(KeyUtil.genKey(RedisConstants.USER_PROXY_PREFIX, username, StringUtils.isNotBlank(id) ? id : "*"));

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
            if (CollUtil.isEmpty(plan.getPositions())
                    && CollUtil.isEmpty(plan.getTwoSidedDxPositions())
                    && CollUtil.isEmpty(plan.getTwoSidedDsPositions())
                    && CollUtil.isEmpty(plan.getTwoSidedLhPositions())) {
                throw new BusinessException(SystemError.USER_1013);
            }
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
            String key = KeyUtil.genKey(RedisConstants.USER_PLAN_PREFIX, username, plan.getLottery(), String.valueOf(plan.getId()));
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

    public JSONArray failedBet(String username) {
        String pattern = KeyUtil.genKey(
                RedisConstants.USER_BET_PERIOD_RES_PREFIX,
                DateUtil.format(DateUtil.date(), "yyyyMMdd"),
                "*",
                "*",
                "failed",
                username,
                "*"
        );
        JSONArray result = new JSONArray();
        // 使用 Redisson 执行扫描所有平台用户操作
        RKeys keys = redisson.getKeys();
        Iterable<String> iterableKeys = keys.getKeysByPattern(pattern);
        List<String> keysList = new ArrayList<>();
        iterableKeys.forEach(keysList::add);
        keysList.forEach(key -> {
            // 使用 Redisson 获取每个 平台用户 的数据
            String json = (String) redisson.getBucket(key).get();
            if (json != null) {
                JSONObject jsonObject = JSONUtil.parseObj(json);
                JSONObject msg = new JSONObject();
                String[] parts = key.split(":");
                String account = parts[8];
                msg.putOpt("account", account);
                msg.putOpt("message", jsonObject.getStr("message"));
                msg.putOpt("lottery", jsonObject.getStr("lottery"));
                msg.putOpt("drawNumber", jsonObject.getStr("drawNumber"));
                msg.putOpt("createTime", jsonObject.getStr("createTime"));
                result.add(msg);
            }
        });
        return result;
    }

    /**
     * 获取最新失败投注
     * @param username
     * @return
     */
    public JSONObject failedBetLatest(String username) {
        JSONObject maxCreateTimeObj = new JSONObject();
        String maxCreateTime = null;
        JSONObject maxCreateTimeJson = null;

        // Redis key pattern
        String pattern = KeyUtil.genKey(
                RedisConstants.USER_BET_PERIOD_RES_PREFIX,
                DateUtil.format(DateUtil.date(), "yyyyMMdd"),
                "*",
                "*",
                "failed",
                username,
                "*"
        );

        RKeys keys = redisson.getKeys();
        Iterable<String> iterableKeys = keys.getKeysByPattern(pattern);
        List<String> keysList = new ArrayList<>();
        iterableKeys.forEach(keysList::add);

        if (!keysList.isEmpty()) {
            // 创建批量任务
            RBatch batch = redisson.createBatch();
            Map<String, RFuture<Object>> futures = new HashMap<>();

            // 将所有 Redis Key 加入批量任务
            for (String key : keysList) {
                RFuture<Object> future = batch.getBucket(key).getAsync();
                futures.put(key, future);
            }

            // 执行批量任务
            batch.execute();

            // 遍历批量结果
            for (Map.Entry<String, RFuture<Object>> entry : futures.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue().getNow(); // 获取异步结果
                if (value != null) {
                    String json = value.toString();
                    JSONObject jsonObject = JSONUtil.parseObj(json);
                    String currentCreateTime = jsonObject.getStr("createTime");

                    // 查找最大的 createTime
                    if (maxCreateTime == null || currentCreateTime.compareTo(maxCreateTime) > 0) {
                        maxCreateTime = currentCreateTime;
                        maxCreateTimeJson = jsonObject;
                        maxCreateTimeObj.putOpt("key", key);
                    }
                }
            }

            // 校验最大时间记录的 confirm 字段是否为 0
            if (maxCreateTimeJson.containsKey("confirm") && maxCreateTimeJson != null && maxCreateTimeJson.getInt("confirm") == 0) {
                maxCreateTimeObj.putOpt("value", maxCreateTimeJson);
            } else {
                // 如果没有符合条件的记录，返回空对象
                maxCreateTimeObj = new JSONObject();
            }
        }

        return maxCreateTimeObj;
    }

    /**
     * 确认下注失败弹窗提示
     * @param redisKey
     */
    public void failedBetLatestConfirm(String redisKey) {
        if (!redisson.getBucket(redisKey).isExists()) {
            log.warn("redis key [{}] 不存在", redisKey);
        }
        JSONObject jsonObject = JSONUtil.parseObj(redisson.getBucket(redisKey).get());
        jsonObject.putOpt("confirm", 1);
        redisson.getBucket(redisKey).set(jsonObject);
    }

}
