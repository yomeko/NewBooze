#!/usr/bin/env python3
"""Read-only, loopback-only HTML observations for a local SEO experiment.

This deliberately does not measure, estimate, or score search-engine rankings.
It fetches no subresources, submits no forms, and executes no JavaScript.
"""

import argparse
from collections import defaultdict
from datetime import datetime, timezone
import hashlib
from html.parser import HTMLParser
import ipaddress
import json
from pathlib import Path
import re
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin, urlsplit, urlunsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


DEFAULT_PATHS = [
    "/", "/diagnosis", "/guide", "/search", "/sake/1", "/sake/7",
    "/about", "/contact", "/privacy", "/external-transmission",
    "/robots.txt", "/sitemap.xml",
]
MAX_RESPONSE_BYTES = 5 * 1024 * 1024
VOID_TAGS = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"}
EXCLUDED_TAGS = {"nav", "footer", "header", "script", "style", "template", "head"}
TEXT_BREAK_TAGS = {"address", "article", "aside", "blockquote", "br", "dd", "div", "dl", "dt", "fieldset", "figcaption", "figure", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "li", "main", "ol", "p", "section", "table", "td", "th", "tr", "ul"}


def normalized(value):
    return re.sub(r"\s+", " ", value).strip()


def loopback_url(value):
    """Validate before any request, including every redirect."""
    parsed = urlsplit(value)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError("HTTP(S)のローカルURLのみ指定できます。")
    if parsed.username is not None or parsed.password is not None:
        raise ValueError("認証情報を含むURLは指定できません。")
    try:
        local = parsed.hostname.lower() == "localhost" or ipaddress.ip_address(parsed.hostname).is_loopback
    except ValueError:
        local = parsed.hostname.lower() == "localhost"
    if not local:
        raise ValueError("外部通信を防ぐため、localhostまたはループバックIPのみ使用できます。")
    try:
        parsed.port
    except ValueError as error:
        raise ValueError("ポート番号が不正です。") from error
    if "\\" in value or any(ord(char) < 32 for char in value):
        raise ValueError("URLに制御文字やバックスラッシュは使用できません。")
    return parsed


def request_path(value):
    parsed = urlsplit(value)
    if not value.startswith("/") or value.startswith("//") or parsed.scheme or parsed.netloc or parsed.fragment:
        raise ValueError("--pathはフラグメントを含まない/から始まる相対パスで指定してください。")
    if "\\" in value or any(ord(char) < 32 for char in value):
        raise ValueError("パスに制御文字やバックスラッシュは使用できません。")
    return value


