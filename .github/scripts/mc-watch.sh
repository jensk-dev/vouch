#!/usr/bin/env bash
# Opens a checklist issue when a new stable Minecraft is out AND the Fabric
# toolchain is ready for it (fabric-api build + loader support present).
# DRY_RUN=1 reports what would happen without touching GitHub.
set -euo pipefail

meta_game="https://meta.fabricmc.net/v2/versions/game"
meta_loader="https://meta.fabricmc.net/v2/versions/loader"
api_metadata="https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml"

current="$(sed -n 's/^minecraft = "\(.*\)"$/\1/p' gradle/libs.versions.toml)"
if [ -z "$current" ]; then
	echo "Could not read the tracked minecraft version from gradle/libs.versions.toml" >&2
	exit 1
fi

latest="$(curl -fsSL "$meta_game" | jq -r '[.[] | select(.stable)][0].version')"
if [ -z "$latest" ] || [ "$latest" = "null" ]; then
	echo "Could not read the latest stable version from Fabric meta" >&2
	exit 1
fi

if [ "$latest" = "$current" ]; then
	echo "Up to date: tracking Minecraft $current"
	exit 0
fi
if [ "$(printf '%s\n%s\n' "$current" "$latest" | sort -V | tail -n1)" != "$latest" ]; then
	echo "Tracked version $current is ahead of latest stable $latest; nothing to do"
	exit 0
fi

fabric_api="$(curl -fsSL "$api_metadata" | grep -oE '<version>[^<]+</version>' \
	| sed 's/<[^>]*>//g' | grep -F "+${latest}" | tail -n1 || true)"
if [ -z "$fabric_api" ]; then
	echo "Minecraft $latest is stable but there is no fabric-api build for it yet; waiting"
	exit 0
fi

loader_count="$(curl -fsSL "${meta_loader}/${latest}" | jq 'length')"
if [ "$loader_count" -eq 0 ]; then
	echo "Minecraft $latest is stable but fabric-loader does not support it yet; waiting"
	exit 0
fi

if [ "${DRY_RUN:-0}" = "1" ]; then
	echo "DRY RUN: would open an issue for Minecraft $latest (fabric-api $fabric_api, $loader_count loader builds)"
	exit 0
fi

if [ "$(gh issue list --label mc-update --state all --search "$latest in:title" --json number --jq length)" -gt 0 ]; then
	echo "An mc-update issue for $latest already exists; nothing to do"
	exit 0
fi

gh label create mc-update --color 1D76DB --description "New Minecraft version to port to" 2>/dev/null || true

gh issue create \
	--label mc-update \
	--title "Minecraft $latest is stable — port from $current" \
	--body "$(cat <<EOF
Fabric meta reports Minecraft **$latest** as stable, and the toolchain is ready for it:
$loader_count fabric-loader build(s) and fabric-api \`$fabric_api\`.

- [ ] \`gradle/libs.versions.toml\`: \`minecraft = "$latest"\`, \`fabric-api = "$fabric_api"\`
- [ ] Check for matching \`loom\` / \`fabric-loader\` updates: https://fabricmc.net/develop
- [ ] \`src/main/resources/fabric.mod.json\`: \`"minecraft": "~$latest"\`
- [ ] \`./gradlew build runGameTest\`
- [ ] Bump \`mod_version\` in \`gradle.properties\`, tag \`vX.Y.Z\`, push the tag to release

Opened automatically by \`mc-watch.yml\`. Closing this issue suppresses re-opening for $latest.
EOF
)"
echo "Opened an mc-update issue for Minecraft $latest"
