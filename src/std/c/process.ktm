module c;

pub extern "c" i32 posix_spawnp(i32 *pid, u8 *file, void *fileActions, void *attr,
                                                        u8 **argv, u8 **envp);

pub extern "c" i32 posix_spawn(i32 *pid, u8 *path, void *fileActions, void *attr,
                                                     u8 **argv, u8 **envp);

pub extern "c" i32 posix_spawn_file_actions_init(void *fa);

pub extern "c" i32 posix_spawn_file_actions_destroy(void *fa);

pub extern "c" i32 posix_spawn_file_actions_adddup2(void *fa, i32 fd, i32 newfd);

pub extern "c" i32 posix_spawn_file_actions_addopen(void *fa, i32 fd, u8 *path,
                                                                                                i32 oflag, i32 mode);

pub extern "c" i32 posix_spawn_file_actions_addchdir_np(void *fa, u8 *path);

pub extern "c" i32 posix_spawnattr_init(void *attr);

pub extern "c" i32 posix_spawnattr_destroy(void *attr);

pub extern "c" i32 posix_spawnattr_setflags(void *attr, i16 flags);

pub extern "c" i32 posix_spawnattr_setpgroup(void *attr, i32 pgroup);

pub extern "c" i32 posix_spawnattr_setsigdefault(void *attr, void *sigset);

pub extern "c" i32 posix_spawnattr_setsigmask(void *attr, void *sigset);

pub extern "c" i32 sigemptyset(void *set);

pub extern "c" i32 sigfillset(void *set);

pub extern "c" i32 sigaddset(void *set, i32 sig);

pub extern "c" i32 sigismember(void *set, i32 sig);

pub extern "c" i32 sigpending(void *set);

pub extern "c" i32 pthread_sigmask(i32 how, void *set, void *oldset);

pub extern "c" i32 pipe(i32 *fds);
