package com.xyzensun.emailcopilot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * 前端 SPA 同源部署的静态资源与 history 路由 fallback（{@code ARCHITECTURE.md} §10）。
 *
 * <p>前端构建产物落在 {@code src/main/resources/static/}（{@code vite build} 直接写入），
 * 由 Spring Boot 默认静态资源 handler 托管。但 SPA 用 history 路由（如
 * {@code /conversations/123}、{@code /settings/system}），这些路径在文件系统里没有对应
 * 静态文件——默认会 404。这里加一条兜底：找不到对应静态资源的请求，一律返回
 * {@code index.html}，让前端路由接管。
 *
 * <p>实现用 {@link PathResourceResolver} 的「解析失败则返回 index.html」模式，
 * 而非 {@code NoHandlerFoundException} + 全局 advice——后者要关掉静态资源默认抛 404 的
 * 短路行为（{@code spring.web.resources.add-mappings=false}），副作用面更大。这里只在
 * resource handler 链内做兜底。
 *
 * <p><b>不影响 API</b>：{@code /api/**} 由 {@code SecurityConfig} 鉴权并由各 Controller
 * 的 {@code @RequestMapping("/api/...")} 优先匹配消费，请求到不了这条 resource handler。
 * 见 {@code SecurityConfig} 第 140-142 行：非 {@code /api/**} 才 {@code permitAll} 走静态资源。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private static final String INDEX_HTML = "static/index.html";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 匹配所有非 /api 路径的静态资源；找不到文件时 fallback 到 index.html（SPA history 路由）。
        // /api/** 由 Controller 优先匹配，不会进入此 handler。
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource resolved = super.getResource(resourcePath, location);
                        // 找到实际文件（含 index.html 自身）正常返回；
                        // 找不到（SPA 子路由如 /conversations/123）forward 到 index.html
                        if (resolved != null && resolved.isReadable()) {
                            return resolved;
                        }
                        Resource index = new ClassPathResource(INDEX_HTML);
                        return index.isReadable() ? index : null;
                    }
                });
    }
}
