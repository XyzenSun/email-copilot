package com.xyzensun.emailcopilot.domain;

/**
 * 附件元数据，用于 {@code pending_action_content.attachment_meta} 与
 * {@code draft.attachment_meta} 两个 JSONB 列。
 *
 * <p><b>第一阶段这两个列恒为空数组</b>（{@code DATABASE.md} §5.5）：系统只存附件元数据不存字节
 * （附件是攻击载荷最集中的位置），因此转发原附件无内容可发，前端也没有附件上传入口。
 * 保留列与本类型仅为占位，<b>不得据此认为发信支持附件</b>。
 *
 * <p>与 {@code attachment} 表不是同一个东西：那张表记的是收到的邮件带了哪些附件
 * （有独立主键、可被 DataPurge 删除），本类型记的是待发送内容快照里的附件清单。
 */
public record AttachmentMeta(String filename, String contentType, long sizeBytes) {
}
