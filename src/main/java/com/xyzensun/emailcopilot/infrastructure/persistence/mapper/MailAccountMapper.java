package com.xyzensun.emailcopilot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.MailAccount;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 邮箱账号。删除前置条件是三个通道全部停用（API.md §12.3）。
 */
public interface MailAccountMapper extends BaseMapper<MailAccount> {

    /**
     * 阶段15 定时同步用：列出所有 IMAP 已启用的账号。
     * 定时同步只覆盖 imapEnabled 账号；停用账号不参与自动同步。
     */
    @Select("""
            select *
              from mail_account
             where imap_enabled = true
            """)
    List<MailAccount> selectImapEnabledAccounts();
}
