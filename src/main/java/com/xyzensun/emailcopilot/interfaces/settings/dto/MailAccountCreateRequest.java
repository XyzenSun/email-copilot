package com.xyzensun.emailcopilot.interfaces.settings.dto;

import com.xyzensun.emailcopilot.application.settings.model.MailAccountCreateCommand;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 凭据不随账号配置传入，避免它们被普通 DTO 日志展开。
 *
 * <p>三个开关省略时使用 OpenAPI 默认值 false；改用 setter 是为了保留这个默认值，同时能在
 * 显式提交 null 时立即拒绝请求，而不是把 null 静默转换成 false。
 */
public final class MailAccountCreateRequest {

    @NotBlank
    @Email
    private String emailAddress;

    @NotBlank
    private String displayName;

    private String imapHost;

    @Min(1)
    @Max(65_535)
    private Integer imapPort;

    private String imapUsername;
    private List<String> imapFolders;
    private boolean imapEnabled;
    private String smtpHost;

    @Min(1)
    @Max(65_535)
    private Integer smtpPort;

    private String smtpUsername;
    private boolean smtpEnabled;

    public void setEmailAddress(String emailAddress) {
        this.emailAddress = emailAddress;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public void setImapHost(String imapHost) {
        this.imapHost = imapHost;
    }

    public void setImapPort(Integer imapPort) {
        this.imapPort = imapPort;
    }

    public void setImapUsername(String imapUsername) {
        this.imapUsername = imapUsername;
    }

    public void setImapFolders(List<String> imapFolders) {
        this.imapFolders = imapFolders;
    }

    public void setImapEnabled(Boolean imapEnabled) {
        this.imapEnabled = requireNonNullBoolean("imapEnabled", imapEnabled);
    }

    public void setSmtpHost(String smtpHost) {
        this.smtpHost = smtpHost;
    }

    public void setSmtpPort(Integer smtpPort) {
        this.smtpPort = smtpPort;
    }

    public void setSmtpUsername(String smtpUsername) {
        this.smtpUsername = smtpUsername;
    }

    public void setSmtpEnabled(Boolean smtpEnabled) {
        this.smtpEnabled = requireNonNullBoolean("smtpEnabled", smtpEnabled);
    }

    public String getEmailAddress() {
        return emailAddress;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getImapHost() {
        return imapHost;
    }

    public Integer getImapPort() {
        return imapPort;
    }

    public String getImapUsername() {
        return imapUsername;
    }

    public List<String> getImapFolders() {
        return imapFolders;
    }

    public boolean getImapEnabled() {
        return imapEnabled;
    }

    public String getSmtpHost() {
        return smtpHost;
    }

    public Integer getSmtpPort() {
        return smtpPort;
    }

    public String getSmtpUsername() {
        return smtpUsername;
    }

    public boolean getSmtpEnabled() {
        return smtpEnabled;
    }

    public MailAccountCreateCommand toCommand() {
        return new MailAccountCreateCommand(
                emailAddress,
                displayName,
                imapHost,
                imapPort,
                imapUsername,
                imapFolders,
                imapEnabled,
                smtpHost,
                smtpPort,
                smtpUsername,
                smtpEnabled);
    }

    private static boolean requireNonNullBoolean(String field, Boolean value) {
        if (value == null) {
            throw new IllegalArgumentException(field + " 不能为 null");
        }
        return value;
    }
}
