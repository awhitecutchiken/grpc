"""External dependencies for grpc-java."""

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
load("@bazel_tools//tools/build_defs/repo:jvm.bzl", "jvm_maven_import_external")

# For use with maven_install's override_targets.
# maven_install(
#     ...
#     override_targets = IO_GRPC_GRPC_JAVA_OVERRIDE_TARGETS,
# )
#
# If you have your own overrides as well, you can use:
#     override_targets = dict(
#         IO_GRPC_GRPC_JAVA_OVERRIDE_TARGETS,
#         "your.target:artifact": "@//third_party/artifact",
#     )
#
# To combine OVERRIDE_TARGETS from multiple libraries:
#     override_targets = dict(
#         IO_GRPC_GRPC_JAVA_OVERRIDE_TARGETS.items() +
#         OTHER_OVERRIDE_TARGETS.items(),
#         "your.target:artifact": "@//third_party/artifact",
#     )
IO_GRPC_GRPC_JAVA_OVERRIDE_TARGETS = {
    "io.grpc:grpc-alts": "@io_grpc_grpc_java//alts",
    "io.grpc:grpc-api": "@io_grpc_grpc_java//api",
    "io.grpc:grpc-auth": "@io_grpc_grpc_java//auth",
    "io.grpc:grpc-context": "@io_grpc_grpc_java//context",
    "io.grpc:grpc-core": "@io_grpc_grpc_java//core_maven",
    "io.grpc:grpc-grpclb": "@io_grpc_grpc_java//grpclb",
    "io.grpc:grpc-netty": "@io_grpc_grpc_java//netty",
    "io.grpc:grpc-netty-shaded": "@io_grpc_grpc_java//netty:shaded_maven",
    "io.grpc:grpc-okhttp": "@io_grpc_grpc_java//okhttp",
    "io.grpc:grpc-protobuf": "@io_grpc_grpc_java//protobuf",
    "io.grpc:grpc-protobuf-lite": "@io_grpc_grpc_java//protobuf-lite",
    "io.grpc:grpc-stub": "@io_grpc_grpc_java//stub",
    "io.grpc:grpc-testing": "@io_grpc_grpc_java//testing",
}

