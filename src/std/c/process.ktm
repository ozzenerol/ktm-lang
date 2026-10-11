module c;

extern "c" I32 posix_spawnp(I32 *pid, U8 *file, U0 *file_actions, U0 *attr,
                            U8 **argv, U8 **envp);

extern "c" I32 posix_spawn(I32 *pid, U8 *path, U0 *file_actions, U0 *attr,
                           U8 **argv, U8 **envp);

extern "c" I32 posix_spawn_file_actions_init(U0 *fa);

extern "c" I32 posix_spawn_file_actions_destroy(U0 *fa);

extern "c" I32 posix_spawn_file_actions_adddup2(U0 *fa, I32 fd, I32 newfd);

extern "c" I32 posix_spawn_file_actions_addopen(U0 *fa, I32 fd, U8 *path,
                                                I32 oflag, I32 mode);

extern "c" I32 posix_spawn_file_actions_addchdir_np(U0 *fa, U8 *path);

extern "c" I32 posix_spawnattr_init(U0 *attr);

extern "c" I32 posix_spawnattr_destroy(U0 *attr);

extern "c" I32 posix_spawnattr_setflags(U0 *attr, I16 flags);

extern "c" I32 posix_spawnattr_setpgroup(U0 *attr, I32 pgroup);

extern "c" I32 posix_spawnattr_setsigdefault(U0 *attr, U0 *sigset);

extern "c" I32 posix_spawnattr_setsigmask(U0 *attr, U0 *sigset);

extern "c" I32 sigemptyset(U0 *set);

extern "c" I32 sigfillset(U0 *set);

extern "c" I32 sigaddset(U0 *set, I32 sig);

extern "c" I32 sigismember(U0 *set, I32 sig);

extern "c" I32 sigpending(U0 *set);

extern "c" I32 pthread_sigmask(I32 how, U0 *set, U0 *oldset);

extern "c" I32 kill(I32 pid, I32 sig);

extern "c" I32 pipe(I32 *fds);
