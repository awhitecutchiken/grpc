#!/bin/bash

set -exu -o pipefail
cat /VERSION
bazel version

use_bazel.sh 0.19.0

cd github/grpc-java
bazel build ...

cd examples
bazel clean
bazel build ...
