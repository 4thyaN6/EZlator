/* NOTE: This class is compiled but unused here. Though it can be used for arm64ec based containers, it is now included in the imagefs.txz */
/* You can choose to preload this from the native lib dir in the apk, via BionicProgramLauncherComponent. But if you do, the architecture */
/* may not work with x86_64 Wine based containers without additional patches to link related binaries to the imageFs libSDL2 and it's symlinks */
/* ─────────── evshim.c (Multi-Controller & Dynamic SDL) ─────────── */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <pthread.h>
#include <unistd.h>
#include <SDL2/SDL.h>
#include <stdarg.h>

static int g_debug_enabled = 0;

#include <android/log.h>

/* Also mirrored to logcat (tag "EVSHIM"): the launcher sends wine's stdout/stderr
   to /dev/null unless Wine debug is on, so these messages were never visible.
   logcat(태그 "EVSHIM")에도 같이 남김: 런처가 Wine 디버그를 켜지 않으면 wine의
   stdout/stderr를 /dev/null로 보내서 이 메시지들이 전혀 안 보였음 */
#define LOGI(...) do { dprintf(STDOUT_FILENO, __VA_ARGS__); __android_log_print(ANDROID_LOG_INFO,  "EVSHIM", __VA_ARGS__); } while (0)
#define LOGE(...) do { dprintf(STDERR_FILENO, __VA_ARGS__); __android_log_print(ANDROID_LOG_ERROR, "EVSHIM", __VA_ARGS__); } while (0)
#define LOGD(...) do { if (g_debug_enabled) dprintf(STDOUT_FILENO, __VA_ARGS__); } while (0)

#define MAX_GAMEPADS 4
static int vjoy_ids[MAX_GAMEPADS] = {-1};
static int read_fd  [MAX_GAMEPADS] = {-1};
static int rumble_fd[MAX_GAMEPADS] = {-1};
static void *handle = NULL;
static pthread_mutex_t shm_mutex = PTHREAD_MUTEX_INITIALIZER;

struct gamepad_io {
    int16_t lx, ly, rx, ry, lt, rt;
    uint8_t btn[15];
    uint8_t hat;
    uint8_t _padding[4];
    uint16_t low_freq_rumble;
    uint16_t high_freq_rumble;
};

static int (*p_SDL_Init)(uint32_t flags);
static const char * (*p_SDL_GetError)(void);
static SDL_Joystick * (*p_SDL_JoystickOpen)(int device_index);
static int (*p_SDL_JoystickAttachVirtualEx)(const SDL_VirtualJoystickDesc *desc);
static int (*p_SDL_JoystickSetVirtualAxis)(SDL_Joystick *joystick, int axis, int16_t value);
static int (*p_SDL_JoystickSetVirtualButton)(SDL_Joystick *joystick, int button, uint8_t value);
static int (*p_SDL_JoystickSetVirtualHat)(SDL_Joystick *joystick, int hat, uint8_t value);
static void (*p_SDL_PumpEvents)(void);
static void (*p_SDL_Delay)(uint32_t ms);
static void (*p_SDL_GetVersion)(SDL_version *);


