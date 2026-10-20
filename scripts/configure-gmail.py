#!/usr/bin/env python3
"""Authenticate Gmail SMTP and save only ignored, private local settings."""

import argparse
from dataclasses import dataclass
import getpass
import os
from pathlib import Path
import re
import smtplib
import socket
import ssl
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit
import warnings


class ConfigurationError(Exception):
    """A safe-to-display error that never contains supplied credentials."""


@dataclass
class _Target:
    path: Path
    original: bytes | None
    replacement: bytes


def _credentials(email, password):
    if not isinstance(email, str) or not re.fullmatch(
        r"[A-Za-z0-9][A-Za-z0-9.+_-]{0,63}@(gmail|googlemail)\.com", email.strip(), re.I
    ):
        raise ConfigurationError("GmailまたはGooglemailのメールアドレスを指定してください。")
    if not isinstance(password, str):
        raise ConfigurationError("16文字のGoogleアプリパスワードを入力してください。")
    password = password.replace(" ", "")
    if not re.fullmatch(r"[A-Za-z]{16}", password):
        raise ConfigurationError("Googleアプリパスワードは空白を除いて半角英字16文字です。")
    return email.strip().lower(), password


def _unescape(value):
    def replace(match):
        code = match.group(1)
        if code.startswith("u"):
            return chr(int(code[1:], 16))
        return {"t": "\t", "r": "\r", "n": "\n", "f": "\f"}.get(code, code)
    return re.sub(r"\\(u[0-9A-Fa-f]{4}|.)", replace, value)


def _property_blocks(text):
    """Keep physical lines intact while recognizing Java property continuations."""
    lines = text.splitlines(keepends=True)
    index = 0
    while index < len(lines):
        physical = [lines[index]]
        logical = lines[index].rstrip("\r\n")
        index += 1
        while len(logical) - len(logical.rstrip("\\")) & 1 and index < len(lines):
            physical.append(lines[index])
            logical = logical[:-1] + lines[index].lstrip(" \t\f").rstrip("\r\n")
            index += 1
        stripped = logical.lstrip(" \t\f")
        key = value = None
        if stripped and stripped[0] not in "#!":
            match = re.match(r"((?:\\.|[^=:\s])+)\s*(?:[=:]\s*)?(.*)$", stripped)
            if match:
                key, value = _unescape(match[1]), _unescape(match[2])
        yield "".join(physical), key, value


def _valid_base_url(value):
    try:
        url = urlsplit(value)
        local = url.hostname in ("localhost", "127.0.0.1", "::1")
        return bool(
            url.hostname and url.username is None and url.password is None
            and not url.query and not url.fragment
            and (url.scheme == "https" or (url.scheme == "http" and local))
            and url.port != 0 and not any(character.isspace() for character in value)
        )
    except (ValueError, TypeError):
        return False


def _escape(value):
    """ASCII output works with both Java Properties and UTF-8 readers."""
    escaped = []
    for character in value:
        if character in "\\=:#! ":
            escaped.append("\\" + character)
        elif character in "\t\n\r\f":
            escaped.append({"\t": "\\t", "\n": "\\n", "\r": "\\r", "\f": "\\f"}[character])
        elif not 0x20 <= ord(character) <= 0x7E:
            for offset in range(0, len(character.encode("utf-16-be")), 2):
                unit = character.encode("utf-16-be")[offset:offset + 2]
                escaped.append("\\u" + unit.hex())
        else:
            escaped.append(character)
    return "".join(escaped)


def _replacement(original, email, password):
    try:
        text = original.decode("utf-8") if original is not None else ""
    except UnicodeError:
        raise ConfigurationError("設定ファイルはUTF-8で保存してください。") from None
    retained = []
    base_url = "http://localhost:8080"
    for block, key, value in _property_blocks(text):
        if key == "app.base-url":
            if _valid_base_url(value):
                base_url = value
        elif key == "app.mail.from" or (key and key.startswith("spring.mail.")):
            continue
        else:
            retained.append(block)
    settings = {
        "spring.mail.host": "smtp.gmail.com",
        "spring.mail.port": "587",
        "spring.mail.username": email,
        "spring.mail.password": password,
        "spring.mail.properties.mail.smtp.auth": "true",
        "spring.mail.properties.mail.smtp.starttls.enable": "true",
        "spring.mail.properties.mail.smtp.starttls.required": "true",
        "spring.mail.properties.mail.smtp.ssl.enable": "false",
        "spring.mail.properties.mail.smtp.ssl.checkserveridentity": "true",
        "spring.mail.properties.mail.smtp.connectiontimeout": "5000",
        "spring.mail.properties.mail.smtp.timeout": "5000",
        "spring.mail.properties.mail.smtp.writetimeout": "5000",
        "app.mail.from": email,
        "app.base-url": base_url,
    }
    prefix = "".join(retained).rstrip("\r\n")
    if prefix:
        prefix += "\n\n"
    return (prefix + "# Gmail SMTP (local settings; do not commit)\n" + "".join(
        key + "=" + _escape(value) + "\n" for key, value in settings.items()
    )).encode("utf-8")


