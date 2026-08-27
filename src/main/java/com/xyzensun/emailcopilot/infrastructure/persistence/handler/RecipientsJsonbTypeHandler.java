package com.xyzensun.emailcopilot.infrastructure.persistence.handler;

import com.xyzensun.emailcopilot.domain.Recipients;
import org.apache.ibatis.type.MappedTypes;

/**
 * {@code message.recipients}、{@code pending_action_content.recipients}、
 * {@code draft.recipients} 三个 jsonb 列的 handler。
 */
@MappedTypes(Recipients.class)
public class RecipientsJsonbTypeHandler extends JsonbTypeHandler<Recipients> {

    @Override
    protected Recipients deserialize(String json) {
        return json == null ? Recipients.empty() : JSON.readValue(json, Recipients.class);
    }
}
