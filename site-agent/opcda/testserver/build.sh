#!/bin/sh
# TEST ONLY: builds the 32-bit BIMBA OPC DA test server DLL with MinGW-w64 (run in a Debian container):
#   docker run --rm -v "<this folder>:/w" -w /w debian:bookworm sh build.sh
set -eu
if ! command -v i686-w64-mingw32-g++ >/dev/null; then
    apt-get update -qq && apt-get install -y -qq --no-install-recommends g++-mingw-w64-i686 >/dev/null
fi
i686-w64-mingw32-g++ -std=c++17 -O2 -shared -static -static-libgcc -static-libstdc++ -municode \
    -o BimbaOpcDaTest.dll opcda_test_server.cpp -Wl,--kill-at -lole32 -loleaut32 -luuid -ladvapi32
ls -l BimbaOpcDaTest.dll
