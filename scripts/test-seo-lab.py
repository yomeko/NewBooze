#!/usr/bin/env python3
"""Meaningful safety and extraction regression tests; no network needed."""

import importlib.util
from pathlib import Path
import unittest
from unittest.mock import Mock
from urllib.request import Request


spec = importlib.util.spec_from_file_location("seo_lab", Path(__file__).with_name("seo-lab.py"))
lab = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lab)


class SeoLabTests(unittest.TestCase):
    def test_external_urls_credentials_and_ambiguous_paths_rejected(self):
        for value in ["https://example.com/", "file:///tmp/a", "http://127.0.0.1.example.com/", "http://user:secret@127.0.0.1/", "http://127.0.0.1\\@example.com/"]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                lab.loopback_url(value)
        for value in ["http://localhost:8082/", "http://127.0.0.1:8082", "http://[::1]:8082/"]:
            lab.loopback_url(value)
        for value in ["//example.com/a", "https://example.com/a", "/x#fragment", "/\\example.com", "/a\nb"]:
            with self.subTest(path=value), self.assertRaises(ValueError):
                lab.request_path(value)
        self.assertEqual("/search?keyword=%E7%8D%BA%E7%A5%AD", lab.request_path("/search?keyword=%E7%8D%BA%E7%A5%AD"))

    def test_external_redirect_refused_before_following(self):
        handler = lab.LoopbackRedirectHandler()
        with self.assertRaises(ValueError):
            handler.redirect_request(Request("http://127.0.0.1:8082/"), Mock(), 302, "Found", {}, "https://example.com/")

    def test_parser_excludes_navigation_and_hidden_content_but_keeps_nested_heading(self):
        source = '''<html lang="ja"><head><title>診断 &amp; 案内</title>
          <meta name="description" content="日本酒の案内"><meta name="viewport" content="width=device-width">
          <link rel="canonical" href="http://localhost:8082/diagnosis"></head>
          <body><header>HEADER</header><main><h1>日本酒<span>の診断</span><span hidden>見出し装飾</span></h1>
          <nav>NAV<a href="/ignored">隠す</a></nav><p>本文</p><span hidden>HIDDEN</span>
          <span aria-hidden="true">DECORATION</span><h2>質問</h2>
          <a href="/guide#buy">購入ガイド</a><a href="https://example.com/">外部</a>
          <img src="a.png" alt=""><img src="b.png"><script>SECRET</script>
          <script type="application/ld+json">{"@type":"WebPage"}</script>
          <script type="application/ld+json">invalid</script></main><footer>FOOTER</footer></body></html>'''
        parser = lab.PageParser("http://127.0.0.1:8082/diagnosis")
        parser.feed(source)
        result = parser.result()
        self.assertEqual("診断 & 案内", result["title"])
        self.assertEqual(["日本酒の診断"], result["h1"])
        self.assertEqual(["質問"], result["h2"])
        self.assertEqual(1, result["json_ld_json_valid_count"])
        self.assertEqual([{"src": "b.png"}], result["images_missing_alt"])
        self.assertEqual(["/guide#buy"], [link["href"] for link in result["internal_main_links"]])
        for excluded in ["HEADER", "NAV", "HIDDEN", "DECORATION", "SECRET", "FOOTER", "WebPage"]:
            self.assertNotIn(excluded, result["main_text"])
        self.assertIn("本文", result["main_text"])
        checks = lab.technical_checks({"status": 200, "analysis": result})
        self.assertEqual("recommendation", next(item for item in checks if item["id"] == "canonical")["status"])

    def test_malformed_or_credential_canonical_never_crashes_audit(self):
        parser = lab.PageParser("http://127.0.0.1:8082/")
        parser.feed("<html><head><title>test</title></head><body><main><h1>test</h1></main></body></html>")
        analysis = parser.result()
        for value in ["https://[", "https://user:secret@example.com/", "javascript:alert(1)", "https://localhost./", "https://127.0.0.1/", "https://example.com:bad/", "/relative"]:
            with self.subTest(value=value):
                analysis["canonical"] = [value]
                checks = lab.technical_checks({"status": 200, "analysis": analysis})
                self.assertEqual("recommendation", next(item for item in checks if item["id"] == "canonical")["status"])
        self.assertTrue(lab.public_canonical_url("https://example.com/page"))

    def test_duplicates_are_observations_and_variant_wrong_product_is_not_generated(self):
        pages = [{"path": "/sake/1", "status": 200, "analysis": {"title": "同じ", "description": "", "main_text_sha256": "abc", "h1": ["別銘柄"]}},
                 {"path": "/search?keyword=x", "status": 200, "analysis": {"title": "同じ", "description": "", "main_text_sha256": "def"}}]
        duplicates = lab.duplicate_observations(pages)
        self.assertEqual(["/sake/1", "/search?keyword=x"], duplicates["title"][0]["paths"])
        self.assertEqual([], duplicates["description"])
        self.assertEqual("not_generated", lab.virtual_variants(pages)[0]["status"])


if __name__ == "__main__":
    unittest.main()
