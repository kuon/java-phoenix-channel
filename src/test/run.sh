#!/usr/bin/env bash

testdir=$(dirname "$0")

root=$(pwd)

cd ${testdir}/mock_server
mix phx.server &
PID=$!

sleep 2

cd ${root}

./gradlew test
RES=$?

kill $PID

exit $RES