class LoopbackRedirectHandler(HTTPRedirectHandler):
    def __init__(self):
        super().__init__()
        self.events = []

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        event = {"status": code, "from_url": req.full_url, "followed": False}
        self.events.append(event)
        try:
            loopback_url(newurl)
        except ValueError as error:
            event["blocked_reason"] = str(error)
            raise
        event.update(to_url=newurl, followed=True)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class PageParser(HTMLParser):
    """Extract source HTML, not computed CSS visibility or a rendered DOM."""

    def __init__(self, page_url):
        super().__init__(convert_charrefs=True)
        self.page_url = page_url
        self.stack = []
        self.titles = []
        self.lang = ""
        self.viewport = []
        self.descriptions = []
        self.canonicals = []
        self.robots = []
        self.h1 = []
        self.h2 = []
        self.json_ld = []
        self.main_text = []
        self.body_text = []
        self.main_seen = False
        self.internal_main_links = []
        self.images_missing_alt = []
        self.image_count = 0

    def handle_starttag(self, tag, attributes):
        attrs = dict(attributes)
        parent_excluded = bool(self.stack and self.stack[-1]["excluded"])
        excluded = parent_excluded or tag in EXCLUDED_TAGS or "hidden" in attrs or (attrs.get("aria-hidden") or "").lower() == "true"
        frame = {"tag": tag, "attrs": attrs, "text": [], "excluded": excluded,
                 "head_title": tag == "title" and any(item["tag"] == "head" for item in self.stack),
                 "main_link": tag == "a" and not excluded and any(item["tag"] == "main" for item in self.stack)}
        if tag in TEXT_BREAK_TAGS:
            self.main_text.append(" ")
            self.body_text.append(" ")
        if tag == "html":
            self.lang = attrs.get("lang", "") or ""
        if tag == "main":
            self.main_seen = True
        if tag == "meta":
            name = (attrs.get("name") or "").lower()
            content = attrs.get("content") or ""
            if name == "viewport":
                self.viewport.append(content)
            elif name == "description":
                self.descriptions.append(content)
            elif name in {"robots", "googlebot"}:
                self.robots.append({"name": name, "content": content})
        if tag == "link" and "canonical" in (attrs.get("rel") or "").lower().split():
            self.canonicals.append(attrs.get("href") or "")
        if tag == "img":
            self.image_count += 1
            if "alt" not in attrs:
                self.images_missing_alt.append({"src": attrs.get("src") or ""})
        if tag not in VOID_TAGS:
            self.stack.append(frame)

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in VOID_TAGS:
            self.handle_endtag(tag)

    def handle_data(self, value):
        visible = bool(self.stack and not self.stack[-1]["excluded"])
        for frame in self.stack:
            if frame["head_title"] or (frame["tag"] == "script" and (frame["attrs"].get("type") or "").lower() == "application/ld+json"):
                frame["text"].append(value)
            elif visible and not frame["excluded"] and frame["tag"] in {"h1", "h2", "a"}:
                frame["text"].append(value)
        if visible:
            if any(frame["tag"] == "body" for frame in self.stack):
                self.body_text.append(value)
            if any(frame["tag"] == "main" for frame in self.stack):
                self.main_text.append(value)

    def handle_endtag(self, tag):
        matching = next((index for index in range(len(self.stack) - 1, -1, -1) if self.stack[index]["tag"] == tag), None)
        if matching is None:
            return
        for frame in reversed(self.stack[matching:]):
            self._finish(frame)
        del self.stack[matching:]
        if tag in TEXT_BREAK_TAGS:
            self.main_text.append(" ")
            self.body_text.append(" ")

    def _finish(self, frame):
        tag, attrs = frame["tag"], frame["attrs"]
        text = normalized("".join(frame["text"]))
        if frame["head_title"]:
            self.titles.append(text)
        if not frame["excluded"] and tag in {"h1", "h2"}:
            getattr(self, tag).append(text)
        if tag == "script" and (attrs.get("type") or "").lower() == "application/ld+json":
            try:
                decoded = json.loads("".join(frame["text"]))
                self.json_ld.append({"json_valid": True, "json_type": type(decoded).__name__})
            except (ValueError, TypeError) as error:
                self.json_ld.append({"json_valid": False, "error": str(error)})
        if frame["main_link"] and attrs.get("href"):
            href = attrs["href"]
            try:
                resolved = urljoin(self.page_url, href)
                link, current = urlsplit(resolved), urlsplit(self.page_url)
            except ValueError:
                return
            if link.scheme in {"http", "https"} and (link.scheme, link.netloc) == (current.scheme, current.netloc):
                self.internal_main_links.append({"href": href, "resolved_url": resolved, "text": text})

    def result(self):
        while self.stack:
            self._finish(self.stack.pop())
        text = normalized("".join(self.main_text if self.main_seen else self.body_text))
        return {
            "title": self.titles[0] if self.titles else "", "titles": self.titles,
            "lang": self.lang, "viewport": self.viewport,
            "description": self.descriptions[0] if self.descriptions else "", "descriptions": self.descriptions,
            "canonical": self.canonicals, "robots": self.robots,
            "json_ld": self.json_ld, "json_ld_json_valid_count": sum(item["json_valid"] for item in self.json_ld),
            "json_ld_note": "JSON構文の検証のみ。語彙・Googleの表示資格は検証していません。",
            "h1": self.h1, "h2": self.h2, "main_element_found": self.main_seen,
            "main_text": text, "main_text_characters": len(text),
            "main_text_sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
            "internal_main_links": self.internal_main_links,
            "image_count": self.image_count, "images_missing_alt": self.images_missing_alt,
            "images_alt_note": "alt属性の欠落のみ。装飾画像の空のaltは欠落として数えません。",
            "extraction_note": "HTMLソースから抽出。JavaScript未実行、CSSによる可視性は未判定。nav/header/footer/script/style/template/hidden/aria-hiddenは本文から除外。",
        }


def public_canonical_url(value):
    """Syntax/local-address observation only; DNS and production access untested."""
    try:
        parsed = urlsplit(value)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            return False
        if parsed.username is not None or parsed.password is not None or "\\" in value or any(char.isspace() for char in value):
            return False
        parsed.port
        host = parsed.hostname.rstrip(".").lower()
        if host == "localhost":
            return False
        try:
            return ipaddress.ip_address(host).is_global
        except ValueError:
            return "." in host
    except (ValueError, TypeError):
        return False


