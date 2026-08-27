package com.xyzensun.emailcopilot.domain.enums;

import com.baomidou.mybatisplus.annotation.IEnum;

/**
 * 外部凭据的类型（{@code DATABASE.md} §8.2）。
 *
 * <p>这五种凭据全部以 AES-GCM 密文入库，密钥来自环境变量、不入库不入仓。
 * 因此仅取得数据库副本无法解出外部凭据，需要服务器环境与数据库同时失守。
 *
 * <p><b>{@code AI_API_KEY} 与 {@code EXA_API_KEY} 不绑定邮箱账号</b>，{@code mail_account_id} 为 null。
 * 这正是唯一索引必须写 {@code NULLS NOT DISTINCT} 的原因——PostgreSQL 默认语义下
 * NULL 彼此不相等，漏掉该子句能插入任意多条 AI key，取用时无法确定该用哪条，<b>而且不报错</b>。
 *
 * <p>AES-GCM 的 AAD <b>不存列</b>，由代码按固定规则拼出
 * （{@code secret_type + ':' + mail_account_id}），一处定义。存一份的唯一后果是它与解密时
 * 重算的值若有任何差异，解密直接失败且极难排查。
 *
 * <p>与登录口令的处理方式<b>不可互换</b>：登录口令是加盐哈希、不可逆，仅用于校验；
 * 这四种是对称加密、可逆，运行时还原后连接外部服务。
 */
public enum SecretType implements IEnum<String> {

    IMAP_PASSWORD("imap_password"),
    SMTP_PASSWORD("smtp_password"),
    AI_API_KEY("ai_api_key"),
    /** Exa MCP 只读搜索凭据，{@code mail_account_id} 为 null 的全局凭据（与 AI_API_KEY 同类）。 */
    EXA_API_KEY("exa_api_key"),
    /** Tavily 搜索凭据，{@code mail_account_id} 为 null 的全局凭据（与 EXA_API_KEY 同类）。 */
    TAVILY_API_KEY("tavily_api_key");

    private final String value;

    SecretType(String value) {
        this.value = value;
    }

    @Override
    public String getValue() {
        return value;
    }
}
