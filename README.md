# ktm

A compiled systems language for servers and tools: native code for Linux (x86_64, arm64) and
macOS (Apple Silicon), no garbage collector, a standard library with HTTP, JSON, TLS and
cryptography, and packages for PostgreSQL and JWT.

**Faster than Go at networking, with less memory and less CPU.** An HTTP server with
64 keep-alive connections, against Go 1.27's `net/http` on the same 2 cores (wrk, ktm v0.19.0):

|                         | ktm     | Go      |      |
| ----------------------- | ------- | ------- | ---- |
| requests per second     | 327,612 | 126,202 | 2.6x |
| requests per CPU second | 164,008 | 70,187  | 2.3x |
| memory (peak RSS)       | 3.3 MB  | 13.0 MB | 4x less |

With 1024 connections: 3.3x the requests, 15.9 MB of memory against 41.8 MB.

```c
import net.http;

void hello(http.Writer *w, http.Request *r) {
    u8[] name = r.query("name");
    if !name.len { name = "world"; }
    w.text(200, "hello, %s\n", name);
}

i32 main() {
    http.Mux mux;
    mux.handle("GET /", &hello);
    return http.listen(8080, &mux);
}
```

## Install

Download the zip for your platform from the [latest release](https://github.com/ozzenerol/ktm-lang/releases/latest),
unzip it and run `sudo ./install.sh`. Or build from source (a C compiler, `make` and `cmake`):

```sh
./build.sh --install
```

## Documentation

[ozzenerol.github.io/ktm-lang](https://ozzenerol.github.io/ktm-lang/): the language, the standard
library, releases and known issues.

## Credits

ktm started as a fork of [holyc-lang](https://github.com/Jamesbarford/holyc-lang) by James
Barford-Evans, a compiler for HolyC, the language Terry A. Davis created for TempleOS.
BSD-2-Clause, see [COPYING](COPYING).
