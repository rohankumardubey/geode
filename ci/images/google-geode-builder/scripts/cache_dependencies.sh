#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -e

WORK_DIR=$(mktemp -d)

export JAVA_HOME=/usr/lib/jvm/bellsoft-java${JAVA_BUILD_VERSION}-amd64
echo "JAVA_HOME is [${JAVA_HOME}]"

if [ -z ${JAVA_HOME} ]; then
  echo "JAVA_HOME [${JAVA_HOME}] does not exist. Quitting."
  exit 1
fi

pushd ${WORK_DIR}
  git clone -b support/1.14 --depth 1 https://github.com/apache/geode.git geode

  pushd geode
    ./gradlew --no-daemon --console=plain --info resolveDependencies
  popd
popd

rm -rf ${WORK_DIR}
