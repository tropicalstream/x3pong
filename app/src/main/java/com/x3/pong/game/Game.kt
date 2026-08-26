package com.x3.pong.game

import com.x3.pong.Settings
import com.x3.pong.audio.GameAudio
import com.x3.pong.input.SwipeControl
import com.x3.pong.render.NeonBatch
import com.x3.pong.render.Particles
import com.x3.pong.render.VectorFont
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * ============================================================================
 *  PONG for the RayNeo X3 Pro — menu, court, and the two modes.
 * ============================================================================
 *
 * [Pong] owns the rules. This turns them into light and sound, and turns a
 * temple touchpad into a paddle.
 *
 * ## Dragging, not flicking
 *
 * Every other game on this chassis quantises the pad into swipe steps. Pong
 * cannot: a paddle that jumps a fixed distance per flick can never be put where
 * the ball is going. So the paddle reads [SwipeControl.pos01], the absolute
 * 0..1 position of your finger along a sweep the chassis has already
 * calibrated, and follows it directly. Your finger IS the paddle.
 *
 * That leaves tap free for everything else: serve, menu, restart.
 *
 * ## Classic looks like 1972 on purpose
 *
 * Classic mode is drawn in one colour, with a dashed net, square paddles and a
 * square ball, and no particles at all. It would be trivial to give it the
 * neon treatment and it is deliberately not given it — the point of having two
 * modes is that they are two different things to want.
 */
