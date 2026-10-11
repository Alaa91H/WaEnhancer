#!/usr/bin/env python3
"""Fail closed when a catalog feature advertises a nonexistent native resolver.

Only actual fun load* declarations in Unobfuscator.kt count as resolvers.
Source comments, literal strings and helper signatures are not registrations.
Exit: 0 contract valid, 1 missing resolver, 2 source cannot be parsed.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
PLATFORM = Path("app/src/main/java/com/wax/module/platform")
UNOBFUSCATOR = Path("app/src/main/java/com/wax/module/xposed/core/devkit/Unobfuscator.kt")
FEATURE = re.compile(r"(?<![\w.])feature\s*\(")
RESOLVER_ARGUMENT = re.compile(r"\b(requiredResolvers|optionalResolvers)\s*=")
DECLARATION = re.compile(r"\bfun\s+(load[A-Za-z0-9_]+)\s*\(")
RESOLVER_NAME = re.compile(r'"([A-Za-z_][A-Za-z0-9_]*)"')


class InvalidCatalog(ValueError):
    """A source file cannot be inspected safely."""


def mask_non_code(source: str, *, keep_strings: bool = False) -> str:
    """Replace comments and, optionally, literals with spaces, retaining offsets."""
    output: list[str] = []
    pos = 0
    size = len(source)

    def emit(end: int, hidden: bool) -> None:
        segment = source[pos:end]
        output.append(
            "".join("\n" if char == "\n" else " " for char in segment)
            if hidden
            else segment
        )

    while pos < size:
        if source.startswith("//", pos):
            end = source.find("\n", pos)
            end = size if end < 0 else end
            emit(end, True)
        elif source.startswith("/*", pos):
            depth = 1
            end = pos + 2
            while end < size and depth:
                if source.startswith("/*", end):
                    depth += 1
                    end += 2
                elif source.startswith("*/", end):
                    depth -= 1
                    end += 2
                else:
                    end += 1
            if depth:
                raise InvalidCatalog("unclosed Kotlin block comment")
            emit(end, True)
        elif source.startswith('"""', pos):
            end = source.find('"""', pos + 3)
            if end < 0:
                raise InvalidCatalog("unclosed Kotlin triple-quoted string")
            emit(end + 3, not keep_strings)
            end += 3
        elif source[pos] in ('"', "'"):
            quote = source[pos]
            end = pos + 1
            while end < size:
                if source[end] == "\\":
                    end += 2
                elif source[end] == quote:
                    end += 1
                    break
                else:
                    end += 1
            else:
                raise InvalidCatalog("unclosed Kotlin literal")
            emit(end, not keep_strings)
        else:
            end = pos + 1
            emit(end, False)
        pos = end
    return "".join(output)


def closing_paren(code: str, opening: int) -> int:
    if opening >= len(code) or code[opening] != "(":
        raise InvalidCatalog("expected opening parenthesis")
    depth = 0
    for offset in range(opening, len(code)):
        if code[offset] == "(":
            depth += 1
        elif code[offset] == ")":
            depth -= 1
            if depth == 0:
                return offset
    raise InvalidCatalog("unbalanced Kotlin parentheses")


def declared_resolvers(source: str) -> set[str]:
    names = {match.group(1) for match in DECLARATION.finditer(mask_non_code(source))}
    if not names:
        raise InvalidCatalog("Unobfuscator.kt has no load* resolver declarations")
    return names


def feature_references(source: str, file_name: str) -> list[tuple[str, str, str, int]]:
    code = mask_non_code(source)
    without_comments = mask_non_code(source, keep_strings=True)
    found: list[tuple[str, str, str, int]] = []
    for call in FEATURE.finditer(code):
        if re.search(r"\bfun\s*$", code[max(0, call.start() - 30) : call.start()]):
            continue
        end = closing_paren(code, call.end() - 1)
        body = code[call.end() : end]
        ident = re.search(r"\bPlatformFeatures\.([A-Z][A-Z0-9_]*)\b", body)
        if ident is None:
            continue
        feature = ident.group(1)
        line = source.count("\n", 0, call.start()) + 1
        for arg in RESOLVER_ARGUMENT.finditer(body):
            kind = arg.group(1)
            value_at = arg.end()
            assignment = re.match(r"\s*(listOf|emptyList)\s*\(", body[value_at:])
            if assignment is None:
                raise InvalidCatalog(
                    f"{file_name}:{line} {feature}: dynamic {kind} cannot be verified"
                )
            opening = value_at + assignment.end() - 1
            closing = closing_paren(body, opening)
            payload = without_comments[call.end() + opening + 1 : call.end() + closing]
            pieces = [piece.strip() for piece in payload.split(",")]
            if pieces[-1] == "":
                pieces.pop()
            if assignment.group(1) == "emptyList" and pieces:
                raise InvalidCatalog(f"{file_name}:{line} {feature}: nonempty emptyList()")
            for piece in pieces:
                match = RESOLVER_NAME.fullmatch(piece)
                if match is None:
                    raise InvalidCatalog(
                        f"{file_name}:{line} {feature}: nonliteral {kind} element {piece!r}"
                    )
                found.append((feature, kind, match.group(1), line))
    return found


def inspect(root: Path) -> tuple[int, int, list[str]]:
    platform = root / PLATFORM
    sources = sorted(platform.glob("*.kt"))
    if not sources:
        raise InvalidCatalog(f"no Kotlin catalog sources found at {platform}")
    resolver_file = root / UNOBFUSCATOR
    try:
        resolver_names = declared_resolvers(resolver_file.read_text(encoding="utf-8"))
    except OSError as error:
        raise InvalidCatalog(f"cannot read {resolver_file}: {error}") from error

    references: list[tuple[str, str, str, int, str]] = []
    registrations = 0
    for path in sources:
        try:
            source = path.read_text(encoding="utf-8")
        except OSError as error:
            raise InvalidCatalog(f"cannot read {path}: {error}") from error
        code = mask_non_code(source)
        registrations += sum(
            re.search(
                r"\bPlatformFeatures\.([A-Z][A-Z0-9_]*)\b",
                code[m.end() : closing_paren(code, m.end() - 1)],
            )
            is not None
            for m in FEATURE.finditer(code)
            if not re.search(r"\bfun\s*$", code[max(0, m.start() - 30) : m.start()])
        )
        for feature, kind, resolver, line in feature_references(source, path.name):
            references.append((feature, kind, resolver, line, path.name))
    if registrations == 0:
        raise InvalidCatalog("no feature registrations were found")
    violations = [
        f"{file_name}:{line}: {feature} {kind} declares missing {resolver}"
        for feature, kind, resolver, line, file_name in references
        if resolver not in resolver_names
    ]
    return registrations, len(references), sorted(violations)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    options = parser.parse_args(argv)
    try:
        features, references, errors = inspect(options.root)
    except (InvalidCatalog, UnicodeError) as error:
        print(f"error: catalog resolver gate cannot verify source: {error}", file=sys.stderr)
        return 2
    if errors:
        for error in errors:
            print(f"error: {error}", file=sys.stderr)
        return 1
    print(
        f"Catalog resolver gate passed: {features} feature declarations, "
        f"{references} declared resolver references."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
