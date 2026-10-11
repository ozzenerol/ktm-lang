module http;

import mem, web, str;

/* uses its parent module through an import, like any other */
pub web.Conn *open(i64 fd) {
    web.Conn *c = mem.alloc(sizeof(web.Conn));
    c.fd = fd;
    c.port = web.port();
    return c;
}

pub i64 get(u8 *path) { return web.port() + str.len(path); }
