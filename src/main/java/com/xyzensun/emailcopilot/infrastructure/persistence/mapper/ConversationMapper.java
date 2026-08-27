package com.xyzensun.emailcopilot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Conversation;

/**
 * 对话容器。物理删除时保留全部 pending_action 相关行。
 */
public interface ConversationMapper extends BaseMapper<Conversation> {
}
