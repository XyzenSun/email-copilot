package com.xyzensun.emailcopilot.application.conversation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.AppSetting;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Conversation;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Turn;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.AppSettingMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.ConversationMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.TurnMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 上下文全量压缩（ARCHITECTURE §8.6，design.md §6）。
 *
 * <pre>
 * 触发：拼出的上下文 token 达 ai_context_window_k 的 70%（固定值，不做配置项）
 * 范围：全部压掉，不是压最老的几轮
 * 方式：压缩提示词 + 完整上下文 → 一次无工具、非流式 ChatModel.call()
 * 之后：上下文 = 这段摘要 + 压缩之后的新轮次
 * </pre>
 *
 * <p><b>不产生 Turn 行、不写 evidence、不计入 model_call_count</b>：
 * 它不是用户的一轮对话，是系统的一次内部整理。
 *
 * <p><b>压缩失败不阻塞</b>：AI 连不上时保持原上下文继续本轮，下一轮再试。
 * 只有原上下文本身已超窗口、模型直接拒绝时那一轮才失败。
 *
 * <p><b>为什么全压</b>：分段压需决定"压几轮/留几轮"，引出"压过的还要不要再压"。
 * 全压只有一个参数（触发阈值）且天然幂等。
 */
@Component
public class ContextCompactor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactor.class);
    private static final short SETTINGS_ROW_ID = 1;
    private static final double COMPACTION_THRESHOLD = 0.70;

    private static final String COMPACTION_PROMPT = """
            请将以下对话历史压缩为一段简洁的摘要。
            保留关键事实、用户意图和已确定的结论，省略冗余细节。
            摘要应足以让后续对话在不需要完整历史的情况下继续。

            对话历史：
            """;

    private final AppSettingMapper appSettingMapper;
    private final TurnMapper turnMapper;
    private final ConversationMapper conversationMapper;

    public ContextCompactor(AppSettingMapper appSettingMapper, TurnMapper turnMapper,
                            ConversationMapper conversationMapper) {
        this.appSettingMapper = appSettingMapper;
        this.turnMapper = turnMapper;
        this.conversationMapper = conversationMapper;
    }

    /**
     * 判断当前上下文是否需要压缩。
     *
     * <p>首轮不算（那时没有任何真实数据可依据，且首轮上下文最短最不可能撑爆）；
     * 之后读上次调用返回的 {@code usage.prompt_tokens}（存 {@code conversation.context_tokens}），
     * provider 不返回 usage 时退化为字符数 ÷ 4。不引入分词库。
     */
    public boolean shouldCompact(Conversation conversation, List<Message> rebuiltContext) {
        AppSetting settings = appSettingMapper.selectById(SETTINGS_ROW_ID);
        if (settings == null || settings.getAiContextWindowK() == null) {
            return false;
        }
        int contextWindowTokens = settings.getAiContextWindowK() * 1000;
        int threshold = (int) (contextWindowTokens * COMPACTION_THRESHOLD);

        // 首轮不算直接发。
        if (conversation.getContextTokens() == null || conversation.getContextTokens() == 0) {
            // 用字符数 ÷ 4 估算。
            int estimatedTokens = estimateTokens(rebuiltContext);
            return estimatedTokens >= threshold;
        }

        // 优先用上次调用返回的 usage.prompt_tokens。
        return conversation.getContextTokens() >= threshold;
    }

    /**
     * 执行全量压缩。返回压缩后的摘要。
     *
     * <p>非流式、无工具：直接 {@link ChatModel#call(Prompt)}。
     * 压缩失败不阻塞调用方（返回 {@link Optional#empty()}）。
     *
     * @param contextMessages 当前完整上下文
     * @return 压缩后的摘要；AI 不可用或失败时返回空
     */
    public Optional<String> compact(ChatModel chatModel, List<Message> contextMessages) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(COMPACTION_PROMPT));
        // 把完整上下文作为一个 user message 传给模型压缩。
        StringBuilder contextText = new StringBuilder();
        for (Message message : contextMessages) {
            contextText.append(roleLabel(message)).append(": ")
                    .append(message.getText() != null ? message.getText() : "")
                    .append("\n\n");
        }
        messages.add(new UserMessage(contextText.toString()));

        try {
            ChatResponse response = chatModel.call(new Prompt(messages));
            if (response == null || response.getResult() == null
                    || response.getResult().getOutput() == null
                    || response.getResult().getOutput().getText() == null) {
                log.warn("上下文压缩返回空响应");
                return Optional.empty();
            }
            String summary = response.getResult().getOutput().getText().strip();
            if (summary.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(summary);
        } catch (Exception exception) {
            // 压缩失败不阻塞：保持原上下文继续本轮，下一轮再试。
            log.warn("上下文压缩失败，保持原上下文", exception);
            return Optional.empty();
        }
    }

    /**
     * 写入压缩结果到 conversation。
     *
     * <p>写 {@code context_summary} + {@code context_summarized_upto_turn_id}
     * （check 约束保证两者同 null/同非 null）。不产生 Turn/evidence、不计 model_call_count。
     */
    public void persistCompaction(Conversation conversation, String summary) {
        // 取当前最大 completed turn id 作为压缩水位。
        Long maxTurnId = turnMapper.selectList(
                Wrappers.lambdaQuery(Turn.class)
                        .select(Turn::getId)
                        .eq(Turn::getConversationId, conversation.getId())
                        .orderByDesc(Turn::getId)
                        .last("limit 1"))
                .stream().map(Turn::getId).findFirst().orElse(null);

        conversationMapper_update(conversation, summary, maxTurnId);
    }

    /**
     * 更新 conversation 的压缩结果。用 SQL 而非实体更新，确保只写目标列。
     */
    private void conversationMapper_update(
            Conversation conversation, String summary, Long summarizedUptoTurnId) {
        conversationMapper.update(null,
                Wrappers.lambdaUpdate(Conversation.class)
                        .eq(Conversation::getId, conversation.getId())
                        .set(Conversation::getContextSummary, summary)
                        .set(Conversation::getContextSummarizedUptoTurnId, summarizedUptoTurnId)
                        .setSql("updated_at = now()"));
    }

    private static int estimateTokens(List<Message> messages) {
        // 字符数 ÷ 4：中文已知代价是低估约四倍，但主流端点都返回 usage。
        int totalChars = 0;
        for (Message message : messages) {
            if (message.getText() != null) {
                totalChars += message.getText().length();
            }
        }
        return totalChars / 4;
    }

    private static String roleLabel(Message message) {
        if (message instanceof UserMessage) {
            return "用户";
        }
        if (message instanceof AssistantMessage) {
            return "助手";
        }
        if (message instanceof SystemMessage) {
            return "系统";
        }
        return "未知";
    }
}