class Game(
    private val settings: Settings,
    private val audio: GameAudio,
    private val swipe: SwipeControl
) {

    val batch = NeonBatch()

    @JvmField var fps: Float = 0f
    @JvmField var tempC: Int = 0

    private companion object {
        // Court, in plane-local metres. The visible field is about +-0.144
        // across and +-0.117 up, measured on the device.
        const val HALF_W = 0.1250f
        const val HALF_H = 0.0820f
        const val PADDLE_W = 0.0040f
        const val BALL_R = 0.0042f

        const val ISO_TILT = 0.38f

        const val ST_MENU = 0
        const val ST_PLAY = 1
        const val ST_OVER = 2

        const val ROW_MODE = 0
        const val ROW_DIFF = 1
        const val ROW_START = 2
        const val ROWS = 3

        /** One flick moves one menu row; see the note in onSwipe. */
        const val ROW_GAP_MS = 150L
    }

    private val pong = Pong()
    private val parts = Particles(384)

    private var state = ST_MENU
    private var stateT = 0f
    private var row = ROW_START
    private var mode = MODE_CLASSIC
    private var diff = DIFF_NORMAL
    private var lastRowMs = 0L
    private var shake = 0f
    private var musicOn = false

    /** Trail of recent ball positions, for remix. Fixed length, never grows. */
    private val trailX = FloatArray(14)
    private val trailY = FloatArray(14)
    private var trailN = 0
    private var trailHead = 0

    private val events = ArrayList<Char>(8)
    private val windowScales = floatArrayOf(70f, 92f, 115f)

    init { parts.scaleLengths(0.013f) }

    // ---- input ------------------------------------------------------------

    fun tap() { synchronized(events) { events.add('T') } }
    fun doubleTap() { synchronized(events) { events.add('D') } }
    fun swipe(steps: Int) { synchronized(events) { events.add(if (steps > 0) '+' else '-') } }

    private fun drainInput() {
        synchronized(events) {
            for (e in events) when (e) {
                '+' -> onSwipe(1)
                '-' -> onSwipe(-1)
                'T' -> onTap()
                'D' -> onDouble()
            }
            events.clear()
        }
    }

    /**
     * Menu navigation only. In play the paddle follows the drag directly, so a
     * swipe here would fight the thing the finger is already doing.
     *
     * One flick moves one row: the pad emits a step every 90px inside a single
     * stroke, so without this a normal swipe scrolls the whole menu.
     */
    private fun onSwipe(dir: Int) {
        if (state != ST_MENU) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastRowMs < ROW_GAP_MS) return
        lastRowMs = now
        row = ((row + dir) % ROWS + ROWS) % ROWS
        audio.sfx("ui")
    }

    private fun onTap() {
        when (state) {
            ST_MENU -> when (row) {
                ROW_MODE -> { mode = 1 - mode; audio.sfx("ui") }
                ROW_DIFF -> { diff = (diff + 1) % 3; audio.sfx("ui") }
                else -> startMatch()
            }
            ST_OVER -> { state = ST_MENU; stateT = 0f; row = ROW_START; audio.sfx("ui"); stopMusic() }
            else -> Unit
        }
    }

    private fun onDouble() {
        // A way out of a match without waiting for it to end.
        if (state == ST_PLAY) { state = ST_MENU; stateT = 0f; row = ROW_START; stopMusic(); audio.sfx("ui") }
    }

    private fun startMatch() {
        pong.start(mode, diff)
        parts.clear()
        trailN = 0; trailHead = 0
        state = ST_PLAY
        stateT = 0f
        shake = 0f
        audio.sfx("serve")
        // Classic gets silence. The 1972 cabinet had no soundtrack, and a
        // synthwave track under it would make the mode choice meaningless.
        if (mode == MODE_REMIX) { audio.playLevelMusic(1 + diff); musicOn = true } else stopMusic()
    }

    private fun stopMusic() {
        if (musicOn) { audio.stopLevelMusic(); musicOn = false }
    }

    // ---- frame ------------------------------------------------------------

    fun update(dt: Float) {
        stateT += dt
        drainInput()

        if (state == ST_PLAY) step(dt)

        parts.update(dt)
        if (shake > 0f) shake = (shake - dt * 2.6f).coerceAtLeast(0f)

        // Game metres -> world units. Without this the whole court draws at
        // 1/92 scale and looks like the app failed to start.
        val s = windowScales[settings.windowSize.coerceIn(0, 2)]
        // CLASSIC IS FLAT, REMIX LEANS BACK. The chassis basis tilts the plane
        // 22 degrees, which is right for a game with depth and wrong for this
        // one: it turns a rectangular court into a trapezoid with a narrower
        // far wall, and Pong is a picture of a television, not a table. Remix
        // keeps the lean, where the ball trail and the particles have somewhere
        // to sit in z.
        val tilt = if (mode == MODE_REMIX && state != ST_MENU) ISO_TILT else 0f
        val ct = cos(tilt); val st = sin(tilt)
        batch.setBasis(0f, 0f, 0f, s, 0f, 0f, 0f, s * ct, -s * st, 0f, s * st, s * ct)
        batch.lift = 0f

        batch.begin()
        when (state) {
            ST_MENU -> drawMenu()
            ST_PLAY -> { drawCourt(); drawScore() }
            else -> { drawCourt(); drawScore(); drawOver() }
        }
        parts.draw(batch)
    }

    private fun step(dt: Float) {
        // The pad's absolute position drives the paddle. 0 is one end of the
        // sweep, 1 the other; the court runs -1..1 with the top at +1.
        val want = (swipe.pos01 * 2f - 1f) * -1f
        for (e in pong.update(dt, want)) when (e) {
            is Ev.Paddle -> {
                audio.sfx(if (mode == MODE_REMIX) "hit_rx" else "hit")
                if (mode == MODE_REMIX) {
                    val u = courtU(if (e.byPlayer) PADDLE_X else -PADDLE_X)
                    parts.burst(u, courtV(pong.ballY), 12, 0.05f, 0.5f, 0.95f, 1f, 0.5f)
                }
            }
            is Ev.Wall -> {
                audio.sfx(if (mode == MODE_REMIX) "wall_rx" else "wall")
                if (mode == MODE_REMIX) {
                    parts.burst(courtU(pong.ballX), courtV(e.y), 8, 0.04f, 1f, 0.55f, 0.2f, 0.4f)
                }
            }
            is Ev.Point -> {
                audio.sfx(if (mode == MODE_REMIX) "score_rx" else "score")
                shake = 0.8f
                trailN = 0; trailHead = 0
                if (mode == MODE_REMIX) {
                    val u = if (e.playerScored) HALF_W else -HALF_W
                    parts.burst(u, 0f, 40, 0.09f, 1f, 0.8f, 0.3f, 0.9f)
                }
            }
            is Ev.Over -> {
                audio.sfx(if (e.playerWon) "win" else "lose")
                stopMusic()
                state = ST_OVER
                stateT = 0f
            }
        }
        if (mode == MODE_REMIX) pushTrail()
    }

    private fun pushTrail() {
        trailX[trailHead] = pong.ballX
        trailY[trailHead] = pong.ballY
        trailHead = (trailHead + 1) % trailX.size
        if (trailN < trailX.size) trailN++
    }

    // ---- geometry ---------------------------------------------------------

    private fun courtU(x: Float) = x * HALF_W
    private fun courtV(y: Float) = y * HALF_H
    private fun shakeU() = if (shake <= 0f) 0f else sin(stateT * 59f) * 0.0018f * shake
    private fun shakeV() = if (shake <= 0f) 0f else cos(stateT * 43f) * 0.0018f * shake

    // ---- drawing ----------------------------------------------------------

    private fun drawCourt() {
        val su = shakeU(); val sv = shakeV()
        val remix = mode == MODE_REMIX

        // Frame: top and bottom walls only. The ends are open because that is
        // where the ball leaves, and drawing them would suggest otherwise.
        val fr = if (remix) 0.35f else 0.85f
        val fg = if (remix) 0.85f else 0.95f
        val fb = if (remix) 1f else 1f
        batch.line(-HALF_W + su, HALF_H + sv, HALF_W + su, HALF_H + sv, 0.0011f, fr, fg, fb, 0.85f)
        batch.line(-HALF_W + su, -HALF_H + sv, HALF_W + su, -HALF_H + sv, 0.0011f, fr, fg, fb, 0.85f)

        // Net: a dashed centre line, exactly as the cabinet drew it.
        var i = 0
        while (i < 11) {
            val v0 = -HALF_H + (i / 11f) * (2 * HALF_H) + 0.0030f
            val v1 = v0 + 0.0075f
            batch.line(su, v0 + sv, su, v1 + sv, 0.0009f, fr, fg, fb, if (remix) 0.30f else 0.45f)
            i++
        }

        if (remix) drawTrail(su, sv)

        // Paddles.
        val ph = pong.paddleHalf * HALF_H
        drawPaddle(courtU(-PADDLE_X) + su, courtV(pong.aiY) + sv, ph, false)
        drawPaddle(courtU(PADDLE_X) + su, courtV(pong.playerY) + sv, ph, true)

        // Ball. In remix it takes its colour from how fast the rally has got,
        // so the danger is visible rather than merely felt.
        val bu = courtU(pong.ballX) + su
        val bv = courtV(pong.ballY) + sv
        if (remix) {
            val heat = ((pong.speed / 1.1f) - 1f).coerceIn(0f, 1f)
            batch.fill(bu, bv, BALL_R, BALL_R, 0f, 1f, 1f - heat * 0.7f, 1f - heat * 0.9f, 0.95f)
            batch.circle(bu, bv, BALL_R * 1.9f, 0.0007f, 1f, 0.6f, 0.3f, 0.35f + heat * 0.4f)
        } else {
            batch.fill(bu, bv, BALL_R, BALL_R, 0f, 0.95f, 1f, 1f, 0.95f)
        }
    }

    private fun drawTrail(su: Float, sv: Float) {
        var k = 0
        while (k < trailN) {
            val idx = (trailHead - 1 - k + trailX.size * 2) % trailX.size
            val a = (1f - k / trailN.toFloat()) * 0.35f
            val r = BALL_R * (1f - k / (trailN * 1.4f))
            batch.circle(courtU(trailX[idx]) + su, courtV(trailY[idx]) + sv, r, 0.0006f,
                1f, 0.5f, 0.9f, a)
            k++
        }
    }

    private fun drawPaddle(u: Float, v: Float, half: Float, isPlayer: Boolean) {
        val remix = mode == MODE_REMIX
        val r = if (!remix) 0.95f else if (isPlayer) 0.35f else 1f
        val g = if (!remix) 1f else if (isPlayer) 1f else 0.35f
        val b = if (!remix) 1f else if (isPlayer) 0.9f else 0.6f
        batch.fill(u, v, PADDLE_W, half, 0f, r * 0.55f, g * 0.55f, b * 0.55f, 0.9f)
        batch.line(u - PADDLE_W, v - half, u - PADDLE_W, v + half, 0.0010f, r, g, b, 1f)
        batch.line(u + PADDLE_W, v - half, u + PADDLE_W, v + half, 0.0010f, r, g, b, 1f)
        batch.line(u - PADDLE_W, v + half, u + PADDLE_W, v + half, 0.0010f, r, g, b, 1f)
        batch.line(u - PADDLE_W, v - half, u + PADDLE_W, v - half, 0.0010f, r, g, b, 1f)
    }

    private fun drawScore() {
        // Big and centred over each half, the way the cabinet did it. This is
        // the one place a large glyph is worth the light: you read it between
        // points, never during one.
        // INSIDE the court, either side of the net, exactly where the cabinet
        // put them. Above the top wall they were clipped by the edge of the
        // display — removing the table lean for classic made every vertical
        // 8% taller, and the digits were the thing that fell off.
        val a = if (state == ST_PLAY) 0.55f else 0.9f
        VectorFont.draw(batch, "${pong.aiScore}", -0.055f, 0.020f, 0.0075f, 0.9f, 0.95f, 1f, a, true)
        VectorFont.draw(batch, "${pong.playerScore}", 0.055f, 0.020f, 0.0075f, 0.9f, 0.95f, 1f, a, true)
    }

    private fun drawMenu() {
        val remix = mode == MODE_REMIX
        // FITS, WITH THE NUMBERS TO PROVE IT. A glyph is about 7.3x its size
        // parameter tall and the visible field ends at v = 0.117, so the old
        // 0.0090 at v = 0.082 reached 0.148 and had its top sliced off. At
        // 0.0070 from v = 0.058 the title tops out at 0.109, and its baseline
        // still clears the MODE row (which reaches 0.050).
        VectorFont.draw(batch, "PONG", 0f, 0.058f, 0.0070f,
            if (remix) 1f else 0.9f, if (remix) 0.35f else 1f, 1f, 1f, true)

        drawMenuRow(ROW_MODE, 0.030f, "MODE", if (remix) "REMIX" else "CLASSIC")
        drawMenuRow(ROW_DIFF, 0.002f, "SKILL", when (diff) {
            DIFF_EASY -> "EASY"; DIFF_HARD -> "HARD"; else -> "NORMAL"
        })

        val sel = row == ROW_START
        val pulse = if (sel) 0.55f + 0.45f * sin(stateT * 3f) else 0.4f
        VectorFont.draw(batch, "START", 0f, -0.034f, 0.0042f, 1f, 1f, 0.6f, pulse, true)
        if (sel) drawCaret(-0.098f, -0.034f, 0.0042f)

        VectorFont.draw(batch, "SWIPE  CHOOSE", -0.126f, -0.070f, 0.0020f, 0.5f, 0.9f, 1f, 0.5f)
        VectorFont.draw(batch, "TAP    CHANGE", -0.126f, -0.086f, 0.0020f, 0.5f, 0.9f, 1f, 0.5f)
        VectorFont.draw(batch, "DRAG   MOVES PADDLE", -0.126f, -0.102f, 0.0020f, 0.5f, 0.9f, 1f, 0.5f)
    }

    /**
     * Label left, value right, with a gap wide enough for the longest value.
     *
     * The font advances about 5.4x its size parameter per character — measured
     * off the device, not guessed — so "SKILL" at 0.0028 is 76mm wide. Starting
     * the value at +0.012 ran "NORMAL" straight into it.
     */
    private fun drawMenuRow(which: Int, v: Float, label: String, value: String) {
        val sel = row == which
        val a = if (sel) 1f else 0.45f
        VectorFont.draw(batch, label, -0.118f, v, 0.0028f, 0.55f, 0.9f, 1f, a)
        VectorFont.draw(batch, value, 0.012f, v, 0.0028f, 1f, 0.85f, 0.35f, a)
        if (sel) drawCaret(-0.140f, v, 0.0028f)
    }

    /**
     * A caret, because a highlight bar would be a filled rectangle and this
     * display charges for every lit pixel. Drawn about the row's OPTICAL
     * centre — the font takes v as a baseline, so a caret centred on v sits
     * below the text it is pointing at.
     */
    private fun drawCaret(u: Float, v: Float, size: Float) {
        val h = size * 1.3f
        val cy = v + size * 1.4f
        batch.line(u, cy + h, u + h * 1.2f, cy, 0.0010f, 1f, 1f, 0.6f, 0.9f)
        batch.line(u, cy - h, u + h * 1.2f, cy, 0.0010f, 1f, 1f, 0.6f, 0.9f)
    }

    private fun drawOver() {
        val won = pong.playerWon
        VectorFont.draw(batch, if (won) "YOU WIN" else "YOU LOSE", 0f, 0.030f, 0.0055f,
            if (won) 0.4f else 1f, if (won) 1f else 0.35f, if (won) 0.7f else 0.4f, 1f, true)
        val pulse = 0.5f + 0.5f * sin(stateT * 3f)
        VectorFont.draw(batch, "TAP FOR MENU", 0f, -0.006f, 0.0030f, 1f, 1f, 0.6f, pulse, true)
        if (won && stateT < 1.2f && parts.live < 220) {
            parts.burst(0f, 0.030f, 5, 0.07f, 0.4f, 1f, 0.8f, 1.1f)
        }
    }
}
