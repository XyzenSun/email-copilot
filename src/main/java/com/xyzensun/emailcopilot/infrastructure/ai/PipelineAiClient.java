package com.xyzensun.emailcopilot.infrastructure.ai;

import com.xyzensun.emailcopilot.domain.pipeline.ClassificationMode;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Message;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Tag;
import org.jsoup.Jsoup;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * 阶段 5 的无工具模型调用边界。
 *
 * <p>每个公开方法严格执行一次 {@link ChatModel#call(Prompt)}；调用方负责在进入方法前只读取一次
 * {@code ChatModelHolder.current()}。本类不注册 tool callback，也不实现 tool loop。
 */
@Component
public final class PipelineAiClient {

    private final SpamPromptAssembler spamPromptAssembler = new SpamPromptAssembler();
    private final SpamJudgmentJsonSchema spamJsonSchema = new SpamJudgmentJsonSchema();
    private final ClassificationPromptAssembler classificationPromptAssembler =
            new ClassificationPromptAssembler();
    private final ClassificationJsonSchema classificationJsonSchema = new ClassificationJsonSchema();
    private final TextPromptAssembler textPromptAssembler = new TextPromptAssembler();

    public SpamJudgmentResult judgeSpam(
            ChatModel model,
            Message message,
            String spamJudgmentPolicy) {
        String rawContent = callOnce(model, spamPromptAssembler.assemble(message, spamJudgmentPolicy));
        return spamJsonSchema.parse(rawContent);
    }

    public ClassificationResult classify(
            ChatModel model,
            Message message,
            List<Tag> tags,
            ClassificationMode mode) {
        Map<String, Long> allowedTagIdsByName = tags.stream()
                .collect(Collectors.toUnmodifiableMap(Tag::getName, Tag::getId));
        String rawContent = callOnce(
                model, classificationPromptAssembler.assemble(message, tags, mode));
        return classificationJsonSchema.parse(rawContent, mode, allowedTagIdsByName);
    }

    public String translate(ChatModel model, Message message) {
        return plainText(callOnce(model, textPromptAssembler.translation(message)));
    }

    public String summarize(ChatModel model, Message message) {
        return plainText(callOnce(model, textPromptAssembler.summary(message)));
    }

    private static String callOnce(ChatModel model, Prompt prompt) {
        requireNonNull(model, "ChatModel 不能为空");
        ChatResponse response = model.call(prompt);
        if (response == null || response.hasToolCalls()) {
            throw new InvalidStructuredOutputException();
        }
        Generation generation = response.getResult();
        if (generation == null || generation.getOutput() == null
                || generation.getOutput().getText() == null) {
            throw new InvalidStructuredOutputException();
        }
        return generation.getOutput().getText();
    }

    private static String plainText(String rawContent) {
        // provider 输出仍是不可信文本；解析为文本可保证邮件 API 永不返回可执行 HTML 标记。
        String sanitized = Jsoup.parse(rawContent).text().strip();
        if (sanitized.isEmpty()) {
            throw new InvalidStructuredOutputException();
        }
        return sanitized;
    }
}
