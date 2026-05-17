package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureKind
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import junit.framework.TestCase
import javax.swing.Icon

class IdeStructureViewExtractorUnitTest : TestCase() {

    fun testConvertTreeElementsKeepsHierarchyAndFlattensUnclassifiedWrappers() {
        val method = FakeTreeElement("save(): void", "method:save")
        val wrapper = FakeTreeElement("members", "wrapper:members", listOf(method))
        val klass = FakeTreeElement("User", "class:User", listOf(wrapper))

        val nodes = IdeStructureViewExtractor.convertTreeElements(
            elements = arrayOf(klass),
            classifier = TestClassifier,
            lineResolver = { value ->
                when (value) {
                    "class:User" -> 5
                    "method:save" -> 11
                    else -> null
                }
            }
        )

        assertEquals(1, nodes.size)
        val classNode = nodes.single()
        assertEquals("User", classNode.name)
        assertEquals(StructureKind.CLASS, classNode.kind)
        assertEquals(5, classNode.line)
        assertEquals(1, classNode.children.size)

        val methodNode = classNode.children.single()
        assertEquals("save", methodNode.name)
        assertEquals(StructureKind.METHOD, methodNode.kind)
        assertEquals(listOf("public"), methodNode.modifiers)
        assertEquals("(): void", methodNode.signature)
        assertEquals(11, methodNode.line)
    }

    fun testConvertTreeElementsFallsBackToFirstChildLineForClassifiedParent() {
        val function = FakeTreeElement("helper()", "function:helper")
        val namespace = FakeTreeElement("App\\Demo", "namespace:App\\Demo", listOf(function))

        val nodes = IdeStructureViewExtractor.convertTreeElements(
            elements = arrayOf(namespace),
            classifier = TestClassifier,
            lineResolver = { value ->
                when (value) {
                    "function:helper" -> 21
                    else -> null
                }
            }
        )

        assertEquals(1, nodes.size)
        assertEquals("App\\Demo", nodes.single().name)
        assertEquals(StructureKind.NAMESPACE, nodes.single().kind)
        assertEquals(21, nodes.single().line)
    }

    fun testConvertTreeElementsSkipsCycles() {
        val cyclic = MutableFakeTreeElement("User", "class:User")
        cyclic.children = listOf(cyclic)

        val nodes = IdeStructureViewExtractor.convertTreeElements(
            elements = arrayOf(cyclic),
            classifier = TestClassifier,
            lineResolver = { 7 }
        )

        assertEquals(1, nodes.size)
        assertEquals("User", nodes.single().name)
        assertTrue(nodes.single().children.isEmpty())
    }

    private object TestClassifier : IdeStructureViewExtractor.Classifier {
        override fun describe(
            value: Any?,
            presentation: ItemPresentation
        ): IdeStructureViewExtractor.StructureElementInfo? {
            return when (value) {
                "class:User" -> IdeStructureViewExtractor.StructureElementInfo(
                    name = "User",
                    kind = StructureKind.CLASS
                )
                "method:save" -> IdeStructureViewExtractor.StructureElementInfo(
                    name = "save",
                    kind = StructureKind.METHOD,
                    modifiers = listOf("public"),
                    signature = "(): void"
                )
                "function:helper" -> IdeStructureViewExtractor.StructureElementInfo(
                    name = "helper",
                    kind = StructureKind.FUNCTION,
                    signature = "()"
                )
                "namespace:App\\Demo" -> IdeStructureViewExtractor.StructureElementInfo(
                    name = "App\\Demo",
                    kind = StructureKind.NAMESPACE
                )
                else -> null
            }
        }
    }

    private open class FakeTreeElement(
        private val label: String,
        private val valueObject: Any,
        private val childElements: List<TreeElement> = emptyList()
    ) : StructureViewTreeElement {
        override fun getValue(): Any = valueObject
        override fun getChildren(): Array<TreeElement> = childElements.toTypedArray()
        override fun getPresentation(): ItemPresentation = FakePresentation(label)
        override fun navigate(requestFocus: Boolean) = Unit
        override fun canNavigate(): Boolean = false
        override fun canNavigateToSource(): Boolean = false
    }

    private class MutableFakeTreeElement(
        label: String,
        valueObject: Any
    ) : FakeTreeElement(label, valueObject) {
        var children: List<TreeElement> = emptyList()
        override fun getChildren(): Array<TreeElement> = children.toTypedArray()
    }

    private class FakePresentation(private val label: String) : ItemPresentation {
        override fun getPresentableText(): String = label
        override fun getLocationString(): String? = null
        override fun getIcon(unused: Boolean): Icon? = null
    }
}
