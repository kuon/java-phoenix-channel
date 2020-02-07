#!/usr/bin/env bash

testdir=$(dirname "$0")

root=$(pwd)

cd ${testdir}/mock_server
mix phx.server &
PID=$!

sleep 2

ps -p $PID

if [ $? -ne 0 ]
then
  echo $PID
  echo "Cannot start mock server"
  exit 1
fi


cd ${root}

./gradlew test
RES=$?

kill $PID

exit $RES
