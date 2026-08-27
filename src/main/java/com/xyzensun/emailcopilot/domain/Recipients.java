package com.xyzensun.emailcopilot.domain;

import java.util.List;

/**
 * 收件人列表。存储上是只读 JSONB 列，不建关系表（{@code DATABASE.md} §3.2）。
 *
 * <p>三个字段都非 null，空列表用空数组表示——{@code openapi.yaml} 的 {@code Recipients}
 * schema 把 to/cc/bcc 全部标为 required，前端因此不必处理"字段缺失"与"空列表"两种情况。
 *
 * <p>用 JSONB 而非关系表的理由（{@code DATABASE.md} §3.2）：结构只读、无外键约束价值
 * （收件人多为外部地址）、扩展频繁。
 *
 * <p><b>地址格式由应用在写入前校验</b>，JSONB 本身无法约束。创建审批提案时尤其不得相信
 * 模型已校验过收件人地址（{@code DATABASE.md} §5.5）。
 */
public record Recipients(List<String> to, List<String> cc, List<String> bcc) {

    public Recipients {
        to = to == null ? List.of() : List.copyOf(to);
        cc = cc == null ? List.of() : List.copyOf(cc);
        bcc = bcc == null ? List.of() : List.copyOf(bcc);
    }

    public static Recipients empty() {
        return new Recipients(List.of(), List.of(), List.of());
    }

    public static Recipients to(String... addresses) {
        return new Recipients(List.of(addresses), List.of(), List.of());
    }
}