def grpc_java_repositories(
        omit_bazel_skylib = False,
        omit_com_google_android_annotations = False,
        omit_com_google_api_grpc_google_common_protos = False,
        omit_com_google_auth_google_auth_library_credentials = False,
        omit_com_google_auth_google_auth_library_oauth2_http = False,
        omit_com_google_code_findbugs_jsr305 = False,
        omit_com_google_code_gson = False,
        omit_com_google_errorprone_error_prone_annotations = False,
        omit_com_google_guava = False,
        omit_com_google_guava_failureaccess = False,
        omit_com_google_j2objc_j2objc_annotations = False,
        omit_com_google_protobuf = False,
        omit_com_google_protobuf_java = False,
        omit_com_google_protobuf_javalite = False,
        omit_com_google_truth_truth = False,
        omit_com_squareup_okhttp = False,
        omit_com_squareup_okio = False,
        omit_io_grpc_grpc_proto = False,
        omit_io_netty_buffer = False,
        omit_io_netty_common = False,
        omit_io_netty_transport = False,
        omit_io_netty_transport_native_epoll = False,
        omit_io_netty_codec = False,
        omit_io_netty_codec_socks = False,
        omit_io_netty_codec_http = False,
        omit_io_netty_codec_http2 = False,
        omit_io_netty_handler = False,
        omit_io_netty_handler_proxy = False,
        omit_io_netty_resolver = False,
        omit_io_netty_tcnative_boringssl_static = False,
        omit_io_opencensus_api = False,
        omit_io_opencensus_grpc_metrics = False,
        omit_io_perfmark = False,
        omit_javax_annotation = False,
        omit_junit_junit = False,
        omit_net_zlib = False,
        omit_org_apache_commons_lang3 = False,
        omit_org_codehaus_mojo_animal_sniffer_annotations = False):
    """Imports dependencies for grpc-java."""
    if not omit_bazel_skylib:
        bazel_skylib()
    if not omit_com_google_android_annotations:
        com_google_android_annotations()
    if not omit_com_google_api_grpc_google_common_protos:
        com_google_api_grpc_google_common_protos()
    if not omit_com_google_auth_google_auth_library_credentials:
        com_google_auth_google_auth_library_credentials()
    if not omit_com_google_auth_google_auth_library_oauth2_http:
        com_google_auth_google_auth_library_oauth2_http()
    if not omit_com_google_code_findbugs_jsr305:
        com_google_code_findbugs_jsr305()
    if not omit_com_google_code_gson:
        com_google_code_gson()
    if not omit_com_google_errorprone_error_prone_annotations:
        com_google_errorprone_error_prone_annotations()
    if not omit_com_google_guava:
        com_google_guava()
    if not omit_com_google_guava_failureaccess:
        com_google_guava_failureaccess()
    if not omit_com_google_j2objc_j2objc_annotations:
        com_google_j2objc_j2objc_annotations()
    if not omit_com_google_protobuf:
        com_google_protobuf()
    if omit_com_google_protobuf_java:
        fail("omit_com_google_protobuf_java is no longer supported and must be not be passed to grpc_java_repositories()")
    if not omit_com_google_protobuf_javalite:
        com_google_protobuf_javalite()
    if not omit_com_google_truth_truth:
        com_google_truth_truth()
    if not omit_com_squareup_okhttp:
        com_squareup_okhttp()
    if not omit_com_squareup_okio:
        com_squareup_okio()
    if not omit_io_grpc_grpc_proto:
        io_grpc_grpc_proto()
    if not omit_io_netty_buffer:
        io_netty_buffer()
    if not omit_io_netty_common:
        io_netty_common()
    if not omit_io_netty_transport:
        io_netty_transport()
    if not omit_io_netty_transport_native_epoll:
        io_netty_transport_native_epoll()
    if not omit_io_netty_codec:
        io_netty_codec()
    if not omit_io_netty_codec_socks:
        io_netty_codec_socks()
    if not omit_io_netty_codec_http:
        io_netty_codec_http()
    if not omit_io_netty_codec_http2:
        io_netty_codec_http2()
    if not omit_io_netty_handler:
        io_netty_handler()
    if not omit_io_netty_handler_proxy:
        io_netty_handler_proxy()
    if not omit_io_netty_resolver:
        io_netty_resolver()
    if not omit_io_netty_tcnative_boringssl_static:
        io_netty_tcnative_boringssl_static()
    if not omit_io_opencensus_api:
        io_opencensus_api()
    if not omit_io_opencensus_grpc_metrics:
        io_opencensus_grpc_metrics()
    if not omit_io_perfmark:
        io_perfmark()
    if not omit_javax_annotation:
        javax_annotation()
    if not omit_junit_junit:
        junit_junit()
    if not omit_net_zlib:
        net_zlib()
    if not omit_org_apache_commons_lang3:
        org_apache_commons_lang3()
    if not omit_org_codehaus_mojo_animal_sniffer_annotations:
        org_codehaus_mojo_animal_sniffer_annotations()

    native.bind(
        name = "guava",
        actual = "@com_google_guava_guava//jar",
    )
    native.bind(
        name = "gson",
        actual = "@com_google_code_gson_gson//jar",
    )
    native.bind(
        name = "error_prone_annotations",
        actual = "@com_google_errorprone_error_prone_annotations//jar",
    )

def bazel_skylib():
    http_archive(
        name = "bazel_skylib",
        sha256 = "bce240a0749dfc52fab20dce400b4d5cf7c28b239d64f8fd1762b3c9470121d8",
        strip_prefix = "bazel-skylib-0.7.0",
        urls = ["https://github.com/bazelbuild/bazel-skylib/archive/0.7.0.zip"],
    )

def com_google_android_annotations():
    jvm_maven_import_external(
        name = "com_google_android_annotations",
        artifact = "com.google.android:annotations:4.1.1.4",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "ba734e1e84c09d615af6a09d33034b4f0442f8772dec120efb376d86a565ae15",
        licenses = ["notice"],  # Apache 2.0
    )

