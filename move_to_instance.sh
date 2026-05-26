#!/bin/bash
# Build homunculus and distribute the jar to every PrismLauncher 1.21.4* instance.
#
# Replaces the old laptop move_to_instance.sh (which hardcoded agent0..9 +
# /home/toast paths). This globs every instance under PrismLauncher matching
# "1.21.4*" that has a minecraft/mods dir, so it auto-covers however many agents
# exist (0..31) on whatever host.
#
# Usage:
#   ./move_to_instance.sh            # gradle build, then distribute
#   ./move_to_instance.sh --no-build # distribute the existing build/libs jar
#
# NOTE: MC clients load the mod jar at JVM start — running agents must be
# RELAUNCHED to pick up a fresh build.
set -euo pipefail
cd "$(dirname "$0")"

JAR="build/libs/homunculus-0.1.0.jar"
XDG="${XDG_DATA_HOME:-$HOME/.local/share}"
# Two homes for instances:
#   1. Canonical PrismLauncher instances (1.21.4.agent0 is the template
#      launch_agent.sh copies new per-agent roots from).
#   2. Per-agent isolated roots ($XDG/pl-agents/agentN) — where launch_agent.sh
#      actually RUNS each agent. These are `cp -a` copies guarded by a .built
#      flag, so they don't re-pull from the template; they MUST be updated here
#      directly or the running fleet keeps the stale jar.
CANON_INSTANCES="$XDG/PrismLauncher/instances"
AGENT_ROOTS="${PRISM_AGENT_ROOTS:-$XDG/pl-agents}"

if [[ "${1:-}" != "--no-build" ]]; then
  echo "[build] ./gradlew build"
  ./gradlew build
fi

[[ -f "$JAR" ]] || { echo "ERROR: $JAR missing — build failed?"; exit 1; }
echo "[jar] $JAR ($(stat -c%s "$JAR") bytes, built $(stat -c%y "$JAR" | cut -d. -f1))"

n=0
shopt -s nullglob
for mods in \
    "$CANON_INSTANCES"/1.21.4*/minecraft/mods \
    "$AGENT_ROOTS"/agent*/instances/1.21.4*/minecraft/mods; do
  [[ -d "$mods" ]] || continue
  cp "$JAR" "$mods/"
  # Print the instance dir (two levels up from minecraft/mods) for legibility.
  inst="${mods%/minecraft/mods}"
  echo "  -> ${inst}"
  n=$((n + 1))
done
echo "[deploy] copied to $n mods dir(s). Relaunch agents to load the new jar."
