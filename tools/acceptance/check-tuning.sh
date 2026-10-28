#!/usr/bin/env bash
# check-tuning.sh - gate G09.5 (D12: the tunables table and SyncTuning are one source).
#
# G09.3 scans outward (no literal may appear outside SyncTuning.kt). This scans inward:
# every field of SyncTuning must be documented, with a value that evaluates to the same
# number. A field that is undocumented, or documented with a stale default, means the
# table is decoration rather than the authority D12 claims it is.
#
# Exit: 0 PASS, 1 FAIL (prints each mismatch), 3 BLOCKED (either side missing).

set -u
ROOT="."
d="$(pwd)"
while [ "$d" != "/" ]; do
  if [ -f "$d/settings.gradle.kts" ]; then ROOT="$d"; break; fi
  d="$(dirname "$d")"
done
cd "$ROOT" || exit 1

SRC="core/domain/src/main/kotlin/ai/deepseek/dsh/android/domain/SyncTuning.kt"
DOC="docs/TUNING.md"
[ -f "$SRC" ] || { echo "BLOCKED: $SRC not found"; exit 3; }
[ -f "$DOC" ] || { echo "BLOCKED: $DOC not found"; exit 3; }

python3 - "$SRC" "$DOC" <<'PY'
import re, sys

src_path, doc_path = sys.argv[1], sys.argv[2]
src = open(src_path, encoding="utf8").read()
doc = open(doc_path, encoding="utf8").read()

body = src.split("companion object")[0]
kt = {n: v.strip() for n, _, v in re.findall(r"^\s{4}val (\w+):\s*(\w+) = ([^,\n]+)", body, re.M)}

rows = {}
for line in doc.splitlines():
    m = re.match(r"^\| `([A-Za-z0-9]+)` \| (.+?) \|", line)
    if m:
        rows[m.group(1)] = m.group(2).strip()


class ExprError(ValueError):
    pass


def evaluate(expr):
    """Evaluate a numeric Kotlin default that is spelled out of digits and +-*/ only,
    e.g. `8L * 1024 * 1024`. Any other shape is an ExprError: guessing would let a real
    mismatch read as a pass."""
    tokens = re.findall(r"\d+\.\d+|\d+\.|\d+|[+\-*/()%]", expr.replace("_", ""))
    rebuilt = "".join(tokens)
    if rebuilt != expr.replace("_", "").replace("L", "").replace("l", "").replace(" ", ""):
        raise ExprError("unparseable default: " + expr)
    pos = 0

    def peek():
        return tokens[pos] if pos < len(tokens) else None

    def take():
        nonlocal pos
        tok = tokens[pos]
        pos += 1
        return tok

    def primary():
        tok = peek()
        if tok is None:
            raise ExprError("truncated: " + expr)
        if tok == "(":
            take()
            value = additive()
            if peek() != ")":
                raise ExprError("unbalanced parens: " + expr)
            take()
            return value
        if tok in "+-":
            take()
            operand = primary()
            return -operand if tok == "-" else operand
        take()
        if not re.fullmatch(r"\d+\.?\d*", tok):
            raise ExprError("unparseable token " + repr(tok) + " in " + expr)
        return float(tok)

    def multiplicative():
        value = primary()
        while peek() in ("*", "/", "%"):
            op = take()
            rhs = primary()
            if op == "*":
                value *= rhs
            elif op == "/":
                if rhs == 0:
                    raise ExprError("division by zero: " + expr)
                value /= rhs
            else:
                value %= rhs
        return value

    def additive():
        value = multiplicative()
        while peek() in ("+", "-"):
            value = value + multiplicative() if take() == "+" else value - multiplicative()
        return value

    value = additive()
    if pos != len(tokens):
        raise ExprError("trailing input: " + expr)
    return value


def doc_value(text):
    s = text.replace(" ", "")
    m = re.match(r"^([\d.]+)", s)
    if not m:
        raise ExprError("unparseable doc value: " + text)
    n = float(m.group(1))
    for unit, factor in (("MiB", 1024**2), ("KiB", 1024), ("days", 86400000.0),
                         ("h", 3600000.0), ("min", 60000.0), ("ms", 1.0)):
        if s.endswith(unit):
            return n * factor
    return n


violations = []
# backoffJitterRange is a single table row covering the low/high field pair.
JITTER = {"backoffJitterLow": 0, "backoffJitterHigh": 1}

for name in sorted(set(kt) | set(rows)):
    if name in JITTER:
        rng = rows.get("backoffJitterRange")
        if rng is None:
            violations.append("backoffJitterRange: no table row for the jitter pair")
            continue
        endpoints = re.findall(r"[\d.]+", rng)
        for field, idx in JITTER.items():
            if field not in kt:
                continue
            try:
                got = evaluate(kt[field])
            except ExprError as e:
                violations.append(f"{field}: {e}")
                continue
            if len(endpoints) <= idx or abs(float(endpoints[idx]) - got) > 1e-9:
                violations.append(f"{field}: SyncTuning={kt[field]} table range={rng!r}")
        continue
    if name == "backoffJitterRange":
        continue
    if name not in kt:
        violations.append(f"{name}: table row has no SyncTuning field")
        continue
    if name not in rows:
        violations.append(f"{name}: SyncTuning field is not documented in {doc_path}")
        continue
    try:
        want, got = doc_value(rows[name]), evaluate(kt[name])
    except ExprError as e:
        violations.append(f"{name}: {e}")
        continue
    if abs(want - got) > 1e-9:
        violations.append(f"{name}: table says {rows[name]!r} ({want:g}) but the default is {got:g}")

print(f"check-tuning: {len(kt)} SyncTuning fields, {len(rows)} table rows")
if violations:
    print("VIOLATIONS:")
    for v in sorted(violations):
        print("  " + v)
    sys.exit(1)
print("check-tuning: PASS (names and defaults agree)")
PY
