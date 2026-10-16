#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
javac -d "$TMP" tests/contracts/SyntaxCheck.java
mapfile -d '' SOURCES < <(find advanced-camera/src/main/java camera-app/src/main/java integration-example/src/main/java -name '*.java' -print0)
java -cp "$TMP" SyntaxCheck "${SOURCES[@]}"
javac -d "$TMP" tests/contracts/stubs/android/app/Activity.java tests/contracts/stubs/android/content/Intent.java tests/contracts/stubs/android/net/Uri.java advanced-camera/src/main/java/in/advancedcamera/sdk/CameraRequest.java advanced-camera/src/main/java/in/advancedcamera/sdk/CameraResult.java tests/contracts/java/in/advancedcamera/sdk/SmokeTest.java
java -cp "$TMP" in.advancedcamera.sdk.SmokeTest
