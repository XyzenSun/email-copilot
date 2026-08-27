package com.xyzensun.emailcopilot.application.settings;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xyzensun.emailcopilot.application.mail.ImapSyncApplicationService;
import com.xyzensun.emailcopilot.application.settings.model.AccountDeleteAccepted;
import com.xyzensun.emailcopilot.application.settings.model.MailAccountCreateCommand;
import com.xyzensun.emailcopilot.application.settings.model.MailAccountUpdateCommand;
import com.xyzensun.emailcopilot.application.settings.model.MailAccountView;
import com.xyzensun.emailcopilot.application.settings.model.MailConnectionTestResult;
import com.xyzensun.emailcopilot.application.settings.model.MailAccountPatchValue;
import com.xyzensun.emailcopilot.domain.enums.MailConnectionChannel;
import com.xyzensun.emailcopilot.domain.enums.SecretType;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.MailAccount;
import com.xyzensun.emailcopilot.infrastructure.persistence.handler.StringListJsonbTypeHandler;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MailAccountMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MailAccountSettingsMapper;
import com.xyzensun.emailcopilot.infrastructure.security.ExternalAccountSecretStore;
import com.xyzensun.emailcopilot.infrastructure.settings.MailConnectionProbe;
import com.xyzensun.emailcopilot.infrastructure.settings.MaintenanceTaskRegistry;
import com.xyzensun.emailcopilot.interfaces.error.ApiError;
import com.xyzensun.emailcopilot.interfaces.error.ApiException;
import com.xyzensun.emailcopilot.interfaces.error.ValidationErrorItem;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 邮箱账号、凭据、连接测试、同步登记与异步删除的用例编排。 */
@Service
public class MailAccountApplicationService {

    private final MailAccountMapper mailAccountMapper;
    private final MailAccountSettingsMapper mailAccountSettingsMapper;
    private final ExternalAccountSecretStore externalAccountSecretStore;
    private final MailConnectionProbe mailConnectionProbe;
    private final MaintenanceTaskRegistry maintenanceTaskRegistry;
    private final MaintenanceDatabaseService maintenanceDatabaseService;
    private final ImapSyncApplicationService imapSyncApplicationService;
    private final Clock clock;

