package app.frame.camera.studio

class Template(val id: String, val name: String, val category: Int, val build: () -> Collage)

/** The built-in template library. Every template is real geometry that the editor can modify further. */
object Templates {
    val categories = listOf("Classic", "Asymmetric", "Artistic", "Social")
    private val list = ArrayList<Template>()
    val all: List<Template> get() = list
    fun byId(id: String): Template? = list.firstOrNull { it.id == id }
    fun inCategory(c: Int): List<Template> = list.filter { it.category == c }

    private const val CLASSIC = 0
    private const val ASYM = 1
    private const val ART = 2
    private const val SOCIAL = 3

    private const val INK = 0xFF121315.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val CREAM = 0xFFEFE8D8.toInt()
    private const val KRAFT = 0xFFC8B186.toInt()

    private fun t(id: String, name: String, cat: Int, build: () -> Collage) { list.add(Template(id, name, cat, build)) }

    private fun base(id: String, name: String, aspect: Float, bg: Int = INK, gap: Float = 0.012f): Collage =
        Collage(id, name, aspect).also { it.bg1 = bg; it.bg2 = bg; it.gap = gap }

    /** Rows of frames: each row has a relative height and a list of relative widths. */
    private fun rows(id: String, name: String, aspect: Float, spec: List<Pair<Float, List<Float>>>, gap: Float = 0.012f): Collage {
        val c = base(id, name, aspect, gap = gap)
        val th = spec.sumOf { it.first.toDouble() }.toFloat()
        var y = 0f
        for ((h, ws) in spec) {
            val tw = ws.sum(); var x = 0f
            for (w in ws) { c.add(Geo.rect(x / tw, y / th, (x + w) / tw, (y + h) / th)); x += w }
            y += h
        }
        return c
    }

    private fun grid(id: String, name: String, aspect: Float, r: Int, cl: Int, gap: Float = 0.012f) =
        rows(id, name, aspect, List(r) { Pair(1f, List(cl) { 1f }) }, gap)

