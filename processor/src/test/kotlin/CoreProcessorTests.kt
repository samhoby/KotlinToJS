import com.tschuchort.compiletesting.SourceFile
import org.junit.jupiter.api.Test
import io.github.samhoby.kotlintojs.tests.BaseProcessorTest
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
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class CoreService {
                    fun example(): String = "ok"
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "CoreServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("private val service: CoreService = CoreService()"))
        assertFalse(wrapperCode.contains("<init>"), "Generated code should not contain constructors")
        assertTrue(files.none { file -> file.name == "TypeConversion.kt" }, "TypeConversion should not be generated if there are no maps")
    }

    @Test
    fun `should handle object class kind`() {
        val source =
            SourceFile.kotlin(
                "CoreService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                object CoreService {
                    fun getData(): String = "ok"
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "CoreServiceJs.kt" }.readText()

        assertFalse(wrapperCode.contains("CoreService()"), "Should not instantiate an object")
    }

    @Test
    fun `should accumulate conversions from multiple files into a single TypeConversion file`() {
        val file1 =
            SourceFile.kotlin(
                "FileOne.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass
                @JsExportClass class ServiceA { fun processA(c: Map<String, String>) = c }
                """.trimIndent(),
            )

        val file2 =
            SourceFile.kotlin(
                "FileTwo.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass
                @JsExportClass class ServiceB { fun processB(c: Map<Long, Long>) = c }
                """.trimIndent(),
            )

        val generatedFiles = compile(file1, file2)
        val conversionFiles = generatedFiles.filter { file -> file.name == "TypeConversion.kt" }
        assertEquals(1, conversionFiles.size, "Should generate a single TypeConversion.kt regardless of how many map signatures appear")

        val conversionsCode = conversionFiles.first().readText()
        assertTrue(
            conversionsCode.contains("fun Json.toMap1()"),
            "ServiceA's map conversion should be in TypeConversion.kt",
        )
        assertTrue(
            conversionsCode.contains("fun Json.toMap2()"),
            "ServiceB's map conversion should be in TypeConversion.kt with a distinct ID so the two do not clash",
        )
    }

    @Test
    fun `should not generate TypeConversion file when no map types are present across multiple files`() {
        val file1 =
            SourceFile.kotlin(
                "ListService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

@JsExportClass class ListService {
    fun list(items: List<String>) = items
}

                """.trimIndent(),
            )

        val file2 =
            SourceFile.kotlin(
                "CountService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass
                @JsExportClass class CountService { fun getCount(): Int = 0 }
                """.trimIndent(),
            )

        val generatedFiles = compile(file1, file2)
        assertTrue(generatedFiles.none { file -> file.name == "TypeConversion.kt" }, "Should not generate TypeConversion.kt without maps")
    }

    @Test
    fun `should generate wrapper in the correct package matching the source class`() {
        val source =
            SourceFile.kotlin(
                "UserService.kt",
                """
                package com.example.services
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class UserService {
                    fun ping(): String = "ok"
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "UserServiceJs.kt" }.readText()

        assertTrue(wrapperCode.startsWith("package com.example.services"), "Wrapper should be in same package as source class")
    }

    @Test
    fun `should not include constructor in generated wrapper`() {
        val source =
            SourceFile.kotlin(
                "CoreService.kt",
                $$"""
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class CoreService(private val name: String) {
                    fun greet(): String = "Hello, $name"
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "CoreServiceJs.kt" }.readText()

        assertFalse(wrapperCode.contains("<init>"), "Constructor should never appear in the wrapper")
        assertTrue(wrapperCode.contains("fun greet()"), "Regular function should still be present")
    }

    @Test
    fun `should instantiate service with its required constructor dependencies`() {
        val dependency =
            SourceFile.kotlin(
                "HttpClient.kt",
                """
                package com.example.net
                class HttpClient
                """.trimIndent(),
            )
        val service =
            SourceFile.kotlin(
                "DepService.kt",
                """
                package com.example
                import io.github.samhoby.kotlintojs.annotations.JsExportClass
                import com.example.net.HttpClient

                @JsExportClass
                class DepService(private val client: HttpClient) {
                    fun ping(): String = "ok"
                }
                """.trimIndent(),
            )

        val files = compile(dependency, service)
        val wrapperCode = files.single { file -> file.name == "DepServiceJs.kt" }.readText()

        assertTrue(
            wrapperCode.contains("DepService(client = HttpClient())"),
            "Required constructor dependencies should be instantiated when building the service",
        )
        assertTrue(
            wrapperCode.contains("import com.example.net.HttpClient"),
            "Dependency types in another package should be imported",
        )
    }

    @Test
    fun `should instantiate service with multiple required dependencies separated by commas`() {
        val source =
            SourceFile.kotlin(
                "MultiDepService.kt",
                """
                package com.example
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                class HttpClient
                class Logger

                @JsExportClass
                class MultiDepService(private val client: HttpClient, private val logger: Logger) {
                    fun ping(): String = "ok"
                }
                """.trimIndent(),
            )

        val wrapperCode = compile(source).single { file -> file.name == "MultiDepServiceJs.kt" }.readText()

        assertTrue(
            wrapperCode.contains("MultiDepService(client = HttpClient(), logger = Logger())"),
            "Multiple required dependencies should be comma-separated in the service initializer",
        )
    }

    @Test
    fun `should omit constructor parameters that declare a default value`() {
        val source =
            SourceFile.kotlin(
                "DefaultDepService.kt",
                """
                package com.example
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                class Config
                @JsExportClass
                class DefaultDepService(private val config: Config = Config()) {
                    fun ping(): String = "ok"
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "DefaultDepServiceJs.kt" }.readText()

        assertTrue(
            wrapperCode.contains("private val service: DefaultDepService = DefaultDepService()"),
            "Parameters with defaults should be omitted so the default applies",
        )
    }

    @Test
    fun `should include both suspend and non-suspend functions in the same wrapper`() {
        val source =
            SourceFile.kotlin(
                "CoreService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass
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

        val files = compileWithOptions(listOf(source), mapOf("longAsBigInt" to "true"))
        val wrapperCode = files.single { file -> file.name == "CoreServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("fun ping()"))
        assertTrue(wrapperCode.contains("fun fetchCount()"))
        assertTrue(wrapperCode.contains("private val scope: CoroutineScope = MainScope()"))
        assertTrue(wrapperCode.contains("Promise<Long>"), "Suspend Long return should become Promise<Long> in BigInt mode")
    }
}
