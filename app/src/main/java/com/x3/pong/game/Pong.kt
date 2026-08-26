package com.x3.pong.game

import kotlin.math.abs
import kotlin.math.sign
import kotlin.random.Random

const val MODE_CLASSIC = 0
const val MODE_REMIX = 1

const val DIFF_EASY = 0
const val DIFF_NORMAL = 1
const val DIFF_HARD = 2

/** Court is normalised: x and y both run -1..1. The renderer owns metres. */
const val WALL_Y = 1f
const val PADDLE_X = 0.92f

sealed class Ev {
    /** [byPlayer] true if the human returned it. [offset] is -1..1 up the paddle. */
    class Paddle(val byPlayer: Boolean, val offset: Float, val speed: Float) : Ev()
    class Wall(val y: Float) : Ev()
    /** [playerScored] true if the point went to the human. */
    class Point(val playerScored: Boolean) : Ev()
    class Over(val playerWon: Boolean) : Ev()
}

/**
 * ============================================================================
 *  PONG — the rules, and nothing else.
 * ============================================================================
 *
 * No Android, no OpenGL, no audio: this is a court, two paddles and a ball.
 * Everything that decides how the game FEELS lives here as a number the two
 * modes and three difficulties set differently, so tuning never means editing
 * the renderer.
 *
 * ## The two modes are genuinely different games
 *
 * CLASSIC is 1972: the ball keeps a constant speed for the whole rally, the
 * only control you have over its angle is where on the paddle you hit it, and
 * the first to eleven wins. It is deliberately not improved.
 *
 * REMIX keeps that skeleton and adds the two things the original could not do:
 * the ball accelerates the longer a rally survives, and a moving paddle imparts
 * SPIN — drag the paddle as the ball arrives and you bend its path. Rallies
 * therefore end faster and more decisively, so it plays to seven.
 */
class Pong(seed: Long = 0x504F4E47L) {

    private val rnd = Random(seed)

    var mode = MODE_CLASSIC; private set
    var difficulty = DIFF_NORMAL; private set

    var ballX = 0f; private set
    var ballY = 0f; private set
    private var vx = 0f
    private var vy = 0f

    /** Paddle centres, -1..1. The player is on the right. */
    var playerY = 0f; private set
    var aiY = 0f; private set
    private var lastPlayerY = 0f

    /** Vertical half-height of a paddle, set from difficulty. */
    var paddleHalf = 0.20f; private set

    var playerScore = 0; private set
    var aiScore = 0; private set
    var rally = 0; private set
    var over = false; private set
    var playerWon = false; private set

    /** Current ball speed, exposed so the renderer can react to it. */
    val speed: Float get() = kotlin.math.sqrt(vx * vx + vy * vy)

    private var baseSpeed = 1.1f
    private var aiSpeed = 1.4f
    private var aiError = 0.10f
    private var winScore = 11
    private var serveDelay = 0f

    private val evs = ArrayList<Ev>(8)

    fun target() = winScore

    fun start(mode: Int, difficulty: Int) {
        this.mode = mode
        this.difficulty = difficulty

        // DIFFICULTY IS THREE NUMBERS, not one. Making the opponent merely
        // faster produces a wall that never misses; making it merely slower
        // produces one that never tries. What actually separates easy from hard
        // is how much of the court the player has to defend (paddleHalf), how
        // quickly the opponent commits to the ball (aiSpeed), and how far off
        // its aim is (aiError) — an opponent that misjudges by a paddle-width
        // loses in a way that feels like a rally rather than a gift.
        when (difficulty) {
            DIFF_EASY -> { paddleHalf = 0.26f; aiSpeed = 0.95f; aiError = 0.26f; baseSpeed = 0.90f }
            DIFF_HARD -> { paddleHalf = 0.15f; aiSpeed = 1.95f; aiError = 0.04f; baseSpeed = 1.35f }
            else      -> { paddleHalf = 0.20f; aiSpeed = 1.40f; aiError = 0.12f; baseSpeed = 1.10f }
        }
        winScore = if (mode == MODE_REMIX) 7 else 11

        playerScore = 0; aiScore = 0
        over = false; playerWon = false
        playerY = 0f; aiY = 0f; lastPlayerY = 0f
        serve(if (rnd.nextBoolean()) 1f else -1f)
    }

    private fun serve(dirX: Float) {
        ballX = 0f; ballY = 0f
        rally = 0
        // Never serve flat: a purely horizontal serve makes the first exchange
        // a formality, and never so steep that it spends the rally in the walls.
        val ang = (rnd.nextFloat() * 0.7f - 0.35f)
        vx = dirX * baseSpeed
        vy = baseSpeed * ang
        serveDelay = 0.7f
    }

