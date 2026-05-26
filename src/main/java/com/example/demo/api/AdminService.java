package com.example.demo.api;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.jwt.JWTUtil;
import com.example.demo.common.constants.RedisConstants;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.common.utils.KeyUtil;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.vo.AdminLoginVO;
import com.example.demo.model.vo.AdminUserBetVO;
import com.example.demo.task.AutoProxyTask;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AdminService {

    @Resource(name = "defaultRedissonClient")
    private RedissonClient redisson;

    @Resource(name = "businessPlatformRedissonClient")
    private RedissonClient businessPlatformRedissonClient;

    @Resource
    private AutoProxyTask autoProxyTask;

    public AdminLoginDTO getAdmin(String username) {
        // Redis 键值
        String redisKey = KeyUtil.genKey(RedisConstants.PLATFORM_USER_PREFIX, username);
        return JSONUtil.toBean(JSONUtil.parseObj(businessPlatformRedissonClient.getBucket(redisKey).get()), AdminLoginDTO.class);
    }

    /**
     * 获取所有平台用户
     * @return
     */
    public List<AdminLoginDTO> getUsers(String group) {

        // 匹配所有平台用户的 Redis Key
        String pattern = KeyUtil.genKey(RedisConstants.PLATFORM_USER_PREFIX, "*");

        RKeys keys = businessPlatformRedissonClient.getKeys();

        // 使用 SCAN 命令代替 KEYS 命令，避免阻塞
        Iterator<String> iterableKeys = keys.getKeysByPattern(pattern, 100).iterator();

        List<String> keysList = new ArrayList<>();

        while (iterableKeys.hasNext()) {
            keysList.add(iterableKeys.next());
        }

        if (keysList.isEmpty()) {
            return Collections.emptyList();
        }

        // =========================
        // 使用 Redisson Pipeline 批量获取
        // =========================
        RBatch batch = businessPlatformRedissonClient.createBatch();

        Map<String, RFuture<Object>> futureMap = new HashMap<>(keysList.size());

        for (String key : keysList) {
            RBucketAsync<Object> bucket = batch.getBucket(key);
            futureMap.put(key, bucket.getAsync());
        }

        // 一次性发送
        batch.execute();

        // 解析结果
        List<AdminLoginDTO> result = new ArrayList<>(keysList.size());

        for (RFuture<Object> future : futureMap.values()) {

            Object obj = future.getNow();

            if (obj == null) {
                continue;
            }

            try {
                AdminLoginDTO user = JSONUtil.toBean(obj.toString(), AdminLoginDTO.class);

                // group 过滤
                if (StringUtils.isBlank(group) || group.equals(user.getGroup())) {
                    result.add(user);
                }

            } catch (Exception e) {
                log.warn("解析用户数据失败: {}", obj, e);
            }
        }

        return result;
    }

    public AdminLoginDTO adminLogin(AdminLoginVO login) {

        // Redis 键值
        String redisKey = KeyUtil.genKey(RedisConstants.PLATFORM_USER_PREFIX, login.getUsername());

        // 判断 Redis 中是否存在该用户数据
        boolean exists = businessPlatformRedissonClient.getBucket(redisKey).isExists();
        if (exists) {
            AdminLoginDTO adminLogin = JSONUtil.toBean(JSONUtil.parseObj(businessPlatformRedissonClient.getBucket(redisKey).get()), AdminLoginDTO.class);
            if (StringUtils.equals(login.getPassword(), adminLogin.getPassword())) {
                String token = JWTUtil.createToken(BeanUtil.beanToMap(adminLogin), "admin".getBytes());
                adminLogin.setAccessToken(token);
                adminLogin.setRefreshToken(token);
                return adminLogin;
            } else {
                throw new BusinessException(SystemError.USER_1008);
            }
        } else {
            throw new BusinessException(SystemError.USER_1008);
        }
    }

    /**
     * 编辑用户投注相关配置
     * @param username
     * @param adminBetVO
     */
    public void editBet(String username, AdminUserBetVO adminBetVO) {
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_USER_PREFIX, username);

        // 判断 Redis 中是否存在该用户数据
        boolean exists = businessPlatformRedissonClient.getBucket(key).isExists();
        if (exists) {
            AdminLoginDTO adminLogin = JSONUtil.toBean(JSONUtil.parseObj(businessPlatformRedissonClient.getBucket(key).get()), AdminLoginDTO.class);
            adminLogin.setStatus(adminBetVO.getStatus());
            adminLogin.setStopBet(adminBetVO.getStopBet());
            adminLogin.setSimulateBet(adminBetVO.getSimulateBet());
            businessPlatformRedissonClient.getBucket(key).set(JSONUtil.toJsonStr(adminLogin));
            // ✅ 投注开关变化，触发 AutoProxyTask 检查任务
            autoProxyTask.handleUserBetChange(adminLogin);
        } else {
            throw new BusinessException(SystemError.USER_1004);
        }
    }

    /**
     * 获取已开启用户
     * @return
     */
    public List<AdminLoginDTO> getEnableUsers() {
        // 匹配所有平台用户的 Redis Key
        String pattern = KeyUtil.genKey(RedisConstants.PLATFORM_USER_PREFIX, "*");
        // 使用 Redisson 执行扫描所有平台用户操作
        RKeys keys = businessPlatformRedissonClient.getKeys();
        // 使用 SCAN 命令代替 KEYS 命令，避免阻塞
        Iterator<String> iterableKeys = keys.getKeysByPattern(pattern, 100).iterator();
        List<String> keysList = new ArrayList<>();
        while (iterableKeys.hasNext()) {
            keysList.add(iterableKeys.next());
        }
        return keysList.stream()
                .map(key -> {
                    String json = (String) businessPlatformRedissonClient.getBucket(key).get();
                    return JSONUtil.toBean(json, AdminLoginDTO.class);
                })
                .filter(user -> user.getStatus() == 1) // 筛选出开启状态的用户
                .collect(Collectors.toList());
    }

    public List<String> getGroup() {

        String pattern = KeyUtil.genKey(RedisConstants.PLATFORM_ADMIN_GROUP_PREFIX, "*");

        RKeys keys = businessPlatformRedissonClient.getKeys();

        // 使用 SCAN 命令代替 KEYS 命令，避免阻塞
        Iterator<String> iterableKeys = keys.getKeysByPattern(pattern, 100).iterator();

        Set<String> groupSet = new HashSet<>();

        while (iterableKeys.hasNext()) {

            String key = iterableKeys.next();

            int first = key.indexOf(':');
            int second = key.indexOf(':', first + 1);

            if (second != -1 && second + 1 < key.length()) {
                groupSet.add(key.substring(second + 1));
            }
        }

        return new ArrayList<>(groupSet);
    }

    public void addGroup(String group) {
        businessPlatformRedissonClient.getBucket(KeyUtil.genKey(RedisConstants.PLATFORM_ADMIN_GROUP_PREFIX, group)).set(1);
    }


    public void add(List<AdminLoginVO> admins) {
        // 读取配置文件
        String xinbaoStr = ResourceUtil.readUtf8Str("setting/xinbao.json");
        // 读取配置文件
        String pingboStr = ResourceUtil.readUtf8Str("setting/pingbo.json");
        // 读取配置文件
        String sboStr = ResourceUtil.readUtf8Str("setting/sbo.json");
        // 读取配置文件
        String settingStr = ResourceUtil.readUtf8Str("setting/default-user-setting.json");
        JSONObject settings = JSONUtil.parseObj(settingStr);
        JSONObject limit = settings.getJSONObject("limit");
        JSONObject interval = settings.getJSONObject("interval");
        JSONObject optimizing = settings.getJSONObject("optimizing");
        JSONObject typeFilter = settings.getJSONObject("typefilter");
        JSONObject timeframe = settings.getJSONObject("timeframe");
        JSONArray oddsrange = settings.getJSONArray("oddsrange");
        JSONObject amount = settings.getJSONObject("amount");
        JSONObject oddsScan = settings.getJSONObject("oddsscan");
        JSONObject profit = settings.getJSONObject("profit");
        for (AdminLoginVO admin : admins) {
            if (null == admin.getUsername()) {
                continue;
            }
            List<String> roles = new ArrayList<>();
            if (null == admin.getRoles() || admin.getRoles().isEmpty()) {
                // 默认普通用户角色
                roles = List.of("common");
            } else if (admin.getRoles().contains("sweepwater")) {
                // 首先检查sweepwater角色是否已存在
                boolean sweepwaterExists = getUsers(null).stream()
                        .anyMatch(user -> user.getRoles() != null && user.getRoles().contains("sweepwater"));
                if (sweepwaterExists) {
                    throw new BusinessException(SystemError.USER_1018);
                }
            } else {
                roles = List.of(admin.getRoles());
            }
            String key = KeyUtil.genKey(RedisConstants.PLATFORM_USER_PREFIX, admin.getUsername());
            AdminLoginDTO adminLoginDTO = new AdminLoginDTO();
            adminLoginDTO.setUsername(admin.getUsername());
            adminLoginDTO.setNickname(admin.getUsername());
            adminLoginDTO.setPassword(admin.getPassword());
            adminLoginDTO.setPlatform(3);
            adminLoginDTO.setGroup(admin.getGroup());
            adminLoginDTO.setExpires("2030/10/30 00:00:00");
            adminLoginDTO.setRoles(roles);
            adminLoginDTO.setPermissions(List.of("*:*:*"));
            businessPlatformRedissonClient.getBucket(key).set(JSONUtil.toJsonStr(adminLoginDTO));

            // 生成 Redis 中的 key
            String websiteKey = KeyUtil.genKey(RedisConstants.PLATFORM_WEBSITE_ALL_PREFIX, admin.getUsername());
            businessPlatformRedissonClient.getList(websiteKey).add(JSONUtil.parseObj(xinbaoStr));
            businessPlatformRedissonClient.getList(websiteKey).add(JSONUtil.parseObj(pingboStr));
            businessPlatformRedissonClient.getList(websiteKey).add(JSONUtil.parseObj(sboStr));
            // 配置默认软件设置信息
            String oddsScanKey = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_GENERAL_ODDSSCAN_PREFIX, admin.getUsername());
            businessPlatformRedissonClient.getBucket(oddsScanKey).set(oddsScan);
            String profitKey = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_GENERAL_PROFIT_PREFIX, admin.getUsername());
            businessPlatformRedissonClient.getBucket(profitKey).set(profit);
            String amountKey = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_GENERAL_AMOUNT_PREFIX, admin.getUsername());
            businessPlatformRedissonClient.getBucket(amountKey).set(amount);
            String limitKey = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_BET_LIMIT_PREFIX, admin.getUsername());
            businessPlatformRedissonClient.getBucket(limitKey).set(limit);
            String intervalKey = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_BET_INTERVAL_PREFIX, admin.getUsername());
            businessPlatformRedissonClient.getBucket(intervalKey).set(interval);
            String typeFilterKey = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_BET_TYPEFILTER_PREFIX, admin.getUsername());
            businessPlatformRedissonClient.getBucket(typeFilterKey).set(typeFilter);
            String optimizingKey = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_BET_OPTIMIZING_PREFIX, admin.getUsername());
            businessPlatformRedissonClient.getBucket(optimizingKey).set(optimizing);
            String timeframeKey = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_FILTER_TIMEFRAME_PREFIX, admin.getUsername());
            businessPlatformRedissonClient.getList(timeframeKey).add(timeframe);
            String oddsrangeKey = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_FILTER_ODDSRANGE_PREFIX, admin.getUsername());
            businessPlatformRedissonClient.getList(oddsrangeKey).addAll(oddsrange);
        }
    }

    public void delUser(String username) {
        businessPlatformRedissonClient.getBucket(KeyUtil.genKey(RedisConstants.PLATFORM_USER_PREFIX, username)).delete();
    }

    public void changePassword(String username, String oldPassword, String newPassword) {
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_USER_PREFIX, username);
        boolean exists = businessPlatformRedissonClient.getBucket(key).isExists();
        if (!exists) {
            throw new BusinessException(SystemError.USER_1004);
        }
        AdminLoginDTO adminLogin = JSONUtil.toBean(JSONUtil.parseObj(businessPlatformRedissonClient.getBucket(key).get()), AdminLoginDTO.class);
        if (!StringUtils.equals(oldPassword, adminLogin.getPassword())) {
            throw new BusinessException(SystemError.USER_1019);
        }
        adminLogin.setPassword(newPassword);
        businessPlatformRedissonClient.getBucket(key).set(JSONUtil.toJsonStr(adminLogin));
    }

}
