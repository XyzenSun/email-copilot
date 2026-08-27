package com.xyzensun.emailcopilot.interfaces.settings.dto;

import com.xyzensun.emailcopilot.application.settings.model.MailAccountUpdateCommand;
import com.xyzensun.emailcopilot.application.settings.model.MailAccountPatchValue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;

/**
 * PATCH 请求通过 setter 记录字段是否出现，保留“省略”和“显式 null”的区别。
 * nullable 配置显式传 null 会清空旧值；省略则保持不变。
 */
public class MailAccountUpdateRequest {

    @Email
    private String emailAddress;
    private boolean emailAddressPresent;

    private String displayName;
    private boolean displayNamePresent;

    private String imapHost;
    private boolean imapHostPresent;

    @Min(1)
    @Max(65_535)
    private Integer imapPort;
    private boolean imapPortPresent;

    private String imapUsername;
    private boolean imapUsernamePresent;

    private List<String> imapFolders;
    private boolean imapFoldersPresent;

    private Boolean imapEnabled;
    private boolean imapEnabledPresent;

    private String smtpHost;
    private boolean smtpHostPresent;

    @Min(1)
    @Max(65_535)
    private Integer smtpPort;
    private boolean smtpPortPresent;

    private String smtpUsername;
    private boolean smtpUsernamePresent;

    private Boolean smtpEnabled;
    private boolean smtpEnabledPresent;

    public String getEmailAddress() {
        return emailAddress;
    }

    public void setEmailAddress(String emailAddress) {
        this.emailAddressPresent = true;
        this.emailAddress = emailAddress;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayNamePresent = true;
        this.displayName = displayName;
    }

    public String getImapHost() {
        return imapHost;
    }

    public void setImapHost(String imapHost) {
        this.imapHostPresent = true;
        this.imapHost = imapHost;
    }

    public Integer getImapPort() {
        return imapPort;
    }

    public void setImapPort(Integer imapPort) {
        this.imapPortPresent = true;
        this.imapPort = imapPort;
    }

    public String getImapUsername() {
        return imapUsername;
    }

    public void setImapUsername(String imapUsername) {
        this.imapUsernamePresent = true;
        this.imapUsername = imapUsername;
    }

    public List<String> getImapFolders() {
        return imapFolders;
    }

    public void setImapFolders(List<String> imapFolders) {
        this.imapFoldersPresent = true;
        this.imapFolders = imapFolders;
    }

    public Boolean getImapEnabled() {
        return imapEnabled;
    }

    public void setImapEnabled(Boolean imapEnabled) {
        this.imapEnabledPresent = true;
        this.imapEnabled = imapEnabled;
    }

    public String getSmtpHost() {
        return smtpHost;
    }

    public void setSmtpHost(String smtpHost) {
        this.smtpHostPresent = true;
        this.smtpHost = smtpHost;
    }

    public Integer getSmtpPort() {
        return smtpPort;
    }

    public void setSmtpPort(Integer smtpPort) {
        this.smtpPortPresent = true;
        this.smtpPort = smtpPort;
    }

    public String getSmtpUsername() {
        return smtpUsername;
    }

    public void setSmtpUsername(String smtpUsername) {
        this.smtpUsernamePresent = true;
        this.smtpUsername = smtpUsername;
    }

    public Boolean getSmtpEnabled() {
        return smtpEnabled;
    }

    public void setSmtpEnabled(Boolean smtpEnabled) {
        this.smtpEnabledPresent = true;
        this.smtpEnabled = smtpEnabled;
    }

    public MailAccountUpdateCommand toCommand() {
        return new MailAccountUpdateCommand(
                patch(emailAddressPresent, emailAddress),
                patch(displayNamePresent, displayName),
                patch(imapHostPresent, imapHost),
                patch(imapPortPresent, imapPort),
                patch(imapUsernamePresent, imapUsername),
                patch(imapFoldersPresent, imapFolders),
                patch(imapEnabledPresent, imapEnabled),
                patch(smtpHostPresent, smtpHost),
                patch(smtpPortPresent, smtpPort),
                patch(smtpUsernamePresent, smtpUsername),
                patch(smtpEnabledPresent, smtpEnabled));
    }

    private static <T> MailAccountPatchValue<T> patch(boolean present, T value) {
        return present ? MailAccountPatchValue.present(value) : MailAccountPatchValue.absent();
    }
}
