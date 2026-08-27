package com.xyzensun.emailcopilot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 租约式领取（{@code DATABASE.md} §4.5）：<b>远程 AI/IMAP 调用期间不持有数据库行锁</b>。
 *
 * <p>领取者崩溃后租约到期，其他执行者可以重新领取。
 *
 * <p>领取必须是<b>比较并交换式的原子更新</b>——只有仍可执行且租约已过期的记录才能被认领，
 * 受影响行数为零表示已经被其他执行者取得：
 *
 * <pre>{@code
 * update processing_claim
 * set claimed_by = ?, claim_until = now() + interval '?', version = version + 1
 * where message_id_pk = ? and (claim_until is null or claim_until < now())
 * returning version;
 * }</pre>
 *
 * <p><b>{@link #version} 刻意不标 MyBatis-Plus 的 {@code @Version}</b>：
 * 领取走的是上面那条自定义 CAS，条件是租约到期而非版本相等。标了 {@code @Version} 后
 * {@code updateById} 会自动附加版本条件，与租约语义叠在一起，
 * 出现"版本对不上所以领取失败"这种与设计意图无关的失败。
 */
@Data
@TableName("processing_claim")
public class ProcessingClaim {

    /** 逻辑引用 message，由应用指定，不自增。 */
    @TableId(type = IdType.INPUT)
    private Long messageIdPk;

    /** 执行者标识。 */
    private String claimedBy;

    /** 租约到期时刻。 */
    private OffsetDateTime claimUntil;

    /** 乐观锁计数，由自定义 CAS 语句维护，见类注释。 */
    private Integer version;
}
