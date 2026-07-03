import com.tschuchort.compiletesting.SourceFile
import io.github.samhoby.kotlintojs.tests.BaseProcessorTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntegratedTests : BaseProcessorTest() {
    private val bigint = mapOf("longAsBigInt" to "true")

    @Test
    fun `should generate every conversion for the complete README example`() {
        val source =
            SourceFile.kotlin(
                "CatalogService.kt",
                $$"""
                import io.github.samhoby.kotlintojs.annotations.JsExportClass
                import io.github.samhoby.kotlintojs.annotations.JsExportFunction
                import kotlinx.coroutines.Dispatchers
                import kotlinx.coroutines.withContext

                @JvmInline
                value class UserId(val value: String)

                @JsExportClass
                class CatalogService {
                    fun count(): Int = 0
                    fun tags(): List<String> = listOf("a", "b")
                    fun prices(): Map<String, Int> = mapOf("apple" to 3)
                    fun total(): Long = 42L
                    fun owner(id: UserId): String = id.value
                    suspend fun search(q: String): List<String> =
                        withContext(Dispatchers.Default) { listOf(q) }
                }

                @JsExportFunction
                fun greet(name: String): String = "Hello, $name"
                """.trimIndent(),
            )

        val files = compileWithOptions(listOf(source), bigint)
        val wrapper = files.single { file -> file.name == "CatalogServiceJs.kt" }.readText()
        val utils = files.single { file -> file.name == "JsExportUtils.kt" }.readText()
        val conversions = files.single { file -> file.name == "TypeConversion.kt" }.readText()

        assertTrue(wrapper.contains("private val service: CatalogService = CatalogService()"))
        assertTrue(wrapper.contains("private val scope: CoroutineScope = MainScope()"))

        assertTrue(wrapper.contains("fun count(): Int = service.count()"), "Int should pass through unchanged")

        assertTrue(
            wrapper.contains("fun tags(): Array<String> = service.tags().toTypedArray()"),
            "List<String> should be exposed as Array<String>",
        )

        assertTrue(
            wrapper.contains("fun prices(): Json = (service.prices()).toJson1()"),
            "Map return should be encoded to Json via toJson1()",
        )
        assertTrue(wrapper.contains("fun total(): Long = service.total()"), "Long should pass through in BigInt mode")
        assertTrue(
            wrapper.contains("fun owner(id: String): String = service.owner(UserId(id))"),
            "Value class parameter should be unwrapped to String and re-wrapped as UserId(id)",
        )
        assertTrue(
            wrapper.contains("fun search(q: String): Promise<Array<String>> ="),
            "Suspend List<String> return should become Promise<Array<String>>",
        )
        assertTrue(wrapper.contains("scope.promise { service.search(q).toTypedArray() }"))

        assertTrue(wrapper.contains("import kotlin.js.Json"))
        assertTrue(wrapper.contains("import kotlinx.coroutines.promise"))
        assertTrue(wrapper.contains("import kotlintojs.generated.toJson1"))

        assertTrue(utils.contains("object JsExportUtils"))
        assertTrue(utils.contains("fun greet(name: String): String = greet(name)"))

        assertTrue(conversions.contains("fun Json.toMap1()"))
        assertTrue(conversions.contains("fun Map<String, Int>.toJson1(): Json"))
    }

    @Test
    fun `should combine collections long and suspend mappings in one wrapper`() {
        val source =
            SourceFile.kotlin(
                "IntegratedService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass
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

        val files = compileWithOptions(listOf(source), bigint)
        val wrapperCode = files.single { file -> file.name == "IntegratedServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("private val service: IntegratedService = IntegratedService()"))
        assertTrue(wrapperCode.contains("private val scope: CoroutineScope = MainScope()"))
        assertTrue(wrapperCode.contains("values: Array<Long>"), "List<Long> should become Array<Long> in BigInt mode")
        assertTrue(wrapperCode.contains("values.toList()"))
        assertTrue(wrapperCode.contains("config: Json"))
        assertTrue(wrapperCode.contains("config.toMap1()"))
        assertTrue(wrapperCode.contains("toJson1()"))
        assertTrue(wrapperCode.contains("ids: Array<Long>"), "Set<Long> should become Array<Long> in BigInt mode")
        assertTrue(wrapperCode.contains("ids.toSet()"))
        assertTrue(
            wrapperCode.contains("Promise<Array<Long>>"),
            "Suspend Set<Long> return should become Promise<Array<Long>> in BigInt mode",
        )
        assertTrue(wrapperCode.contains("scope.promise"))
    }

    @Test
    fun `should keep a single TypeConversion file for mixed integrated sources`() {
        val collectionsFile =
            SourceFile.kotlin(
                "CollectionsCoreService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

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
                import io.github.samhoby.kotlintojs.annotations.JsExportClass
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

        val generatedFiles = compileWithOptions(listOf(collectionsFile, suspendFile), bigint)

        val conversionFiles = generatedFiles.filter { file -> file.name == "TypeConversion.kt" }
        assertEquals(
            1,
            conversionFiles.size,
            "Should generate a single TypeConversion.kt regardless of how many map signatures appear",
        )

        val collectionsWrapper = generatedFiles.single { file -> file.name == "CollectionsCoreServiceJs.kt" }.readText()
        assertTrue(collectionsWrapper.contains("payload: Json"))
        assertTrue(collectionsWrapper.contains("payload.toMap1()"))
        assertTrue(collectionsWrapper.contains("toJson1()"))

        val suspendWrapper = generatedFiles.single { file -> file.name == "SuspendLongServiceJs.kt" }.readText()
        assertTrue(suspendWrapper.contains("private val scope: CoroutineScope = MainScope()"))
        assertTrue(
            suspendWrapper.contains("Promise<Array<Long>>"),
            "Suspend Set<Long> should become Promise<Array<Long>> in BigInt mode",
        )
        assertTrue(suspendWrapper.contains("ids.toSet()"))
        assertTrue(suspendWrapper.contains("payload: Json"))
        assertTrue(suspendWrapper.contains("payload.toMap2()"))
        assertTrue(suspendWrapper.contains("toJson2()"))

        val conversionsCode = conversionFiles.first().readText()
        assertTrue(
            conversionsCode.contains("fun Json.toMap1()"),
            "TypeConversion.kt should contain the first map's decode function",
        )
        assertTrue(
            conversionsCode.contains("fun Json.toMap2()"),
            "TypeConversion.kt should contain the second map's decode function with a distinct ID",
        )
        assertTrue(
            conversionsCode.contains("toJson1(): Json"),
            "TypeConversion.kt should contain the first map's encode function",
        )
        assertTrue(
            conversionsCode.contains("toJson2(): Json"),
            "TypeConversion.kt should contain the second map's encode function with a distinct ID",
        )
    }
}
