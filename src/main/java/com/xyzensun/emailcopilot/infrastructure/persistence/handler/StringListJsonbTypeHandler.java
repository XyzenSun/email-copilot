package com.xyzensun.emailcopilot.infrastructure.persistence.handler;

import tools.jackson.core.type.TypeReference;

import java.util.List;

/**
 * {@code mail_account.imap_folders}（同步文件夹列表）的 handler。
 *
 * <p>该列 nullable，未配置 IMAP 时为 null，此时返回空列表。
 */
public class StringListJsonbTypeHandler extends JsonbTypeHandler<List<String>> {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    @Override
    protected List<String> deserialize(String json) {
        return json == null ? List.of() : JSON.readValue(json, STRING_LIST);
    }
}
