package com.xyzensun.emailcopilot.domain.enums;

import com.baomidou.mybatisplus.annotation.IEnum;

/**
 * AI 服务商，决定用哪个 ChatModel 实现类（{@code DATABASE.md} §8.5）。
 *
 * <p>只有两个值，因为<b>"换服务商"退化成改 baseUrl + key + 型号名三个字段</b>：
 * 绝大多数第三方与自建服务提供 OpenAI 兼容端点，{@code OPENAI} + 自定义
 * {@code ai_base_url} 就能连上。保留 {@code ANTHROPIC} 分支是因为它的原生 API 不兼容。
 *
 * <p>本项目<b>不使用 Spring AI starter 自动装配的 ChatModel 单例</b>
 * （{@code ARCHITECTURE.md} §8.5）：provider、端点、型号、超时、API key 五项全部可在界面上
 * 热改、立即生效、不需要重启，因此由 {@code ChatModelHolder} 持有一个 volatile 引用，
 * 配置变更时用 {@code OpenAiApi.builder()} / {@code AnthropicApi.builder()} 重建。
 * 正在跑的调用持旧引用跑完，替换只对此后的新调用生效。
 *
 * <p>数据库里存小写字面值。{@code ARCHITECTURE.md} §8.5 代码示意里写作大写常量名，
 * 是同一枚举的两种书写形式。
 */
public enum AiProvider implements IEnum<String> {

    OPENAI("openai"),
    ANTHROPIC("anthropic");

    private final String value;

    AiProvider(String value) {
        this.value = value;
    }

    @Override
    public String getValue() {
        return value;
    }
}
