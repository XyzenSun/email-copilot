package com.xyzensun.emailcopilot.infrastructure.mcp;

import com.xyzensun.emailcopilot.domain.enums.SecretType;
import com.xyzensun.emailcopilot.infrastructure.security.ExternalAccountSecretStore;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;
import java.util.Set;

/**
 * Exa MCP 只读接入配置（design.md §7，research/exa-mcp-streamable-http-verified.md 已验证）。
 *
 * <p><b>双层 allowlist + fail closed</b>（PRD R1.5）：
 * <ol>
 *   <li>服务端：{@code ?tools=web_search_exa,web_fetch_exa} 让 Exa 只注册这两个工具</li>
 *   <li>客户端：{@link #exaToolFilter()} 仅放行 allowlist 中的工具名，其余默认 false</li>
 * </ol>
 *
 * <p><b>API key 注入</b>：通过 {@link McpClientCustomizer} 在 transport builder 上设置
 * {@code httpRequestCustomizer}，每次 HTTP 请求时从 {@link ExternalAccountSecretStore}
 * 读取 EXA_API_KEY 并注入 {@code x-api-key} header。
 * key 不进 URL、工具参数、模型上下文、日志、API 响应。
 *
 * <p><b>不可用处理</b>（PRD R1.8）：MCP 不可用不阻塞邮件入库和流水线（流水线走 PipelineAiClient，
 * 不涉及 MCP）。对话请求在 MCP 不可用时返回可解释的工具失败结果，不伪造搜索结果。
 */
@Configuration
public class ExaMcpConfig {

    private static final Logger log = LoggerFactory.getLogger(ExaMcpConfig.class);

    /** 审核过的 Exa 只读工具名；任何 Exa 新增未知工具 fail closed（不注册为 ToolCallback）。 */
    private static final Set<String> EXA_ALLOWED_TOOLS = Set.of("web_search_exa", "web_fetch_exa");

    private final ExternalAccountSecretStore secretStore;

    public ExaMcpConfig(ExternalAccountSecretStore secretStore) {
        this.secretStore = secretStore;
    }

    /**
     * 注入 {@code x-api-key} header（research 已验证有效，key 不泄漏）。
     *
     * <p>{@link McpClientCustomizer} 对每个连接名的 transport builder 调用 customize；
     * 这里只对 Exa 连接注入 header。key 在每次 HTTP 请求时从密文存储实时读取，
     * 因此 key 更新后立即生效，且 key 不缓存在内存中。
     */
    @Bean
    public McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> exaApiKeyInjector() {
        return (connectionName, transportBuilder) -> {
            if (!"exa".equals(connectionName)) {
                return;
            }
            // 不在启动时连接：key 在启动时可能尚未配置，initialize 会失败。
            // 连接延迟到首次 getToolCallbacks() 时发生，此时 key 已可通过 PUT /mcp-key 配好。
            transportBuilder.openConnectionOnStartup(false);
            transportBuilder.httpRequestCustomizer((requestBuilder, method, uri, body, context) -> {
                Optional<String> apiKey = secretStore.load(SecretType.EXA_API_KEY, null);
                // key 不进日志：只记录是否已配置。
                if (apiKey.isPresent()) {
                    requestBuilder.header("x-api-key", apiKey.get());
                } else {
                    log.debug("Exa MCP API key 未配置，连接 {} 的请求不携带 x-api-key", connectionName);
                }
            });
        };
    }

    /**
     * 客户端 allowlist：仅放行 {@code web_search_exa} / {@code web_fetch_exa}。
     *
     * <p>其余工具（含 {@code agent_run}、{@code web_search_advanced_exa}、未来可能出现的写工具）
     * 默认 {@code false}，不注册为 {@code ToolCallback}。任何 Exa 新增未知工具 fail closed。
     */
    @Bean
    public McpToolFilter exaToolFilter() {
        return (McpConnectionInfo connectionInfo, McpSchema.Tool tool) ->
                EXA_ALLOWED_TOOLS.contains(tool.name());
    }
}
