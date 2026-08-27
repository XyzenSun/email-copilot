package com.xyzensun.emailcopilot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xyzensun.emailcopilot.domain.enums.SourceChannelType;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 同一封邮件被多通道送达时的来源元数据，{@code FirstIngestWins} 的载体
 * （{@code DATABASE.md} §3.3）。
 *
 * <p>后续通道<b>只记来源，不比较内容差异、不替换</b> {@code message.bodyText}。
 * {@code isCanonical = true} 的行每封邮件只有一条。
 *
 * <p>{@code uk (messageIdPk, channelType)} 正好允许 outbound 邮件同时有
 * {@code (smtp, canonical)} 与后来 IMAP 读到 Sent 副本的 {@code (imap, 非 canonical)}
 * 两条来源——现有约束原样复用，无需新逻辑。
 */
@Data
@TableName("message_source")
public class MessageSource {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 逻辑引用 message。 */
    private Long messageIdPk;

    private SourceChannelType channelType;

    /** 首次入库的通道为 true，一封邮件仅一条。 */
    private Boolean isCanonical;

    /** 该通道的到达时间。 */
    private OffsetDateTime receivedAt;
}
