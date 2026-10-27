#!/usr/bin/env python3
"""Verify backend three-layer contracts with Python standard library only.

Run: python scripts/verify-backend-layers.py
Controllers depend on business Service interfaces. Mapper XML must resolve to
real typed interfaces and cannot contain raw SQL substitution. Production SQL
uses MyBatis; Spring JDBC execution APIs are prohibited. Tests may use JDBC
for fixtures and assertions. Maven tests verify behavioral semantics.
Business controllers use controller packages and persistence uses Mapper interfaces
without an intervening Repository layer.
"""
from __future__ import annotations
import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

MODULES = tuple('flowdesk-' + n + '-service' for n in ('iam', 'ticket', 'workflow', 'alert', 'notification'))
UTILITY_SERVICES = {'CursorTokenService', 'TicketCursorTokenService', 'WorkflowCursorTokenService', 'AlertCursorTokenService', 'NotificationCursorTokenService'}
PACKAGE = re.compile(r'\bpackage\s+([\w.]+)\s*;')
IMPORT = re.compile(r'\bimport\s+(?:static\s+)?([\w.*]+)\s*;')
DECLARATION = re.compile(r'\b(?:public\s+)?(?:abstract\s+|final\s+)?(class|interface|record|enum)\s+(\w+)')
FIELD = re.compile(r'\b(?:private|protected|public)\s+(?:final\s+)?([\w.]+)(?:<[^;=]+>)?\s+\w+\s*(?:;|=)')
METHOD = re.compile(r'(?:^|[;{}])\s*(?:(?:public|protected|private|default|static|abstract|final)\s+)*(?:<[^;{}]+>\s+)?[\w.\[\]<>?, ]+?\s+(\w+)\s*\(', re.MULTILINE)

@dataclass
class JavaSource:
    """保存单个 Java 文件的包、名称、类型、正文和导入信息，供分层规则解析类型依赖。"""
    path: Path
    package: str
    name: str
    kind: str
    text: str
    imports: list[str]

    @property
    def qualified_name(self) -> str:
        """拼接包名与类型名，作为跨文件依赖解析的唯一类名。"""
        return f'{self.package}.{self.name}'

def without_comments(text: str) -> str:
    # Keep literals intact so URL and SQL strings do not turn into comments.
    """删除 Java 行注释及块注释，同时保留字符串和文本块，避免 URL 或 SQL 字面量被误解析。"""
    pattern = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|//[^\n]*|/\*[\s\S]*?\*/')
    return pattern.sub(lambda m: ' ' if m.group().startswith(('//', '/*')) else m.group(), text)

