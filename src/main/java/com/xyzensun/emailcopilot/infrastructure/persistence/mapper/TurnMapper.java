package com.xyzensun.emailcopilot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Turn;

/**
 * 对话轮次。uk (conversation_id) WHERE status='running' 拦并发提问。
 */
public interface TurnMapper extends BaseMapper<Turn> {
}
