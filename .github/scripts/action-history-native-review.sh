#!/usr/bin/env bash
set -uo pipefail
cd app
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.chatbar.ui.chat.ChatHistoryActionWireTest --no-daemon --stacktrace
native_result=$?
cd ..
mkdir -p action-history-review/wire
adb exec-out run-as com.example.chatbar.actionhistory.r1 tar -C files -cf - action-history-wire > action-history-review/wire/originals.tar
wire_result=$?
if [ "$native_result" -ne 0 ]; then
  exit "$native_result"
fi
exit "$wire_result"
