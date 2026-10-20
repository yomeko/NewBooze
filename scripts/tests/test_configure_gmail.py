"""Offline tests: no SMTP connection or email leaves the test process."""

import importlib.util
import os
from pathlib import Path
import smtplib
import stat
import subprocess
import sys
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location("configure_gmail", Path(__file__).resolve().parents[1] / "configure-gmail.py")
gmail = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = gmail
SPEC.loader.exec_module(gmail)

EMAIL = "smtp-test@example.invalid"
GMAIL = "smtp.test@gmail.com"
PASSWORD = "abcdefghijklmnop"


class FakeSMTP:
    calls = []
    authentication_failure = False

    def __init__(self, host, port, timeout):
        self.calls.append(("connect", host, port, timeout))

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def ehlo(self):
        self.calls.append(("ehlo",))

    def starttls(self, *, context):
        self.calls.append(("starttls", context.check_hostname, context.verify_mode))

    def login(self, email, password):
        self.calls.append(("login",))
        if self.authentication_failure:
            raise smtplib.SMTPAuthenticationError(535, (email + password).encode())


class ConfigureGmailTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repository = Path(self.temporary.name).resolve()
        self.run_git("init", "-q")
        (self.repository / ".gitignore").write_text("application-local.properties\nsecond-local.properties\n", encoding="utf-8")
        self.config = self.repository / "application-local.properties"
        FakeSMTP.calls = []
        FakeSMTP.authentication_failure = False

    def run_git(self, *args):
        return subprocess.run(["git", "-C", str(self.repository), *args], check=True, capture_output=True)

    def configure(self, **kwargs):
        return gmail.configure(GMAIL, PASSWORD, [self.config], smtp_factory=FakeSMTP, **kwargs)

    def test_authentication_failure_changes_no_files_and_redacts_credentials(self):
        original = b"spring.datasource.password=preserve-test-value\n"
        self.config.write_bytes(original)
        FakeSMTP.authentication_failure = True
        with self.assertRaises(gmail.ConfigurationError) as caught:
            self.configure()
        self.assertEqual(self.config.read_bytes(), original)
        self.assertNotIn(GMAIL, str(caught.exception))
        self.assertNotIn(PASSWORD, str(caught.exception))
        self.assertEqual(list(self.repository.glob(".smtp-config-*")), [])

    def test_retains_database_settings_and_replaces_duplicate_smtp(self):
        self.config.write_text(
            "# 日本語の説明\nspring.datasource.url=jdbc:mysql://localhost/test\n"
            "spring.datasource.password=keep-me\nspring.mail.host=old\n"
            "spring.mail.host=duplicate\nspring.mail.password=old\n"
            "spring.mail.properties.mail.smtp.ssl.trust=*\n"
            "spring.mail.\\\n  port=1025\napp.mail.from=old\n"
            "app.base-url=https://example.test/日本語\n", encoding="utf-8")
        self.assertEqual(self.configure(), 1)
        output = self.config.read_text(encoding="utf-8")
        self.assertIn("# 日本語の説明\n", output)
        self.assertIn("spring.datasource.password=keep-me\n", output)
        self.assertEqual(output.count("spring.mail.host="), 1)
        self.assertNotIn("duplicate", output)
        self.assertNotIn("ssl.trust", output)
        self.assertIn("spring.mail.properties.mail.smtp.starttls.required=true\n", output)
        self.assertIn("spring.mail.properties.mail.smtp.ssl.checkserveridentity=true\n", output)
        properties = {key: value for _, key, value in gmail._property_blocks(output) if key}
        self.assertEqual(properties["app.base-url"], "https://example.test/日本語")
        self.assertEqual(properties["app.mail.from"], GMAIL)
        self.assertEqual(properties["spring.mail.username"], GMAIL)
        self.assertEqual(FakeSMTP.calls[0], ("connect", "smtp.gmail.com", 587, 10))
        self.assertEqual(FakeSMTP.calls[1], ("ehlo",))
        self.assertEqual(FakeSMTP.calls[2][0:2], ("starttls", True))
        self.assertEqual(FakeSMTP.calls[3:], [("ehlo",), ("login",)])

    def test_nonignored_or_tracked_file_refused_before_authentication(self):
        other = self.repository / "visible.properties"
        with self.assertRaises(gmail.ConfigurationError):
            gmail.configure(GMAIL, PASSWORD, [other], smtp_factory=FakeSMTP)
        self.assertFalse(other.exists())
        self.config.write_text("keep=true\n", encoding="utf-8")
        self.run_git("add", "-f", "application-local.properties")
        with self.assertRaises(gmail.ConfigurationError):
            self.configure()
        self.assertEqual(self.config.read_text(), "keep=true\n")
        self.assertEqual(FakeSMTP.calls, [])

    def test_multiple_files_preflight_before_authentication_or_write(self):
        self.config.write_text("keep=true\n", encoding="utf-8")
        other = self.repository / "not-ignored.properties"
        with self.assertRaises(gmail.ConfigurationError):
            gmail.configure(GMAIL, PASSWORD, [self.config, other], smtp_factory=FakeSMTP)
        self.assertEqual(self.config.read_text(), "keep=true\n")
        self.assertEqual(FakeSMTP.calls, [])

    def test_atomic_replacement_and_private_permissions_for_both_files(self):
        self.config.write_text("keep=true\n", encoding="utf-8")
        self.config.chmod(0o644)
        previous_inode = self.config.stat().st_ino
        other = self.repository / "second-local.properties"
        self.assertEqual(gmail.configure(GMAIL, "abcd efgh ijkl mnop", [self.config, other], smtp_factory=FakeSMTP), 2)
        self.assertNotEqual(self.config.stat().st_ino, previous_inode)
        for config in (self.config, other):
            self.assertEqual(stat.S_IMODE(config.stat().st_mode), 0o600)
            self.assertIn("spring.mail.password=" + PASSWORD, config.read_text())
        self.assertEqual(list(self.repository.glob(".smtp-config-*")), [])

    def test_invalid_inputs_never_authenticate(self):
        for email, password in ((EMAIL, PASSWORD), (GMAIL + "\nInjected", PASSWORD),
                                (GMAIL, "short"), (GMAIL, "abcdefghijklmno1"),
                                (GMAIL, "あ" * 16), (GMAIL, "abcdefghijklmnop\t")):
            with self.subTest(email=email):
                with self.assertRaises(gmail.ConfigurationError) as caught:
                    gmail.configure(email, password, [self.config], smtp_factory=FakeSMTP)
                self.assertNotIn(password, str(caught.exception))
        self.assertFalse(self.config.exists())
        self.assertEqual(FakeSMTP.calls, [])

    def test_symlink_and_missing_parent_refused(self):
        destination = self.repository / "destination"
        destination.write_text("keep=true\n", encoding="utf-8")
        self.config.symlink_to(destination)
        with self.assertRaises(gmail.ConfigurationError):
            self.configure()
        self.assertEqual(destination.read_text(), "keep=true\n")
        with self.assertRaises(gmail.ConfigurationError):
            gmail.configure(GMAIL, PASSWORD, [self.repository / "missing" / "application-local.properties"], smtp_factory=FakeSMTP)
        self.assertEqual(FakeSMTP.calls, [])

    def test_check_only_authenticates_without_changing_file(self):
        self.config.write_text("keep=true\n", encoding="utf-8")
        self.assertEqual(self.configure(check_only=True), 1)
        self.assertEqual(self.config.read_text(), "keep=true\n")
        self.assertEqual(FakeSMTP.calls[-1], ("login",))

    def test_unsafe_existing_base_url_is_replaced_with_local_default(self):
        for invalid in ("http://public.example", "https://user@example.test", "https://example.test/?token=x"):
            self.config.write_text("app.base-url=" + invalid + "\n", encoding="utf-8")
            self.configure()
            properties = {key: value for _, key, value in gmail._property_blocks(self.config.read_text()) if key}
            self.assertEqual(properties["app.base-url"], "http://localhost:8080")


if __name__ == "__main__":
    unittest.main()
