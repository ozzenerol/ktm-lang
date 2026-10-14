module http;
import mem, str;

/* Routes requests to handlers by method and path. Declare one
 * (`http.Mux mux;`), add routes with mux.handle, give it to http.listen. */
pub struct Mux {
    MuxRoute *routes;
    i64 len;
    i64 cap;
};

struct MuxRoute {
    String method;     /* "" for any method */
    String path;
    Handler h;
};

/* Adds a route: "GET /users" or "/health" (any method) */
pub void Mux.handle(u8[] pattern, Handler h)
{
    i64 sp = 0;
    while (sp < pattern.len && pattern.ptr[sp] != ' ') { sp++; }
    if (self.len == self.cap) {
        i64 cap = self.cap ? self.cap * 2 : 8;
        self.routes = mem.realloc(self.routes, cap * sizeof(MuxRoute));
        self.cap = cap;
    }
    MuxRoute *rt = &self.routes[self.len++];
    mem.set(rt, 0, sizeof(MuxRoute));
    if (sp < pattern.len) {
        rt.method = str.from(pattern[0:sp]);
        rt.path = str.from(pattern[sp + 1:pattern.len]);
    } else {
        rt.path = str.from(pattern);
    }
    rt.h = h;
}

/* Answers r with the route that matches, 404 or 405 */
pub void Mux.serve(Writer *w, Request *r)
{
    bool pathSeen = false;
    for (i64 i = 0; i < self.len; i++) {
        MuxRoute *rt = &self.routes[i];
        if (!str.equals(rt.path[0:rt.path.len], r.path)) { continue; }
        pathSeen = true;
        if (rt.method.len && !str.equals(rt.method[0:rt.method.len], r.method) &&
            !(str.equals(rt.method[0:rt.method.len], "GET") && str.equals(r.method, "HEAD"))) {
            continue;
        }
        rt.h(w, r);
        return;
    }
    if (pathSeen) {
        w.error(405, "method not allowed");
    } else {
        w.error(404, "not found");
    }
}

/* Frees the routes */
pub void Mux.free()
{
    for (i64 i = 0; i < self.len; i++) {
        self.routes[i].method.free();
        self.routes[i].path.free();
    }
    mem.free(self.routes);
    self.routes = null;
    self.len = self.cap = 0;
}
