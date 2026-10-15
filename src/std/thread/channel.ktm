module thread;
import mem;

/* A queue of T between threads, held by value: make one with
 * thread.channel<T>(cap) and free it with free() (or declare it scoped)
 * once no thread uses it; threads share it by pointer.
 *     scoped thread.Channel<i64> ch = thread.channel<i64>(16);
 *     // a producer: ch.send(v) ... ch.close();
 *     // a consumer: i64 v; while (ch.recv(&v)) { ... }
 * With cap items queued send() waits for a recv(); with none, recv()
 * waits for a send(). cap 0 is unbuffered: send() returns once a recv()
 * has taken the value. */
pub struct Channel<T> {
    Mutex m;
    Cond notEmpty;
    Cond notFull;
    Cond taken;
    T *buf;
    i64 cap;
    i64 head;
    i64 len;
    i64 sent;       /* values ever sent and received: an unbuffered */
    i64 received;   /* send() waits until its value is taken */
    bool unbuffered;
    bool closed;
};

/* A Channel holding up to cap values (0: unbuffered) */
pub Channel<T> channel<T>(i64 cap = 0) {
    Channel<T> ch;
    ch.unbuffered = cap <= 0;
    if (cap <= 0) { cap = 1; }
    ch.buf = mem.alloc(sizeof(T) * cap);
    ch.cap = cap;
    return ch;
}

/* Queues v, waiting while the channel is full (unbuffered: until a recv()
 * takes it). Throws ERR_THREAD once the channel is closed. */
pub void Channel<T>.send(T v) {
    if (!self.buf) { throw(ERR_THREAD, "thread.Channel.send: not made with thread.channel"); }
    self.m.lock();
    while (self.len == self.cap && !self.closed) {
        self.notFull.wait(&self.m);
    }
    if (self.closed) {
        self.m.unlock();
        throw(ERR_THREAD, "thread.Channel.send: the channel is closed");
    }
    self.buf[(self.head + self.len) % self.cap] = v;
    self.len++;
    self.sent++;
    i64 ticket = self.sent;
    self.notEmpty.signal();
    if (self.unbuffered) {
        /* closed meanwhile: the value is still received */
        while (self.received < ticket && !self.closed) {
            self.taken.wait(&self.m);
        }
    }
    self.m.unlock();
}

/* The next value into *out, waiting while the channel is empty: false
 * (and *out untouched) once it is closed and every value was received */
pub bool Channel<T>.recv(T *out) {
    if (!self.buf) { throw(ERR_THREAD, "thread.Channel.recv: not made with thread.channel"); }
    self.m.lock();
    while (self.len == 0 && !self.closed) {
        self.notEmpty.wait(&self.m);
    }
    if (self.len == 0) {
        self.m.unlock();
        return false;
    }
    *out = self.buf[self.head];
    self.head = (self.head + 1) % self.cap;
    self.len--;
    self.received++;
    self.notFull.signal();
    if (self.unbuffered) {
        self.taken.broadcast();
    }
    self.m.unlock();
    return true;
}

/* No more values: send() throws from now on, recv() returns the queued
 * values and then false. Closing again does nothing. */
pub void Channel<T>.close() {
    self.m.lock();
    self.closed = true;
    self.notEmpty.broadcast();
    self.notFull.broadcast();
    self.taken.broadcast();
    self.m.unlock();
}

/* The values queued now */
pub i64 Channel<T>.count() {
    self.m.lock();
    i64 n = self.len;
    self.m.unlock();
    return n;
}

/* Frees the queue; values still in it are not freed. Only once no thread
 * uses the channel. */
pub void Channel<T>.free() {
    mem.free(self.buf);
    self.buf = null;
}
