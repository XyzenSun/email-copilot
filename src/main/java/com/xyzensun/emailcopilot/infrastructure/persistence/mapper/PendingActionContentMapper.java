package com.xyzensun.emailcopilot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.PendingActionContent;

/**
 * 发送/草稿的不可变快照。执行只读此表，不读 draft。
 */
public interface PendingActionContentMapper extends BaseMapper<PendingActionContent> {
}
