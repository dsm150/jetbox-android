package net.jetnet.jetbox.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Значки состояний щита — векторные аналоги SF Symbols (`shield.slash`, `shield.lefthalf.filled`,
 * `checkmark.shield.fill`, `exclamationmark.shield.fill`). Контур щита снят со скриншотов iOS
 * (`proof/store-69`). Окно 24×24; щит занимает 17,5×21,2 — при размере значка `диаметр × 0,44`
 * высота щита совпадает с SF-символом шрифта `диаметр × 0,40`.
 *
 * Слои — как `symbolRenderingMode(.hierarchical)` iOS: основной слой — цвет фазы, вторичный —
 * тот же цвет с непрозрачностью [SECONDARY_ALPHA]. Значки щита строятся один раз белыми и
 * тонируются при рисовании (`ColorFilter.tint`, SrcIn сохраняет альфу слоёв) — смена цвета фазы
 * не пересоздаёт вектор. Галочка «Под защитой» — отдельный белый слой поверх [shieldFill].
 *
 * Здесь же — значки интерфейса `gearshape`, `house.fill`, `arrow.triangle.2.circlepath` и
 * `point.3.filled.connected.trianglepath.dotted` (вместо Material-иконок).
 */
object JetBoxIcons {
    const val SECONDARY_ALPHA = 0.5f

    /** Отношение размера значка к диаметру щита-кнопки. */
    const val SIZE_TO_DIAMETER = 0.44f

    private const val OUTLINE = 1.25f
    private const val SLASH = 1.55f

    /** `shield.slash`: контур щита — вторичный слой, косая черта — основной; контур разорван у черты. */
    fun shieldSlash(tint: Color = Color.White): ImageVector {
        val a = 1.3f to 1.9f
        val b = 21.5f to 21.6f
        return builder("shield.slash")
            .addGroup(name = "cut", clipPathData = bandCut(a, b, gap = 1.9f))
            .addPath(
                shieldPath(inset = OUTLINE),
                stroke = SolidColor(tint),
                strokeAlpha = SECONDARY_ALPHA,
                strokeLineWidth = OUTLINE,
                strokeLineJoin = StrokeJoin.Round,
            )
            .clearGroup()
            .addPath(
                PathData { moveTo(a.first, a.second); lineTo(b.first, b.second) },
                stroke = SolidColor(tint),
                strokeLineWidth = SLASH,
                strokeLineCap = StrokeCap.Round,
            )
            .build()
    }

    /** `shield.lefthalf.filled`: контур + залитая левая половина. */
    fun shieldLeftHalfFilled(tint: Color = Color.White): ImageVector =
        builder("shield.lefthalf.filled")
            .addPath(
                shieldPath(inset = OUTLINE),
                stroke = SolidColor(tint),
                strokeLineWidth = OUTLINE,
                strokeLineJoin = StrokeJoin.Round,
            )
            .addGroup(name = "left", clipPathData = PathData { moveTo(0f, 0f); lineTo(12f, 0f); lineTo(12f, 24f); lineTo(0f, 24f); close() })
            .addPath(shieldPath(inset = OUTLINE), fill = SolidColor(tint))
            .clearGroup()
            .build()

    /**
     * `checkmark.shield.fill`. На iOS в фазе «Под защитой» — палитра: сплошной щит цвета фазы и белая
     * галочка ([mark] = белый); иначе — иерархия (щит вторичным слоем, галочка — основным).
     */
    fun checkmarkShieldFill(shield: Color, mark: Color, shieldAlpha: Float = 1f): ImageVector =
        builder("checkmark.shield.fill")
            .addPath(shieldPath(), fill = SolidColor(shield), fillAlpha = shieldAlpha)
            .addCheckmark(mark)
            .build()

    /** Сплошной силуэт щита — нижний слой `checkmark.shield.fill` (тонируется цветом фазы). */
    fun shieldFill(tint: Color = Color.White): ImageVector =
        builder("shield.fill").addPath(shieldPath(), fill = SolidColor(tint)).build()

    /** Галочка `checkmark.shield.fill` отдельным слоем — на iOS белая поверх щита. */
    fun shieldCheckmark(mark: Color = Color.White): ImageVector =
        builder("shield.checkmark").addCheckmark(mark).build()

    /**
     * Галочка как у SF-символа: при щите 188 dp — ≈34×34 dp вместе с толщиной линии (сверено с
     * `proof/store-69/01-protected-blue.png`), линия 1,9 — вес Regular.
     */
    private fun ImageVector.Builder.addCheckmark(mark: Color) = addPath(
        PathData { moveTo(7.64f, 12.34f); lineTo(10.97f, 15.66f); lineTo(15.59f, 7.77f) },
        stroke = SolidColor(mark),
        strokeLineWidth = 1.9f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )

