cmake_minimum_required(VERSION 3.10)

project(ktm)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED True)
set(CMAKE_C_STANDARD_REQUIRED True)
set(CMAKE_C_STANDARD 11)
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ../)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Choose the type of build (Debug or Release)" FORCE)
endif()

add_definitions(-DINSTALL_PREFIX="${CMAKE_INSTALL_PREFIX}")

# Add debugging symbols for Debug builds
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -g")
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_compile_definitions(DEBUG)
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fsanitize=address")
    set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -fsanitize=address")
endif()

if (KTM_LINK_SQLITE3)
    add_compile_definitions(KTM_LINK_SQLITE3)
endif()

execute_process(
  COMMAND git rev-parse HEAD
  WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}
  OUTPUT_VARIABLE KTM_GIT_HASH
  OUTPUT_STRIP_TRAILING_WHITESPACE
)

add_compile_definitions(KTM_GIT_HASH="${KTM_GIT_HASH}")

# Optionally, add more flags or options depending on the build type
# For example, optimization flags for Release builds:
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -O2")

set(SOURCES
    asm.c
    aostr.c
    arena.c
    ast.c
    cctrl.c
    cfg-print.c
    cfg.c
    cli.c
    containers.c
    compile.c
    aarch64.c
    ir.c
    ir-debug.c
    ir-eval.c
    ir-optimise.c
    ir-regalloc.c
    ir-types.c
    json.c
    lexer.c
    list.c
    lsp.c
    main.c
    memory.c
    mempool.c
    parser.c
    pkg.c
    pkgfetch.c
    pkgtest.c
    prsasm.c
    prslib.c
    prsutil.c
    x86_64.c
)

set(HEADERS
    aostr.h
    arena.h
    asm.h
    ast.h
    cctrl.h
    cfg-print.h
    cfg.h
    cli.h
    compile.h
    config.h
    json.h
    lsp.h
    containers.h
    ir.h
    ir-debug.h
    ir-eval.h
    ir-regalloc.h
    ir-types.h
    lexer.h
    list.h
    memory.h
    mempool.h
    parser.h
    prsasm.h
    prslib.h
    prsutil.h
    util.h
    version.h
    x86_64.h
)

add_subdirectory(asm)

option(KTM_ENABLE_JIT "Build the optional in-process JIT (aarch64 & x86_64)" OFF)
if (KTM_ENABLE_JIT)
    list(APPEND SOURCES jit-common.c aarch64-jit.c x86_64-jit.c repl.c
         memsafe.c linenoise/linenoise.c)
    list(APPEND HEADERS jit-common.h aarch64-jit.h x86_64-jit.h repl.h
         memsafe.h linenoise/linenoise.h)
    # Vendored (antirez/linenoise) - don't hold it to our warning bar.
    set_source_files_properties(linenoise/linenoise.c
                                PROPERTIES COMPILE_FLAGS "-w")
    # memsafe names a report's call site by walking the frame-pointer
    # chain from its wrappers back into JIT code; -O2 drops it.
    set_source_files_properties(memsafe.c
                                PROPERTIES COMPILE_FLAGS "-fno-omit-frame-pointer")
endif()

add_executable(ktm ${SOURCES} ${HEADERS})

if (KTM_ENABLE_JIT)
    target_compile_definitions(ktm PRIVATE KTM_ENABLE_JIT)
    target_link_libraries(ktm tasm ${CMAKE_DL_LIBS})
endif()

set(KTM_PATH "${CMAKE_INSTALL_PREFIX}/bin/ktm")

# Command for unit tests
add_custom_target(unit-test
    COMMAND ${CMAKE_COMMAND} -E echo "Running unit tests..."
    COMMAND ${KTM_PATH} -o test-runner ${CMAKE_SOURCE_DIR}/tests/run.ktm
    COMMAND ${CMAKE_SOURCE_DIR}/tests/test-runner
    WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}/tests
    COMMENT "Building and running unit tests"
    USES_TERMINAL
)

target_link_libraries(ktm m)

# net.tls: mbedTLS (vendor/mbedtls, unchanged) and ktm's layer over it
# (vendor/ktmtls), as libktmtls: the static library programs that import
# net.tls link, and a shared one the JIT loads. build.sh puts both next to
# libktm.
file(GLOB KTMTLS_MBEDTLS_SOURCES ${CMAKE_SOURCE_DIR}/vendor/mbedtls/library/*.c)
add_library(ktmtls_obj OBJECT ${KTMTLS_MBEDTLS_SOURCES} vendor/ktmtls/ktmtls.c vendor/ktmtls/ktmcrypto.c vendor/ktmtls/ktmpassword.c)
set_target_properties(ktmtls_obj PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(ktmtls_obj PRIVATE
    ${CMAKE_SOURCE_DIR}/vendor/mbedtls/include
    ${CMAKE_SOURCE_DIR}/vendor/mbedtls/library
    ${CMAKE_SOURCE_DIR}/vendor/ktmtls)
target_compile_definitions(ktmtls_obj PRIVATE
    MBEDTLS_USER_CONFIG_FILE="ktm_mbedtls_config.h")
# Vendored: not held to our warning bar, always optimised
target_compile_options(ktmtls_obj PRIVATE -w -O2)
add_library(ktmtls STATIC $<TARGET_OBJECTS:ktmtls_obj>)
add_library(ktmtls_shared SHARED $<TARGET_OBJECTS:ktmtls_obj>)
set_target_properties(ktmtls_shared PROPERTIES OUTPUT_NAME ktmtls)
find_package(Threads REQUIRED)
target_link_libraries(ktmtls_shared Threads::Threads)

# Install the executable
install(TARGETS ktm DESTINATION bin)


# The standard library: its modules into <prefix>/modules, then compiled
# as one tree into <prefix>/lib (libktm.a and the versioned shared object)
install(DIRECTORY ${CMAKE_SOURCE_DIR}/std/ DESTINATION modules)
# Vim/Neovim syntax, filetype and ftplugin: `set rtp+=<prefix>/share/ktm/vim`
install(DIRECTORY ${CMAKE_SOURCE_DIR}/syntax-highlighting/vim/ DESTINATION share/ktm/vim)
# ${CMAKE_INSTALL_PREFIX} escaped: the prefix at install time (`cmake --install --prefix`)
install(CODE "execute_process(COMMAND \"\${CMAKE_INSTALL_PREFIX}/bin/ktm\" -fPIC -lib ktm --install-dir=\${CMAKE_INSTALL_PREFIX} \${CMAKE_INSTALL_PREFIX}/modules)")
