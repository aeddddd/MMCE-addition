#!/usr/bin/env bash
set -euo pipefail
addon_project=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
addon_local_jdk="$addon_project/../../.dev/jdk-25.0.5+8"
if [[ -n "${ADDON_JAVA_HOME:-}" ]]; then
    export JAVA_HOME="$ADDON_JAVA_HOME"
elif [[ -x "$addon_local_jdk/bin/java" ]]; then
    export JAVA_HOME="$addon_local_jdk"
fi
addon_proxy_args=()
# Java does not automatically honor the usual HTTP(S)_PROXY environment variables.
addon_proxy="${HTTPS_PROXY:-${https_proxy:-}}"
if [[ "$addon_proxy" =~ ^https?://([^/:@]+):([0-9]+)/?$ ]]; then
    addon_proxy_args+=("-Dhttps.proxyHost=${BASH_REMATCH[1]}" "-Dhttps.proxyPort=${BASH_REMATCH[2]}")
    addon_proxy_args+=("-Dhttp.proxyHost=${BASH_REMATCH[1]}" "-Dhttp.proxyPort=${BASH_REMATCH[2]}")
fi
cd -- "$addon_project"
exec ./gradlew "${addon_proxy_args[@]}" "$@"
