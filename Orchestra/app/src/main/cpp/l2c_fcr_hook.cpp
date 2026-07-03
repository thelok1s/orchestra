/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <elf.h>
#include <atomic>
#include <jni.h>

#include "l2c_fcr_hook.h"
#include "shadowhook.h"

extern "C" {
#include "xz.h"
}

#define LOG_TAG "LibrePodsHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static tBTA_STATUS (*original_BTA_DmSetLocalDiRecord)(tSDP_DI_RECORD *, uint32_t *) = nullptr;

static std::atomic<bool> enableSdpHook(false);

// Precomputed (disk-ELF-relative) offset of BTA_DmSetLocalDiRecord within g_sdp_libname, and the
// hook-install state. Split out so the slow ELF/.gnu_debugdata work (precomputeDidOffset, ~380ms)
// can run BEFORE the target library is even loaded, and the actual hook install (armDidHook) is
// then just an add + a shadowhook call — cheap enough to poll every ~1ms and win the race against
// the Bluetooth stack's own DI-record write.
static uint64_t g_sdp_offset = 0;          // symbol offset within libbluetooth_jni (disk ELF)
static std::string g_sdp_libname;          // which lib g_sdp_offset belongs to
static std::atomic<bool> g_shadowhook_ready(false);
static std::atomic<bool> g_hooked(false);

tBTA_STATUS fake_BTA_DmSetLocalDiRecord(tSDP_DI_RECORD *p_device_info, uint32_t *p_handle) {

    LOGI("fake_BTA_DmSetLocalDiRecord called");

    if (original_BTA_DmSetLocalDiRecord &&
        enableSdpHook.load(std::memory_order_relaxed))
        original_BTA_DmSetLocalDiRecord(p_device_info, p_handle);

    LOGI("fake_BTA_DmSetLocalDiRecord: modifying vendor to 0x004C, vendor_id_source to 0x0001");

    if (p_device_info) {
        p_device_info->vendor = 0x004C;
        p_device_info->vendor_id_source = 0x0001;
    }

    LOGI("fake_BTA_DmSetLocalDiRecord: returning status %d",
         original_BTA_DmSetLocalDiRecord ? original_BTA_DmSetLocalDiRecord(p_device_info, p_handle)
                                         : BTA_FAILURE);
    return original_BTA_DmSetLocalDiRecord ? original_BTA_DmSetLocalDiRecord(p_device_info,
                                                                             p_handle)
                                           : BTA_FAILURE;
}

static bool decompressXZ(const uint8_t *input, size_t input_size, std::vector<uint8_t> &output) {

    LOGI("decompressXZ called with input_size: %zu", input_size);

    xz_crc32_init();
#ifdef XZ_USE_CRC64
    xz_crc64_init();
#endif

    struct xz_dec *dec = xz_dec_init(XZ_DYNALLOC, 64U << 20);
    if (!dec) {
        LOGE("decompressXZ: xz_dec_init failed");
        return false;
    }
    LOGI("decompressXZ: xz_dec_init succeeded");

    struct xz_buf buf{};
    buf.in = input;
    buf.in_pos = 0;
    buf.in_size = input_size;

    output.resize(input_size * 8);

    buf.out = output.data();
    buf.out_pos = 0;
    buf.out_size = output.size();

    LOGI("decompressXZ: entering decompression loop");
    while (true) {
        LOGI("decompressXZ: xz_dec_run iteration, buf.in_pos: %zu, buf.out_pos: %zu", buf.in_pos,
             buf.out_pos);
        enum xz_ret ret = xz_dec_run(dec, &buf);

        LOGI("decompressXZ: xz_dec_run returned %d", ret);

        if (ret == XZ_STREAM_END)
            break;

        if (ret != XZ_OK) {
            LOGE("decompressXZ: xz_dec_run error");
            xz_dec_end(dec);
            return false;
        }

        if (buf.out_pos == buf.out_size) {
            size_t old = output.size();
            LOGI("decompressXZ: resizing output to %zu", old * 2);
            output.resize(old * 2);
            buf.out = output.data();
            buf.out_size = output.size();
        }
    }

    output.resize(buf.out_pos);
    xz_dec_end(dec);
    LOGI("decompressXZ: decompression successful, output size: %zu", output.size());
    return true;
}

