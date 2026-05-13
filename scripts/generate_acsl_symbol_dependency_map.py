#!/usr/bin/env python3
"""
Analisa src/main/resources/lib/B2ACSLLib/**/*.acsl e grava b2acsl/symbol_dependency_map.json.

Campos gerados:
  - symbols: nomes declarados como logic/predicate (exclui ficheiros em SKIPPED_DEFINING_FILES).
  - symbol_to_defining_file: símbolo → ficheiro canónico (primeira declaração encontrada).
  - symbol_to_all_files: símbolo → todos os ficheiros que o declaram (sobrecarga incluída).
  - dependencies: símbolo → outros símbolos da lib usados no texto agregado do seu ficheiro.
  - includes_from: grafo completo de dependências entre ficheiros (includes explícitos +
    dependências implícitas detectadas pelo uso de símbolos nos axiomas).

Algoritmo de detecção de dependências implícitas:
  Para cada ficheiro f e cada símbolo s chamado em f cujo ficheiro definidor def(s) não está
  no fecho transitivo de f, é adicionada a aresta f → def(s). A iteração repete até estabilizar.
  Símbolos com múltiplos ficheiros definidores (sobrecarga ambígua, ex.: first, ran, equals)
  são tratados via EXTRA_INCLUDES_FROM quando a detecção automática é insuficiente.

Uso (na raiz do projeto):
  python3 scripts/generate_acsl_symbol_dependency_map.py
"""

from __future__ import annotations

import json
import re
from collections import defaultdict, deque
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
ROOT = PROJECT_ROOT / "src/main/resources/lib/B2ACSLLib"
OUT = PROJECT_ROOT / "src/main/resources/b2acsl/symbol_dependency_map.json"

# Ficheiros a ignorar como fontes de declaração de símbolos (legados / teste).
SKIPPED_DEFINING_FILES = {
    "old_sequence.acsl",
    "sequence_functions/mapping_functions.acsl",
    "sequence_test.acsl",
    "types_lemmas.acsl",
}

# Dependências de include que não são detectadas automaticamente por envolverem
# símbolos sobrecarregados onde o contexto de tipo não é analisado estaticamente.
# Chave: ficheiro onde a dependência ocorre; valor: lista de ficheiros necessários.
EXTRA_INCLUDES_FROM: dict[str, list[str]] = {
    # is_partial_function usa ran(f) sobre Relation — 'ran' também existe em
    # sequence_functions/range.acsl (lista), portanto é ambíguo; forçamos a versão de Relation.
    "function_functions/is_partial.acsl": [
        "relation_functions/range.acsl",
    ],
    # is_function_of usa ran(f) sobre Relation (idem).
    "function_functions/is_function_of.acsl": [
        "relation_functions/range.acsl",
    ],
    # disjoint usa equals(...) sobre Set — 'equals' também existe em tuple_functions/equals.acsl.
    "set_functions/disjoint.acsl": [
        "set_functions/equals.acsl",
    ],
    # relation_domain_restriction usa first(t) sobre Tuple — 'first' também existe em
    # sequence_functions/first.acsl (lista), portanto é ambíguo; forçamos a versão de Tuple.
    "relation_axioms/relation_domain_restriction.acsl": [
        "tuple_functions/accessors.acsl",
    ],
}

KEYWORDS_CALLS = {
    "forall", "exists", "let", "lambda", "old", "at", "loop",
    "sizeof", "true", "false", "nothing", "max", "min", "mix",
    "abs", "sqrt",
}

# Nomes que surgem em padrões de declaração mas não são símbolos de biblioteca.
SKIP_NAMES = {"axiom", "lemma", "predicate", "logic", "type", "inductive"}


def strip_block_comments(s: str) -> str:
    return re.sub(r"/\*[\s\S]*?\*/", " ", s)


