C_COMPILER     ?= gcc
BUILD_TYPE     ?= Release
INSTALL_PREFIX ?= /usr/local
CFLAGS         ?= '-Wextra -Wall -Wpedantic'

default: all

.PHONY: all build clean install unit-test jit-unit-test lsp-test lib-ktm release-unit-test

# Without an explicit rule, make's built-in `%: %.sh` would "build" a file
# called build by copying build.sh, which then blocks the ./build directory.
%: %.sh

# Same as ./build.sh (local prefix, no install); pass flags with ARGS="--test".
build:
	./build.sh $(ARGS)

# To add sqlite3 support add -DKTM_LINK_SQLITE3=1 to the below like so:
#```
#all:
#	cmake -S ./src -B ./build -G 'Unix Makefiles' \
#		-DCMAKE_C_COMPILER=$(_C_COMPILER) \
#		-DCMAKE_BUILD_TYPE=$(_BUILD_TYPE) \
#		-DKTM_LINK_SQLITE3=1 \
#		&& $(MAKE) -C ./build -j2
#```

all:
	cmake -S ./src \
		-B ./build \
		-G 'Unix Makefiles' \
		-DCMAKE_C_COMPILER=$(C_COMPILER) \
		-DCMAKE_BUILD_TYPE=$(BUILD_TYPE) \
		-DCMAKE_INSTALL_PREFIX=$(INSTALL_PREFIX) \
		-DCMAKE_C_FLAGS=$(CFLAGS) \
		-DCMAKE_EXPORT_COMPILE_COMMANDS=on \
		-DKTM_ENABLE_JIT=on \
		&& $(MAKE) -C ./build -j2

install:
	$(MAKE) -C ./build install

release-unit-test:
	$(MAKE) -C ./build unit-test

unit-test:
	cd ./src/tests && ../../ktm ./run.ktm -o test-runner && ./test-runner && cd ../../

jit-unit-test:
	cd ./src/tests && ../../ktm ./run_jit.ktm -o test-runner-jit && ./test-runner-jit && cd ../../

# Hermetic: builds libktm from the tree into a local prefix and
# compiles the (ktm) harness against it, so the suite tests in-tree
# sources - never whatever happens to be installed in /usr/local.
lsp-test:
	rm -rf ./build/test-prefix
	mkdir -p ./build/test-prefix/lib ./build/test-prefix/modules
	cp -R ./src/std/. ./build/test-prefix/modules/
	./ktm -fPIC -lib ktm --install-dir=$(CURDIR)/build/test-prefix $(CURDIR)/build/test-prefix/modules
	cd ./src/tests/lsp && ../../../ktm --install-dir=$(CURDIR)/build/test-prefix ./run_lsp_tests.ktm -o lsp-test-runner && ./lsp-test-runner

# The standard library's modules into $(LIB_PREFIX)/modules, compiled
# into $(LIB_PREFIX)/lib
LIB_PREFIX ?= /usr/local
lib-ktm:
	mkdir -p $(LIB_PREFIX)/lib $(LIB_PREFIX)/modules
	cp -R ./src/std/. $(LIB_PREFIX)/modules/
	./ktm -fPIC -lib ktm --install-dir=$(LIB_PREFIX) $(LIB_PREFIX)/modules

clean:
	rm -rf ./build ./ktm
