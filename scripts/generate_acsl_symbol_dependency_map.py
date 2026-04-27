#!/usr/bin/env python3
"""
Analisa src/main/resources/ACSL_Lib/**/*.acsl e grava symbol_dependency_map.json:

- symbols: todos os nomes declarados como logic/predicate (heurística por regex).
- symbol_to_defining_file: primeiro ficheiro onde o símbolo aparece como declaração.
- dependencies: cada símbolo → conjunto de outros símbolos da lib chamados como nome(
  no texto agregado do seu ficheiro definidor + includes transitivos deste.
- includes_from: grafo direto include "..." entre ficheiros da lib.

Uso (na raiz do projeto):
  python3 scripts/generate_acsl_symbol_dependency_map.py
"""

from __future__ import annotations

import json
import re
from collections import defaultdict, deque
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
ROOT = PROJECT_ROOT / "src/main/resources/ACSL_Lib"
OUT = ROOT / "symbol_dependency_map.json"

KEYWORDS_CALLS = {
    "forall",
    "exists",
    "let",
    "lambda",
    "old",
    "at",
    "loop",
    "sizeof",
    "true",
    "false",
    "nothing",
    "max",
    "min",
    "mix",
    "abs",
    "sqrt",
}


def strip_block_comments(s: str) -> str:
    return re.sub(r"/\*[\s\S]*?\*/", " ", s)


def strip_line_comments(s: str) -> str:
    out = []
    for line in s.split("\n"):
        if "//" in line:
            i = line.find("//")
            line = line[:i]
        out.append(line)
    return "\n".join(out)


def clean(t: str) -> str:
    return strip_line_comments(strip_block_comments(t))


def normalize_include(base_rel: str, inc: str, root: Path) -> str | None:
    base = root / Path(base_rel).parent
    tgt = (base / inc).resolve()
    try:
        return str(tgt.relative_to(root.resolve())).replace("\\", "/")
    except ValueError:
        return None


def main() -> None:
    root = ROOT.resolve()
    if not root.is_dir():
        raise SystemExit(f"Pasta ACSL_Lib não encontrada: {root}")

    files: dict[str, str] = {}
    for p in root.rglob("*.acsl"):
        if p.name == "symbol_dependency_map.json":
            continue
        rel = str(p.relative_to(root)).replace("\\", "/")
        files[rel] = clean(p.read_text(encoding="utf-8"))

    includes_of: dict[str, list[str]] = defaultdict(list)
    for rel, text in files.items():
        for m in re.finditer(r'include\s+"([^"]+)"\s*;', text):
            inc = m.group(1).strip()
            if inc.startswith("/") or inc.startswith("import/"):
                continue
            tgt = normalize_include(rel, inc, root)
            if tgt and tgt in files:
                includes_of[rel].append(tgt)

    def transitive_files(start: str) -> tuple[set[str], list[str]]:
        seen: set[str] = set()
        q: deque[str] = deque([start])
        order: list[str] = []
        while q:
            u = q.popleft()
            if u in seen:
                continue
            seen.add(u)
            order.append(u)
            for v in includes_of.get(u, []):
                if v not in seen:
                    q.append(v)
        return seen, order

    decl_patterns = [
        re.compile(r"\bpredicate\s+(\w+)\s*\(", re.MULTILINE),
        re.compile(
            r"\blogic\s+(?:[^\n(;]|\([^)]*\)|<[^>]*>|\[[^\]]*\]|\\list<[^>]+>)+?\s+(\w+)\s*\(",
            re.MULTILINE,
        ),
    ]

    declared_in_file: dict[str, set[str]] = defaultdict(set)
    symbol_def_file: dict[str, str] = {}

    for rel in sorted(files.keys()):
        text = files[rel]
        found: set[str] = set()
        for pat in decl_patterns:
            for m in pat.finditer(text):
                name = m.group(1)
                if name in ("axiom", "lemma", "predicate", "logic", "type", "inductive"):
                    continue
                found.add(name)
        declared_in_file[rel] = found
        for s in found:
            symbol_def_file.setdefault(s, rel)

    all_symbols = set(symbol_def_file.keys())

    call_re = re.compile(r"(?<![A-Za-z0-9_])([A-Za-z_]\w*)\s*\(")

    def calls_in_text(text: str) -> set[str]:
        out: set[str] = set()
        for m in call_re.finditer(text):
            w = m.group(1)
            if w in KEYWORDS_CALLS:
                continue
            out.add(w)
        return out

    deps: dict[str, set[str]] = defaultdict(set)
    for sym, def_f in symbol_def_file.items():
        _, order = transitive_files(def_f)
        blob = "\n".join(files.get(r, "") for r in order)
        for c in calls_in_text(blob):
            if c in all_symbols and c != sym:
                deps[sym].add(c)

    out_json = {
        "_meta": {
            "generator": "scripts/generate_acsl_symbol_dependency_map.py",
            "root": str(ROOT.relative_to(PROJECT_ROOT)).replace("\\", "/"),
            "symbol_count": len(all_symbols),
        },
        "symbols": sorted(all_symbols),
        "symbol_to_defining_file": dict(sorted(symbol_def_file.items())),
        "dependencies": {k: sorted(v) for k, v in sorted(deps.items())},
        "includes_from": {k: sorted(set(includes_of[k])) for k in sorted(includes_of)},
    }

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(out_json, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"Written {OUT} ({len(all_symbols)} symbols)")


if __name__ == "__main__":
    main()
