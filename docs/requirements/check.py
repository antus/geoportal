# SPDX-FileCopyrightText: 2026 Massimo Antonini
"""Checks the requirements of this repository, as the requirements test of Mosaikit does for its own.
A requirement lives in the docs/requirements folder of the extension that implements it
(extensions/<name>/docs/requirements/GP-nnn.md), or in docs/requirements when it spans several
extensions or concerns the product as a whole; every docs/requirements folder of the repository is read.

- the front matter follows docs/requirements/requirement.schema.json (a copy of the one of Mosaikit),
  the file is named after the id, the id has the prefix and the area is one of requirements.json,
  and the body is not empty;
- every dependency on a GP requirement exists; dependencies on Mosaikit (MK) and on its common
  extensions (EXT, mosaikit-ext) are checked against a clone of each when there is one (../mosaikit or
  MOSAIKIT_REQUIREMENTS, ../mosaikit-ext or MOSAIKIT_EXT_REQUIREMENTS, which the CI sets and which must
  then exist), and so is a moved-to;
- every GP id cited by a test exists, and every requirement marked done is cited by a test
  (a proof suite, prototypes/<phase>/run.py, counts as one).

    python docs/requirements/check.py
"""
from __future__ import annotations

import io
import json
import os
import re
import sys

import jsonschema
import yaml

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DIRECTORY = os.path.join(ROOT, "docs", "requirements")
FRONT_MATTER = re.compile(r"^---\n(.*?)\n---\n(.*)$", re.S)
# Folders never searched for requirements: dependencies and builds, and those starting with a dot
# (.git, and the clones of other repositories that the CI puts in .requirements).
SKIPPED = ("node_modules", "target")
TEST_FILES = re.compile(r"(\.spec\.ts|\.test\.ts|\.e2e\.ts|Test\.java|IT\.java)$")
# A proof (prototypes/<phase>/run.py) is the test of a requirement that settles a question rather
# than ships code: it runs every proof and fails when one does not hold.
PROOF_SUITE = "run.py"


def read(path: str) -> tuple[dict, str]:
    text = io.open(path, encoding="utf-8").read().replace("\r\n", "\n")
    m = FRONT_MATTER.match(text)
    if not m:
        raise ValueError("no front matter between --- lines")
    return yaml.safe_load(m.group(1)) or {}, m.group(2)


def requirement_files(root: str, pattern: str) -> list[tuple[str, str]]:
    """(file name, path) of every file named after `pattern` in a docs/requirements folder under root."""
    found = []
    for base, dirs, files in os.walk(root):
        dirs[:] = sorted(d for d in dirs if d not in SKIPPED and not d.startswith("."))
        if os.path.basename(base) == "requirements" and os.path.basename(os.path.dirname(base)) == "docs":
            found += [(name, os.path.join(base, name)) for name in sorted(files) if re.match(pattern, name)]
    return found


# The products this one builds on: prefix -> (variable naming their requirements, clone next to this one).
PRODUCTS = {
    "MK": ("MOSAIKIT_REQUIREMENTS", "mosaikit"),
    "EXT": ("MOSAIKIT_EXT_REQUIREMENTS", "mosaikit-ext"),
}


def product_ids(prefix: str) -> set[str] | None:
    variable, clone = PRODUCTS[prefix]
    directory = os.environ.get(variable, os.path.join(os.path.dirname(ROOT), clone, "docs", "requirements"))
    if not os.path.isdir(directory):
        if variable in os.environ:
            # Named on purpose (the CI does): a wrong path must fail, not skip the check.
            raise SystemExit(f"error   {variable}={directory} is not a directory")
        return None
    # A docs/requirements folder stands for its repository: the requirements of its extensions count too.
    if os.path.normpath(directory).replace(os.sep, "/").endswith("docs/requirements"):
        directory = os.path.dirname(os.path.dirname(os.path.normpath(directory)))
    return {name[:-3] for name, _ in requirement_files(directory, rf"^{prefix}-\d{{3,}}\.md$")}


