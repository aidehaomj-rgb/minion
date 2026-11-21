package com.minion.core.security;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;

/**
 * Crypt32 DPAPI 直调（当前用户 scope，开发说明 v2.4 §4.6 首选方案）。
 *
 * <p>仅依赖 jna 核心（无 jna-platform）：DATA_BLOB 按平台手工构造——
 * x64: cbData(DWORD,offset 0)+填充+pbData(ptr,offset 8)=16 字节；x86: 8 字节。
 * 加载系统 crypt32.dll（随系统位数，天然匹配进程架构，无需自带 DLL）。
 * 出参 blob 由 DPAPI 用 LocalAlloc 分配，读出后必须 LocalFree，防句柄泄漏。
 */
public final class Crypt32Dpapi implements SecretStore.Dpapi {

    private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;
    /** DATA_BLOB 内 pbData 偏移：x64 结构体按 8 字节对齐 */
    private static final int PB_OFFSET = Native.POINTER_SIZE == 8 ? 8 : 4;
    private static final int BLOB_SIZE = Native.POINTER_SIZE == 8 ? 16 : 8;

    private interface Crypt32Lib extends StdCallLibrary {
        // 只用到指针参数与 flags，无字符串编组问题（desc/entropy/reserved/prompt 全 null）
        boolean CryptProtectData(Pointer dataIn, Pointer descr, Pointer entropy, Pointer reserved,
                                 Pointer prompt, int flags, Pointer dataOut);
        boolean CryptUnprotectData(Pointer dataIn, Pointer descrOut, Pointer entropy, Pointer reserved,
                                   Pointer prompt, int flags, Pointer dataOut);
    }

    private interface Kernel32Lib extends StdCallLibrary {
        Pointer LocalFree(Pointer mem);
    }

    private static final Crypt32Lib C32 = (Crypt32Lib) Native.loadLibrary("Crypt32", Crypt32Lib.class);
    private static final Kernel32Lib K32 = (Kernel32Lib) Native.loadLibrary("kernel32", Kernel32Lib.class);

    public Crypt32Dpapi() { /* 加载失败由 Native.loadLibrary 抛 Error，调用方 defaultDpapi 捕获回退 */ }

    @Override
    public byte[] protect(byte[] plain) throws Exception {
        Memory data = copyIn(plain);
        Memory in = blob(data, plain.length);
        Memory out = new Memory(BLOB_SIZE);
        if (!C32.CryptProtectData(in, null, null, null, null, CRYPTPROTECT_UI_FORBIDDEN, out))
            throw new IllegalStateException("CryptProtectData 失败（DPAPI 不可用或内存不足）");
        return copyOutAndFree(out);
    }

    @Override
    public byte[] unprotect(byte[] blob) throws Exception {
        Memory data = copyIn(blob);
        Memory in = blob(data, blob.length);
        Memory out = new Memory(BLOB_SIZE);
        if (!C32.CryptUnprotectData(in, null, null, null, null, CRYPTPROTECT_UI_FORBIDDEN, out))
            throw new IllegalStateException("CryptUnprotectData 失败（换 Windows 用户/机器，或 master.key 被修改）");
        return copyOutAndFree(out);
    }

    private static Memory copyIn(byte[] bytes) {
        Memory m = new Memory(Math.max(1, bytes.length));
        m.write(0, bytes, 0, bytes.length);
        return m;
    }

    /** DATA_BLOB 结构体（data 必须由调用方持有引用防 GC，函数返回前不可释放） */
    private static Memory blob(Memory data, int length) {
        Memory b = new Memory(BLOB_SIZE);
        b.setInt(0, length);
        b.setPointer(PB_OFFSET, data);
        return b;
    }

    private static byte[] copyOutAndFree(Memory out) {
        int len = out.getInt(0);
        Pointer p = out.getPointer(PB_OFFSET);
        if (len <= 0 || p == null) throw new IllegalStateException("DPAPI 输出 blob 非法");
        byte[] result = p.getByteArray(0, len);
        K32.LocalFree(p);
        return result;
    }
}
