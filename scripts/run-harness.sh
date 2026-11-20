#!/usr/bin/env bash
set -euo pipefail

HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$HARNESS_DIR"

if [[ -z "${OMA_API_URL:-}" ]]; then
  echo "OMA_API_URL is required" >&2
  exit 1
fi

if [[ -z "${OMA_BRAIN_CLAIM_TOKEN:-}" ]]; then
  echo "OMA_BRAIN_CLAIM_TOKEN is required" >&2
  exit 1
fi

if [[ ! -d "$HARNESS_DIR/oma-harness-agentscope/target/classes" ]]; then
  mvn -B -q install -DskipTests
fi

mvn -B -q -pl oma-harness-agentscope dependency:build-classpath -Dmdep.outputFile=target/harness-cp.txt

CLASSPATH="$HARNESS_DIR/oma-harness-agentscope/target/classes:$HARNESS_DIR/oma-harness-core/target/classes:$(cat oma-harness-agentscope/target/harness-cp.txt)"

exec java -cp "$CLASSPATH" ai.oma.harness.agentscope.example.Main