def cited_by_tests(prefix: str) -> set[str]:
    cited: set[str] = set()
    tag = re.compile(rf"\b{prefix}-\d{{3,}}\b")
    for base, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in ("node_modules", "target", ".git", "docs")]
        for name in files:
            proof = name == PROOF_SUITE and os.path.relpath(base, ROOT).split(os.sep)[0] == "prototypes"
            if TEST_FILES.search(name) or proof:
                cited.update(tag.findall(io.open(os.path.join(base, name), encoding="utf-8", errors="replace").read()))
    return cited


def main() -> int:
    product = json.load(io.open(os.path.join(DIRECTORY, "requirements.json"), encoding="utf-8"))
    schema = json.load(io.open(os.path.join(DIRECTORY, "requirement.schema.json"), encoding="utf-8"))
    validator = jsonschema.Draft202012Validator(schema)
    prefix = product["prefix"]
    own = re.compile(rf"^{prefix}-\d{{3,}}$")
    errors: list[str] = []
    requirements: dict[str, dict] = {}
    for name, path in requirement_files(ROOT, r"^[A-Z]{2,4}-\d{3,}\.md$"):
        where_file = os.path.relpath(path, ROOT).replace(os.sep, "/")
        try:
            meta, body = read(path)
        except (ValueError, yaml.YAMLError) as e:
            errors.append(f"{name}: {e}")
            continue
        for error in validator.iter_errors(meta):
            where = "/".join(str(p) for p in error.absolute_path) or "front matter"
            errors.append(f"{name}: {where}: {error.message}")
        rid = meta.get("id", "")
        if f"{rid}.md" != name:
            errors.append(f"{name}: its id is {rid}")
        if not own.match(str(rid)):
            errors.append(f"{name}: the id does not have the prefix {prefix}")
        if meta.get("area") not in product["areas"]:
            errors.append(f"{name}: area {meta.get('area')} is not one of requirements.json")
        if len(body.strip()) <= 10:
            errors.append(f"{name}: the body is empty")
        if str(rid) in requirements:
            errors.append(f"{where_file}: {rid} is also in {requirements[str(rid)]['_file']}")
        requirements[str(rid)] = {**meta, "_file": where_file}
    others = {prefix: product_ids(prefix) for prefix in product["dependsOn"] if prefix in PRODUCTS}
    for rid, meta in requirements.items():
        for d in list(meta.get("depends") or []) + ([meta["moved-to"]] if meta.get("moved-to") else []):
            if own.match(d):
                if d not in requirements:
                    errors.append(f"{rid}: {d} does not exist")
            elif not any(d.startswith(p + "-") for p in product["dependsOn"]):
                errors.append(f"{rid}: {d} is not of a product this one builds on")
            else:
                ids = others.get(d.split("-")[0])
                if ids is not None and d not in ids:
                    errors.append(f"{rid}: {d} does not exist in {PRODUCTS[d.split('-')[0]][1]}")
    cited = cited_by_tests(prefix)
    for rid in sorted(cited - set(requirements)):
        errors.append(f"a test cites {rid}, which does not exist")
    for rid, meta in requirements.items():
        if meta.get("status") == "done" and "verified-in" not in meta and rid not in cited:
            errors.append(f"{rid} is done but no test cites it")
    for e in errors:
        print("error  ", e)
    counts: dict[str, int] = {}
    for meta in requirements.values():
        counts[meta.get("status", "?")] = counts.get(meta.get("status", "?"), 0) + 1
    print(f"{len(requirements)} requirements ({', '.join(f'{v} {k}' for k, v in sorted(counts.items()))}), "
          f"{len(errors)} errors" + "".join(f"; {PRODUCTS[p][1]} not found, {p} ids not checked"
                                             for p, ids in others.items() if ids is None))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
