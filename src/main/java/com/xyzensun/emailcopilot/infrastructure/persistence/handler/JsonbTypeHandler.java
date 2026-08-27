package com.xyzensun.emailcopilot.infrastructure.persistence.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * PostgreSQL {@code jsonb} 列的 TypeHandler 基类。
 *
 * <p><b>为什么不用 MyBatis-Plus 内置的 JacksonTypeHandler</b>：它用 {@code ps.setString()} 写值，
 * 而 PostgreSQL JDBC 驱动默认 {@code stringtype=varchar} 会显式声明参数类型为 varchar，
 * PostgreSQL 又没有 varchar → jsonb 的隐式赋值转换，于是报
 * {@code column "recipients" is of type jsonb but expression is of type character varying}。
 *
 * <p>本类改用 {@code setObject(i, json, Types.OTHER)}：驱动以 <i>unspecified</i> 类型发送该参数，
 * 由 PostgreSQL 按目标列推断为 jsonb。
 *
 * <p>另外两条路都不如它：
 * <ul>
 *   <li>在 JDBC URL 上加 {@code stringtype=unspecified} —— 全局生效，会让<b>所有</b>
 *       {@code setString} 都交给 PostgreSQL 推断，以后某个 {@code where text_col = ?}
 *       传入纯数字字符串时会出现意外的类型推断</li>
 *   <li>用 {@code org.postgresql.util.PGobject} 显式设类型 —— 需要把 PostgreSQL 驱动
 *       从 {@code runtime} 提到编译期 scope，等于允许业务代码直接调驱动私有 API。
 *       {@code Types.OTHER} 是标准 JDBC，效果相同</li>
 * </ul>
 *
 * <p>泛型擦除导致反序列化拿不到目标类型，因此每个具体类型一个子类实现
 * {@link #deserialize(String)}。
 *
 * <p><b>用到本类子孙的实体必须标 {@code @TableName(autoResultMap = true)}</b>，
 * 否则查询时 handler 不生效——写入正常、读出为 null，属于典型的"一半能用"故障。
 *
 * @param <T> 该列对应的 Java 类型
 */
public abstract class JsonbTypeHandler<T> extends BaseTypeHandler<T> {

    /**
     * 用 Jackson 3（{@code tools.jackson}，Spring Boot 4 的默认）而非 classpath 上同时存在的
     * Jackson 2。自建静态实例而不注入 Spring 容器里那个 ObjectMapper：TypeHandler 由 MyBatis
     * 实例化而非 Spring，注入需要静态 setter + 启动时序配合，而本处只序列化三种简单结构，
     * 用不到容器里那份的任何定制。
     */
    protected static final ObjectMapper JSON = JsonMapper.builder().build();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, T parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, JSON.writeValueAsString(parameter), Types.OTHER);
    }

    @Override
    public T getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return deserialize(rs.getString(columnName));
    }

    @Override
    public T getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return deserialize(rs.getString(columnIndex));
    }

    @Override
    public T getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return deserialize(cs.getString(columnIndex));
    }

    /**
     * 把列里的 JSON 文本还原为目标类型。
     *
     * <p>实现必须处理 {@code json == null} —— nullable 的 jsonb 列（如
     * {@code mail_account.imap_folders}）读出来就是 null。返回空集合而非 null，
     * 让调用方不必到处判空。
     */
    protected abstract T deserialize(String json);
}
