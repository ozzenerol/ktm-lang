import atomic, coro, date, libc, mem, net, net.http, str, test;

/* net.http (v0.21.0): background work from a handler. coro.go in a
 * handler starts a coroutine on the handler's loop that outlives the
 * request: the reply goes out at once, the work finishes later (with
 * memory of its own, not the request's), on every backend; and
 * http.shutdown waits for work in flight. */

extern "c" i32 pthread_create(void *t, void *attr, void *start, void *arg);
extern "c" i32 pthread_join(void *t, void **ret);
extern "c" i32 usleep(u32 us);

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

struct Job {
    i64 id;
    String note;
};

i64 gStarted = 0, gFinished = 0, gNoteOk = 0;

void *work(void *arg) {
    Job *j = arg;
    atomic.add(&gStarted, 1);
    coro.sleep(150);                    /* slow work: an email, a webhook */
    if (str.equals(j.note[0:], "saved 7")) { atomic.add(&gNoteOk, 1); }
    j.note.free();
    mem.free(j);
    atomic.add(&gFinished, 1);
    return null;
}

void save(http.Writer *w, http.Request *r) {
    Job *j = mem.allocZero(sizeof(Job));    /* the job's own memory: the request's is gone after the reply */
    j.id = 7;
    j.note = str.format("saved %s", r.param("id"));
    coro.go(&work, j);
    w.text(202, "queued");
}

i32 freePort() {
    i32 fd = net.listen(0, "127.0.0.1", 1);
    i32 port = net.localPort(fd);
    libc.close(fd);
    return port;
}

i32 gPort;
i64 gReplyMs = -1, gFinishedAtReply = -1, gFinishedBeforeShutdown = -1;
String gAll;

void *drive(void *unused) {
    i32 fd = -1;
    for (i64 i = 0; i < 200 && fd < 0; i++) {
        fd = net.dial("127.0.0.1", gPort);
        if (fd < 0) { usleep(10000); }
    }
    net.setRecvTimeout(fd, 5000);
    i64 t0 = date.monoNs();
    net.send(fd, "POST /save/7 HTTP/1.1\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
    u8 buf[4096];
    i64 n;
    while ((n = libc.recv(fd, buf, sizeof(buf), 0)) > 0) { gAll.append(buf[0:n]); }
    gReplyMs = (date.monoNs() - t0) / 1000000;
    gFinishedAtReply = atomic.load(&gFinished);
    libc.close(fd);
    gFinishedBeforeShutdown = atomic.load(&gFinished);
    http.shutdown();                    /* with the job still sleeping */
    return null;
}

i32 main() {
    printf("Test - http background work: ");
    http.Mux mux;
    mux.handle("POST /save/{id}", &save);
    gPort = freePort();
    http.Options o;
    o.threads = 1;
    o.bindaddr = "127.0.0.1";
    o.noSignals = true;
    void *t;
    pthread_create(&t, null, &drive, null);
    http.listen(gPort, &mux, null, &o);
    pthread_join(t, null);
    check(str.find(gAll[0:], "HTTP/1.1 202") == 0 && str.find(gAll[0:], "queued") > 0, "the handler answers");
    check(gReplyMs >= 0 && gReplyMs < 120 && gFinishedAtReply == 0, "the reply doesn't wait for the work");
    if (gReplyMs >= 120) { printf("  reply took %d ms\n", gReplyMs); }
    check(gStarted == 1 && gFinished == 1 && gNoteOk == 1 && gFinishedBeforeShutdown == 0,
          "the work finishes with its own memory, and shutdown waits for it");
    gAll.free();
    mux.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
