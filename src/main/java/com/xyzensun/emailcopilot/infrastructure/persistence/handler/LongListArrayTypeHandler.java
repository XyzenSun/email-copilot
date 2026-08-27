package com.xyzensun.emailcopilot.infrastructure.persistence.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * PostgreSQL 原生 {@code bigint[]} 列 ↔ {@code List<Long>}。
 *
 * <p>用在 {@code message.tags} 与 {@code pending_action.target_message_ids}。
 * 这两处都是<b>只读 id 集合</b>，按已定取舍用原生数组列 + GIN 索引，不建关系表
 * （{@code DATABASE.md} §6.2：关系表只增加 join 而无查询收益）。
 *
 * <p><b>数组不自动去重排序，须由应用在写入前处理。</b>
 * {@code pending_action.target_message_ids} 尤其要注意——{@code canonical_payload_hash}
 * 的算法本就要求目标 id 排序，两件事顺带一起做，否则"语义相同"的提案会算出不同的指纹，
 * 兜底幂等键失效。
 */
public class LongListArrayTypeHandler extends BaseTypeHandler<List<Long>> {

    private static final String PG_TYPE_BIGINT = "bigint";

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<Long> parameter,
                                    JdbcType jdbcType) throws SQLException {
        Array array = ps.getConnection().createArrayOf(PG_TYPE_BIGINT, parameter.toArray());
        ps.setArray(i, array);
    }

    @Override
    public List<Long> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toList(rs.getArray(columnName));
    }

    @Override
    public List<Long> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toList(rs.getArray(columnIndex));
    }

    @Override
    public List<Long> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toList(cs.getArray(columnIndex));
    }

    /**
     * 两个数组列都是 {@code not null default '{}'}，理论上读不到 null；
     * 仍然容错返回空列表，让调用方不必判空。
     */
    private static List<Long> toList(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Long[] values = (Long[]) array.getArray();
        return Arrays.asList(values);
    }
}
