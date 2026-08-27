package com.xyzensun.emailcopilot.infrastructure.security;

/**
 * 为系统设置提供主密钥状态的只读来源。
 *
 * <p>主密钥来自进程外部配置，系统设置只能展示状态，不能通过此接口写入或替换它。
 * 实现只有在启动自检成功后才提供状态；若已有密文无法由当前密钥认证，应用会在
 * Spring 容器 refresh 阶段失败，不会把“不匹配”状态带入正常运行状态。
 */
public interface MasterKeyStatusSource {

    /**
     * 返回启动自检确认过的主密钥状态。
     *
     * @return 不含任何密钥或凭据数据的不可变状态快照
     * @throws IllegalStateException 在启动自检尚未完成时调用
     */
    MasterKeyStatus getMasterKeyStatus();

    /** 系统设置 DTO 可直接读取，无需了解状态快照的内部组织。 */
    default boolean masterKeyPresent() {
        return getMasterKeyStatus().masterKeyPresent();
    }

    /** 系统设置 DTO 可直接读取；不匹配状态会先触发启动失败。 */
    default boolean masterKeyMatchesCiphertext() {
        return getMasterKeyStatus().masterKeyMatchesCiphertext();
    }
}
