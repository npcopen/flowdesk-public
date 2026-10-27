package com.flowdesk.alert.mapper;
import java.util.UUID;
import java.nio.ByteBuffer;
/** JDBC 基础设施与测试夹具复用的二进制 UUID 转换。 */
public final class AlertPersistenceSupport {
    /**
     * 禁止直接实例化该无状态工具类；调用方通过其静态方法或嵌套契约模型使用能力。
     */
    private AlertPersistenceSupport() {}
    /**
     * 将 UUID 转为数据库 BINARY(16) 字节，空标识保持为 null，统一 Mapper 参数编码。
     */
    public static byte[] bytes(UUID value) { return value==null?null:ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array(); }
    /**
     * 把数据库 BINARY(16) 标识还原为 UUID，空数据库值保持为 null。
     */
    public static UUID uuid(byte[] value) { if(value==null)return null;var b=ByteBuffer.wrap(value);return new UUID(b.getLong(),b.getLong()); }
}
