package processor

import com.tschuchort.compiletesting.SourceFile
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LongProcessorTests : BaseProcessorTest() {
    private val bigint = mapOf("longAsBigInt" to "true")

    @Test
    fun `should emit build warning when Long is used at boundary without BigInt mode`() {
        val source =
            SourceFile.kotlin(
                "LongService.kt",
                """
                import annotations.JsExportClass

                @JsExportClass
                class LongService {
                    fun processId(id: Long): Long = id
                }
                """.trimIndent(),
            )

        val messages = compileWithMessages(source)

        assertTrue(
            messages.any { message -> message.contains("Long is not supported at @JsExport boundaries") },
            "Should report a build warning explaining the BigInt requirement",
        )
        assertTrue(
            messages.any { message -> message.contains("longAsBigInt") },
            "Warning message should mention the longAsBigInt plugin option",
        )
    }

    @Test
    fun `should expose Long directly without conversion in BigInt mode`() {
        val source =
            SourceFile.kotlin(
                "LongService.kt",
                """
                import annotations.JsExportClass

                @JsExportClass
                class LongService {
                    fun processId(id: Long): Long = id
                }
                """.trimIndent(),
            )

        val files = compileWithOptions(listOf(source), bigint)
        val wrapperCode = files.single { file -> file.name =="LongServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("id: Long"), "Long parameter should stay Long in BigInt mode")
        assertFalse(wrapperCode.contains("toLong()"), "Should not call toLong() in BigInt mode")
        assertFalse(wrapperCode.contains("toDouble()"), "Should not call toDouble() in BigInt mode")
    }

    @Test
    fun `should expose Map with Long keys and List of Longs in BigInt mode`() {
        val source =
            SourceFile.kotlin(
                "LongService.kt",
                """
                import annotations.JsExportClass

                @JsExportClass
                class LongService {
                    fun getConfig(): Map<Long, List<Long>> = mapOf(1L to listOf(1L))
                }
                """.trimIndent(),
            )

        val files = compileWithOptions(listOf(source), bigint)
        val wrapperCode = files.single { file -> file.name =="LongServiceJs.kt" }.readText()
        val conversionsCode = files.single { file -> file.name =="TypeConversion.kt" }.readText()

        assertTrue(wrapperCode.contains("Json"), "Map return should be exposed as Json")
        assertTrue(wrapperCode.contains("toJson1()"), "Should call the map's encode function")
        assertTrue(
            conversionsCode.contains("fun Json.toMap1()"),
            "TypeConversion should contain the map decode function",
        )
        assertFalse(conversionsCode.contains("toDouble()"), "BigInt mode should not emit toDouble() in map conversions")
    }

    @Test
    fun `should expose nested Long collections as Array of Long in BigInt mode`() {
        val source =
            SourceFile.kotlin(
                "LongService.kt",
                """
                import annotations.JsExportClass

                @JsExportClass
                class LongService {
                    fun convert(values: List<Long>): Map<Long, List<Long>> = mapOf(1L to values)
                }
                """.trimIndent(),
            )

        val files = compileWithOptions(listOf(source), bigint)
        val wrapperCode = files.single { file -> file.name =="LongServiceJs.kt" }.readText()
        val conversionsCode = files.single { file -> file.name =="TypeConversion.kt" }.readText()

        assertTrue(wrapperCode.contains("values: Array<Long>"), "List<Long> parameter should become Array<Long> in BigInt mode")
        assertTrue(wrapperCode.contains("values.toList()"), "Should still call toList() to convert the array back")
        assertTrue(wrapperCode.contains("toJson1()"), "Should call the map's encode function for the return")
        assertFalse(conversionsCode.contains("toDouble()"), "BigInt mode should not convert Long to Double anywhere")
    }
}