def com_google_api_grpc_google_common_protos():
    jvm_maven_import_external(
        name = "com_google_api_grpc_proto_google_common_protos",
        artifact = "com.google.api.grpc:proto-google-common-protos:1.12.0",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "bd60cd7a423b00fb824c27bdd0293aaf4781be1daba6ed256311103fb4b84108",
        licenses = ["notice"],  # Apache 2.0
    )

def com_google_auth_google_auth_library_credentials():
    jvm_maven_import_external(
        name = "com_google_auth_google_auth_library_credentials",
        artifact = "com.google.auth:google-auth-library-credentials:0.17.1",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "aaeea9333fff9b763715bca0174ec76c4f9551b5731c89a95f263cdc82b4b56e",
        licenses = ["notice"],  # BSD 3-clause
    )

def com_google_auth_google_auth_library_oauth2_http():
    jvm_maven_import_external(
        name = "com_google_auth_google_auth_library_oauth2_http",
        artifact = "com.google.auth:google-auth-library-oauth2-http:0.17.1",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "fa9a1589c8bc279416988d437c2636967cd5e4eff70fbddc986b9c5a77b0231b",
        licenses = ["notice"],  # BSD 3-clause
    )

def com_google_code_findbugs_jsr305():
    jvm_maven_import_external(
        name = "com_google_code_findbugs_jsr305",
        artifact = "com.google.code.findbugs:jsr305:3.0.2",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "766ad2a0783f2687962c8ad74ceecc38a28b9f72a2d085ee438b7813e928d0c7",
        licenses = ["notice"],  # Apache 2.0
    )

def com_google_code_gson():
    jvm_maven_import_external(
        name = "com_google_code_gson_gson",
        artifact = "com.google.code.gson:gson:jar:2.8.5",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "233a0149fc365c9f6edbd683cfe266b19bdc773be98eabdaf6b3c924b48e7d81",
        licenses = ["notice"],  # Apache 2.0
    )

def com_google_errorprone_error_prone_annotations():
    jvm_maven_import_external(
        name = "com_google_errorprone_error_prone_annotations",
        artifact = "com.google.errorprone:error_prone_annotations:2.3.3",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "ec59f1b702d9afc09e8c3929f5c42777dec623a6ea2731ac694332c7d7680f5a",
        licenses = ["notice"],  # Apache 2.0
    )

def com_google_guava():
    jvm_maven_import_external(
        name = "com_google_guava_guava",
        artifact = "com.google.guava:guava:28.1-android",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "e112ce92c0f0733965eede73d94589c59a72128b06b08bba5ebe2f9ea672ef60",
        licenses = ["notice"],  # Apache 2.0
    )

def com_google_guava_failureaccess():
    # Not needed until Guava 27.0, but including now to ease upgrading of users. See #5214
    jvm_maven_import_external(
        name = "com_google_guava_failureaccess",
        artifact = "com.google.guava:failureaccess:1.0.1",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "a171ee4c734dd2da837e4b16be9df4661afab72a41adaf31eb84dfdaf936ca26",
        licenses = ["notice"],  # Apache 2.0
    )

def com_google_j2objc_j2objc_annotations():
    jvm_maven_import_external(
        name = "com_google_j2objc_j2objc_annotations",
        artifact = "com.google.j2objc:j2objc-annotations:1.3",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "21af30c92267bd6122c0e0b4d20cccb6641a37eaf956c6540ec471d584e64a7b",
        licenses = ["notice"],  # Apache 2.0
    )

def com_google_protobuf():
    # proto_library rules implicitly depend on @com_google_protobuf//:protoc,
    # which is the proto-compiler.
    # This statement defines the @com_google_protobuf repo.
    http_archive(
        name = "com_google_protobuf",
        sha256 = "33cba8b89be6c81b1461f1c438424f7a1aa4e31998dbe9ed6f8319583daac8c7",
        strip_prefix = "protobuf-3.10.0",
        urls = ["https://github.com/protocolbuffers/protobuf/archive/v3.10.0.zip"],
    )

def com_google_protobuf_javalite():
    # java_lite_proto_library rules implicitly depend on @com_google_protobuf_javalite
    http_archive(
        name = "com_google_protobuf_javalite",
        sha256 = "79d102c61e2a479a0b7e5fc167bcfaa4832a0c6aad4a75fa7da0480564931bcc",
        strip_prefix = "protobuf-384989534b2246d413dbcd750744faab2607b516",
        urls = ["https://github.com/google/protobuf/archive/384989534b2246d413dbcd750744faab2607b516.zip"],
    )

