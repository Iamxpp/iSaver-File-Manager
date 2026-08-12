#define _GNU_SOURCE

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/fs.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/vfs.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

enum {
    X_NOT_FOUND = 44,
    X_NOT_DIRECTORY = 45,
    X_NOT_READABLE = 46,
    X_SOURCE_UNREADABLE = 56,
    X_NOT_WRITABLE = 48,
    X_ALREADY_EXISTS = 49,
    X_NO_SPACE = 50,
    X_IO = 51,
    X_PARENT_INVALID = 52,
    X_STAGE_INVALID = 53,
    X_SOURCE_CHANGED = 54,
    X_OUTCOME_UNCERTAIN = 55,
    X_OUTPUT_LIMIT = 57,
    X_CROSS_DEVICE = 58,
    X_MOVE_PARTIAL = 59,
    X_USAGE = 64,
};

static const char PAYLOAD_NAME[] = "payload";

#ifndef FUSE_SUPER_MAGIC
#define FUSE_SUPER_MAGIC 0x65735546
#endif

#define SDCARDFS_SUPER_MAGIC 0x5dca2df5

enum {
    LIST_MAX_ITEMS = 100000,
};

static const size_t LIST_MAX_FIELD_BYTES = 1048576U;
static const size_t LIST_MAX_PROTOCOL_BYTES = 67108864U;
static const unsigned long long READ_FILE_MAX_BYTES = 268435456ULL;
static const size_t READ_FILE_CHUNK_BYTES = 49152U;

struct output_buffer {
    char *data;
    size_t length;
    size_t capacity;
};

static int list_errno(int error) {
    if (error == ENOENT) return X_NOT_FOUND;
    if (error == ENOTDIR || error == ELOOP) return X_NOT_DIRECTORY;
    if (error == EACCES || error == EPERM) return X_NOT_READABLE;
    return X_IO;
}

static int nofollow_open_path(const char *path, const char **open_path, char **owned_path) {
    size_t original_length = strlen(path);
    size_t trimmed_length = original_length;
    while (trimmed_length > 1U && path[trimmed_length - 1U] == '/') {
        trimmed_length -= 1U;
    }
    if (trimmed_length == original_length) {
        *open_path = path;
        *owned_path = NULL;
        return 0;
    }
    if (trimmed_length == SIZE_MAX) return X_OUTPUT_LIMIT;
    char *trimmed = malloc(trimmed_length + 1U);
    if (trimmed == NULL) return X_IO;
    memcpy(trimmed, path, trimmed_length);
    trimmed[trimmed_length] = '\0';
    *open_path = trimmed;
    *owned_path = trimmed;
    return 0;
}

static int base64_length(size_t input_length, size_t *encoded_length) {
    if (input_length > SIZE_MAX - 2U) return X_OUTPUT_LIMIT;
    size_t groups = (input_length + 2U) / 3U;
    if (groups > SIZE_MAX / 4U) return X_OUTPUT_LIMIT;
    size_t length = groups * 4U;
    if (length > LIST_MAX_FIELD_BYTES) return X_OUTPUT_LIMIT;
    *encoded_length = length;
    return 0;
}

static int buffer_reserve(struct output_buffer *buffer, size_t additional) {
    if (additional > LIST_MAX_PROTOCOL_BYTES - buffer->length) return X_OUTPUT_LIMIT;
    size_t required = buffer->length + additional;
    if (required <= buffer->capacity) return 0;

    size_t capacity = buffer->capacity == 0U ? 4096U : buffer->capacity;
    while (capacity < required) {
        if (capacity > LIST_MAX_PROTOCOL_BYTES / 2U) {
            capacity = LIST_MAX_PROTOCOL_BYTES;
            break;
        }
        capacity *= 2U;
    }
    if (capacity < required) return X_OUTPUT_LIMIT;
    char *resized = realloc(buffer->data, capacity);
    if (resized == NULL) return X_IO;
    buffer->data = resized;
    buffer->capacity = capacity;
    return 0;
}

static int buffer_append_bytes(
    struct output_buffer *buffer,
    const char *bytes,
    size_t byte_count
) {
    int result = buffer_reserve(buffer, byte_count);
    if (result != 0) return result;
    memcpy(buffer->data + buffer->length, bytes, byte_count);
    buffer->length += byte_count;
    return 0;
}

static int buffer_append_text(struct output_buffer *buffer, const char *text) {
    return buffer_append_bytes(buffer, text, strlen(text));
}

static int buffer_append_character(struct output_buffer *buffer, char character) {
    return buffer_append_bytes(buffer, &character, 1U);
}

