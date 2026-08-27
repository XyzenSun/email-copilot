package com.xyzensun.emailcopilot.application.send;

import com.xyzensun.emailcopilot.application.mail.OutboundMessageIngester;
import com.xyzensun.emailcopilot.application.send.model.SendResultView;
import com.xyzensun.emailcopilot.domain.Recipients;
import com.xyzensun.emailcopilot.domain.enums.SecretType;
import com.xyzensun.emailcopilot.infrastructure.mail.SmtpMailSender;
import com.xyzensun.emailcopilot.infrastructure.mail.SmtpSendOutcome;
import com.xyzensun.emailcopilot.infrastructure.mail.SmtpUnavailableException;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.AppSetting;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.Draft;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.MailAccount;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.AppSettingMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.DraftMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.MailAccountMapper;
import com.xyzensun.emailcopilot.infrastructure.security.ExternalAccountSecretStore;
import com.xyzensun.emailcopilot.interfaces.error.ApiError;
import com.xyzensun.emailcopilot.interfaces.error.ApiException;
import com.xyzensun.emailcopilot.interfaces.error.ValidationErrorItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 用户直接发信（design.md §7）。
 *
 * <p><b>不经审批</b>：本服务背后的 {@link SmtpMailSender} <b>从不注册为 AI 工具</b>，
 * 对话 AI 没有任何途径调到它。用户自己点发送就是用户意图。
 *
 * <p><b>内容取自请求体，不从 draftId 读库</b>：发出去的必须是用户屏幕上那份。
 * draftId 仅用于"发送成功后删掉那行草稿"。
 *
 * <p><b>三态入库</b>：succeeded 入库 outbound + 删草稿；failed/indeterminate 不入库、
 * 保留草稿、不自动重发。SMTP 连不上（未提交数据）→ 503 SMTP_UNAVAILABLE（可安全重试）。
 */
@Service
public class SendApplicationService {