    public MailAccountApplicationService(
            MailAccountMapper mailAccountMapper,
            MailAccountSettingsMapper mailAccountSettingsMapper,
            ExternalAccountSecretStore externalAccountSecretStore,
            MailConnectionProbe mailConnectionProbe,
            MaintenanceTaskRegistry maintenanceTaskRegistry,
            MaintenanceDatabaseService maintenanceDatabaseService,
            ImapSyncApplicationService imapSyncApplicationService,
            Clock clock) {
        this.mailAccountMapper = mailAccountMapper;
        this.mailAccountSettingsMapper = mailAccountSettingsMapper;
        this.externalAccountSecretStore = externalAccountSecretStore;
        this.mailConnectionProbe = mailConnectionProbe;
        this.maintenanceTaskRegistry = maintenanceTaskRegistry;
        this.maintenanceDatabaseService = maintenanceDatabaseService;
        this.imapSyncApplicationService = imapSyncApplicationService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<MailAccountView> listMailAccounts() {
        return mailAccountMapper.selectList(
                        Wrappers.lambdaQuery(MailAccount.class).orderByAsc(MailAccount::getId))
                .stream()
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public MailAccountView getMailAccount(long mailAccountId) {
        return toView(getRequiredAccount(mailAccountId));
    }

    @Transactional
    public MailAccountView createMailAccount(MailAccountCreateCommand command) {
        MailAccount account = normalize(fromCreateCommand(command));
        validateAccount(account);

        // 凭据接口要求账号 id，因此创建时不能同时配置凭据；先停用创建、写凭据、再启用。
        if (hasAnyEnabledChannel(account)) {
            throw new ApiException(
                    ApiError.SECRET_REQUIRED,
                    "请先以停用状态创建账号、写入对应凭据，再启用通道");
        }

        try {
            mailAccountMapper.insert(account);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(ApiError.MAIL_ACCOUNT_ADDRESS_TAKEN);
        }
        return toView(getRequiredAccount(account.getId()));
    }

    @Transactional
    public MailAccountView updateMailAccount(long mailAccountId, MailAccountUpdateCommand command) {
        if (!command.hasAnyField()) {
            throw ApiException.validationFailed(
                    List.of(new ValidationErrorItem("$", "至少需要提供一个要修改的字段")));
        }

        lockRequiredAccount(mailAccountId);
        MailAccount merged = getRequiredAccount(mailAccountId);
        applyPatch(merged, command);
        normalize(merged);
        validateAccount(merged);
        ensureSecretsForEnabledChannels(merged);

        LambdaUpdateWrapper<MailAccount> update = new LambdaUpdateWrapper<>();
        setPatchedColumns(update, command, merged);
        update.set(MailAccount::getUpdatedAt, now());
        update.eq(MailAccount::getId, mailAccountId);

        try {
            if (mailAccountMapper.update(null, update) != 1) {
                throw new ApiException(ApiError.MAIL_ACCOUNT_NOT_FOUND);
            }
        } catch (DuplicateKeyException ex) {
            throw new ApiException(ApiError.MAIL_ACCOUNT_ADDRESS_TAKEN);
        }
        return toView(getRequiredAccount(mailAccountId));
    }

    /**
     * 锁住账号行后 upsert 凭据，防止异步删除在“账号存在性检查”和写密文之间穿过并留下孤儿行。
     */
    @Transactional
    public void putSecret(long mailAccountId, SecretType secretType, String plaintext) {
        lockRequiredAccount(mailAccountId);
        if (plaintext == null || plaintext.isEmpty()) {
            throw ApiException.validationFailed(
                    List.of(new ValidationErrorItem("value", "凭据不能为空")));
        }
        externalAccountSecretStore.save(secretType, mailAccountId, plaintext);
    }

    /** 远程 I/O 不带 {@code @Transactional}，连接期间不持有数据库连接或行锁。 */
    public MailConnectionTestResult testConnection(
            long mailAccountId, MailConnectionChannel channel) {
        MailAccount account = getRequiredAccount(mailAccountId);
        MailConnectionProbe.ProbeResult probeResult;
        if (channel == MailConnectionChannel.IMAP) {
            ensureImapProbeReady(account, false);
            String password = externalAccountSecretStore.load(SecretType.IMAP_PASSWORD, mailAccountId)
                    .orElseThrow(() -> new ApiException(ApiError.SECRET_REQUIRED));
            probeResult = mailConnectionProbe.testImap(account, password);
        } else {
            ensureSmtpProbeReady(account);
            String password = externalAccountSecretStore.load(SecretType.SMTP_PASSWORD, mailAccountId)
                    .orElseThrow(() -> new ApiException(ApiError.SECRET_REQUIRED));
            probeResult = mailConnectionProbe.testSmtp(account, password);
        }
        return new MailConnectionTestResult(probeResult.ok(), probeResult.message());
    }

    public String requestSync(long mailAccountId) {
        MailAccount account = getRequiredAccount(mailAccountId);
        ensureImapProbeReady(account, true);
        if (!externalAccountSecretStore.exists(SecretType.IMAP_PASSWORD, mailAccountId)) {
            throw new ApiException(ApiError.SECRET_REQUIRED);
        }

        return maintenanceTaskRegistry.tryStartSync(
                        mailAccountId,
                        "等待执行只读 IMAP 同步",
                        reporter -> imapSyncApplicationService.synchronize(mailAccountId, reporter))
                .orElseThrow(() -> new ApiException(ApiError.SYNC_ALREADY_RUNNING));
    }

    public AccountDeleteAccepted requestDelete(long mailAccountId) {
        MailAccount account = getRequiredAccount(mailAccountId);
        if (hasAnyEnabledChannel(account)) {
            throw new ApiException(ApiError.MAIL_ACCOUNT_NOT_DISABLED);
        }
        long messageCount = mailAccountSettingsMapper.countMessages(mailAccountId);

        String taskId = maintenanceTaskRegistry.tryStartAccountDelete(
                        mailAccountId,
                        "准备删除账号数据，共 " + messageCount + " 封邮件",
                        reporter -> {
                            reporter.update("正在删除账号的本地邮件、凭据与草稿");
                            try {
                                maintenanceDatabaseService.deleteMailAccount(mailAccountId);
                            } catch (ApiException ex) {
                                // 受理后状态发生变化属于可安全展示的失败，不把底层 SQL 异常暴露给前端。
                                throw new MaintenanceTaskRegistry.ExpectedTaskFailure(ex.error().title());
                            }
                            reporter.update("账号删除完成，共删除 " + messageCount + " 封邮件");
                        })
                .orElseThrow(() -> new ApiException(ApiError.MAINTENANCE_TASK_RUNNING));
        return new AccountDeleteAccepted(taskId, messageCount);
    }

    private MailAccount getRequiredAccount(long mailAccountId) {
        MailAccount account = mailAccountMapper.selectById(mailAccountId);
        if (account == null) {
            throw new ApiException(ApiError.MAIL_ACCOUNT_NOT_FOUND);
        }
        return account;
    }

    private void lockRequiredAccount(long mailAccountId) {
        if (mailAccountSettingsMapper.lockById(mailAccountId) == null) {
            throw new ApiException(ApiError.MAIL_ACCOUNT_NOT_FOUND);
        }
    }

    private MailAccountView toView(MailAccount account) {
        long accountId = account.getId();
        return new MailAccountView(
                accountId,
                account.getEmailAddress(),
                account.getDisplayName(),
                account.getImapHost(),
                account.getImapPort(),
                account.getImapUsername(),
                account.getImapFolders() == null ? null : List.copyOf(account.getImapFolders()),
                Boolean.TRUE.equals(account.getImapEnabled()),
                account.getSmtpHost(),
                account.getSmtpPort(),
                account.getSmtpUsername(),
                Boolean.TRUE.equals(account.getSmtpEnabled()),
                externalAccountSecretStore.exists(SecretType.IMAP_PASSWORD, accountId),
                externalAccountSecretStore.exists(SecretType.SMTP_PASSWORD, accountId),
                mailAccountSettingsMapper.countMessages(accountId),
                account.getCreatedAt(),
                account.getUpdatedAt());
    }

    private static MailAccount fromCreateCommand(MailAccountCreateCommand command) {
        MailAccount account = new MailAccount();
        account.setEmailAddress(command.emailAddress());
        account.setDisplayName(command.displayName());
        account.setImapHost(command.imapHost());
        account.setImapPort(command.imapPort());
        account.setImapUsername(command.imapUsername());
        account.setImapFolders(command.imapFolders());
        account.setImapEnabled(command.imapEnabled());
        account.setSmtpHost(command.smtpHost());
        account.setSmtpPort(command.smtpPort());
        account.setSmtpUsername(command.smtpUsername());
        account.setSmtpEnabled(command.smtpEnabled());
        return account;
    }

    private static void applyPatch(MailAccount account, MailAccountUpdateCommand command) {
        apply(command.emailAddress(), account::setEmailAddress);
        apply(command.displayName(), account::setDisplayName);
        apply(command.imapHost(), account::setImapHost);
        apply(command.imapPort(), account::setImapPort);
        apply(command.imapUsername(), account::setImapUsername);
        apply(command.imapFolders(), account::setImapFolders);
        apply(command.imapEnabled(), account::setImapEnabled);
        apply(command.smtpHost(), account::setSmtpHost);
        apply(command.smtpPort(), account::setSmtpPort);
        apply(command.smtpUsername(), account::setSmtpUsername);
        apply(command.smtpEnabled(), account::setSmtpEnabled);
    }

    private static <T> void apply(MailAccountPatchValue<T> patchValue, java.util.function.Consumer<T> setter) {
        if (patchValue.present()) {
            setter.accept(patchValue.value());
        }
    }

    private static void setPatchedColumns(
            LambdaUpdateWrapper<MailAccount> update,
            MailAccountUpdateCommand command,
            MailAccount merged) {
        set(update, command.emailAddress(), MailAccount::getEmailAddress, merged.getEmailAddress());
        set(update, command.displayName(), MailAccount::getDisplayName, merged.getDisplayName());
        set(update, command.imapHost(), MailAccount::getImapHost, merged.getImapHost());
        set(update, command.imapPort(), MailAccount::getImapPort, merged.getImapPort());
        set(update, command.imapUsername(), MailAccount::getImapUsername, merged.getImapUsername());
        if (command.imapFolders().present()) {
            // Wrapper 参数拿不到实体字段上的 TypeHandler 元数据，必须在 SET 参数上显式指定；
            // 否则 PostgreSQL 驱动无法为 ArrayList 推断 SQL 类型，PATCH 文件夹会在运行时 500。
            update.set(
                    MailAccount::getImapFolders,
                    merged.getImapFolders(),
                    "jdbcType=OTHER,typeHandler=" + StringListJsonbTypeHandler.class.getName());
        }
        set(update, command.imapEnabled(), MailAccount::getImapEnabled, merged.getImapEnabled());
        set(update, command.smtpHost(), MailAccount::getSmtpHost, merged.getSmtpHost());
        set(update, command.smtpPort(), MailAccount::getSmtpPort, merged.getSmtpPort());
        set(update, command.smtpUsername(), MailAccount::getSmtpUsername, merged.getSmtpUsername());
        set(update, command.smtpEnabled(), MailAccount::getSmtpEnabled, merged.getSmtpEnabled());
    }

    private static <T> void set(
            LambdaUpdateWrapper<MailAccount> update,
            MailAccountPatchValue<?> patchValue,
            com.baomidou.mybatisplus.core.toolkit.support.SFunction<MailAccount, T> column,
            T value) {
        if (patchValue.present()) {
            update.set(column, value);
        }
    }

    private static MailAccount normalize(MailAccount account) {
        account.setEmailAddress(normalizeEmail(account.getEmailAddress()));
        account.setDisplayName(trimRequired(account.getDisplayName()));
        account.setImapHost(normalizeHost(account.getImapHost()));
        account.setImapUsername(trimNullable(account.getImapUsername()));
        account.setSmtpHost(normalizeHost(account.getSmtpHost()));
        account.setSmtpUsername(trimNullable(account.getSmtpUsername()));
        if (account.getImapFolders() != null) {
            account.setImapFolders(new ArrayList<>(account.getImapFolders()));
        }
        return account;
    }

    private static void validateAccount(MailAccount account) {
        List<ValidationErrorItem> errors = new ArrayList<>();
        if (isBlank(account.getEmailAddress())) {
            errors.add(new ValidationErrorItem("emailAddress", "邮箱地址不能为空"));
        } else if (!isValidMailboxAddress(account.getEmailAddress())) {
            errors.add(new ValidationErrorItem("emailAddress", "必须是合法的邮箱地址"));
        }
        if (isBlank(account.getDisplayName())) {
            errors.add(new ValidationErrorItem("displayName", "展示名不能为空"));
        }
        validatePort("imapPort", account.getImapPort(), errors);
        validatePort("smtpPort", account.getSmtpPort(), errors);
        validateBoolean("imapEnabled", account.getImapEnabled(), errors);
        validateBoolean("smtpEnabled", account.getSmtpEnabled(), errors);

        if (Boolean.TRUE.equals(account.getImapEnabled())) {
            requireNonBlank("imapHost", account.getImapHost(), errors);
            requireNonNull("imapPort", account.getImapPort(), errors);
            requireNonBlank("imapUsername", account.getImapUsername(), errors);
        }
        if (Boolean.TRUE.equals(account.getSmtpEnabled())) {
            requireNonBlank("smtpHost", account.getSmtpHost(), errors);
            requireNonNull("smtpPort", account.getSmtpPort(), errors);
            requireNonBlank("smtpUsername", account.getSmtpUsername(), errors);
        }
        if (account.getImapFolders() != null) {
            for (int index = 0; index < account.getImapFolders().size(); index++) {
                if (account.getImapFolders().get(index) == null) {
                    errors.add(new ValidationErrorItem(
                            "imapFolders[" + index + "]", "文件夹名称不能为 null"));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
    }

    private void ensureSecretsForEnabledChannels(MailAccount account) {
        long accountId = account.getId();
        if (Boolean.TRUE.equals(account.getImapEnabled())
                && !externalAccountSecretStore.exists(SecretType.IMAP_PASSWORD, accountId)) {
            throw new ApiException(ApiError.SECRET_REQUIRED, "启用 IMAP 前必须先配置 IMAP 凭据");
        }
        if (Boolean.TRUE.equals(account.getSmtpEnabled())
                && !externalAccountSecretStore.exists(SecretType.SMTP_PASSWORD, accountId)) {
            throw new ApiException(ApiError.SECRET_REQUIRED, "启用 SMTP 前必须先配置 SMTP 凭据");
        }
    }

    private static void ensureImapProbeReady(MailAccount account, boolean requireEnabled) {
        if ((requireEnabled && !Boolean.TRUE.equals(account.getImapEnabled()))
                || isBlank(account.getImapHost())
                || account.getImapPort() == null
                || isBlank(account.getImapUsername())) {
            throw new ApiException(ApiError.SECRET_REQUIRED, "IMAP 未启用或服务器配置不完整");
        }
    }

    private static void ensureSmtpProbeReady(MailAccount account) {
        if (isBlank(account.getSmtpHost())
                || account.getSmtpPort() == null
                || isBlank(account.getSmtpUsername())) {
            throw new ApiException(ApiError.SECRET_REQUIRED, "SMTP 服务器配置不完整");
        }
    }

    private static boolean hasAnyEnabledChannel(MailAccount account) {
        return Boolean.TRUE.equals(account.getImapEnabled())
                || Boolean.TRUE.equals(account.getSmtpEnabled());
    }

    private static String normalizeEmail(String value) {
        String trimmed = trimRequired(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static String normalizeHost(String value) {
        String trimmed = trimNullable(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static String trimRequired(String value) {
        return value == null ? null : value.trim();
    }

    private static String trimNullable(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isValidMailboxAddress(String value) {
        try {
            InternetAddress parsed = new InternetAddress(value, true);
            parsed.validate();
            return value.equalsIgnoreCase(parsed.getAddress());
        } catch (AddressException ex) {
            return false;
        }
    }

    private static void validatePort(
            String field, Integer port, List<ValidationErrorItem> errors) {
        if (port != null && (port < 1 || port > 65_535)) {
            errors.add(new ValidationErrorItem(field, "必须在 1 到 65535 之间"));
        }
    }

    private static void validateBoolean(
            String field, Boolean value, List<ValidationErrorItem> errors) {
        if (value == null) {
            errors.add(new ValidationErrorItem(field, "不能为 null"));
        }
    }

    private static void requireNonBlank(
            String field, String value, List<ValidationErrorItem> errors) {
        if (isBlank(value)) {
            errors.add(new ValidationErrorItem(field, "启用通道时必须配置该字段"));
        }
    }

    private static void requireNonNull(
            String field, Object value, List<ValidationErrorItem> errors) {
        if (value == null) {
            errors.add(new ValidationErrorItem(field, "启用通道时必须配置该字段"));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
