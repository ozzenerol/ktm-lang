import os, proc, test;

/* `ktm -lib <name>` installs lib<name>.a plus the VERSIONED shared
 * object (lib<name>.so.0.0.1 / lib<name>.0.0.1.dylib) and deliberately
 * no unversioned one, so `-l<name>` keeps linking AOT statically. The
 * JIT used to dlopen only lib<name>.{so,dylib}, so `#link <name>` of a
 * `-lib` library failed under -jit. The library is installed into a
 * scratch prefix holding a copy of libktm from the prefix under test
 * ($KTM_INSTALL_DIR, else the default /usr/local); both the JIT and
 * the AOT program are pointed at that prefix. The AOT program runs
 * with no LD_LIBRARY_PATH, so it must have linked the archive. */

i64 correct = 0;
i64 tests = 3;

void main() {
    printf("Test - -lib library loadable via #link (JIT + AOT): ");
    proc.shell("rm -rf ./ktm-85-prefix ./ktm-85-work ./ktm-85-use.ktm ./ktm-85-use");
    if (proc.shell("import libc;\n\nD=${KTM_INSTALL_DIR:-/usr/local} && H=$PWD/../../ktm && P=$PWD && "
                         "libc.mkdir -p ./ktm-85-prefix/include ./ktm-85-prefix/lib ./ktm-85-work && "
                         "cp $D/include/std.ktmh ./ktm-85-prefix/include/ && "
                         "cp -P $D/lib/libktm.* ./ktm-85-prefix/lib/ && "
                         "printf 'pub i64 Ktm85Triple(i64 x) { return x * 3; }\n'"
                         " > ./ktm-85-work/ktm85.ktm && "
                         "cd ./ktm-85-work && "
                         "$H -lib ktm85 --install-dir=$P/ktm-85-prefix ktm85.ktm > /dev/null 2>&1") == 0) {
        correct++;
    }
    proc.shell("printf '#link <ktm85>\n"
                 "i64 Ktm85Triple(i64 x);\n"
                 "i32 main() { if (Ktm85Triple(14) == 42) return 0; return 1; }\n'"
                 " > ./ktm-85-use.ktm");
    if (proc.shell("../../ktm -jit --install-dir=./ktm-85-prefix ./ktm-85-use.ktm") == 0) {
        correct++;
    }
    if (proc.shell("../../ktm --install-dir=./ktm-85-prefix ./ktm-85-use.ktm -o ./ktm-85-use && "
                         "./ktm-85-use") == 0) {
        correct++;
    }
    proc.shell("rm -rf ./ktm-85-prefix ./ktm-85-work ./ktm-85-use.ktm ./ktm-85-use");
    test.printResult(correct, tests);
    printf("===\n");
    os.exit(0);
}