static bool getLibraryPath(const char *name, std::string &out) {
    LOGI("getLibraryPath called with name: %s", name);

    FILE *fp = fopen("/proc/self/maps", "r");
    if (!fp) {
        LOGE("getLibraryPath: fopen failed");
        return false;
    }

    char line[1024];

    LOGI("getLibraryPath: scanning /proc/self/maps");
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, name)) {
            LOGI("getLibraryPath: found line containing %s", name);
            char *path = strchr(line, '/');
            if (path) {
                out = path;
                out.erase(out.find('\n'));
                LOGI("getLibraryPath: path found: %s", out.c_str());
                fclose(fp);
                return true;
            }
        }
    }

    fclose(fp);
    LOGI("getLibraryPath: failed to find path for %s", name);
    return false;
}

static uintptr_t getModuleBase(const char *name) {
    LOGI("getModuleBase called with name: %s", name);

    FILE *fp = fopen("/proc/self/maps", "r");
    if (!fp) {
        LOGE("getModuleBase: fopen failed");
        return 0;
    }

    char line[1024];
    uintptr_t base = 0;

    LOGI("getModuleBase: scanning /proc/self/maps");
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, name)) {
            base = strtoull(line, nullptr, 16);
            LOGI("getModuleBase: found base at 0x%lx", base);
            break;
        }
    }

    fclose(fp);
    LOGI("getModuleBase: failed to find base for %s", name);
    return base;
}

// Same /proc/self/maps scan as getModuleBase, WITHOUT logging: this is polled every ~1ms from
// armDidHook while waiting for the target library to load, and per-call LOGI/LOGE would flood
// logcat.
static uintptr_t getModuleBaseQuiet(const char *name) {
    FILE *fp = fopen("/proc/self/maps", "r");
    if (!fp) return 0;
    char line[1024];
    uintptr_t base = 0;
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, name)) {
            base = strtoull(line, nullptr, 16);
            break;
        }
    }
    fclose(fp);
    return base;
}

static uint64_t
findSymbolOffsetDynsym(const std::vector<uint8_t> &elf, const char *symbol_substring) {

    LOGI("findSymbolOffsetDynsym called with %s", symbol_substring);

    auto *eh = reinterpret_cast<const Elf64_Ehdr *>(elf.data());
    auto *shdr = reinterpret_cast<const Elf64_Shdr *>(
            elf.data() + eh->e_shoff);

    const char *shstr = reinterpret_cast<const char *>(
            elf.data() + shdr[eh->e_shstrndx].sh_offset);

    const Elf64_Shdr *dynsym = nullptr;
    const Elf64_Shdr *dynstr = nullptr;

    for (int i = 0; i < eh->e_shnum; ++i) {
        const char *secname = shstr + shdr[i].sh_name;

        if (!strcmp(secname, ".dynsym"))
            dynsym = &shdr[i];
        if (!strcmp(secname, ".dynstr"))
            dynstr = &shdr[i];
    }

    if (!dynsym || !dynstr) {
        LOGE("findSymbolOffsetDynsym: dynsym or dynstr not found");
        return 0;
    }

    auto *symbols = reinterpret_cast<const Elf64_Sym *>(
            elf.data() + dynsym->sh_offset);

    const char *strings = reinterpret_cast<const char *>(
            elf.data() + dynstr->sh_offset);

    size_t count = dynsym->sh_size / sizeof(Elf64_Sym);

    LOGI("findSymbolOffsetDynsym: scanning %zu symbols", count);

    for (size_t i = 0; i < count; ++i) {
        const char *name = strings + symbols[i].st_name;

        if (strstr(name, symbol_substring) && ELF64_ST_TYPE(symbols[i].st_info) == STT_FUNC) {

            LOGI("findSymbolOffsetDynsym: matched %s @ 0x%lx", name,
                 (unsigned long) symbols[i].st_value);

            return symbols[i].st_value;
        }
    }

    LOGI("findSymbolOffsetDynsym: no match for %s", symbol_substring);
    return 0;
}

