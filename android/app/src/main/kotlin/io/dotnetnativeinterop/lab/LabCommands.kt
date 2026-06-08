package io.dotnetnativeinterop.lab

import java.util.Locale

/** Builds the parametric ShowcaseCommand ids the Lab demos run over the existing feature path.
 *  Formats mirror the iOS currentCommand() exactly (Locale.ROOT, %.6f / %.3f). */
public object LabCommands {
    private fun d6(v: Double): String = String.format(Locale.ROOT, "%.6f", v)
    private fun d3(v: Double): String = String.format(Locale.ROOT, "%.3f", v)

    public fun mandelbrot(cx: Double, cy: Double, zoom: Double, iters: Int, size: Int = 256): String =
        "viz-mandelbrot~cx_${d6(cx)}~cy_${d6(cy)}~zoom_${d6(zoom)}~iters_${iters}~w_${size}~h_${size}"

    public fun raymarch(angle: Double, size: Int = 220): String =
        "viz-raymarch~angle_${d3(angle)}~w_${size}~h_${size}"

    public fun matmul(max: Int = 384): String = "bench-matmul~max_$max"

    public fun parallel(size: Int = 480): String = "bench-parallel~size_$size"
}
