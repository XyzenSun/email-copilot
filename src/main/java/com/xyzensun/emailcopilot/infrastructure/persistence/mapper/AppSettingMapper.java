package com.xyzensun.emailcopilot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.AppSetting;

/**
 * 单行配置表。只有 UPDATE，没有 insert/delete。
 */
public interface AppSettingMapper extends BaseMapper<AppSetting> {
}