def com_google_truth_truth():
    jvm_maven_import_external(
        name = "com_google_truth_truth",
        artifact = "com.google.truth:truth:1.0",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "edaa12f3b581fcf1c07311e94af8766919c4f3d904b00d3503147b99bf5b4004",
        licenses = ["notice"],  # Apache 2.0
    )

def com_squareup_okhttp():
    jvm_maven_import_external(
        name = "com_squareup_okhttp_okhttp",
        artifact = "com.squareup.okhttp:okhttp:2.5.0",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "1cc716e29539adcda677949508162796daffedb4794cbf947a6f65e696f0381c",
        licenses = ["notice"],  # Apache 2.0
    )

def com_squareup_okio():
    jvm_maven_import_external(
        name = "com_squareup_okio_okio",
        artifact = "com.squareup.okio:okio:1.13.0",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "734269c3ebc5090e3b23566db558f421f0b4027277c79ad5d176b8ec168bb850",
        licenses = ["notice"],  # Apache 2.0
    )

def io_grpc_grpc_proto():
    http_archive(
        name = "io_grpc_grpc_proto",
        sha256 = "873f3fdec7ed052f899aef83fc897926729713d96d7ccdb2df22843dc702ef3a",
        strip_prefix = "grpc-proto-96ecba6941c67b1da2af598330c60cf9b0336051",
        urls = ["https://github.com/grpc/grpc-proto/archive/96ecba6941c67b1da2af598330c60cf9b0336051.zip"],
    )

