#!/usr/bin/env bash
set -uo pipefail
cd app
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.chatbar.ui.chat.ChatHistoryActionWireTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true --no-daemon --stacktrace
native_result=$?
cd ..
mkdir -p action-history-review/wire
adb exec-out run-as com.example.chatbar.actionhistory.r1 tar -C files -cf - action-history-wire > action-history-review/wire/originals.tar
wire_result=$?
python - <<'CHECK_WIRE'
import json,tarfile
with tarfile.open('action-history-review/wire/originals.tar') as archive:
    rows=[json.load(archive.extractfile(member)) for member in archive.getmembers() if member.isfile() and member.name.endswith('.json')]
    assert len(rows)==25, len(rows)
    assert all(row.get('stage')=='COLD_SOURCE_VERIFIED' for row in rows)
print('Verified 25 actual serialized requests and cold source pairs')
CHECK_WIRE
validation_result=$?
if [ "$native_result" -ne 0 ]; then
  exit "$native_result"
fi
if [ "$wire_result" -ne 0 ]; then
  exit "$wire_result"
fi
exit "$validation_result"
