#!/bin/bash
EXIT_STATUS=0
./gradlew test || EXIT_STATUS=$?
if [ $EXIT_STATUS -ne 0 ]; then
  exit $EXIT_STATUS
fi
cd infra
cdk deploy --require-approval never --profile sraclara
cd ..
echo "deployed"
exit $EXIT_STATUS
