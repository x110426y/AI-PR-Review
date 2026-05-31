package org.fourerif.config;

import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.OkHttp3ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.concurrent.TimeUnit;

/**
 * HTTP 客户端配置 — 覆盖 DeepSeek API 调用超时。
 * <p>
 * OkHttp 默认 readTimeout=10s，对大型 PR Diff 的 AI 审查完全不够。
 * <p>
 * <b>原理：</b>
 * 提供 {@link OkHttpClient} Bean + {@link RestClient.Builder} (@Primary) Bean，
 * 阻断 Spring Boot 默认的短超时 Builder。
 * Spring AI 1.0.0 的 {@code OpenAiChatAutoConfiguration} 通过
 * {@code ObjectProvider<RestClient.Builder>} 注入此 Builder 到 {@code OpenAiApi}。
 */
@Configuration
public class HttpClientConfig {

    private static final Logger log = LoggerFactory.getLogger(HttpClientConfig.class);

    /**
     * 长超时 OkHttpClient。
     * readTimeout=180s 足以覆盖 DeepSeek 对最大规模 PR Diff 的审查。
     */
    @Bean
    public OkHttpClient okHttpClient() {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        log.info("OkHttpClient 已配置: connect=30s, read=180s, write=60s");
        return client;
    }

    /**
     * 覆盖 Spring Boot 自动配置的 RestClient.Builder，
     * 用自定义 OkHttp3ClientHttpRequestFactory 替换默认短超时工厂。
     * <p>
     * {@link Primary} 确保此 Bean 在所有注入点优先被选中，
     * 包括 Spring AI 的 {@code ObjectProvider<RestClient.Builder>}。
     */
    @Bean
    @Primary
    public RestClient.Builder restClientBuilder(OkHttpClient okHttpClient) {
        return RestClient.builder()
                .requestFactory(new OkHttp3ClientHttpRequestFactory(okHttpClient));
    }
}
