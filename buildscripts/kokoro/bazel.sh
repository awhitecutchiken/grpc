#!/bin/bash

set -exu -o pipefail
cat /VERSION

use_bazel.sh 0.12.0

cd github/grpc-java
bazel build ...

cd examples
bazel build ...
