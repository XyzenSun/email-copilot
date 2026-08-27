package com.xyzensun.emailcopilot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * {@link Message} 列出的上游引用（{@code References} / {@code In-Reply-To}），
 * 会话归并的输入（{@code DATABASE.md} §3.5）。
 *
 * <p>引用的目标可能本系统未持有，但 {@link ThreadNode} 已为其建节点，
 * 因此 {@code referencedRfcMessageId} 可 join {@code thread_node.rfcMessageId} 找到节点。
 * 不需要单独的 {@code thread_node_mention} 表。
 *
 * <p><b>复合主键 {@code (messageIdPk, referencedRfcMessageId)}，无单列 id。</b>
 * MyBatis-Plus 不支持复合主键，因此本实体不标 {@code @TableId}：
 * {@code insert} 可用，{@code selectById} / {@code updateById} / {@code deleteById} 不可用——
 * 这几个方法本来也用不上，本表只按 messageIdPk 或 referencedRfcMessageId 成组查询。
 *
 * <p>发信时由代码把原邮件的 Message-ID 追加到已有引用链后面组成 References，
 * <b>不接受模型自行拼头</b>（{@code DATABASE.md} §5.5）。
 */
@Data
@TableName("message_mention")
public class MessageMention {

    /** 逻辑引用 message。 */
    private Long messageIdPk;

    private String referencedRfcMessageId;

    /** 在 References 中的顺序。 */
    private Integer position;
}
