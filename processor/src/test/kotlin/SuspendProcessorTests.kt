package processor

import com.tschuchort.compiletesting.SourceFile
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuspendProcessorTests : BaseProcessorTest() {
    @Test
    fun `should generate scope only for suspend functions`() {
        val sourceWithSuspend =
            SourceFile.kotlin(
                "SuspendService.kt",
                """
                import annotations.JsExportClass
                import kotlinx.coroutines.delay

                @JsExportClass
                class SuspendService {
                    suspend fun fetchData(): String {
                        delay(1)
                        return "ok"
                    }
                }
                """.trimIndent(),
            )

        val filesWithSuspend = compile(sourceWithSuspend)
        val wrapperWithSuspend = filesWithSuspend.single { file -> file.name == "SuspendServiceJs.kt" }.readText()
        assertTrue(wrapperWithSuspend.contains("scope"))

        val sourceWithoutSuspend =
            SourceFile.kotlin(
                "SyncService.kt",
                """
                import annotations.JsExportClass

                @JsExportClass
                class SyncService {
                    fun getData(): String = "ok"
                }
                """.trimIndent(),
            )

        val filesWithoutSuspend = compile(sourceWithoutSuspend)
        val wrapperWithoutSuspend = filesWithoutSuspend.single { file -> file.name == "SyncServiceJs.kt" }.readText()
        assertFalse(wrapperWithoutSuspend.contains("scope"))
    }

    @Test
    fun `should wrap suspend function with Promise`() {
        val source =
            SourceFile.kotlin(
                "SuspendService.kt",
                """
                import annotations.JsExportClass
                import kotlinx.coroutines.delay

                @JsExportClass
                class SuspendService {
                    suspend fun fetchData(): String {
                        delay(1)
                        return "ok"
                    }
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "SuspendServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("Promise<String>"))
        assertTrue(wrapperCode.contains("scope.promise"))
    }

    @Test
    fun `should support suspend functions with long and collection types`() {
        val source =
            SourceFile.kotlin(
                "SuspendService.kt",
                """
                import annotations.JsExportClass
                import kotlinx.coroutines.delay

                @JsExportClass
                class SuspendService {
                    suspend fun fetchIds(): List<Long> {
                        delay(1)
                        return listOf(1L)
                    }
                    suspend fun fetchConfig(): Map<String, Long> {
                        delay(1)
                        return mapOf("a" to 1L)
                    }
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "SuspendServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("Promise<Array<Double>>"))
        assertTrue(wrapperCode.contains("Promise<Json>"))
        assertTrue(wrapperCode.contains("scope.promise"))
        assertTrue(wrapperCode.contains("private val scope: CoroutineScope = MainScope()"))
        assertTrue(wrapperCode.contains("toTypedArray()"))
        assertTrue(wrapperCode.contains("toJson1()"))
    }
}
