package com.xyzensun.emailcopilot.application.processing;

import com.xyzensun.emailcopilot.domain.enums.ProcessingStage;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Message;

import static java.util.Objects.requireNonNull;

/** 已完成阶段开始 CAS 后，供事务外处理使用的不可变定位信息与邮件快照。 */
public record ProcessingStageContext(
        ProcessingLease lease,
        ProcessingStage stage,
        Message message) {

    public ProcessingStageContext {
        requireNonNull(lease, "处理租约不能为空");
        requireNonNull(stage, "处理阶段不能为空");
        requireNonNull(message, "邮件快照不能为空");
    }

    @Override
    public String toString() {
        return "ProcessingStageContext[lease=" + lease
                + ", stage=" + stage
                + ", messageId=" + message.getId() + "]";
    }
}
