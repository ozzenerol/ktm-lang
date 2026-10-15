module thread;
import atomic, libc;

extern "c" i32 sched_yield();
extern "c" i32 pthread_mutex_trylock(libc.pthread_mutex_t *lk);

/* Each lock below works as declared, with no init and nothing to free:
 * the C lock inside is set up on first use. Don't copy one that is in use. */

/* state: 0 not set up, 1 being set up, 2 ready */
static bool readyOnce(i32 *state) {
    if (atomic.load(state) == 2) { return false; }
    if (atomic.cas(state, 0, 1)) { return true; }
    while (atomic.load(state) != 2) { sched_yield(); }
    return false;
}

/* A lock one thread holds at a time:
 *     m.lock();
 *     defer m.unlock();
 * or `scoped thread.Guard g = m.guard();` */
pub struct Mutex {
    i32 state;
    libc.pthread_mutex_t m;
};

static void mutexReady(Mutex *m) {
    if (readyOnce(&m.state)) {
        libc.pthread_mutex_init(&m.m);
        atomic.store(&m.state, 2);
    }
}

/* Waits until no other thread holds the lock, then holds it */
pub void Mutex.lock() {
    mutexReady(self);
    libc.pthread_mutex_lock(&self.m);
}

/* Holds the lock if no other thread does: true when it did */
pub bool Mutex.tryLock() {
    mutexReady(self);
    return pthread_mutex_trylock(&self.m) == 0;
}

pub void Mutex.unlock() {
    libc.pthread_mutex_unlock(&self.m);
}

/* A held Mutex that free() unlocks: `scoped thread.Guard g = m.guard();`
 * holds m until the end of the block */
pub struct Guard {
    Mutex *m;
};

/* Locks m and returns the Guard that unlocks it */
pub Guard Mutex.guard() {
    self.lock();
    Guard g;
    g.m = self;
    return g;
}

pub void Guard.free() {
    if (self.m) {
        self.m.unlock();
        self.m = null;
    }
}

/* A condition threads wait on while holding a Mutex:
 *     m.lock();
 *     while (!ready) { c.wait(&m); }
 *     m.unlock();
 * and, where ready is set (with m held), c.signal() or c.broadcast() */
pub struct Cond {
    i32 state;
    libc.pthread_cond_t c;
};

static void condReady(Cond *c) {
    if (readyOnce(&c.state)) {
        libc.pthread_cond_init(&c.c);
        atomic.store(&c.state, 2);
    }
}

/* Unlocks m, sleeps until signal() or broadcast() (or, rarely, for no
 * reason: check the condition in a loop), and locks m again */
pub void Cond.wait(Mutex *m) {
    condReady(self);
    mutexReady(m);
    libc.pthread_cond_wait(&self.c, &m.m);
}

/* Wakes one waiting thread */
pub void Cond.signal() {
    condReady(self);
    libc.pthread_cond_signal(&self.c);
}

/* Wakes every waiting thread */
pub void Cond.broadcast() {
    condReady(self);
    libc.pthread_cond_broadcast(&self.c);
}

/* A lock many readers can hold at once, or one writer. A waiting writer
 * goes before readers that come after it. */
pub struct RwLock {
    Mutex m;
    Cond c;
    i64 readers;
    i64 writersWaiting;
    bool writer;
};

pub void RwLock.readLock() {
    self.m.lock();
    while (self.writer || self.writersWaiting) {
        self.c.wait(&self.m);
    }
    self.readers++;
    self.m.unlock();
}

pub void RwLock.readUnlock() {
    self.m.lock();
    self.readers--;
    if (self.readers == 0) {
        self.c.broadcast();
    }
    self.m.unlock();
}

pub void RwLock.writeLock() {
    self.m.lock();
    self.writersWaiting++;
    while (self.writer || self.readers) {
        self.c.wait(&self.m);
    }
    self.writersWaiting--;
    self.writer = true;
    self.m.unlock();
}

pub void RwLock.writeUnlock() {
    self.m.lock();
    self.writer = false;
    self.c.broadcast();
    self.m.unlock();
}

/* Runs a function once, whichever threads call run() and however many
 * times: the others wait until it has run. One that throws hasn't run:
 * the next call tries again. */
pub struct Once {
    i32 done;
    Mutex m;
};

pub void Once.run(fn() void f) {
    if (atomic.load(&self.done)) { return; }
    self.m.lock();
    defer self.m.unlock();
    if (!self.done) {
        f();
        atomic.store(&self.done, 1);
    }
}
