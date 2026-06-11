package processor

import com.tschuchort.compiletesting.SourceFile
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CollectionProcessorTests : BaseProcessorTest() {
    @Test
    fun `should map List return type to Array`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import annotations.JsExportClass
                
                @JsExportClass
                class CollectionService {
                    fun getItems(): List<String> = listOf("a", "b")
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { it.name == "CollectionServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("Array<String>"))
        assertTrue(wrapperCode.contains("toTypedArray()"))
    }

    @Test
    fun `should map List parameter type to Array`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import annotations.JsExportClass
                
                @JsExportClass
                class CollectionService {
                    fun process(items: List<String>) = items
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { it.name == "CollectionServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("items: Array<String>"))
        assertTrue(wrapperCode.contains("items.toList()"))
    }

    @Test
    fun `should map nested List to nested Array`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import annotations.JsExportClass
                
                @JsExportClass
                class CollectionService {
                    fun getMatrix(): List<List<String>> = listOf(listOf("a"))
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { it.name == "CollectionServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("Array<Array<String>>"))
    }

    @Test
    fun `should map Map to Json`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import annotations.JsExportClass
                
                @JsExportClass
                class CollectionService {
                    fun getConfig(): Map<String, String> = mapOf("key" to "value")
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { it.name == "CollectionServiceJs.kt" }.readText()
        val conversionsCode = files.single { it.name == "TypeConversion.kt" }.readText()

        assertTrue(wrapperCode.contains("Json"))
        assertTrue(wrapperCode.contains("toJson1()"))
        assertNotNull(conversionsCode)
        assertTrue(conversionsCode.contains("fun Json.toMap1()"))
        assertTrue(conversionsCode.contains("toJson1(): Json"))
    }

    @Test
    fun `should map Map parameter to Json and generate TypeConversion`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import annotations.JsExportClass
                
                @JsExportClass
                class CollectionService {
                    fun process(config: Map<String, String>) = config
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { it.name == "CollectionServiceJs.kt" }.readText()
        val conversionsCode = files.single { it.name == "TypeConversion.kt" }.readText()

        assertTrue(wrapperCode.contains("config: Json"))
        assertTrue(wrapperCode.contains("toMap1()"))
        assertNotNull(conversionsCode)
    }

    @Test
    fun `should map Set of Longs to Array of Doubles`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import annotations.JsExportClass
                
                @JsExportClass
                class CollectionService {
                    fun updateIds(ids: Set<Long>): Set<Long> = ids
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { it.name == "CollectionServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("ids: Array<Double>"))
        assertTrue(wrapperCode.contains("ids.toSet()"))
        assertTrue(wrapperCode.contains("toTypedArray()"))
    }

    @Test
    fun `should map nested collection types across list and set`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import annotations.JsExportClass
                
                @JsExportClass
                class CollectionService {
                    fun getGroups(): List<Set<String>> = listOf(setOf("a"))
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { it.name == "CollectionServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("Array<Array<String>>"))
        assertTrue(wrapperCode.contains("toTypedArray()"))
    }
}
