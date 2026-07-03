import com.tschuchort.compiletesting.SourceFile
import org.junit.jupiter.api.Test
import io.github.samhoby.kotlintojs.tests.BaseProcessorTest
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CollectionProcessorTests : BaseProcessorTest() {
    @Test
    fun `should map List return type to Array`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass
                
                @JsExportClass
                class CollectionService {
                    fun getItems(): List<String> = listOf("a", "b")
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "CollectionServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("Array<String>"))
        assertTrue(wrapperCode.contains("toTypedArray()"))
    }

    @Test
    fun `should map List parameter type to Array`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass
                
                @JsExportClass
                class CollectionService {
                    fun process(items: List<String>) = items
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "CollectionServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("items: Array<String>"))
        assertTrue(wrapperCode.contains("items.toList()"))
    }

    @Test
    fun `should map nested List to nested Array`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class CollectionService {
                    fun getMatrix(): List<List<String>> = listOf(listOf("a"))
                    fun getDataFrame(): List<List<List<String>>> = listOf(listOf(listOf("a")))
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "CollectionServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("Array<Array<String>>"))
        assertTrue(
            wrapperCode.contains("service.getMatrix().map { elem -> elem.toTypedArray() }.toTypedArray()"),
            "Nested list return must convert inner lists recursively, not just the outer one",
        )
    }

    @Test
    fun `should map nested List parameter recursively back to nested List`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class CollectionService {
                    fun setMatrix(matrix: List<List<String>>) = matrix
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "CollectionServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("matrix: Array<Array<String>>"), "Nested list parameter should expose nested Array")
        assertTrue(
            wrapperCode.contains("matrix.map { elem -> elem.toList() }.toList()"),
            "Nested list parameter must convert inner arrays recursively, not just the outer one",
        )
    }

    @Test
    fun `should map Map to Json`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass
                
                @JsExportClass
                class CollectionService {
                    fun getConfig(): Map<String, String> = mapOf("key" to "value")
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "CollectionServiceJs.kt" }.readText()
        val conversionsCode = files.single { file -> file.name == "TypeConversion.kt" }.readText()

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
                import io.github.samhoby.kotlintojs.annotations.JsExportClass
                
                @JsExportClass
                class CollectionService {
                    fun process(config: Map<String, String>) = config
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "CollectionServiceJs.kt" }.readText()
        val conversionsCode = files.single { file -> file.name == "TypeConversion.kt" }.readText()

        assertTrue(wrapperCode.contains("config: Json"))
        assertTrue(wrapperCode.contains("config.toMap1()"))
        assertNotNull(conversionsCode)
    }

    @Test
    fun `should map Set of Longs to Array of Longs in BigInt mode`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class CollectionService {
                    fun updateIds(ids: Set<Long>): Set<Long> = ids
                }
                """.trimIndent(),
            )

        val files = compileWithOptions(listOf(source), mapOf("longAsBigInt" to "true"))
        val wrapperCode = files.single { file -> file.name == "CollectionServiceJs.kt" }.readText()

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
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class CollectionService {
                    fun getNested(): Map<String, Map<String, Int>> = mapOf("a" to mapOf("b" to 1))
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "CollectionServiceJs.kt" }.readText()
        val conversionsCode = files.single { file -> file.name == "TypeConversion.kt" }.readText()

        assertTrue(wrapperCode.contains("Json"), "Nested Map should still expose as Json at the boundary")
        assertTrue(
            conversionsCode.contains("fun Json.toMap1()"),
            "Should generate the outer map decode function (ID 1, registered first)",
        )
        assertTrue(
            conversionsCode.contains("fun Json.toMap2()"),
            "Should recursively register the inner map's conversion function (ID 2, registered during nested walk)",
        )
    }

    @Test
    fun `should map Map parameter with Long key to Json in BigInt mode`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class CollectionService {
                    fun process(payload: Map<Long, String>): Map<Long, String> = payload
                }
                """.trimIndent(),
            )

        val files = compileWithOptions(listOf(source), mapOf("longAsBigInt" to "true"))
        val wrapperCode = files.single { file -> file.name == "CollectionServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("payload: Json"), "Map<Long, String> parameter should become Json")
        assertTrue(
            wrapperCode.contains("payload.toMap1()"),
            "Wrapper should call the generated decode function to convert Json back to Map<Long, String>",
        )
        assertFalse(
            wrapperCode.contains("unsafeCast<Map<Long, String>>()"),
            "The decode function returns the correct type — no unsafeCast needed in the wrapper",
        )
    }

    @Test
    fun `should map nested collection types across list and set`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass
                
                @JsExportClass
                class CollectionService {
                    fun getGroups(): List<Set<String>> = listOf(setOf("a"))
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "CollectionServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("Array<Array<String>>"))
        assertTrue(wrapperCode.contains("toTypedArray()"))
    }

    @Test
    fun `should map a List of Maps to an Array of Json`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class CollectionService {
                    fun getRows(): List<Map<String, Int>> = listOf(mapOf("a" to 1))
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "CollectionServiceJs.kt" }.readText()

        assertTrue(
            wrapperCode.contains("Array<Json>"),
            "A Map nested as a list element resolves its element type to Json at the boundary",
        )
    }

    @Test
    fun `should convert List of Longs with toLong and toDouble in default mode`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class CollectionService {
                    fun roundTrip(ids: List<Long>): List<Long> = ids
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "CollectionServiceJs.kt" }.readText()

        assertTrue(
            wrapperCode.contains("ids: Array<Double>"),
            "In default mode Long elements are exposed as Double at the boundary",
        )
        assertTrue(
            wrapperCode.contains("elem.toLong()"),
            "The Array<Double> parameter must convert each element back to Long",
        )
        assertTrue(
            wrapperCode.contains("elem.toDouble()"),
            "The List<Long> return must convert each element to Double for the boundary",
        )
    }
}