def _git_ignored(path):
    options = {"capture_output": True, "text": True, "timeout": 10}
    try:
        root = subprocess.run(
            ["git", "-C", str(path.parent), "rev-parse", "--show-toplevel"], **options
        )
        if root.returncode:
            raise ConfigurationError("設定の保存先はGit管理されたプロジェクト内に指定してください。")
        repository = Path(root.stdout.strip())
        relative = str(path.relative_to(repository))
        tracked = subprocess.run(
            ["git", "-C", str(repository), "ls-files", "--error-unmatch", "--", relative], **options
        )
        ignored = subprocess.run(
            ["git", "-C", str(repository), "check-ignore", "-q", "--", relative], **options
        )
        if tracked.returncode != 1 or ignored.returncode != 0:
            raise ConfigurationError("設定の保存先はGit管理対象外かつignore済みである必要があります。")
    except ConfigurationError:
        raise
    except (OSError, ValueError, subprocess.SubprocessError):
        raise ConfigurationError("設定のGit管理対象外チェックに失敗しました。") from None


def _prepare(paths, email, password):
    targets = []
    seen = set()
    for supplied in paths:
        path = Path(os.path.abspath(Path(supplied).expanduser()))
        if path in seen:
            continue
        seen.add(path)
        if any(part.is_symlink() for part in (path, *path.parents)):
            raise ConfigurationError("シンボリックリンクには設定を保存できません。")
        if not path.parent.is_dir() or (path.exists() and not path.is_file()):
            raise ConfigurationError("設定ファイルの保存先フォルダーを確認してください。")
        _git_ignored(path)
        try:
            original = path.read_bytes() if path.exists() else None
        except OSError:
            raise ConfigurationError("設定ファイルを読み込めませんでした。") from None
        targets.append(_Target(path, original, _replacement(original, email, password)))
    if not targets:
        raise ConfigurationError("設定ファイルの保存先を指定してください。")
    return targets


def _authenticate(email, password, smtp_factory):
    try:
        with smtp_factory("smtp.gmail.com", 587, timeout=10) as smtp:
            smtp.ehlo()
            smtp.starttls(context=ssl.create_default_context())
            smtp.ehlo()
            smtp.login(email, password)
    except smtplib.SMTPAuthenticationError:
        raise ConfigurationError("SMTP認証に失敗しました。Googleのアプリパスワードを確認してください。") from None
    except ssl.SSLError:
        raise ConfigurationError("SMTPのTLS接続を確認できませんでした。") from None
    except (TimeoutError, socket.timeout):
        raise ConfigurationError("SMTP接続がタイムアウトしました。") from None
    except (smtplib.SMTPException, OSError):
        raise ConfigurationError("SMTPサーバーに接続できませんでした。ネットワークとGoogle設定を確認してください。") from None


def _write(targets):
    temporary = []
    replaced = 0
    try:
        # Prepare every file before replacing any current configuration.
        for target in targets:
            descriptor, name = tempfile.mkstemp(prefix=".smtp-config-", dir=target.path.parent)
            temporary.append((target, Path(name)))
            with os.fdopen(descriptor, "wb") as output:
                os.fchmod(output.fileno(), 0o600)
                output.write(target.replacement)
                output.flush()
                os.fsync(output.fileno())
        for target, _ in temporary:
            if any(part.is_symlink() for part in (target.path, *target.path.parents)):
                raise ConfigurationError("保存先が変更されたため設定を保存しませんでした。")
            current = target.path.read_bytes() if target.path.exists() else None
            if current != target.original:
                raise ConfigurationError("設定ファイルが変更されたため、もう一度実行してください。")
        for target, name in temporary:
            os.replace(name, target.path)
            replaced += 1
    except ConfigurationError:
        raise
    except OSError:
        if replaced:
            raise ConfigurationError("一部の設定は保存済みですが、残りの保存に失敗しました。保存先の権限を確認して再実行してください。") from None
        raise ConfigurationError("設定ファイルを保存できませんでした。保存先の権限を確認してください。") from None
    finally:
        for _, name in temporary:
            name.unlink(missing_ok=True)


def configure(email, password, paths=None, *, check_only=False, smtp_factory=None):
    """Authenticate before writing; return a count, never credentials or SMTP replies.

    No email is sent. smtp_factory is injectable for offline unit tests.
    """
    email, password = _credentials(email, password)
    if paths is None:
        paths = [Path(__file__).resolve().parent.parent / "application-local.properties"]
    targets = _prepare(paths, email, password)
    _authenticate(email, password, smtp_factory or smtplib.SMTP)
    if not check_only:
        _write(targets)
    return len(targets)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Gmail SMTPを認証し、Git管理外のローカル設定へ保存します。")
    parser.add_argument("--email", required=True, help="送信元のGmailアドレス")
    parser.add_argument("--config", action="append", type=Path, help="保存先（複数指定可）")
    parser.add_argument("--check-only", action="store_true", help="SMTP認証だけを確認し、保存しない")
    args = parser.parse_args(argv)
    try:
        # Refuse getpass's echoing fallback rather than exposing a pasted password.
        with warnings.catch_warnings():
            warnings.simplefilter("error", getpass.GetPassWarning)
            password = getpass.getpass("Googleアプリパスワード（入力は表示されません）: ")
        count = configure(args.email, password, args.config, check_only=args.check_only)
    except ConfigurationError as error:
        print(str(error), file=sys.stderr)
        return 1
    except (EOFError, KeyboardInterrupt):
        print("設定を中止しました。", file=sys.stderr)
        return 1
    except getpass.GetPassWarning:
        print("非表示入力を利用できるターミナルで実行してください。", file=sys.stderr)
        return 1
    if args.check_only:
        print("SMTP認証を確認しました。メールは送信していません。")
    else:
        print(f"SMTP認証を確認し、{count}件のローカル設定を保存しました。メールは送信していません。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
