// FIR_IDENTICAL
// FILE: test/UseKotlinInner.java
package test;

public class UseKotlinInner extends KotlinClass {

    KotlinInner getKotlinInner() { return null; }

    JavaInner getJavaInner() { return null; }

    KotlinInner3 getKotlinInner3() { return null; }
}

// FILE: test/JavaClass2.java
package test;

public class JavaClass2  {
    public static class JavaInner {}
}

// FILE: test/UseKotlinInner.kt
package test

open class KotlinClass : KotlinInterface.KotlinInner2() {
    inner class KotlinInner
}

interface KotlinInterface {
    open class KotlinInner2 : JavaClass2() {
        class KotlinInner3
    }
}

private fun getKotlinInner() = UseKotlinInner().kotlinInner

private fun getJavaInner() = <!RETURN_TYPE_MISMATCH!>UseKotlinInner().javaInner<!>

private fun getKotlinInner3() = <!RETURN_TYPE_MISMATCH!>UseKotlinInner().kotlinInner3<!>
