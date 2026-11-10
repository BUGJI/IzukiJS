package com.benton.izukijs.runtime.api

import java.util.PriorityQueue

/** 一个颜色连通域；坐标均为屏幕像素。 */
internal data class ColorRegion(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val pixels: Int,
    val cx: Int,
    val cy: Int,
)

/**
 * 只保留面积最大的若干个连通域，避免把成千上万个噪点区域全部留在内存里。
 * 用最小堆维护 top-N：堆顶始终是当前结果中像素数最少的区域。
 */
internal class ColorRegionAccumulator(
    private val minPixels: Int,
    maxRegions: Int,
) {
    private val limit = maxRegions.coerceAtLeast(1)
    private val heap = PriorityQueue<ColorRegion>(limit + 1, COMPARATOR)

    fun offer(region: ColorRegion) {
        if (region.pixels < minPixels) return
        heap.add(region)
        if (heap.size > limit) heap.poll()
    }

    fun toList(): List<ColorRegion> =
        heap.sortedWith(
            compareByDescending<ColorRegion> { it.pixels }
                .thenBy { it.cy }
                .thenBy { it.cx },
        )

    private companion object {
        val COMPARATOR = compareBy<ColorRegion> { it.pixels }
            .thenByDescending { it.cy }
            .thenByDescending { it.cx }
    }
}
