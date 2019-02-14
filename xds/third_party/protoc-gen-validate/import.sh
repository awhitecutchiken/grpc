#!/bin/bash
# Copyright 2018 The gRPC Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Update GIT_ORIGIN_REV_ID then in this directory run ./import.sh

set -e
BRANCH=master
# import GIT_ORIGIN_REV_ID from one of the google internal CLs
GIT_ORIGIN_REV_ID=8e6aaf55f4954f1ef9d3ee2e8f5a50e79cc04f8f
GIT_REPO="https://github.com/lyft/protoc-gen-validate.git"
GIT_BASE_DIR=protoc-gen-validate
SOURCE_PROTO_BASE_DIR=protoc-gen-validate
TARGET_PROTO_BASE_DIR=src/main/proto
FILES=(
validate/validate.proto
)

# clone the protoc-gen-validate github repo in a tmp directory
tmpdir="$(mktemp -d)"
pushd "${tmpdir}" || exit 1
rm -rf "$GIT_BASE_DIR"
git clone -b $BRANCH $GIT_REPO
cd "$GIT_BASE_DIR" || exit 1
git checkout $GIT_ORIGIN_REV_ID
popd || exit 1

cp -p "${tmpdir}/${GIT_BASE_DIR}/LICENSE" LICENSE

mkdir -p "${TARGET_PROTO_BASE_DIR}"
pushd "${TARGET_PROTO_BASE_DIR}" || exit 1

# copy proto files to project directory
for file in "${FILES[@]}"
do
  mkdir -p "$(dirname "${file}")"
  cp -p "${tmpdir}/${SOURCE_PROTO_BASE_DIR}/${file}" "${file}"
done
popd || exit 1

rm -rf "$tmpdir"
