#!/usr/bin/env bash

set -euo pipefail

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

copy_template() {
  local target="$1"

  mkdir -p "$target"
  tar \
    --exclude=.git \
    --exclude=.gradle \
    --exclude=build \
    --exclude=run \
    --exclude='.init-package-tree.*' \
    -C "$template_root" \
    -cf - . \
    | tar -C "$target" -xf -

  if [ -f "$template_root/run/options.txt" ]; then
    mkdir -p "$target/run"
    cp "$template_root/run/options.txt" "$target/run/options.txt"
  fi
}

assert_path_exists() {
  local path="$1"

  if [ ! -e "$path" ]; then
    echo "Expected path to exist: $path" >&2
    exit 1
  fi
}

assert_path_missing() {
  local path="$1"

  if [ -e "$path" ]; then
    echo "Expected path to be absent: $path" >&2
    exit 1
  fi
}

assert_no_match() {
  local pattern="$1"
  shift

  if rg -n "$pattern" "$@" >/tmp/template-smoke-rg.out; then
    cat /tmp/template-smoke-rg.out >&2
    exit 1
  fi
}

assert_match() {
  local pattern="$1"
  shift

  if ! rg -n "$pattern" "$@" >/tmp/template-smoke-rg.out; then
    echo "Expected pattern to match: $pattern" >&2
    cat /tmp/template-smoke-rg.out >&2
    exit 1
  fi
}

assert_json_string() {
  local file="$1"
  local filter="$2"
  local expected="$3"
  local actual

  actual=$(jq -r "$filter" "$file")
  if [ "$actual" != "$expected" ]; then
    echo "Expected $file $filter to be '$expected', got '$actual'" >&2
    exit 1
  fi
}

assert_json_compact() {
  local file="$1"
  local filter="$2"
  local expected="$3"
  local actual

  actual=$(jq -c "$filter" "$file")
  if [ "$actual" != "$expected" ]; then
    echo "Expected $file $filter to be '$expected', got '$actual'" >&2
    exit 1
  fi
}

assert_json_has_key() {
  local file="$1"
  local filter="$2"
  local key="$3"

  if ! jq -e "$filter | has(\"$key\")" "$file" >/dev/null; then
    echo "Expected $file $filter to contain key '$key'" >&2
    exit 1
  fi
}

assert_json_missing_key() {
  local file="$1"
  local filter="$2"
  local key="$3"

  if jq -e "$filter | has(\"$key\")" "$file" >/dev/null; then
    echo "Expected $file $filter not to contain key '$key'" >&2
    exit 1
  fi
}