    private static final short SETTINGS_ROW_ID = 1;
    private static final int MIN_SMTP_TIMEOUT_SECONDS = 5;
    private static final Pattern EMAIL_ADDRESS = Pattern.compile(
            "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final MailAccountMapper mailAccountMapper;
    private final AppSettingMapper appSettingMapper;
    private final DraftMapper draftMapper;
    private final ExternalAccountSecretStore secretStore;
    private final SmtpMailSender smtpMailSender;
    private final OutboundMessageIngester outboundMessageIngester;
    private final TransactionTemplate transactionTemplate;

    public SendApplicationService(
            MailAccountMapper mailAccountMapper,
            AppSettingMapper appSettingMapper,
            DraftMapper draftMapper,
            ExternalAccountSecretStore secretStore,
            SmtpMailSender smtpMailSender,
            OutboundMessageIngester outboundMessageIngester,
            TransactionTemplate transactionTemplate) {
        this.mailAccountMapper = mailAccountMapper;
        this.appSettingMapper = appSettingMapper;
        this.draftMapper = draftMapper;
        this.secretStore = secretStore;
        this.smtpMailSender = smtpMailSender;
        this.outboundMessageIngester = outboundMessageIngester;
        this.transactionTemplate = transactionTemplate;
    }

    public SendResultView send(
            long fromMailAccountId,
            Long inReplyToMessageId,
            Recipients recipients,
            String subject,
            String bodyText,
            Long draftId) {

        MailAccount account = mailAccountMapper.selectById(fromMailAccountId);
        if (account == null) {
            throw new ApiException(ApiError.MAIL_ACCOUNT_NOT_FOUND);
        }
        requireSmtpConfigured(account);
        validateRecipients(recipients);
        if (draftId != null) {
            requireDraft(draftId);
        }

        // 回复头由代码拼：从原邮件的 Message-ID 拼 In-Reply-To，引用链追加成 References。
        OutboundMessageIngester.ReplyHeaders replyHeaders =
                outboundMessageIngester.resolveReplyHeaders(inReplyToMessageId);
        String rfcMessageId = generateRfcMessageId(account.getEmailAddress());
        String inReplyTo = replyHeaders != null ? replyHeaders.inReplyTo() : null;
        String references = replyHeaders != null ? replyHeaders.references() : null;

        int timeoutMillis = resolveSmtpTimeoutMillis();
        SmtpSendOutcome outcome;
        try {
            outcome = smtpMailSender.send(
                    account,
                    secretStore.load(SecretType.SMTP_PASSWORD, account.getId()).orElseThrow(),
                    recipients, subject, bodyText,
                    rfcMessageId, inReplyTo, references, timeoutMillis);
        } catch (SmtpUnavailableException exception) {
            // /send 的 503：未提交任何数据、可安全重试（与 approve 的 200+failed 分界）。
            throw new ApiException(ApiError.SMTP_UNAVAILABLE);
        }

        OffsetDateTime sentAt = OffsetDateTime.now();

        if (outcome.status() == SmtpSendOutcome.Status.SUCCEEDED) {
            List<String> referenceChain = replyHeaders != null
                    ? replyHeaders.referenceChain() : List.of();
            Long outboundMessageId = transactionTemplate.execute(status -> {
                Long messageId = outboundMessageIngester.ingest(
                        new OutboundMessageIngester.OutboundCommand(
                                account.getId(),
                                outcome.rfcMessageId(),
                                account.getEmailAddress(),
                                resolveDisplayName(account),
                                recipients, subject, bodyText, sentAt, referenceChain));
                if (draftId != null) {
                    draftMapper.deleteById(draftId);
                }
                return messageId;
            });
            return new SendResultView("succeeded", outboundMessageId, outcome.serverMessage());
        }

        // failed / indeterminate：不入库、保留草稿、不自动重发。
        String status = outcome.status().name().toLowerCase(Locale.ROOT);
        return new SendResultView(status, null, outcome.serverMessage());
    }

    private void requireSmtpConfigured(MailAccount account) {
        if (!Boolean.TRUE.equals(account.getSmtpEnabled())) {
            throw new ApiException(ApiError.SMTP_NOT_CONFIGURED);
        }
        if (!secretStore.exists(SecretType.SMTP_PASSWORD, account.getId())) {
            throw new ApiException(ApiError.SMTP_NOT_CONFIGURED);
        }
    }

    private void requireDraft(long draftId) {
        Draft draft = draftMapper.selectById(draftId);
        if (draft == null) {
            throw new ApiException(ApiError.DRAFT_NOT_FOUND);
        }
    }

    private static void validateRecipients(Recipients recipients) {
        if (recipients == null) {
            throw ApiException.validationFailed(List.of(
                    new ValidationErrorItem("recipients", "收件人不能为空")));
        }
        List<ValidationErrorItem> errors = new ArrayList<>();
        if (recipients.to().isEmpty() && recipients.cc().isEmpty() && recipients.bcc().isEmpty()) {
            errors.add(new ValidationErrorItem("recipients", "至少需要一个收件人"));
        }
        validateAddresses("recipients.to", recipients.to(), errors);
        validateAddresses("recipients.cc", recipients.cc(), errors);
        validateAddresses("recipients.bcc", recipients.bcc(), errors);
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
    }

    private static void validateAddresses(String field, List<String> addresses, List<ValidationErrorItem> errors) {
        for (int i = 0; i < addresses.size(); i++) {
            String address = addresses.get(i);
            if (address == null || address.isBlank() || !EMAIL_ADDRESS.matcher(address).matches()) {
                errors.add(new ValidationErrorItem(field + "[" + i + "]", "收件人地址格式非法"));
            }
        }
    }

    private int resolveSmtpTimeoutMillis() {
        AppSetting setting = appSettingMapper.selectById(SETTINGS_ROW_ID);
        int timeoutSeconds = setting != null && setting.getSmtpTimeoutSeconds() != null
                ? setting.getSmtpTimeoutSeconds() : 20;
        return Math.max(MIN_SMTP_TIMEOUT_SECONDS, timeoutSeconds) * 1000;
    }

    private static String resolveDisplayName(MailAccount account) {
        if (account.getDisplayName() != null && !account.getDisplayName().isBlank()) {
            return account.getDisplayName();
        }
        return account.getEmailAddress();
    }

    private static String generateRfcMessageId(String fromAddress) {
        String domain = extractDomain(fromAddress);
        return "<" + UUID.randomUUID() + "@" + domain + ">";
    }

    private static String extractDomain(String emailAddress) {
        int atIndex = emailAddress.lastIndexOf('@');
        if (atIndex < 0 || atIndex == emailAddress.length() - 1) {
            return emailAddress.toLowerCase(Locale.ROOT);
        }
        return emailAddress.substring(atIndex + 1).toLowerCase(Locale.ROOT);
    }
}
