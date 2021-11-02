#!/bin/bash -e

set -x

coveralls=${1:-}
echo "============================================"
echo "Build projects"
echo "--------------------------------------------"
echo ""
export SOURCE_DATE_EPOCH=$(date +%s)
echo "SOURCE_DATE_EPOCH=$SOURCE_DATE_EPOCH"
if [[ "$CI_BRANCH" == "main" || "$CI_BRANCH" == "release" ]]
then
  sbt \
    -J-XX:MaxMetaspaceSize=1024m \
    -J-Xmx2048m \
    clean \
    test \
    packagedArtifacts
else
  sbt \
    -J-XX:MaxMetaspaceSize=1024m \
    -J-Xmx2048m \
    clean \
    test \
    package
fi

echo "============================================"
echo "Building projects: Done"
echo "============================================"
