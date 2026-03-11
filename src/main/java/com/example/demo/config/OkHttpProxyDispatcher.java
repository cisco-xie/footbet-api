package com.example.demo.config;

import com.example.demo.common.constants.Constants;
import com.example.demo.common.enmu.RequestPlatform;
import com.example.demo.model.vo.ConfigAccountVO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okhttp3.ConnectionPool;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

@Slf4j
@Component
public class OkHttpProxyDispatcher {

    private static final int MAX_FAIL = 3;
    private static final long COOLDOWN_MS = 10 * 1000;  // 10秒冷却
    private static final int MAX_RETRY = 0;                 // 最多重试次数

    private final ConcurrentHashMap<String, ProxyState> proxyStateMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OkHttpClient> clientMap = new ConcurrentHashMap<>();

    // 全局默认无代理的OkHttpClient，单例即可
    private final OkHttpClient defaultClient = new OkHttpClient.Builder()
            .followRedirects(false) // 禁用自动重定向，方便拿location
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)   // 整个调用最大超时
            .retryOnConnectionFailure(false)            // 🚫 禁用自动重试--开启（true）	网络不稳定、代理环境频繁断连、希望提升请求成功率///关闭（false）	业务请求非幂等、严格控制重试次数、希望错误直接抛出给业务层处理
