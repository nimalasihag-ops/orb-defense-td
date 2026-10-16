<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="true"
        android:label="Orb Defense TD 6"
        android:hardwareAccelerated="true"
        android:supportsRtl="true"
        android:theme="@style/Theme.AppCompat.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:configChanges="orientation|screenSize|keyboardHidden"
            android:exported="true"
            android:screenOrientation="sensorLandscape">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
package com.southdelhi.orbdefensetd

import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.*
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var gameView: BtdGameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enable edge-to-edge immersive fullscreen on Android 10+ (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        }
        gameView = BtdGameView(this)
        setContentView(gameView)
    }

    override fun onPause() {
        super.onPause()
        gameView.pause()
    }

    override fun onResume() {
        super.onResume()
        gameView.resume()
    }
}

enum class TargetPriority { FIRST, LAST, CLOSE, STRONG }

enum class TowerType(
    val title: String,
    val baseCost: Int,
    val baseRange: Float,
    val baseFireRate: Float, // shots per second
    val baseDamage: Int,
    val basePierce: Int,
    val color: Int
) {
    DART("Dart Striker", 200, 210f, 1.6f, 1, 2, Color.rgb(59, 130, 246)),
    TACK("Tack Nova", 280, 145f, 1.1f, 1, 1, Color.rgb(236, 72, 153)),
    CRYO("Cryo Mage", 400, 195f, 1.0f, 1, 3, Color.rgb(16, 185, 129)),
    BOMB("MOAB Cannon", 525, 240f, 0.75f, 2, 8, Color.rgb(245, 158, 11))
}

/**
 * Multi-layered enemy ("Bloon") model:
 * Tier 1: Red (1 HP) -> Tier 2: Blue -> Tier 3: Green -> Tier 4: Yellow -> Tier 5: Pink
 * Tier 6: Lead/Armored (Requires Bomb or Tier 3+ Pierce) -> Tier 7: MOAB Boss (60 HP, bursts into 4 Yellows)
 */
class BloonEnemy(
    var tier: Int,
    var distanceTraveled: Float = 0f,
    var isCamo: Boolean = false
) {
    var moabHp: Int = if (tier == 7) 65 else 1
    var slowTimer: Float = 0f
    var x: Float = 0f
    var y: Float = 0f

    val speed: Float
        get() {
            val base = when (tier) {
                1 -> 115f // Red
                2 -> 150f // Blue
                3 -> 190f // Green
                4 -> 260f // Yellow
                5 -> 310f // Pink
                6 -> 110f // Armored Lead
                7 -> 75f  // MOAB Airship
                else -> 115f
            }
            return if (slowTimer > 0f) base * 0.5f else base
        }

    val radius: Float
        get() = when (tier) {
            7 -> 38f
            6 -> 22f
            else -> 16f + tier * 1.5f
        }

    val color: Int
        get() = when (tier) {
            1 -> Color.rgb(239, 68, 68)   // Red
            2 -> Color.rgb(59, 130, 246)  // Blue
            3 -> Color.rgb(34, 197, 94)   // Green
            4 -> Color.rgb(250, 204, 21)  // Yellow
            5 -> Color.rgb(244, 114, 182) // Pink
            6 -> Color.rgb(100, 116, 139) // Armored Lead
            7 -> Color.rgb(14, 165, 233)  // MOAB Blue Blimp
            else -> Color.RED
        }

    val rbeValue: Int
        get() = if (tier == 7) moabHp + 16 else tier
}

class PlacedTower(val type: TowerType, val x: Float, val y: Float) {
    var path1Tier = 0 // Top Path: Damage & Pierce
    var path2Tier = 0 // Middle Path: Attack Speed & Active Ability
    var path3Tier = 0 // Bottom Path: Range, Camo Sight & MOAB Bonus
    var priority = TargetPriority.FIRST
    var cooldown = 0f
    var totalInvested = type.baseCost
    var popCount = 0
    var angle = 0f

