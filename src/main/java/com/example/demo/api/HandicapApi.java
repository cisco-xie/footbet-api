package com.example.demo.api;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.common.constants.RedisConstants;
import com.example.demo.common.utils.KeyUtil;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.core.factory.WebsiteApiFactory;
import com.example.demo.core.factory.WebsiteFactoryManager;
import com.example.demo.core.model.UserConfig;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.vo.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class HandicapApi {

    @Resource(name = "defaultRedissonClient")
    private RedissonClient redisson;

    @Resource(name = "businessPlatformRedissonClient")
    private RedissonClient businessPlatformRedissonClient;

    @Resource
    private WebsiteFactoryManager factoryManager;

    @Resource
    private AdminService adminService;

    @Resource
    private ConfigAccountService accountService;

    /**
     * 盘口账号自动登录
     */
    public void login() {
        List<AdminLoginDTO> users = adminService.getEnableUsers();
        for (AdminLoginDTO user : users) {
            List<String> jsonList = businessPlatformRedissonClient.getList(KeyUtil.genKey(RedisConstants.PLATFORM_WEBSITE_ALL_PREFIX, user.getUsername()));
            if (jsonList == null || jsonList.isEmpty()) {
                break;
            }
            // 将 List 中的 JSON 字符串反序列化为 WebSiteVO 列表
            List<WebsiteVO> websites =  jsonList.stream()
                    .map(json -> JSONUtil.toBean(json, WebsiteVO.class))
                    .filter(websiteVO -> websiteVO.getEnable() == 1)
                    .toList();
            for (WebsiteVO website : websites) {
                List<ConfigAccountVO> accounts = accountService.getAccount(user.getUsername(), website.getId());
                accounts = accounts.stream().filter(account -> account.getEnable() == 1 && account.getAutoLogin() == 1).toList();
                if (accounts.isEmpty()) {
                    break;
                }
                for (ConfigAccountVO account : accounts) {
                    WebsiteApiFactory factory = factoryManager.getFactory(website.getId());

                    ApiHandler apiHandler = factory.getLoginHandler();
                    if (apiHandler == null) {
                        break;
                    }
                    Map<String, Object> params = new HashMap<>();
                    // 根据不同站点传入不同的参数
                    if ("1874805533324103680".equals(website.getId())) {
                        params.put("loginId", account.getAccount());
                        params.put("password", account.getPassword());
                    } else if ("1874804932787851264".equals(website.getId()) || "1877702689064243200".equals(website.getId())) {
                        params.put("username", account.getAccount());
                        params.put("password", account.getPassword());
                    }
                    JSONObject result = apiHandler.handleLogin(params);
                    account.setToken(result);
                    accountService.saveAccount(user.getUsername(), website.getId(), account);
                }
            }
        };
    }


}
