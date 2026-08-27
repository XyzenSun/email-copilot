package com.xyzensun.emailcopilot.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 阶段 3 邮箱设置需要的锁与聚合查询，不扩张通用 {@link MailAccountMapper}。 */
public interface MailAccountSettingsMapper {

    /**
     * 先锁账号行，再读取/更新配置或凭据。
     * 账号删除与 PATCH/PUT 因此不会交错出“账号已删但孤儿凭据刚写入”的状态。
     */
    @Select("select id from mail_account where id = #{id} for update")
    Long lockById(@Param("id") long id);

    @Select("select count(*) from message where mail_account_id = #{mailAccountId}")
    long countMessages(@Param("mailAccountId") long mailAccountId);
}
