# Upstream contract extraction

Dev-only tooling that captures the upstream Python server's tool contracts. Nothing here
ships in the Java build or image; Java tests only read the generated files under
`contracts/`.

| Script | Needs upstream env | Output |
|---|---|---|
| `extract.py` | yes | `contracts/upstream-tools.json` — names, descriptions, tags, annotations, input/output schemas, LibreNMS HTTP operations |
| `capture.py` | yes | `contracts/transcripts/<tool>.json` — golden request/response transcripts per scenario |
| `capture_tests.py` | yes (+ pytest) | `contracts/upstream-tests/*.json` — every tool call in upstream's pytest suites, with scripted LibreNMS answers |
| `capture_search.py` | yes | `contracts/tool-search.json` — search tool definitions and recorded BM25/regex rankings |
| `capture_live.py` | yes + test LibreNMS | `contracts/live/<tool>.json` — sanitized transcripts of READ tools against a real instance (GET only, guarded) |
| `gen_matrix.py` | no (stdlib) | `docs/parity-matrix.md` from the two files above + `contracts/tool-classification.json` |

`contracts/tool-classification.json` (access level, destructive flag, retry safety, Java status)
and `scenarios.json` (argument overrides and extra scenarios) are hand-maintained.

## Regenerating

Requires Python 3.11+. The upstream commit is pinned in `docs/upstream-baseline.md`.

```sh
git clone https://github.com/mhajder/librenms-mcp /tmp/librenms-mcp
git -C /tmp/librenms-mcp checkout 09ba2f6d393d199ff6bb0b0fdaa824da0f909274
python3.14 -m venv /tmp/librenms-mcp-venv
/tmp/librenms-mcp-venv/bin/pip install /tmp/librenms-mcp -r tools/upstream-extract/requirements.txt

/tmp/librenms-mcp-venv/bin/python -I tools/upstream-extract/extract.py \
    --upstream /tmp/librenms-mcp --out contracts/upstream-tools.json
/tmp/librenms-mcp-venv/bin/python -I tools/upstream-extract/capture.py
/tmp/librenms-mcp-venv/bin/python -I tools/upstream-extract/capture_tests.py --upstream /tmp/librenms-mcp
/tmp/librenms-mcp-venv/bin/python -I tools/upstream-extract/capture_search.py
python3 tools/upstream-extract/gen_matrix.py

# Live capture: token from LIBRENMS_TOKEN / LIBRENMS_API_KEY or the .env file.
/tmp/librenms-mcp-venv/bin/python -I tools/upstream-extract/capture_live.py \
    --url http://librenms.hex42labs.ca --out /tmp/live
# Review /tmp/live (sanitization is heuristic), then copy it to contracts/live.
```

Capture is deterministic: rerunning against the same commit produces byte-identical files, so
any diff after bumping the upstream pin is a real contract change.

## Updating the upstream pin

1. Check out the new commit and rerun all three scripts.
2. `gen_matrix.py` fails on any new or removed tool until `tool-classification.json` is updated.
3. Review the diff of `contracts/` — every schema, request or result change needs a matching
   Java change or an approved deviation in `docs/parity-matrix.md`.
4. Update the commit in `docs/upstream-baseline.md` and this README.