def technical_checks(page):
    checks = []

    def add(check_id, observed, recommendation=None):
        check = {"id": check_id, "status": "recommendation" if recommendation else "observed", "observed": observed}
        if recommendation:
            check["recommendation"] = recommendation
        checks.append(check)

    add("http_response", {"status": page.get("status"), "error": page.get("error")},
        None if page.get("status") == 200 else "意図した公開ページを取得できるか、アクセス設定やURLを確認します。404自体が順位の評価値を示すものではありません。")
    analysis = page.get("analysis")
    if not analysis:
        return checks
    add("title", analysis["titles"], None if len(analysis["titles"]) == 1 and analysis["title"] else "ページの内容を正確に伝えるtitleを1つ用意します。")
    add("language", analysis["lang"], None if analysis["lang"] else "利用者・支援技術向けに本文の言語を指定します。")
    add("viewport", analysis["viewport"], None if analysis["viewport"] else "スマートフォンでの表示を確認し、必要なviewportを指定します。")
    add("meta_description", analysis["descriptions"], None if len(analysis["descriptions"]) == 1 and analysis["description"] else "本文と一致する要約を検討します。検索結果の説明文候補であり、順位の直接要因として採点しません。")
    canonicals = analysis["canonical"]
    public_canonical = len(canonicals) == 1 and public_canonical_url(canonicals[0])
    add("canonical", canonicals, None if public_canonical else "公開URLの確定後、重複URLの整理方針を決めて必要なcanonicalを指定します。localhostの値では公開設定完了と判定しません。")
    add("robots_directives", {"meta": analysis["robots"], "x_robots_tag": page.get("x_robots_tag", "")})
    add("headings", {"h1": analysis["h1"], "h2": analysis["h2"]}, None if analysis["h1"] else "利用者がページの主題を理解できる主見出しを用意します。見出し数を順位の予測値に換算しません。")
    add("json_ld_syntax", analysis["json_ld"], "構造化データを採用する場合は本文との一致・対象の要件を別途確認します。必須項目や順位加点とは扱いません。" if any(not item["json_valid"] for item in analysis["json_ld"]) else None)
    add("image_alt_attributes", analysis["images_missing_alt"], "情報を伝える画像には内容に合った代替テキストを検討します。装飾画像は空のaltを使えます。" if analysis["images_missing_alt"] else None)
    return checks


def fetch_page(opener, base_url, path, output):
    requested = urljoin(base_url + "/", request_path(path))
    loopback_url(requested)
    started = time.monotonic()
    page = {"path": path, "requested_url": requested, "final_url": requested, "status": None}
    body = b""
    response = None
    redirect_handlers = [handler for handler in opener.handlers if isinstance(handler, LoopbackRedirectHandler)]
    for handler in redirect_handlers:
        handler.events = []
    try:
        request = Request(requested, headers={"User-Agent": "NewBooze-Local-SEO-Lab/1.0", "Accept": "text/html,application/xhtml+xml,text/plain,application/xml"}, method="GET")
        try:
            response = opener.open(request, timeout=10)
        except HTTPError as error:
            response = error
        with response:
            page.update(status=response.code, final_url=response.geturl(), content_type=response.headers.get("Content-Type", ""), x_robots_tag=response.headers.get("X-Robots-Tag", ""))
            body = response.read(MAX_RESPONSE_BYTES + 1)
            if len(body) > MAX_RESPONSE_BYTES:
                body = body[:MAX_RESPONSE_BYTES]
                page["error"] = "応答上限5MiBを超えたため切り詰めました。"
            charset = response.headers.get_content_charset() or "utf-8"
    except (URLError, OSError, ValueError) as error:
        page["error"] = str(error)
        charset = "utf-8"
    page.update(elapsed_ms=round((time.monotonic() - started) * 1000, 2), response_bytes=len(body), timing_scope="localhostの1回のHTTP取得。利用者の速度・Core Web Vitalsではありません。")
    page["redirect_chain"] = [event for handler in redirect_handlers for event in handler.events]
    page["initial_status"] = page["redirect_chain"][0]["status"] if page["redirect_chain"] else page["status"]
    if body:
        digest = hashlib.sha256(path.encode("utf-8")).hexdigest()[:10]
        slug = re.sub(r"[^A-Za-z0-9_-]+", "-", urlsplit(path).path).strip("-") or "home"
        html = "text/html" in page.get("content_type", "").lower() or "application/xhtml+xml" in page.get("content_type", "").lower()
        file_path = Path("snapshots") / f"{slug}-{digest}.{'html' if html else 'txt'}"
        (output / file_path).write_bytes(body)
        page["snapshot"] = file_path.as_posix()
        if html:
            try:
                source = body.decode(charset, errors="replace")
            except LookupError:
                source = body.decode("utf-8", errors="replace")
            parser = PageParser(page["final_url"])
            parser.feed(source)
            page["analysis"] = parser.result()
    page["checks"] = technical_checks(page)
    return page


def duplicate_observations(pages):
    result = {}
    for field in ("title", "description", "main_text_sha256"):
        values = defaultdict(list)
        for page in pages:
            value = page.get("analysis", {}).get(field)
            if value and page.get("status") == 200:
                values[value].append(page["path"])
        result[field] = [{"value": value, "paths": paths} for value, paths in values.items() if len(paths) > 1]
    result["note"] = "同じ文字列・本文の観測であり、重複ペナルティや順位低下を示すものではありません。"
    return result