static uint64_t findSymbolOffset(const std::vector<uint8_t> &elf, const char *symbol_substring) {

    LOGI("findSymbolOffset called with symbol_substring: %s", symbol_substring);

    auto *eh = reinterpret_cast<const Elf64_Ehdr *>(elf.data());
    auto *shdr = reinterpret_cast<const Elf64_Shdr *>(
            elf.data() + eh->e_shoff);

    const char *shstr = reinterpret_cast<const char *>(
            elf.data() + shdr[eh->e_shstrndx].sh_offset);

    const Elf64_Shdr *symtab = nullptr;
    const Elf64_Shdr *strtab = nullptr;

    LOGI("findSymbolOffset: parsing ELF sections");
    for (int i = 0; i < eh->e_shnum; ++i) {
        const char *secname = shstr + shdr[i].sh_name;
        if (!strcmp(secname, ".symtab"))
            symtab = &shdr[i];
        if (!strcmp(secname, ".strtab"))
            strtab = &shdr[i];
    }

    if (!symtab || !strtab) {
        LOGE("findSymbolOffset: symtab or strtab not found");
        return 0;
    }
    LOGI("findSymbolOffset: found symtab and strtab");

    auto *symbols = reinterpret_cast<const Elf64_Sym *>(
            elf.data() + symtab->sh_offset);

    const char *strings = reinterpret_cast<const char *>(
            elf.data() + strtab->sh_offset);

    size_t count = symtab->sh_size / sizeof(Elf64_Sym);

    LOGI("findSymbolOffset: scanning %zu symbols", count);
    for (size_t i = 0; i < count; ++i) {
        const char *name = strings + symbols[i].st_name;

        if (strstr(name, symbol_substring) && ELF64_ST_TYPE(symbols[i].st_info) == STT_FUNC) {

            LOGI("findSymbolOffset: matched symbol %s at 0x%lx", name,
                 (unsigned long) symbols[i].st_value);

            return symbols[i].st_value;
        }
    }

    LOGI("findSymbolOffset: no match found for %s", symbol_substring);
    return 0;
}

// Disk-ELF-only: reads libname off disk (via /proc/self/maps, so the module DOES need to be
// mapped to resolve its path, but NOT necessarily fully relocated/usable yet), decompresses
// .gnu_debugdata if present and resolves BTA_DmSetLocalDiRecord's file-relative offset. This is the
// slow part (~380ms, dominated by the xz decompress) and deliberately does NOT call
// getModuleBase/getModuleBaseQuiet: the returned value is a file offset, valid regardless of
// whether (or where) the library ends up loaded.
static uint64_t computeSdpOffset(const char *libname) {
    LOGI("computeSdpOffset called with libname: %s", libname);

    std::string path;
    // Prefer the on-disk apex path: it resolves BEFORE libbluetooth_jni is mmap'd into the process,
    // so precompute can finish the (slow) decompress early and the later arm-poll just needs the
    // module base — that's what wins the DI-record-write race. Fall back to /proc/self/maps.
    const char *apexDirs[] = {"/apex/com.android.bt/lib64/",
                              "/apex/com.google.android.bluetooth/lib64/"};
    for (const char *d : apexDirs) {
        std::string cand = std::string(d) + libname;
        if (access(cand.c_str(), R_OK) == 0) { path = cand; break; }
    }
    if (path.empty() && !getLibraryPath(libname, path)) {
        LOGE("Failed to locate %s", libname);
        return 0;
    }
    LOGI("computeSdpOffset: located path: %s", path.c_str());

    int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) {
        LOGE("computeSdpOffset: open failed");
        return 0;
    }

    struct stat st{};
    if (fstat(fd, &st) != 0) {
        LOGE("computeSdpOffset: fstat failed");
        close(fd);
        return 0;
    }
    LOGI("computeSdpOffset: opened file, size: %lld", (long long) st.st_size);

    std::vector<uint8_t> file(st.st_size);
    read(fd, file.data(), st.st_size);
    close(fd);

    auto *eh = reinterpret_cast<Elf64_Ehdr *>(file.data());
    auto *shdr = reinterpret_cast<Elf64_Shdr *>(
            file.data() + eh->e_shoff);

    const char *shstr = reinterpret_cast<const char *>(
            file.data() + shdr[eh->e_shstrndx].sh_offset);

    uint64_t sdp_offset = 0;

    for (int i = 0; i < eh->e_shnum; ++i) {
        if (!strcmp(shstr + shdr[i].sh_name, ".gnu_debugdata")) {
            LOGI("computeSdpOffset: found .gnu_debugdata section");

            std::vector<uint8_t> compressed(file.begin() + shdr[i].sh_offset,
                                            file.begin() + shdr[i].sh_offset + shdr[i].sh_size);

            std::vector<uint8_t> decompressed;

            if (decompressXZ(compressed.data(), compressed.size(), decompressed)) {

                sdp_offset = findSymbolOffset(decompressed, "BTA_DmSetLocalDiRecord");
            } else {
                LOGE("debugdata decompress failed");
            }

            break;
        }
    }

    if (!sdp_offset) {
        LOGI("fallback dynsym sdp");
        sdp_offset = findSymbolOffsetDynsym(file, "BTA_DmSetLocalDiRecord");
    }

    return sdp_offset;
}

