package com.example.demo.api;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.io.resource.ResourceUtil;
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
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AdminService {

    @Resource(name = "defaultRedissonClient")
    private RedissonClient redisson;

    @Resource(name = "businessPlatformRedissonClient")
    private RedissonClient businessPlatformRedissonClient;

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
        // 使用 Redisson 执行扫描所有平台用户操作
        RKeys keys = businessPlatformRedissonClient.getKeys();
        Iterator<String> iterableKeys = keys.getKeysByPattern(pattern).iterator();
        List<String> keysList = new ArrayList<>();
        while (iterableKeys.hasNext()) {
            keysList.add(iterableKeys.next());
        }
        return keysList.stream()
                .map(key -> {
                    String json = (String) businessPlatformRedissonClient.getBucket(key).get();
                    return JSONUtil.toBean(json, AdminLoginDTO.class);
                })
                .filter(user -> StringUtils.isBlank(group) || group.equals(user.getGroup())) // 如果 group 为空，跳过过滤
                .collect(Collectors.toList());
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
        Iterator<String> iterableKeys = keys.getKeysByPattern(pattern).iterator();
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
        // 匹配所有平台用户的 Redis Key
        String pattern = KeyUtil.genKey(RedisConstants.PLATFORM_ADMIN_GROUP_PREFIX, "*");
        // 使用 Redisson 执行扫描所有平台用户操作
        RKeys keys = businessPlatformRedissonClient.getKeys();
        Iterator<String> iterableKeys = keys.getKeysByPattern(pattern).iterator();
        List<String> groupList = new ArrayList<>();
        // 遍历所有匹配的键
        while (iterableKeys.hasNext()) {
            String[] parts = iterableKeys.next().split(":");
            // 检查数组长度，确保索引存在
            if (parts.length > 2) {
                groupList.add(parts[2]);
            }
        }
        return groupList;
    }

    public void addGroup(String group) {
        businessPlatformRedissonClient.getBucket(KeyUtil.genKey(RedisConstants.PLATFORM_ADMIN_GROUP_PREFIX, group)).set(1);
    }


    public void add(List<AdminLoginVO> admins) {
        // 读取配置文件
        String settingStr = ResourceUtil.readUtf8Str("setting/default-user-setting.json");
        JSONObject settings = JSONUtil.parseObj(settingStr);
        JSONObject limit = settings.getJSONObject("limit");
        JSONObject interval = settings.getJSONObject("interval");
        JSONObject optimizing = settings.getJSONObject("optimizing");
        JSONObject typeFilter = settings.getJSONObject("typefilter");
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
        }
    }

    public void delUser(String username) {
        businessPlatformRedissonClient.getBucket(KeyUtil.genKey(RedisConstants.PLATFORM_USER_PREFIX, username)).delete();
    }

}
