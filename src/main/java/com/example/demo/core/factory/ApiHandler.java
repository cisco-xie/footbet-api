package com.example.demo.core.factory;

import cn.hutool.json.JSONObject;
import org.springframework.http.HttpEntity;

import java.util.Map;

/**
 * API 处理器接口
 */
public interface ApiHandler {
    HttpEntity<String> buildRequest(Map<String, Object> params); // 构建请求
    Map<String, Object> parseResponse(String responseBody);      // 解析响应
    JSONObject handleLogin(Map<String, Object> params); // 发送请求
}