    val range: Float
        get() = type.baseRange * (1f + path3Tier * 0.16f)

    val fireRate: Float
        get() = type.baseFireRate * (1f + path2Tier * 0.28f)

    val damage: Int
        get() = type.baseDamage + (if (path1Tier >= 2) 1 else 0) + (if (path1Tier >= 4) 2 else 0) + (if (path1Tier == 5) 4 else 0)

    val pierce: Int
        get() = type.basePierce + path1Tier * 2

    val canPopLead: Boolean
        get() = type == TowerType.BOMB || path1Tier >= 3 || path3Tier >= 3

    val canSeeCamo: Boolean
        get() = path3Tier >= 2 || type == TowerType.CRYO

    // BTD6 Crosspathing Rule:
    // Max Tier is 5. Only ONE path can exceed Tier 2. Once two paths have upgrades, the 3rd path is locked!
    fun canUpgradePath(pathIndex: Int): Boolean {
        val currentTier = when (pathIndex) {
            1 -> path1Tier
            2 -> path2Tier
            else -> path3Tier
        }
        if (currentTier >= 5) return false

        val pathsUsed = listOf(path1Tier > 0, path2Tier > 0, path3Tier > 0).count { it }
        if (currentTier == 0 && pathsUsed >= 2) return false // 3rd path locked

        val anotherPathIsDeep = when (pathIndex) {
            1 -> path2Tier > 2 || path3Tier > 2
            2 -> path1Tier > 2 || path3Tier > 2
            else -> path1Tier > 2 || path2Tier > 2
        }
        if (currentTier == 2 && anotherPathIsDeep) return false // Crosspath capped at Tier 2

        return true
    }

    fun getUpgradeCost(pathIndex: Int): Int {
        val nextTier = when (pathIndex) {
            1 -> path1Tier + 1
            2 -> path2Tier + 1
            else -> path3Tier + 1
        }
        return when (nextTier) {
            1 -> (type.baseCost * 0.6f).toInt()
            2 -> (type.baseCost * 1.1f).toInt()
            3 -> (type.baseCost * 2.2f).toInt()
            4 -> (type.baseCost * 5.0f).toInt()
            5 -> (type.baseCost * 12.0f).toInt()
            else -> 99999
        }
    }
}

class Projectile(
    var x: Float,
    var y: Float,
    val vx: Float,
    val vy: Float,
    val damage: Int,
    var pierceLeft: Int,
    val isSplash: Boolean,
    val appliesSlow: Boolean,
    val canPopLead: Boolean,
    val moabBonus: Int,
    val sourceTower: PlacedTower,
    val color: Int
) {
    val hitEnemies = HashSet<BloonEnemy>()
    var life = 1.4f
}

class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, val color: Int, var life: Float = 0.35f)

class BtdGameView(context: Context) : SurfaceView(context), Runnable {

    private var thread: Thread? = null
    @Volatile private var isPlaying = false

    // Game State
    private var cash = 650
    private var lives = 100
    private var currentRound = 0
    private var roundActive = false
    private var fastForward = false
    private var autoStart = false
    private var gameOver = false

    // Spawning Queue
    private val spawnQueue = mutableListOf<Pair<Int, Boolean>>() // Pair(tier, isCamo)
    private var spawnTimer = 0f
    private var abilityCooldown = 0f

    // Entities
    private val enemies = mutableListOf<BloonEnemy>()
    private val towers = mutableListOf<PlacedTower>()
    private val projectiles = mutableListOf<Projectile>()
    private val particles = mutableListOf<Particle>()

    // UI Selection
    private var selectedShopType: TowerType = TowerType.DART
    private var inspectedTower: PlacedTower? = null