// slow (~380ms), safe to call before libbluetooth_jni is loaded; caches the offset. Call once.
extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_thelok1s_orchestra_NativeBridge_precomputeDidOffset(JNIEnv *, jclass) {
    if (g_sdp_offset) return JNI_TRUE;
    for (const char *ln : {"libbluetooth_jni.so", "libbluetooth_qti.so"}) {
        uint64_t off = computeSdpOffset(ln);
        if (off) {
            g_sdp_offset = off;
            g_sdp_libname = ln;
            LOGI("precompute %s off=0x%lx", ln, (unsigned long) off);
            return JNI_TRUE;
        }
    }
    LOGE("precompute: BTA_DmSetLocalDiRecord not found");
    return JNI_FALSE;
}

// cheap; sets the gate; hooks AT MOST ONCE the instant the module is loaded. Poll from Java until
// it returns true.
extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_thelok1s_orchestra_NativeBridge_armDidHook(JNIEnv *, jclass, jboolean enable) {
    enableSdpHook.store(enable, std::memory_order_relaxed);
    if (!enable) return JNI_TRUE;
    if (g_hooked.load()) return JNI_TRUE;
    if (!g_sdp_offset) return JNI_FALSE;               // precompute not done
    uintptr_t base = getModuleBaseQuiet(g_sdp_libname.c_str());
    if (!base) return JNI_FALSE;                        // module not loaded yet -> caller keeps polling
    if (!g_shadowhook_ready.load()) {
        if (shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false) != 0) {
            LOGE("shadowhook_init failed");
            return JNI_FALSE;
        }
        g_shadowhook_ready.store(true);
    }
    void *target = (void *) (base + g_sdp_offset);
    void *stub = shadowhook_hook_func_addr(target, (void *) fake_BTA_DmSetLocalDiRecord,
                                           (void **) &original_BTA_DmSetLocalDiRecord);
    if (!stub) {
        LOGE("armDidHook: hook failed");
        return JNI_FALSE;
    }
    g_hooked.store(true);
    LOGI("armDidHook: hooked %s @ base+0x%lx", g_sdp_libname.c_str(), (unsigned long) g_sdp_offset);
    return JNI_TRUE;
}

// gate-only, for a runtime toggle-off (no re-hook)
extern "C" JNIEXPORT void JNICALL
Java_io_github_thelok1s_orchestra_NativeBridge_setDidGate(JNIEnv *, jclass, jboolean enable) {
    enableSdpHook.store(enable, std::memory_order_relaxed);
}
