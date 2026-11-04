package com.benton.izukijs.privileged;

/**
 * 锁屏解锁的特权服务面。运行在 Shizuku UserService 进程里（root 启动的 Shizuku 下为 UID 0），
 * 因此可以直接读 /dev/input 并使用 InputManager.injectInputEvent。
 *
 * 返回值都是 UnlockResult 的 name，录制状态是 JSON。
 */
interface IUnlockService {

    /** Shizuku 保留的销毁入口 */
    oneway void destroy() = 16777114;

    String version() = 1;

    /** 亮屏并解锁；credential 为纯数字 PIN，无密码锁屏传空串 */
    String unlock(String credential) = 2;

    /** 按 UnlockPattern JSON 回放手势 */
    String unlockWithGesture(String gestureJson) = 3;

    /** 设置页自测：先上锁息屏，再按 PIN / 空串解锁 */
    String testUnlock(String credential) = 4;

    /** 设置页自测：先上锁息屏，再回放手势 */
    String testUnlockGesture(String gestureJson) = 5;

    /** 上锁并息屏 */
    String lockAndSleep() = 6;

    /** 开始录制：锁屏 -> 亮屏 -> 录触摸 -> keyguard 消失结束；立即返回 */
    oneway void startGestureRecord(int timeoutMs) = 7;

    /** 录制状态快照；终态读一次后清空 */
    String pollGestureRecord() = 8;

    oneway void cancelGestureRecord() = 9;

    boolean isScreenOn() = 10;
}