    // Layout Metrics
    private var screenW = 1920f
    private var screenH = 1080f
    private var sidebarX = 1520f
    private val waypoints = mutableListOf<PointF>()
    private var totalTrackLength = 1f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun buildTrack(w: Float, h: Float) {
        screenW = w
        screenH = h
        sidebarX = w * 0.78f
        waypoints.clear()
        val playW = sidebarX - 40f
        waypoints.add(PointF(0f, h * 0.25f))
        waypoints.add(PointF(playW * 0.32f, h * 0.25f))
        waypoints.add(PointF(playW * 0.32f, h * 0.75f))
        waypoints.add(PointF(playW * 0.65f, h * 0.75f))
        waypoints.add(PointF(playW * 0.65f, h * 0.20f))
        waypoints.add(PointF(playW * 0.88f, h * 0.20f))
        waypoints.add(PointF(playW * 0.88f, h * 0.55f))
        waypoints.add(PointF(0f, h * 0.55f)) // loops back and exits left

        var len = 0f
        for (i in 0 until waypoints.size - 1) {
            len += hypot(waypoints[i + 1].x - waypoints[i].x, waypoints[i + 1].y - waypoints[i].y)
        }
        totalTrackLength = max(1f, len)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildTrack(w.toFloat(), h.toFloat())
    }

    override fun run() {
        var lastTime = System.nanoTime()
        while (isPlaying) {
            val now = System.nanoTime()
            var dt = (now - lastTime) / 1_000_000_000f
            lastTime = now
            if (dt > 0.1f) dt = 0.1f

            val simDt = if (fastForward) dt * 2.2f else dt
            if (!gameOver) {
                updateGame(simDt)
            }
            drawGame()
        }
    }

    private fun startNextRound() {
        if (roundActive || gameOver) return
        currentRound++
        roundActive = true
        spawnQueue.clear()

        // Procedural BTD6-style wave generator
        val baseCount = 10 + currentRound * 5
        for (i in 0 until baseCount) {
            val maxTier = when {
                currentRound < 3 -> 1
                currentRound < 6 -> 2
                currentRound < 10 -> 3
                currentRound < 15 -> 4
                currentRound < 22 -> 5
                else -> 6
            }
            val tier = Random.nextInt(1, maxTier + 1)
            val isCamo = currentRound >= 12 && (i % 7 == 0)
            spawnQueue.add(Pair(tier, isCamo))
        }
        // Spawn MOAB Boss on Round 10, 20, 30, 40...
        if (currentRound % 10 == 0) {
            val moabCount = currentRound / 10
            repeat(moabCount) {
                spawnQueue.add(Pair(7, false))
            }
        }
    }

    private fun getPointAlongTrack(dist: Float): PointF {
        var rem = dist
        for (i in 0 until waypoints.size - 1) {
            val p1 = waypoints[i]
            val p2 = waypoints[i + 1]
            val seg = hypot(p2.x - p1.x, p2.y - p1.y)
            if (rem <= seg) {
                val t = if (seg == 0f) 0f else rem / seg
                return PointF(p1.x + (p2.x - p1.x) * t, p1.y + (p2.y - p1.y) * t)
            }
            rem -= seg
        }
        return waypoints.last()
    }