static int buffer_append_base64(
    struct output_buffer *buffer,
    const unsigned char *input,
    size_t input_length
) {
    static const char alphabet[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    size_t encoded_length;
    int result = base64_length(input_length, &encoded_length);
    if (result != 0) return result;
    result = buffer_reserve(buffer, encoded_length);
    if (result != 0) return result;

    char *output = buffer->data + buffer->length;
    size_t input_index = 0U;
    size_t output_index = 0U;
    while (input_index + 3U <= input_length) {
        uint32_t value = ((uint32_t) input[input_index] << 16U) |
            ((uint32_t) input[input_index + 1U] << 8U) |
            (uint32_t) input[input_index + 2U];
        output[output_index++] = alphabet[(value >> 18U) & 0x3fU];
        output[output_index++] = alphabet[(value >> 12U) & 0x3fU];
        output[output_index++] = alphabet[(value >> 6U) & 0x3fU];
        output[output_index++] = alphabet[value & 0x3fU];
        input_index += 3U;
    }
    size_t remaining = input_length - input_index;
    if (remaining == 1U) {
        uint32_t value = (uint32_t) input[input_index] << 16U;
        output[output_index++] = alphabet[(value >> 18U) & 0x3fU];
        output[output_index++] = alphabet[(value >> 12U) & 0x3fU];
        output[output_index++] = '=';
        output[output_index++] = '=';
    } else if (remaining == 2U) {
        uint32_t value = ((uint32_t) input[input_index] << 16U) |
            ((uint32_t) input[input_index + 1U] << 8U);
        output[output_index++] = alphabet[(value >> 18U) & 0x3fU];
        output[output_index++] = alphabet[(value >> 12U) & 0x3fU];
        output[output_index++] = alphabet[(value >> 6U) & 0x3fU];
        output[output_index++] = '=';
    }
    if (output_index != encoded_length) return X_IO;
    buffer->length += encoded_length;
    return 0;
}

static int retry_fstat(int descriptor, struct stat *status) {
    int result;
    do {
        result = fstat(descriptor, status);
    } while (result != 0 && errno == EINTR);
    return result;
}

static int retry_fstatat(
    int directory_fd,
    const char *name,
    struct stat *status,
    int flags
) {
    int result;
    do {
        result = fstatat(directory_fd, name, status, flags);
    } while (result != 0 && errno == EINTR);
    return result;
}

static int retry_faccessat(int directory_fd, const char *name, int mode, int flags) {
    int result;
    do {
        result = faccessat(directory_fd, name, mode, flags);
    } while (result != 0 && errno == EINTR);
    return result;
}

static int access_flags_unsupported(int error) {
    return error == EINVAL || error == ENOSYS || error == EOPNOTSUPP;
}

static int mode_allows(const struct stat *status, int requested) {
    if ((requested & ~(R_OK | W_OK)) != 0) return 0;
    if (geteuid() == 0) return 1;

    mode_t permissions;
    if (geteuid() == status->st_uid) {
        permissions = (status->st_mode >> 6U) & 07U;
    } else if (getegid() == status->st_gid) {
        permissions = (status->st_mode >> 3U) & 07U;
    } else {
        permissions = status->st_mode & 07U;
    }
    if ((requested & R_OK) != 0 && (permissions & 04U) == 0) return 0;
    if ((requested & W_OK) != 0 && (permissions & 02U) == 0) return 0;
    return 1;
}

static int parent_capability(
    int directory_fd,
    const struct stat *status,
    int requested
) {
    if (requested == R_OK) return 1;
    if (retry_faccessat(directory_fd, ".", requested, AT_EACCESS) == 0) return 1;
    if (!access_flags_unsupported(errno)) return 0;

    /* The held dirfd makes "." safe from path replacement. Android 10/11 reject AT_EACCESS,
       so equal real/effective IDs can retry flags=0 and retain read-only mount enforcement. */
    if (getuid() == geteuid()) {
        if (retry_faccessat(directory_fd, ".", requested, 0) == 0) return 1;
        if (!access_flags_unsupported(errno)) return 0;
    }
    return mode_allows(status, requested);
}

static int entry_capability(
    int directory_fd,
    const char *name,
    const struct stat *status,
    int requested,
    int parent_writable
) {
    if (retry_faccessat(
        directory_fd,
        name,
        requested,
        AT_EACCESS | AT_SYMLINK_NOFOLLOW
    ) == 0) {
        return 1;
    }
    if (!access_flags_unsupported(errno)) return 0;

    /* Android 11 can reject NOFOLLOW access flags before a syscall. The mode-only fallback is a
       conservative UI hint over the already captured lstat; every later write revalidates live. */
    if (requested == W_OK && !parent_writable) return 0;
    return mode_allows(status, requested);
}

static int build_child_path(
    const char *parent,
    const char *name,
    char **child,
    size_t *child_length
) {
    size_t parent_length = strlen(parent);
    size_t name_length = strlen(name);
    size_t separator_length =
        parent_length > 0U && parent[parent_length - 1U] == '/' ? 0U : 1U;
    if (parent_length > SIZE_MAX - separator_length) return X_OUTPUT_LIMIT;
    size_t prefix_length = parent_length + separator_length;
    if (name_length > SIZE_MAX - prefix_length) return X_OUTPUT_LIMIT;
    size_t length = prefix_length + name_length;
    size_t encoded_length;
    int result = base64_length(length, &encoded_length);
    if (result != 0) return result;
    (void) encoded_length;
    if (length == SIZE_MAX) return X_OUTPUT_LIMIT;

    char *path = malloc(length + 1U);
    if (path == NULL) return X_IO;
    memcpy(path, parent, parent_length);
    if (separator_length != 0U) path[parent_length] = '/';
    memcpy(path + prefix_length, name, name_length);
    path[length] = '\0';
    *child = path;
    *child_length = length;
    return 0;
}

static int append_listing_record(
    struct output_buffer *buffer,
    const char *name,
    const char *path,
    size_t path_length,
    const struct stat *status,
    int symlink_hint,
    int readable,
    int writable
) {
    const char *type = "other";
    const char *size = "-";
    const char *modified = "-";
    int symbolic_link = symlink_hint;
    char size_text[32];
    char modified_text[32];

    if (status != NULL) {
        symbolic_link = S_ISLNK(status->st_mode) ? 1 : 0;
        if (!symbolic_link && S_ISDIR(status->st_mode)) {
            type = "directory";
        } else if (!symbolic_link && S_ISREG(status->st_mode)) {
            type = "file";
            if (status->st_size >= 0) {
                int size_length = snprintf(
                    size_text,
                    sizeof(size_text),
                    "%lld",
                    (long long) status->st_size
                );
                if (size_length < 0 || (size_t) size_length >= sizeof(size_text)) return X_IO;
                size = size_text;
            }
        }
        int modified_length = snprintf(
            modified_text,
            sizeof(modified_text),
            "%lld",
            (long long) status->st_mtim.tv_sec
        );
        if (modified_length < 0 ||
            (size_t) modified_length >= sizeof(modified_text)) return X_IO;
        modified = modified_text;
    }

    int result = buffer_append_base64(
        buffer,
        (const unsigned char *) name,
        strlen(name)
    );
    if (result != 0) return result;
    result = buffer_append_character(buffer, '\t');
    if (result != 0) return result;
    result = buffer_append_base64(
        buffer,
        (const unsigned char *) path,
        path_length
    );
    if (result != 0) return result;
    result = buffer_append_character(buffer, '\t');
    if (result != 0) return result;
    result = buffer_append_text(buffer, type);
    if (result != 0) return result;
    result = buffer_append_character(buffer, '\t');
    if (result != 0) return result;
    result = buffer_append_text(buffer, size);
    if (result != 0) return result;
    result = buffer_append_character(buffer, '\t');
    if (result != 0) return result;
    result = buffer_append_text(buffer, modified);
    if (result != 0) return result;

    char capability_fields[] = {
        '\t', readable ? '1' : '0',
        '\t', writable ? '1' : '0',
        '\t', symbolic_link ? '1' : '0',
        '\n',
    };
    return buffer_append_bytes(buffer, capability_fields, sizeof(capability_fields));
}

static int write_stdout(const char *bytes, size_t byte_count) {
    if (signal(SIGPIPE, SIG_IGN) == SIG_ERR) return X_IO;
    size_t offset = 0U;
    while (offset < byte_count) {
        ssize_t written;
        do {
            written = write(STDOUT_FILENO, bytes + offset, byte_count - offset);
        } while (written < 0 && errno == EINTR);
        if (written <= 0) return X_IO;
        offset += (size_t) written;
    }
    return 0;
}

static int write_base64_line(const unsigned char *bytes, size_t byte_count) {
    struct output_buffer output = {0};
    int result = buffer_append_base64(&output, bytes, byte_count);
    if (result == 0) result = buffer_append_character(&output, '\n');
    if (result == 0) result = write_stdout(output.data, output.length);
    free(output.data);
    return result;
}

static int read_file_stdout(int argc, char **argv) {
    if (argc != 3 || argv[2] == NULL || argv[2][0] != '/') return X_USAGE;
    const char *open_path;
    char *owned_open_path;
    int result = nofollow_open_path(argv[2], &open_path, &owned_open_path);
    if (result != 0) return result;

    int source_fd;
    do {
        source_fd = open(open_path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    } while (source_fd < 0 && errno == EINTR);
    free(owned_open_path);
    if (source_fd < 0) return X_SOURCE_UNREADABLE;

    struct stat initial;
    if (retry_fstat(source_fd, &initial) != 0 || !S_ISREG(initial.st_mode) || initial.st_size < 0) {
        close(source_fd);
        return X_SOURCE_UNREADABLE;
    }
    if ((unsigned long long) initial.st_size > READ_FILE_MAX_BYTES) {
        close(source_fd);
        return X_OUTPUT_LIMIT;
    }

    char header[96];
    int header_length = snprintf(
        header,
        sizeof(header),
        "ISAVER_FILE_V1\t%llu\n",
        (unsigned long long) initial.st_size
    );
    if (header_length < 0 || (size_t) header_length >= sizeof(header)) {
        close(source_fd);
        return X_IO;
    }
    result = write_stdout(header, (size_t) header_length);
    unsigned char buffer[49152];
    unsigned long long copied = 0ULL;
    while (result == 0) {
        ssize_t read_count;
        do {
            read_count = read(source_fd, buffer, READ_FILE_CHUNK_BYTES);
        } while (read_count < 0 && errno == EINTR);
        if (read_count < 0) {
            result = X_SOURCE_UNREADABLE;
            break;
        }
        if (read_count == 0) break;
        copied += (unsigned long long) read_count;
        if (copied > (unsigned long long) initial.st_size) {
            result = X_SOURCE_CHANGED;
            break;
        }
        result = write_base64_line(buffer, (size_t) read_count);
    }

    struct stat final_status;
    if (result == 0 &&
        (retry_fstat(source_fd, &final_status) != 0 ||
         final_status.st_dev != initial.st_dev || final_status.st_ino != initial.st_ino ||
         final_status.st_size != initial.st_size || copied != (unsigned long long) initial.st_size ||
         final_status.st_mtim.tv_sec != initial.st_mtim.tv_sec ||
         final_status.st_mtim.tv_nsec != initial.st_mtim.tv_nsec ||
         final_status.st_ctim.tv_sec != initial.st_ctim.tv_sec ||
         final_status.st_ctim.tv_nsec != initial.st_ctim.tv_nsec)) {
        result = X_SOURCE_CHANGED;
    }
    close(source_fd);
    return result;
}

static int list_directory(int argc, char **argv) {
    if (argc != 3) return X_USAGE;

    const char *open_path;
    char *owned_open_path;
    int result = nofollow_open_path(argv[2], &open_path, &owned_open_path);
    if (result != 0) return result;

    int directory_fd;
    do {
        directory_fd = open(
            open_path,
            O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC
        );
    } while (directory_fd < 0 && errno == EINTR);
    free(owned_open_path);
    if (directory_fd < 0) return list_errno(errno);

    struct stat parent_status;
    if (retry_fstat(directory_fd, &parent_status) != 0 ||
        !S_ISDIR(parent_status.st_mode) ||
        (unsigned long long) parent_status.st_dev > (unsigned long long) LLONG_MAX ||
        (unsigned long long) parent_status.st_ino > (unsigned long long) LLONG_MAX) {
        close(directory_fd);
        return X_IO;
    }

    struct output_buffer output = {0};
    int parent_readable = parent_capability(directory_fd, &parent_status, R_OK);
    int parent_writable = parent_capability(directory_fd, &parent_status, W_OK);
    char header[160];
    int header_length = snprintf(
        header,
        sizeof(header),
        "ISAVER_LIST_V1\t%llu\t%llu\t%d\t%d\n",
        (unsigned long long) parent_status.st_dev,
        (unsigned long long) parent_status.st_ino,
        parent_readable,
        parent_writable
    );
    if (header_length < 0 || (size_t) header_length >= sizeof(header)) {
        close(directory_fd);
        return X_IO;
    }
    result = buffer_append_bytes(&output, header, (size_t) header_length);
    if (result != 0) {
        free(output.data);
        close(directory_fd);
        return result;
    }

    DIR *directory = fdopendir(directory_fd);
    if (directory == NULL) {
        free(output.data);
        close(directory_fd);
        return X_IO;
    }
    int stream_fd = dirfd(directory);
    if (stream_fd < 0) {
        free(output.data);
        closedir(directory);
        return X_IO;
    }

    size_t item_count = 0U;
    for (;;) {
        errno = 0;
        struct dirent *entry = readdir(directory);
        if (entry == NULL) {
            if (errno == EINTR) continue;
            if (errno != 0) result = X_IO;
            break;
        }
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) {
            continue;
        }
        if (item_count >= LIST_MAX_ITEMS) {
            result = X_OUTPUT_LIMIT;
            break;
        }
        item_count += 1U;

        char *child_path = NULL;
        size_t child_path_length = 0U;
        result = build_child_path(
            argv[2],
            entry->d_name,
            &child_path,
            &child_path_length
        );
        if (result != 0) break;

        struct stat child_status;
        int status_available = retry_fstatat(
            stream_fd,
            entry->d_name,
            &child_status,
            AT_SYMLINK_NOFOLLOW
        ) == 0;
        int symbolic_link = status_available
            ? (S_ISLNK(child_status.st_mode) ? 1 : 0)
            : 0;
        int readable = status_available && !symbolic_link
            ? entry_capability(
                stream_fd,
                entry->d_name,
                &child_status,
                R_OK,
                parent_writable
            )
            : 0;
        int writable = status_available && !symbolic_link
            ? entry_capability(
                stream_fd,
                entry->d_name,
                &child_status,
                W_OK,
                parent_writable
            )
            : 0;
        result = append_listing_record(
            &output,
            entry->d_name,
            child_path,
            child_path_length,
            status_available ? &child_status : NULL,
            symbolic_link,
            readable,
            writable
        );
        free(child_path);
        if (result != 0) break;
    }

    if (closedir(directory) != 0 && result == 0) result = X_IO;
    if (result == 0) result = write_stdout(output.data, output.length);
    free(output.data);
    return result;
}

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

static int extraction_stage_name_ok(const char *name) {
    static const char prefix[] = ".isaver-extract-";
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

static int relative_path_ok(const char *path, int allow_empty) {
    if (path == NULL) return 0;
    size_t length = strlen(path);
    if (length == 0U) return allow_empty;
    if (path[0] == '/' || path[length - 1U] == '/') return 0;
    size_t component_start = 0U;
    for (size_t index = 0U; index <= length; ++index) {
        if (index != length && path[index] != '/') continue;
        size_t component_length = index - component_start;
        if (component_length == 0U || component_length > 255U) return 0;
        if (component_length == 1U && path[component_start] == '.') return 0;
        if (component_length == 2U && path[component_start] == '.' &&
            path[component_start + 1U] == '.') return 0;
        component_start = index + 1U;
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

static int identity_matches(
    const struct stat *status,
    unsigned long long device,
    unsigned long long inode
) {
    return (unsigned long long) status->st_dev == device &&
        (unsigned long long) status->st_ino == inode;
}

static int regular_file_version_matches(
    const struct stat *current,
    const struct stat *initial
) {
    return S_ISREG(current->st_mode) &&
        current->st_dev == initial->st_dev &&
        current->st_ino == initial->st_ino &&
        current->st_size == initial->st_size &&
        current->st_mtim.tv_sec == initial->st_mtim.tv_sec &&
        current->st_mtim.tv_nsec == initial->st_mtim.tv_nsec &&
        current->st_ctim.tv_sec == initial->st_ctim.tv_sec &&
        current->st_ctim.tv_nsec == initial->st_ctim.tv_nsec;
}

static int is_emulated_storage_fd(int fd) {
    struct statfs file_system;
    if (fstatfs(fd, &file_system) != 0) return 0;
    return (unsigned long) file_system.f_type == (unsigned long) FUSE_SUPER_MAGIC ||
        (unsigned long) file_system.f_type == (unsigned long) SDCARDFS_SUPER_MAGIC;
}

static int emulated_mode_is_safe(mode_t permissions) {
    return (permissions & 0002) == 0;
}

static int stage_security_valid(int parent_fd, const struct stat *status) {
    if (!S_ISDIR(status->st_mode)) return 0;
    mode_t permissions = status->st_mode & 07777;
    if (status->st_uid == 0 && permissions == 0700) return 1;
    return is_emulated_storage_fd(parent_fd) && emulated_mode_is_safe(permissions);
}

static int payload_security_valid(int parent_fd, const struct stat *status) {
    if (!S_ISREG(status->st_mode) || status->st_nlink != 1) return 0;
    mode_t permissions = status->st_mode & 07777;
    if (status->st_uid == 0 && permissions == 0600) return 1;
    return is_emulated_storage_fd(parent_fd) && emulated_mode_is_safe(permissions);
}

static void print_stage_security_diagnostic(int parent_fd, const struct stat *status) {
    struct statfs file_system;
    unsigned long fs_type = fstatfs(parent_fd, &file_system) == 0
        ? (unsigned long) file_system.f_type
        : 0UL;
    fprintf(
        stderr,
        "stage security invalid: mode=%o uid=%llu gid=%llu nlink=%llu fs=0x%lx\n",
        (unsigned int) (status->st_mode & 07777),
        (unsigned long long) status->st_uid,
        (unsigned long long) status->st_gid,
        (unsigned long long) status->st_nlink,
        fs_type
    );
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
    if (fstat(stage_fd, &status) != 0 ||
        !identity_matches(&status, device, inode) ||
        !stage_security_valid(parent_fd, &status)) {
        close(stage_fd);
        return -X_STAGE_INVALID;
    }
    return stage_fd;
}

static int open_relative_directory(int stage_fd, const char *relative, int create) {
    if (!relative_path_ok(relative, 1)) return -X_USAGE;
    int current_fd = fcntl(stage_fd, F_DUPFD_CLOEXEC, 0);
    if (current_fd < 0) return -X_IO;
    if (relative[0] == '\0') return current_fd;
    char *copy = strdup(relative);
    if (copy == NULL) {
        close(current_fd);
        return -X_IO;
    }
    char *save = NULL;
    char *component = strtok_r(copy, "/", &save);
    while (component != NULL) {
        if (!basename_ok(component)) {
            free(copy);
            close(current_fd);
            return -X_USAGE;
        }
        if (create && mkdirat(current_fd, component, 0700) != 0 && errno != EEXIST) {
            int result = write_errno(errno);
            free(copy);
            close(current_fd);
            return -result;
        }
        int child_fd = openat(
            current_fd,
            component,
            O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC
        );
        if (child_fd < 0) {
            free(copy);
            close(current_fd);
            return -X_STAGE_INVALID;
        }
        struct stat child_status;
        if (fstat(child_fd, &child_status) != 0 ||
            !stage_security_valid(current_fd, &child_status)) {
            close(child_fd);
            free(copy);
            close(current_fd);
            return -X_STAGE_INVALID;
        }
        close(current_fd);
        current_fd = child_fd;
        component = strtok_r(NULL, "/", &save);
    }
    free(copy);
    return current_fd;
}

static int remove_known_extract_file(
    int directory_fd,
    const char *name,
    const struct stat *expected
) {
    struct stat current;
    if (fstatat(directory_fd, name, &current, AT_SYMLINK_NOFOLLOW) != 0) {
        return errno == ENOENT ? 0 : X_STAGE_INVALID;
    }
    int same = current.st_dev == expected->st_dev && current.st_ino == expected->st_ino;
    int emulated = is_emulated_storage_fd(directory_fd) &&
        payload_security_valid(directory_fd, &current) &&
        current.st_size == expected->st_size;
    if (!S_ISREG(current.st_mode) || (!same && !emulated)) return X_STAGE_INVALID;
    return unlinkat(directory_fd, name, 0) == 0 || errno == ENOENT ? 0 : X_STAGE_INVALID;
}

static int remove_known_payload(int stage_fd, const struct stat *expected) {
    struct stat current;
    if (fstatat(stage_fd, PAYLOAD_NAME, &current, AT_SYMLINK_NOFOLLOW) != 0) {
        return errno == ENOENT ? 0 : X_STAGE_INVALID;
    }
    int identity_matches_expected = current.st_dev == expected->st_dev &&
        current.st_ino == expected->st_ino;
    int emulated_equivalent = is_emulated_storage_fd(stage_fd) &&
        payload_security_valid(stage_fd, &current) &&
        current.st_size == expected->st_size;
    if (!S_ISREG(current.st_mode) || (!identity_matches_expected && !emulated_equivalent)) {
        return X_STAGE_INVALID;
    }
    return unlinkat(stage_fd, PAYLOAD_NAME, 0) == 0 || errno == ENOENT ? 0 : X_STAGE_INVALID;
}

static int remove_known_final(int parent_fd, const char *name, const struct stat *expected) {
    struct stat current;
    if (fstatat(parent_fd, name, &current, AT_SYMLINK_NOFOLLOW) != 0) {
        return errno == ENOENT ? 0 : X_OUTCOME_UNCERTAIN;
    }
    if (!S_ISREG(current.st_mode) || current.st_dev != expected->st_dev ||
        current.st_ino != expected->st_ino) {
        return X_OUTCOME_UNCERTAIN;
    }
    return unlinkat(parent_fd, name, 0) == 0 || errno == ENOENT ? 0 : X_OUTCOME_UNCERTAIN;
}

static int publish_emulated_no_replace(
    int parent_fd,
    int stage_fd,
    const char *final_name,
    unsigned long long expected_size,
    const struct stat *payload_status,
    struct stat *published_status
) {
    int source_fd = openat(stage_fd, PAYLOAD_NAME, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (source_fd < 0) return X_IO;
    struct stat source_status;
    if (fstat(source_fd, &source_status) != 0 ||
        source_status.st_dev != payload_status->st_dev ||
        source_status.st_ino != payload_status->st_ino ||
        (unsigned long long) source_status.st_size != expected_size) {
        close(source_fd);
        return X_STAGE_INVALID;
    }

    int final_fd = openat(
        parent_fd,
        final_name,
        O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC,
        0600
    );
    if (final_fd < 0) {
        int result = write_errno(errno);
        close(source_fd);
        return result;
    }
    struct stat reserved;
    int reserved_valid = fstat(final_fd, &reserved) == 0;
    if (!reserved_valid || !payload_security_valid(parent_fd, &reserved)) {
        close(source_fd);
        close(final_fd);
        if (reserved_valid) remove_known_final(parent_fd, final_name, &reserved);
        return X_OUTCOME_UNCERTAIN;
    }

    unsigned char buffer[65536];
    unsigned long long copied = 0;
    int result = 0;
    while (copied < expected_size) {
        size_t wanted = expected_size - copied < sizeof(buffer)
            ? (size_t) (expected_size - copied)
            : sizeof(buffer);
        ssize_t read_count;
        do {
            read_count = read(source_fd, buffer, wanted);
        } while (read_count < 0 && errno == EINTR);
        if (read_count <= 0) {
            result = X_IO;
            break;
        }
        size_t offset = 0;
        while (offset < (size_t) read_count) {
            ssize_t write_count;
            do {
                write_count = write(final_fd, buffer + offset, (size_t) read_count - offset);
            } while (write_count < 0 && errno == EINTR);
            if (write_count <= 0) {
                result = write_errno(errno);
                break;
            }
            offset += (size_t) write_count;
            copied += (unsigned long long) write_count;
        }
        if (result != 0) break;
    }
    if (result == 0 && fsync(final_fd) != 0 && errno != EINVAL) result = write_errno(errno);
    if (result == 0) {
        struct stat verified;
        if (fstat(final_fd, &verified) != 0 ||
            !payload_security_valid(parent_fd, &verified) ||
            verified.st_dev != reserved.st_dev || verified.st_ino != reserved.st_ino ||
            (unsigned long long) verified.st_size != expected_size) {
            result = X_OUTCOME_UNCERTAIN;
        } else {
            *published_status = verified;
        }
    }
    close(source_fd);
    close(final_fd);
    if (result != 0) {
        int cleanup = remove_known_final(parent_fd, final_name, &reserved);
        return cleanup == 0 ? result : X_OUTCOME_UNCERTAIN;
    }
    int payload_cleanup = remove_known_payload(stage_fd, payload_status);
    if (payload_cleanup != 0) {
        return X_OUTCOME_UNCERTAIN;
    }
    return 0;
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
        (require_secure_mode && !stage_security_valid(parent_fd, &current))) {
        close(stage_fd);
        return X_OUTCOME_UNCERTAIN;
    }
    int removed = unlinkat(parent_fd, stage_name, AT_REMOVEDIR);
    close(stage_fd);
    return removed == 0 ? 0 : X_OUTCOME_UNCERTAIN;
}

static int finish_stage(int parent_fd, int stage_fd, const char *stage_name) {
    struct stat held;
    if (fstat(stage_fd, &held) != 0 || !stage_security_valid(parent_fd, &held)) {
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
    if (!stage_security_valid(parent_fd, &status)) {
        print_stage_security_diagnostic(parent_fd, &status);
        int cleanup = remove_stage_path(parent_fd, stage_fd, argv[4], &status, 0);
        close(parent_fd);
        return cleanup == 0 ? X_STAGE_INVALID : X_OUTCOME_UNCERTAIN;
    }
    printf("%llu:%llu\n", (unsigned long long) status.st_dev, (unsigned long long) status.st_ino);
    close(stage_fd);
    close(parent_fd);
    return 0;
}

static int copy_publish_from_fd(
    int parent_fd,
    int stage_fd,
    const char *stage_name,
    const char *final_name,
    int source_fd,
    unsigned long long expected_size,
    const struct stat *source_initial
) {
    int payload_fd = openat(
        stage_fd,
        PAYLOAD_NAME,
        O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC,
        0600
    );
    if (payload_fd < 0) {
        int result = write_errno(errno);
        int cleanup = finish_stage(parent_fd, stage_fd, stage_name);
        close(parent_fd);
        return cleanup == 0 ? result : X_OUTCOME_UNCERTAIN;
    }
    struct stat payload_status;
    if (fstat(payload_fd, &payload_status) != 0) {
        close(payload_fd);
        unlinkat(stage_fd, PAYLOAD_NAME, 0);
        finish_stage(parent_fd, stage_fd, stage_name);
        close(parent_fd);
        return X_OUTCOME_UNCERTAIN;
    }
    if (!payload_security_valid(parent_fd, &payload_status)) {
        close(payload_fd);
        int cleanup = remove_known_payload(stage_fd, &payload_status);
        int stage_cleanup = finish_stage(parent_fd, stage_fd, stage_name);
        close(parent_fd);
        if (cleanup != 0 || stage_cleanup != 0) return X_OUTCOME_UNCERTAIN;
        return X_STAGE_INVALID;
    }

    unsigned char buffer[65536];
    unsigned long long copied = 0;
    int result = 0;
    while (copied < expected_size) {
        unsigned long long remaining = expected_size - copied;
        size_t wanted = remaining < sizeof(buffer) ? (size_t) remaining : sizeof(buffer);
        ssize_t read_count;
        do {
            read_count = read(source_fd, buffer, wanted);
        } while (read_count < 0 && errno == EINTR);
        if (read_count < 0) {
            result = X_SOURCE_UNREADABLE;
            break;
        }
        if (read_count == 0) {
            result = X_SOURCE_CHANGED;
            break;
        }
        size_t offset = 0U;
        while (offset < (size_t) read_count) {
            ssize_t write_count;
            do {
                write_count = write(
                    payload_fd,
                    buffer + offset,
                    (size_t) read_count - offset
                );
            } while (write_count < 0 && errno == EINTR);
            if (write_count < 0) {
                result = write_errno(errno);
                break;
            }
            if (write_count == 0) {
                result = X_IO;
                break;
            }
            offset += (size_t) write_count;
            copied += (unsigned long long) write_count;
        }
        if (result != 0) break;
    }
    if (result == 0) {
        unsigned char extra;
        ssize_t read_count;
        do {
            read_count = read(source_fd, &extra, 1U);
        } while (read_count < 0 && errno == EINTR);
        if (read_count < 0) {
            result = X_SOURCE_UNREADABLE;
        } else if (read_count != 0) {
            result = X_SOURCE_CHANGED;
        }
    }
    if (result == 0 && source_initial != NULL) {
        struct stat source_final;
        if (retry_fstat(source_fd, &source_final) != 0 ||
            source_final.st_dev != source_initial->st_dev ||
            source_final.st_ino != source_initial->st_ino ||
            source_final.st_size != source_initial->st_size ||
            source_final.st_mtim.tv_sec != source_initial->st_mtim.tv_sec ||
            source_final.st_mtim.tv_nsec != source_initial->st_mtim.tv_nsec ||
            source_final.st_ctim.tv_sec != source_initial->st_ctim.tv_sec ||
            source_final.st_ctim.tv_nsec != source_initial->st_ctim.tv_nsec) {
            result = X_SOURCE_CHANGED;
        }
    }
    if (result == 0) {
        int synced;
        do {
            synced = fsync(payload_fd);
        } while (synced != 0 && errno == EINTR);
        if (synced != 0) result = write_errno(errno);
    }
    if (result == 0) {
        struct stat verified_payload;
        if (fstat(payload_fd, &verified_payload) != 0) {
            result = X_IO;
        } else if (!payload_security_valid(parent_fd, &verified_payload) ||
            verified_payload.st_dev != payload_status.st_dev ||
            verified_payload.st_ino != payload_status.st_ino) {
            result = X_STAGE_INVALID;
        } else if ((unsigned long long) verified_payload.st_size != expected_size) {
            result = X_IO;
        } else {
            payload_status = verified_payload;
        }
    }

    if (result == 0) {
        long renamed = syscall(
            SYS_renameat2,
            stage_fd,
            PAYLOAD_NAME,
            parent_fd,
            final_name,
            RENAME_NOREPLACE
        );
        if (renamed != 0) {
            int rename_error = errno;
            if ((rename_error == EINVAL || rename_error == ENOTSUP || rename_error == EOPNOTSUPP) &&
                is_emulated_storage_fd(parent_fd)) {
                result = publish_emulated_no_replace(
                    parent_fd,
                    stage_fd,
                    final_name,
                    expected_size,
                    &payload_status,
                    &payload_status
                );
            } else {
                result = write_errno(rename_error);
            }
        }
    }

    if (result != 0) {
        int cleanup = remove_known_payload(stage_fd, &payload_status);
        close(payload_fd);
        int stage_cleanup = finish_stage(parent_fd, stage_fd, stage_name);
        close(parent_fd);
        if (cleanup != 0 || stage_cleanup != 0) return X_OUTCOME_UNCERTAIN;
        return result;
    }

    close(payload_fd);
    int stage_cleanup = finish_stage(parent_fd, stage_fd, stage_name);
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

static int copy_publish_stdin(int argc, char **argv) {
    if (argc != 11 || !stage_name_ok(argv[4]) || !basename_ok(argv[5])) return X_USAGE;
    unsigned long long parent_device;
    unsigned long long parent_inode;
    unsigned long long stage_device;
    unsigned long long stage_inode;
    unsigned long long expected_size;
    if (!parse_identity(argv, 6, &parent_device, &parent_inode) ||
        !parse_identity(argv, 8, &stage_device, &stage_inode) ||
        !parse_u64(argv[10], &expected_size)) {
        return X_USAGE;
    }

    int parent_fd = open_parent(argv[2], argv[3], parent_device, parent_inode);
    if (parent_fd < 0) return -parent_fd;
    int stage_fd = open_stage(parent_fd, argv[4], stage_device, stage_inode);
    if (stage_fd < 0) {
        close(parent_fd);
        return -stage_fd;
    }
    return copy_publish_from_fd(
        parent_fd, stage_fd, argv[4], argv[5], STDIN_FILENO, expected_size, NULL
    );
}

static int copy_file_publish(int argc, char **argv) {
    if (argc != 18 || !basename_ok(argv[4]) || !stage_name_ok(argv[11]) ||
        !basename_ok(argv[12])) {
        return X_USAGE;
    }
    unsigned long long source_parent_device;
    unsigned long long source_parent_inode;
    unsigned long long source_device;
    unsigned long long source_inode;
    unsigned long long target_parent_device;
    unsigned long long target_parent_inode;
    unsigned long long stage_device;
    unsigned long long stage_inode;
    unsigned long long expected_size;
    if (!parse_identity(argv, 5, &source_parent_device, &source_parent_inode) ||
        !parse_identity(argv, 7, &source_device, &source_inode) ||
        !parse_identity(argv, 13, &target_parent_device, &target_parent_inode) ||
        !parse_identity(argv, 15, &stage_device, &stage_inode) ||
        !parse_u64(argv[17], &expected_size)) {
        return X_USAGE;
    }

    int target_parent_fd = open_parent(
        argv[9], argv[10], target_parent_device, target_parent_inode
    );
    if (target_parent_fd < 0) return -target_parent_fd;
    int stage_fd = open_stage(target_parent_fd, argv[11], stage_device, stage_inode);
    if (stage_fd < 0) {
        close(target_parent_fd);
        return -stage_fd;
    }

    int source_parent_fd = open_parent(
        argv[2], argv[3], source_parent_device, source_parent_inode
    );
    if (source_parent_fd < 0) {
        int cleanup = finish_stage(target_parent_fd, stage_fd, argv[11]);
        close(target_parent_fd);
        return cleanup == 0 ? -source_parent_fd : X_OUTCOME_UNCERTAIN;
    }
    int source_fd;
    do {
        source_fd = openat(
            source_parent_fd,
            argv[4],
            O_RDONLY | O_NOFOLLOW | O_CLOEXEC
        );
    } while (source_fd < 0 && errno == EINTR);
    if (source_fd < 0) {
        int source_error = errno == ENOENT ? X_NOT_FOUND : X_SOURCE_UNREADABLE;
        close(source_parent_fd);
        int cleanup = finish_stage(target_parent_fd, stage_fd, argv[11]);
        close(target_parent_fd);
        return cleanup == 0 ? source_error : X_OUTCOME_UNCERTAIN;
    }

    struct stat source_status;
    if (retry_fstat(source_fd, &source_status) != 0 ||
        !S_ISREG(source_status.st_mode) ||
        source_status.st_size < 0 ||
        !identity_matches(&source_status, source_device, source_inode) ||
        (unsigned long long) source_status.st_size != expected_size) {
        close(source_fd);
        close(source_parent_fd);
        int cleanup = finish_stage(target_parent_fd, stage_fd, argv[11]);
        close(target_parent_fd);
        return cleanup == 0 ? X_SOURCE_CHANGED : X_OUTCOME_UNCERTAIN;
    }

    int result = copy_publish_from_fd(
        target_parent_fd,
        stage_fd,
        argv[11],
        argv[12],
        source_fd,
        expected_size,
        &source_status
    );
    close(source_fd);
    close(source_parent_fd);
    return result;
}

static int move_cross_device_noreplace(int argc, char **argv) {
    if (argc != 18 || !basename_ok(argv[4]) || !stage_name_ok(argv[11]) ||
        !basename_ok(argv[12])) {
        return X_USAGE;
    }
    unsigned long long source_parent_device;
    unsigned long long source_parent_inode;
    unsigned long long source_device;
    unsigned long long source_inode;
    unsigned long long target_parent_device;
    unsigned long long target_parent_inode;
    unsigned long long stage_device;
    unsigned long long stage_inode;
    unsigned long long expected_size;
    if (!parse_identity(argv, 5, &source_parent_device, &source_parent_inode) ||
        !parse_identity(argv, 7, &source_device, &source_inode) ||
        !parse_identity(argv, 13, &target_parent_device, &target_parent_inode) ||
        !parse_identity(argv, 15, &stage_device, &stage_inode) ||
        !parse_u64(argv[17], &expected_size)) {
        return X_USAGE;
    }

    int target_parent_fd = open_parent(
        argv[9], argv[10], target_parent_device, target_parent_inode
    );
    if (target_parent_fd < 0) return -target_parent_fd;
    int stage_fd = open_stage(target_parent_fd, argv[11], stage_device, stage_inode);
    if (stage_fd < 0) {
        close(target_parent_fd);
        return -stage_fd;
    }

    int source_parent_fd = open_parent(
        argv[2], argv[3], source_parent_device, source_parent_inode
    );
    if (source_parent_fd < 0) {
        int cleanup = finish_stage(target_parent_fd, stage_fd, argv[11]);
        close(target_parent_fd);
        return cleanup == 0 ? -source_parent_fd : X_OUTCOME_UNCERTAIN;
    }
    int source_fd;
    do {
        source_fd = openat(
            source_parent_fd,
            argv[4],
            O_RDONLY | O_NOFOLLOW | O_CLOEXEC
        );
    } while (source_fd < 0 && errno == EINTR);
    if (source_fd < 0) {
        int source_error = errno == ENOENT ? X_NOT_FOUND : X_SOURCE_UNREADABLE;
        close(source_parent_fd);
        int cleanup = finish_stage(target_parent_fd, stage_fd, argv[11]);
        close(target_parent_fd);
        return cleanup == 0 ? source_error : X_OUTCOME_UNCERTAIN;
    }

    struct stat source_initial;
    if (retry_fstat(source_fd, &source_initial) != 0 ||
        !S_ISREG(source_initial.st_mode) ||
        source_initial.st_size < 0 ||
        !identity_matches(&source_initial, source_device, source_inode) ||
        (unsigned long long) source_initial.st_size != expected_size) {
        close(source_fd);
        close(source_parent_fd);
        int cleanup = finish_stage(target_parent_fd, stage_fd, argv[11]);
        close(target_parent_fd);
        return cleanup == 0 ? X_SOURCE_CHANGED : X_OUTCOME_UNCERTAIN;
    }

    int result = copy_publish_from_fd(
        target_parent_fd,
        stage_fd,
        argv[11],
        argv[12],
        source_fd,
        expected_size,
        &source_initial
    );
    if (result != 0) {
        close(source_fd);
        close(source_parent_fd);
        return result;
    }

    struct stat held_after_publish;
    struct stat path_after_publish;
    if (retry_fstat(source_fd, &held_after_publish) != 0 ||
        retry_fstatat(
            source_parent_fd, argv[4], &path_after_publish, AT_SYMLINK_NOFOLLOW
        ) != 0 ||
        !regular_file_version_matches(&held_after_publish, &source_initial) ||
        !regular_file_version_matches(&path_after_publish, &source_initial)) {
        close(source_fd);
        close(source_parent_fd);
        return X_MOVE_PARTIAL;
    }

    if (unlinkat(source_parent_fd, argv[4], 0) != 0) {
        struct stat retained;
        int source_retained = retry_fstatat(
            source_parent_fd, argv[4], &retained, AT_SYMLINK_NOFOLLOW
        ) == 0 && regular_file_version_matches(&retained, &source_initial);
        close(source_fd);
        close(source_parent_fd);
        return source_retained ? X_MOVE_PARTIAL : X_OUTCOME_UNCERTAIN;
    }

    struct stat source_after_delete;
    if (retry_fstatat(
        source_parent_fd, argv[4], &source_after_delete, AT_SYMLINK_NOFOLLOW
    ) == 0) {
        if (identity_matches(&source_after_delete, source_device, source_inode)) {
            close(source_fd);
            close(source_parent_fd);
            return X_OUTCOME_UNCERTAIN;
        }
    } else if (errno != ENOENT) {
        close(source_fd);
        close(source_parent_fd);
        return X_OUTCOME_UNCERTAIN;
    }
    if (fsync(source_parent_fd) != 0 && errno != EINVAL && errno != EROFS) {
        close(source_fd);
        close(source_parent_fd);
        return X_OUTCOME_UNCERTAIN;
    }
    close(source_fd);
    close(source_parent_fd);
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
        if (!payload_security_valid(parent_fd, &payload)) {
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

static int move_noreplace(int argc, char **argv) {
    if (argc != 13 || !basename_ok(argv[4])) return X_USAGE;
    unsigned long long source_parent_device;
    unsigned long long source_parent_inode;
    unsigned long long source_device;
    unsigned long long source_inode;
    unsigned long long target_parent_device;
    unsigned long long target_parent_inode;
    if (!parse_identity(argv, 5, &source_parent_device, &source_parent_inode) ||
        !parse_identity(argv, 7, &source_device, &source_inode) ||
        !parse_identity(argv, 11, &target_parent_device, &target_parent_inode)) {
        return X_USAGE;
    }

    int source_parent_fd = open_parent(
        argv[2], argv[3], source_parent_device, source_parent_inode
    );
    if (source_parent_fd < 0) return -source_parent_fd;
    int target_parent_fd = open_parent(
        argv[9], argv[10], target_parent_device, target_parent_inode
    );
    if (target_parent_fd < 0) {
        close(source_parent_fd);
        return -target_parent_fd;
    }

    struct stat source_status;
    if (retry_fstatat(
        source_parent_fd, argv[4], &source_status, AT_SYMLINK_NOFOLLOW
    ) != 0) {
        int result = errno == ENOENT ? X_NOT_FOUND : X_SOURCE_CHANGED;
        close(target_parent_fd);
        close(source_parent_fd);
        return result;
    }
    if (!S_ISREG(source_status.st_mode) ||
        !identity_matches(&source_status, source_device, source_inode)) {
        close(target_parent_fd);
        close(source_parent_fd);
        return X_SOURCE_CHANGED;
    }

    struct stat target_status;
    if (retry_fstatat(
        target_parent_fd, argv[4], &target_status, AT_SYMLINK_NOFOLLOW
    ) == 0) {
        close(target_parent_fd);
        close(source_parent_fd);
        return X_ALREADY_EXISTS;
    }
    if (errno != ENOENT) {
        int result = write_errno(errno);
        close(target_parent_fd);
        close(source_parent_fd);
        return result;
    }
    if ((unsigned long long) source_status.st_dev != target_parent_device) {
        close(target_parent_fd);
        close(source_parent_fd);
        return X_CROSS_DEVICE;
    }

    long renamed = syscall(
        SYS_renameat2,
        source_parent_fd,
        argv[4],
        target_parent_fd,
        argv[4],
        RENAME_NOREPLACE
    );
    if (renamed != 0) {
        int error = errno;
        int result = error == EXDEV ? X_CROSS_DEVICE : write_errno(error);
        close(target_parent_fd);
        close(source_parent_fd);
        return result;
    }

    struct stat moved_status;
    struct stat stale_source;
    int source_absent = retry_fstatat(
        source_parent_fd, argv[4], &stale_source, AT_SYMLINK_NOFOLLOW
    ) != 0 && errno == ENOENT;
    if (retry_fstatat(
        target_parent_fd, argv[4], &moved_status, AT_SYMLINK_NOFOLLOW
    ) != 0 || !S_ISREG(moved_status.st_mode) ||
        !identity_matches(&moved_status, source_device, source_inode) || !source_absent) {
        close(target_parent_fd);
        close(source_parent_fd);
        return X_OUTCOME_UNCERTAIN;
    }
    if ((fsync(source_parent_fd) != 0 && errno != EINVAL && errno != EROFS) ||
        (fsync(target_parent_fd) != 0 && errno != EINVAL && errno != EROFS)) {
        close(target_parent_fd);
        close(source_parent_fd);
        return X_OUTCOME_UNCERTAIN;
    }

    printf(
        "%llu:%llu\n",
        (unsigned long long) moved_status.st_dev,
        (unsigned long long) moved_status.st_ino
    );
    close(target_parent_fd);
    close(source_parent_fd);
    return 0;
}

static int rename_noreplace(int argc, char **argv) {
    if (argc != 10 || !basename_ok(argv[4]) || !basename_ok(argv[9])) return X_USAGE;
    unsigned long long parent_device;
    unsigned long long parent_inode;
    unsigned long long source_device;
    unsigned long long source_inode;
    if (!parse_identity(argv, 5, &parent_device, &parent_inode) ||
        !parse_identity(argv, 7, &source_device, &source_inode)) {
        return X_USAGE;
    }

    int parent_fd = open_parent(argv[2], argv[3], parent_device, parent_inode);
    if (parent_fd < 0) return -parent_fd;
    struct stat source_status;
    if (retry_fstatat(parent_fd, argv[4], &source_status, AT_SYMLINK_NOFOLLOW) != 0) {
        int result = errno == ENOENT ? X_NOT_FOUND : X_SOURCE_CHANGED;
        close(parent_fd);
        return result;
    }
    if (!S_ISREG(source_status.st_mode) ||
        !identity_matches(&source_status, source_device, source_inode)) {
        close(parent_fd);
        return X_SOURCE_CHANGED;
    }
    struct stat target_status;
    if (retry_fstatat(parent_fd, argv[9], &target_status, AT_SYMLINK_NOFOLLOW) == 0) {
        close(parent_fd);
        return X_ALREADY_EXISTS;
    }
    if (errno != ENOENT) {
        int result = write_errno(errno);
        close(parent_fd);
        return result;
    }
    if (syscall(SYS_renameat2, parent_fd, argv[4], parent_fd, argv[9], RENAME_NOREPLACE) != 0) {
        int result = write_errno(errno);
        close(parent_fd);
        return result;
    }
    struct stat renamed_status;
    struct stat source_after;
    if (retry_fstatat(parent_fd, argv[9], &renamed_status, AT_SYMLINK_NOFOLLOW) != 0 ||
        !S_ISREG(renamed_status.st_mode) ||
        !identity_matches(&renamed_status, source_device, source_inode)) {
        close(parent_fd);
        return X_OUTCOME_UNCERTAIN;
    }
    if (retry_fstatat(parent_fd, argv[4], &source_after, AT_SYMLINK_NOFOLLOW) == 0 ||
        errno != ENOENT) {
        close(parent_fd);
        return X_OUTCOME_UNCERTAIN;
    }
    if (fsync(parent_fd) != 0 && errno != EINVAL && errno != EROFS) {
        close(parent_fd);
        return X_OUTCOME_UNCERTAIN;
    }
    printf("%llu:%llu\n", (unsigned long long) renamed_status.st_dev, (unsigned long long) renamed_status.st_ino);
    close(parent_fd);
    return 0;
}

static int prepare_extraction_stage(int argc, char **argv) {
    if (argc != 7 || !extraction_stage_name_ok(argv[4])) return X_USAGE;
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
    int stage_fd = openat(
        parent_fd,
        argv[4],
        O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC
    );
    struct stat status;
    if (stage_fd < 0 || fstat(stage_fd, &status) != 0) {
        if (stage_fd >= 0) close(stage_fd);
        close(parent_fd);
        return X_OUTCOME_UNCERTAIN;
    }
    if (!stage_security_valid(parent_fd, &status)) {
        int cleanup = remove_stage_path(parent_fd, stage_fd, argv[4], &status, 0);
        close(parent_fd);
        return cleanup == 0 ? X_STAGE_INVALID : X_OUTCOME_UNCERTAIN;
    }
    printf("%llu:%llu\n", (unsigned long long) status.st_dev, (unsigned long long) status.st_ino);
    close(stage_fd);
    close(parent_fd);
    return 0;
}

static int mkdir_extract(int argc, char **argv) {
    if (argc != 10 || !extraction_stage_name_ok(argv[4]) ||
        !relative_path_ok(argv[5], 0)) return X_USAGE;
    unsigned long long parent_device;
    unsigned long long parent_inode;
    unsigned long long stage_device;
    unsigned long long stage_inode;
    if (!parse_identity(argv, 6, &parent_device, &parent_inode) ||
        !parse_identity(argv, 8, &stage_device, &stage_inode)) return X_USAGE;
    int parent_fd = open_parent(argv[2], argv[3], parent_device, parent_inode);
    if (parent_fd < 0) return -parent_fd;
    int stage_fd = open_stage(parent_fd, argv[4], stage_device, stage_inode);
    if (stage_fd < 0) {
        close(parent_fd);
        return -stage_fd;
    }
    int directory_fd = open_relative_directory(stage_fd, argv[5], 1);
    if (directory_fd < 0) {
        close(stage_fd);
        close(parent_fd);
        return -directory_fd;
    }
    if (fsync(directory_fd) != 0 && errno != EINVAL && errno != EROFS) {
        close(directory_fd);
        close(stage_fd);
        close(parent_fd);
        return X_OUTCOME_UNCERTAIN;
    }
    close(directory_fd);
    close(stage_fd);
    close(parent_fd);
    return 0;
}

static int copy_extract_stdin(int argc, char **argv) {
    if (argc != 12 || !extraction_stage_name_ok(argv[4]) ||
        !relative_path_ok(argv[5], 1) || !basename_ok(argv[6])) return X_USAGE;
    unsigned long long parent_device;
    unsigned long long parent_inode;
    unsigned long long stage_device;
    unsigned long long stage_inode;
    unsigned long long expected_size;
    if (!parse_identity(argv, 7, &parent_device, &parent_inode) ||
        !parse_identity(argv, 9, &stage_device, &stage_inode) ||
        !parse_u64(argv[11], &expected_size)) return X_USAGE;
    int parent_fd = open_parent(argv[2], argv[3], parent_device, parent_inode);
    if (parent_fd < 0) return -parent_fd;
    int stage_fd = open_stage(parent_fd, argv[4], stage_device, stage_inode);
    if (stage_fd < 0) {
        close(parent_fd);
        return -stage_fd;
    }
    int directory_fd = open_relative_directory(stage_fd, argv[5], 0);
    if (directory_fd < 0) {
        close(stage_fd);
        close(parent_fd);
        return -directory_fd;
    }
    int file_fd = openat(
        directory_fd,
        argv[6],
        O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC,
        0600
    );
    if (file_fd < 0) {
        int result = write_errno(errno);
        close(directory_fd);
        close(stage_fd);
        close(parent_fd);
        return result;
    }
    struct stat file_status;
    if (fstat(file_fd, &file_status) != 0 ||
        !payload_security_valid(directory_fd, &file_status)) {
        if (fstat(file_fd, &file_status) == 0) {
            remove_known_extract_file(directory_fd, argv[6], &file_status);
        }
        close(file_fd);
        close(directory_fd);
        close(stage_fd);
        close(parent_fd);
        return X_STAGE_INVALID;
    }
    unsigned char buffer[65536];
    unsigned long long copied = 0;
    int result = 0;
    while (copied < expected_size) {
        size_t wanted = expected_size - copied < sizeof(buffer)
            ? (size_t) (expected_size - copied)
            : sizeof(buffer);
        ssize_t read_count;
        do {
            read_count = read(STDIN_FILENO, buffer, wanted);
        } while (read_count < 0 && errno == EINTR);
        if (read_count < 0) {
            result = X_SOURCE_UNREADABLE;
            break;
        }
        if (read_count == 0) {
            result = X_SOURCE_CHANGED;
            break;
        }
        size_t offset = 0U;
        while (offset < (size_t) read_count) {
            ssize_t written;
            do {
                written = write(file_fd, buffer + offset, (size_t) read_count - offset);
            } while (written < 0 && errno == EINTR);
            if (written <= 0) {
                result = written < 0 ? write_errno(errno) : X_IO;
                break;
            }
            offset += (size_t) written;
            copied += (unsigned long long) written;
        }
        if (result != 0) break;
    }
    if (result == 0) {
        unsigned char extra;
        ssize_t read_count;
        do {
            read_count = read(STDIN_FILENO, &extra, 1U);
        } while (read_count < 0 && errno == EINTR);
        if (read_count < 0) result = X_SOURCE_UNREADABLE;
        else if (read_count != 0) result = X_SOURCE_CHANGED;
    }
    if (result == 0 && fsync(file_fd) != 0 && errno != EINVAL) result = write_errno(errno);
    if (result == 0) {
        struct stat verified;
        if (fstat(file_fd, &verified) != 0 ||
            !payload_security_valid(directory_fd, &verified) ||
            verified.st_dev != file_status.st_dev || verified.st_ino != file_status.st_ino ||
            (unsigned long long) verified.st_size != expected_size) {
            result = X_STAGE_INVALID;
        } else {
            file_status = verified;
        }
    }
    close(file_fd);
    if (result != 0) {
        int cleanup = remove_known_extract_file(directory_fd, argv[6], &file_status);
        close(directory_fd);
        close(stage_fd);
        close(parent_fd);
        return cleanup == 0 ? result : X_OUTCOME_UNCERTAIN;
    }
    printf(
        "%llu:%llu:%llu\n",
        (unsigned long long) file_status.st_dev,
        (unsigned long long) file_status.st_ino,
        copied
    );
    close(directory_fd);
    close(stage_fd);
    close(parent_fd);
    return 0;
}

static int commit_extraction_stage(int argc, char **argv) {
    if (argc != 10 || !extraction_stage_name_ok(argv[4]) || !basename_ok(argv[5])) {
        return X_USAGE;
    }
    unsigned long long parent_device;
    unsigned long long parent_inode;
    unsigned long long stage_device;
    unsigned long long stage_inode;
    if (!parse_identity(argv, 6, &parent_device, &parent_inode) ||
        !parse_identity(argv, 8, &stage_device, &stage_inode)) return X_USAGE;
    int parent_fd = open_parent(argv[2], argv[3], parent_device, parent_inode);
    if (parent_fd < 0) return -parent_fd;
    int stage_fd = open_stage(parent_fd, argv[4], stage_device, stage_inode);
    if (stage_fd < 0) {
        close(parent_fd);
        return -stage_fd;
    }
    struct stat held;
    if (fstat(stage_fd, &held) != 0 || !identity_matches(&held, stage_device, stage_inode)) {
        close(stage_fd);
        close(parent_fd);
        return X_STAGE_INVALID;
    }
    long renamed = syscall(
        SYS_renameat2,
        parent_fd,
        argv[4],
        parent_fd,
        argv[5],
        RENAME_NOREPLACE
    );
    if (renamed != 0) {
        int result = write_errno(errno);
        close(stage_fd);
        close(parent_fd);
        return result;
    }
    struct stat committed;
    if (fstat(stage_fd, &committed) != 0 || committed.st_dev != held.st_dev ||
        committed.st_ino != held.st_ino) {
        close(stage_fd);
        close(parent_fd);
        return X_OUTCOME_UNCERTAIN;
    }
    if (fsync(parent_fd) != 0 && errno != EINVAL && errno != EROFS) {
        close(stage_fd);
        close(parent_fd);
        return X_OUTCOME_UNCERTAIN;
    }
    printf("%llu:%llu\n", (unsigned long long) committed.st_dev, (unsigned long long) committed.st_ino);
    close(stage_fd);
    close(parent_fd);
    return 0;
}

static int remove_extraction_contents(int directory_fd) {
    int duplicate = fcntl(directory_fd, F_DUPFD_CLOEXEC, 0);
    if (duplicate < 0) return X_STAGE_INVALID;
    DIR *directory = fdopendir(duplicate);
    if (directory == NULL) {
        close(duplicate);
        return X_STAGE_INVALID;
    }
    int result = 0;
    errno = 0;
    struct dirent *entry;
    while ((entry = readdir(directory)) != NULL) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) continue;
        struct stat status;
        if (fstatat(directory_fd, entry->d_name, &status, AT_SYMLINK_NOFOLLOW) != 0) {
            result = X_STAGE_INVALID;
            break;
        }
        if (S_ISDIR(status.st_mode)) {
            int child_fd = openat(
                directory_fd,
                entry->d_name,
                O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC
            );
            if (child_fd < 0 || !stage_security_valid(directory_fd, &status)) {
                if (child_fd >= 0) close(child_fd);
                result = X_STAGE_INVALID;
                break;
            }
            result = remove_extraction_contents(child_fd);
            close(child_fd);
            if (result != 0) break;
            struct stat current;
            if (fstatat(directory_fd, entry->d_name, &current, AT_SYMLINK_NOFOLLOW) != 0 ||
                current.st_dev != status.st_dev || current.st_ino != status.st_ino ||
                unlinkat(directory_fd, entry->d_name, AT_REMOVEDIR) != 0) {
                result = X_STAGE_INVALID;
                break;
            }
        } else if (S_ISREG(status.st_mode)) {
            if (!payload_security_valid(directory_fd, &status) ||
                unlinkat(directory_fd, entry->d_name, 0) != 0) {
                result = X_STAGE_INVALID;
                break;
            }
        } else if (S_ISLNK(status.st_mode)) {
            if (unlinkat(directory_fd, entry->d_name, 0) != 0) {
                result = X_STAGE_INVALID;
                break;
            }
        } else {
            result = X_STAGE_INVALID;
            break;
        }
        errno = 0;
    }
    if (result == 0 && errno != 0) result = X_STAGE_INVALID;
    closedir(directory);
    return result;
}

static int remove_extraction_stage(int argc, char **argv) {
    if (argc != 9 || !extraction_stage_name_ok(argv[4])) return X_USAGE;
    unsigned long long parent_device;
    unsigned long long parent_inode;
    unsigned long long stage_device;
    unsigned long long stage_inode;
    if (!parse_identity(argv, 5, &parent_device, &parent_inode) ||
        !parse_identity(argv, 7, &stage_device, &stage_inode)) return X_USAGE;
    int parent_fd = open_parent(argv[2], argv[3], parent_device, parent_inode);
    if (parent_fd < 0) return -parent_fd;
    int stage_fd = open_stage(parent_fd, argv[4], stage_device, stage_inode);
    if (stage_fd < 0) {
        struct stat missing;
        int absent = fstatat(parent_fd, argv[4], &missing, AT_SYMLINK_NOFOLLOW) != 0 &&
            errno == ENOENT;
        close(parent_fd);
        return absent ? 0 : X_STAGE_INVALID;
    }
    int result = remove_extraction_contents(stage_fd);
    if (result == 0) result = finish_stage(parent_fd, stage_fd, argv[4]);
    else close(stage_fd);
    close(parent_fd);
    return result;
}

int main(int argc, char **argv) {
    if (argc < 2) return X_USAGE;
    if (strcmp(argv[1], "list-dir") == 0) return list_directory(argc, argv);
    if (strcmp(argv[1], "read-file-stdout") == 0) return read_file_stdout(argc, argv);
    if (strcmp(argv[1], "prepare-stage") == 0) return prepare_stage(argc, argv);
    if (strcmp(argv[1], "copy-publish-stdin") == 0) return copy_publish_stdin(argc, argv);
    if (strcmp(argv[1], "copy-file-publish") == 0) return copy_file_publish(argc, argv);
    if (strcmp(argv[1], "move-cross-device-noreplace") == 0) {
        return move_cross_device_noreplace(argc, argv);
    }
    if (strcmp(argv[1], "remove-stage") == 0) return remove_stage(argc, argv);
    if (strcmp(argv[1], "move-noreplace") == 0) return move_noreplace(argc, argv);
    if (strcmp(argv[1], "rename-noreplace") == 0) return rename_noreplace(argc, argv);
    if (strcmp(argv[1], "prepare-extract-stage") == 0) return prepare_extraction_stage(argc, argv);
    if (strcmp(argv[1], "mkdir-extract") == 0) return mkdir_extract(argc, argv);
    if (strcmp(argv[1], "copy-extract-stdin") == 0) return copy_extract_stdin(argc, argv);
    if (strcmp(argv[1], "commit-extract-stage") == 0) return commit_extraction_stage(argc, argv);
    if (strcmp(argv[1], "remove-extract-stage") == 0) return remove_extraction_stage(argc, argv);
    return X_USAGE;
}
