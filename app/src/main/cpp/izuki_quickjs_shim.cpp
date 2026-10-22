#include <jni.h>

#include <atomic>
#include <dlfcn.h>

namespace {

/**
 * QuickJS 的中断回调状态。回调运行在脚本执行线程，[stop] 由看门狗 / 用户停止
 * 在另一个线程写入，因此必须是原子的，且回调内不能访问 JVM。
 */
struct InterruptState {
    std::atomic<bool> stop{false};
};

using JsInterruptHandler = int (*)(void* runtime, void* opaque);
using SetInterruptHandlerFn = void (*)(void* runtime, JsInterruptHandler handler, void* opaque);

std::atomic<SetInterruptHandlerFn> g_set_interrupt{nullptr};

int interrupt_handler(void* /*runtime*/, void* opaque) {
    auto* state = static_cast<InterruptState*>(opaque);
    if (state == nullptr) return 0;
    return state->stop.load(std::memory_order_relaxed) ? 1 : 0;
}

/**
 * libquickjs.so 由 QuickJS-Android 载入，这里按 soname 取到已加载的实例，
 * 再解析 JS_SetInterruptHandler；解析失败时上层会退化为仅检查点停止。
 */
SetInterruptHandlerFn resolve_set_interrupt() {
    SetInterruptHandlerFn cached = g_set_interrupt.load(std::memory_order_acquire);
    if (cached != nullptr) return cached;

    void* handle = dlopen("libquickjs.so", RTLD_NOW | RTLD_NOLOAD);
    if (handle == nullptr) {
        handle = dlopen("libquickjs.so", RTLD_NOW);
    }
    if (handle == nullptr) return nullptr;

    auto fn = reinterpret_cast<SetInterruptHandlerFn>(
        dlsym(handle, "JS_SetInterruptHandler"));
    if (fn == nullptr) {
        dlclose(handle);
        return nullptr;
    }
    g_set_interrupt.store(fn, std::memory_order_release);
    return fn;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_benton_izukijs_runtime_QuickJsInterruptNative_createState(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return reinterpret_cast<jlong>(new InterruptState());
}

JNIEXPORT jboolean JNICALL
Java_com_benton_izukijs_runtime_QuickJsInterruptNative_install(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong runtime_ptr, jlong state_ptr) {
    if (runtime_ptr == 0 || state_ptr == 0) return JNI_FALSE;
    auto fn = resolve_set_interrupt();
    if (fn == nullptr) return JNI_FALSE;
    fn(reinterpret_cast<void*>(runtime_ptr), interrupt_handler,
       reinterpret_cast<void*>(state_ptr));
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_benton_izukijs_runtime_QuickJsInterruptNative_stop(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong state_ptr) {
    auto* state = reinterpret_cast<InterruptState*>(state_ptr);
    if (state != nullptr) {
        state->stop.store(true, std::memory_order_relaxed);
    }
}

JNIEXPORT void JNICALL
Java_com_benton_izukijs_runtime_QuickJsInterruptNative_uninstall(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong runtime_ptr) {
    if (runtime_ptr == 0) return;
    auto fn = resolve_set_interrupt();
    if (fn == nullptr) return;
    fn(reinterpret_cast<void*>(runtime_ptr), nullptr, nullptr);
}

JNIEXPORT void JNICALL
Java_com_benton_izukijs_runtime_QuickJsInterruptNative_freeState(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong state_ptr) {
    delete reinterpret_cast<InterruptState*>(state_ptr);
}

}  // extern "C"
