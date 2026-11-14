package com.benton.izukijs.privileged;

import android.content.Intent;
import android.view.Surface;

/**
 * 后台虚拟屏的特权服务面。运行在 Shizuku UserService 进程里，
 * 以 shell 身份创建 VirtualDisplay 并把目标应用拉到该屏；
 * Shizuku 以 root 运行时会在建屏前把调用身份降级到 shell。
 */
interface IVirtualScreenService {

    /** Shizuku 保留的销毁入口 */
    oneway void destroy() = 16777114;

    String version() = 1;

    /**
     * 创建虚拟屏；[captureSurface] 由 App 侧的 ImageReader 提供，返回 displayId，失败返回 -1。
     * [showSystemDecorations] 为 true 时尝试请求系统状态栏 / 导航栏 insets。
     */
    int create(int width, int height, int dpi, in Surface captureSurface, boolean showSystemDecorations) = 2;

    int displayId() = 3;

    /**
     * 在虚拟屏上启动 [intent]；目标应用已在其他屏运行时按 [forceStopIfElsewhere] 先强停。
     */
    boolean launch(in Intent intent, boolean forceStopIfElsewhere) = 4;

    /** 只销毁虚拟屏，保留特权进程以便下次启动复用。 */
    void destroyDisplay() = 5;

    /** 虚拟屏当前前台包名；没有虚拟屏或读取失败返回 null。 */
    String currentPackage() = 6;

    /** 最近一次 create 失败的真实原因；成功或尚未创建时返回 null。 */
    String lastError() = 7;
}