    private fun updateGame(dt: Float) {
        if (abilityCooldown > 0f) abilityCooldown = max(0f, abilityCooldown - dt)

        // 1. Spawn Enemies from Queue
        if (roundActive) {
            spawnTimer -= dt
            if (spawnTimer <= 0f && spawnQueue.isNotEmpty()) {
                val next = spawnQueue.removeAt(0)
                val enemy = BloonEnemy(next.first, 0f, next.second)
                val startPos = getPointAlongTrack(0f)
                enemy.x = startPos.x
                enemy.y = startPos.y
                enemies.add(enemy)
                spawnTimer = max(0.12f, 0.55f - currentRound * 0.012f)
            } else if (spawnQueue.isEmpty() && enemies.isEmpty()) {
                // Round Cleared! Award BTD6 end-of-round cash bonus
                roundActive = false
                cash += 100 + currentRound * 10
                if (autoStart) startNextRound()
            }
        }

        // 2. Move Enemies along Track
        val leaked = mutableListOf<BloonEnemy>()
        for (e in enemies) {
            if (e.slowTimer > 0f) e.slowTimer -= dt
            e.distanceTraveled += e.speed * dt
            if (e.distanceTraveled >= totalTrackLength) {
                leaked.add(e)
            } else {
                val pt = getPointAlongTrack(e.distanceTraveled)
                e.x = pt.x
                e.y = pt.y
            }
        }
        for (e in leaked) {
            enemies.remove(e)
            lives -= e.rbeValue
            if (lives <= 0) {
                lives = 0
                gameOver = true
            }
        }

        // 3. Towers Acquire Targets & Fire
        for (t in towers) {
            t.cooldown -= dt
            if (t.cooldown <= 0f) {
                val inRange = enemies.filter { e ->
                    (!e.isCamo || t.canSeeCamo) && hypot(e.x - t.x, e.y - t.y) <= t.range
                }
                if (inRange.isNotEmpty()) {
                    val target = when (t.priority) {
                        TargetPriority.FIRST -> inRange.maxByOrNull { it.distanceTraveled }!!
                        TargetPriority.LAST -> inRange.minByOrNull { it.distanceTraveled }!!
                        TargetPriority.CLOSE -> inRange.minByOrNull { hypot(it.x - t.x, it.y - t.y) }!!
                        TargetPriority.STRONG -> inRange.maxByOrNull { it.rbeValue }!!
                    }
                    fireTower(t, target)
                    t.cooldown = 1f / t.fireRate
                }
            }
        }

        // 4. Move Projectiles & Apply Multi-Layer Pop Logic
        val deadProjectiles = mutableListOf<Projectile>()
        val newlySpawnedChildren = mutableListOf<BloonEnemy>()

        for (p in projectiles) {
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.life -= dt
            if (p.life <= 0f || p.x < 0f || p.x > sidebarX || p.y < 0f || p.y > screenH) {
                deadProjectiles.add(p)
                continue
            }

            for (e in enemies) {
                if (p.hitEnemies.contains(e)) continue
                if (hypot(e.x - p.x, e.y - p.y) <= e.radius + 12f) {
                    p.hitEnemies.add(e)
                    p.pierceLeft--

                    if (p.isSplash) {
                        // Splash damages nearby enemies too
                        for (other in enemies) {
                            if (hypot(other.x - p.x, other.y - p.y) <= 85f) {
                                applyPopDamage(other, p, newlySpawnedChildren)
                            }
                        }
                    } else {
                        applyPopDamage(e, p, newlySpawnedChildren)
                    }

                    if (p.pierceLeft <= 0) {
                        deadProjectiles.add(p)
                        break
                    }
                }
            }
        }
        projectiles.removeAll(deadProjectiles)
        enemies.removeAll { it.tier <= 0 }
        enemies.addAll(newlySpawnedChildren)

        // 5. Update Pop Particles
        val deadParticles = mutableListOf<Particle>()
        for (pt in particles) {
            pt.x += pt.vx * dt
            pt.y += pt.vy * dt
            pt.life -= dt
            if (pt.life <= 0f) deadParticles.add(pt)
        }
        particles.removeAll(deadParticles)
    }

