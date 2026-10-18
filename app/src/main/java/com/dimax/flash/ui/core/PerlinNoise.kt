package com.dimax.flash.ui.core

import kotlin.math.floor

object PerlinNoise {

    private val permutation = IntArray(256).also { p ->
        val base = intArrayOf(
            151,160,137,91,90,15,131,13,201,95,96,53,194,233,7,225,140,36,103,30,
            69,142,8,99,37,240,21,10,23,190,6,148,247,120,234,75,0,26,197,62,94,
            252,219,203,117,35,11,32,57,177,33,88,237,149,56,87,174,20,125,136,
            171,168,68,175,74,165,71,134,139,48,27,166,77,146,158,231,83,111,229,
            122,60,211,133,230,220,105,92,41,55,46,245,40,244,102,143,54,65,25,
            63,161,1,216,80,73,209,76,132,187,208,89,18,169,200,196,135,130,116,
            188,159,86,164,100,109,198,173,186,3,64,52,217,226,250,124,123,5,202,
            38,147,118,126,255,82,85,212,207,206,59,227,47,16,58,17,182,189,28,
            42,223,183,170,213,119,248,152,2,44,154,163,70,221,153,101,155,167,
            43,172,9,129,22,39,253,19,98,108,110,79,113,224,232,178,185,112,104,
            218,246,97,228,251,34,242,193,238,210,144,12,191,179,162,241,81,51,
            145,235,249,14,239,107,49,192,214,31,181,199,106,157,184,84,204,176,
            115,121,50,45,127,4,150,254,138,236,205,93,222,114,67,29,24,72,243,
            141,128,195,78,66,215,61,156,180
        )
        base.copyInto(p)
    }

    private val p = IntArray(512) { permutation[it % 256] }

    private fun fade(t: Double) = t * t * t * (t * (t * 6 - 15) + 10)

    private fun lerp(t: Double, a: Double, b: Double) = a + t * (b - a)

    private fun grad(hash: Int, x: Double, y: Double): Double {
        return when (hash and 3) {
            0 ->  x + y
            1 -> -x + y
            2 ->  x - y
            else -> -x - y
        }
    }

    fun noise(x: Double, y: Double): Double {
        val fx = floor(x); val fy = floor(y)
        val xi = fx.toInt() and 255
        val yi = fy.toInt() and 255
        val xf = x - fx
        val yf = y - fy

        val u = fade(xf)
        val v = fade(yf)

        val aa = p[p[xi] + yi]
        val ab = p[p[xi] + yi + 1]
        val ba = p[p[xi + 1] + yi]
        val bb = p[p[xi + 1] + yi + 1]

        return lerp(v,
            lerp(u, grad(aa, xf, yf),        grad(ba, xf - 1, yf)),
            lerp(u, grad(ab, xf, yf - 1),    grad(bb, xf - 1, yf - 1))
        )
    }

    fun octaveNoise(x: Double, y: Double, octaves: Int = 4, persistence: Double = 0.5): Double {
        var total = 0.0
        var frequency = 1.0
        var amplitude = 1.0
        var maxValue = 0.0
        repeat(octaves) {
            total += noise(x * frequency, y * frequency) * amplitude
            maxValue += amplitude
            amplitude *= persistence
            frequency *= 2.0
        }
        return total / maxValue
    }
}
