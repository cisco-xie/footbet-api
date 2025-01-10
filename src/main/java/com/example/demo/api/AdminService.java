package com.example.demo.api;

import cn.hutool.core.bean.BeanUtil;
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
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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

}
