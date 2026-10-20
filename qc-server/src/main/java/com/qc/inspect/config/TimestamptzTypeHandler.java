package com.qc.inspect.config;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * timestamptz ↔ LocalDateTime。
 *
 * <p>为什么必须有这个类：本项目所有业务表的时间列都是 {@code timestamptz}（带时区），
 * 实体字段是 {@code LocalDateTime}。PostgreSQL JDBC 的
 * {@code getObject(col, LocalDateTime.class)} 只认不带时区的 {@code timestamp}，
 * 遇到 timestamptz 会直接抛
 * 「Cannot convert the column of type TIMESTAMPTZ to requested type java.time.LocalDateTime」。
 *
 * <p>这个错误在单测和编译阶段都发现不了，只有真正连库查一次才会炸——
 * 表现是「服务能起来、一调接口就 500」。也正因如此，集成测试用的是真 PostgreSQL。
 *
 * <p>换算口径：读出来先转 {@link OffsetDateTime}，再按 JVM 默认时区还原成「墙上时间」，
 * 与写入时 {@code LocalDateTime.atZone(系统时区)} 对称，因此同一个值读回来不变。
 * 若以后要支持跨时区部署，这里应换成显式时区配置而不是系统默认值。
 */
@MappedTypes(LocalDateTime.class)
public class TimestamptzTypeHandler extends BaseTypeHandler<LocalDateTime> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, LocalDateTime parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, parameter.atZone(ZoneId.systemDefault()).toOffsetDateTime());
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toLocal(rs.getObject(columnName, OffsetDateTime.class));
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toLocal(rs.getObject(columnIndex, OffsetDateTime.class));
    }

    @Override
    public LocalDateTime getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toLocal(cs.getObject(columnIndex, OffsetDateTime.class));
    }

    private LocalDateTime toLocal(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }
}
