package dev.aerove.lite.flutter

import java.math.BigDecimal
import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MeowJsonTest {
    @Test fun objectKeysAreUnorderedAndNestedArraysRemainOrdered() {
        assertTrue(meowJsonEqual(JSONObject("""{"a":[1,{"b":null,"c":true}],"d":"text"}"""),
            JSONObject("""{"d":"text","a":[1.0,{"c":true,"b":null}]}""")))
        assertFalse(meowJsonEqual(JSONObject("""{"a":1}"""), JSONObject("""{"b":1}""")))
        assertFalse(meowJsonEqual(JSONObject("""{"a":null}"""), JSONObject()))
        assertFalse(meowJsonEqual(JSONArray("[1,2]"), JSONArray("[2,1]")))
        assertFalse(meowJsonEqual(JSONArray("[1]"), JSONArray("[1,2]")))
    }

    @Test fun nullAndJsonNullAreEquivalentButTypesAreNotCoerced() {
        assertTrue(meowJsonEqual(null, JSONObject.NULL))
        assertTrue(meowJsonEqual(JSONObject.NULL, null))
        assertTrue(meowJsonEqual(JSONObject.NULL, JSONObject.NULL))
        assertFalse(meowJsonEqual(JSONObject.NULL, "null"))
        assertFalse(meowJsonEqual("1", 1))
        assertFalse(meowJsonEqual(true, 1))
        assertFalse(meowJsonEqual(JSONObject(), JSONArray()))
    }

    @Test fun numericEqualityPreservesBigIntegerAndDecimalPrecision() {
        assertTrue(meowJsonEqual(BigInteger("9007199254740993"), 9007199254740993L))
        assertFalse(meowJsonEqual(BigInteger("9007199254740993"), 9007199254740992.0))
        assertFalse(meowJsonEqual(BigInteger("18446744073709551615"), BigInteger("18446744073709551614")))
        assertTrue(meowJsonEqual(BigDecimal("1.000000"), 1))
        assertFalse(meowJsonEqual(BigDecimal("1.00000000000000000001"), 1))
        assertTrue(meowJsonEqual(BigDecimal(Long.MAX_VALUE.toString()), Long.MAX_VALUE))
        assertFalse(meowJsonEqual(Long.MAX_VALUE, Long.MAX_VALUE.toDouble()))
        assertFalse(meowJsonEqual(BigDecimal("0.10000000000000001"), 0.1))
        assertFalse(meowJsonEqual(1.9, 1))
    }

    @Test fun invalidNonfiniteNumbersAreNeverJsonEqual() {
        assertFalse(meowJsonEqual(Double.NaN, Double.NaN))
        assertFalse(meowJsonEqual(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY))
        assertFalse(meowJsonEqual(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY))
    }
}
