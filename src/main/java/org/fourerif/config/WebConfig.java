package org.fourerif.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局跨域配置
 * <p>
 * 允许前端开发服务器 (Vite @ localhost:5173) 跨域访问后端 API。
 * 生产环境应缩小 allowedOrigins 范围，禁止使用 "*"。
 * </p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")          // 只对 /api 路径开启 CORS
                .allowedOriginPatterns("*")      // 开发阶段允许所有来源
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")             // 允许所有请求头
                .allowCredentials(true)          // 允许携带 Cookie（如后续需要认证）
                .maxAge(3600);                   // 预检请求缓存 1 小时
    }
}