smoke_side() {
  local side="$1"
  local target="$tmp_root/$side/FabricModdingTemplate-${side}_42"
  local package_dir="io/github/brainage04/fabricmoddingtemplate_${side}_42"
  local package_name="io.github.brainage04.fabricmoddingtemplate_${side}_42"
  local main_class="Fabricmoddingtemplate${side^}42"
  local mod_id="fabricmoddingtemplate_${side}_42"
  local -a build_args

  echo "Testing init.sh --side=${side}"
  copy_template "$target"

  (
    cd "$target"

    ./init.sh --side="$side" "FabricModdingTemplate-${side}_42"

    assert_path_missing "init.sh"
    assert_path_missing ".github/workflows/init.yml"
    assert_path_exists "src/main/java/${package_dir}/${main_class}.java"
    assert_path_exists "src/test/java/${package_dir}/${main_class}MetadataTest.java"
    assert_path_exists "src/main/resources/${mod_id}.accesswidener"
    assert_path_exists "src/main/resources/${mod_id}.mixins.json"
    assert_path_exists "src/main/resources/assets/${mod_id}/icon.png"

    assert_json_string src/main/resources/fabric.mod.json '.environment' "$(if [ "$side" = "client" ]; then printf client; else printf '*'; fi)"
    assert_json_string src/main/resources/fabric.mod.json '.entrypoints.main[0]' "${package_name}.${main_class}"
    assert_json_string src/main/resources/fabric.mod.json '.icon' "assets/${mod_id}/icon.png"
    assert_json_string src/main/resources/fabric.mod.json '.mixins[0]' "${mod_id}.mixins.json"
    assert_json_string src/main/resources/fabric.mod.json '.accessWidener' "${mod_id}.accesswidener"
    assert_json_string src/gametest/resources/fabric.mod.json '.environment' "$(if [ "$side" = "client" ]; then printf client; else printf '*'; fi)"
    assert_json_string src/gametest/resources/fabric.mod.json '.icon' "assets/${mod_id}/icon.png"

    if [ "$side" = "server" ]; then
      assert_path_missing "run/options.txt"
      assert_path_missing "src/client"
      assert_path_missing "src/gametest/java/${package_dir}/${main_class}ClientGameTest.java"
      assert_no_match 'client-gametests:|runClientGameTest' .github/workflows/build.yml
      assert_no_match 'prepareClientGameTestRun|CLIENT GAMETEST RUN SETUP' build.gradle
      assert_json_compact src/main/resources/fabric.mod.json '.entrypoints | keys_unsorted' '["main"]'
      assert_json_compact src/gametest/resources/fabric.mod.json '.entrypoints | keys_unsorted' '["fabric-gametest"]'
      assert_json_missing_key src/main/resources/fabric.mod.json '.entrypoints' client
      assert_json_has_key src/gametest/resources/fabric.mod.json '.entrypoints' fabric-gametest
      assert_json_missing_key src/gametest/resources/fabric.mod.json '.entrypoints' fabric-client-gametest
      assert_json_string src/gametest/resources/fabric.mod.json '.entrypoints["fabric-gametest"][0]' "${package_name}.${main_class}GameTest"
    else
      assert_path_exists "run/options.txt"
      assert_path_exists "src/client/java/${package_dir}/${main_class}Client.java"
      assert_path_exists "src/client/java/${package_dir}/command/ExampleClientCommand.java"
      assert_path_exists "src/client/java/${package_dir}/command/core/ClientModCommands.java"
      assert_path_exists "src/gametest/java/${package_dir}/${main_class}ClientGameTest.java"
      assert_path_exists "src/client/resources/assets/${mod_id}/lang/en_us.json"
      assert_match 'client-gametests:' .github/workflows/build.yml
      assert_match 'runClientGameTest' .github/workflows/build.yml
      assert_match 'prepareClientGameTestRun' build.gradle
      assert_json_compact src/main/resources/fabric.mod.json '.entrypoints | keys_unsorted' '["client","main"]'
      assert_json_has_key src/main/resources/fabric.mod.json '.entrypoints' client
      assert_json_string src/main/resources/fabric.mod.json '.entrypoints.client[0]' "${package_name}.${main_class}Client"
      assert_json_has_key src/gametest/resources/fabric.mod.json '.entrypoints' fabric-client-gametest
      assert_json_string src/gametest/resources/fabric.mod.json '.entrypoints["fabric-client-gametest"][0]' "${package_name}.${main_class}ClientGameTest"
    fi

    if [ "$side" = "client" ]; then
      assert_path_missing "src/main/java/${package_dir}/command/ExampleCommand.java"
      assert_path_missing "src/main/java/${package_dir}/command/core/ModCommands.java"
      assert_path_missing "src/test/java/${package_dir}/command/ExampleCommandTest.java"
      assert_path_missing "src/gametest/java/${package_dir}/${main_class}GameTest.java"
      assert_json_compact src/gametest/resources/fabric.mod.json '.entrypoints | keys_unsorted' '["fabric-client-gametest"]'
      assert_json_missing_key src/gametest/resources/fabric.mod.json '.entrypoints' fabric-gametest
      build_args=(build)
    elif [ "$side" = "server" ]; then
      assert_path_exists "src/main/java/${package_dir}/command/ExampleCommand.java"
      build_args=(build)
    else
      assert_path_exists "src/main/java/${package_dir}/command/ExampleCommand.java"
      assert_path_exists "src/gametest/java/${package_dir}/${main_class}GameTest.java"
      assert_json_compact src/gametest/resources/fabric.mod.json '.entrypoints | keys_unsorted' '["fabric-client-gametest","fabric-gametest"]'
      assert_json_has_key src/gametest/resources/fabric.mod.json '.entrypoints' fabric-gametest
      assert_json_string src/gametest/resources/fabric.mod.json '.entrypoints["fabric-gametest"][0]' "${package_name}.${main_class}GameTest"
      build_args=(build)
    fi

    grep -qx "maven_group=${package_name}" gradle.properties
    assert_no_match 'com\.example|io\.github\.brainage04\.fabricmoddingtemplate([^_a-z0-9]|$)|io/github/brainage04/fabricmoddingtemplate([^_a-z0-9]|$)|fabricmoddingtemplate\.(accesswidener|mixins\.json)|assets/fabricmoddingtemplate/icon\.png' README.md build.gradle gradle.properties LICENSE src
    assert_no_match 'package [^;]*-' src

    if [ "${TEMPLATE_SMOKE_SKIP_BUILD:-false}" = "true" ]; then
      echo "Skipping generated ${side} Gradle build."
    else
      ./gradlew --no-daemon "${build_args[@]}"
    fi
  )
}

require_command rg
require_command jq
require_command tar

template_root="$(cd "$(dirname "$0")/../.." && pwd)"
tmp_root="${TMPDIR:-/tmp}/fabric-template-smoke.$$"
trap 'rm -rf "$tmp_root" /tmp/template-smoke-rg.out' EXIT

smoke_side both
smoke_side server
smoke_side client

echo "Template generation smoke tests passed."