//            .connectionPool(new ConnectionPool(20, 5, TimeUnit.MINUTES)) // 🚀 高并发支持
            .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS)) // 连接池大小为 0，避免复用
            .build();

    private OkHttpClient defaultClient() {
        return defaultClient;
    }

    // 根据账户唯一key获取或创建OkHttpClient
    public OkHttpClient getClient(ConfigAccountVO config) {
        String key = config.getProxyKey();

        // ✅ 无代理：直接返回默认客户端
        if ("no-proxy".equals(key)) {
            return defaultClient();
        }

        // 自动代理 3 映射成 HTTP
        int type = (config.getProxyType() == 3 ? 1 : config.getProxyType());

        // ✅ SOCKS5 且带用户名密码认证：使用自定义 SocketFactory 生成 OkHttpClient
        if (type == 2 && config.hasAuth()) {
            return clientMap.computeIfAbsent(key, k ->
                    Socks5ProxyClientBuilder.createSocks5Client(
                            config.getProxyHost(),
                            config.getProxyPort(),
                            config.getProxyUsername(),
                            config.getProxyPassword()
                    )
            );
        }

        // ✅ 其他（HTTP代理、SOCKS5无认证）
        return clientMap.computeIfAbsent(key, k -> {
            Proxy proxy = new Proxy(
                    type == 1 ? Proxy.Type.HTTP : Proxy.Type.SOCKS,
                    new InetSocketAddress(config.getProxyHost(), config.getProxyPort())
            );

            OkHttpClient.Builder builder = new OkHttpClient.Builder()
                    .proxy(proxy)
                    .followRedirects(false) // 禁用自动重定向，方便拿location
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .callTimeout(10, TimeUnit.SECONDS)   // 整个调用最大超时
                    .retryOnConnectionFailure(false)            // 🚫 禁用自动重试
                    //.connectionPool(new ConnectionPool(20, 5, TimeUnit.MINUTES)); // 🚀 高并发支持
                    .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS)); // 连接池大小为 0，避免复用

            // 仅 HTTP 代理认证支持
            if (type == 1 && config.hasAuth()) {
                builder.proxyAuthenticator((route, response) -> {
                    String credential = Credentials.basic(config.getProxyUsername(), config.getProxyPassword());
                    return response.request().newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build();
                });
            }

            return builder.build();
        });
    }

    /**
     * 执行请求,并返回结果
     * @param method
     * @param url
     * @param body
     * @param headers
     * @param config
     * @param checkOnlyConnection   仅校验连通性
     * @return
     * @throws IOException
     */
    public HttpResult execute(String method,
                          String url,
                          String body,
                          Map<String, String> headers,
                          ConfigAccountVO config,
                          boolean checkOnlyConnection) throws IOException {

        String key = config.getProxyKey();
        ProxyState state = proxyStateMap.computeIfAbsent(key, k -> new ProxyState());

        for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
            // TODO 暂时不开启代理冷却功能
            /*if (!state.isAvailable()) {
                String msg = String.format("[OkHttpProxyDispatcher] 代理 %s 冷却中，拒绝请求", key);
                log.info(msg);
                throw new IOException(msg);
            }*/
            try {
                String proxyTypeStr = (config.getProxyType() == null || config.getProxyType() == 0) ? "无代理" : (config.getProxyType() == 1 ? "HTTP" : "SOCKS");
                log.info("[OkHttpProxyDispatcher] 尝试请求，方法={}，URL={}，代理={}[{}]，第{}次尝试",
                        method, url, key, proxyTypeStr, attempt + 1);

                OkHttpClient client = getClient(config);

                Request.Builder requestBuilder = new Request.Builder().url(url);
                if ("POST".equalsIgnoreCase(method)) {
                    String contentType = headers.getOrDefault("content-type", "application/json");
                    // 确保请求体 MIME 和 Header 一致
                    RequestBody requestBody = RequestBody.create(
                            StringUtils.isBlank(body) ? "" : body,
                            MediaType.parse(contentType)
                    );
                    // OkHttp 不自动设置 Content-Type 头，所以手动设置
                    requestBuilder.addHeader("Content-Type", contentType);
                    requestBuilder.post(requestBody);
                } else if ("GET".equalsIgnoreCase(method)) {
                    requestBuilder.get();
                } else {
                    throw new UnsupportedOperationException("仅支持 GET 和 POST 方法");
                }

                if (headers != null) {
                    headers.forEach(requestBuilder::addHeader);
                }

                // ⭐ 在这里注入平台伪装
                applyPlatformHeaders(requestBuilder, RequestPlatform.ANDROID);

                long start = System.currentTimeMillis(); // ✅ 请求开始时间
                try (Response response = client.newCall(requestBuilder.build()).execute()) {
                    long cost = System.currentTimeMillis() - start; // ✅ 请求耗时
                    int code = response.code();
                    String respBody;
                    String contentType = Objects.requireNonNull(response.header("Content-Type", "")).toLowerCase();

                    if (contentType.contains("application/json")) {
                        respBody = response.body() != null ? response.body().string() : null;
                    } else {
                        byte[] bytes = response.body() != null ? response.body().bytes() : new byte[0];
                        Charset charset = StandardCharsets.UTF_8; // 默认

                        if (contentType.contains("gbk") || contentType.contains("gb2312")) {
                            charset = Charset.forName("GBK");
                        }

                        respBody = new String(bytes, charset);
                    }

                    Map<String, List<String>> respHeaders = response.headers().toMultimap();

                    // 提取特定 Cookie（示例，如果需要）
                    String cookieToken = null;
                    List<String> setCookies = respHeaders.get("Set-Cookie");
                    if (setCookies != null) {
                        for (String cookie : setCookies) {
                            Matcher matcher = Pattern.compile("token=([^;]+)").matcher(cookie);
                            if (matcher.find()) {
                                cookieToken = matcher.group(1);
                                break;
                            }
                        }
                    }

                    // ✅ 若是“仅校验连通性”，不做任何状态码校验
                    if (checkOnlyConnection) {
                        // 成功，重置失败计数
                        state.reset();
                        return new HttpResult(respBody, respHeaders, code, cookieToken, cost);
                    }
                    //if (code >= 200 && code < 300) {
                        // 成功，重置失败计数
                        state.reset();
                        log.info("[OkHttpProxyDispatcher] 请求成功，方法={}，URL={}，账户={}，代理=[{}]，耗时={}m", method, url, config.getAccount(), proxyTypeStr, cost);
                        return new HttpResult(respBody, respHeaders, code, cookieToken, cost);
                    /*} else {
                        throw new IOException("响应失败，状态码：" + code + "，响应体：" + respBody);
                    }*/
                }
            } catch (Exception e) {
                state.fail();
                log.warn("[OkHttpProxyDispatcher] 请求失败，方法={}，URL={}，账户={}，代理={}[{}]，失败次数={}/{}, 错误：{}",
                        method, url, config.getAccount(), key, (config.getProxyType() == null || config.getProxyType() == 0) ? "无代理" : (config.getProxyType() == 1 ? "HTTP" : "SOCKS"), state.getFailCount(), MAX_FAIL, e.getMessage());
                if (attempt == MAX_RETRY) {
                    throw new IOException("请求全部重试失败：" + e.getMessage(), e);
                }
                // 简单等待一下再重试，也可以根据需要调整策略
                /*try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {}*/
            }
        }
        throw new IOException("请求执行失败，未命中任何有效结果");
    }

    /**
     * 发起请求，返回完整响应信息（包括状态码、响应头、body、cookie等）
     * @param method
     * @param url
     * @param body
     * @param headers
     * @param config
     * @return
     * @throws IOException
     */
    public HttpResult executeFull(String method,
                                  String url,
                                  String body,
                                  Map<String, String> headers,
                                  ConfigAccountVO config) throws IOException {

        String key = config.getProxyKey();
        ProxyState state = proxyStateMap.computeIfAbsent(key, k -> new ProxyState());

        for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
            /*if (!state.isAvailable()) {
                String msg = String.format("[OkHttpProxyDispatcher] 代理 %s 冷却中，拒绝请求", key);
                log.info(msg);
                throw new IOException(msg);
            }*/
            try {
                String proxyTypeStr = (config.getProxyType() == null || config.getProxyType() == 0) ? "无代理" : (config.getProxyType() == 1 ? "HTTP" : "SOCKS");
                log.info("[OkHttpProxyDispatcher] 尝试请求，方法={}，URL={}，代理={}[{}]，第{}次尝试",
                        method, url, key, proxyTypeStr, attempt + 1);

                OkHttpClient client = getClient(config);

                Request.Builder requestBuilder = new Request.Builder().url(url);
                if ("POST".equalsIgnoreCase(method)) {
                    String contentType = headers.getOrDefault("content-type", "application/json");
                    RequestBody requestBody = RequestBody.create(
                            StringUtils.isBlank(body) ? "" : body,
                            MediaType.parse(contentType)
                    );
                    requestBuilder.addHeader("Content-Type", contentType);
                    requestBuilder.post(requestBody);
                } else if ("GET".equalsIgnoreCase(method)) {
                    requestBuilder.get();
                } else {
                    throw new UnsupportedOperationException("仅支持 GET 和 POST 方法");
                }

                if (headers != null) {
                    headers.forEach(requestBuilder::addHeader);
                }

                // ⭐ 在这里注入平台伪装
                applyPlatformHeaders(requestBuilder, RequestPlatform.ANDROID);

                // 添加默认伪装头（若 headers 中未指定）
                // 默认 OkHttp 会自动添加并解压 gzip
                if (!requestBuilder.build().headers().names().contains("Accept-Encoding")) {
                    requestBuilder.header("Accept-Encoding", "gzip, deflate, br, zstd");
                }

                long start = System.currentTimeMillis(); // ✅ 请求开始时间
                try (Response response = client.newCall(requestBuilder.build()).execute()) {
                    long cost = System.currentTimeMillis() - start;
                    int code = response.code();
                    ResponseBody responseBody = response.body();
                    byte[] bytes = responseBody != null ? responseBody.bytes() : new byte[0];

                    // 1. 调试信息：打印关键响应头
                    log.debug("Content-Type: {}", response.header("Content-Type"));
                    log.debug("Content-Encoding: {}", response.header("Content-Encoding"));

                    // 手动处理 Gzip（即使服务器未声明）
                    if (isGzipCompressed(bytes)) {
                        bytes = decompressGzip(bytes);
                    }

                    // 2. 确定编码
                    // String charset = determineCharset(response, bytes);

                    // 3. 使用正确编码解码
                    // String respBody = new String(bytes, charset);
                    // 强制 UTF-8 解码
                    String html = new String(bytes, StandardCharsets.UTF_8);

                    // 检查是否是有效内容
                    if (html.contains("<!DOCTYPE html") || html.contains("<html")) {
                        // log.info("成功获取 HTML 内容");
                    } else {
                        // log.info("响应可能不是 HTML: {}", html.substring(0, Math.min(100, html.length())));
                    }

                    // 5. 返回结果（包含响应头、状态码等）
                    Map<String, List<String>> respHeaders = response.headers().toMultimap();

                    // 提取特定 Cookie（示例，如果需要）
                    String cookieToken = null;
                    List<String> setCookies = respHeaders.get("Set-Cookie");
                    if (setCookies != null) {
                        for (String cookie : setCookies) {
                            Matcher matcher = Pattern.compile("token=([^;]+)").matcher(cookie);
                            if (matcher.find()) {
                                cookieToken = matcher.group(1);
                                break;
                            }
                        }
                    }
                    state.reset();
                    log.info("[OkHttpProxyDispatcher] 请求成功，URL={}", url);
                    return new HttpResult(html, respHeaders, code, cookieToken, cost);

                }
            } catch (Exception e) {
                state.fail();
                log.warn("[OkHttpProxyDispatcher] 请求失败，方法={}，URL={}，代理={}[{}]，失败次数={}/{}, 错误：{}",
                        method, url, key, config.getProxyType() == 1 ? "HTTP" : "SOCKS", state.getFailCount(), MAX_FAIL, e.getMessage());
                if (attempt == MAX_RETRY) {
                    throw new IOException("请求全部重试失败：" + e.getMessage(), e);
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {}
            }
        }
        throw new IOException("请求执行失败，未命中任何有效结果");
    }

    /**
     * 执行请求，返回图片字节数组
     * @param method GET/POST，一般请求验证码用GET
     * @param url 请求地址
     * @param body 请求体，图片请求一般不带，传null或空字符串
     * @param headers 自定义请求头
     * @param config 代理配置等
     * @return 图片二进制
     * @throws IOException 请求失败时抛出
     */
    public ImageResult executeImage(String method,
                                    String url,
                                    String body,
                                    Map<String, String> headers,
                                    ConfigAccountVO config) throws IOException {

        String key = config.getProxyKey();
        ProxyState state = proxyStateMap.computeIfAbsent(key, k -> new ProxyState());

        for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
            /*if (!state.isAvailable()) {
                String msg = String.format("[OkHttpProxyDispatcher] 代理 %s 冷却中，拒绝请求", key);
                log.info(msg);
                throw new IOException(msg);
            }*/
            try {
                String proxyTypeStr = (config.getProxyType() == null || config.getProxyType() == 0) ? "无代理" : (config.getProxyType() == 1 ? "HTTP" : "SOCKS");
                log.info("[OkHttpProxyDispatcher] 尝试请求，方法={}，URL={}，代理={}[{}]，第{}次尝试",
                        method, url, key, proxyTypeStr, attempt + 1);

                OkHttpClient client = getClient(config);

                Request.Builder requestBuilder = new Request.Builder().url(url);
                if ("POST".equalsIgnoreCase(method)) {
                    String contentType = headers.getOrDefault("content-type", "application/json");
                    RequestBody requestBody = RequestBody.create(
                            StringUtils.isBlank(body) ? "" : body,
                            MediaType.parse(contentType)
                    );
                    requestBuilder.addHeader("Content-Type", contentType);
                    requestBuilder.post(requestBody);
                } else if ("GET".equalsIgnoreCase(method)) {
                    requestBuilder.get();
                } else {
                    throw new UnsupportedOperationException("仅支持 GET 和 POST 方法");
                }

                if (headers != null) {
                    headers.forEach(requestBuilder::addHeader);
                }

                // 添加默认伪装头（若 headers 中未指定）
                if (!requestBuilder.build().headers().names().contains("User-Agent")) {
                    requestBuilder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
                }
                if (!requestBuilder.build().headers().names().contains("Accept")) {
                    requestBuilder.header("Accept", "*/*");
                }
                if (!requestBuilder.build().headers().names().contains("Accept-Encoding")) {
                    requestBuilder.header("Accept-Encoding", "identity"); // 禁用 GZIP 解码，保证图片完整
                }
                if (!requestBuilder.build().headers().names().contains("Accept-Language")) {
                    requestBuilder.header("Accept-Language", "zh-CN,zh;q=0.9");
                }

                try (Response response = client.newCall(requestBuilder.build()).execute()) {
                    int code = response.code();
                    ResponseBody responseBody = response.body();

                    if (responseBody != null) {
                        byte[] bytes = responseBody.bytes();
                        if (bytes.length == 0) {
                            throw new IOException("响应体为空");
                        }
                        state.reset();
                        log.info("[OkHttpProxyDispatcher] 请求成功，方法={}，URL={}，代理={}[{}]", method, url, key, config.getProxyType() == 1 ? "HTTP" : "SOCKS");

                        ImageResult result = new ImageResult();
                        result.setStatus(code);
                        result.setBody(bytes);
                        result.setHeaders(response.headers().toMultimap());
                        return result;
                    } else {
                        String respStr = responseBody != null ? responseBody.string() : "";
                        throw new IOException("响应失败，状态码：" + code + "，响应体：" + respStr);
                    }
                }
            } catch (Exception e) {
                state.fail();
                log.warn("[OkHttpProxyDispatcher] 请求失败，方法={}，URL={}，代理={}[{}]，失败次数={}/{}, 错误：{}",
                        method, url, key, config.getProxyType() == 1 ? "HTTP" : "SOCKS", state.getFailCount(), MAX_FAIL, e.getMessage());
                if (attempt == MAX_RETRY) {
                    throw new IOException("请求全部重试失败：" + e.getMessage(), e);
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {}
            }
        }
        throw new IOException("请求执行失败，未命中任何有效结果");
    }

    private void applyPlatformHeaders(Request.Builder builder, RequestPlatform platform) {

        if (platform == RequestPlatform.ANDROID) {
            builder.header("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/121.0.0.0 Mobile Safari/537.36");

            builder.header("Accept", "*/*");
            builder.header("Accept-Language", "zh-CN,zh;q=0.9");
            builder.header("Sec-CH-UA-Mobile", "?1");
            builder.header("Sec-CH-UA-Platform", "\"Android\"");
            builder.header("X-Requested-With", "com.android.browser");

        } else {
            // PC
            builder.header("User-Agent", Constants.USER_AGENT);
            builder.header("Accept", "*/*");
            builder.header("Accept-Language", "zh-CN,zh;q=0.9");
        }
    }

    @Data
    @AllArgsConstructor
    public static class HttpResult {
        private String body;
        private Map<String, List<String>> headers;
        private int status;
        private String cookieToken;     // 如果需要提取特定 Cookie
        private final long durationMs;  // 耗时
    }
    @Data
    public class ImageResult {
        private int status;
        private byte[] body;
        private Map<String, List<String>> headers;
    }
    private static class ProxyState {
        private int failCount = 0;
        private long lastFailTime = 0;

        synchronized void fail() {
            failCount++;
            lastFailTime = System.currentTimeMillis();
        }

        synchronized void reset() {
            failCount = 0;
            lastFailTime = 0;
        }

        synchronized boolean isAvailable() {
            if (failCount < MAX_FAIL) {
                return true;
            }
            long elapsed = System.currentTimeMillis() - lastFailTime;
            return elapsed > COOLDOWN_MS;
        }

        synchronized int getFailCount() {
            return failCount;
        }
    }

    // 判断是否为 Gzip 压缩的辅助方法
    private boolean isGzipCompressed(byte[] bytes) {
        return bytes.length >= 2 &&
                bytes[0] == (byte) 0x1F &&
                bytes[1] == (byte) 0x8B;
    }
    private byte[] decompressGzip(byte[] compressed) throws IOException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(compressed);
             GZIPInputStream gzip = new GZIPInputStream(bis);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gzip.read(buffer)) > 0) {
                bos.write(buffer, 0, len);
            }
            return bos.toByteArray();
        }
    }

    // 确定响应编码
    private String determineCharset(Response response, byte[] bytes) {
        // 1. 优先从Content-Type头获取编码
        String contentType = response.header("Content-Type");
        if (contentType != null) {
            Matcher matcher = Pattern.compile("charset=([^;]+)").matcher(contentType);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }

        // 2. 尝试从HTML meta标签检测
        String partialContent = new String(bytes, 0, Math.min(bytes.length, 1024), StandardCharsets.ISO_8859_1);
        Matcher htmlMetaMatcher = Pattern.compile("<meta.*?charset=[\"']?([^\"'>]+)").matcher(partialContent);
        if (htmlMetaMatcher.find()) {
            return htmlMetaMatcher.group(1);
        }

        // 3. 默认使用UTF-8，但尝试常见中文编码
        try {
            String testContent = new String(bytes, "GBK");
            if (!testContent.contains("�")) { // 如果没有乱码字符
                return "GBK";
            }
        } catch (UnsupportedEncodingException e) {
            // ignore
        }

        return "UTF-8"; // 最终回退到UTF-8
    }
}


