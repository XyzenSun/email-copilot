/**
 * 应用服务层：用例编排与事务边界。
 *
 * <p>{@code @Transactional} 只标在这一层的方法上。
 *
 * <p>四个核心服务（{@code ARCHITECTURE.md} §1）：
 * <ul>
 *   <li>{@code IngestApplicationService}——邮件入库，IMAP 汇入同一用例</li>
 *   <li>{@code ProcessingApplicationService}——判定流水线推进</li>
 *   <li>{@code ConversationApplicationService}——对话轮次与上下文重建</li>
 *   <li>{@code ActionApplicationService}——审批的原子消费与执行</li>
 * </ul>
 *
 * <p><b>远程 I/O 绝不包在事务里。</b>SMTP、IMAP、AI API 都不参与数据库事务
 * （{@code ARCHITECTURE.md} §6.2）。模式固定为：短事务领取或消费 → 提交 →
 * 事务外远程调用 → 新事务写结果。把远程调用包进事务会在网络卡住时长时间持有行锁，
 * 而超时时间由外部服务决定，本地无从控制。
 *
 * <p><b>前端 REST 与 AI 工具复用同一个应用服务</b>（{@code ARCHITECTURE.md} §9）。
 * 为 AI 工具另写一份检索实现会导致两边结果不一致，而这种不一致排查极难：
 * 用户说“我记得有 3 封”，AI 说“没找到”，两边代码看着都对。
 *
 * <p>发信执行组件放这一层，且<b>从不注册为 AI 工具</b>——对话 AI 只能创建
 * {@code PendingAction}，真实副作用要经用户审批（{@code ARCHITECTURE.md} §2.2）。
 */
package com.xyzensun.emailcopilot.application;