#define GETFUNCPTR(name)\
do {\
	if (!(p_##name = (typeof(p_##name))dlsym(handle, #name))) {\
        LOGE("Failed to load SDL symbol, %s\n", #name);\
    }\
} while (0)

static int OnRumble(void *userdata,
                    uint16_t low_frequency_rumble,
                    uint16_t high_frequency_rumble)
{
    int idx = (int)(intptr_t)userdata;
    if (idx < 0 || idx >= MAX_GAMEPADS || rumble_fd[idx] < 0) return -1;

    uint16_t vals[2] = { low_frequency_rumble, high_frequency_rumble };

    pthread_mutex_lock(&shm_mutex);             /* NEW */
    ssize_t w = pwrite(rumble_fd[idx], vals, sizeof(vals), 32);
    pthread_mutex_unlock(&shm_mutex);           /* NEW */

    if (w != (ssize_t)sizeof(vals))
        LOGE("Rumble write failed (P%d): %s\n", idx, strerror(errno));

    LOGD("Rumble P%d  low=%u  high=%u\n", idx,
         low_frequency_rumble, high_frequency_rumble);
    return 0;
}



static void *event_pump_thread(void *arg) {
    for (;;) {
        p_SDL_PumpEvents();
        p_SDL_Delay(5);
    }
    return NULL;
}

static void *vjoy_updater(void *arg)
{
    int idx = (int)(intptr_t)arg;

    int fd = read_fd[idx];
    if (fd < 0) {
        LOGE("P%d: read_fd not initialised – aborting thread\n", idx);
        return NULL;
    }

    SDL_Joystick *js = p_SDL_JoystickOpen(vjoy_ids[idx]);
    if (!js) {
        LOGE("P%d: SDL_JoystickOpen failed\n", idx);
        return NULL;
    }

    struct gamepad_io cur, last_state = {0};

    LOGI("VJOY UPDATER P%d running (PID %d)\n", idx, getpid());

    for (;;) {
        pthread_mutex_lock(&shm_mutex);

        /* BUG FIX: read() advanced the file offset, so after the first 36-byte
           read the next call got the last 28 bytes of the 64-byte file (size
           mismatch -> ignored) and every call after that hit EOF (n = 0). The
           virtual pad kept its very first state forever - no button or stick
           from the screen or a Bluetooth pad ever reached the game. pread() at
           offset 0 re-reads the live state every time.
           버그 수정: read()가 파일 오프셋을 계속 밀어서, 처음 36바이트를 읽은 뒤
           다음 호출은 64바이트 파일의 마지막 28바이트만 읽었고(크기 불일치로 무시)
           그 뒤로는 계속 EOF(n = 0)였음. 가상 패드가 맨 처음 상태에서 영원히
           멈춰 있어서 화면 버튼이든 블루투스 패드든 입력이 게임에 전혀 안 갔음.
           오프셋 0에서 pread()로 매번 최신 상태를 다시 읽음 */
        ssize_t n = pread(fd, &cur, sizeof cur, 0);

        if (n == sizeof cur && memcmp(&cur, &last_state, sizeof cur) != 0) {

            p_SDL_JoystickSetVirtualAxis (js, 0, cur.lx);
            p_SDL_JoystickSetVirtualAxis (js, 1, cur.ly);
            p_SDL_JoystickSetVirtualAxis (js, 2, cur.rx);
            p_SDL_JoystickSetVirtualAxis (js, 3, cur.ry);
            p_SDL_JoystickSetVirtualAxis (js, 4, cur.lt);
            p_SDL_JoystickSetVirtualAxis (js, 5, cur.rt);

            for (int i = 0; i < 15; ++i)
                p_SDL_JoystickSetVirtualButton(js, i, cur.btn[i]);

            p_SDL_JoystickSetVirtualHat(js, 0, cur.hat);

            last_state = cur;
        }
        else if (n < 0) {
            LOGE("P%d: read error: %s\n", idx, strerror(errno));
        }

        pthread_mutex_unlock(&shm_mutex);

        p_SDL_Delay(5);
    }

    return NULL;
}

__attribute__((constructor))
static void initialize_all_pads(void)
{
    const char *dbg = getenv("EVSHIM_DEBUG");
    g_debug_enabled = dbg && strchr("1yY", *dbg);

    LOGI("EVSHIM initializing…\n");

    handle = dlopen("libSDL2-2.0.so.0", RTLD_LAZY | RTLD_GLOBAL);
    if (!handle) { LOGE("dlopen SDL failed: %s\n", dlerror()); return; }

    GETFUNCPTR(SDL_Init);  GETFUNCPTR(SDL_GetError);
    GETFUNCPTR(SDL_JoystickOpen);  GETFUNCPTR(SDL_JoystickAttachVirtualEx);
    GETFUNCPTR(SDL_JoystickSetVirtualAxis);  GETFUNCPTR(SDL_JoystickSetVirtualButton);
    GETFUNCPTR(SDL_JoystickSetVirtualHat);   GETFUNCPTR(SDL_PumpEvents);
    GETFUNCPTR(SDL_Delay);  GETFUNCPTR(SDL_GetVersion);

    /* Wine processes load the APK's Android SDL2 port. Its Android joystick
       driver would add the phone accelerometer as a joystick (hint default on);
       winebus then exposes it to the game as a controller, which can end up as
       controller #1 ahead of the virtual pad. HIDAPI scanning is also useless
       here. Both are switched off before SDL_Init (overwrite=0 keeps any value
       the user set on purpose).
       wine 프로세스는 APK의 안드로이드용 SDL2를 불러옴. 그 안드로이드 조이스틱
       드라이버는 폰 가속도계를 조이스틱으로 추가함(힌트 기본값 켜짐). 그러면
       winebus가 그걸 게임에 컨트롤러로 보여주고, 가상 패드보다 앞선 1번
       컨트롤러가 될 수 있음. HIDAPI 스캔도 여기선 쓸모없음. 둘 다 SDL_Init 전에
       끔(overwrite=0이라 사용자가 일부러 넣은 값은 유지됨) */
    setenv("SDL_ACCELEROMETER_AS_JOYSTICK", "0", 0);
    setenv("SDL_JOYSTICK_HIDAPI", "0", 0);

    /* REVERTED (logcat 2026-09-27): calling SDL_SetMainReady() here let SDL_Init
       continue into Android-only code that needs a Java VM, and it crashed
       (SIGSEGV inside libSDL2, called from this constructor) in EVERY Wine
       process, so nothing could start. Without it SDL_Init fails harmlessly.
       Pads need an SDL build without the Android/JNI backend instead.
       되돌림(로그캣 2026-09-27): 여기서 SDL_SetMainReady()를 부르면 SDL_Init이
       JavaVM이 필요한 안드로이드 전용 코드까지 진행해서, 모든 Wine 프로세스가
       이 생성자에서 libSDL2 내부 SIGSEGV로 죽었음 - 아무것도 실행이 안 됐음.
       이게 없으면 SDL_Init은 무해하게 실패만 함. 패드는 안드로이드/JNI 백엔드가
       없는 SDL 빌드가 따로 필요함 */

    /* Result is logged: a failed init means no virtual pad in this process
       결과를 로그로 남김: 초기화 실패면 이 프로세스엔 가상 패드가 없음 */
    if (p_SDL_Init(SDL_INIT_JOYSTICK) != 0)
        LOGE("SDL_Init(JOYSTICK) failed: %s\n", p_SDL_GetError());

    SDL_version v; p_SDL_GetVersion(&v);
    LOGI("SDL %d.%d.%d bound\n", v.major, v.minor, v.patch);

    int players = getenv("EVSHIM_MAX_PLAYERS") ? atoi(getenv("EVSHIM_MAX_PLAYERS")) : 1;
    if (players > MAX_GAMEPADS) players = MAX_GAMEPADS;


    /* per-player setup */
    for (int i = 0; i < players; ++i) {

        /* SHM folder comes from EVSHIM_SHM_DIR (set by the launcher), so the
           path follows the applicationId instead of a hardcoded package name.
           SHM 폴더는 런처가 넣어주는 EVSHIM_SHM_DIR에서 가져옴. 패키지명을
           하드코딩하지 않고 applicationId를 따라가게 하기 위함 */
        const char *shm_dir = getenv("EVSHIM_SHM_DIR");
        if (!shm_dir || !*shm_dir) shm_dir = "/data/data/com.ezlator.app/files/imagefs/tmp";
        char path[512];
        snprintf(path, sizeof path, "%s/gamepad%s.mem", shm_dir,
                 (i == 0) ? "" : (char[2]){'0' + i, '\0'});

        /* open once – store for reader + writer */
        read_fd  [i] = open(path, O_RDONLY);
        rumble_fd[i] = open(path, O_WRONLY);

        if (read_fd[i]  < 0 || rumble_fd[i] < 0) {
            LOGE("P%d: failed to open shared file '%s': %s\n", i, path, strerror(errno));
            if (read_fd[i]  >= 0) close(read_fd[i]);
            if (rumble_fd[i] >= 0) close(rumble_fd[i]);
            read_fd[i] = rumble_fd[i] = -1;
            continue;
        }

        /* SDL virtual device */
        SDL_VirtualJoystickDesc d = {0};
        d.version = SDL_VIRTUAL_JOYSTICK_DESC_VERSION;
        d.type    = SDL_JOYSTICK_TYPE_GAMECONTROLLER;
        d.naxes   = 6; d.nbuttons = 15; d.nhats = 1;
        d.Rumble  = &OnRumble;  d.userdata = (void*)(intptr_t)i;

        char name[64];
        snprintf(name, sizeof name, (i < 2) ? "B (Player %d)" : "A (Player %d)", i + 1);
        d.name = strdup(name);

        vjoy_ids[i] = p_SDL_JoystickAttachVirtualEx(&d);
        if (vjoy_ids[i] < 0) {
            LOGE("P%d: SDL attach failed: %s\n", i, p_SDL_GetError());
            close(read_fd[i]);   read_fd[i]   = -1;
            close(rumble_fd[i]); rumble_fd[i] = -1;
            continue;
        }
        LOGI("P%d: virtual joystick id=%d ready (%s)\n", i, vjoy_ids[i], path);

        pthread_t up_tid;
        pthread_create(&up_tid, NULL, vjoy_updater, (void*)(intptr_t)i);
        pthread_detach(up_tid);
    }
}

/* ------------  “hide /dev/input/event*” hooks  -------------------- */
#undef ioctl

static inline int is_event_node(const char *p)
{ return p && !strncmp(p, "/dev/input/event", 16); }

typedef int (*open_f)(const char *, int, ...);
static open_f real_open;

static int open_common(const char *path, int flags, va_list ap)
{
    if (is_event_node(path)) { errno = ENOENT; return -1; }
    if (!real_open) real_open = (open_f)dlsym(RTLD_NEXT, "open");
    mode_t mode = 0;
    if (flags & O_CREAT) mode = va_arg(ap, mode_t);
    return real_open(path, flags, mode);
}

int open(const char *path, int flags, ...) __attribute__((visibility("default")));
int open(const char *path, int flags, ...)
{
    va_list ap;
    va_start(ap, flags);
    int r = open_common(path, flags, ap);
    va_end(ap);
    return r;
}

int open64(const char *path, int flags, ...) __attribute__((visibility("default")));
int open64(const char *path, int flags, ...)
{
    va_list ap;
    va_start(ap, flags);
    int r = open_common(path, flags, ap);
    va_end(ap);
    return r;
}

typedef int (*ioctl_f)(int, int, ...);
static ioctl_f real_ioctl;

int ioctl(int fd, int req, ...) __attribute__((visibility("default")));
int ioctl(int fd, int req, ...)
{
    if (!real_ioctl) real_ioctl = (ioctl_f)dlsym(RTLD_NEXT, "ioctl");
    char linkbuf[64], path[64];
    snprintf(linkbuf, sizeof linkbuf, "/proc/self/fd/%d", fd);
    ssize_t n = readlink(linkbuf, path, sizeof path - 1);
    if (n > 0) {
        path[n] = 0;
        if (is_event_node(path)) { errno = ENOTTY; return -1; }
    }
    va_list ap;
    va_start(ap, req);
    void *arg = va_arg(ap, void *);
    va_end(ap);
    return real_ioctl(fd, req, arg);
}

typedef ssize_t (*read_f)(int, void *, size_t);
static read_f real_read;

ssize_t read(int fd, void *buf, size_t count) __attribute__((visibility("default")));
ssize_t read(int fd, void *buf, size_t count)
{
    if (!real_read) real_read = (read_f)dlsym(RTLD_NEXT, "read");
    char linkbuf[64], path[64];
    snprintf(linkbuf, sizeof(linkbuf), "/proc/self/fd/%d", fd);
    ssize_t n = readlink(linkbuf, path, sizeof(path) - 1);
    if (n > 0) {
        path[n] = 0;
        if (is_event_node(path)) { errno = EAGAIN; return -1; }
    }
    return real_read(fd, buf, count);
}
/* --------------------------------------------------------------------- */