VARIANT_COPY = {
    "/": {
        "title": "日本酒の好み診断・初心者ガイド | 一升の出会い",
        "description": "日本酒初心者向けの一升の出会い。登録なしで好み診断を試し、日本酒を検索できます。購入場所・選び方から保存・開封後までのガイドも読めます。",
        "h1": "日本酒の好み診断で、好きな一本に出会う",
    },
    "/diagnosis": {
        "title": "日本酒の好み診断｜登録なしで試せる | 一升の出会い",
        "description": "専門知識がなくても、質問に答えて好みに合いそうな日本酒を探せます。ログインせず診断でき、未ログイン時の回答や好みの点数は保存されません。",
        "h1": "日本酒の好み診断",
    },
    "/guide": {
        "title": "日本酒初心者ガイド｜選び方・保存・開封後 | 一升の出会い",
        "description": "はじめての日本酒を買う場所、選び方、持ち帰り、保存、開封・飲み方、開封後の扱いを場面別に案内。商品ラベルや蔵元の案内とあわせて確認できます。",
        "h1": "日本酒初心者ガイド：購入から保存・開封後まで",
    },
    "/sake/1": {
        "title": "獺祭 純米大吟醸45｜基本情報と飲み方 | 一升の出会い",
        "description": "獺祭 純米大吟醸45の種類・産地・アルコール度数などの登録情報を確認できます。価格・仕様は購入前に公式サイトや商品ラベルで最新情報をご確認ください。",
        "h1": "獺祭 純米大吟醸45の基本情報",
        "expected_name": "獺祭",
    },
    "/sake/7": {
        "title": "出羽桜 桜花吟醸｜基本情報 | 一升の出会い",
        "description": "出羽桜 桜花吟醸の種類・産地・アルコール度数などの登録情報を確認できます。価格・仕様は購入前に公式サイトや商品ラベルで最新情報をご確認ください。",
        "h1": "出羽桜 桜花吟醸の基本情報",
        "expected_name": "出羽桜",
    },
}


def virtual_variants(pages):
    result = []
    for page in pages:
        copy = VARIANT_COPY.get(page["path"])
        analysis = page.get("analysis")
        if not copy or not analysis or page.get("status") != 200:
            continue
        if copy.get("expected_name") and copy["expected_name"] not in " ".join(analysis["h1"]):
            result.append({"path": page["path"], "status": "not_generated", "reason": "取得した主見出しの銘柄が想定と異なるため、提案を適用しません。"})
            continue
        result.append({
            "path": page["path"], "status": "proposal_only",
            "baseline": {"title": analysis["title"], "description": analysis["description"], "h1": analysis["h1"]},
            "proposed": {key: copy[key] for key in ("title", "description", "h1")},
            "notes": "ローカル取得結果に対する対照案です。本体・保存HTMLは変更せず、本文の他の部分も追加しません。順位・クリック率の向上は未測定です。",
        })
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8082")
    parser.add_argument("--output", type=Path, default=Path("output/seo-lab-20261021"))
    parser.add_argument("--path", action="append", default=[], help="追加のGETパス。複数回指定可。既定パスは常に取得します。")
    args = parser.parse_args(argv)
    try:
        base = loopback_url(args.base_url)
        if base.path not in {"", "/"} or base.query or base.fragment:
            raise ValueError("--base-urlはパス・クエリ・フラグメントを含まないオリジンで指定してください。")
        for path in args.path:
            request_path(path)
    except ValueError as error:
        parser.error(str(error))
    base_url = urlunsplit((base.scheme, base.netloc, "", "", ""))
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "snapshots").mkdir(exist_ok=True)
    paths = list(dict.fromkeys(DEFAULT_PATHS + args.path))
    opener = build_opener(ProxyHandler({}), LoopbackRedirectHandler())
    pages = [fetch_page(opener, base_url, path, args.output) for path in paths]
    summary = {
        "schema_version": 1,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "scope": {
            "base_url": base_url, "only_loopback": True, "methods": ["GET"],
            "authenticated": False, "cookies_used": False, "subresources_fetched": False,
            "javascript_executed": False, "rankings_measured": False,
            "note": "ローカルHTTP応答とHTMLの観測です。検索エンジンへの送信、実順位・検索需要・被リンク・Core Web Vitalsの測定は行いません。SEO点数は算出しません。",
        },
        "pages": pages, "duplicates": duplicate_observations(pages),
        "virtual_variants": virtual_variants(pages),
    }
    (args.output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"summary": str(args.output / "summary.json"), "paths": len(pages), "status_200": sum(page.get("status") == 200 for page in pages), "fetch_errors": sum("error" in page for page in pages), "rankings_measured": False}, ensure_ascii=False))
    return 0 if any(page.get("status") == 200 for page in pages) else 1


if __name__ == "__main__":
    raise SystemExit(main())
