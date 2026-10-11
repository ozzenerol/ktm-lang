#include "testhelper.ktm"

i32 main() {
    printf("Test - Taking the address of a local: ");
    i64 i = 420;
    i64 *x = &i;
    *x = 69;

    PrintResult(*x == 69, 1);
    return 0;
}
