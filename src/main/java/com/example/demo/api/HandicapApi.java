package com.example.demo.api;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.TimeInterval;
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
     * 所有盘口账号自动登录
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
                    TimeInterval timer = DateUtil.timer();
                    WebsiteApiFactory factory = factoryManager.getFactory(website.getId());

                    ApiHandler apiHandler = factory.getLoginHandler();
                    if (apiHandler == null) {
                        break;
                    }
                    JSONObject params = new JSONObject();
                    params.putOpt("adminUsername", user.getUsername());
                    params.putOpt("websiteId", website.getId());
                    // 根据不同站点传入不同的参数
                    if ("1874805533324103680".equals(website.getId())) {
                        params.putOpt("loginId", account.getAccount());
                        params.putOpt("password", account.getPassword());
                    } else if ("1874804932787851264".equals(website.getId()) || "1877702689064243200".equals(website.getId())) {
                        params.putOpt("username", account.getAccount());
                        params.putOpt("password", account.getPassword());
                    }
                    JSONObject result = apiHandler.execute(params);
                    account.setToken(result);
                    account.setExecuteMsg(result.get("msg") + "：" + timer.interval() + " ms");
                    accountService.saveAccount(user.getUsername(), website.getId(), account);
                }
            }
        };
    }

    /**
     * 根据平台用户和指定网站登录
     * 不限制网站或者账户状态是否开启
     * @param username
     * @param websiteId
     */
    public void loginByWebsite(String username, String websiteId) {
        List<ConfigAccountVO> accounts = accountService.getAccount(username, websiteId);
        if (accounts.isEmpty()) {
            return;
        }
        for (ConfigAccountVO account : accounts) {
            TimeInterval timer = DateUtil.timer();
            WebsiteApiFactory factory = factoryManager.getFactory(websiteId);

            ApiHandler apiHandler = factory.getLoginHandler();
            if (apiHandler == null) {
                break;
            }
            JSONObject params = new JSONObject();
            params.putOpt("adminUsername", username);
            params.putOpt("websiteId", websiteId);
            // 根据不同站点传入不同的参数
            if ("1874805533324103680".equals(websiteId)) {
                params.putOpt("loginId", account.getAccount());
                params.putOpt("password", account.getPassword());
            } else if ("1874804932787851264".equals(websiteId) || "1877702689064243200".equals(websiteId)) {
                params.putOpt("username", account.getAccount());
                params.putOpt("password", account.getPassword());
            }
            JSONObject result = apiHandler.execute(params);
            account.setIsTokenValid(result.getBool("success") ? 1 : 0);
            account.setToken(result);
            account.setExecuteMsg(result.get("msg") + "：" + timer.interval() + " ms");
            accountService.saveAccount(username, websiteId, account);
        }
    }

    public void info() {
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
                    TimeInterval timer = DateUtil.timer();
                    WebsiteApiFactory factory = factoryManager.getFactory(website.getId());

                    ApiHandler apiHandler = factory.getInfoHandler();
                    if (apiHandler == null) {
                        break;
                    }
                    JSONObject params = new JSONObject();
                    params.putOpt("adminUsername", user.getUsername());
                    params.putOpt("websiteId", website.getId());
                    // 根据不同站点传入不同的参数
                    if ("1874805533324103680".equals(website.getId())) {
                        params.putAll(account.getToken().getJSONObject("tokens"));
                    } else if ("1874804932787851264".equals(website.getId())) {
                        params.putOpt("token", "Bearer " + account.getToken().getStr("token"));
                    } else if ("1877702689064243200".equals(website.getId())) {
                        params.putAll(account.getToken().getJSONObject("serverresponse"));
                    }
                    JSONObject result = apiHandler.execute(params);
                    account.setBetCredit(result.getBigDecimal("betCredit"));
                    account.setExecuteMsg(result.get("msg") + "：" + timer.interval() + " ms");
                    accountService.saveAccount(user.getUsername(), website.getId(), account);
                }
            }
        };
    }

}