    /** `exclamationmark.shield.fill` в иерархии iOS: щит — вторичный слой, знак — основной. */
    fun exclamationmarkShieldFill(tint: Color = Color.White): ImageVector =
        builder("exclamationmark.shield.fill")
            .addPath(shieldPath(), fill = SolidColor(tint), fillAlpha = SECONDARY_ALPHA)
            .addPath(
                PathData { moveTo(12f, 6.9f); lineTo(12f, 13.4f) },
                stroke = SolidColor(tint),
                strokeLineWidth = 2.3f,
                strokeLineCap = StrokeCap.Round,
            )
            .addPath(
                PathData {
                    moveTo(13.35f, 17.1f)
                    arcToRelative(1.35f, 1.35f, 0f, true, true, -2.7f, 0f)
                    arcToRelative(1.35f, 1.35f, 0f, true, true, 2.7f, 0f)
                    close()
                },
                fill = SolidColor(tint),
            )
            .build()

    /**
     * `gearshape` (шапка, `JBGlassIconButton`): тонкий контур шестерни с 8 зубцами и кольцом в
     * центре. Окно 24, шестерня 20,3 — как глиф `.title3` на iOS (20,3 pt). Цвет задаёт `Icon(tint)`.
     */
    val gearshape: ImageVector by lazy {
        val w = 1.6f
        val tip = 10.15f - w / 2
        val root = 7.45f
        fun px(r: Float, deg: Float) = 12f + r * cos(deg * PI / 180).toFloat()
        fun py(r: Float, deg: Float) = 12f + r * sin(deg * PI / 180).toFloat()
        val outline = PathData {
            for (k in 0 until 8) {
                val c = -90f + 45f * k
                if (k == 0) moveTo(px(root, c - 14.5f), py(root, c - 14.5f)) else lineTo(px(root, c - 14.5f), py(root, c - 14.5f))
                lineTo(px(tip, c - 10.5f), py(tip, c - 10.5f))
                arcTo(tip, tip, 0f, false, true, px(tip, c + 10.5f), py(tip, c + 10.5f))
                lineTo(px(root, c + 14.5f), py(root, c + 14.5f))
                arcTo(root, root, 0f, false, true, px(root, c + 30.5f), py(root, c + 30.5f))
            }
            close()
        }
        val hub = PathData {
            moveTo(12f + 2.95f, 12f)
            arcToRelative(2.95f, 2.95f, 0f, true, true, -5.9f, 0f)
            arcToRelative(2.95f, 2.95f, 0f, true, true, 5.9f, 0f)
            close()
        }
        builder("gearshape")
            .addPath(outline, stroke = SolidColor(Color.Black), strokeLineWidth = w, strokeLineJoin = StrokeJoin.Round)
            .addPath(hub, stroke = SolidColor(Color.Black), strokeLineWidth = w)
            .build()
    }

