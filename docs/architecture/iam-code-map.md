# IAM 服务代码阅读地图

IAM 使用传统三层调用链：controller/*Controller → service/*Service 接口 → service/impl/*ServiceImpl → mapper/*Mapper → MySQL。

## 分层与接口

- controller 保存 HTTP 入口，dto 保存请求响应契约与参数校验。Controller 只注入业务 Service 接口，不直接访问 Mapper 或实现类。
- service 定义登录、账号管理、角色管理、授权关系和资格检查的业务接口。业务返回类型属于接口契约，不依赖实现类。
- service.impl 实现权限检查、业务前置条件、幂等竞争处理和事务编排。
- mapper 是 MyBatis 数据访问接口，SQL 与显式构造映射位于 src/main/resources/mapper/iam/*.xml。
- model 保存独立的不可变数据库投影；UUID 使用公共 UuidBinaryTypeHandler 保持既有 BINARY(16) 编码，时间字段保留 Instant 语义。
- 游标签名、密码散列及 JWT 签发等模块工具保持独立；数据库测试夹具的二进制转换工具仅存在于测试源码。

## 用例与数据访问

| 入口 | Service 接口 / 实现 | 数据访问 |
| --- | --- | --- |
| 登录与当前用户 | IamAuthService / IamAuthServiceImpl | CredentialMapper、UserMapper、AccessMapper、AuditMapper |
| 用户、候选人与最小身份 | UserManagementService / UserManagementServiceImpl | UserMapper，创建时协调凭证、授权及审计 Mapper |
| 角色维护 | RoleManagementService / RoleManagementServiceImpl | AccessMapper、AuditMapper |
| 权限目录与两类授权关系 | BindingService / BindingServiceImpl | AccessMapper、UserMapper、AuditMapper |
| 服务资格核验 | EligibilityService / EligibilityServiceImpl | EligibilityMapper |
| 登录失败计数 | CredentialFailureService / CredentialFailureServiceImpl | CredentialMapper，独立提交计数与临时锁定 |

服务令牌启动器通过 CredentialMapper 校验配置租户，JWT 签发与轮转行为保持不变。它是启动基础设施，不属于 HTTP 入口。

## 事务与一致性

- Service 实现继续使用原 IAM 数据源和事务管理器；账号、凭证、授权及审计在原来的同一业务事务内提交。
- IamAuthServiceImpl.login 保留登录事务。密码失败计数通过另一个 Spring Service 的 REQUIRES_NEW 方法提交，因此登录抛出失败响应不会回滚计数和锁定。
- 角色、用户和绑定的幂等键、唯一键竞争后的当前读、版本条件及审计锚点保持不变，禁止拆成彼此独立的 Mapper 事务。
- EligibilityMapper.findEligibilityFacts 仍通过单条 SQL 读取用户、角色和绑定事实，资格版本由各组件版本贡献相加。
- SQL 显式保留租户条件、排序列白名单、游标比较与稳定的第二排序键，不使用来自请求的原始 SQL 插值。
- MyBatis 配置为 localCacheScope=STATEMENT 且关闭二级缓存，避免同一事务中幂等竞争重读命中陈旧结果。

## 回归验证

mvn -pl flowdesk-iam-service -am test 验证应用启动、登录、角色权限、租户隔离、分页、并发创建与审计回滚。失败登录回归还验证计数独立提交、达到阈值锁定、锁过期重新计数及成功登录清零。
