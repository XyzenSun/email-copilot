package com.xyzensun.emailcopilot.infrastructure.ai.tools;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.MailAccount;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MailAccountMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 对话 AI 的邮箱账号列表工具（阶段10 design §3.3）。
 *
 * <p>AI 发信/起草需 {@code fromMailAccountId}（见 {@code SaveDraftProposalTool}），但此前
 * 无任何工具/上下文能告诉 AI 当前有哪些邮箱账号 → 只能反复问用户。本工具补缺。
 *
 * <p><b>最小暴露原则</b>：只返回发信所需字段（id + emailAddress + displayName + smtpEnabled），
 * 不返回 host/port/username/secrets exists（{@code MailAccountView} 有 19 字段，全量暴露
 * 不安全且无必要）。注入 {@link MailAccountMapper} 直接查精简数据，不经
 * {@code MailAccountApplicationService} 的 secrets exists 查询开销。
 */
@Component
public class ListMailAccountsTool {

    private final MailAccountMapper mailAccountMapper;

    public ListMailAccountsTool(MailAccountMapper mailAccountMapper) {
        this.mailAccountMapper = mailAccountMapper;
    }

    @Tool(name = "list_mail_accounts", description = """
            列出当前可用的邮箱账号。返回每个账号的 id、邮箱地址、显示名、是否可发信(smtpEnabled)。
            发信或起草草稿前调用以获取 fromMailAccountId。
            """)
    public String listMailAccounts() {
        List<MailAccount> accounts = mailAccountMapper.selectList(
                Wrappers.<MailAccount>lambdaQuery().orderByAsc(MailAccount::getId));

        if (accounts.isEmpty()) {
            return "当前没有配置任何邮箱账号。";
        }

        StringBuilder sb = new StringBuilder("共 " + accounts.size() + " 个邮箱账号：\n");
        for (MailAccount account : accounts) {
            sb.append("- id: ").append(account.getId());
            sb.append(" | 邮箱: ").append(account.getEmailAddress());
            sb.append(" | 显示名: ").append(account.getDisplayName());
            sb.append(" | 可发信: ").append(Boolean.TRUE.equals(account.getSmtpEnabled()));
            sb.append('\n');
        }
        return sb.toString();
    }
}
