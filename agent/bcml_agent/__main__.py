"""python3 -m bcml_agent [--config PATH] [--once]"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

from . import config, loop


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="bcml_agent")
    parser.add_argument("--config", type=Path, default=config.DEFAULT_PATH)
    parser.add_argument("--once", action="store_true",
                        help="collect one sample, send it once, exit 0 if the server accepted it")
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    try:
        cfg = config.load(args.config)
    except ValueError as e:
        logging.error("%s", e)
        return 2
    if args.once:
        return 0 if loop.run_once(cfg) else 1
    loop.run(cfg)
    return 0


if __name__ == "__main__":
    sys.exit(main())
