#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>

#define KERNEL_SU_OPTION 0xDEADBEEF
#define CMD_GET_VERSION 2
#define CMD_SET_APP_PROFILE 11
#define CMD_UID_GRANTED_ROOT 12

#define KSU_APP_PROFILE_VER 2
#define KSU_MAX_PACKAGE_NAME 256
#define KSU_MAX_GROUPS 32
#define KSU_SELINUX_DOMAIN 64

struct root_profile {
    int32_t uid;
    int32_t gid;
    int32_t groups_count;
    int32_t groups[KSU_MAX_GROUPS];
    struct {
        uint64_t effective;
        uint64_t permitted;
        uint64_t inheritable;
    } capabilities;
    char selinux_domain[KSU_SELINUX_DOMAIN];
    int32_t namespaces;
};

struct non_root_profile {
    bool umount_modules;
};

struct app_profile {
    uint32_t version;
    char key[KSU_MAX_PACKAGE_NAME];
    int32_t current_uid;
    bool allow_su;
    union {
        struct {
            bool use_default;
            char template_name[KSU_MAX_PACKAGE_NAME];
            struct root_profile profile;
        } rp_config;
        struct {
            bool use_default;
            struct non_root_profile profile;
        } nrp_config;
    };
};

static bool ksuctl(unsigned long cmd, void *arg1, void *arg2) {
    uint32_t result = 0;
    prctl(KERNEL_SU_OPTION, cmd, arg1, arg2, &result);
    return result == KERNEL_SU_OPTION;
}

static int get_version(void) {
    int32_t version = -1;
    if (!ksuctl(CMD_GET_VERSION, &version, NULL)) {
        return -1;
    }
    return version;
}

static bool uid_granted(int uid) {
    bool granted = false;
    if (!ksuctl(CMD_UID_GRANTED_ROOT, (void *)(uintptr_t)uid, &granted)) {
        return false;
    }
    return granted;
}

static int grant_profile(const char *pkg, int uid) {
    struct app_profile profile;
    memset(&profile, 0, sizeof(profile));
    profile.version = KSU_APP_PROFILE_VER;
    strncpy(profile.key, pkg, KSU_MAX_PACKAGE_NAME - 1);
    profile.current_uid = uid;
    profile.allow_su = true;
    profile.rp_config.use_default = true;
    profile.rp_config.profile.uid = 0;
    profile.rp_config.profile.gid = 0;
    profile.rp_config.profile.groups_count = 1;
    profile.rp_config.profile.groups[0] = 0;
    strncpy(profile.rp_config.profile.selinux_domain, "u:r:su:s0", KSU_SELINUX_DOMAIN - 1);

    if (!ksuctl(CMD_SET_APP_PROFILE, &profile, NULL)) {
        fprintf(stderr, "set_app_profile failed; run this helper as the KernelSU manager uid\n");
        return 3;
    }

    printf("set_app_profile ok\n");
    printf("uid_granted=%d\n", uid_granted(uid) ? 1 : 0);
    return uid_granted(uid) ? 0 : 4;
}

int main(int argc, char **argv) {
    printf("sizeof_app_profile=%zu\n", sizeof(struct app_profile));
    printf("ksu_version=%d\n", get_version());

    if (sizeof(struct app_profile) != 776) {
        fprintf(stderr, "unsupported KernelSU app_profile layout\n");
        return 5;
    }

    if (argc == 3 && strcmp(argv[1], "check") == 0) {
        int uid = atoi(argv[2]);
        printf("uid_granted=%d\n", uid_granted(uid) ? 1 : 0);
        return uid_granted(uid) ? 0 : 2;
    }

    if (argc == 4 && strcmp(argv[1], "grant") == 0) {
        return grant_profile(argv[2], atoi(argv[3]));
    }

    fprintf(stderr, "usage: %s check <uid> | grant <package> <uid>\n", argv[0]);
    return 1;
}
