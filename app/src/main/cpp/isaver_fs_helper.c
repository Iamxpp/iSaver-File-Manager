#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

enum {
    X_NOT_FOUND = 44,
    X_NOT_DIRECTORY = 45,
    X_SOURCE_UNREADABLE = 56,
    X_NOT_WRITABLE = 48,
    X_ALREADY_EXISTS = 49,
    X_NO_SPACE = 50,
    X_IO = 51,
    X_PARENT_INVALID = 52,
    X_STAGE_INVALID = 53,
    X_SOURCE_CHANGED = 54,
    X_OUTCOME_UNCERTAIN = 55,
    X_USAGE = 64,
};

static const char PAYLOAD_NAME[] = "payload";

static int parse_u64(const char *text, unsigned long long *value) {
    if (text == NULL || text[0] == '\0') return 0;
    for (const char *cursor = text; *cursor != '\0'; ++cursor) {
        if (*cursor < '0' || *cursor > '9') return 0;
    }
    char *end = NULL;
    errno = 0;
    *value = strtoull(text, &end, 10);
    return errno == 0 && end != NULL && *end == '\0';
}

static int parse_identity(
    char **argv,
    int index,
    unsigned long long *device,
    unsigned long long *inode
) {
    return parse_u64(argv[index], device) && parse_u64(argv[index + 1], inode);
}

static int basename_ok(const char *name) {
    return name != NULL && name[0] != '\0' && strcmp(name, ".") != 0 &&
        strcmp(name, "..") != 0 && strchr(name, '/') == NULL && strlen(name) <= 255;
}

static int stage_name_ok(const char *name) {
    static const char prefix[] = ".isaver-stage-";
    if (name == NULL || strncmp(name, prefix, sizeof(prefix) - 1) != 0) return 0;
    const char *uuid = name + sizeof(prefix) - 1;
    if (strlen(uuid) != 36) return 0;
    for (size_t i = 0; i < 36; ++i) {
        if (i == 8 || i == 13 || i == 18 || i == 23) {
            if (uuid[i] != '-') return 0;
        } else if (!((uuid[i] >= '0' && uuid[i] <= '9') ||
                     (uuid[i] >= 'a' && uuid[i] <= 'f') ||
                     (uuid[i] >= 'A' && uuid[i] <= 'F'))) {
            return 0;
        }
    }
    return 1;
}

static int parent_errno(int error) {
    if (error == ENOENT) return X_NOT_FOUND;
    if (error == ENOTDIR) return X_NOT_DIRECTORY;
    if (error == EACCES || error == EPERM || error == EROFS) return X_NOT_WRITABLE;
    return X_PARENT_INVALID;
}

static int write_errno(int error) {
    if (error == EEXIST) return X_ALREADY_EXISTS;
    if (error == ENOSPC || error == EDQUOT) return X_NO_SPACE;
    if (error == EACCES || error == EPERM || error == EROFS) return X_NOT_WRITABLE;
    if (error == ENOENT) return X_NOT_FOUND;
    return X_IO;
}

static int source_errno(int error) {
    if (error == ENOENT || error == EACCES || error == EPERM || error == ELOOP) {
        return X_SOURCE_UNREADABLE;
    }
    return X_IO;
}

static int identity_matches(
    const struct stat *status,
    unsigned long long device,
    unsigned long long inode
) {
    return (unsigned long long) status->st_dev == device &&
        (unsigned long long) status->st_ino == inode;
}