    /**
     * @param wantPlayerY where the human's paddle is being asked to go, -1..1.
     */
    fun update(dt: Float, wantPlayerY: Float): List<Ev> {
        evs.clear()
        if (over) return evs

        // A NaN or a stalled frame must not poison the court. dt arrives from a
        // GL callback, and one bad value would otherwise leave every position
        // NaN for the rest of the session with nothing on screen moving.
        val h = if (dt.isNaN()) 0f else dt.coerceIn(0f, 0.05f)

        lastPlayerY = playerY
        playerY = wantPlayerY.coerceIn(-1f + paddleHalf, 1f - paddleHalf)

        if (serveDelay > 0f) { serveDelay -= h; return evs }

        moveAi(h)

        ballX += vx * h
        ballY += vy * h

        // Walls.
        if (ballY > WALL_Y) { ballY = WALL_Y - (ballY - WALL_Y); vy = -abs(vy); evs.add(Ev.Wall(WALL_Y)) }
        if (ballY < -WALL_Y) { ballY = -WALL_Y - (ballY + WALL_Y); vy = abs(vy); evs.add(Ev.Wall(-WALL_Y)) }

        // Paddles. Tested against the paddle's plane rather than its box: at
        // speed the ball can cross a paddle's whole thickness inside one frame,
        // and a box test would let it through.
        if (vx > 0f && ballX >= PADDLE_X) hitPaddle(true)
        else if (vx < 0f && ballX <= -PADDLE_X) hitPaddle(false)

        // Points.
        if (ballX > 1.08f) point(false)
        else if (ballX < -1.08f) point(true)

        return evs
    }

    private fun hitPaddle(player: Boolean) {
        val py = if (player) playerY else aiY
        val off = (ballY - py) / paddleHalf
        if (abs(off) > 1.12f) return            // missed; let it run off court

        ballX = if (player) PADDLE_X else -PADDLE_X
        rally++

        // Contact point sets the angle. This is the whole of Pong's skill in
        // one line and both modes keep it.
        var newVy = off * baseSpeed * 0.95f

        if (mode == MODE_REMIX) {
            // SPIN. A paddle that is moving as the ball arrives drags it. This
            // is what makes remix a different game: the player has a second
            // control input that costs them position to use.
            val paddleVel = if (player) (playerY - lastPlayerY) else 0f
            newVy += paddleVel * 22f
        }

        val sp = if (mode == MODE_REMIX) {
            // Accelerate with the rally, capped, so a long exchange gets
            // genuinely dangerous rather than merely long.
            (baseSpeed * (1f + rally * 0.055f)).coerceAtMost(baseSpeed * 2.4f)
        } else {
            // 1972: constant, with the single step-up the original had.
            if (rally >= 8) baseSpeed * 1.25f else baseSpeed
        }

        vy = newVy.coerceIn(-sp * 0.95f, sp * 0.95f)
        val vxMag = kotlin.math.sqrt((sp * sp - vy * vy).coerceAtLeast(0.04f))
        vx = if (player) -vxMag else vxMag
        evs.add(Ev.Paddle(player, off.coerceIn(-1f, 1f), sp))
    }

    private fun moveAi(h: Float) {
        // The opponent tracks the ball only while it is coming toward it, and
        // drifts back to centre otherwise. An opponent that mirrors the ball at
        // all times is unbeatable and, worse, boring to watch.
        val goal = if (vx < 0f) ballY + aiError * aiErrorSign() else 0f
        val d = goal - aiY
        val step = aiSpeed * h
        aiY = (aiY + d.coerceIn(-step, step)).coerceIn(-1f + paddleHalf, 1f - paddleHalf)
    }

    private var errSign = 1f
    private var errT = 0f
    private fun aiErrorSign(): Float {
        // Flip the aim bias occasionally so the miss is not always the same
        // way, which a player learns in about three rallies.
        errT += 0.016f
        if (errT > 1.3f) { errT = 0f; errSign = if (rnd.nextBoolean()) 1f else -1f }
        return errSign
    }

    private fun point(playerScored: Boolean) {
        if (playerScored) playerScore++ else aiScore++
        evs.add(Ev.Point(playerScored))
        if (playerScore >= winScore || aiScore >= winScore) {
            over = true
            playerWon = playerScore >= winScore
            evs.add(Ev.Over(playerWon))
        } else {
            serve(if (playerScored) -1f else 1f)
        }
    }
}