    private fun rr(c: Collage, cx: Float, cy: Float, w: Float, h: Float, deg: Float): FloatArray =
        Geo.rotate(Geo.rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), deg, cx, cy, c.aspect)

    /** A polaroid card (decoration under the picture) with a rotated photo frame on top. w is a fraction of canvas width. */
    private fun polaroid(c: Collage, cx: Float, cy: Float, w: Float, deg: Float, shadow: Boolean = true) {
        val a = c.aspect
        val wp = w * a; val hp = wp * 1.2f            // card size in height units
        val side = wp * 0.86f
        val ly = -hp / 2 + wp * 0.07f + side / 2       // photo centre in card space
        val r = Math.toRadians(deg.toDouble()); val cs = Math.cos(r).toFloat(); val sn = Math.sin(r).toFloat()
        val px = cx + (0 * cs - ly * sn) / a; val py = cy + 0 * sn + ly * cs
        c.decos.add(Deco(D_RECT, cx, cy, w, hp, deg, WHITE, below = true, shadow = shadow, round = 0.01f))
        c.add(rr(c, px, py, side / a, side, deg))
    }

    private fun confetti(c: Collage, n: Int, seed: Int, colors: IntArray, avoid: List<FloatArray> = emptyList()) {
        var s = seed
        fun rnd(): Float { s = (s * 1103515245 + 12345) and 0x7fffffff; return (s % 10000) / 10000f }
        var made = 0; var guard = 0
        while (made < n && guard < n * 20) {
            guard++
            val x = 0.04f + rnd() * 0.92f; val y = 0.03f + rnd() * 0.94f
            if (avoid.any { x > it[0] - 0.02f && x < it[2] + 0.02f && y > it[1] - 0.02f && y < it[3] + 0.02f }) continue
            val kind = when ((rnd() * 4).toInt()) { 0 -> ST_STAR; 1 -> ST_SPARKLE; 2 -> ST_DOT; else -> ST_FLAG }
            c.decos.add(Deco(D_STICKER, x, y, 0.04f + rnd() * 0.04f, 0f, rnd() * 360f, colors[(rnd() * colors.size).toInt() % colors.size], sticker = kind))
            made++
        }
    }

    private fun bounds(c: Collage): List<FloatArray> = c.frames.map { it.bbox() }

    init {
        // ---------------------------------------------------------------- classic grids
        t("c_1x2", "1 x 2", CLASSIC) { grid("c_1x2", "1 x 2", 1.5f, 1, 2) }
        t("c_2x1", "2 x 1", CLASSIC) { grid("c_2x1", "2 x 1", 0.67f, 2, 1) }
        t("c_1x3", "1 x 3", CLASSIC) { grid("c_1x3", "1 x 3", 1.8f, 1, 3) }
        t("c_3x1", "3 x 1", CLASSIC) { grid("c_3x1", "3 x 1", 0.56f, 3, 1) }
        t("c_2x2", "2 x 2", CLASSIC) { grid("c_2x2", "2 x 2", 1f, 2, 2) }
        t("c_3x2", "3 x 2", CLASSIC) { grid("c_3x2", "3 x 2", 0.67f, 3, 2) }
        t("c_2x3", "2 x 3", CLASSIC) { grid("c_2x3", "2 x 3", 1.5f, 2, 3) }
        t("c_3x3", "3 x 3", CLASSIC) { grid("c_3x3", "3 x 3", 1f, 3, 3, 0.01f) }
        t("c_4x4", "4 x 4", CLASSIC) { grid("c_4x4", "4 x 4", 1f, 4, 4, 0.008f) }
        t("c_2x4", "2 x 4", CLASSIC) { grid("c_2x4", "2 x 4", 2f, 2, 4, 0.01f) }
        t("c_ucols", "Wide and narrow", CLASSIC) { rows("c_ucols", "Wide and narrow", 1.5f, listOf(Pair(1f, listOf(1f, 2f)))) }
        t("c_urows", "Tall middle", CLASSIC) { rows("c_urows", "Tall middle", 0.75f, listOf(Pair(1f, listOf(1f)), Pair(2f, listOf(1f)), Pair(1f, listOf(1f)))) }
        t("c_umix", "Offset blocks", CLASSIC) { rows("c_umix", "Offset blocks", 0.8f, listOf(Pair(2f, listOf(1f, 2f)), Pair(3f, listOf(2f, 1f)))) }
        t("c_mix3", "Stepped", CLASSIC) { rows("c_mix3", "Stepped", 0.8f, listOf(Pair(1.2f, listOf(1f)), Pair(1f, listOf(1f, 1f)), Pair(1f, listOf(1f, 2f, 1f)))) }
        t("c_blank", "Single frame", CLASSIC) { grid("c_blank", "Single frame", 0.8f, 1, 1, 0f) }

        // ---------------------------------------------------------------- asymmetric
        t("a_1_2", "One large, two small", ASYM) {
            val c = base("a_1_2", "One large, two small", 0.8f)
            c.add(Geo.rect(0f, 0f, 0.62f, 1f)); c.add(Geo.rect(0.62f, 0f, 1f, 0.5f)); c.add(Geo.rect(0.62f, 0.5f, 1f, 1f)); c
        }
        t("a_1_3", "One large, three small", ASYM) {
            val c = base("a_1_3", "One large, three small", 0.8f)
            c.add(Geo.rect(0f, 0f, 1f, 0.66f))
            for (i in 0..2) c.add(Geo.rect(i / 3f, 0.66f, (i + 1) / 3f, 1f)); c
        }
        t("a_1_3s", "Hero with side stack", ASYM) {
            val c = base("a_1_3s", "Hero with side stack", 1.2f)
            c.add(Geo.rect(0f, 0f, 0.64f, 1f))
            for (i in 0..2) c.add(Geo.rect(0.64f, i / 3f, 1f, (i + 1) / 3f)); c
        }
        t("a_ring", "Centre and surround", ASYM) {
            val c = base("a_ring", "Centre and surround", 1f, gap = 0.01f)
            for (r in 0..3) for (k in 0..3) if (!(r in 1..2 && k in 1..2)) c.add(Geo.rect(k / 4f, r / 4f, (k + 1) / 4f, (r + 1) / 4f))
            c.add(Geo.rect(0.25f, 0.25f, 0.75f, 0.75f)); c
        }
        t("a_cross", "Cross", ASYM) {
            val c = base("a_cross", "Cross", 1f)
            c.add(Geo.rect(0.2f, 0.2f, 0.8f, 0.8f)); c.add(Geo.rect(0.2f, 0f, 0.8f, 0.2f)); c.add(Geo.rect(0.2f, 0.8f, 0.8f, 1f))
            c.add(Geo.rect(0f, 0.2f, 0.2f, 0.8f)); c.add(Geo.rect(0.8f, 0.2f, 1f, 0.8f)); c
        }
        t("a_diag2", "Diagonal split", ASYM) {
            val c = base("a_diag2", "Diagonal split", 0.8f, gap = 0.016f)
            c.add(floatArrayOf(0f, 0f, 0.68f, 0f, 0.32f, 1f, 0f, 1f)); c.add(floatArrayOf(0.68f, 0f, 1f, 0f, 1f, 1f, 0.32f, 1f)); c
        }
        t("a_diag3", "Diagonal bands", ASYM) {
            val c = base("a_diag3", "Diagonal bands", 0.8f, gap = 0.016f)
            c.add(floatArrayOf(0f, 0f, 0.5f, 0f, 0.2f, 1f, 0f, 1f))
            c.add(floatArrayOf(0.5f, 0f, 0.9f, 0f, 0.6f, 1f, 0.2f, 1f))
            c.add(floatArrayOf(0.9f, 0f, 1f, 0f, 1f, 1f, 0.6f, 1f)); c
        }
        t("a_diagc", "Corner cut", ASYM) {
            val c = base("a_diagc", "Corner cut", 1f, gap = 0.016f)
            c.add(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)); c.add(floatArrayOf(1f, 0f, 1f, 1f, 0f, 1f)); c
        }
        t("a_mixed", "Vertical and horizontal", ASYM) {
            val c = base("a_mixed", "Vertical and horizontal", 1f)
            c.add(Geo.rect(0f, 0f, 0.5f, 0.5f)); c.add(Geo.rect(0f, 0.5f, 0.5f, 1f))
            c.add(Geo.rect(0.5f, 0f, 1f, 0.58f)); c.add(Geo.rect(0.5f, 0.58f, 0.75f, 1f)); c.add(Geo.rect(0.75f, 0.58f, 1f, 1f)); c
        }
        t("a_over3", "Overlapping trio", ASYM) {
            val c = base("a_over3", "Overlapping trio", 0.8f, gap = 0f); c.shadow = true; c.borderW = 0.012f
            c.add(rr(c, 0.38f, 0.32f, 0.56f, 0.4f, -6f)); c.add(rr(c, 0.62f, 0.55f, 0.56f, 0.4f, 5f)); c.add(rr(c, 0.4f, 0.78f, 0.56f, 0.34f, -3f)); c
        }
        t("a_fan", "Fan of five", ASYM) {
            val c = base("a_fan", "Fan of five", 0.8f, gap = 0f); c.shadow = true; c.borderW = 0.012f
            val ang = floatArrayOf(-24f, -12f, 0f, 12f, 24f)
            for (i in 0..4) { val a = ang[i]; val cx = 0.5f + a * 0.007f; c.add(rr(c, cx, 0.52f + Math.abs(a) * 0.002f, 0.4f, 0.56f, a)) }; c
        }
        t("a_mag1", "Magazine cover", ASYM) {
            val c = base("a_mag1", "Magazine cover", 0.75f, WHITE, 0.012f)
            c.add(Geo.rect(0f, 0.13f, 1f, 0.62f)); c.add(Geo.rect(0f, 0.62f, 0.34f, 1f)); c.add(Geo.rect(0.34f, 0.62f, 0.67f, 1f)); c.add(Geo.rect(0.67f, 0.62f, 1f, 1f))
            c.decos.add(Deco(D_TEXT, 0.5f, 0.065f, 0f, 0f, 0f, INK, "MAGAZINE", 0.075f, serif = true)); c
        }
        t("a_mag2", "Editorial columns", ASYM) {
            val c = base("a_mag2", "Editorial columns", 0.75f, CREAM, 0.014f)
            c.add(Geo.rect(0f, 0f, 0.46f, 1f)); c.add(Geo.rect(0.46f, 0f, 1f, 0.38f)); c.add(Geo.rect(0.46f, 0.38f, 1f, 0.58f)); c.add(Geo.rect(0.46f, 0.58f, 1f, 1f)); c
        }
        t("a_pip", "Picture in picture", ASYM) {
            val c = base("a_pip", "Picture in picture", 0.75f, gap = 0f)
            c.add(Geo.rect(0f, 0f, 1f, 1f)); val s = c.add(Geo.rect(0.56f, 0.64f, 0.94f, 0.94f)); c.borderW = 0.012f; c
        }
        t("a_pip2", "Two insets", ASYM) {
            val c = base("a_pip2", "Two insets", 0.75f, gap = 0f)
            c.add(Geo.rect(0f, 0f, 1f, 1f)); c.add(Geo.rect(0.58f, 0.05f, 0.95f, 0.27f)); c.add(Geo.rect(0.58f, 0.3f, 0.95f, 0.52f)); c.borderW = 0.012f; c
        }
        t("a_pipc", "Round inset", ASYM) {
            val c = base("a_pipc", "Round inset", 0.75f, gap = 0f)
            c.add(Geo.rect(0f, 0f, 1f, 1f)); c.add(Geo.rect(0.58f, 0.62f, 0.94f, 0.62f + 0.36f * 0.75f), SHAPE_ELLIPSE); c.borderW = 0.014f; c
        }

        // ---------------------------------------------------------------- artistic
        t("r_film_v", "Film strip", ART) {
            val c = base("r_film_v", "Film strip", 0.42f, 0xFF0C0C0D.toInt(), 0.012f)
            for (i in 0..3) c.add(Geo.rect(0.17f, 0.03f + i * 0.2375f, 0.83f, 0.03f + (i + 1) * 0.2375f - 0.012f))
            for (i in 0..15) { val y = 0.025f + i * 0.0605f
                c.decos.add(Deco(D_RECT, 0.075f, y + 0.02f, 0.06f, 0.03f, 0f, 0xFFEFEAD9.toInt(), below = true, round = 0.25f))
                c.decos.add(Deco(D_RECT, 0.925f, y + 0.02f, 0.06f, 0.03f, 0f, 0xFFEFEAD9.toInt(), below = true, round = 0.25f)) }
            c
        }
        t("r_film_h", "Film reel", ART) {
            val c = base("r_film_h", "Film reel", 1.78f, 0xFF0C0C0D.toInt(), 0.01f)
            for (i in 0..2) c.add(Geo.rect(0.03f + i * 0.3233f, 0.17f, 0.03f + (i + 1) * 0.3233f - 0.01f, 0.83f))
            for (i in 0..19) { val x = 0.02f + i * 0.0492f
                c.decos.add(Deco(D_RECT, x + 0.015f, 0.075f, 0.026f, 0.06f, 0f, 0xFFEFEAD9.toInt(), below = true, round = 0.25f))
                c.decos.add(Deco(D_RECT, x + 0.015f, 0.925f, 0.026f, 0.06f, 0f, 0xFFEFEAD9.toInt(), below = true, round = 0.25f)) }
            c
        }
        t("r_booth4", "Photo booth", ART) {
            val c = base("r_booth4", "Photo booth", 0.36f, 0xFFEDE9E0.toInt(), 0.016f)
            for (i in 0..3) c.add(Geo.rect(0.1f, 0.04f + i * 0.2125f, 0.9f, 0.04f + (i + 1) * 0.2125f - 0.016f))
            c.decos.add(Deco(D_TEXT, 0.5f, 0.935f, 0f, 0f, 0f, INK, "PHOTO BOOTH", 0.06f)); c
        }
        t("r_booth3", "Booth trio", ART) {
            val c = base("r_booth3", "Booth trio", 0.4f, WHITE, 0.016f)
            for (i in 0..2) c.add(Geo.rect(0.1f, 0.035f + i * 0.28f, 0.9f, 0.035f + (i + 1) * 0.28f - 0.016f))
            c.decos.add(Deco(D_TEXT, 0.5f, 0.935f, 0f, 0f, 0f, INK, "SMILE", 0.1f)); c
        }
        t("r_pol3", "Polaroid trio", ART) {
            val c = base("r_pol3", "Polaroid trio", 0.8f, CREAM)
            polaroid(c, 0.34f, 0.27f, 0.48f, -7f); polaroid(c, 0.66f, 0.52f, 0.48f, 6f); polaroid(c, 0.36f, 0.77f, 0.48f, -4f); c
        }
        t("r_pol4", "Polaroid grid", ART) {
            val c = base("r_pol4", "Polaroid grid", 1f, 0xFFDDE3E8.toInt())
            polaroid(c, 0.26f, 0.27f, 0.38f, -4f); polaroid(c, 0.74f, 0.27f, 0.38f, 3f); polaroid(c, 0.26f, 0.73f, 0.38f, 3f); polaroid(c, 0.74f, 0.73f, 0.38f, -3f); c
        }
        t("r_scrap", "Scrapbook", ART) {
            val c = base("r_scrap", "Scrapbook", 0.8f, KRAFT)
            polaroid(c, 0.3f, 0.26f, 0.44f, -8f); polaroid(c, 0.7f, 0.33f, 0.4f, 7f); polaroid(c, 0.38f, 0.72f, 0.42f, 4f)
            c.decos.add(Deco(D_STICKER, 0.3f, 0.08f, 0.14f, 0f, -10f, 0xCCF2E8A8.toInt(), sticker = ST_TAPE))
            c.decos.add(Deco(D_STICKER, 0.7f, 0.15f, 0.14f, 0f, 12f, 0xCCF2E8A8.toInt(), sticker = ST_TAPE))
            c.decos.add(Deco(D_STICKER, 0.78f, 0.78f, 0.12f, 0f, 14f, 0xFFD94F4F.toInt(), sticker = ST_HEART))
            c.decos.add(Deco(D_TEXT, 0.76f, 0.66f, 0f, 0f, -6f, 0xFF3B2F20.toInt(), "memories", 0.07f, bold = false, serif = true)); c
        }
        t("r_news", "Newspaper", ART) {
            val c = base("r_news", "Newspaper", 0.75f, 0xFFEDE6D2.toInt(), 0.012f)
            c.add(Geo.rect(0.04f, 0.2f, 0.66f, 0.62f)); c.add(Geo.rect(0.68f, 0.2f, 0.96f, 0.41f)); c.add(Geo.rect(0.68f, 0.43f, 0.96f, 0.62f))
            c.add(Geo.rect(0.04f, 0.72f, 0.5f, 0.96f)); c.add(Geo.rect(0.52f, 0.72f, 0.96f, 0.96f))
            c.decos.add(Deco(D_TEXT, 0.5f, 0.085f, 0f, 0f, 0f, 0xFF1D1B17.toInt(), "THE DAILY FRAME", 0.075f, serif = true))
            c.decos.add(Deco(D_RECT, 0.5f, 0.15f, 0.92f, 0.004f, 0f, 0xFF1D1B17.toInt(), below = true))
            c.decos.add(Deco(D_TEXT, 0.5f, 0.675f, 0f, 0f, 0f, 0xFF1D1B17.toInt(), "Front page story", 0.04f, bold = false, serif = true))
            c.decos.add(Deco(D_RECT, 0.5f, 0.69f, 0.92f, 0.003f, 0f, 0xFF1D1B17.toInt(), below = true)); c
        }
        t("r_glass", "Broken glass", ART) {
            val c = base("r_glass", "Broken glass", 0.8f, gap = 0.006f)
            val cx = 0.46f; val cy = 0.42f
            val angs = floatArrayOf(10f, 62f, 118f, 170f, 214f, 262f, 318f)
            var piece = Geo.rect(0f, 0f, 1f, 1f)
            val aspect = c.aspect
            fun dirN(deg: Float): FloatArray {
                val r = Math.toRadians(deg.toDouble()); return floatArrayOf(Math.cos(r).toFloat(), Math.sin(r).toFloat())
            }
            for (i in angs.indices) {
                val a0 = angs[i]; val a1 = angs[(i + 1) % angs.size].let { if (it <= a0) it + 360f else it }
                val d0 = dirN(a0); val d1 = dirN(a1)
                // wedge between ray a0 and ray a1 (pixel space -> normalised): keep side of each ray
                var w = Geo.rect(0f, 0f, 1f, 1f)
                // normal of ray 0 pointing towards a1 side: n = (-dy, dx) in pixel space
                w = Geo.clip(w, -d0[1] * aspect * 1f, d0[0], -d0[1] * aspect * cx + d0[0] * cy, true)
                w = Geo.clip(w, -d1[1] * aspect * 1f, d1[0], -d1[1] * aspect * cx + d1[0] * cy, false)
                if (w.size < 6) continue
                // keep only the wedge on the forward side of the centre (away from the opposite wedge)
                val mid = dirN((a0 + a1) / 2f)
                w = Geo.clip(w, mid[0] * aspect, mid[1], mid[0] * aspect * cx + mid[1] * cy, true)
                if (w.size < 6) continue
                val cut = Geo.split(w, mid[0] * aspect, mid[1], mid[0] * aspect * cx + mid[1] * cy + 0.2f)
                if (cut != null) { c.add(cut.second); c.add(cut.first) } else c.add(w)
            }
            c
        }
        t("r_diam", "Diamonds", ART) {
            val c = base("r_diam", "Diamonds", 1f, gap = 0.014f)
            for (i in 0..2) for (j in 0..2) {
                val cx = i * 0.5f; val cy = j * 0.5f
                val d = Geo.clipRect(floatArrayOf(cx, cy - 0.5f, cx + 0.5f, cy, cx, cy + 0.5f, cx - 0.5f, cy))
                if (d.size >= 6 && Geo.area(d) > 0.01f) c.add(d)
            }
            c
        }
        t("r_tri_x", "Triangle X", ART) {
            val c = base("r_tri_x", "Triangle X", 1f, gap = 0.014f)
            c.add(floatArrayOf(0f, 0f, 1f, 0f, 0.5f, 0.5f)); c.add(floatArrayOf(1f, 0f, 1f, 1f, 0.5f, 0.5f))
            c.add(floatArrayOf(1f, 1f, 0f, 1f, 0.5f, 0.5f)); c.add(floatArrayOf(0f, 1f, 0f, 0f, 0.5f, 0.5f)); c
        }
        t("r_zig", "Triangle strip", ART) {
            val c = base("r_zig", "Triangle strip", 1.6f, gap = 0.012f)
            val n = 6; val w = 1f / (n + 1) * 2f
            for (i in 0 until n) {
                val x0 = i * (w / 2f)
                c.add(if (i % 2 == 0) floatArrayOf(x0, 1f, x0 + w / 2f, 0f, x0 + w, 1f) else floatArrayOf(x0, 0f, x0 + w, 0f, x0 + w / 2f, 1f))
            }
            c
        }
        t("r_circles", "Circles", ART) {
            val c = base("r_circles", "Circles", 0.8f, CREAM)
            c.add(Geo.rect(0.14f, 0.05f, 0.86f, 0.05f + 0.72f * 0.8f), SHAPE_ELLIPSE)
            c.add(Geo.rect(0.05f, 0.64f, 0.5f, 0.64f + 0.45f * 0.8f), SHAPE_ELLIPSE)
            c.add(Geo.rect(0.56f, 0.68f, 0.92f, 0.68f + 0.36f * 0.8f), SHAPE_ELLIPSE); c
        }
        t("r_ovals", "Ovals", ART) {
            val c = base("r_ovals", "Ovals", 0.8f, 0xFFE4DDD0.toInt())
            c.add(Geo.rect(0.1f, 0.05f, 0.9f, 0.47f), SHAPE_ELLIPSE); c.add(Geo.rect(0.1f, 0.52f, 0.9f, 0.95f), SHAPE_ELLIPSE); c
        }
        t("r_dots", "Round grid", ART) {
            val c = base("r_dots", "Round grid", 1f, 0xFF1C1E22.toInt(), 0.02f)
            for (r in 0..1) for (k in 0..1) c.add(Geo.rect(k / 2f, r / 2f, (k + 1) / 2f, (r + 1) / 2f), SHAPE_ELLIPSE); c
        }
        t("r_heart1", "Heart", ART) {
            val c = base("r_heart1", "Heart", 0.8f, 0xFFF6D9DE.toInt())
            c.add(Geo.rect(0.08f, 0.14f, 0.92f, 0.14f + 0.84f * 0.8f * 1.0f), SHAPE_HEART)
            c.decos.add(Deco(D_TEXT, 0.5f, 0.92f, 0f, 0f, 0f, 0xFFB0344A.toInt(), "with love", 0.06f, bold = false, serif = true)); c
        }
        t("r_heart2", "Two hearts", ART) {
            val c = base("r_heart2", "Two hearts", 1f, 0xFFF6D9DE.toInt())
            c.add(Geo.rect(0.04f, 0.2f, 0.52f, 0.76f), SHAPE_HEART); c.add(Geo.rect(0.48f, 0.24f, 0.96f, 0.8f), SHAPE_HEART); c
        }
        t("r_free", "Freeform", ART) {
            val c = base("r_free", "Freeform", 0.8f, gap = 0.012f)
            val vx = arrayOf(floatArrayOf(0f, 0.30f, 0.62f, 1f), floatArrayOf(0f, 0.36f, 0.58f, 1f), floatArrayOf(0f, 0.27f, 0.66f, 1f), floatArrayOf(0f, 0.33f, 0.6f, 1f))
            val vy = arrayOf(floatArrayOf(0f, 0.04f, 0f, 0f), floatArrayOf(0.30f, 0.24f, 0.33f, 0.28f), floatArrayOf(0.64f, 0.70f, 0.60f, 0.68f), floatArrayOf(1f, 1f, 1f, 1f))
            for (r in 0..2) for (k in 0..2) {
                c.add(floatArrayOf(vx[r][k], vy[r][k], vx[r][k + 1], vy[r][k + 1], vx[r + 1][k + 1], vy[r + 1][k + 1], vx[r + 1][k], vy[r + 1][k]))
            }
            c
        }

        // ---------------------------------------------------------------- social
        t("s_story3", "Story, three stack", SOCIAL) {
            val c = base("s_story3", "Story, three stack", 0.5625f, gap = 0.02f); c.outer = 0.03f; c.radius = 0.03f
            for (i in 0..2) c.add(Geo.rect(0f, i / 3f, 1f, (i + 1) / 3f)); c
        }
        t("s_story2", "Story with caption", SOCIAL) {
            val c = base("s_story2", "Story with caption", 0.5625f, 0xFFF3EEE4.toInt(), 0.02f); c.radius = 0.03f
            c.add(Geo.rect(0.05f, 0.05f, 0.95f, 0.52f)); c.add(Geo.rect(0.05f, 0.54f, 0.95f, 0.88f))
            c.decos.add(Deco(D_TEXT, 0.5f, 0.94f, 0f, 0f, 0f, INK, "TODAY", 0.07f)); c
        }
        t("s_square", "Square post", SOCIAL) {
            val c = grid("s_square", "Square post", 1f, 2, 2, 0.02f); c.outer = 0.03f; c.radius = 0.025f; c
        }
        t("s_hero", "Square with strip", SOCIAL) {
            val c = base("s_hero", "Square with strip", 1f, 0xFFF3EEE4.toInt(), 0.016f); c.outer = 0.03f; c.radius = 0.02f
            c.add(Geo.rect(0f, 0f, 1f, 0.68f)); for (i in 0..2) c.add(Geo.rect(i / 3f, 0.68f, (i + 1) / 3f, 1f)); c
        }
        t("s_45", "Portrait 4:5", SOCIAL) {
            val c = base("s_45", "Portrait 4:5", 0.8f, 0xFF1A1B1F.toInt(), 0.016f); c.outer = 0.025f; c.radius = 0.02f
            c.add(Geo.rect(0f, 0f, 1f, 0.6f)); c.add(Geo.rect(0f, 0.6f, 0.5f, 1f)); c.add(Geo.rect(0.5f, 0.6f, 1f, 1f)); c
        }
        t("s_reels", "Reels cover", SOCIAL) {
            val c = base("s_reels", "Reels cover", 0.5625f, 0xFF15161A.toInt())
            c.add(Geo.rect(0.1f, 0.26f, 0.9f, 0.26f + 0.8f * 0.5625f * 1.0f), SHAPE_ELLIPSE)
            c.decos.add(Deco(D_TEXT, 0.5f, 0.14f, 0f, 0f, 0f, 0xFFFFFFFF.toInt(), "YOUR TITLE", 0.09f))
            c.decos.add(Deco(D_TEXT, 0.5f, 0.88f, 0f, 0f, 0f, 0xFFEDA94C.toInt(), "part one", 0.06f, bold = false)); c
        }
        t("s_pin", "Pinterest columns", SOCIAL) {
            val c = base("s_pin", "Pinterest columns", 0.7f, 0xFFF2EFEA.toInt(), 0.016f); c.radius = 0.02f; c.outer = 0.02f
            val a = floatArrayOf(0f, 0.36f, 0.68f, 1f); val b = floatArrayOf(0f, 0.22f, 0.62f, 1f)
            for (i in 0..2) c.add(Geo.rect(0f, a[i], 0.5f, a[i + 1]))
            for (i in 0..2) c.add(Geo.rect(0.5f, b[i], 1f, b[i + 1])); c
        }
        t("s_pin3", "Pinterest three", SOCIAL) {
            val c = base("s_pin3", "Pinterest three", 0.75f, 0xFFF2EFEA.toInt(), 0.016f); c.radius = 0.02f; c.outer = 0.02f
            val cols = arrayOf(floatArrayOf(0f, 0.45f, 1f), floatArrayOf(0f, 0.3f, 0.65f, 1f), floatArrayOf(0f, 0.55f, 1f))
            for (k in 0..2) for (i in 0 until cols[k].size - 1) c.add(Geo.rect(k / 3f, cols[k][i], (k + 1) / 3f, cols[k][i + 1])); c
        }
        t("s_travel", "Travel diary", SOCIAL) {
            val c = base("s_travel", "Travel diary", 0.8f, 0xFFF1E7D3.toInt())
            polaroid(c, 0.34f, 0.3f, 0.46f, -6f); polaroid(c, 0.68f, 0.5f, 0.42f, 5f)
            c.decos.add(Deco(D_TEXT, 0.5f, 0.07f, 0f, 0f, 0f, 0xFF3B2F20.toInt(), "Travel diary", 0.08f, bold = false, serif = true))
            c.decos.add(Deco(D_TEXT, 0.3f, 0.86f, 0f, 0f, -3f, 0xFF3B2F20.toInt(), "Day 1", 0.06f, bold = false, serif = true))
            c.decos.add(Deco(D_STICKER, 0.82f, 0.86f, 0.12f, 0f, 10f, 0xFFD94F4F.toInt(), sticker = ST_STAR))
            c.decos.add(Deco(D_STICKER, 0.34f, 0.07f, 0.12f, 0f, -8f, 0xCCF2E8A8.toInt(), sticker = ST_TAPE).also { it.y = 0.14f }); c
        }
        t("s_birthday", "Birthday", SOCIAL) {
            val c = base("s_birthday", "Birthday", 0.8f, 0xFFFF9A8B.toInt(), 0.014f)
            c.bg2 = 0xFFFFD1A8.toInt(); c.gradient = true; c.bgAngle = 90f
            c.add(Geo.rect(0.2f, 0.22f, 0.8f, 0.22f + 0.6f * 0.8f), SHAPE_ELLIPSE)
            c.add(Geo.rect(0.06f, 0.66f, 0.46f, 0.66f + 0.4f * 0.8f * 0.9f), SHAPE_ELLIPSE)
            c.add(Geo.rect(0.54f, 0.66f, 0.94f, 0.66f + 0.4f * 0.8f * 0.9f), SHAPE_ELLIPSE)
            c.decos.add(Deco(D_TEXT, 0.5f, 0.11f, 0f, 0f, 0f, WHITE, "Happy Birthday", 0.085f))
            confetti(c, 22, 7, intArrayOf(WHITE, 0xFFFFE066.toInt(), 0xFFFF5C8A.toInt(), 0xFF5CC8FF.toInt()), bounds(c)); c
        }
        t("s_couple", "Couple", SOCIAL) {
            val c = base("s_couple", "Couple", 1f, 0xFFF6D9DE.toInt())
            c.add(Geo.rect(0.04f, 0.18f, 0.52f, 0.74f), SHAPE_HEART); c.add(Geo.rect(0.48f, 0.26f, 0.96f, 0.82f), SHAPE_HEART)
            c.decos.add(Deco(D_TEXT, 0.5f, 0.92f, 0f, 0f, 0f, 0xFFB0344A.toInt(), "us", 0.09f, bold = false, serif = true)); c
        }
        t("s_couple2", "Together", SOCIAL) {
            val c = base("s_couple2", "Together", 0.8f, 0xFFFBF3F0.toInt(), 0.02f)
            c.add(floatArrayOf(0f, 0f, 0.6f, 0f, 0.4f, 1f, 0f, 1f)); c.add(floatArrayOf(0.6f, 0f, 1f, 0f, 1f, 1f, 0.4f, 1f))
            c.decos.add(Deco(D_STICKER, 0.5f, 0.5f, 0.14f, 0f, 0f, 0xFFE0405C.toInt(), sticker = ST_HEART)); c
        }
        t("s_friends", "Friendship", SOCIAL) {
            val c = base("s_friends", "Friendship", 0.8f, 0xFFFFE9A8.toInt())
            c.add(Geo.rect(0.05f, 0.18f, 0.5f, 0.18f + 0.45f * 0.8f), SHAPE_ELLIPSE)
            c.add(Geo.rect(0.5f, 0.18f, 0.95f, 0.18f + 0.45f * 0.8f), SHAPE_ELLIPSE)
            c.add(Geo.rect(0.275f, 0.5f, 0.725f, 0.5f + 0.45f * 0.8f), SHAPE_ELLIPSE)
            c.decos.add(Deco(D_TEXT, 0.5f, 0.08f, 0f, 0f, 0f, 0xFF3A2A12.toInt(), "best friends", 0.075f, bold = false, serif = true)); c
        }
        t("s_fashion", "Fashion editorial", SOCIAL) {
            val c = base("s_fashion", "Fashion editorial", 0.75f, WHITE, 0.012f)
            c.add(Geo.rect(0.04f, 0.2f, 0.4f, 0.96f)); c.add(Geo.rect(0.42f, 0.2f, 0.72f, 0.74f)); c.add(Geo.rect(0.74f, 0.2f, 0.96f, 0.96f))
            c.decos.add(Deco(D_TEXT, 0.5f, 0.1f, 0f, 0f, 0f, INK, "EDIT", 0.11f, serif = true))
            c.decos.add(Deco(D_TEXT, 0.57f, 0.85f, 0f, 0f, 0f, INK, "SS", 0.05f, bold = false))
            c.decos.add(Deco(D_RECT, 0.5f, 0.17f, 0.92f, 0.003f, 0f, INK, below = true)); c
        }
        t("s_food", "Food hero", SOCIAL) {
            val c = base("s_food", "Food hero", 1f, 0xFFF4EDE1.toInt())
            c.add(Geo.rect(0.1f, 0.06f, 0.78f, 0.74f), SHAPE_ELLIPSE)
            c.add(Geo.rect(0.64f, 0.56f, 0.96f, 0.88f), SHAPE_ELLIPSE)
            c.decos.add(Deco(D_TEXT, 0.3f, 0.88f, 0f, 0f, 0f, 0xFF3B2F20.toInt(), "Fresh today", 0.075f, bold = false, serif = true)); c
        }
        t("s_menu", "Food menu", SOCIAL) {
            val c = base("s_menu", "Food menu", 0.8f, 0xFF1F2A20.toInt(), 0.016f); c.radius = 0.02f; c.outer = 0.04f
            c.add(Geo.rect(0f, 0.16f, 1f, 0.58f)); c.add(Geo.rect(0f, 0.58f, 0.5f, 1f)); c.add(Geo.rect(0.5f, 0.58f, 1f, 1f))
            c.decos.add(Deco(D_TEXT, 0.5f, 0.075f, 0f, 0f, 0f, 0xFFF4EDE1.toInt(), "MENU", 0.085f, serif = true)); c
        }
    }
}