static int open_parent(
    const char *original,
    const char *canonical,
    unsigned long long device,
    unsigned long long inode
) {
    int original_fd = open(original, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (original_fd < 0) return -parent_errno(errno);
    struct stat original_status;
    if (fstat(original_fd, &original_status) != 0 || !S_ISDIR(original_status.st_mode) ||
        !identity_matches(&original_status, device, inode)) {
        close(original_fd);
        return -X_PARENT_INVALID;
    }

    int canonical_fd = open(canonical, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (canonical_fd < 0) {
        int result = parent_errno(errno);
        close(original_fd);
        return -result;
    }
    struct stat canonical_status;
    if (fstat(canonical_fd, &canonical_status) != 0 || !S_ISDIR(canonical_status.st_mode) ||
        !identity_matches(&canonical_status, device, inode) ||
        original_status.st_dev != canonical_status.st_dev ||
        original_status.st_ino != canonical_status.st_ino) {
        close(canonical_fd);
        close(original_fd);
        return -X_PARENT_INVALID;
    }
    close(original_fd);
    return canonical_fd;
}

static int open_stage(
    int parent_fd,
    const char *stage_name,
    unsigned long long device,
    unsigned long long inode
) {
    int stage_fd = openat(parent_fd, stage_name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (stage_fd < 0) return -X_STAGE_INVALID;
    struct stat status;
    if (fstat(stage_fd, &status) != 0 || !S_ISDIR(status.st_mode) ||
        !identity_matches(&status, device, inode) || status.st_uid != 0 ||
        (status.st_mode & 07777) != 0700) {
        close(stage_fd);
        return -X_STAGE_INVALID;
    }
    return stage_fd;
}

static int remove_known_payload(int stage_fd, const struct stat *expected) {
    struct stat current;
    if (fstatat(stage_fd, PAYLOAD_NAME, &current, AT_SYMLINK_NOFOLLOW) != 0) {
        return errno == ENOENT ? 0 : X_STAGE_INVALID;
    }
    if (!S_ISREG(current.st_mode) || current.st_dev != expected->st_dev ||
        current.st_ino != expected->st_ino) {
        return X_STAGE_INVALID;
    }
    return unlinkat(stage_fd, PAYLOAD_NAME, 0) == 0 || errno == ENOENT ? 0 : X_STAGE_INVALID;
}

static int remove_stage_path(
    int parent_fd,
    int stage_fd,
    const char *stage_name,
    const struct stat *held,
    int require_secure_mode
) {
    struct stat current;
    if (fstatat(parent_fd, stage_name, &current, AT_SYMLINK_NOFOLLOW) != 0 ||
        !S_ISDIR(current.st_mode) || current.st_dev != held->st_dev ||
        current.st_ino != held->st_ino ||
        (require_secure_mode &&
         (current.st_uid != 0 || (current.st_mode & 07777) != 0700))) {
        close(stage_fd);
        return X_OUTCOME_UNCERTAIN;
    }
    int removed = unlinkat(parent_fd, stage_name, AT_REMOVEDIR);
    close(stage_fd);
    return removed == 0 ? 0 : X_OUTCOME_UNCERTAIN;
}

static int finish_stage(int parent_fd, int stage_fd, const char *stage_name) {
    struct stat held;
    if (fstat(stage_fd, &held) != 0 || !S_ISDIR(held.st_mode) || held.st_uid != 0 ||
        (held.st_mode & 07777) != 0700) {
        close(stage_fd);
        return X_OUTCOME_UNCERTAIN;
    }
    return remove_stage_path(parent_fd, stage_fd, stage_name, &held, 1);
}

static int prepare_stage(int argc, char **argv) {
    if (argc != 7 || !stage_name_ok(argv[4])) return X_USAGE;
    unsigned long long parent_device;
    unsigned long long parent_inode;
    if (!parse_identity(argv, 5, &parent_device, &parent_inode)) return X_USAGE;

    int parent_fd = open_parent(argv[2], argv[3], parent_device, parent_inode);
    if (parent_fd < 0) return -parent_fd;
    if (mkdirat(parent_fd, argv[4], 0700) != 0) {
        int result = write_errno(errno);
        close(parent_fd);
        return result;
    }
    int stage_fd = openat(parent_fd, argv[4], O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    struct stat status;
    if (stage_fd < 0) {
        close(parent_fd);
        return X_OUTCOME_UNCERTAIN;
    }
    if (fstat(stage_fd, &status) != 0) {
        close(stage_fd);
        close(parent_fd);
        return X_OUTCOME_UNCERTAIN;
    }
    if (!S_ISDIR(status.st_mode) || status.st_uid != 0 ||
        (status.st_mode & 07777) != 0700) {
        int cleanup = remove_stage_path(parent_fd, stage_fd, argv[4], &status, 0);
        close(parent_fd);
        return cleanup == 0 ? X_STAGE_INVALID : X_OUTCOME_UNCERTAIN;
    }
    printf("%llu:%llu\n", (unsigned long long) status.st_dev, (unsigned long long) status.st_ino);
    close(stage_fd);
    close(parent_fd);
    return 0;
}

static int copy_publish(int argc, char **argv) {
    if (argc != 14 || !stage_name_ok(argv[4]) || !basename_ok(argv[5])) return X_USAGE;
    unsigned long long parent_device;
    unsigned long long parent_inode;
    unsigned long long stage_device;
    unsigned long long stage_inode;
    unsigned long long source_device;
    unsigned long long source_inode;
    unsigned long long expected_size;
    if (!parse_identity(argv, 7, &parent_device, &parent_inode) ||
        !parse_identity(argv, 9, &stage_device, &stage_inode) ||
        !parse_identity(argv, 11, &source_device, &source_inode) ||
        !parse_u64(argv[13], &expected_size)) {
        return X_USAGE;
    }

    int parent_fd = open_parent(argv[2], argv[3], parent_device, parent_inode);
    if (parent_fd < 0) return -parent_fd;
    int stage_fd = open_stage(parent_fd, argv[4], stage_device, stage_inode);
    if (stage_fd < 0) {
        close(parent_fd);
        return -stage_fd;
    }

    int source_fd = open(argv[6], O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (source_fd < 0) {
        int result = source_errno(errno);
        int cleanup = finish_stage(parent_fd, stage_fd, argv[4]);
        close(parent_fd);
        return cleanup == 0 ? result : X_OUTCOME_UNCERTAIN;
    }
    struct stat source_status;
    if (fstat(source_fd, &source_status) != 0 || !S_ISREG(source_status.st_mode) ||
        !identity_matches(&source_status, source_device, source_inode) ||
        (unsigned long long) source_status.st_size != expected_size) {
        close(source_fd);
        int cleanup = finish_stage(parent_fd, stage_fd, argv[4]);
        close(parent_fd);
        return cleanup == 0 ? X_SOURCE_CHANGED : X_OUTCOME_UNCERTAIN;
    }

    int payload_fd = openat(
        stage_fd,
        PAYLOAD_NAME,
        O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC,
        0600
    );
    if (payload_fd < 0) {
        int result = write_errno(errno);
        close(source_fd);
        int cleanup = finish_stage(parent_fd, stage_fd, argv[4]);
        close(parent_fd);
        return cleanup == 0 ? result : X_OUTCOME_UNCERTAIN;
    }
    struct stat payload_status;
    if (fstat(payload_fd, &payload_status) != 0) {
        close(payload_fd);
        close(source_fd);
        unlinkat(stage_fd, PAYLOAD_NAME, 0);
        finish_stage(parent_fd, stage_fd, argv[4]);
        close(parent_fd);
        return X_OUTCOME_UNCERTAIN;
    }
    if (!S_ISREG(payload_status.st_mode) || payload_status.st_uid != 0 ||
        (payload_status.st_mode & 07777) != 0600) {
        close(payload_fd);
        close(source_fd);
        int cleanup = remove_known_payload(stage_fd, &payload_status);
        int stage_cleanup = finish_stage(parent_fd, stage_fd, argv[4]);
        close(parent_fd);
        if (cleanup != 0 || stage_cleanup != 0) return X_OUTCOME_UNCERTAIN;
        return X_STAGE_INVALID;
    }

    unsigned char buffer[65536];
    unsigned long long copied = 0;
    int result = 0;
    for (;;) {
        ssize_t read_count;
        do {
            read_count = read(source_fd, buffer, sizeof(buffer));
        } while (read_count < 0 && errno == EINTR);
        if (read_count < 0) {
            result = source_errno(errno);
            break;
        }
        if (read_count == 0) break;
        ssize_t offset = 0;
        while (offset < read_count) {
            ssize_t write_count;
            do {
                write_count = write(payload_fd, buffer + offset, (size_t) (read_count - offset));
            } while (write_count < 0 && errno == EINTR);
            if (write_count < 0) {
                result = write_errno(errno);
                break;
            }
            if (write_count == 0) {
                result = X_IO;
                break;
            }
            offset += write_count;
            copied += (unsigned long long) write_count;
        }
        if (result != 0) break;
    }
    if (result == 0 && copied != expected_size) result = X_SOURCE_CHANGED;
    if (result == 0) {
        int synced;
        do {
            synced = fsync(payload_fd);
        } while (synced != 0 && errno == EINTR);
        if (synced != 0) result = write_errno(errno);
    }
    if (result == 0) {
        struct stat current_source;
        if (fstat(source_fd, &current_source) != 0 ||
            !identity_matches(&current_source, source_device, source_inode) ||
            (unsigned long long) current_source.st_size != expected_size ||
            current_source.st_mtim.tv_sec != source_status.st_mtim.tv_sec ||
            current_source.st_mtim.tv_nsec != source_status.st_mtim.tv_nsec ||
            current_source.st_ctim.tv_sec != source_status.st_ctim.tv_sec ||
            current_source.st_ctim.tv_nsec != source_status.st_ctim.tv_nsec) {
            result = X_SOURCE_CHANGED;
        }
    }
    close(source_fd);

    if (result == 0) {
        long renamed = syscall(
            SYS_renameat2,
            stage_fd,
            PAYLOAD_NAME,
            parent_fd,
            argv[5],
            RENAME_NOREPLACE
        );
        if (renamed != 0) result = write_errno(errno);
    }

    if (result != 0) {
        int cleanup = remove_known_payload(stage_fd, &payload_status);
        close(payload_fd);
        int stage_cleanup = finish_stage(parent_fd, stage_fd, argv[4]);
        close(parent_fd);
        if (cleanup != 0 || stage_cleanup != 0) return X_OUTCOME_UNCERTAIN;
        return result;
    }

    close(payload_fd);
    int stage_cleanup = finish_stage(parent_fd, stage_fd, argv[4]);
    if (stage_cleanup != 0) {
        close(parent_fd);
        return X_OUTCOME_UNCERTAIN;
    }
    if (fsync(parent_fd) != 0 && errno != EINVAL && errno != EROFS) {
        close(parent_fd);
        return X_OUTCOME_UNCERTAIN;
    }
    printf(
        "%llu:%llu:%llu\n",
        (unsigned long long) payload_status.st_dev,
        (unsigned long long) payload_status.st_ino,
        copied
    );
    close(parent_fd);
    return 0;
}

static int remove_stage(int argc, char **argv) {
    if (argc != 9 || !stage_name_ok(argv[4])) return X_USAGE;
    unsigned long long parent_device;
    unsigned long long parent_inode;
    unsigned long long stage_device;
    unsigned long long stage_inode;
    if (!parse_identity(argv, 5, &parent_device, &parent_inode) ||
        !parse_identity(argv, 7, &stage_device, &stage_inode)) {
        return X_USAGE;
    }
    int parent_fd = open_parent(argv[2], argv[3], parent_device, parent_inode);
    if (parent_fd < 0) return -parent_fd;
    int stage_fd = open_stage(parent_fd, argv[4], stage_device, stage_inode);
    if (stage_fd < 0) {
        struct stat missing;
        int absent = fstatat(parent_fd, argv[4], &missing, AT_SYMLINK_NOFOLLOW) != 0 && errno == ENOENT;
        close(parent_fd);
        return absent ? 0 : X_STAGE_INVALID;
    }

    struct stat payload;
    if (fstatat(stage_fd, PAYLOAD_NAME, &payload, AT_SYMLINK_NOFOLLOW) == 0) {
        if (!S_ISREG(payload.st_mode) || payload.st_uid != 0 ||
            (payload.st_mode & 07777) != 0600 || payload.st_nlink != 1) {
            close(stage_fd);
            close(parent_fd);
            return X_STAGE_INVALID;
        }
        if (unlinkat(stage_fd, PAYLOAD_NAME, 0) != 0 && errno != ENOENT) {
            close(stage_fd);
            close(parent_fd);
            return X_STAGE_INVALID;
        }
    } else if (errno != ENOENT) {
        close(stage_fd);
        close(parent_fd);
        return X_STAGE_INVALID;
    }
    int result = finish_stage(parent_fd, stage_fd, argv[4]);
    close(parent_fd);
    return result;
}

int main(int argc, char **argv) {
    if (argc < 2) return X_USAGE;
    if (strcmp(argv[1], "prepare-stage") == 0) return prepare_stage(argc, argv);
    if (strcmp(argv[1], "copy-publish") == 0) return copy_publish(argc, argv);
    if (strcmp(argv[1], "remove-stage") == 0) return remove_stage(argc, argv);
    return X_USAGE;
}