    private fun applyPopDamage(
        e: BloonEnemy,
        p: Projectile,
        childrenOut: MutableList<BloonEnemy>
    ) {
        if (e.tier <= 0) return
        // Armored Lead check
        if (e.tier == 6 && !p.canPopLead) return

        if (p.appliesSlow && e.tier < 7) {
            e.slowTimer = 2.5f
        }

        val totalDmg = p.damage + (if (e.tier == 7) p.moabBonus else 0)
        // Spawn pop burst particles
        repeat(4) {
            val ang = Random.nextFloat() * 6.28f
            particles.add(Particle(e.x, e.y, cos(ang) * 140f, sin(ang) * 140f, e.color))
        }

        if (e.tier == 7) {
            // MOAB Airship takes HP damage first
            e.moabHp -= totalDmg
            p.sourceTower.popCount += totalDmg
            if (e.moabHp <= 0) {
                cash += 50
                e.tier = 0 // Destroy MOAB and spawn 4 Yellow Orbs
                for (i in 0..3) {
                    val child = BloonEnemy(4, max(0f, e.distanceTraveled - i * 18f), false)
                    val pos = getPointAlongTrack(child.distanceTraveled)
                    child.x = pos.x
                    child.y = pos.y
                    childrenOut.add(child)
                }
            }
        } else {
            // Standard multi-layer Bloon: strips `totalDmg` layers and grants $1 per layer popped
            val layersPopped = min(e.tier, totalDmg)
            cash += layersPopped
            p.sourceTower.popCount += layersPopped
            e.tier -= totalDmg
        }
    }

    private fun fireTower(t: PlacedTower, target: BloonEnemy) {
        val dx = target.x - t.x
        val dy = target.y - t.y
        t.angle = atan2(dy, dx)
        val moabBonus = if (t.path3Tier >= 3) 4 + t.path3Tier * 2 else 0

        if (t.type == TowerType.TACK) {
            // Fires 8 (or 12 with Path 1) projectiles in a 360-degree ring
            val count = if (t.path1Tier >= 3) 12 else 8
            for (i in 0 until count) {
                val a = (i * 2.0 * Math.PI / count).toFloat()
                projectiles.add(
                    Projectile(
                        t.x, t.y, cos(a) * 520f, sin(a) * 520f,
                        t.damage, t.pierce, false, false,
                        t.canPopLead, moabBonus, t, t.type.color
                    )
                )
            }
        } else {
            // Single or multi-shot (Middle path Tier 3+ grants Triple Shot for Dart Striker)
            val shotCount = if (t.type == TowerType.DART && t.path2Tier >= 3) 3 else 1
            for (i in 0 until shotCount) {
                val spread = (i - (shotCount - 1) / 2f) * 0.16f
                val a = t.angle + spread
                projectiles.add(
                    Projectile(
                        t.x, t.y, cos(a) * 680f, sin(a) * 680f,
                        t.damage, t.pierce,
                        isSplash = (t.type == TowerType.BOMB),
                        appliesSlow = (t.type == TowerType.CRYO),
                        canPopLead = t.canPopLead,
                        moabBonus = moabBonus,
                        sourceTower = t,
                        color = t.type.color
                    )
                )
            }
        }
    }

    private fun triggerOverdriveAbility() {
        if (abilityCooldown > 0f) return
        val hasAbilityTower = towers.any { it.path2Tier >= 4 }
        if (!hasAbilityTower) return

        abilityCooldown = 18f
        val spawned = mutableListOf<BloonEnemy>()
        for (e in enemies) {
            if (e.tier == 7) {
                e.moabHp -= 25
                if (e.moabHp <= 0) e.tier = 0
            } else {
                cash += min(e.tier, 3)
                e.tier -= 3
            }
        }
        enemies.removeAll { it.tier <= 0 }
        enemies.addAll(spawned)
    }

    private fun isPointNearTrack(px: Float, py: Float): Boolean {
        for (i in 0 until waypoints.size - 1) {
            val a = waypoints[i]
            val b = waypoints[i + 1]
            val l2 = (b.x - a.x).pow(2) + (b.y - a.y).pow(2)
            val t = if (l2 == 0f) 0f else (((px - a.x) * (b.x - a.x) + (py - a.y) * (b.y - a.y)) / l2).coerceIn(0f, 1f)
            val projX = a.x + t * (b.x - a.x)
            val projY = a.y + t * (b.y - a.y)
            if (hypot(px - projX, py - projY) < 52f) return true
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        val tx = event.x
        val ty = event.y

        if (gameOver) {
            // Reset Game on Tap
            cash = 650
            lives = 100
            currentRound = 0
            roundActive = false
            enemies.clear()
            
