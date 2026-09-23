#!/usr/bin/env python3
"""Catch NameError-class bugs that `ast.parse` and `py_compile` do not.

Written after a bad string-replace deleted the line binding `deck`, `h`,
`mine`, `opps` and `info` in cmd_cycle. The module still parsed and imported
perfectly -- reading an unbound name is valid syntax -- so every check run on
it passed, and the failure surfaced only when the timer fired on the droplet
seven minutes later.

When a name is read in a function but never assigned there, Python resolves it
as a global. `symtable` reports exactly that, and unlike a scan of
`code.co_names` it does not confuse attribute access (`x.get`) for a name, so
this stays quiet enough to be worth running.

Neither pyflakes nor ruff is installed here; if either ever is, prefer it.
"""
from __future__ import annotations

import builtins
import symtable
import sys
from pathlib import Path


def undefined_globals(path: Path) -> list[str]:
    src = path.read_text()
    top = symtable.symtable(src, str(path), "exec")
    module_level = {s.get_name() for s in top.get_symbols()}
    known = module_level | set(dir(builtins))

    out: list[str] = []

    def walk(table, trail: str) -> None:
        for child in table.get_children():
            name = f"{trail}.{child.get_name()}" if trail else child.get_name()
            if child.get_type() == "function":
                for sym in child.get_symbols():
                    if not sym.is_global():
                        continue
                    ident = sym.get_name()
                    if ident in known or ident.startswith("__"):
                        continue
                    out.append(f"{path}:{child.get_lineno()}: {name}() reads "
                               f"'{ident}' — not a local, module global, or builtin")
            walk(child, name)

    walk(top, "")
    return out


def main(argv: list[str]) -> int:
    root = Path(__file__).resolve().parent.parent
    paths = [Path(p) for p in argv[1:]] or sorted(root.glob("abagent/*.py"))
    found: list[str] = []
    for p in paths:
        found.extend(undefined_globals(p))
    for line in found:
        print(line)
    print(f"{len(found)} undefined name(s) across {len(paths)} file(s)")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
