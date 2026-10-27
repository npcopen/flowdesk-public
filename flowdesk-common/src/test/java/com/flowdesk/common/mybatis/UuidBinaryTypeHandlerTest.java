package com.flowdesk.common.mybatis;

import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 UUID 与数据库 BINARY(16) 之间的映射，保留 null 并拒绝非法长度字节。
 */
class UuidBinaryTypeHandlerTest {
    /**
     * 验证 UUID 二进制类型处理器保持字节顺序与 null 语义，并拒绝长度异常的数据库值。
     */
    @Test
    void preservesUuidBytesNullsAndRejectsMalformedBinaryValues() throws Exception {
        var handler = new UuidBinaryTypeHandler();
        UUID id = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:uuid-handler")) {
            try (var insert = connection.prepareStatement("SELECT CAST(? AS BINARY(16)) AS id")) {
                handler.setParameter(insert, 1, id, JdbcType.BINARY);
                try (var row = insert.executeQuery()) {
                    row.next();
                    assertThat(row.getBytes(1)).containsExactly(
                            (byte) 0x00, (byte) 0x11, (byte) 0x22, (byte) 0x33,
                            (byte) 0x44, (byte) 0x55, (byte) 0x66, (byte) 0x77,
                            (byte) 0x88, (byte) 0x99, (byte) 0xaa, (byte) 0xbb,
                            (byte) 0xcc, (byte) 0xdd, (byte) 0xee, (byte) 0xff);
                    assertThat(handler.getResult(row, 1)).isEqualTo(id);
                    assertThat(handler.getResult(row, "id")).isEqualTo(id);
                }
                handler.setParameter(insert, 1, null, JdbcType.BINARY);
                try (var row = insert.executeQuery()) {
                    row.next();
                    assertThat(handler.getResult(row, 1)).isNull();
                }
            }
            try (var query = connection.createStatement();
                 var row = query.executeQuery("SELECT X'0102' AS id")) {
                row.next();
                assertThatThrownBy(() -> handler.getNullableResult(row, 1))
                        .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("16 bytes");
            }
        }
    }
}
