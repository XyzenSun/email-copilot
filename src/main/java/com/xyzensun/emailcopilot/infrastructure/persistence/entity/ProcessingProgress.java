package com.xyzensun.emailcopilot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xyzensun.emailcopilot.domain.enums.ProcessingStage;
import com.xyzensun.emailcopilot.domain.enums.ProcessingStatus;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 每封邮件一份当前进度，单行阶段游标（{@code DATABASE.md} §4.4）。
 *
 * <p>{@link #stage} 指的是<b>下一项待执行阶段</b>，不是已完成的阶段。
 *
 * <p><b>只有 inbound 的邮件才建本表的行</b>：自己发出的邮件不需要分类、垃圾判定、
 * 翻译或摘要，因此领取队列的查询天然只看得到收到的邮件，无须额外条件。
 *
 * <p>判定结果<b>不进本表</b>——游标只指位置，不承载业务结果。分类、垃圾置信度、
 * 翻译、摘要都是 {@link Message} 的 typed columns。
 *
 * <p>阶段重试上限走全局配置（单用户系统所有邮件一个上限），不每行存。
 * 垃圾判断不访问外部网页，也不再保存取证计数。不设 {@code updatedAt}：
 * {@link #inProgressSince} 与 {@link #lastErrorAt} 已覆盖时间排查。
 */
@Data
@TableName("processing_progress")
public class ProcessingProgress {

    /**
     * 一封一行，逻辑引用 message。
     *
     * <p>{@code IdType.INPUT}：主键是引用来的 message id，由应用指定，不自增。
     */
    @TableId(type = IdType.INPUT)
    private Long messageIdPk;

    private ProcessingStage stage;

    private ProcessingStatus status;

    /** status=IN_PROGRESS 时必填（check 约束保证），用于识别僵尸。 */
    private OffsetDateTime inProgressSince;

    private Integer retryCount;

    /** status=FAILED 时必填（check 约束保证）。 */
    private String lastErrorCode;

    private OffsetDateTime lastErrorAt;
}
