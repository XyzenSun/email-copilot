package com.xyzensun.emailcopilot.application.mail;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xyzensun.emailcopilot.domain.mail.JwzThreadMerger;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Message;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.MessageMention;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.ThreadNode;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MessageMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MessageMentionMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.ThreadMergeMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.ThreadNodeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 持久化 JWZ 归并与 {@code thread_size_limit} 守卫，参与调用方的单封入库短事务。 */
@Service
public class ThreadMergeApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ThreadMergeApplicationService.class);

    private final ThreadMergeMapper threadMergeMapper;
    private final MessageMapper messageMapper;
    private final MessageMentionMapper messageMentionMapper;
    private final ThreadNodeMapper threadNodeMapper;

    public ThreadMergeApplicationService(
            ThreadMergeMapper threadMergeMapper,
            MessageMapper messageMapper,
            MessageMentionMapper messageMentionMapper,
            ThreadNodeMapper threadNodeMapper) {
        this.threadMergeMapper = threadMergeMapper;
        this.messageMapper = messageMapper;
        this.messageMentionMapper = messageMentionMapper;
        this.threadNodeMapper = threadNodeMapper;
    }

    @Transactional
    public MergeResult mergeNewMessage(
            Message currentMessage,
            List<String> references,
            int threadSizeLimit) {
        if (threadSizeLimit < 1) {
            throw new IllegalArgumentException("threadSizeLimit 必须为正数");
        }
        List<String> relevantRfcMessageIds = new ArrayList<>(references);
        relevantRfcMessageIds.add(currentMessage.getMessageId());
        relevantRfcMessageIds = relevantRfcMessageIds.stream().distinct().toList();

        // placeholder ThreadNode 没有 message 行；先锁引用链节点，才能让跨账号的
        // 并发兄弟回复在同一个已提交视图上重算，而不是各自保留独立 representative。
        threadMergeMapper.lockThreadNodesByRfcMessageIds(relevantRfcMessageIds);
        Set<Long> representativeSet = new LinkedHashSet<>(
                threadMergeMapper.selectCandidateRepresentativeIds(relevantRfcMessageIds));
        boolean currentSubjectIsReply = subjectIsReply(currentMessage.getSubject());
        if (currentMessage.getBaseSubject() != null
                && !currentMessage.getBaseSubject().isBlank()) {
            // 不同根节点没有共同业务行可锁；先取得主题级事务锁，确保并发首封根邮件
            // 依次查询已提交候选，避免 fallback 只在单线程测试中成立。
            threadMergeMapper.lockRootFallbackSubject(currentMessage.getBaseSubject());
            representativeSet.addAll(threadMergeMapper.selectRootFallbackRepresentativeIds(
                    currentMessage.getBaseSubject(), currentSubjectIsReply));
        }
        representativeSet.add(currentMessage.getThreadNodeId());
        List<Long> representativeIds = representativeSet.stream().sorted().toList();

        threadMergeMapper.lockMessagesByRepresentativeIds(representativeIds);
        long candidateMessageCount =
                threadMergeMapper.countActiveMessagesByRepresentativeIds(representativeIds);
        if (candidateMessageCount > threadSizeLimit) {
            // 不含 Subject/正文/地址；这些字段由攻击者控制且日志不需要它们。
            log.warn("会话规模超限，停止归并: threadNodeId={} size={} limit={}",
                    currentMessage.getThreadNodeId(), candidateMessageCount, threadSizeLimit);
            return new MergeResult(currentMessage.getThreadNodeId(), false, candidateMessageCount);
        }

        List<Message> candidateMessages = messageMapper.selectList(
                Wrappers.lambdaQuery(Message.class)
                        .in(Message::getThreadNodeId, representativeIds)
                        .orderByAsc(Message::getId));
        if (candidateMessages.isEmpty()) {
            throw new IllegalStateException("JWZ 候选集合不包含当前消息");
        }
        List<Long> candidateMessageIds = candidateMessages.stream().map(Message::getId).toList();
        Map<Long, List<String>> referencesByMessage = loadReferences(candidateMessageIds);
        Map<String, Long> ownNodeIdByMessageId = loadOwnNodeIds(candidateMessages);

        List<JwzThreadMerger.InputMessage> inputs = candidateMessages.stream()
                .map(message -> new JwzThreadMerger.InputMessage(
                        message.getId(),
                        requireOwnNodeId(ownNodeIdByMessageId, message.getMessageId()),
                        message.getMessageId(),
                        referencesByMessage.getOrDefault(message.getId(), List.of()),
                        message.getBaseSubject(),
                        subjectIsReply(message.getSubject())))
                .toList();
        Map<Long, Long> representativeByMessage =
                JwzThreadMerger.representativeByMessage(inputs);

        Map<Long, List<Long>> messageIdsByRepresentative = new LinkedHashMap<>();
        representativeByMessage.forEach((messageId, representativeId) ->
                messageIdsByRepresentative
                        .computeIfAbsent(representativeId, ignored -> new ArrayList<>())
                        .add(messageId));
        messageIdsByRepresentative.forEach((representativeId, messageIds) ->
                threadMergeMapper.updateRepresentative(messageIds, representativeId));

        long currentRepresentative = representativeByMessage.getOrDefault(
                currentMessage.getId(), currentMessage.getThreadNodeId());
        return new MergeResult(currentRepresentative, true, candidateMessageCount);
    }

    private Map<Long, List<String>> loadReferences(List<Long> messageIds) {
        List<MessageMention> mentions = messageMentionMapper.selectList(
                Wrappers.lambdaQuery(MessageMention.class)
                        .in(MessageMention::getMessageIdPk, messageIds)
                        .orderByAsc(MessageMention::getMessageIdPk)
                        .orderByAsc(MessageMention::getPosition));
        Map<Long, List<String>> result = new LinkedHashMap<>();
        for (MessageMention mention : mentions) {
            result.computeIfAbsent(mention.getMessageIdPk(), ignored -> new ArrayList<>())
                    .add(mention.getReferencedRfcMessageId());
        }
        return result;
    }

    private Map<String, Long> loadOwnNodeIds(List<Message> messages) {
        List<String> messageIds = messages.stream().map(Message::getMessageId).distinct().toList();
        List<ThreadNode> nodes = threadNodeMapper.selectList(
                Wrappers.lambdaQuery(ThreadNode.class)
                        .in(ThreadNode::getRfcMessageId, messageIds));
        Map<String, Long> result = new LinkedHashMap<>();
        nodes.stream()
                .sorted(Comparator.comparing(ThreadNode::getId))
                .forEach(node -> result.putIfAbsent(node.getRfcMessageId(), node.getId()));
        return result;
    }

    private static long requireOwnNodeId(Map<String, Long> nodes, String messageId) {
        Long nodeId = nodes.get(messageId);
        if (nodeId == null) {
            throw new IllegalStateException("消息自身 ThreadNode 不存在");
        }
        return nodeId;
    }

    private static boolean subjectIsReply(String subject) {
        if (subject == null) {
            return false;
        }
        String normalized = subject.stripLeading().toLowerCase(Locale.ROOT);
        return normalized.startsWith("re:")
                || normalized.startsWith("fw:")
                || normalized.startsWith("fwd:");
    }

    public record MergeResult(
            long representativeThreadNodeId,
            boolean merged,
            long candidateMessageCount) {
    }
}
