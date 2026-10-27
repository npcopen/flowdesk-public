#!/usr/bin/env python3
"""Plan, check, or publish FlowDesk YAML through the Nacos 3.x Admin API.

Uses only Python's standard library. Credentials are read from the process
environment or dotenv; application placeholders are uploaded unchanged.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time
from urllib import error, parse, request

ROOT = Path(__file__).resolve().parent.parent
GROUP = "FLOWDESK_GROUP"
CONFIG_API = "/v3/admin/cs/config"
NAMESPACE_API = "/v3/admin/core/namespace"
EXPECTED_FILES = {
    "flowdesk-common.yaml", "flowdesk-gateway.yaml", "flowdesk-iam-service.yaml",
    "flowdesk-ticket-service.yaml", "flowdesk-workflow-service.yaml",
    "flowdesk-alert-service.yaml", "flowdesk-notification-service.yaml",
}


class ConfigError(Exception):
    """可安全展示的配置操作异常；消息仅描述诊断信息，不回显响应正文或凭据。"""


class ApiError(ConfigError):
    """记录 Nacos 请求的 HTTP 状态及业务码，让调用方判断缺失资源等情况而无需展示服务端消息。"""
    def __init__(self, method, path, status, code):
        """保存状态与错误码，并生成只包含方法、路径和状态的安全诊断消息。"""
        self.status, self.code = status, code
        super().__init__(f"Nacos {method} {path} failed (HTTP {status}, code {code}).")


class NoRedirect(request.HTTPRedirectHandler):
    """禁止跟随 HTTP 重定向，防止认证凭据被转发到其他地址。"""
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        """对任何重定向直接抛出配置异常，要求使用 Nacos 的直接服务地址。"""
        raise ConfigError("Nacos redirected the request; use its direct server address.")


def load_settings(env_file=None):
    """解析选定 dotenv 文件并校验赋值格式，再以进程环境覆盖同名配置；占位符不展开，错误只报告行号。"""
    settings = {}
    selected = Path(env_file) if env_file is not None else ROOT / ".env"
    if selected.is_file():
        for number, raw in enumerate(selected.read_text(encoding="utf-8-sig").splitlines(), 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if line.startswith("export "):
                line = line[7:].lstrip()
            key, sep, value = line.partition("=")
            key, value = key.strip(), value.strip()
            if not sep or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
                raise ConfigError(f"Invalid dotenv assignment at line {number}.")
            if value.startswith(("'", '"')):
                if len(value) < 2 or value[-1] != value[0]:
                    raise ConfigError(f"Unclosed dotenv quote at line {number}.")
                value = value[1:-1]
            else:
                value = re.split(r"\s+#", value, maxsplit=1)[0].rstrip()
            settings[key] = value
    elif env_file is not None:
        raise ConfigError("The requested dotenv file does not exist.")
    settings.update(os.environ)
    return settings


def server_url(address):
    """规范化单个 Nacos HTTP(S) 地址，拒绝内嵌凭据、查询参数及不支持路径，再补齐 /nacos 根路径。"""
    value = address.strip()
    if not value:
        raise ConfigError("Set FLOWDESK_NACOS_SERVER_ADDR or --server-addr.")
    if "://" not in value:
        value = "http://" + value
    try:
        parts = parse.urlsplit(value)
        _ = parts.port
    except ValueError:
        raise ConfigError("Invalid Nacos server address.") from None
    if (parts.scheme not in ("http", "https") or not parts.hostname
            or parts.username is not None or parts.password is not None
            or parts.query or parts.fragment
            or parts.path.rstrip("/") not in ("", "/nacos")):
        raise ConfigError("Use one HTTP(S) Nacos server address without credentials or query parameters.")
    return parse.urlunsplit((parts.scheme, parts.netloc, "/nacos", "", ""))


def digest(content):
    """以 UTF-8 编码配置正文并计算 SHA-256，用于计划输出和发布后的内容一致性检查。"""
    return hashlib.sha256(content.encode("utf-8")).hexdigest()


def sources(environment, config_root):
    """读取指定环境的七份 YAML，拒绝缺失、多余或空文件，统一换行但保持应用占位符原文。"""
    files = {p.name: p for p in (Path(config_root) / environment).glob("*.yaml") if p.is_file()}
    if set(files) != EXPECTED_FILES:
        missing, extra = sorted(EXPECTED_FILES - set(files)), sorted(set(files) - EXPECTED_FILES)
        raise ConfigError(f"Expected seven FlowDesk YAML files; missing={missing}, unexpected={extra}.")
    result = {}
    for name in sorted(files):
        # Universal newline handling normalizes CRLF, without expanding placeholders.
        content = files[name].read_text(encoding="utf-8-sig")
        if not content.strip():
            raise ConfigError(f"Empty configuration: {name}.")
        result[name] = content
    return result


class NacosClient:
    """封装 Nacos 管理 API 的认证、配置和命名空间操作，统一超时、响应检查及安全错误输出。"""
    def __init__(self, address, timeout=15.0):
        """规范化服务地址并保存超时，初始化无令牌状态及禁止重定向的 HTTP opener。"""
        self.base, self.timeout = server_url(address), timeout
        self.token = None
        self.opener = request.build_opener(NoRedirect())

    def call(self, method, path, params=None, headers=None):
        """按方法编码参数并附带令牌，执行请求后校验 HTTP 状态和业务码；网络或响应异常转换为不泄露正文的错误。"""
        fields = dict(params or {})
        if self.token:
            fields["accessToken"] = self.token
        encoded = parse.urlencode(fields).encode("utf-8")
        url, body = self.base + path, None
        if method == "GET":
            if encoded:
                url += "?" + encoded.decode("ascii")
        else:
            body = encoded
        req_headers = {"Accept": "application/json"}
        if body is not None:
            req_headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
        req_headers.update(headers or {})
        req = request.Request(url, data=body, headers=req_headers, method=method)
        try:
            with self.opener.open(req, timeout=self.timeout) as response:
                status, raw = response.status, response.read()
        except error.HTTPError as exc:
            status, raw = exc.code, exc.read()
        except (error.URLError, TimeoutError, OSError):
            raise ConfigError(f"Cannot reach Nacos for {method} {path}; check address and connectivity.") from None
        try:
            payload = json.loads(raw)
        except (ValueError, UnicodeDecodeError):
            raise ConfigError(f"Nacos {method} {path} returned non-JSON (HTTP {status}).") from None
        if not isinstance(payload, dict):
            raise ConfigError(f"Nacos {method} {path} returned an unexpected response.")
        code = payload.get("code")
        if not 200 <= status < 300 or code not in (None, 0, 200):
            # Never include server messages: they can echo credentials/content.
            raise ApiError(method, path, status, code if isinstance(code, int) else "unknown")
        return payload.get("data", payload)

    def login(self, username, password):
        """拒绝缺失或示例凭据，调用登录 API 并保存返回令牌；响应缺少令牌时立即失败。"""
        if not username or not password or password.startswith("change-"):
            raise ConfigError("Set real Nacos username/password environment variables.")
        value = self.call("POST", "/v3/auth/user/login", {"username": username, "password": password})
        if not isinstance(value, dict) or not isinstance(value.get("accessToken"), str):
            raise ConfigError("Nacos login did not return an access token.")
        self.token = value["accessToken"]

    def namespace_exists(self, namespace):
        """读取命名空间列表并按实际 namespace 标识匹配，响应结构不正确时拒绝继续。"""
        items = self.call("GET", NAMESPACE_API + "/list")
        if not isinstance(items, list):
            raise ConfigError("Unexpected Nacos namespace list response.")
        return any(item.get("namespace") == namespace for item in items if isinstance(item, dict))

    def create_namespace(self, namespace, environment):
        """使用明确的 namespaceId 创建环境命名空间，再读回确认创建结果。"""
        value = self.call("POST", NAMESPACE_API, {
            "namespaceId": namespace, "namespaceName": environment,
            "namespaceDesc": f"FlowDesk {environment} configuration and discovery",
        })
        if value is not True or not self.namespace_exists(namespace):
            raise ConfigError("Nacos namespace creation could not be verified.")

    def get_config(self, namespace, name):
        """按命名空间、固定分组和 Data ID 读取正文，仅文档约定的 404/20004 被视为不存在。"""
        try:
            value = self.call("GET", CONFIG_API, {
                "namespaceId": namespace, "groupName": GROUP, "dataId": name,
            })
        except ApiError as exc:
            if exc.code == 20004 and exc.status == 404:
                return None
            raise
        if not isinstance(value, dict) or not isinstance(value.get("content"), str):
            raise ConfigError(f"Unexpected Nacos configuration response for {name}.")
        return value["content"]

    def put_config(self, namespace, name, content, previous):
        """对已有内容附带 CAS MD5 发布 YAML，再有限重试读回并比较 SHA-256；冲突或不一致均报错。"""
        headers = {}
        if previous is not None:
            headers["casMd5"] = hashlib.md5(previous.encode("utf-8"), usedforsecurity=False).hexdigest()
        value = self.call("POST", CONFIG_API, {
            "namespaceId": namespace, "groupName": GROUP, "dataId": name,
            "content": content, "type": "yaml",
        }, headers=headers)
        if value is not True:
            raise ConfigError(f"Nacos refused publication of {name}; it may have changed concurrently.")
        for attempt in range(3):
            current = self.get_config(namespace, name)
            if current is not None and digest(current) == digest(content):
                return
            if attempt < 2:
                time.sleep(0.2)
        raise ConfigError(f"Readback verification failed for {name}; inspect the target environment.")


class ApplicationAccount:
    """创建独立且限定命名空间的应用身份，只授予配置读取和服务发现读写权限。"""

    def __init__(self, client, environment, settings, admin):
        """校验应用账号与管理账号分离且密码独立，建立环境专属角色及最小权限集合。"""
        self.client, self.environment = client, environment
        self.username = settings.get("FLOWDESK_NACOS_USERNAME", "")
        self.password = settings.get("FLOWDESK_NACOS_PASSWORD", "")
        self.role = f"flowdesk-{environment}-application"
        self.permissions = {
            (f"flowdesk-{environment}:{GROUP}:config/*", "r"),
            (f"flowdesk-{environment}:{GROUP}:naming/*", "rw"),
        }
        if (not re.fullmatch(r"[A-Za-z0-9_-]{3,64}", self.username)
                or self.username in ("nacos", admin) or len(self.password) < 16
                or self.password == settings.get("FLOWDESK_NACOS_ADMIN_PASSWORD")):
            raise ConfigError("Use a separate application username and independent password of at least 16 characters.")
        self.user_exists = False
        self.has_role = False
        self.current_permissions = set()

    def page_items(self, endpoint, params):
        """逐页读取账号、角色或权限，校验分页结构并限制最多一百页，避免异常响应导致无限循环。"""
        items, page = [], 1
        while True:
            value = self.client.call("GET", endpoint, {
                **params, "pageNo": page, "pageSize": 100, "search": "accurate",
            })
            if not isinstance(value, dict) or not isinstance(value.get("pageItems"), list):
                raise ConfigError("Unexpected Nacos identity/permission list response.")
            items.extend(value["pageItems"])
            pages = value.get("pagesAvailable", 1)
            if not isinstance(pages, int) or pages < 0:
                raise ConfigError("Unexpected Nacos pagination response.")
            if page >= pages:
                return items
            page += 1
            if page > 100:
                raise ConfigError("Nacos identity list exceeds safe pagination limit.")

    def preflight(self):
        """只读检查账号、专属角色和权限是否符合最小授权约定；已有账号同时验证凭据，不自动重置密码。"""
        users = self.page_items("/v3/auth/user/list", {"username": self.username})
        self.user_exists = any(x.get("username") == self.username for x in users)
        roles = self.page_items("/v3/auth/role/list", {"username": self.username})
        assigned = {x.get("role") for x in roles if x.get("username") == self.username}
        if assigned - {self.role}:
            raise ConfigError("Application user has unrelated roles; review its privileges manually.")
        self.has_role = self.role in assigned
        # Do not reuse a role shared by other users or carrying broader permissions.
        members = self.page_items("/v3/auth/role/list", {"role": self.role})
        if any(x.get("username") != self.username for x in members):
            raise ConfigError("Application role is assigned to another identity; use a dedicated account.")
        perms = self.page_items("/v3/auth/permission/list", {"role": self.role})
        self.current_permissions = {(x.get("resource"), x.get("action")) for x in perms}
        if self.current_permissions - self.permissions:
            raise ConfigError("Application role has unexpected permissions; review its privileges manually.")
        if self.user_exists:
            # Verify existing credentials; never silently reset another account.
            verifier = NacosClient(self.client.base, self.client.timeout)
            verifier.login(self.username, self.password)

    def apply(self):
        """仅创建缺少的账号、角色绑定及权限，随后重新预检确认结果，不扩展到约定之外的权限。"""
        if not self.user_exists:
            self.client.call("POST", "/v3/auth/user", {
                "username": self.username, "password": self.password,
            })
        if not self.has_role:
            self.client.call("POST", "/v3/auth/role", {
                "username": self.username, "role": self.role,
            })
        for resource, action in sorted(self.permissions - self.current_permissions):
            self.client.call("POST", "/v3/auth/permission", {
                "role": self.role, "resource": resource, "action": action,
            })
        self.preflight()
        if not self.user_exists or not self.has_role or self.current_permissions != self.permissions:
            raise ConfigError("Application account provisioning could not be verified.")
        print(f"verified application identity for flowdesk-{self.environment}: config=r, naming=rw, group={GROUP}")


def synchronize(client, mode, environment, configs, overwrite=False, provision=None):
    """先读取并报告配置差异，检查模式保持只读；发布前完成冲突预检，写入前再次检查并发变化并逐项验证回读。"""
    namespace = f"flowdesk-{environment}"
    exists = client.namespace_exists(namespace)
    before = {name: client.get_config(namespace, name) if exists else None for name in configs}
    conflicts = [name for name, text in configs.items() if before[name] is not None and before[name] != text]
    for name, content in configs.items():
        state = "missing" if before[name] is None else "identical" if before[name] == content else "different"
        print(f"{state:9} {namespace}/{GROUP}/{name} sha256={digest(content)}")
    if mode == "check":
        return exists and all(before[name] == text for name, text in configs.items())
    if conflicts and not overwrite:
        raise ConfigError("Existing configurations differ; review and explicitly use --overwrite: " + ", ".join(conflicts))
    # Complete every conflict preflight before any namespace/config/account mutation.
    if provision:
        provision.preflight()
    if not exists:
        client.create_namespace(namespace, environment)
    for name, content in configs.items():
        if before[name] == content:
            continue
        if client.get_config(namespace, name) != before[name]:
            raise ConfigError(f"Configuration changed after preflight: {name}; rerun check.")
        client.put_config(namespace, name, content, before[name])
        print(f"published {namespace}/{GROUP}/{name}; readback verified")
    if provision:
        provision.apply()
    return True


def main(argv=None):
    """解析计划、检查或发布参数，加载本地配置及可选账号授权流程，输出稳定退出码和安全诊断消息。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("plan", "check", "publish"))
    parser.add_argument("--environment", required=True, choices=("dev", "test", "prod"))
    parser.add_argument("--env-file", type=Path, help="Defaults to repository .env if present")
    parser.add_argument("--server-addr", help="Direct server address without credentials")
    parser.add_argument("--config-root", type=Path, default=ROOT / "infra" / "nacos")
    parser.add_argument("--overwrite", action="store_true", help="Permit replacing different remote content")
    parser.add_argument("--provision-user", action="store_true", help="Create namespace-scoped application account")
    args = parser.parse_args(argv)
    try:
        configs = sources(args.environment, args.config_root)
        if args.mode == "plan":
            for name, content in configs.items():
                print(f"flowdesk-{args.environment}/{GROUP}/{name} sha256={digest(content)}")
            print("Local plan only; no network requests or changes.")
            return 0
        if args.provision_user and args.mode != "publish":
            raise ConfigError("--provision-user is only valid with publish.")
        settings = load_settings(args.env_file)
        client = NacosClient(args.server_addr or settings.get("FLOWDESK_NACOS_SERVER_ADDR", ""))
        admin = settings.get("FLOWDESK_NACOS_ADMIN_USERNAME", "nacos")
        client.login(admin, settings.get("FLOWDESK_NACOS_ADMIN_PASSWORD", ""))
        provision = ApplicationAccount(client, args.environment, settings, admin) if args.provision_user else None
        ok = synchronize(client, args.mode, args.environment, configs, args.overwrite, provision)
        return 0 if ok else 1
    except (ConfigError, OSError, UnicodeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