def verify(root: Path) -> tuple[list[str], dict[str, int]]:
    """扫描生产 Java 与 Mapper XML，检查控制器服务依赖、分层位置及类型化 SQL 边界，返回全部错误和统计。"""
    errors: list[str] = []
    sources: dict[str, JavaSource] = {}
    counts = {'modules': 0, 'controllers': 0, 'business_service_dependencies': 0, 'mapper_files': 0, 'mapped_statements': 0}

    def fail(path: Path, message: str) -> None:
        """记录相对仓库路径及规则错误，供一次扫描结束后集中展示。"""
        errors.append(f'{path.relative_to(root).as_posix()}: {message}')

    # Include common recovery workers and gateway in this rule, not only HTTP services.
    forbidden = re.compile(r'\b(?:JdbcTemplate|NamedParameterJdbcTemplate|JdbcClient|JdbcOperations|NamedParameterJdbcOperations|SimpleJdbcInsert|SimpleJdbcCall)\b')
    direct_sql = re.compile(r'\b(?:prepareStatement|prepareCall|createStatement)\s*\(')
    health_probe = 'flowdesk-common/src/main/java/com/flowdesk/common/web/ServiceHealthController.java'
    for module_dir in sorted(root.glob('flowdesk-*')):
        for path in sorted((module_dir / 'src/main/java').rglob('*.java')):
            text = without_comments(path.read_text(encoding='utf-8-sig'))
            if forbidden.search(text) or re.search(r'\b(?:extends|implements)\s+JdbcDaoSupport\b', text):
                fail(path, 'Production source uses Spring JDBC SQL execution; use a typed MyBatis Mapper')
            if path.relative_to(root).as_posix() != health_probe and direct_sql.search(text):
                fail(path, 'Production business SQL bypasses MyBatis through direct JDBC execution')

    for module in MODULES:
        directory = root / module / 'src/main/java'
        if not directory.is_dir():
            errors.append(f'{module}: missing production Java source directory')
            continue
        counts['modules'] += 1
        for path in sorted(directory.rglob('*.java')):
            text = without_comments(path.read_text(encoding='utf-8-sig'))
            package = PACKAGE.search(text)
            declaration = next((m for m in DECLARATION.finditer(text) if m.group(2) == path.stem), None)
            if not package or not declaration:
                continue
            source = JavaSource(path, package.group(1), declaration.group(2), declaration.group(1), text, IMPORT.findall(text))
            sources[source.qualified_name] = source

    def resolve(owner: JavaSource, name: str) -> JavaSource | None:
        """按完整名称、显式或通配导入及同包顺序解析源码类型，无法解析时返回 None。"""
        simple = name.rsplit('.', 1)[-1]
        if name in sources:
            return sources[name]
        for imported in owner.imports:
            candidate = imported[:-1] + simple if imported.endswith('.*') else imported
            if candidate.endswith('.' + simple) and candidate in sources:
                return sources[candidate]
        return sources.get(owner.package + '.' + simple)

    for source in sources.values():
        package_parts = set(source.package.split('.'))
        if source.name.endswith('Repository') or 'repository' in package_parts:
            fail(source.path, 'Repository intermediary is prohibited; move coordination into Service implementations and access Mapper directly')
        if 'api' in package_parts:
            fail(source.path, 'Use controller for HTTP entry points and dto for HTTP contracts instead of an api package')
        if source.name.endswith('ServiceImpl') and 'impl' not in package_parts:
            fail(source.path, 'Service implementation must live in an impl package')
        if source.kind == 'interface' and source.name.endswith('Service'):
            for imported in source.imports:
                if '.impl.' in imported or imported.rsplit('.', 1)[-1].endswith('Impl'):
                    fail(source.path, f'Service interface imports implementation {imported}')
        if not (source.name.endswith('Controller') or re.search(r'@(?:RestController|Controller)\b', source.text)):
            continue
        counts['controllers'] += 1
        expected_package = '.'.join(source.package.split('.')[:3]) + '.controller'
        if source.package != expected_package:
            fail(source.path, f'Business Controller must live in {expected_package}')
        for imported in source.imports:
            if '.impl.' in imported or imported.rsplit('.', 1)[-1].endswith('Impl'):
                fail(source.path, f'Controller imports implementation {imported}; depend on a business Service interface')
            if {'repository', 'mapper'} & set(imported.split('.')) or imported.startswith(('org.springframework.jdbc.', 'java.sql.', 'org.apache.ibatis.')):
                fail(source.path, f'Controller imports persistence type {imported}; depend on a business Service')
        for name in FIELD.findall(source.text):
            simple = name.rsplit('.', 1)[-1]
            if simple.endswith(('Repository', 'Mapper')) or simple in {'JdbcTemplate', 'JdbcClient', 'NamedParameterJdbcTemplate', 'DataSource', 'SqlSession'}:
                fail(source.path, f'Controller has persistence field {name}')
            if not simple.endswith('Service') or simple in UTILITY_SERVICES:
                continue
            counts['business_service_dependencies'] += 1
            service = resolve(source, name)
            if service is None:
                fail(source.path, f'Cannot resolve injected business Service {name}')
            elif service.kind != 'interface':
                fail(source.path, f'Injected business Service {service.qualified_name} must be an interface')

    for module in MODULES:
        for path in sorted((root / module / 'src/main/resources').rglob('*.xml')):
            try:
                document = ET.fromstring(path.read_text(encoding='utf-8-sig'))
            except ET.ParseError as error:
                fail(path, f'Invalid XML: {error}')
                continue
            if document.tag != 'mapper':
                continue
            counts['mapper_files'] += 1
            sql = ' '.join(document.itertext()) + ' '.join(value for e in document.iter() for value in e.attrib.values())
            if chr(36) + '{' in sql:
                fail(path, 'Mapper contains raw SQL substitution; use bound values and fixed SQL choices')
            namespace = document.attrib.get('namespace', '')
            mapper = sources.get(namespace)
            if mapper is None:
                fail(path, f'Mapper namespace {namespace!r} does not resolve to production Java')
                continue
            if mapper.kind != 'interface':
                fail(path, f'Mapper namespace {namespace} must resolve to an interface')
            methods = set(METHOD.findall(mapper.text))
            statements: set[tuple[str, str]] = set()
            for element in document:
                if element.tag not in {'select', 'insert', 'update', 'delete'}:
                    continue
                counts['mapped_statements'] += 1
                name = element.attrib.get('id', '')
                if name not in methods:
                    fail(path, f'Statement {name!r} has no method in {namespace}')
                key = name, element.attrib.get('databaseId', '')
                if key in statements:
                    fail(path, f'Duplicate mapped statement {key!r}')
                statements.add(key)
    return errors, counts

def main() -> int:
    """解析仓库位置并执行分层检查，打印统计及错误，以退出码表示规则是否全部通过。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1],
                        help='Project root (defaults to the directory above scripts)')
    args = parser.parse_args()
    errors, counts = verify(args.root.resolve())
    if errors:
        print(f'Backend layer verification FAILED ({len(errors)} violation(s)):', file=sys.stderr)
        for error in errors:
            print(f'  - {error}', file=sys.stderr)
        return 1
    print('Backend layer verification passed: ' + ', '.join(f"{value} {key.replace('_', ' ')}" for key, value in counts.items()))
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
