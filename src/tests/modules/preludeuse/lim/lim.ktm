module lim;

pub i64 limits() {
    return U8_MAX + I8_MAX + EXIT_FAIL;
}

pub i64 caught() {
    try {
        throw(42);
    } catch (Error e) {
        return e.code;
    }
    return 0;
}

pub Error make(i64 code) {
    Error e;
    e.code = code;
    return e;
}