def strip_line_comments(s: str) -> str:
    out = []
    for line in s.split("\n"):
        if "//" in line:
            line = line[:line.find("//")]
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
        raise SystemExit(f"Pasta B2ACSLLib não encontrada: {root}")

    # ── 1. Ler e limpar todos os ficheiros .acsl ──────────────────────────────
    files: dict[str, str] = {}
    for p in root.rglob("*.acsl"):
        rel = str(p.relative_to(root)).replace("\\", "/")
        files[rel] = clean(p.read_text(encoding="utf-8"))

    # ── 2. Construir grafo de includes explícitos ─────────────────────────────
    includes_of: dict[str, list[str]] = defaultdict(list)
    for rel, text in files.items():
        for m in re.finditer(r'include\s+"([^"]+)"\s*;', text):
            inc = m.group(1).strip()
            if inc.startswith("/") or inc.startswith("import/"):
                continue
            tgt = normalize_include(rel, inc, root)
            if tgt and tgt in files:
                includes_of[rel].append(tgt)

    # ── 3. Adicionar includes extras manuais ──────────────────────────────────
    for src, targets in EXTRA_INCLUDES_FROM.items():
        if src not in files:
            continue
        for tgt in targets:
            if tgt in files and tgt not in includes_of[src]:
                includes_of[src].append(tgt)

    # ── 4. Encontrar declarações de símbolos ──────────────────────────────────
    # Padrão para predicados: predicate nome<Params>(  ou  predicate nome(
    _pat_predicate = re.compile(
        r"\bpredicate\s+(\w+)(?:<[^>]*>)?\s*[=(]", re.MULTILINE
    )
    # Padrão para funções lógicas: logic <TipoRetorno> nome<Params>(
    # Usa [^\n=]+ (para antes do '=') e match greedy para consumir o tipo de retorno;
    # o último identificador antes de <...>( é o nome da função.
    # O '=' separa a cabeça da declaração do corpo inline, evitando capturar
    # chamadas de função no corpo (ex.: pair = set_union(singleton(...))).
    _pat_logic = re.compile(
        r"\blogic\s+[^\n=]+\s(\w+)(?:<[^>]*>)?\s*\(", re.MULTILINE
    )
    decl_patterns = [_pat_predicate, _pat_logic]

    declared_in_file: dict[str, set[str]] = defaultdict(set)
    symbol_def_file: dict[str, str] = {}
    symbol_to_all_def_files: dict[str, list[str]] = defaultdict(list)

    for rel in sorted(files.keys()):
        if rel in SKIPPED_DEFINING_FILES:
            continue
        text = files[rel]
        found: set[str] = set()
        for pat in decl_patterns:
            for m in pat.finditer(text):
                name = m.group(1)
                if name in SKIP_NAMES:
                    continue
                found.add(name)
        declared_in_file[rel] = found
        for s in found:
            symbol_def_file.setdefault(s, rel)
            if rel not in symbol_to_all_def_files[s]:
                symbol_to_all_def_files[s].append(rel)

    all_symbols = set(symbol_def_file.keys())

    # ── 5. Enriquecer includes_of com dependências implícitas ─────────────────
    # Para símbolos com definição ÚNICA (não sobrecarregados de forma ambígua),
    # detecta usos nos axiomas e adiciona arestas ao grafo de includes.
    # Símbolos ambíguos (ex.: first, ran, equals) são geridos por EXTRA_INCLUDES_FROM.

    call_re = re.compile(r"(?<![A-Za-z0-9_])([A-Za-z_]\w*)\s*\(")

    def sym_calls_in(text: str) -> set[str]:
        """Símbolos da lib chamados no texto, excluindo \\symbol (built-ins ACSL)."""
        out: set[str] = set()
        for m in call_re.finditer(text):
            start = m.start()
            if start > 0 and text[start - 1] == "\\":
                continue  # \length, \nth, etc.
            w = m.group(1)
            if w in all_symbols:
                out.add(w)
        return out

    def compute_closure(start: str) -> set[str]:
        seen: set[str] = set()
        q: deque[str] = deque([start])
        while q:
            f = q.popleft()
            if f in seen:
                continue
            seen.add(f)
            for v in includes_of.get(f, []):
                if v not in seen:
                    q.append(v)
        return seen

    for _ in range(20):  # itera até estabilizar (normalmente 2-3 passagens)
        new_edges: set[tuple[str, str]] = set()
        for rel in files.keys():
            cl = compute_closure(rel)
            for f in list(cl):
                text = files.get(f, "")
                for sym in sym_calls_in(text):
                    defs = symbol_to_all_def_files.get(sym, [])
                    # Apenas símbolos com definição única evitam ambiguidade de sobrecarga.
                    if len(defs) == 1:
                        def_file = defs[0]
                        if def_file not in cl:
                            new_edges.add((f, def_file))
        if not new_edges:
            break
        for src, dst in new_edges:
            if dst not in includes_of[src]:
                includes_of[src].append(dst)

    # ── 6. Calcular dependências entre símbolos ───────────────────────────────
    def all_calls_in(text: str) -> set[str]:
        out: set[str] = set()
        for m in call_re.finditer(text):
            start = m.start()
            if start > 0 and text[start - 1] == "\\":
                continue
            w = m.group(1)
            if w not in KEYWORDS_CALLS:
                out.add(w)
        return out

    def transitive_order(start: str) -> list[str]:
        seen: set[str] = set()
        order: list[str] = []
        q: deque[str] = deque([start])
        while q:
            u = q.popleft()
            if u in seen:
                continue
            seen.add(u)
            order.append(u)
            for v in includes_of.get(u, []):
                if v not in seen:
                    q.append(v)
        return order

    deps: dict[str, set[str]] = defaultdict(set)
    for sym, def_f in symbol_def_file.items():
        order = transitive_order(def_f)
        blob = "\n".join(files.get(r, "") for r in order)
        for c in all_calls_in(blob):
            if c in all_symbols and c != sym:
                deps[sym].add(c)

    # ── 7. Gravar JSON ────────────────────────────────────────────────────────
    out_json = {
        "_meta": {
            "generator": "scripts/generate_acsl_symbol_dependency_map.py",
            "root": str(ROOT.relative_to(PROJECT_ROOT)).replace("\\", "/"),
            "symbol_count": len(all_symbols),
        },
        "symbols": sorted(all_symbols),
        "symbol_to_defining_file": dict(sorted(symbol_def_file.items())),
        "symbol_to_all_files": {
            k: sorted(set(v)) for k, v in sorted(symbol_to_all_def_files.items())
        },
        "dependencies": {k: sorted(v) for k, v in sorted(deps.items()) if v},
        "includes_from": {
            k: sorted(set(includes_of[k])) for k in sorted(includes_of)
        },
    }

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(out_json, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"Written {OUT} ({len(all_symbols)} symbols)")


if __name__ == "__main__":
    main()
