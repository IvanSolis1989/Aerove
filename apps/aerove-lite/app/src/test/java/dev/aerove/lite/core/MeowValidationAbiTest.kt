package dev.aerove.lite.core

import java.lang.reflect.Modifier
import org.junit.Assert.*
import org.junit.Test

/** Resolves the JNI contract without initializing or loading the native library. */
class MeowValidationAbiTest {
    private val facade = Class.forName("dev.aerove.lite.core.MeowNativeCore", false, javaClass.classLoader)

    @Test fun validationMethodsKeepNullableDiagnosticContract() {
        method("validate", 1)
        method("validateResource", 4)
    }

    @Test fun ruleInspectionAndFullDumpKeepStringContract() {
        method("inspectRuleSet", 1)
        method("dumpRuleSet", 3)
    }

    private fun method(name: String, arity: Int) {
        val method = facade.getDeclaredMethod(name, *Array(arity) { String::class.java })
        assertEquals(String::class.java, method.returnType)
        assertTrue(Modifier.isPublic(method.modifiers))
        assertTrue(Modifier.isStatic(method.modifiers))
        assertTrue(Modifier.isNative(method.modifiers))
    }
}
