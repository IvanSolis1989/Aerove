package dev.aerove.lite.core

import java.lang.reflect.Modifier
import org.junit.Assert.*
import org.junit.Test

/** Resolves the JNI contract without initializing or loading the native library. */
class MeowValidationAbiTest {
    private val facade = Class.forName("dev.aerove.lite.core.MeowNativeCore", false, javaClass.classLoader)

    @Test fun validationMethodsKeepNullableDiagnosticContract() {
        method("validate", owned = true, strings = 1)
        method("validateResource", owned = true, strings = 4)
    }

    @Test fun ruleInspectionAndFullDumpKeepStringContract() {
        method("inspectRuleSet", owned = true, strings = 1)
        method("dumpRuleSet", owned = true, strings = 3)
    }

    // File reads take the offline owner handle first; no handle-less overload may remain reachable.
    private fun method(name: String, owned: Boolean, strings: Int) {
        val parameters = (if (owned) listOf<Class<*>>(java.lang.Long.TYPE) else emptyList()) + List(strings) { String::class.java }
        val method = facade.getDeclaredMethod(name, *parameters.toTypedArray())
        assertEquals(String::class.java, method.returnType)
        assertTrue(Modifier.isPublic(method.modifiers))
        assertTrue(Modifier.isStatic(method.modifiers))
        assertTrue(Modifier.isNative(method.modifiers))
        assertEquals(1, facade.declaredMethods.count { it.name == name })
    }
}
