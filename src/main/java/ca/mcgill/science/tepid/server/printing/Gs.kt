package ca.mcgill.science.tepid.server.printing

import org.apache.logging.log4j.kotlin.Logging
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.util.*

/**
 * Singleton holder to be used for production
 */
object Gs : GsContract by GsDelegate()

interface GsContract {
    /**
     * Given a postscript file, output the ink coverage for each page
     * Returns null if the process fails to launch
     * If the process does launch, then any output not matching our expected format
     * will be ignored
     */
    fun inkCoverage(f: File): List<InkCoverage>?

    /**
     * Given a postscript file, output for the entire file:
     * - total page count
     * - color page count
     */
    fun psInfo(f: File): PsData

    /**
     * Tests that the required GS devices are installed,
     * so processing will work and won't fail during first invocation
     */
    fun testRequiredDevicesInstalled()
}

/**
 * Underlying delegate that exposes methods for unit testing
 */
class GsDelegate : Logging, GsContract {
    private val gsBin = if (System.getProperty("os.name").startsWith("Windows"))
        "gswin64c.exe" else "gs"

    private fun run(vararg args: String): Process? {
        val pb = ProcessBuilder(listOf(gsBin, *args))
        return try {
            pb.start()
        } catch (e: IOException) {
            logger.error("Could not launch gs", e)
            null
        }
    }

    /*
    * The ink_cov device differs from the inkcov device
    * ink_cov tries to get a more accurate representation of the actual colors which will be used by the page.
    * it tries to deal with conversions from RGB space to CMYK space.
    * For example, it will try to crush all monochrome to K, rather than some CMY combination or a "rich black" MYK
    * It is also able to deal with pages with a small patch of color.
    * For example, a page might have a small color logo which is too small to count for more than 1% of 1% of the page (a square roughly 7mm on a side). With inkcov, there are not enough decimals printed for this to show up. But ink_cov will make the difference greater, and so more color pages will be detected as color
    * This is undocumented in GhostScript, but they have basically the same inputs
    */
    fun gs(f: File): List<String> {
        val gsProcess = run(
            "-sOutputFile=%stdout%",
            "-dBATCH", "-dNOPAUSE", "-dQUIET", "-q",
            "-sDEVICE=ink_cov", f.absolutePath
        )
            ?: throw Printer.PrintException("Internal Error processing postscript file at ${f.absolutePath}")
        return gsProcess.inputStream.bufferedReader().useLines { it.toList() }
    }

    /**
     * Matcher for the snippet relevant to ink coverage
     * Note that this may not necessarily match a full line, as not all outputs are separated by new lines
     */
    private val cmykRegex: Regex by lazy { Regex("(\\d+\\.\\d+)\\s+(\\d+\\.\\d+)\\s+(\\d+\\.\\d+)\\s+(\\d+\\.\\d+)\\s+CMYK OK") }

    /**
     * Expected input format:
     * 0.06841  0.41734  0.17687  0.04558 CMYK OK
     * See [cmykRegex] for matching regex
     *
     * The lines are joined and then all regex matches are extracted because of a bug in GhostScript
     * see for more details: https://bugs.ghostscript.com/show_bug.cgi?id=699342
     */
    fun inkCoverage(lines: List<String>): List<InkCoverage> = cmykRegex.findAll(lines.joinToString(" "))
        .map {
            val (_, c, y, m, k) = it.groupValues
            InkCoverage(c.toFloat(), y.toFloat(), m.toFloat(), k.toFloat())
        }.toList()

    override fun inkCoverage(f: File): List<InkCoverage> {
        return inkCoverage(gs(f))
    }

    /**
     * Returns true if a monochrome color model is specified
     */
    private fun BufferedReader.hasMonochromeColorModel(): Boolean {
        for (line in lines()) {
            if (line.contains(INDICATOR_MONOCHROME_V3) or line.contains(INDICATOR_MONOCHROME_V4))
                return true
            if (line.contains(INDICATOR_COLOR_V3) or line.contains(INDICATOR_COLOR_V4))
                return false
        }
        return false
    }

    override fun psInfo(f: File): PsData {
        val coverage = inkCoverage(f)
        var info = coverageToInfo(coverage)

        val br = BufferedReader(FileReader(f.absolutePath))
        val psMonochrome = br.hasMonochromeColorModel()
        if (psMonochrome) {
            info = info.copy(colorPages = 0)
        }

        return info
    }

    fun coverageToInfo(coverage: List<InkCoverage>): PsData {
        val pages = coverage.size
        val color = coverage.filter { !it.monochrome }.size
        return PsData(pages, color)
    }

    override fun testRequiredDevicesInstalled() {
        val p = run("-dNOPAUSE", "-dBATCH", "-dSAFER", "-sDEVICE=ink_cov", "-dQUIET", "-q")
            ?: throw GSException("Error running process") // TODO: propagate throw from run

        // https://stackoverflow.com/a/35446009/1947070
        val s = Scanner(p.inputStream).useDelimiter("\\A")
        val result = if (s.hasNext()) s.next() else ""
        if (result.contains("Unknown device")) {
            throw GSException("Could not invoke GS ink_cov device")
        }
    }

    companion object {
        private const val INDICATOR_COLOR_V3 = "/ProcessColorModel /DeviceCMYK"
        private const val INDICATOR_MONOCHROME_V3 = "/ProcessColorModel /DeviceGray"

        private const val INDICATOR_COLOR_V4 = "<color-effects-type syntax=\"keyword\">color</color-effects-type>"
        private const val INDICATOR_MONOCHROME_V4 =
            "<color-effects-type syntax=\"keyword\">monochrome-grayscale</color-effects-type>"
    }
}

/**
 * Holds the distribution of
 * cyan, magenta, yellow, and black in a given page
 * If the first three values are equal, then the page is monochrome
 */
data class InkCoverage(val c: Float, val m: Float, val y: Float, val k: Float) {
    val monochrome = c == m && c == y
}

/**
 * Holds info for ps files
 */
data class PsData(val pages: Int, val colorPages: Int) {
    val isColor: Boolean
        get() = this.colorPages != 0
}

class GSException : RuntimeException {
    constructor()
    constructor(msg: String?) : super(msg)
    constructor(parent: Throwable?) : super(parent)
    constructor(msg: String?, parent: Throwable?) : super(msg, parent)

    companion object {
        private const val serialVersionUID = -2212386613903764979L
    }
}