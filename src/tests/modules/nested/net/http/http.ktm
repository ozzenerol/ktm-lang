module http;

import net;

/* uses its parent module through an import, like any other */
pub net.Conn *open(i64 fd) {
  net.Conn *c = MAlloc(sizeof(net.Conn));
  c->fd = fd;
  c->port = net.port();
  return c;
}

pub i64 get(u8 *path) { return net.port() + StrLen(path); }
