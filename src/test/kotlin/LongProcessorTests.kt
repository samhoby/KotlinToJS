package processor

import com.tschuchort.compiletesting.SourceFile
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class LongProcessorTests : BaseProcessorTest() {
    @Test
    fun `should map Map to Long and List of Longs`() {
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

        val files = compile(source)
        val wrapperCode = files.single { it.name == "LongServiceJs.kt" }.readText()
        println(wrapperCode)

        assertTrue(wrapperCode.contains("Json"))
        assertTrue(wrapperCode.contains("toJson1()"))
    }

    @Test
    fun `should convert Long parameters and returns to Double`() {
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

        val files = compile(source)
        val wrapperCode = files.single { it.name == "LongServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("id: Double"))
        assertTrue(wrapperCode.contains("toLong()"))
        assertTrue(wrapperCode.contains("toDouble()"))
    }

    @Test
    fun `should convert Longs nested inside collections and maps`() {
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

        val files = compile(source)
        val wrapperCode = files.single { it.name == "LongServiceJs.kt" }.readText()
        val conversionsCode = files.single { it.name == "TypeConversion.kt" }.readText()

        assertTrue(wrapperCode.contains("values: Array<Double>"))
        assertTrue(wrapperCode.contains("values.toList()"))
        assertTrue(wrapperCode.contains("toJson1()"))
        assertTrue(conversionsCode.contains("fun Json.toMap1()"))
        assertTrue(conversionsCode.contains("Map<Long, List<Long>>"))
        assertTrue(conversionsCode.contains("toLong()"))
        assertTrue(conversionsCode.contains("toDouble()"))
        assertTrue(conversionsCode.contains("toTypedArray()"))
    }
}
