package com.xyzensun.emailcopilot.infrastructure.settings;

import com.xyzensun.emailcopilot.infrastructure.persistence.entity.MailAccount;

/** 事务外执行的真实邮箱连接探测端口。 */
public interface MailConnectionProbe {

    ProbeResult testImap(MailAccount account, String password);

    ProbeResult testSmtp(MailAccount account, String password);

    record ProbeResult(boolean ok, String message) {
    }
}
