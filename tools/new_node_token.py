#!/usr/bin/env python3
"""노드 토큰 만들기. 토큰 평문은 한 번만 나오고, DB 에 넣을 INSERT 문에는 SHA-256 만 들어간다.

    python3 tools/new_node_token.py ws4 --expected-gpus 3 > node.sql
        토큰은 stderr 로 한 번 보인다. 노드의 토큰 파일(0600)에 옮겨 적는다.
    python3 tools/new_node_token.py ws4 --token-file ./ws4.token > node.sql
        토큰을 화면에 보이지 않고 0600 파일로 쓴다.

stdout 은 INSERT 문뿐이라 그대로 psql 에 넘길 수 있다.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import secrets
import sys

# 서버의 NodeName 과 같은 규칙. 이름이 SQL 문자열에 그대로 들어가므로 여기서 막는다.
NODE_NAME = re.compile(r"[a-z0-9][a-z0-9-]{0,31}")


def insert_sql(name: str, token: str, expected_gpus: int) -> str:
    digest = hashlib.sha256(token.encode("utf-8")).hexdigest()
    return (f"INSERT INTO node (name, token_sha256, expected_gpus) "
            f"VALUES ('{name}', decode('{digest}', 'hex'), {expected_gpus});")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("name", help="node name, e.g. ws4 (same spelling as the Slurm NodeName)")
    parser.add_argument("--expected-gpus", type=int, default=0)
    parser.add_argument("--token-file", help="write the token to this new file (mode 0600) instead of showing it")
    args = parser.parse_args(argv)

    if not NODE_NAME.fullmatch(args.name):
        parser.error(f"invalid node name {args.name!r}: expected {NODE_NAME.pattern}")
    if args.expected_gpus < 0:
        parser.error("--expected-gpus must be >= 0")

    token = secrets.token_urlsafe(32)
    if args.token_file:
        fd = os.open(args.token_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(token + "\n")
        print(f"token written to {args.token_file}", file=sys.stderr)
    else:
        print(f"token for {args.name} (shown once, not stored anywhere): {token}", file=sys.stderr)
    print(insert_sql(args.name, token, args.expected_gpus))
    return 0


if __name__ == "__main__":
    sys.exit(main())
