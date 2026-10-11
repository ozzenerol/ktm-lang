module c;

public extern "c" I32 posix_spawnp(I32 *pid, U8 *file, U0 *file_actions, U0 *attr,
                            U8 **argv, U8 **envp);

public extern "c" I32 posix_spawn(I32 *pid, U8 *path, U0 *file_actions, U0 *attr,
                           U8 **argv, U8 **envp);

public extern "c" I32 posix_spawn_file_actions_init(U0 *fa);

public extern "c" I32 posix_spawn_file_actions_destroy(U0 *fa);

public extern "c" I32 posix_spawn_file_actions_adddup2(U0 *fa, I32 fd, I32 newfd);

public extern "c" I32 posix_spawn_file_actions_addopen(U0 *fa, I32 fd, U8 *path,
                                                I32 oflag, I32 mode);

public extern "c" I32 posix_spawn_file_actions_addchdir_np(U0 *fa, U8 *path);

public extern "c" I32 posix_spawnattr_init(U0 *attr);

public extern "c" I32 posix_spawnattr_destroy(U0 *attr);

public extern "c" I32 posix_spawnattr_setflags(U0 *attr, I16 flags);

public extern "c" I32 posix_spawnattr_setpgroup(U0 *attr, I32 pgroup);

public extern "c" I32 posix_spawnattr_setsigdefault(U0 *attr, U0 *sigset);

public extern "c" I32 posix_spawnattr_setsigmask(U0 *attr, U0 *sigset);

public extern "c" I32 sigemptyset(U0 *set);

public extern "c" I32 sigfillset(U0 *set);

public extern "c" I32 sigaddset(U0 *set, I32 sig);

public extern "c" I32 sigismember(U0 *set, I32 sig);

public extern "c" I32 sigpending(U0 *set);

public extern "c" I32 pthread_sigmask(I32 how, U0 *set, U0 *oldset);

public extern "c" I32 pipe(I32 *fds);
