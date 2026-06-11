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
    fun `should map Set of Longs to Array of Longs in BigInt mode`() {
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

        val files = compileWithOptions(listOf(source), mapOf("longAsBigInt" to "true"))
        val wrapperCode = files.single { it.name == "CollectionServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("ids: Array<Long>"), "Set<Long> should become Array<Long> in BigInt mode")
        assertTrue(wrapperCode.contains("ids.toSet()"))
        assertTrue(wrapperCode.contains("toTypedArray()"))
    }

    @Test
    fun `should map Map with nested Map value and generate recursive conversion functions`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import annotations.JsExportClass

                @JsExportClass
                class CollectionService {
                    fun getNested(): Map<String, Map<String, Int>> = mapOf("a" to mapOf("b" to 1))
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { it.name == "CollectionServiceJs.kt" }.readText()
        val conversionsCode = files.single { it.name == "TypeConversion.kt" }.readText()

        assertTrue(wrapperCode.contains("Json"), "Nested Map should still expose as Json at the boundary")
        assertTrue(
            conversionsCode.contains("toMap1()") || conversionsCode.contains("toMap2()"),
            "Should generate conversion functions for nested maps",
        )
    }

    @Test
    fun `should map Map parameter with Long key to Json in BigInt mode`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import annotations.JsExportClass

                @JsExportClass
                class CollectionService {
                    fun process(payload: Map<Long, String>): Map<Long, String> = payload
                }
                """.trimIndent(),
            )

        val files = compileWithOptions(listOf(source), mapOf("longAsBigInt" to "true"))
        val wrapperCode = files.single { it.name == "CollectionServiceJs.kt" }.readText()
        val conversionsCode = files.single { it.name == "TypeConversion.kt" }.readText()

        assertTrue(wrapperCode.contains("payload: Json"), "Map<Long, String> parameter should become Json")
        assertTrue(conversionsCode.contains("Map<Long, String>"), "TypeConversion should reference Kotlin types")
        assertTrue(conversionsCode.contains("unsafeCast<Long>()"), "BigInt mode key conversion should unsafeCast to Long")
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
