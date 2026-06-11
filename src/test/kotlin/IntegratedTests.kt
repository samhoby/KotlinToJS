package processor

import com.tschuchort.compiletesting.SourceFile
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntegratedTests : BaseProcessorTest() {
    @Test
    fun `should combine collections long and suspend mappings in one wrapper`() {
        val source =
            SourceFile.kotlin(
                "IntegratedService.kt",
                """
                import annotations.JsExportClass
                import kotlinx.coroutines.delay

                @JsExportClass
                class IntegratedService {
                	fun normalize(values: List<Long>): List<Long> = values

                	fun describe(config: Map<String, List<Long>>): Map<String, List<Long>> = config

                	suspend fun load(ids: Set<Long>): Set<Long> {
                		delay(1)
                		return ids
                	}
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { it.name == "IntegratedServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("private val service: IntegratedService = IntegratedService()"))
        assertTrue(wrapperCode.contains("private val scope: CoroutineScope = MainScope()"))
        assertTrue(wrapperCode.contains("values: Array<Double>"))
        assertTrue(wrapperCode.contains("values.toList()"))
        assertTrue(wrapperCode.contains("config: Json"))
        assertTrue(wrapperCode.contains("toMap1()"))
        assertTrue(wrapperCode.contains("toJson1()"))
        assertTrue(wrapperCode.contains("ids: Array<Double>"))
        assertTrue(wrapperCode.contains("ids.toSet()"))
        assertTrue(wrapperCode.contains("Promise<Array<Double>>"))
        assertTrue(wrapperCode.contains("scope.promise"))
    }

    @Test
    fun `should keep a single type conversion file for mixed integrated sources`() {
        val collectionsFile =
            SourceFile.kotlin(
                "CollectionsCoreService.kt",
                """
                import annotations.JsExportClass

                @JsExportClass
                class CollectionsCoreService {
                	fun extract(payload: Map<String, List<Long>>): Map<String, List<Long>> = payload
                }
                """.trimIndent(),
            )

        val suspendFile =
            SourceFile.kotlin(
                "SuspendLongService.kt",
                """
                import annotations.JsExportClass
                import kotlinx.coroutines.delay

                @JsExportClass
                class SuspendLongService {
                	suspend fun fetch(ids: Set<Long>): Set<Long> {
                		delay(1)
                		return ids
                	}
                	fun mirror(payload: Map<Long, Set<Long>>): Map<Long, Set<Long>> = payload
                }
                """.trimIndent(),
            )

        val generatedFiles = compile(collectionsFile, suspendFile)

        val typeConversionFiles = generatedFiles.filter { it.name == "TypeConversion.kt" }
        assertEquals(1, typeConversionFiles.size, "Should generate a single TypeConversion.kt")

        val collectionsWrapper = generatedFiles.single { it.name == "CollectionsCoreServiceJs.kt" }.readText()
        assertTrue(collectionsWrapper.contains("payload: Json"))
        assertTrue(collectionsWrapper.contains("toMap1()"))
        assertTrue(collectionsWrapper.contains("toJson1()"))

        val suspendWrapper = generatedFiles.single { it.name == "SuspendLongServiceJs.kt" }.readText()
        assertTrue(suspendWrapper.contains("private val scope: CoroutineScope = MainScope()"))
        assertTrue(suspendWrapper.contains("Promise<Array<Double>>"))
        assertTrue(suspendWrapper.contains("ids.toSet()"))
        assertTrue(suspendWrapper.contains("payload: Json"))
        assertTrue(suspendWrapper.contains("toMap2()"))
        assertTrue(suspendWrapper.contains("toJson2()"))

        val conversionsCode = typeConversionFiles.first().readText()
        assertTrue(conversionsCode.contains("fun Json.toMap1()"))
        assertTrue(conversionsCode.contains("fun Json.toMap2()"))
        assertTrue(conversionsCode.contains("toJson1(): Json"))
        assertTrue(conversionsCode.contains("toJson2(): Json"))
    }
}
