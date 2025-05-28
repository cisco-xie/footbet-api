package com.example.demo.config;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.http.HttpRequest;
import com.example.demo.core.model.UserConfig;
import com.example.demo.model.vo.ConfigAccountVO;
import org.apache.commons.lang3.StringUtils;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;

/**
 * 代理封装配置
 */
public class HttpProxyConfig {

    /**
     * 封装代理
     * @param request
     * @param userConfig
     */
    public static void configureProxy(HttpRequest request, ConfigAccountVO userConfig) {
        if (BeanUtil.isNotEmpty(userConfig) && null != userConfig.getProxyType() && 0 != userConfig.getProxyType()) {
            // 统一设置代理认证
            if (StringUtils.isNotBlank(userConfig.getProxyUsername()) && StringUtils.isNotBlank(userConfig.getProxyPassword())) {
                Authenticator.setDefault(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(
                                userConfig.getProxyUsername(),
                                userConfig.getProxyPassword().toCharArray()
                        );
                    }
                });
            }

            // 设置代理主机和端口
            if (userConfig.getProxyType() == 1) {
                setHttpProxy(userConfig);
            } else if (userConfig.getProxyType() == 2) {
                setSocksProxy(userConfig);
            }

            // 禁用默认的 HTTP 认证隧道设置（针对 SOCKS 代理）
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");

            // 设置代理类型
            Proxy proxy = new Proxy(
                    userConfig.getProxyType() == 1 ? Proxy.Type.HTTP : Proxy.Type.SOCKS,
                    new InetSocketAddress(userConfig.getProxyHost(), userConfig.getProxyPort())
            );
            request.setProxy(proxy);
        }
    }

    private static void setHttpProxy(ConfigAccountVO userConfig) {
        System.setProperty("http.proxyHost", userConfig.getProxyHost());
        System.setProperty("http.proxyPort", String.valueOf(userConfig.getProxyPort()));
        System.setProperty("https.proxyHost", userConfig.getProxyHost());
        System.setProperty("https.proxyPort", String.valueOf(userConfig.getProxyPort()));
    }

    private static void setSocksProxy(ConfigAccountVO userConfig) {
        System.setProperty("socksProxyHost", userConfig.getProxyHost());
        System.setProperty("socksProxyPort", String.valueOf(userConfig.getProxyPort()));
    }

}
