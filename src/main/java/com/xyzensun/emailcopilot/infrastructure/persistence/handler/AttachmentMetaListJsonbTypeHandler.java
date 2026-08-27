package com.xyzensun.emailcopilot.infrastructure.persistence.handler;

import com.xyzensun.emailcopilot.domain.AttachmentMeta;
import tools.jackson.core.type.TypeReference;

import java.util.List;

/**
 * {@code pending_action_content.attachment_meta} 与 {@code draft.attachment_meta} 的 handler。
 *
 * <p><b>这两个列在第一阶段恒为空数组</b>：系统只存附件元数据不存字节，
 * 转发原附件无内容可发，前端也没有附件上传入口。本 handler 存在只为让列能正常读写，
 * 不表示发信支持附件。
 */
public class AttachmentMetaListJsonbTypeHandler extends JsonbTypeHandler<List<AttachmentMeta>> {

    private static final TypeReference<List<AttachmentMeta>> ATTACHMENT_META_LIST =
            new TypeReference<>() {
            };

    @Override
    protected List<AttachmentMeta> deserialize(String json) {
        return json == null ? List.of() : JSON.readValue(json, ATTACHMENT_META_LIST);
    }
}
