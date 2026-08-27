package com.xyzensun.emailcopilot.infrastructure.persistence;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * {@code created_at} / {@code updated_at} 的自动填充。
 *
 * <p><b>为什么应用层填而不只靠数据库默认值</b>：DB 的 {@code default now()} 只在 INSERT
 * 时生效，UPDATE 不会自动刷新 {@code updated_at}。靠每处 update 手写一行
 * {@code setUpdatedAt(...)} 迟早会漏，漏掉的表现是"改了配置但时间没变"这类脏数据，
 * 而排查时又很难想到是某一处漏写。
 *
 * <p>两层并存不冲突：应用层填了就用应用层的值，没填才落到 DB 默认——
 * 后者让 migration 内的 INSERT 与测试里的裸 SQL 也能满足 not null。
 *
 * <p>{@code strictInsertFill} / {@code strictUpdateFill} 在实体没有对应字段时静默跳过，
 * 这正是需要的行为：本项目的时间列<b>刻意不统一</b>（有的表只有 created_at，
 * 有的只有 updated_at，{@code processing_progress} 两个都没有），
 * 那是"派生值不落库、无信息量的列不建"的结果，不要为了整齐给所有表补齐。
 *
 * <p>用 {@link OffsetDateTime} 对应 PostgreSQL 的 {@code timestamptz}——
 * PostgreSQL JDBC 驱动对这一组映射的支持是明确且文档化的。
 */
@Component
public class TimestampAutoFillHandler implements MetaObjectHandler {

    private static final String FIELD_CREATED_AT = "createdAt";
    private static final String FIELD_UPDATED_AT = "updatedAt";

    @Override
    public void insertFill(MetaObject metaObject) {
        OffsetDateTime now = OffsetDateTime.now();
        strictInsertFill(metaObject, FIELD_CREATED_AT, OffsetDateTime.class, now);
        strictInsertFill(metaObject, FIELD_UPDATED_AT, OffsetDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, FIELD_UPDATED_AT, OffsetDateTime.class, OffsetDateTime.now());
    }
}