def io_netty_buffer():
    jvm_maven_import_external(
        name = "io_netty_netty_buffer",
        artifact = "io.netty:netty-buffer:4.1.38.Final",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "a3dcb49108f83b195e66cdad70b2d4a127c17f1be7f5b228a88ce18908c30b3e",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_codec():
    jvm_maven_import_external(
        name = "io_netty_netty_codec",
        artifact = "io.netty:netty-codec:4.1.38.Final",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "8a4cb96e2131eeb8a824014f0ee338b11c6041405446acf73181199ed05744ac",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_codec_http():
    jvm_maven_import_external(
        name = "io_netty_netty_codec_http",
        artifact = "io.netty:netty-codec-http:4.1.38.Final",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "b35c4ac78ed3aaefeb782965ce9f22901e5302bf5e75b75fbed79434ce007e8c",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_codec_http2():
    jvm_maven_import_external(
        name = "io_netty_netty_codec_http2",
        artifact = "io.netty:netty-codec-http2:4.1.38.Final",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "7e2d792407d2da34d3338a3e8f8ed421570fdbf845941b8ee0aa952fe0e07026",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_codec_socks():
    jvm_maven_import_external(
        name = "io_netty_netty_codec_socks",
        artifact = "io.netty:netty-codec-socks:4.1.38.Final",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "16d932d76d06992923ce640609d297fe35d9f7b219ea5f0514a4259f1f1f2146",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_common():
    jvm_maven_import_external(
        name = "io_netty_netty_common",
        artifact = "io.netty:netty-common:4.1.38.Final",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "710df7c27fe5ba4b15689ae10668cd10ab3b618a1291f3a47b2cc292a0fa67da",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_handler():
    jvm_maven_import_external(
        name = "io_netty_netty_handler",
        artifact = "io.netty:netty-handler:4.1.38.Final",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "b49b57dbdc88a2c77e3ea9b9d00d3136f28771e059b74a7be7458d7a86bfccd1",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_handler_proxy():
    jvm_maven_import_external(
        name = "io_netty_netty_handler_proxy",
        artifact = "io.netty:netty-handler-proxy:4.1.38.Final",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "9dab309a0422dd9858f431d503b58b854b37d2545c50ad7b4771f34d2288e5c0",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_resolver():
    jvm_maven_import_external(
        name = "io_netty_netty_resolver",
        artifact = "io.netty:netty-resolver:4.1.38.Final",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "3692c12a0035e566d5cd1dc1529d4f61725304c5e88817ae78b5c2f7f6d86cad",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_tcnative_boringssl_static():
    jvm_maven_import_external(
        name = "io_netty_netty_tcnative_boringssl_static",
        artifact = "io.netty:netty-tcnative-boringssl-static:2.0.25.Final",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "96d9c14ab4c47cbad7fec9bdb083917db971d3754d6c7fa89f958bc719e230ed",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_transport():
    jvm_maven_import_external(
        name = "io_netty_netty_transport",
        artifact = "io.netty:netty-transport:4.1.38.Final",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "5f826976585a49aae9b495290125a60a59dc6887fbe4c70da3182a83fb8bfa88",
        licenses = ["notice"],  # Apache 2.0
    )

def io_netty_transport_native_epoll():
    jvm_maven_import_external(
        name = "io_netty_netty_transport_native_epoll",
        artifact = "io.netty:netty-transport-native-epoll:jar:linux-x86_64:4.1.38.Final",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "7933467e1cfc37bc6fb3f22af471ed69cb66bebaceab73d2041772bb6a38218a",
        licenses = ["notice"],  # Apache 2.0
    )

def io_opencensus_api():
    jvm_maven_import_external(
        name = "io_opencensus_opencensus_api",
        artifact = "io.opencensus:opencensus-api:0.21.0",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "8e2cb0f6391d8eb0a1bcd01e7748883f0033b1941754f4ed3f19d2c3e4276fc8",
        licenses = ["notice"],  # Apache 2.0
    )

def io_opencensus_grpc_metrics():
    jvm_maven_import_external(
        name = "io_opencensus_opencensus_contrib_grpc_metrics",
        artifact = "io.opencensus:opencensus-contrib-grpc-metrics:0.21.0",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "29fc79401082301542cab89d7054d2f0825f184492654c950020553ef4ff0ef8",
        licenses = ["notice"],  # Apache 2.0
    )

def io_perfmark():
    jvm_maven_import_external(
        name = "io_perfmark_perfmark_api",
        artifact = "io.perfmark:perfmark-api:0.17.0",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "816c11409b8a0c6c9ce1cda14bed526e7b4da0e772da67c5b7b88eefd41520f9",
        licenses = ["notice"],  # Apache 2.0
    )

def javax_annotation():
    # Use //stub:javax_annotation for neverlink=1 support.
    jvm_maven_import_external(
        name = "javax_annotation_javax_annotation_api",
        artifact = "javax.annotation:javax.annotation-api:1.2",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "5909b396ca3a2be10d0eea32c74ef78d816e1b4ead21de1d78de1f890d033e04",
        licenses = ["reciprocal"],  # CDDL License
    )

def junit_junit():
    jvm_maven_import_external(
        name = "junit_junit",
        artifact = "junit:junit:4.12",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "59721f0805e223d84b90677887d9ff567dc534d7c502ca903c0c2b17f05c116a",
        licenses = ["notice"],  # EPL 1.0
    )

def net_zlib():
    http_archive(
        name = "net_zlib",
        build_file = "@com_google_protobuf//:third_party/zlib.BUILD",
        sha256 = "c3e5e9fdd5004dcb542feda5ee4f0ff0744628baf8ed2dd5d66f8ca1197cb1a1",
        strip_prefix = "zlib-1.2.11",
        urls = ["https://zlib.net/zlib-1.2.11.tar.gz"],
    )

def org_apache_commons_lang3():
    jvm_maven_import_external(
        name = "org_apache_commons_commons_lang3",
        artifact = "org.apache.commons:commons-lang3:3.5",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "8ac96fc686512d777fca85e144f196cd7cfe0c0aec23127229497d1a38ff651c",
        licenses = ["notice"],  # Apache 2.0
    )

def org_codehaus_mojo_animal_sniffer_annotations():
    jvm_maven_import_external(
        name = "org_codehaus_mojo_animal_sniffer_annotations",
        artifact = "org.codehaus.mojo:animal-sniffer-annotations:1.17",
        server_urls = ["http://central.maven.org/maven2"],
        artifact_sha256 = "92654f493ecfec52082e76354f0ebf87648dc3d5cec2e3c3cdb947c016747a53",
        licenses = ["notice"],  # MIT
    )
