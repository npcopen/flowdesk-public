package com.flowdesk.alert.webhook;

/**
 * 定义认证过滤器与控制器之间共享的请求属性名，只在当前 Servlet 请求内传递已验签快照。
 *
 * <p>该属性由服务器认证代码写入，不从请求头或 JSON 获取。控制器经由过滤器的 verified 方法读取，
 * 属性缺失即视为认证链被绕过；它既不是跨请求缓存，也不代表业务事件已提交或租户上下文已建立。
 */
final class WebhookRequestAttributes {
    static final String VERIFIED = WebhookRequestAttributes.class.getName() + ".verified";

    /**
     * 禁止直接实例化该无状态工具类；调用方通过其静态方法或嵌套契约模型使用能力。
     */
    private WebhookRequestAttributes() {}
}
