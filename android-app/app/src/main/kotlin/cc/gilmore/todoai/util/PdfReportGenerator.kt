package cc.gilmore.todoai.util

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import cc.gilmore.todoai.data.local.TodoEntity
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate

/**
 * Renders a "todos completed in this date range" report using Android's
 * built-in android.graphics.pdf.PdfDocument — deliberately not a
 * third-party PDF library, matching this project's minimal-dependency
 * stance elsewhere (see android-app/README.md's "Why manual DI instead of
 * Hilt"). Manual Canvas/Paint drawing and manual pagination; no layout
 * engine, no measuring beyond what [truncate] needs to avoid clipping a
 * long title off the page edge.
 *
 * Callers (ReportViewModel) are expected to have already filtered [todos]
 * to status DONE with a completedAt inside [from]..[to] and sorted them —
 * this just lays out whatever list it's given.
 */
object PdfReportGenerator {
    private const val PAGE_WIDTH = 595 // A4 @ 72dpi
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40f
    private const val LINE_HEIGHT = 18f
    private const val ENTRY_GAP = 24f

    fun generate(context: Context, from: LocalDate, to: LocalDate, todos: List<TodoEntity>): File {
        val document = PdfDocument()
        val titlePaint = Paint().apply { textSize = 20f; isFakeBoldText = true; color = Color.BLACK }
        val rangePaint = Paint().apply { textSize = 12f; color = Color.DKGRAY }
        val titleRowPaint = Paint().apply { textSize = 12f; color = Color.BLACK }
        val metaPaint = Paint().apply { textSize = 10f; color = Color.DKGRAY }
        val footerPaint = Paint().apply { textSize = 9f; color = Color.GRAY }

        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        var canvas = page.canvas
        var y = MARGIN

        fun finishCurrentPage() {
            canvas.drawText("Page $pageNumber", PAGE_WIDTH - MARGIN - 50f, PAGE_HEIGHT - 20f, footerPaint)
            document.finishPage(page)
        }

        fun startNewPage() {
            finishCurrentPage()
            pageNumber += 1
            page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            canvas = page.canvas
            y = MARGIN
        }

        canvas.drawText("Completed todos", MARGIN, y, titlePaint)
        y += LINE_HEIGHT + 6f
        canvas.drawText("${DateUtils.formatDueDate(from)} to ${DateUtils.formatDueDate(to)}", MARGIN, y, rangePaint)
        y += LINE_HEIGHT + 2f
        canvas.drawText(
            if (todos.size == 1) "1 todo" else "${todos.size} todos",
            MARGIN, y, rangePaint,
        )
        y += LINE_HEIGHT * 1.5f

        if (todos.isEmpty()) {
            canvas.drawText("No todos were completed in this range.", MARGIN, y, titleRowPaint)
        } else {
            val maxTitleWidth = PAGE_WIDTH - 2 * MARGIN
            todos.forEach { todo ->
                if (y > PAGE_HEIGHT - MARGIN - ENTRY_GAP) startNewPage()
                canvas.drawText(truncate(todo.title, titleRowPaint, maxTitleWidth), MARGIN, y, titleRowPaint)
                y += LINE_HEIGHT
                val meta = buildString {
                    append(DateUtils.displayCompletedAt(todo.completedAt))
                    if (!todo.project.isNullOrBlank()) append("   •   ${todo.project}")
                }
                canvas.drawText(truncate(meta, metaPaint, maxTitleWidth), MARGIN, y, metaPaint)
                y += ENTRY_GAP
            }
        }

        finishCurrentPage()

        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        // Overwrites any prior report for the same range rather than
        // accumulating a new file per generate() call — keeps the reports/
        // folder from growing unbounded across repeated use.
        val file = File(dir, "report-${DateUtils.formatDueDate(from)}-to-${DateUtils.formatDueDate(to)}.pdf")
        FileOutputStream(file).use { out -> document.writeTo(out) }
        document.close()
        return file
    }

    /** Appends "…" and trims until the text fits [maxWidth] at [paint]'s metrics — PdfDocument's Canvas does no wrapping or clipping of its own. */
    private fun truncate(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }
}
