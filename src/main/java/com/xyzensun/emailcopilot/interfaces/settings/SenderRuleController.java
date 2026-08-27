package com.xyzensun.emailcopilot.interfaces.settings;

import com.xyzensun.emailcopilot.application.settings.SenderRuleApplicationService;
import com.xyzensun.emailcopilot.application.settings.SenderRuleApplicationService.CreateCommand;
import com.xyzensun.emailcopilot.application.settings.SenderRuleApplicationService.UpdateCommand;
import com.xyzensun.emailcopilot.interfaces.settings.dto.SenderRuleCreateRequest;
import com.xyzensun.emailcopilot.interfaces.settings.dto.SenderRuleListResponse;
import com.xyzensun.emailcopilot.interfaces.settings.dto.SenderRuleResponse;
import com.xyzensun.emailcopilot.interfaces.settings.dto.SenderRuleUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 发件人规则设置接口（{@code API.md} §12.5）。 */
@RestController
@RequestMapping("/api/sender-rules")
public class SenderRuleController {

    private final SenderRuleApplicationService senderRuleApplicationService;

    public SenderRuleController(SenderRuleApplicationService senderRuleApplicationService) {
        this.senderRuleApplicationService = senderRuleApplicationService;
    }

    @GetMapping
    public SenderRuleListResponse listSenderRules() {
        return new SenderRuleListResponse(senderRuleApplicationService.listSenderRules().stream()
                .map(SenderRuleResponse::from)
                .toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SenderRuleResponse createSenderRule(@Valid @RequestBody SenderRuleCreateRequest request) {
        return SenderRuleResponse.from(senderRuleApplicationService.createSenderRule(
                new CreateCommand(request.ruleType(), request.domainPattern(), request.enabled())));
    }

    @PatchMapping("/{id}")
    public SenderRuleResponse updateSenderRule(@PathVariable("id") long senderRuleId,
                                               @RequestBody SenderRuleUpdateRequest request) {
        return SenderRuleResponse.from(senderRuleApplicationService.updateSenderRule(
                senderRuleId,
                new UpdateCommand(
                        request.hasRuleType(),
                        request.ruleType(),
                        request.hasDomainPattern(),
                        request.domainPattern(),
                        request.hasEnabled(),
                        request.enabled())));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSenderRule(@PathVariable("id") long senderRuleId) {
        senderRuleApplicationService.deleteSenderRule(senderRuleId);
    }
}
