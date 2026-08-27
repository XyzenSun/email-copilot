package com.xyzensun.emailcopilot.interfaces.settings;

import com.xyzensun.emailcopilot.application.settings.MailAccountApplicationService;
import com.xyzensun.emailcopilot.domain.enums.MailConnectionChannel;
import com.xyzensun.emailcopilot.domain.enums.SecretType;
import com.xyzensun.emailcopilot.interfaces.error.ApiException;
import com.xyzensun.emailcopilot.interfaces.error.ValidationErrorItem;
import com.xyzensun.emailcopilot.interfaces.settings.dto.AccountDeleteAcceptedResponse;
import com.xyzensun.emailcopilot.interfaces.settings.dto.AsyncTaskAcceptedResponse;
import com.xyzensun.emailcopilot.interfaces.settings.dto.MailAccountCreateRequest;
import com.xyzensun.emailcopilot.interfaces.settings.dto.MailAccountListResponse;
import com.xyzensun.emailcopilot.interfaces.settings.dto.MailAccountResponse;
import com.xyzensun.emailcopilot.interfaces.settings.dto.MailAccountUpdateRequest;
import com.xyzensun.emailcopilot.interfaces.settings.dto.MailAccountSecretValueRequest;
import com.xyzensun.emailcopilot.interfaces.settings.dto.MailAccountConnectionTestRequest;
import com.xyzensun.emailcopilot.interfaces.settings.dto.MailAccountConnectionTestResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 邮箱账号与凭据设置入口；Controller 只做绑定、路径枚举解析与响应映射。 */
@RestController
@RequestMapping("/api/mail-accounts")
public class MailAccountController {

    private final MailAccountApplicationService mailAccountApplicationService;

    public MailAccountController(MailAccountApplicationService mailAccountApplicationService) {
        this.mailAccountApplicationService = mailAccountApplicationService;
    }

    @GetMapping
    public MailAccountListResponse listMailAccounts() {
        return new MailAccountListResponse(
                mailAccountApplicationService.listMailAccounts().stream()
                        .map(MailAccountResponse::from)
                        .toList());
    }

    @PostMapping
    public ResponseEntity<MailAccountResponse> createMailAccount(
            @Valid @RequestBody MailAccountCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(MailAccountResponse.from(
                        mailAccountApplicationService.createMailAccount(request.toCommand())));
    }

    @GetMapping("/{id}")
    public MailAccountResponse getMailAccount(@PathVariable long id) {
        return MailAccountResponse.from(mailAccountApplicationService.getMailAccount(id));
    }

    @PatchMapping("/{id}")
    public MailAccountResponse updateMailAccount(
            @PathVariable long id,
            @Valid @RequestBody MailAccountUpdateRequest request) {
        return MailAccountResponse.from(
                mailAccountApplicationService.updateMailAccount(id, request.toCommand()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<AccountDeleteAcceptedResponse> deleteMailAccount(@PathVariable long id) {
        return ResponseEntity.accepted()
                .body(AccountDeleteAcceptedResponse.from(
                        mailAccountApplicationService.requestDelete(id)));
    }

    @PutMapping("/{id}/secrets/{type}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void putSecret(
            @PathVariable long id,
            @PathVariable String type,
            @Valid @RequestBody MailAccountSecretValueRequest request) {
        mailAccountApplicationService.putSecret(id, parseSecretType(type), request.value());
    }

    @PostMapping("/{id}/test-connection")
    public MailAccountConnectionTestResponse testConnection(
            @PathVariable long id,
            @Valid @RequestBody MailAccountConnectionTestRequest request) {
        return MailAccountConnectionTestResponse.from(
                mailAccountApplicationService.testConnection(
                        id, MailConnectionChannel.fromValue(request.channel())));
    }

    @PostMapping("/{id}/sync")
    public ResponseEntity<AsyncTaskAcceptedResponse> syncMailAccount(@PathVariable long id) {
        return ResponseEntity.accepted()
                .body(new AsyncTaskAcceptedResponse(
                        mailAccountApplicationService.requestSync(id)));
    }

    private static SecretType parseSecretType(String type) {
        return switch (type) {
            case "imap-password" -> SecretType.IMAP_PASSWORD;
            case "smtp-password" -> SecretType.SMTP_PASSWORD;
            default -> throw ApiException.validationFailed(
                    List.of(new ValidationErrorItem(
                            "type", "只支持 imap-password 或 smtp-password")));
        };
    }
}
