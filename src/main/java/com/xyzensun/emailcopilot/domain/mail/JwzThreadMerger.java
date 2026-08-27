package com.xyzensun.emailcopilot.domain.mail;

import org.apache.commons.net.nntp.Threadable;
import org.apache.commons.net.nntp.Threader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Apache Commons Net JWZ {@link Threader} 的纯领域适配。
 *
 * <p>主链由 References 建立，基础主题只交给库作根集兜底。持久化 representative
 * 取每棵输出树中最小真实 thread node id，使并发重算结果确定且只向更小代表收敛。
 */
public final class JwzThreadMerger {

    private JwzThreadMerger() {
    }

    public static Map<Long, Long> representativeByMessage(List<InputMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return Map.of();
        }
        List<MutableThreadable> nodes = messages.stream()
                .map(MutableThreadable::real)
                .toList();
        MutableThreadable root = (MutableThreadable) new Threader().thread(nodes);
        if (root == null) {
            return Map.of();
        }

        Map<Long, Long> result = new LinkedHashMap<>();
        MutableThreadable currentRoot = root;
        while (currentRoot != null) {
            MutableThreadable nextRoot = currentRoot.next;
            List<MutableThreadable> tree = new ArrayList<>();
            collectTree(currentRoot, tree);
            long representative = tree.stream()
                    .filter(node -> !node.dummy)
                    .mapToLong(node -> node.input.threadNodeId())
                    .min()
                    .orElseThrow(() -> new IllegalStateException("JWZ 输出树没有真实节点"));
            tree.stream()
                    .filter(node -> !node.dummy)
                    .forEach(node -> result.put(node.input.messageIdPk(), representative));
            currentRoot = nextRoot;
        }

        // Commons Net 会按 Message-ID 合并重复对象；同一真实 Message-ID 跨账号出现时，
        // 被折叠的对象仍应共享已保留对象的 representative。
        Map<String, Long> representativeByRfcMessageId = new HashMap<>();
        messages.forEach(message -> {
            Long representative = result.get(message.messageIdPk());
            if (representative != null) {
                representativeByRfcMessageId.putIfAbsent(message.rfcMessageId(), representative);
            }
        });
        messages.forEach(message -> {
            if (!result.containsKey(message.messageIdPk())) {
                Long representative = representativeByRfcMessageId.get(message.rfcMessageId());
                result.put(message.messageIdPk(),
                        representative == null ? message.threadNodeId() : representative);
            }
        });
        return Map.copyOf(result);
    }

    private static void collectTree(MutableThreadable node, List<MutableThreadable> destination) {
        destination.add(node);
        MutableThreadable child = node.child;
        while (child != null) {
            MutableThreadable nextSibling = child.next;
            collectTree(child, destination);
            child = nextSibling;
        }
    }

    public record InputMessage(
            long messageIdPk,
            long threadNodeId,
            String rfcMessageId,
            List<String> references,
            String baseSubject,
            boolean subjectIsReply) {

        public InputMessage {
            if (rfcMessageId == null || rfcMessageId.isBlank()) {
                throw new IllegalArgumentException("rfcMessageId 不能为空");
            }
            references = references == null ? List.of() : List.copyOf(references);
            baseSubject = baseSubject == null ? "" : baseSubject;
        }
    }

    private static final class MutableThreadable implements Threadable<MutableThreadable> {

        private final InputMessage input;
        private final boolean dummy;
        private MutableThreadable child;
        private MutableThreadable next;

        private MutableThreadable(InputMessage input, boolean dummy) {
            this.input = input;
            this.dummy = dummy;
        }

        private static MutableThreadable real(InputMessage input) {
            return new MutableThreadable(input, false);
        }

        @Override
        public boolean isDummy() {
            return dummy;
        }

        @Override
        public MutableThreadable makeDummy() {
            return new MutableThreadable(null, true);
        }

        @Override
        public String messageThreadId() {
            return dummy ? null : input.rfcMessageId();
        }

        @Override
        public String[] messageThreadReferences() {
            return dummy ? new String[0] : input.references().toArray(String[]::new);
        }

        @Override
        public void setChild(MutableThreadable child) {
            this.child = child;
        }

        @Override
        public void setNext(MutableThreadable next) {
            this.next = next;
        }

        @Override
        public String simplifiedSubject() {
            return dummy ? "" : input.baseSubject();
        }

        @Override
        public boolean subjectIsReply() {
            return !dummy && input.subjectIsReply();
        }
    }
}
