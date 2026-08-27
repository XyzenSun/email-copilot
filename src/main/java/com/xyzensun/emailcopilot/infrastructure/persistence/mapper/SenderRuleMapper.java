package com.xyzensun.emailcopilot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.SenderRule;

/**
 * 发件人规则，按已认证域名匹配。
 */
public interface SenderRuleMapper extends BaseMapper<SenderRule> {
}
