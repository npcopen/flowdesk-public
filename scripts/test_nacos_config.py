"""Offline tests for Nacos 3.x wire format and safe publication behavior."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
from urllib import error, parse

SPEC = importlib.util.spec_from_file_location("nacos_config", Path(__file__).with_name("nacos-config.py"))
nacos = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(nacos)


class Response:
    """模拟 urllib 响应与上下文管理协议，使客户端测试无需真实 Nacos 网络访问。"""
    status = 200

    def __init__(self, payload):
        """将测试载荷编码为响应字节，并保存用于 HTTP 分支判断的状态码。"""
        self.payload = payload

    def read(self):
        """返回预先编码的响应正文，模拟 urllib 的字节读取接口。"""
        return json.dumps(self.payload).encode()

    def __enter__(self):
        """返回模拟响应自身，支持客户端 with 语句读取。"""
        return self

    def __exit__(self, *args):
        """结束模拟响应作用域，不吞掉测试过程中发生的异常。"""
        return False


class ClientContractTests(unittest.TestCase):
    """验证 Nacos 客户端请求字段、CAS 发布、响应校验及凭据保护契约。"""
    def setUp(self):
        """为每个用例创建独立客户端及模拟网络入口，避免请求状态相互污染。"""
        self.client = nacos.NacosClient("127.0.0.1:28848")
        self.client.opener = mock.Mock()

    def test_config_get_uses_v3_names_and_preserves_placeholders(self):
        """验证读取配置使用 Nacos v3 参数名称，并保持应用占位符不被展开。"""
        self.client.token = "test-token"
        content = "password: ${FLOWDESK_DB_PASSWORD}\n"
        self.client.opener.open.return_value = Response({"code": 0, "data": {"content": content}})
        self.assertEqual(content, self.client.get_config("flowdesk-test", "service.yaml"))
        req = self.client.opener.open.call_args.args[0]
        self.assertEqual("/nacos/v3/admin/cs/config", parse.urlsplit(req.full_url).path)
        self.assertEqual({"namespaceId": ["flowdesk-test"], "groupName": [nacos.GROUP],
                          "dataId": ["service.yaml"], "accessToken": ["test-token"]},
                         parse.parse_qs(parse.urlsplit(req.full_url).query))

    def test_publish_sends_type_yaml_and_cas_then_verifies_hash(self):
        """验证发布包含 YAML 类型及 CAS 条件，且随后读回校验内容摘要。"""
        self.client.opener.open.side_effect = [
            Response({"code": 0, "data": True}),
            Response({"code": 0, "data": {"content": "x: new\n"}}),
        ]
        self.client.put_config("flowdesk-dev", "service.yaml", "x: new\n", "x: old\n")
        req = self.client.opener.open.call_args_list[0].args[0]
        fields = parse.parse_qs(req.data.decode())
        self.assertEqual(["yaml"], fields["type"])
        self.assertEqual(["FLOWDESK_GROUP"], fields["groupName"])
        self.assertEqual(["flowdesk-dev"], fields["namespaceId"])
        self.assertEqual(["x: new\n"], fields["content"])
        self.assertIn("Casmd5", req.headers)

    def test_get_missing_resource_only_for_documented_error_code(self):
        """验证只有文档约定的缺失资源错误被转换为 None，其他异常保持失败。"""
        payload = io.BytesIO(json.dumps({"code": 20004, "message": "not found"}).encode())
        self.client.opener.open.side_effect = error.HTTPError("private-url", 404, "missing", {}, payload)
        self.assertIsNone(self.client.get_config("flowdesk-dev", "service.yaml"))

    def test_auth_errors_do_not_echo_password_token_or_response(self):
        """验证认证失败的诊断不会回显密码、令牌或响应正文。"""
        payload = io.BytesIO(json.dumps({"code": 403, "message": "private-secret-token"}).encode())
        self.client.opener.open.side_effect = error.HTTPError("private-url", 403, "secret", {}, payload)
        with self.assertRaises(nacos.ApiError) as caught:
            self.client.login("nacos", "private-secret-password")
        self.assertNotIn("private-secret", str(caught.exception))

    def test_namespace_creation_uses_actual_id(self):
        """验证命名空间创建提交真正的 namespaceId，并检查后续可读性。"""
        self.client.opener.open.side_effect = [
            Response({"code": 0, "data": True}),
            Response({"code": 0, "data": [{"namespace": "flowdesk-dev"}]}),
        ]
        self.client.create_namespace("flowdesk-dev", "dev")
        req = self.client.opener.open.call_args_list[0].args[0]
        self.assertEqual(["flowdesk-dev"], parse.parse_qs(req.data.decode())["namespaceId"])

    def test_redirect_never_forwards_credentials(self):
        """验证重定向被拒绝，认证凭据不会发送到新地址。"""
        with self.assertRaises(nacos.ConfigError):
            nacos.NoRedirect().redirect_request(None, None, 302, "", {}, "https://other-server/")


class PreflightTests(unittest.TestCase):
    """验证发布前预检、只读检查、并发变化及显式覆盖行为，确保冲突在写入前暴露。"""
    def test_conflicts_abort_before_any_namespace_config_or_account_write(self):
        """验证配置冲突会在任何命名空间、配置或账号写入前中止操作。"""
        client, provision = mock.Mock(), mock.Mock()
        client.namespace_exists.return_value = True
        client.get_config.side_effect = [None, "different"]
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(nacos.ConfigError):
            nacos.synchronize(client, "publish", "dev", {"a.yaml": "a", "b.yaml": "b"}, provision=provision)
        client.put_config.assert_not_called()
        client.create_namespace.assert_not_called()
        provision.apply.assert_not_called()

    def test_check_is_read_only_and_reports_missing(self):
        """验证检查模式只读，并能报告缺失配置。"""
        client = mock.Mock()
        client.namespace_exists.return_value = False
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertFalse(nacos.synchronize(client, "check", "test", {"a.yaml": "a"}))
        client.create_namespace.assert_not_called()
        client.put_config.assert_not_called()

    def test_identical_publish_performs_no_config_write(self):
        """验证远程内容相同时不重复写入配置。"""
        client = mock.Mock()
        client.namespace_exists.return_value = True
        client.get_config.return_value = "a"
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertTrue(nacos.synchronize(client, "publish", "dev", {"a.yaml": "a"}))
        client.put_config.assert_not_called()

    def test_new_config_race_aborts_without_overwrite(self):
        """验证新配置在预检后被其他进程创建时中止，避免覆盖并发结果。"""
        client = mock.Mock()
        client.namespace_exists.return_value = True
        client.get_config.side_effect = [None, "someone else published"]
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(nacos.ConfigError):
            nacos.synchronize(client, "publish", "dev", {"a.yaml": "a"})
        client.put_config.assert_not_called()

    def test_explicit_overwrite_uses_preflight_content_for_cas(self):
        """验证显式覆盖仍使用预检正文作为 CAS 条件，防止盲目覆盖变化。"""
        client = mock.Mock()
        client.namespace_exists.return_value = True
        client.get_config.return_value = "old"
        with contextlib.redirect_stdout(io.StringIO()):
            nacos.synchronize(client, "publish", "prod", {"a.yaml": "new"}, overwrite=True)
        client.put_config.assert_called_once_with("flowdesk-prod", "a.yaml", "new", "old")

    def test_plan_reads_seven_files_without_network_or_env(self):
        """验证计划模式只读七份本地配置，不访问网络或加载凭据环境。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "dev").mkdir()
            for name in nacos.EXPECTED_FILES:
                (root / "dev" / name).write_text("x: ${KEEP_UNEXPANDED}\n", encoding="utf-8")
            with mock.patch.object(nacos, "NacosClient") as client, contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, nacos.main(["plan", "--environment", "dev", "--config-root", directory]))
            client.assert_not_called()


