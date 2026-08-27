package com.xyzensun.emailcopilot.interfaces.settings.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.NotBlank;

/**
 * 新建发件人规则请求，对应 {@code openapi.yaml SenderRuleCreateRequest}。
 *
 * <p>{@code enabled} 省略时保留 OpenAPI 默认值 true；显式 null 会由 setter 拒绝，不能被默认值
 * 吞掉。这里不用 record，因为 record 的构造参数无法区分“JSON 未出现”和“出现但为 null”。
 */
public final class SenderRuleCreateRequest {

    @NotBlank(message = "规则类型不能为空")
    private String ruleType;

    @NotBlank(message = "域名模式不能为空")
    private String domainPattern;

    private Boolean enabled = Boolean.TRUE;

    public void setRuleType(String ruleType) {
        this.ruleType = ruleType;
    }

    public void setDomainPattern(String domainPattern) {
        this.domainPattern = domainPattern;
    }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String ruleType() {
        return ruleType;
    }

    public String domainPattern() {
        return domainPattern;
    }

    public Boolean enabled() {
        return enabled;
    }
}
