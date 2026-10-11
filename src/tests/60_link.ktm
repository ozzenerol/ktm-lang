import test;

import os, proc;

/* Two-stage test: build a small C shared library, then compile a
 * second ktm program whose only route to that library is a
 * `#link "<path>"` directive (no .so on the ktm command line).
 * The name form is exercised with `#link <m>` (libm exists on both
 * macOS and Linux). */

i64 correct = 0;
i64 tests = 3;

/* No quotes or `%` in the C source keeps the shell printf simple. */





void main() {
    printf("Test - #link directive (AOT): ");
    if (proc.shell("printf 'long KtmLinkTestValue(void) { return 42; }' "
                         "> ./ktm-60-link.c && "
                         "cc -shared -fPIC ./ktm-60-link.c -o ./ktm-60-link.so") == 0) {
        correct++;
    }
    proc.shell("printf '#link \"'\"$PWD\"'/ktm-60-link.so\"\n"
                 "extern \"c\" i64 KtmLinkTestValue();\n"
                 "i32 main() { if (KtmLinkTestValue() == 42) return 0; return 1; }\n'"
                 " > ./ktm-60-link-path.ktm");
    if (proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} ./ktm-60-link-path.ktm -o ./ktm-60-link-path && "
                         "./ktm-60-link-path") == 0) {
        correct++;
    }
    proc.shell("printf '#link <m>\n"
                 "extern \"c\" f64 cos(f64 x);\n"
                 "i32 main() { if (cos(0.0) == 1.0) return 0; return 1; }\n'"
                 " > ./ktm-60-link-name.ktm");
    if (proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} ./ktm-60-link-name.ktm -o ./ktm-60-link-name && "
                         "./ktm-60-link-name") == 0) {
        correct++;
    }
    proc.shell("rm -f ./ktm-60-link.c ./ktm-60-link.so"
                 " ./ktm-60-link-path.ktm ./ktm-60-link-path"
                 " ./ktm-60-link-name.ktm ./ktm-60-link-name");
    test.printResult(correct, tests);
    printf("===\n");
    os.exit(0);
}
