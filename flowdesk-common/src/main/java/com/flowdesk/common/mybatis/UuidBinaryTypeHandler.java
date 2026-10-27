package com.flowdesk.common.mybatis;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.nio.ByteBuffer;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * 为 MyBatis 统一映射 UUID 与 BINARY(16)，按网络字节顺序编码并校验存储长度。
 */
@MappedTypes(UUID.class)
@MappedJdbcTypes(value = {JdbcType.BINARY, JdbcType.VARBINARY}, includeNullJdbcType = true)
public final class UuidBinaryTypeHandler extends BaseTypeHandler<UUID> {
    /**
     * 将 UUID 高低位编码为 16 字节并写入 JDBC 参数，保持与 BINARY(16) 存储格式一致。
     */
    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, UUID value, JdbcType jdbcType)
            throws SQLException {
        statement.setBytes(index, ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array());
    }

    /**
     * 读取 JDBC 二进制 UUID；数据库 NULL 保持为空，长度不等于 16 的值视为非法存储数据。
     */
    @Override
    public UUID getNullableResult(ResultSet result, String column) throws SQLException {
        return decode(result.getBytes(column));
    }

    /**
     * 读取 JDBC 二进制 UUID；数据库 NULL 保持为空，长度不等于 16 的值视为非法存储数据。
     */
    @Override
    public UUID getNullableResult(ResultSet result, int column) throws SQLException {
        return decode(result.getBytes(column));
    }

    /**
     * 读取 JDBC 二进制 UUID；数据库 NULL 保持为空，长度不等于 16 的值视为非法存储数据。
     */
    @Override
    public UUID getNullableResult(CallableStatement statement, int column) throws SQLException {
        return decode(statement.getBytes(column));
    }

    /**
     * 按大端顺序将 16 字节还原为 UUID；空值返回 null，错误长度抛出 SQLException。
     */
    private static UUID decode(byte[] bytes) throws SQLException {
        if (bytes == null) return null;
        if (bytes.length != 16) throw new SQLException(
                "UUID BINARY column must contain exactly 16 bytes, received " + bytes.length);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
