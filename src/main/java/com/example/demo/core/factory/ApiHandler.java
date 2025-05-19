package com.example.demo.core.factory;

import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import org.springframework.http.HttpEntity;

import java.util.Map;

/**
 * API 处理器接口
 */
public interface ApiHandler {
    HttpEntity<String> buildRequest(JSONObject params); // 构建请求
    Map<String, Object> parseResponse(JSONObject params, HttpResponse response);        // 解析响应,带请求参数版
    JSONObject execute(JSONObject params); // 发送请求
}
