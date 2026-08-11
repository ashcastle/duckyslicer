#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=versions.env
source "$SCRIPT_DIR/versions.env"

UPSTREAM_ROOT="$REPOSITORY_ROOT/third_party/android-slicer-runtime"
WORK_ROOT="${DUCKYSLICER_NATIVE_BUILD_DIR:-$REPOSITORY_ROOT/build/native-slicer}"
SOURCE_ROOT="$WORK_ROOT/source"
DEPENDENCY_SOURCE_ROOT="$WORK_ROOT/dependency-sources"
BUILD_ROOT="$WORK_ROOT/build"
OUTPUT_ROOT="$WORK_ROOT/output/$ANDROID_ABI"
EXTERN_ROOT="$SOURCE_ROOT/app/src/main/cpp/extern"
JOBS="${DUCKYSLICER_NATIVE_JOBS:-2}"

die() {
    echo "error: $*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

host_toolchain() {
    local candidates=("$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*)
    [ "${#candidates[@]}" -eq 1 ] || die "expected exactly one NDK host toolchain"
    printf '%s\n' "${candidates[0]}"
}

checkout_commit() {
    local directory="$1"
    local repository="$2"
    local commit="$3"

    if [ ! -d "$directory/.git" ]; then
        mkdir -p "$(dirname "$directory")"
        git init "$directory"
        git -C "$directory" remote add origin "$repository"
    fi
    if ! git -C "$directory" cat-file -e "$commit^{commit}" 2>/dev/null; then
        git -C "$directory" fetch --depth 1 origin "$commit"
    fi
    if [ "$(git -C "$directory" rev-parse HEAD 2>/dev/null || true)" != "$commit" ] || \
        ! git -C "$directory" diff --quiet; then
        git -C "$directory" checkout --detach --force "$commit"
    fi
    [ "$(git -C "$directory" rev-parse HEAD)" = "$commit" ] || die "pin mismatch for $directory"
}

copy_if_changed() {
    local source="$1"
    local destination="$2"
    if [ ! -f "$destination" ] || ! cmp -s "$source" "$destination"; then
        cp "$source" "$destination"
    fi
}

download_checked() {
    local url="$1"
    local expected_sha="$2"
    local destination="$3"

    mkdir -p "$(dirname "$destination")"
    if [ ! -f "$destination" ] || [ "$(sha256_file "$destination")" != "$expected_sha" ]; then
        rm -f "$destination"
        curl --fail --location --retry 3 --output "$destination" "$url"
    fi
    [ "$(sha256_file "$destination")" = "$expected_sha" ] || die "checksum mismatch: $destination"
}

cmake_android() {
    local source_directory="$1"
    local build_directory="$2"
    shift 2
    cmake -S "$source_directory" -B "$build_directory" -GNinja \
        -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
        -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$ANDROID_ABI" \
        -DANDROID_PLATFORM="android-$ANDROID_API_LEVEL" \
        -DCMAKE_BUILD_TYPE=Release \
        "$@"
    cmake --build "$build_directory" --parallel "$JOBS"
}

prepare_runtime_source() {
    git -C "$UPSTREAM_ROOT" rev-parse --git-dir >/dev/null 2>&1 || \
        die "initialize submodules with: git submodule update --init"
    [ "$(git -C "$UPSTREAM_ROOT" rev-parse HEAD)" = "$ANDROID_SLICER_RUNTIME_COMMIT" ] || \
        die "android runtime submodule is not at its locked commit"

    if [ ! -e "$SOURCE_ROOT/.git" ]; then
        mkdir -p "$WORK_ROOT"
        git -C "$UPSTREAM_ROOT" worktree add --detach "$SOURCE_ROOT" "$ANDROID_SLICER_RUNTIME_COMMIT"
    fi
    [ "$(git -C "$SOURCE_ROOT" rev-parse HEAD)" = "$ANDROID_SLICER_RUNTIME_COMMIT" ] || \
        die "generated runtime worktree is stale; remove $WORK_ROOT and retry"

    git -C "$SOURCE_ROOT" submodule update --init --recursive app/src/main/cpp/orcaslicer
    [ "$(git -C "$SOURCE_ROOT/app/src/main/cpp/orcaslicer" rev-parse HEAD)" = "$SLICER_ENGINE_COMMIT" ] || \
        die "slicer engine submodule pin mismatch"

    if git -C "$SOURCE_ROOT" apply --check "$SCRIPT_DIR/runtime.patch" 2>/dev/null; then
        git -C "$SOURCE_ROOT" apply "$SCRIPT_DIR/runtime.patch"
    elif ! git -C "$SOURCE_ROOT" apply --reverse --check "$SCRIPT_DIR/runtime.patch" 2>/dev/null; then
        die "runtime source contains changes outside the reviewed DuckySlicer patch"
    fi

    mkdir -p "$EXTERN_ROOT/openssl_stub/include/openssl"
    mkdir -p "$EXTERN_ROOT/libpng_stub/include"
    copy_if_changed "$SCRIPT_DIR/overlay/openssl/md5.h" "$EXTERN_ROOT/openssl_stub/include/openssl/md5.h"
    copy_if_changed "$SCRIPT_DIR/overlay/png.h" "$EXTERN_ROOT/libpng_stub/include/png.h"
    copy_if_changed "$SCRIPT_DIR/overlay/sapil_model_export.cpp" \
        "$SOURCE_ROOT/app/src/main/cpp/src/sapil_model_export.cpp"
    copy_if_changed "$SCRIPT_DIR/overlay/sapil_model_simplify.cpp" \
        "$SOURCE_ROOT/app/src/main/cpp/src/sapil_model_simplify.cpp"
}

prepare_dependency_sources() {
    checkout_commit "$DEPENDENCY_SOURCE_ROOT/eigen" "$EIGEN_REPOSITORY" "$EIGEN_COMMIT"
    checkout_commit "$DEPENDENCY_SOURCE_ROOT/cereal" "$CEREAL_REPOSITORY" "$CEREAL_COMMIT"
    checkout_commit "$DEPENDENCY_SOURCE_ROOT/json" "$NLOHMANN_JSON_REPOSITORY" "$NLOHMANN_JSON_COMMIT"
    checkout_commit "$DEPENDENCY_SOURCE_ROOT/zlib" "$ZLIB_REPOSITORY" "$ZLIB_COMMIT"
    checkout_commit "$DEPENDENCY_SOURCE_ROOT/expat" "$EXPAT_REPOSITORY" "$EXPAT_COMMIT"
    checkout_commit "$DEPENDENCY_SOURCE_ROOT/clipper2" "$CLIPPER2_REPOSITORY" "$CLIPPER2_COMMIT"
    checkout_commit "$DEPENDENCY_SOURCE_ROOT/onetbb" "$ONETBB_REPOSITORY" "$ONETBB_COMMIT"
    checkout_commit "$DEPENDENCY_SOURCE_ROOT/boost-android" "$BOOST_ANDROID_REPOSITORY" "$BOOST_ANDROID_COMMIT"
    checkout_commit "$DEPENDENCY_SOURCE_ROOT/occt" "$OCCT_REPOSITORY" "$OCCT_COMMIT"
    checkout_commit "$DEPENDENCY_SOURCE_ROOT/nlopt" "$NLOPT_REPOSITORY" "$NLOPT_COMMIT"
    checkout_commit "$DEPENDENCY_SOURCE_ROOT/libjpeg-turbo" "$LIBJPEG_TURBO_REPOSITORY" "$LIBJPEG_TURBO_COMMIT"

    download_checked "$BOOST_ARCHIVE_URL" "$BOOST_ARCHIVE_SHA256" \
        "$DEPENDENCY_SOURCE_ROOT/boost-android/boost_1_84_0.tar.bz2"
    download_checked "$CGAL_URL" "$CGAL_SHA256" "$DEPENDENCY_SOURCE_ROOT/CGAL-5.6.tar.xz"
    download_checked "$GMP_URL" "$GMP_SHA256" "$DEPENDENCY_SOURCE_ROOT/gmp-6.3.0.tar.xz"
    download_checked "$MPFR_URL" "$MPFR_SHA256" "$DEPENDENCY_SOURCE_ROOT/mpfr-4.2.1.tar.xz"
}

copy_static_libraries() {
    local search_root="$1"
    local destination="$2"
    mkdir -p "$destination"
    find "$search_root" -type f -name '*.a' -exec cp {} "$destination/" \;
}

build_dependencies() {
    local lock_sha
    lock_sha="$(sha256_file "$SCRIPT_DIR/versions.env")"
    if [ -f "$EXTERN_ROOT/.duckyslicer-dependencies" ] && \
        [ "$(tr -d '\n' < "$EXTERN_ROOT/.duckyslicer-dependencies")" = "$lock_sha" ]; then
        echo "Native dependencies already match the lock file."
        return
    fi

    rm -rf "$EXTERN_ROOT/boost" "$EXTERN_ROOT/cereal" "$EXTERN_ROOT/cgal" \
        "$EXTERN_ROOT/clipper2" "$EXTERN_ROOT/eigen" "$EXTERN_ROOT/expat" \
        "$EXTERN_ROOT/gmp" "$EXTERN_ROOT/jpeg" "$EXTERN_ROOT/mpfr" \
        "$EXTERN_ROOT/nlohmann" "$EXTERN_ROOT/nlopt" "$EXTERN_ROOT/occt" \
        "$EXTERN_ROOT/tbb" "$EXTERN_ROOT/zlib"
    mkdir -p "$EXTERN_ROOT"

    mkdir -p "$EXTERN_ROOT/eigen/include" "$EXTERN_ROOT/cereal/include" "$EXTERN_ROOT/nlohmann/include"
    cp -R "$DEPENDENCY_SOURCE_ROOT/eigen/Eigen" "$DEPENDENCY_SOURCE_ROOT/eigen/unsupported" "$EXTERN_ROOT/eigen/include/"
    cp -R "$DEPENDENCY_SOURCE_ROOT/cereal/include/cereal" "$EXTERN_ROOT/cereal/include/"
    cp -R "$DEPENDENCY_SOURCE_ROOT/json/include/nlohmann" "$EXTERN_ROOT/nlohmann/include/"

    rm -rf "$BUILD_ROOT/cgal-source"
    mkdir -p "$BUILD_ROOT/cgal-source"
    tar -xf "$DEPENDENCY_SOURCE_ROOT/CGAL-5.6.tar.xz" -C "$BUILD_ROOT/cgal-source" --strip-components=1
    mkdir -p "$EXTERN_ROOT/cgal/include"
    cp -R "$BUILD_ROOT/cgal-source/include/CGAL" "$EXTERN_ROOT/cgal/include/"

    cmake_android "$DEPENDENCY_SOURCE_ROOT/zlib" "$BUILD_ROOT/zlib" \
        -DCMAKE_INSTALL_PREFIX="$EXTERN_ROOT/zlib" -DBUILD_SHARED_LIBS=OFF -DZLIB_BUILD_EXAMPLES=OFF
    cmake --install "$BUILD_ROOT/zlib"
    mkdir -p "$EXTERN_ROOT/zlib/lib/$ANDROID_ABI"
    cp "$EXTERN_ROOT/zlib/lib/libz.a" "$EXTERN_ROOT/zlib/lib/$ANDROID_ABI/"

    cmake_android "$DEPENDENCY_SOURCE_ROOT/expat/expat" "$BUILD_ROOT/expat" \
        -DCMAKE_INSTALL_PREFIX="$EXTERN_ROOT/expat" -DEXPAT_BUILD_DOCS=OFF \
        -DEXPAT_BUILD_EXAMPLES=OFF -DEXPAT_BUILD_FUZZERS=OFF -DEXPAT_BUILD_TESTS=OFF \
        -DEXPAT_BUILD_TOOLS=OFF -DEXPAT_SHARED_LIBS=OFF
    cmake --install "$BUILD_ROOT/expat"
    copy_static_libraries "$BUILD_ROOT/expat" "$EXTERN_ROOT/expat/lib/$ANDROID_ABI"

    cmake_android "$DEPENDENCY_SOURCE_ROOT/clipper2/CPP" "$BUILD_ROOT/clipper2" \
        -DCLIPPER2_EXAMPLES=OFF -DCLIPPER2_TESTS=OFF -DCLIPPER2_UTILS=OFF -DBUILD_SHARED_LIBS=OFF
    mkdir -p "$EXTERN_ROOT/clipper2/include"
    cp -R "$DEPENDENCY_SOURCE_ROOT/clipper2/CPP/Clipper2Lib/include/clipper2" "$EXTERN_ROOT/clipper2/include/"
    copy_static_libraries "$BUILD_ROOT/clipper2" "$EXTERN_ROOT/clipper2/lib/$ANDROID_ABI"

    cmake_android "$DEPENDENCY_SOURCE_ROOT/onetbb" "$BUILD_ROOT/onetbb" \
        -DTBB_TEST=OFF -DTBB_EXAMPLES=OFF -DBUILD_SHARED_LIBS=OFF
    mkdir -p "$EXTERN_ROOT/tbb/include"
    cp -R "$DEPENDENCY_SOURCE_ROOT/onetbb/include/tbb" "$DEPENDENCY_SOURCE_ROOT/onetbb/include/oneapi" "$EXTERN_ROOT/tbb/include/"
    copy_static_libraries "$BUILD_ROOT/onetbb" "$EXTERN_ROOT/tbb/lib/$ANDROID_ABI"

    build_gmp_and_mpfr
    build_boost
    build_occt

    build_nlopt
    build_jpeg

    printf '%s\n' "$lock_sha" > "$EXTERN_ROOT/.duckyslicer-dependencies"
}

build_nlopt() {
    rm -rf "$BUILD_ROOT/nlopt"
    cmake_android "$DEPENDENCY_SOURCE_ROOT/nlopt" "$BUILD_ROOT/nlopt" \
        -DCMAKE_INSTALL_PREFIX="$EXTERN_ROOT/nlopt" -DBUILD_SHARED_LIBS=OFF \
        -DNLOPT_CXX=OFF -DNLOPT_PYTHON=OFF -DNLOPT_OCTAVE=OFF -DNLOPT_MATLAB=OFF -DNLOPT_GUILE=OFF
    cmake --install "$BUILD_ROOT/nlopt"
}

build_jpeg() {
    rm -rf "$BUILD_ROOT/jpeg"
    cmake_android "$DEPENDENCY_SOURCE_ROOT/libjpeg-turbo" "$BUILD_ROOT/jpeg" \
        -DCMAKE_INSTALL_PREFIX="$EXTERN_ROOT/jpeg" \
        -DCMAKE_INSTALL_LIBDIR="$EXTERN_ROOT/jpeg/lib" \
        -DENABLE_SHARED=OFF -DENABLE_STATIC=ON -DWITH_SIMD=OFF -DWITH_JAVA=OFF
    cmake --install "$BUILD_ROOT/jpeg"
}

build_gmp_and_mpfr() {
    local toolchain target gmp_source mpfr_source
    toolchain="$(host_toolchain)"
    target=aarch64-linux-android
    gmp_source="$BUILD_ROOT/gmp-source"
    mpfr_source="$BUILD_ROOT/mpfr-source"

    rm -rf "$gmp_source" "$mpfr_source" "$BUILD_ROOT/gmp" "$BUILD_ROOT/mpfr"
    mkdir -p "$gmp_source" "$mpfr_source" "$BUILD_ROOT/gmp" "$BUILD_ROOT/mpfr"
    tar -xf "$DEPENDENCY_SOURCE_ROOT/gmp-6.3.0.tar.xz" -C "$gmp_source" --strip-components=1
    tar -xf "$DEPENDENCY_SOURCE_ROOT/mpfr-4.2.1.tar.xz" -C "$mpfr_source" --strip-components=1

    (
        cd "$BUILD_ROOT/gmp"
        AR="$toolchain/bin/llvm-ar" CC="$toolchain/bin/${target}${ANDROID_API_LEVEL}-clang" \
        CXX="$toolchain/bin/${target}${ANDROID_API_LEVEL}-clang++" RANLIB="$toolchain/bin/llvm-ranlib" \
        CFLAGS=-fPIC CXXFLAGS=-fPIC "$gmp_source/configure" --host="$target" \
            --disable-shared --enable-static --enable-cxx --with-pic --prefix="$EXTERN_ROOT/gmp"
        make -j"$JOBS"
        make install
    )
    (
        cd "$BUILD_ROOT/mpfr"
        AR="$toolchain/bin/llvm-ar" CC="$toolchain/bin/${target}${ANDROID_API_LEVEL}-clang" \
        CXX="$toolchain/bin/${target}${ANDROID_API_LEVEL}-clang++" RANLIB="$toolchain/bin/llvm-ranlib" \
        CFLAGS=-fPIC CXXFLAGS=-fPIC "$mpfr_source/configure" --host="$target" \
            --disable-shared --enable-static --with-pic --with-gmp="$EXTERN_ROOT/gmp" --prefix="$EXTERN_ROOT/mpfr"
        make -j"$JOBS"
        make install
    )
    mkdir -p "$EXTERN_ROOT/gmp/lib/$ANDROID_ABI" "$EXTERN_ROOT/mpfr/lib/$ANDROID_ABI"
    cp "$EXTERN_ROOT/gmp/lib/libgmp.a" "$EXTERN_ROOT/gmp/lib/libgmpxx.a" "$EXTERN_ROOT/gmp/lib/$ANDROID_ABI/"
    cp "$EXTERN_ROOT/mpfr/lib/libmpfr.a" "$EXTERN_ROOT/mpfr/lib/$ANDROID_ABI/"
}

build_boost() {
    local old_path boost_output boost_log library source_name target_name
    old_path="$PATH"
    export PATH="$(host_toolchain)/bin:$PATH"
    boost_log="$BUILD_ROOT/boost.log"
    if ! (
        cd "$DEPENDENCY_SOURCE_ROOT/boost-android"
        ./build-android.sh --boost=1.84.0 --arch="$ANDROID_ABI" \
            --with-libraries=system,filesystem,thread,log,regex,iostreams,nowide "$ANDROID_NDK_HOME"
    ) >"$boost_log" 2>&1; then
        tail -n 200 "$boost_log" >&2
        return 1
    fi
    export PATH="$old_path"

    boost_output="$DEPENDENCY_SOURCE_ROOT/boost-android/build/out/$ANDROID_ABI"
    mkdir -p "$EXTERN_ROOT/boost/include" "$EXTERN_ROOT/boost/lib/$ANDROID_ABI"
    cp -RL "$boost_output/include/boost-1_84/boost" "$EXTERN_ROOT/boost/include/"
    for library in "$boost_output"/lib/*.a; do
        source_name="$(basename "$library")"
        target_name="${source_name/-clang-darwin-mt-/-clang-mt-}"
        cp -L "$library" "$EXTERN_ROOT/boost/lib/$ANDROID_ABI/$target_name"
    done
}

build_occt() {
    cmake_android "$DEPENDENCY_SOURCE_ROOT/occt" "$BUILD_ROOT/occt" \
        -DBUILD_LIBRARY_TYPE=Static -DUSE_FREETYPE=OFF -DUSE_FREEIMAGE=OFF \
        -DUSE_OPENVR=OFF -DUSE_RAPIDJSON=OFF -DUSE_TBB=OFF -DUSE_VTK=OFF \
        -DBUILD_MODULE_Draw=OFF -DBUILD_MODULE_Visualization=OFF -DBUILD_DOC_Overview=OFF
    mkdir -p "$EXTERN_ROOT/occt/include"
    find "$DEPENDENCY_SOURCE_ROOT/occt/src" -type f \( -name '*.hxx' -o -name '*.h' \) -exec cp {} "$EXTERN_ROOT/occt/include/" \;
    copy_static_libraries "$BUILD_ROOT/occt" "$EXTERN_ROOT/occt/lib/$ANDROID_ABI"
}

build_runtime() {
    local runtime_build runtime_so output_so readelf elf_header sections load_count needed unexpected
    runtime_build="$BUILD_ROOT/runtime"
    cmake -S "$SOURCE_ROOT/app/src/main/cpp" -B "$runtime_build" -GNinja \
        -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
        -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$ANDROID_ABI" -DANDROID_PLATFORM="android-$ANDROID_API_LEVEL" \
        -DCMAKE_BUILD_TYPE=Release -DSLICER_BACKEND=orca -DANDROID_STL=c++_shared \
        -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384"
    cmake --build "$runtime_build" --parallel "$JOBS"

    runtime_so="$runtime_build/libprusaslicer-jni.so"
    output_so="$OUTPUT_ROOT/libprusaslicer-jni.so"
    readelf="$(host_toolchain)/bin/llvm-readelf"
    mkdir -p "$OUTPUT_ROOT"
    cp "$runtime_so" "$output_so"

    # Capture the complete output before matching. With `set -o pipefail`, piping
    # readelf directly into `grep -q` can report SIGPIPE after grep finds an early
    # match, turning a valid ELF into a false failure.
    elf_header="$("$readelf" -h "$output_so")"
    grep -q 'Machine:.*AArch64' <<< "$elf_header" || die "runtime is not AArch64"
    sections="$("$readelf" -S "$output_so")"
    grep -q '\.symtab' <<< "$sections" || die "runtime is missing its native symbol table"
    grep -q '\.debug_info' <<< "$sections" || die "runtime is missing full debug information"
    load_count="$("$readelf" -l "$output_so" | awk '$1 == "LOAD" { count += 1; if ($NF != "0x4000") bad += 1 } END { if (bad) exit 1; print count + 0 }')" || \
        die "runtime contains a LOAD segment without 16 KB alignment"
    [ "$load_count" -gt 0 ] || die "runtime has no LOAD segments"
    needed="$("$readelf" -d "$output_so" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')"
    unexpected="$(printf '%s\n' "$needed" | grep -Ev '^(libc\.so|libc\+\+_shared\.so|libdl\.so|liblog\.so|libm\.so)$' || true)"
    [ -z "$unexpected" ] || die "unexpected shared-library dependency: $unexpected"
    echo "Built $output_so with full symbols for Gradle release extraction"
    echo "SHA-256: $(sha256_file "$output_so")"
}

main() {
    require_command git
    require_command cmake
    require_command ninja
    require_command curl
    require_command tar
    require_command make
    require_command cmp

    : "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK $ANDROID_NDK_VERSION}"
    [ -f "$ANDROID_NDK_HOME/source.properties" ] || die "invalid ANDROID_NDK_HOME"
    grep -q "Pkg.Revision = $ANDROID_NDK_VERSION" "$ANDROID_NDK_HOME/source.properties" || \
        die "expected Android NDK $ANDROID_NDK_VERSION"

    prepare_runtime_source
    prepare_dependency_sources
    build_dependencies
    build_runtime
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    main "$@"
fi
