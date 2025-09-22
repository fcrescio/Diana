package li.crescio.penates.diana.notes

/**
 * Represents a rich document for a group of thoughts. The markdown body stores the
 * full rendered text while the outline contains the hierarchical headings that can
 * be used to render navigation controls in the UI.
 */
data class ThoughtDocument(
    val markdownBody: String,
    val outline: ThoughtOutline = ThoughtOutline.EMPTY,
)

/** Outline metadata extracted from a [ThoughtDocument]'s markdown body. */
data class ThoughtOutline(
    val sections: List<ThoughtOutlineSection>,
) {
    companion object {
        val EMPTY = ThoughtOutline(emptyList())
    }
}

/** A heading within the markdown document. */
data class ThoughtOutlineSection(
    val title: String,
    val level: Int,
    val anchor: String,
    val children: List<ThoughtOutlineSection> = emptyList(),
)
