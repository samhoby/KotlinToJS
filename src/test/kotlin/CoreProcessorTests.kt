package processor

import com.tschuchort.compiletesting.SourceFile
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreProcessorTests : BaseProcessorTest() {
    @Test
    fun `should generate wrapper object with service instance`() {
        val source =
            SourceFile.kotlin(
                "CoreService.kt",
                """
                import annotations.JsExportClass

                @JsExportClass
                class CoreService {
                    fun example(): String = "ok"
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { it.name == "CoreServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("private val service: CoreService = CoreService()"))
        assertFalse(wrapperCode.contains("<init>"), "Generated code should not contain constructors")
        assertTrue(files.none { it.name == "TypeConversion.kt" }, "TypeConversion should not be generated if there are no maps")
    }

    @Test
    fun `should handle object class kind`() {
        val source =
            SourceFile.kotlin(
                "CoreService.kt",
                """
                import annotations.JsExportClass

                @JsExportClass
                object CoreService {
                    fun getData(): String = "ok"
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { it.name == "CoreServiceJs.kt" }.readText()

        assertFalse(wrapperCode.contains("CoreService()"), "Should not instantiate an object")
    }

    @Test
    fun `should accumulate conversions from multiple files into a single TypeConversion file`() {
        val file1 =
            SourceFile.kotlin(
                "FileOne.kt",
                """
                import annotations.JsExportClass
                @JsExportClass class ServiceA { fun processA(c: Map<String, String>) = c }
                """.trimIndent(),
            )

        val file2 =
            SourceFile.kotlin(
                "FileTwo.kt",
                """
                import annotations.JsExportClass
                @JsExportClass class ServiceB { fun processB(c: Map<Long, Long>) = c }
                """.trimIndent(),
            )

        val generatedFiles = compile(file1, file2)
        val typeConversionFiles = generatedFiles.filter { it.name == "TypeConversion.kt" }
        assertEquals(1, typeConversionFiles.size, "Should generate only one TypeConversion.kt")

        val conversionsCode = typeConversionFiles.first().readText()
        assertTrue(conversionsCode.contains("fun Json.toMap1()"))
        assertTrue(conversionsCode.contains("fun Json.toMap2()"))
    }

    @Test
    fun `should not generate TypeConversion file when no map types are present across multiple files`() {
        val file1 =
            SourceFile.kotlin(
                "ListService.kt",
                """
                import annotations.JsExportClass
                @JsExportClass class ListService { fun list(items: List<String>) = items }
                """.trimIndent(),
            )

        val file2 =
            SourceFile.kotlin(
                "LongService.kt",
                """
                import annotations.JsExportClass
                @JsExportClass class LongService { fun convert(id: Long) = id }
                """.trimIndent(),
            )

        val generatedFiles = compile(file1, file2)
        assertTrue(generatedFiles.none { it.name == "TypeConversion.kt" }, "Should not generate TypeConversion.kt without maps")
    }

    @Test
    fun `should include both suspend and non-suspend functions in the same wrapper`() {
        val source =
            SourceFile.kotlin(
                "CoreService.kt",
                """
                import annotations.JsExportClass
                import kotlinx.coroutines.delay

                @JsExportClass
                class CoreService {
                    fun ping(): String = "ok"
                    suspend fun fetchCount(): Long {
                        delay(1)
                        return 1L
                    }
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { it.name == "CoreServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("fun ping()"))
        assertTrue(wrapperCode.contains("fun fetchCount()"))
        assertTrue(wrapperCode.contains("private val scope: CoroutineScope = MainScope()"))
        assertTrue(wrapperCode.contains("Promise<Double>"))
    }
}
