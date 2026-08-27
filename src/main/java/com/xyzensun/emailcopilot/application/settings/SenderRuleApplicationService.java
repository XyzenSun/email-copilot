package com.xyzensun.emailcopilot.application.settings;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xyzensun.emailcopilot.domain.enums.SenderRuleType;
import com.xyzensun.emailcopilot.domain.sender.DomainPattern;
import com.xyzensun.emailcopilot.domain.sender.DomainPatternValidator;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.SenderRule;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.SenderRuleMapper;
import com.xyzensun.emailcopilot.interfaces.error.ApiError;
import com.xyzensun.emailcopilot.interfaces.error.ApiException;
import com.xyzensun.emailcopilot.interfaces.error.ValidationErrorItem;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 发件人规则设置用例。
 *
 * <p>规则只影响后续进入流水线的邮件，本服务不会重置历史邮件的分类或处理游标。
 * 去重直接依赖数据库 {@code uk_sender_rule(rule_type, domain_pattern)}，不做并发不安全的
 * “先查再插”。
 */
@Service
public class SenderRuleApplicationService {

    private final SenderRuleMapper senderRuleMapper;

    public SenderRuleApplicationService(SenderRuleMapper senderRuleMapper) {
        this.senderRuleMapper = senderRuleMapper;
    }

    @Transactional(readOnly = true)
    public List<SenderRuleView> listSenderRules() {
        return senderRuleMapper.selectList(
                        Wrappers.lambdaQuery(SenderRule.class).orderByAsc(SenderRule::getId))
                .stream()
                .map(SenderRuleApplicationService::toView)
                .toList();
    }

    @Transactional
    public SenderRuleView createSenderRule(CreateCommand command) {
        validateCreateCommand(command);
        SenderRuleType ruleType = parseRuleType(command.ruleType());
        DomainPattern domainPattern = parseDomainPattern(command.domainPattern());

        SenderRule senderRule = new SenderRule();
        senderRule.setRuleType(ruleType);
        senderRule.setDomainPattern(domainPattern.value());
        senderRule.setEnabled(command.enabled() == null ? Boolean.TRUE : command.enabled());

        try {
            senderRuleMapper.insert(senderRule);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(ApiError.SENDER_RULE_DUPLICATE);
        }
        return toView(senderRule);
    }

    @Transactional
    public SenderRuleView updateSenderRule(long senderRuleId, UpdateCommand command) {
        validateUpdateCommand(command);

        SenderRuleType updatedRuleType = command.ruleTypePresent()
                ? parseRuleType(command.ruleType())
                : null;
        DomainPattern updatedDomainPattern = command.domainPatternPresent()
                ? parseDomainPattern(command.domainPattern())
                : null;

        LambdaUpdateWrapper<SenderRule> update = new LambdaUpdateWrapper<SenderRule>()
                .eq(SenderRule::getId, senderRuleId);
        if (command.ruleTypePresent()) {
            update.set(SenderRule::getRuleType, updatedRuleType);
        }
        if (command.domainPatternPresent()) {
            update.set(SenderRule::getDomainPattern, updatedDomainPattern.value());
        }
        if (command.enabledPresent()) {
            update.set(SenderRule::getEnabled, command.enabled());
        }
        update.setSql("updated_at = now()");

        try {
            int updatedRows = senderRuleMapper.update(null, update);
            if (updatedRows == 0) {
                throw new ApiException(ApiError.SENDER_RULE_NOT_FOUND);
            }
        } catch (DuplicateKeyException ex) {
            throw new ApiException(ApiError.SENDER_RULE_DUPLICATE);
        }
        // 重新读取数据库事实源，避免返回旧快照，也让并发 PATCH 的最终值与响应一致。
        SenderRule persisted = senderRuleMapper.selectById(senderRuleId);
        if (persisted == null) {
            throw new ApiException(ApiError.SENDER_RULE_NOT_FOUND);
        }
        return toView(persisted);

    }

    @Transactional
    public void deleteSenderRule(long senderRuleId) {
        if (senderRuleMapper.deleteById(senderRuleId) == 0) {
            throw new ApiException(ApiError.SENDER_RULE_NOT_FOUND);
        }
    }

    private static void validateCreateCommand(CreateCommand command) {
        List<ValidationErrorItem> validationErrors = new ArrayList<>();
        addRequiredStringError(validationErrors, "ruleType", command.ruleType());
        addRequiredStringError(validationErrors, "domainPattern", command.domainPattern());
        if (!validationErrors.isEmpty()) {
            throw ApiException.validationFailed(validationErrors);
        }
    }

    private static void validateUpdateCommand(UpdateCommand command) {
        List<ValidationErrorItem> validationErrors = new ArrayList<>();
        if (!command.hasAnyField()) {
            validationErrors.add(new ValidationErrorItem("$", "至少需要提供一个可修改字段"));
        }
        if (command.ruleTypePresent()) {
            addRequiredStringError(validationErrors, "ruleType", command.ruleType());
        }
        if (command.domainPatternPresent()) {
            addRequiredStringError(validationErrors, "domainPattern", command.domainPattern());
        }
        if (command.enabledPresent() && command.enabled() == null) {
            validationErrors.add(new ValidationErrorItem("enabled", "不能为 null"));
        }
        if (!validationErrors.isEmpty()) {
            throw ApiException.validationFailed(validationErrors);
        }
    }

    private static void addRequiredStringError(
            List<ValidationErrorItem> validationErrors, String field, String value) {
        if (value == null || value.isBlank()) {
            validationErrors.add(new ValidationErrorItem(field, "不能为空"));
        }
    }

    private static SenderRuleType parseRuleType(String ruleType) {
        return switch (ruleType) {
            case "block" -> SenderRuleType.BLOCK;
            case "trust" -> SenderRuleType.TRUST;
            default -> throw ApiException.validationFailed(List.of(
                    new ValidationErrorItem("ruleType", "只支持 block 或 trust")));
        };
    }

    private static DomainPattern parseDomainPattern(String domainPattern) {
        try {
            return DomainPatternValidator.validate(domainPattern);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ApiError.INVALID_DOMAIN_PATTERN);
        }
    }

    private static SenderRuleView toView(SenderRule senderRule) {
        return new SenderRuleView(
                senderRule.getId(),
                senderRule.getRuleType().getValue(),
                senderRule.getDomainPattern(),
                senderRule.getEnabled(),
                senderRule.getUpdatedAt());
    }

    public record CreateCommand(String ruleType, String domainPattern, Boolean enabled) {
    }

    public record UpdateCommand(
            boolean ruleTypePresent,
            String ruleType,
            boolean domainPatternPresent,
            String domainPattern,
            boolean enabledPresent,
            Boolean enabled) {

        public boolean hasAnyField() {
            return ruleTypePresent || domainPatternPresent || enabledPresent;
        }
    }

    public record SenderRuleView(
            Long id,
            String ruleType,
            String domainPattern,
            Boolean enabled,
            OffsetDateTime updatedAt) {
    }
}
