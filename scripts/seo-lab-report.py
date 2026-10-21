#!/usr/bin/env python3
"""Compare explicit query terms against local source HTML and proposed metadata.

This measures literal matches, never search ranking or an SEO score.
Raw HTML, cookies, CSRF tokens, and full page bodies are not exported.
"""

import argparse
import json
from pathlib import Path
import re
import subprocess
import unicodedata
from urllib.parse import urlsplit


PUBLIC_PATHS = {"/", "/diagnosis", "/guide", "/search", "/about", "/contact",
                "/privacy", "/external-transmission"} | {f"/sake/{i}" for i in range(1, 11)}


def normalize(value):
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", value)).casefold()


def matches(value, terms):
    text = normalize(value)
    return {"found": [term for term in terms if normalize(term) in text],
            "missing": [term for term in terms if normalize(term) not in text]}


def build_report(summary, queries, catalog, revision):
    pages = {p["path"]: p for p in summary["pages"]}
    missing = PUBLIC_PATHS - pages.keys()
    if missing:
        raise ValueError("主要18ページが揃っていません: " + ", ".join(sorted(missing)))
    public = [pages[path] for path in sorted(PUBLIC_PATHS)]
    if any(p.get("initial_status") != 200 or "analysis" not in p for p in public):
        raise ValueError("取得できなかった主要ページがあります。結果を成功扱いにせず、summaryを確認してください。")
    variants = {v["path"]: v for v in summary["virtual_variants"] if v["status"] == "proposal_only"}
    comparisons = []
    for query in queries:
        path, terms = query["path"], query["terms"]
        analysis = pages[path]["analysis"]
        before = {"title": analysis["title"], "description": analysis["description"],
                  "h1": " ".join(analysis["h1"])}
        after = variants[path]["proposed"]
        # Hold the captured source constant. Only model the proposed H1 substitution.
        body_before = normalize(analysis["main_text"])
        heading = normalize(before["h1"])
        if not heading or heading not in body_before:
            raise ValueError("本文内の主見出しを対応付けできません: " + path)
        body_after = body_before.replace(heading, normalize(after["h1"]), 1)
        comparisons.append({
            **query, "baseline": before, "proposed": after,
            "literal_matches": {
                "baseline": {**{key: matches(before[key], terms) for key in ("title", "h1", "description")},
                             "main_including_h1": matches(body_before, terms)},
                "proposed": {**{key: matches(after[key], terms) for key in ("title", "h1", "description")},
                             "main_including_h1": matches(body_after, terms)},
            },
        })
    observations = [{"path": p["path"], "initial_status": p.get("initial_status"),
                     "final_status": p["status"], "final_path": urlsplit(p["final_url"]).path,
                     "redirect_chain": [{"status": r["status"], "followed": r["followed"]}
                                        for r in p.get("redirect_chain", [])],
                     "title": p.get("analysis", {}).get("title", ""),
                     "main_characters": p.get("analysis", {}).get("main_text_characters", 0)}
                    for p in summary["pages"]]
    return {
        "checked_on": "2026-10-21", "source_revision": revision,
        "captured_at_utc": summary["generated_at"],
        "scope": {"local_only": True, "app_source_modified": False, "published": False,
                  "rankings_measured": False, "rank_prediction": None,
                  "query_method": "事前固定した7検索語。NFKC・空白除去・大小文字の正規化後に語句の部分一致を確認。同義語・検索需要・被リンク・Googleの評価は推定しない。",
                  "variant_method": "5ページでtitle・description・H1の仮案を比較。本体と保存HTMLを変更せず、本文モデルはH1だけ置換。本文に新たな記事は追加しない。",
                  "limitations": "語句がないことは機能がないことを意味しない。例えば『ログインせず』と『登録なし』を同一視しない。本文にはH1を含む。Googleの検索表示・順位・クリック率・Core Web Vitalsは未測定。"},
        "observations": {
            "urls_checked": len(summary["pages"]), "public_pages": len(public),
            "public_direct_200": sum(p["initial_status"] == 200 for p in public),
            "public_with_title": sum(bool(p["analysis"]["title"]) for p in public),
            "public_with_description": sum(bool(p["analysis"]["description"]) for p in public),
            "public_with_canonical": sum(bool(p["analysis"]["canonical"]) for p in public),
            "public_with_json_ld": sum(bool(p["analysis"]["json_ld"]) for p in public),
            "public_with_single_h1": sum(len(p["analysis"]["h1"]) == 1 for p in public),
            "catalog_entries": len(catalog),
            "catalog_with_drinking": sum(bool(p.get("drinking")) for p in catalog),
            "catalog_with_taste": sum(bool(p.get("taste")) for p in catalog),
            "catalog_with_recommended_for": sum(bool(p.get("recommendedFor")) for p in catalog),
        },
        "requests": observations, "duplicate_titles": summary["duplicates"]["title"],
        "comparisons": comparisons,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=Path("output/seo-lab-20261021/summary.json"))
    parser.add_argument("--queries", type=Path, default=Path("data/seo-lab/queries.json"))
    parser.add_argument("--output", type=Path, default=Path("data/seo-lab/results-20261021.json"))
    args = parser.parse_args()
    read = lambda p: json.loads(p.read_text(encoding="utf-8"))
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    report = build_report(read(args.input), read(args.queries),
                          read(Path("src/main/resources/data/sake-pages.json")), revision)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report["observations"], ensure_ascii=False))


if __name__ == "__main__":
    main()