    /**
     * `house.fill` (панель дома): крыша-скат линией с круглыми концами и трубой справа, под ней —
     * дом с проёмом двери. Пропорции сняты с `proof/store-69/02-off-blue.png`. Цвет задаёт `Icon(tint)`.
     */
    val houseFill: ImageVector by lazy {
        val roof = PathData { moveTo(2.0f, 11.4f); lineTo(12f, 3.2f); lineTo(22.0f, 11.4f) }
        val chimney = PathData {
            moveTo(17.3f, 8.4f)
            lineTo(17.3f, 4.6f)
            quadTo(17.3f, 4.0f, 17.9f, 4.0f)
            lineTo(19.3f, 4.0f)
            quadTo(19.9f, 4.0f, 19.9f, 4.6f)
            lineTo(19.9f, 10.5f)
            close()
        }
        val body = PathData {
            // Дом: конёк под крышей, стены, скруглённые нижние углы.
            moveTo(12f, 5.5f)
            lineTo(20.0f, 12.05f)
            lineTo(20.0f, 20.0f)
            quadTo(20.0f, 21.6f, 18.4f, 21.6f)
            lineTo(5.6f, 21.6f)
            quadTo(4.0f, 21.6f, 4.0f, 20.0f)
            lineTo(4.0f, 12.05f)
            close()
            // Проём двери (вычитается правилом even-odd).
            moveTo(9.5f, 14.1f)
            lineTo(14.5f, 14.1f)
            lineTo(14.5f, 19.5f)
            lineTo(9.5f, 19.5f)
            close()
        }
        builder("house.fill")
            .addPath(roof, stroke = SolidColor(Color.Black), strokeLineWidth = 2.0f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            .addPath(chimney, fill = SolidColor(Color.Black))
            .addPath(body, fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd)
            .build()
    }

    /**
     * `arrow.triangle.2.circlepath` (экран «Другой JetBox»): две дуги кольца тонкой линией с круглыми
     * концами, у каждой — небольшой скруглённый треугольник-наконечник (слева — вверх, справа — вниз,
     * как у SF-символа). Рисунок симметричен относительно поворота на 180°.
     */
    val arrowTriangle2Circlepath: ImageVector by lazy {
        val r = 8.1f
        val line = 1.7f
        fun px(deg: Float, rad: Float = r) = 12f + rad * cos(deg * PI / 180).toFloat()
        fun py(deg: Float, rad: Float = r) = 12f + rad * sin(deg * PI / 180).toFloat()
        // Дуга по часовой от [from] до [to] градусов (экранные углы: 0 — вправо, 90 — вниз) + наконечник
        // на конце: основание — поперёк касательной, остриё — вдоль неё.
        fun arrow(from: Float, to: Float) = PathData {
            moveTo(px(from), py(from))
            arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = px(to), y1 = py(to))
        } to PathData {
            val bx = px(to)
            val by = py(to)
            val tx = -sin(to * PI / 180).toFloat()
            val ty = cos(to * PI / 180).toFloat()
            val nx = cos(to * PI / 180).toFloat()
            val ny = sin(to * PI / 180).toFloat()
            val half = 3.0f
            val len = 4.4f
            moveTo(bx + nx * half, by + ny * half)
            lineTo(bx + tx * len, by + ty * len)
            lineTo(bx - nx * half, by - ny * half)
            close()
        }
        val top = arrow(205f, 350f)
        val bottom = arrow(25f, 170f)
        val b = builder("arrow.triangle.2.circlepath")
        for ((arc, head) in listOf(top, bottom)) {
            b.addPath(arc, stroke = SolidColor(Color.Black), strokeLineWidth = line, strokeLineCap = StrokeCap.Round)
            b.addPath(
                head,
                fill = SolidColor(Color.Black),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 0.9f,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        b.build()
    }

    /**
     * `point.3.filled.connected.trianglepath.dotted` (выбор выхода): три точки-узла в вершинах
     * треугольника, между ними — пунктир.
     */
    val point3ConnectedTriangle: ImageVector by lazy {
        val nodes = listOf(12f to 4.6f, 4.4f to 18.4f, 19.6f to 18.4f)
        val dots = PathData {
            for ((x, y) in nodes) circle(x, y, 2.7f)
            for (i in nodes.indices) {
                val (ax, ay) = nodes[i]
                val (bx, by) = nodes[(i + 1) % nodes.size]
                // Три точки пунктира между узлами.
                for (k in 1..3) {
                    val t = k / 4f
                    circle(ax + (bx - ax) * t, ay + (by - ay) * t, 0.95f)
                }
            }
        }
        builder("point.3.connected").addPath(dots, fill = SolidColor(Color.Black)).build()
    }

    private fun androidx.compose.ui.graphics.vector.PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = cx + r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = cx - r, y1 = cy)
        close()
    }

    private fun builder(name: String) = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )

    /** Силуэт щита SF Symbols. [inset] — толщина обводки: контур сжимается, чтобы снаружи совпасть с заливкой. */
    private fun shieldPath(inset: Float = 0f) = PathData {
        val k = 1f - inset / 17.5f
        fun x(v: Float) = 12f + (v - 12f) * k
        fun y(v: Float) = 12f + (v - 12f) * k
        moveTo(x(12.7f), y(1.62f))
        lineTo(x(19.6f), y(4.1f))
        quadTo(x(20.77f), y(4.55f), x(20.77f), y(5.75f))
        lineTo(x(20.77f), y(12.2f))
        curveTo(x(20.77f), y(16.8f), x(16.5f), y(20.0f), x(12.9f), y(22.15f))
        quadTo(x(12f), y(22.7f), x(11.1f), y(22.15f))
        curveTo(x(7.5f), y(20.0f), x(3.23f), y(16.8f), x(3.23f), y(12.2f))
        lineTo(x(3.23f), y(5.75f))
        quadTo(x(3.23f), y(4.55f), x(4.4f), y(4.1f))
        lineTo(x(11.3f), y(1.62f))
        quadTo(x(12f), y(1.38f), x(12.7f), y(1.62f))
        close()
    }

    /** Маска «всё, кроме полосы шириной 2×[gap] вдоль отрезка a→b» — разрыв контура у косой черты. */
    private fun bandCut(a: Pair<Float, Float>, b: Pair<Float, Float>, gap: Float): List<androidx.compose.ui.graphics.vector.PathNode> {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        val ux = dx / len
        val uy = dy / len
        val nx = uy
        val ny = -ux
        val far = 60f
        return PathData {
            for (side in listOf(1f, -1f)) {
                val ox = a.first + nx * gap * side
                val oy = a.second + ny * gap * side
                moveTo(ox - ux * far, oy - uy * far)
                lineTo(ox + ux * far, oy + uy * far)
                lineTo(ox + ux * far + nx * far * side, oy + uy * far + ny * far * side)
                lineTo(ox - ux * far + nx * far * side, oy - uy * far + ny * far * side)
                close()
            }
        }
    }
}
