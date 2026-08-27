package com.xyzensun.emailcopilot.infrastructure.mail;

import org.apache.james.jdkim.DKIMVerifier;
import org.apache.james.jdkim.api.Result;
import org.apache.james.jdkim.api.SignatureRecord;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/**
 * 使用 Apache jDKIM 和真实 DNS 验证原始 RFC 5322 字节。
 *
 * <p>无签名、签名失败、DNS 失败及 From 域不对齐都返回未认证，而不拒绝邮件入库。
 * 只接受签名 {@code d=} 与规范化 From 域精确匹配；不在本阶段扩展组织域 relaxed alignment。
 */
@Component
public class DkimMessageVerifier {

    public DkimVerificationResult verify(InputStream rawMessage, String fromDomain) {
        if (rawMessage == null || fromDomain == null || fromDomain.isBlank()) {
            return DkimVerificationResult.failed();
        }
        DKIMVerifier verifier = new DKIMVerifier();
        try {
            List<SignatureRecord> signatures = verifier.verify(rawMessage);
            if (signatures == null || !verifier.hasAnyValidSignature()) {
                return DkimVerificationResult.failed();
            }
            String normalizedFromDomain = fromDomain.toLowerCase(Locale.ROOT);
            return verifier.getResults().stream()
                    .filter(Result::isSuccess)
                    .map(Result::getRecord)
                    .filter(java.util.Objects::nonNull)
                    .map(SignatureRecord::getDToken)
                    .filter(java.util.Objects::nonNull)
                    .map(Object::toString)
                    .map(domain -> domain.toLowerCase(Locale.ROOT))
                    .filter(normalizedFromDomain::equals)
                    .findFirst()
                    .map(DkimVerificationResult::passed)
                    .orElseGet(DkimVerificationResult::failed);
        } catch (Exception ex) {
            // DKIM/DNS 失败是邮件认证结果，不是接入失败；异常原文可能含 DNS/邮件内容，绝不回显或记录。
            return DkimVerificationResult.failed();
        }
    }
}
