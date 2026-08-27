package com.xyzensun.emailcopilot.interfaces.settings.dto;

/**
 * 发件人规则部分更新请求。
 *
 * <p>用 setter 记录字段是否出现，避免把“未传”与“显式 null”混成同一个值：三个数据库列
 * 都不可清空，显式 null 必须返回字段级校验错误，而不能被当作省略后静默忽略。
 */
public final class SenderRuleUpdateRequest {

    private boolean ruleTypePresent;
    private String ruleType;
    private boolean domainPatternPresent;
    private String domainPattern;
    private boolean enabledPresent;
    private Boolean enabled;

    public void setRuleType(String ruleType) {
        this.ruleTypePresent = true;
        this.ruleType = ruleType;
    }

    public void setDomainPattern(String domainPattern) {
        this.domainPatternPresent = true;
        this.domainPattern = domainPattern;
    }

    public void setEnabled(Boolean enabled) {
        this.enabledPresent = true;
        this.enabled = enabled;
    }

    public boolean hasRuleType() {
        return ruleTypePresent;
    }

    public String ruleType() {
        return ruleType;
    }

    public boolean hasDomainPattern() {
        return domainPatternPresent;
    }

    public String domainPattern() {
        return domainPattern;
    }

    public boolean hasEnabled() {
        return enabledPresent;
    }

    public Boolean enabled() {
        return enabled;
    }
}
