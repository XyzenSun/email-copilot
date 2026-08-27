package com.xyzensun.emailcopilot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Draft;

/**
 * 本地草稿。用户自己的增改不经审批。
 */
public interface DraftMapper extends BaseMapper<Draft> {
}
