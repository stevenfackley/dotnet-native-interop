package io.dotnetnativeinterop.lab

import org.junit.Assert.assertEquals
import org.junit.Test

public class LabCommandsTest {

    @Test
    public fun mandelbrotMatchesIosFormat() {
        assertEquals(
            "viz-mandelbrot~cx_-0.500000~cy_0.000000~zoom_1.000000~iters_220~w_256~h_256",
            LabCommands.mandelbrot(cx = -0.5, cy = 0.0, zoom = 1.0, iters = 220),
        )
    }

    @Test
    public fun raymarchMatchesIosFormat() {
        assertEquals("viz-raymarch~angle_0.000~w_220~h_220", LabCommands.raymarch(angle = 0.0))
        assertEquals("viz-raymarch~angle_1.571~w_220~h_220", LabCommands.raymarch(angle = 1.5708))
    }

    @Test
    public fun benchmarksUseDefaults() {
        assertEquals("bench-matmul~max_384", LabCommands.matmul())
        assertEquals("bench-parallel~size_480", LabCommands.parallel())
        assertEquals("bench-matmul~max_128", LabCommands.matmul(128))
    }
}
