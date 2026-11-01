#!/bin/zsh
set -euo pipefail

script_dir="${0:A:h}"
project_dir="${script_dir:h}"
counter_file="$script_dir/IPA_BUILD_NUMBER"
app_path="${1:-/private/tmp/remoble-b1-device/Build/Products/Debug-iphoneos/Remoble.app}"

if [[ ! -d "$app_path" ]]; then
  print -u2 "App bundle not found: $app_path"
  exit 1
fi

current_number="$(tr -d '[:space:]' < "$counter_file")"
if [[ ! "$current_number" =~ '^[0-9]+$' ]]; then
  print -u2 "Invalid IPA build number: $current_number"
  exit 1
fi

next_number=$((current_number + 1))
output_dir="$project_dir/build"
output_path="$output_dir/Remoble-${next_number}.ipa"
if [[ -e "$output_path" ]]; then
  print -u2 "Refusing to overwrite existing IPA: $output_path"
  exit 1
fi

package_dir="$(mktemp -d /private/tmp/remoble-ipa.XXXXXX)"
mkdir -p "$package_dir/Payload" "$output_dir"
ditto "$app_path" "$package_dir/Payload/Remoble.app"
(
  cd "$package_dir"
  COPYFILE_DISABLE=1 zip -qry "$output_path" Payload
)
unzip -tq "$output_path"
printf '%s\n' "$next_number" > "$counter_file"

print "$output_path"
