// TARGET_BACKEND: JVM

// MODULE: lib
// FILE: lib.kt

@Suppress("UNUSED_VARIABLE")
fun foo(): String {
    val x = "FAIL"
    return "OK"
}

// MODULE: main(lib)
// FILE: main.kt

fun box() = foo()
