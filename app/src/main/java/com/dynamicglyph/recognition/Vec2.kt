package com.dynamicglyph.recognition

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 2D vector with Double components, replacing Swift's SIMD2<Double>.
 */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(other: Vec2) = Vec2(x + other.x, y + other.y)
    operator fun minus(other: Vec2) = Vec2(x - other.x, y - other.y)
    operator fun times(scalar: Double) = Vec2(x * scalar, y * scalar)
    operator fun times(other: Vec2) = Vec2(x * other.x, y * other.y)

    /** Squared magnitude. */
    fun lengthSquared(): Double = x * x + y * y

    /** Euclidean magnitude. */
    fun length(): Double = sqrt(lengthSquared())

    /** Dot product. */
    fun dot(other: Vec2): Double = x * other.x + y * other.y

    /** 2D cross product (scalar). */
    fun cross(other: Vec2): Double = x * other.y - y * other.x

    /** Component access by axis, 0 = x, 1 = y. Mirrors SIMD2 subscripting. */
    operator fun get(axis: Int): Double = when (axis) {
        0 -> x
        1 -> y
        else -> throw IndexOutOfBoundsException("Vec2 has 2 components, asked for $axis")
    }

    companion object {
        val ZERO = Vec2(0.0, 0.0)
        fun fromArray(arr: DoubleArray) = Vec2(arr[0], arr[1])
    }
}
