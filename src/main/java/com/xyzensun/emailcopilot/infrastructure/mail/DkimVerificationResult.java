package com.xyzensun.emailcopilot.infrastructure.mail;

/** DKIM 验证与 From 对齐后的安全结果；失败不阻塞入库。 */
public record DkimVerificationResult(boolean passed, String authenticatedDomain) {

    public static DkimVerificationResult failed() {
        return new DkimVerificationResult(false, null);
    }

    public static DkimVerificationResult passed(String authenticatedDomain) {
        return new DkimVerificationResult(true, authenticatedDomain);
    }
}