class IdentityTests(unittest.TestCase):
    """验证应用账号独立性、命名空间权限范围及最小权限补齐行为。"""
    def settings(self):
        """构造独立应用用户名及密码的测试配置，供账号预检场景复用。"""
        return {"FLOWDESK_NACOS_USERNAME": "flowdesk-dev", "FLOWDESK_NACOS_PASSWORD": "independent-app-password",
                "FLOWDESK_NACOS_ADMIN_PASSWORD": "independent-admin-password"}

    def test_namespace_permission_scope_is_config_read_naming_read_write(self):
        """验证命名空间权限仅包含配置只读与服务发现读写。"""
        account = nacos.ApplicationAccount(mock.Mock(), "dev", self.settings(), "nacos")
        self.assertEqual({("flowdesk-dev:FLOWDESK_GROUP:config/*", "r"),
                          ("flowdesk-dev:FLOWDESK_GROUP:naming/*", "rw")}, account.permissions)

    def test_rejects_admin_identity_or_reused_secret(self):
        """验证应用账号不能复用管理员身份或管理密码。"""
        for patch in ({"FLOWDESK_NACOS_USERNAME": "nacos"},
                      {"FLOWDESK_NACOS_PASSWORD": "independent-admin-password"}):
            with self.subTest(patch=patch), self.assertRaises(nacos.ConfigError):
                nacos.ApplicationAccount(mock.Mock(), "dev", self.settings() | patch, "nacos")

    def test_existing_extra_role_aborts_preflight(self):
        """验证已有额外角色时预检中止，不隐式接受扩大权限。"""
        client = mock.Mock()
        client.call.side_effect = [{"pageItems": [{"username": "flowdesk-dev"}]},
                                   {"pageItems": [{"username": "flowdesk-dev", "role": "ROLE_ADMIN"}]}]
        account = nacos.ApplicationAccount(client, "dev", self.settings(), "nacos")
        with self.assertRaises(nacos.ConfigError):
            account.preflight()
        self.assertTrue(all(x.args[0] == "GET" for x in client.call.call_args_list))

    def test_account_writes_only_missing_privileges(self):
        """验证授权流程只写入缺少的权限，并保留已经符合约定的授权。"""
        client = mock.Mock()
        account = nacos.ApplicationAccount(client, "dev", self.settings(), "nacos")
        account.user_exists = True
        account.has_role = True
        account.current_permissions = set(account.permissions)
        with mock.patch.object(account, "preflight"), contextlib.redirect_stdout(io.StringIO()):
            account.apply()
        client.call.assert_not_called()


if __name__ == "__main__":
    unittest.main()
