package com.example.ar_jewelry_app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.android.filament.Camera
import com.google.android.filament.EntityManager
import com.google.android.filament.Renderer
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.Manipulator
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import io.flutter.plugin.platform.PlatformView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Vue AR native — étape **B.3+** : tracking stabilisé et pseudo-6DoF.
 *
 * Par rapport à B.3 (position 2D brute), cette version ajoute :
 *  - lissage One-Euro (x, y, taille, orientation) + **extrapolation de
 *    vitesse** (~90 ms) pour compenser la latence du pipeline async — porté
 *    de la version Dart (_AnchorSmoother de ar_service.dart), mêmes constantes ;
 *  - **orientation** du bijou déduite des landmarks 3D : axe du trou aligné
 *    sur le doigt (bague, lm13→14) ou l'avant-bras (bracelet, lm9→0), face
 *    visible orientée vers la caméra via la normale de la paume (lm5/lm17) ;
 *  - **échelle dynamique** proportionnelle à la taille apparente de la main
 *    (distance lm0–lm9) : le bijou grossit quand la main approche ;
 *  - bijou masqué (échelle 0) quand aucune main n'est détectée.
 *
 * Empilement inchangé : PreviewView (caméra, dessous) + SurfaceView Filament
 * transparent (dessus). MediaPipe écrit les valeurs filtrées (thread analyse),
 * la boucle de rendu les lit et compose la transform du nœud racine.
 */
class NativeArView(
    context: Context,
    private val activity: Activity,
    id: Int,
    args: Any?,
) : PlatformView {

    private val root = FrameLayout(context)
    private val previewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }
    private val surfaceView = SurfaceView(context).apply {
        setZOrderMediaOverlay(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
    }

    // Guide de placement (bracelet) : silhouette de poignet + couloir
    // d'avant-bras, affiché tant que le suivi n'a pas verrouillé (main
    // détectée + largeur du bras mesurée). Même principe que les essayages
    // commerciaux : imposer le cadrage où l'ajustement est fiable.
    private val guideStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.WHITE
        pathEffect = DashPathEffect(floatArrayOf(20f, 14f), 0f)
        setShadowLayer(4f, 0f, 1f, Color.argb(160, 0, 0, 0))
    }
    private val guideText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 40f
        setShadowLayer(6f, 0f, 2f, Color.argb(200, 0, 0, 0))
    }
    private val guideView: View = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val cx = w / 2f
            val cy = h * 0.45f
            val rx = w * 0.17f
            // Ellipse du poignet (légèrement aplatie) + couloir d'avant-bras
            // qui descend vers le bas de l'écran.
            canvas.drawOval(cx - rx, cy - rx * 0.38f, cx + rx, cy + rx * 0.38f, guideStroke)
            canvas.drawLine(cx - rx, cy + rx * 0.25f, cx - rx * 1.18f, h * 0.92f, guideStroke)
            canvas.drawLine(cx + rx, cy + rx * 0.25f, cx + rx * 1.18f, h * 0.92f, guideStroke)
            canvas.drawText("Placez votre poignet ici", cx, cy - rx * 0.9f, guideText)
            canvas.drawText(
                "avant-bras vers le bas, dos de la main face caméra",
                cx,
                cy - rx * 0.9f + 46f,
                guideText,
            )
        }
    }

    private val choreographer = Choreographer.getInstance()
    private val modelViewer: ModelViewer
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var handLandmarker: HandLandmarker? = null

    // Pose Landmarker (bracelet seulement) : fournit le COUDE, donc l'axe
    // réel de l'avant-bras — un vrai bracelet reste aligné sur l'avant-bras,
    // pas sur la paume (qui s'incline à chaque flexion du poignet).
    private var poseLandmarker: PoseLandmarker? = null
    private var analysisFrameCount = 0

    // Axe avant-bras (espace affichage) écrit par onPose, lu par onHands.
    @Volatile
    private var poseAxis: FloatArray? = null
    @Volatile
    private var poseAxisMs = 0L

    // Mesure de la silhouette RÉELLE du bras par le masque de segmentation
    // du Pose Landmarker (comme les essayages commerciaux : masque temps réel
    // → largeur exacte du poignet). [midX, midY, dX, dY] en espace écran
    // normalisé : centre du bras + vecteur pleine largeur de la silhouette.
    // Deux points de mesure le long de l'avant-bras : la MANCHETTE est haute
    // (bande de 1.85 unité pour un Ø intérieur de 1.30, soit ~9 cm de bras
    // couvert) et l'avant-bras s'évase justement sur cette longueur. Un rayon
    // unique la ferait mordre dans la chair d'un bout et flotter de l'autre.
    // Deux largeurs → évasement → rayon évalué au centre RÉEL de la bande.
    @Volatile
    private var wristSeg: FloatArray? = null
    @Volatile
    private var wristSegFar: FloatArray? = null
    @Volatile
    private var wristSegMs = 0L

    // Correction latérale lissée vers le centre mesuré du bras (rendu seul).
    private var segOff = 0f

    // Axe médio-latéral du poignet (colX du bijou) déduit de la silhouette
    // mesurée, lissé dans le temps (rendu seul). null = pas encore mesuré →
    // repli sur la normale de paume. Voir le bloc "roulis" de
    // updateModelTransform pour le pourquoi.
    private var smoothRoll: FloatArray? = null

    // Position brute (espace image MediaPipe) du poignet détecté par la main,
    // pour choisir le bon bras côté pose (gauche/droite).
    @Volatile
    private var handWristImg = floatArrayOf(0.5f, 0.5f)

    // Champ de vision vertical RÉEL de la caméra (radians), lu depuis les
    // caractéristiques Camera2. La caméra virtuelle DOIT l'utiliser : sinon
    // la perspective du bijou ne colle pas à celle de la vidéo et l'anneau
    // paraît "plat, posé sur l'image" (ellipse d'ouverture quasi fermée,
    // avant/arrière de même taille). Défaut : ~60° (selfie typique).
    @Volatile
    private var cameraVerticalFov = kFallbackFovRad

    private val assetPath: String =
        (args as? Map<*, *>)?.get("assetPath") as? String ?: ""

    // Point d'ancrage : "wrist" (bracelet) ou "ringFinger" (bague).
    private val anchor: String =
        (args as? Map<*, *>)?.get("anchor") as? String ?: "ringFinger"

    // Debug : rend l'occluseur visible (rouge) au lieu de profondeur-seule,
    // pour vérifier sa présence/position/taille sur device.
    private val debugOccluder: Boolean =
        (args as? Map<*, *>)?.get("debugOccluder") as? Boolean ?: false

    // Correction morphologique issue de la calibration à l'écran (doigt/
    // poignet mesuré sur la dalle, cf. CalibrationService côté Dart). Les
    // world landmarks MediaPipe sont l'ajustement d'un modèle de main
    // GÉNÉRIQUE : métriques, mais biaisés pour une main donnée. Ce facteur
    // ramène l'échelle à la morphologie réelle. 1.0 = non calibré = rendu
    // inchangé ; borné pour qu'une calibration aberrante ne puisse pas
    // produire un bijou absurde.
    private val scaleCorrection: Float =
        ((args as? Map<*, *>)?.get("scaleCorrection") as? Number)
            ?.toFloat()?.coerceIn(0.7f, 1.4f) ?: 1.0f

    // --- Filtres One-Euro (mêmes constantes que la version Dart) -----------
    private val fX = OneEuroFilter(minCutoff = 1.2f, beta = 0.02f)
    private val fY = OneEuroFilter(minCutoff = 1.2f, beta = 0.02f)
    private val fHandDX = OneEuroFilter(minCutoff = 0.8f, beta = 0.01f)
    private val fHandDY = OneEuroFilter(minCutoff = 0.8f, beta = 0.01f)
    // Orientation : lissage plus fort que la position (minCutoff bas) — les
    // micro-oscillations d'angle donnent une impression de "flottement" du
    // bijou ; le beta laisse les vraies rotations de la main passer vite.
    // La normale (roulis) est la plus bruitée des mesures (logs : dérives
    // jusqu'à l'hémisphère arrière) → lissage encore plus fort.
    private val fAxis = Array(3) { OneEuroFilter(minCutoff = 0.6f, beta = 0.015f) }
    private val fNorm = Array(3) { OneEuroFilter(minCutoff = 0.35f, beta = 0.01f) }
    private var lastHandMs = 0L

    // Dernière normale dorsale NON filtrée (thread analyse uniquement) :
    // sert à garder un signe cohérent frame à frame pour que le bijou puisse
    // pivoter continûment avec la main (pas de saut à 90°).
    private var prevNormal: FloatArray? = null

    private var cameraProvider: ProcessCameraProvider? = null

    // --- Occlusion réelle (B.4) : cylindre proxy du bras/doigt qui n'écrit
    // que la PROFONDEUR (colorWrite=false). Invisible à l'écran (la caméra
    // reste visible à travers), mais tout fragment du bijou situé derrière
    // lui est masqué → la moitié arrière du bijou passe derrière la peau.
    private var occProvider: UbershaderProvider? = null
    private var occLoader: AssetLoader? = null
    private var occResourceLoader: ResourceLoader? = null
    private var occAsset: FilamentAsset? = null

    // --- État partagé détection → rendu (écrit par MediaPipe, lu au rendu).
    // Position cible [0..1] en espace d'affichage, déjà lissée + extrapolée.
    @Volatile
    private var smX = 0.5f
    @Volatile
    private var smY = 0.5f

    // Taille apparente de la main : composantes lissées du vecteur lm0→lm9
    // en unités normalisées d'image (converties en monde au rendu).
    @Volatile
    private var handDX = 0f
    @Volatile
    private var handDY = 0.3f

    // Axe principal du bijou et normale dorsale, lissés, en unités
    // normalisées d'image signées "affichage" (X miroir, Y haut, Z caméra).
    @Volatile
    private var axisVec = floatArrayOf(0f, 1f, 0f)
    @Volatile
    private var normVec = floatArrayOf(0f, 0f, 1f)

    @Volatile
    private var hasHand = false

    // Géométrie de la frame d'analyse (renseignée par [analyze]) : dimensions
    // capteur + rotation à appliquer pour redresser l'image. Les landmarks
    // MediaPipe restent dans l'espace capteur BRUT même quand on passe
    // rotationDegrees (piège vérifié) → la remise dans l'espace d'affichage
    // est faite par nous dans [onHands].
    @Volatile
    private var frameRotation = 0
    @Volatile
    private var frameW = 0
    @Volatile
    private var frameH = 0

    private var logCount = 0
    private var poseLogCount = 0
    private var renderLogCount = 0

    // Échelle lissée du bijou (boucle de rendu uniquement) ; 0 = à réinitialiser.
    private var smoothScale = 0f

    // Source de l'axe utilisée à la dernière détection (diagnostic).
    @Volatile
    private var lastAxisSource = "?"

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            choreographer.postFrameCallback(this)
            updateModelTransform()
            modelViewer.render(frameTimeNanos)
        }
    }

    init {
        val mp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        root.addView(previewView, mp)
        root.addView(surfaceView, mp)
        root.addView(guideView, mp)
        // Le guide ne concerne que le bracelet (cadrage du poignet).
        if (anchor != "wrist") guideView.visibility = View.GONE

        Utils.init()
        val manipulator = Manipulator.Builder()
            .targetPosition(0.0f, 0.0f, 0.0f)
            .orbitHomePosition(0.0f, 0.0f, kCameraDistance)
            .viewport(1, 1)
            .build(Manipulator.Mode.ORBIT)
        val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
            isOpaque = false
        }
        modelViewer = ModelViewer(surfaceView, uiHelper = uiHelper, manipulator = manipulator)
        modelViewer.view.blendMode = com.google.android.filament.View.BlendMode.TRANSLUCENT
        modelViewer.renderer.clearOptions = Renderer.ClearOptions().apply {
            clear = true
            clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        }

        loadModel()
        loadOccluder()
        loadEnvironment()
        setupHandLandmarker()
        setupPoseLandmarker()
        startCamera(activity)
        choreographer.postFrameCallback(frameCallback)
    }

    // --- Filament : pose complète (position + orientation + échelle) --------

    private fun updateModelTransform() {
        val asset = modelViewer.asset ?: return
        val tcm = modelViewer.engine.transformManager
        val inst = tcm.getInstance(asset.root)
        val vp = modelViewer.view.viewport
        if (vp.width == 0 || vp.height == 0) return
        val aspect = vp.width.toFloat() / vp.height.toFloat()
        // Projection imposée à CHAQUE frame, avec le FOV RÉEL de la caméra :
        // (1) ModelViewer écrase sa projection (focale 28 mm) à chaque resize ;
        // (2) surtout, la perspective du rendu doit être IDENTIQUE à celle de
        // la vidéo, sinon l'anneau paraît plat/collé à l'image. Le mapping
        // écran→monde (halfH ci-dessous) utilise le même FOV → cohérent.
        val fov = cameraVerticalFov
        modelViewer.camera.setProjection(
            Math.toDegrees(fov.toDouble()),
            aspect.toDouble(),
            kNearPlane,
            kFarPlane,
            Camera.Fov.VERTICAL,
        )
        // Guide de placement : visible tant que le suivi n'a pas verrouillé
        // main + mesure du bras (les essayages commerciaux imposent le même
        // cadrage) ; fondu de sortie quand tout est verrouillé.
        val segFreshNow = wristSeg != null &&
            SystemClock.uptimeMillis() - wristSegMs < kSegFreshMs
        if (anchor == "wrist") {
            val target = if (hasHand && segFreshNow) 0f else 1f
            guideView.alpha += kGuideFade * (target - guideView.alpha)
        }
        if (!hasHand) {
            tcm.setTransform(inst, kHiddenTransform)
            occAsset?.let { tcm.setTransform(tcm.getInstance(it.root), kHiddenTransform) }
            // À la prochaine acquisition, l'échelle repart de la mesure.
            smoothScale = 0f
            segOff = 0f
            smoothRoll = null
            return
        }
        // Demi-étendue du monde visible au plan du modèle (z=0), à distance
        // kCameraDistance. On mappe l'ancre [0..1] (origine haut-gauche) → monde.
        val halfH = kCameraDistance * tan(0.5f * fov)
        val halfW = halfH * aspect

        // Base orthonormée depuis les vecteurs MONDE (métriques, isotropes :
        // aucune correction d'aspect à faire, contrairement aux landmarks
        // normalisés d'image).
        val axis = normalized(
            axisVec,
            fallback = if (anchor == "wrist") floatArrayOf(0f, 1f, 0f) else floatArrayOf(0f, 0f, 1f),
        )
        var norm = normVec.copyOf()

        // Mesure fraîche de la silhouette du bras (masque de segmentation) ?
        val seg = wristSeg
        val segFresh = anchor == "wrist" && seg != null && segFreshNow

        // Recentrage latéral : ramène l'ancre sur le CENTRE mesuré de la
        // silhouette du bras, perpendiculairement à l'axe seulement (le long
        // de l'axe, l'ancre main garde le placement voulu du bracelet). Le
        // landmark poignet peut être biaisé vers un bord du bras — c'est ce
        // biais qui donne l'impression d'un bijou posé sur le dessus.
        var aX = smX
        var aY = smY
        run {
            // Perpendiculaire écran de l'axe : axe écran = (x, -y) → perp = (y, x).
            var p0 = axis[1]
            var p1 = axis[0]
            val pl = hypot(p0, p1)
            if (pl > 1e-4f) {
                p0 /= pl
                p1 /= pl
                if (segFresh) {
                    val off = (seg!![0] - smX) * p0 + (seg[1] - smY) * p1
                    segOff += kSegOffsetLerp *
                        (off.coerceIn(-kSegOffsetMax, kSegOffsetMax) - segOff)
                } else {
                    segOff *= 1f - kSegOffsetLerp
                }
                aX += segOff * p0
                aY += segOff * p1
            }
        }

        // Échelle : taille de la main en unités monde (le plein écran
        // normalisé [0..1] couvre 2·halfW × 2·halfH). La distance lm0–lm9 est
        // mesurée à l'ÉCRAN : quand la main s'incline vers la caméra elle se
        // raccourcit par perspective alors que le poignet garde sa largeur →
        // on divise par la fraction de l'axe restée dans le plan écran.
        val planar = hypot(axis[0], axis[1]).coerceAtLeast(kMinPlanarFactor)
        val worldHandLen =
            hypot(handDX * 2f * halfW, handDY * 2f * halfH) / planar
        // Échelle FORTEMENT lissée : les logs montraient s oscillant de ±20 %
        // (bruit de mesure + compensation de perspective) → le bracelet
        // "respirait", ce qui casse l'illusion d'objet rigide porté. La
        // taille réelle d'une main ne change pas : convergence lente.
        //
        // Bracelet : l'échelle est déduite du bras RÉEL. Priorité à la
        // largeur MESURÉE de la silhouette (masque de segmentation, comme les
        // essayages commerciaux) ; repli sur l'estimation anatomique depuis
        // la taille de main si la mesure n'est pas fraîche. Le Ø intérieur
        // du trou est ensuite posé à kBraceletClearance × cette largeur :
        // le bracelet prend exactement la taille du bras, l'entoure, et le
        // léger jeu + l'occlusion arrière créent l'effet de traversée.
        // NB : la largeur silhouette d'un cylindre perpendiculaire à son axe
        // projeté = son diamètre, quel que soit le basculement → pas de
        // correction de perspective à appliquer sur cette mesure.
        // La correction morphologique ne s'applique qu'aux estimations
        // GÉNÉRIQUES : la largeur lue sur le masque est déjà celle du bras
        // réel de l'utilisateur, la corriger la fausserait. Seul le repli
        // anatomique (ratio de population) et l'échelle de la bague (dérivée
        // de la taille de main du modèle générique) en ont besoin.
        //
        // ÉVASEMENT : la manchette est haute (2×kBandHalfHeight ≈ 9 cm de
        // bras) et l'avant-bras s'élargit sur cette longueur. Le rayon lu au
        // point de mesure proche n'est donc PAS celui qu'il faut au centre de
        // la bande, plus bas vers le coude. Deux largeurs mesurées donnent la
        // pente ; on évalue le rayon là où la bande se trouve réellement.
        val segFar = wristSegFar
        var taper = 0f // rayon gagné par unité monde vers le coude
        var rNearAlong = 0f // distance ancre → point proche, le long de -axis
        if (segFresh && segFar != null && anchor == "wrist") {
            val rN = 0.5f * hypot(seg!![2] * 2f * halfW, seg[3] * 2f * halfH)
            val rF = 0.5f * hypot(segFar[2] * 2f * halfW, segFar[3] * 2f * halfH)
            // Positions monde des deux points de mesure.
            val nX = (seg[0] - 0.5f) * 2f * halfW
            val nY = (0.5f - seg[1]) * 2f * halfH
            val fX2 = (segFar[0] - 0.5f) * 2f * halfW
            val fY2 = (0.5f - segFar[1]) * 2f * halfH
            // Écart le long de l'axe, compté vers le COUDE (-axis).
            val d = -((fX2 - nX) * axis[0] + (fY2 - nY) * axis[1])
            if (d > kMinTaperSpan) {
                taper = ((rF - rN) / d).coerceIn(0f, kMaxTaper)
                rNearAlong = -((nX - (aX - 0.5f) * 2f * halfW) * axis[0] +
                    (nY - (0.5f - aY) * 2f * halfH) * axis[1])
            }
        }

        val worldWristRadius = if (segFresh) {
            0.5f * hypot(seg!![2] * 2f * halfW, seg[3] * 2f * halfH)
        } else {
            0.5f * kWristWidthPerHand * worldHandLen * scaleCorrection
        }
        // Collier : le canal "taille de main" porte la largeur d'épaules. Le
        // rayon du cou en est déduit anatomiquement, et l'échelle est posée
        // pour que l'OUVERTURE du plastron (rayon intérieur mesuré sur le
        // GLB : 0.507 unité) épouse le cou.
        val worldShoulderW = hypot(handDX * 2f * halfW, handDY * 2f * halfH)
        val sRaw = if (anchor == "neck") {
            0.5f * kNeckWidthPerShoulder * worldShoulderW * scaleCorrection *
                kNecklaceClearance / kNecklaceInnerRadius
        } else if (anchor == "wrist") {
            // Le rayon voulu est celui au CENTRE de la bande, situé à
            // s·(kBandHalfHeight + kBandGap) vers le coude — mais ce centre
            // dépend de s, qui dépend du rayon. Plutôt que d'itérer :
            //   r = rProche + taper·(dAncre→proche + s·B)
            //   s = A·r,  A = clearance/rayonModèle,  B = demi-hauteur + jeu
            //   ⟹ s = A·(rProche + taper·dAncre→proche) / (1 − A·taper·B)
            // Le dénominateur ne s'annule que pour un évasement absurde, déjà
            // borné par kMaxTaper ; garde-fou malgré tout.
            val a = kBraceletClearance / kModelInnerRadius
            val b = kBandHalfHeight + kBandGap
            val denom = (1f - a * taper * b).coerceAtLeast(kMinTaperDenom)
            a * (worldWristRadius + taper * rNearAlong) / denom
        } else {
            worldHandLen * kRingPerHand * scaleCorrection
        }
        smoothScale = if (smoothScale <= 0f) sRaw
        else smoothScale + kScaleLerp * (sRaw - smoothScale)
        val s = smoothScale

        // Placement AXIAL mesuré : la manchette fait 2×kBandHalfHeight unités
        // de haut le long du bras (profil GLB mesuré : bande pleine hauteur,
        // rayon intérieur constant). Centrée sur le poignet, elle
        // chevaucherait la paume — c'est ce chevauchement qui donnait le
        // rendu "posé sur la main". On place son bord supérieur au pli du
        // poignet : centre = poignet + (demi-hauteur + marge) vers le coude
        // (-axis). La composante planaire brute de l'axe raccourcit
        // naturellement l'offset écran quand le bras plonge vers la caméra.
        // Collier : même piège que la manchette, en plus marqué — l'ouverture
        // du plastron est à Y=+0.90 dans le modèle, PAS à son centre. Centrer
        // la pièce sur le cou la placerait ~1 unité trop haut, l'anneau
        // au-dessus du menton. On descend donc l'origine du modèle de
        // 0.90×s sous le creux sternal : l'ouverture y affleure et la pièce
        // retombe sur la poitrine.
        val alongOff = when (anchor) {
            "wrist" -> s * (kBandHalfHeight + kBandGap)
            "neck" -> s * kNecklaceOpeningY
            else -> 0f
        }
        val wx = (aX - 0.5f) * 2f * halfW - axis[0] * alongOff
        val wy = (0.5f - aY) * 2f * halfH - axis[1] * alongOff
        // Gram-Schmidt : composante de la normale orthogonale à l'axe.
        val d = axis[0] * norm[0] + axis[1] * norm[1] + axis[2] * norm[2]
        norm = normalized(
            floatArrayOf(norm[0] - d * axis[0], norm[1] - d * axis[1], norm[2] - d * axis[2]),
            fallback = anyPerpendicular(axis),
        )

        // --- Roulis du bracelet contraint par la silhouette MESURÉE ---------
        // La normale de paume est la mesure la plus bruitée du pipeline (d'où
        // fNorm à minCutoff 0.35) et surtout elle est mal CONTRAINTE quand la
        // main est à plat face caméra : les deux bords lm0→lm5 et lm0→lm17
        // sont alors presque colinéaires en projection, leur produit vectoriel
        // part au bruit et le bracelet pivote autour de l'avant-bras.
        //
        // Le masque de segmentation donne bien mieux : le vecteur pleine
        // largeur de la silhouette EST la projection écran de l'axe
        // médio-latéral du poignet — le grand axe de son ellipse. On impose
        // donc colX (la largeur du bijou) le long de ce vecteur, et on ne
        // garde de la normale de paume que le SIGNE (quelle face regarde la
        // caméra). Mesure directe plutôt qu'estimation dérivée.
        if (anchor == "wrist" && segFresh) {
            // seg[2..3] : écran normalisé, Y vers le BAS → monde : Y inversé.
            val u = floatArrayOf(seg!![2] * 2f * halfW, -seg[3] * 2f * halfH, 0f)
            // Composante orthogonale à l'axe du bras (le grand axe de
            // l'ellipse est perpendiculaire à l'avant-bras par construction,
            // mais la mesure et l'axe viennent de deux détecteurs différents).
            val du = axis[0] * u[0] + axis[1] * u[1] + axis[2] * u[2]
            val o = floatArrayOf(
                u[0] - du * axis[0],
                u[1] - du * axis[1],
                u[2] - du * axis[2],
            )
            if (sqrt(o[0] * o[0] + o[1] * o[1] + o[2] * o[2]) > kMinRollNorm) {
                var c = normalized(o, fallback = floatArrayOf(1f, 0f, 0f))
                // Lever l'ambiguïté ±180° de l'axe de largeur : garder le sens
                // qui met la face avant du bijou (cross(colX, axe)) côté caméra.
                if (cross(c, axis)[2] < 0f) c = floatArrayOf(-c[0], -c[1], -c[2])
                val sm = smoothRoll
                smoothRoll = if (sm == null) {
                    c
                } else {
                    normalized(
                        floatArrayOf(
                            sm[0] + kRollLerp * (c[0] - sm[0]),
                            sm[1] + kRollLerp * (c[1] - sm[1]),
                            sm[2] + kRollLerp * (c[2] - sm[2]),
                        ),
                        fallback = c,
                    )
                }
            }
        }

        // Convention des modèles Meshy (cf. mémoire projet) :
        //  - bague : axe du trou = +Z modèle, pierre = +Y → Z→doigt, Y→dos de main ;
        //  - bracelet : axe du trou = +Y modèle → Y→avant-bras, Z→caméra.
        // Le tangage +90° appliqué en V1 est absorbé par ce mapping direct.
        val colX: FloatArray
        val colY: FloatArray
        val colZ: FloatArray
        val roll = if (anchor == "wrist") smoothRoll else null
        if (anchor == "neck") {
            // norm porte déjà la ligne d'épaules orthogonalisée contre l'axe
            // du cou (cf. onPoseNeck) : c'est colX. Aucune normale dérivée
            // n'intervient, donc pas de roulis qui dérive.
            colY = axis
            colX = norm
            colZ = cross(colX, colY)
        } else if (roll != null) {
            // Base pilotée par la silhouette : colX = largeur mesurée du
            // poignet, ré-orthogonalisée (l'axe du bras a pu bouger depuis la
            // dernière mesure de masque, qui tourne ~2× moins vite).
            colY = axis
            val dr = axis[0] * roll[0] + axis[1] * roll[1] + axis[2] * roll[2]
            colX = normalized(
                floatArrayOf(
                    roll[0] - dr * axis[0],
                    roll[1] - dr * axis[1],
                    roll[2] - dr * axis[2],
                ),
                fallback = cross(axis, norm),
            )
            colZ = cross(colX, colY)
        } else {
            if (anchor == "wrist") {
                colY = axis
                colZ = norm
            } else {
                colY = norm
                colZ = axis
            }
            colX = cross(colY, colZ)
        }

        // Colonne-major : m = T · R · S.
        val m = floatArrayOf(
            colX[0] * s, colX[1] * s, colX[2] * s, 0f,
            colY[0] * s, colY[1] * s, colY[2] * s, 0f,
            colZ[0] * s, colZ[1] * s, colZ[2] * s, 0f,
            wx, wy, 0f, 1f,
        )
        tcm.setTransform(inst, m)

        if (renderLogCount++ % 120 == 0) {
            Log.i(
                TAG,
                "rendu: fov=${Math.toDegrees(fov.toDouble()).toInt()}° " +
                    "halfH=$halfH s=$s handLen=$worldHandLen planar=$planar " +
                    "poignetR=$worldWristRadius " +
                    "(${if (segFresh) "mesuré masque" else "estimé main"}) " +
                    "évasement=$taper dAncreProche=$rNearAlong " +
                    "recentrage=$segOff " +
                    "axe=(${axis[0]}, ${axis[1]}, ${axis[2]}) " +
                    "roulis=${if (roll != null) "silhouette" else "normale paume"} " +
                    "colX=(${colX[0]}, ${colX[1]}, ${colX[2]}) " +
                    "norm=(${norm[0]}, ${norm[1]}, ${norm[2]}) pos=($wx, $wy)",
            )
        }

        // --- Occluseur : cylindre aligné sur l'axe du trou du bijou, aux
        // dimensions du membre (ellipse du poignet / cylindre du doigt).
        val occ = occAsset
        if (occ != null) {
            // Axe du cylindre (Y du modèle occluseur) = axe du trou ; les
            // deux autres colonnes portent les rayons radiaux.
            val axisDir: FloatArray
            val radial: FloatArray
            if (anchor == "wrist" || anchor == "neck") {
                axisDir = colY
                radial = colZ
            } else {
                axisDir = colZ
                radial = colY
            }
            // Bracelet : l'occluseur représente le POIGNET RÉEL (ellipse
            // anatomique), plus le bijou — il est dimensionné sur le rayon du
            // poignet (retrouvé depuis l'échelle lissée pour rester
            // rigidement solidaire du bracelet), avec une petite marge pour
            // couvrir la silhouette. kOccluderMargin < kBraceletClearance
            // garantit qu'il reste à l'intérieur du trou (rayon 0.652×s).
            val r1: Float
            val r2: Float
            val len: Float
            if (anchor == "neck") {
                // Le cou masque l'arrière du collier. Rayon retrouvé depuis
                // l'échelle (donc rigidement solidaire de la pièce), section
                // légèrement aplatie d'avant en arrière comme un vrai cou.
                r1 = s * kNecklaceInnerRadius / kNecklaceClearance *
                    kOccluderMargin
                r2 = r1 * kNeckDepthRatio
                len = s * kNeckOccLen
            } else if (anchor == "wrist") {
                val wristR = s * kModelInnerRadius / kBraceletClearance
                r1 = wristR * kOccluderMargin
                r2 = r1 * kWristDepthRatio
                len = s * kWristOccLen
            } else {
                r1 = s * kRingOccRadius
                r2 = s * kRingOccRadius
                len = s * kRingOccLen
            }
            // L'occluseur suit le MEMBRE, pas l'origine du modèle. Pour la
            // manchette et la bague les deux coïncident ; pour le collier
            // l'origine a été descendue de alongOff sous le creux sternal,
            // alors que le cou à masquer est au-dessus → on remonte.
            val occRise = if (anchor == "neck") alongOff + s * kNeckOccRise else 0f
            val ox = wx + axis[0] * occRise
            val oy = wy + axis[1] * occRise
            val om = floatArrayOf(
                colX[0] * r1, colX[1] * r1, colX[2] * r1, 0f,
                axisDir[0] * len, axisDir[1] * len, axisDir[2] * len, 0f,
                radial[0] * r2, radial[1] * r2, radial[2] * r2, 0f,
                ox, oy, 0f, 1f,
            )
            tcm.setTransform(tcm.getInstance(occ.root), om)
        }
    }

    // --- MediaPipe ---------------------------------------------------------

    private fun setupHandLandmarker() {
        // Le collier ne dépend que de la pose (épaules + tête) : pas de main
        // dans le cadrage, et un 2e détecteur coûterait du CPU pour rien.
        if (anchor == "neck") return
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .build()
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .setResultListener { result, _ -> onHands(result) }
                .setErrorListener { e -> Log.e(TAG, "MediaPipe error: ${e.message}") }
                .build()
            handLandmarker = HandLandmarker.createFromOptions(activity, options)
            Log.i(TAG, "HandLandmarker prêt")
        } catch (e: Exception) {
            Log.e(TAG, "échec init HandLandmarker", e)
        }
    }

    /** Convertit un vecteur de l'espace image MediaPipe (capteur brut) vers
     *  l'espace d'affichage : rotation capteur→écran (version vecteur des
     *  formules de toScreen), puis miroir X, Y bas→haut, Z vers la caméra. */
    private fun displayVec(dx: Float, dy: Float, dz: Float, rot: Int): FloatArray {
        val ux: Float
        val uy: Float
        when (rot) {
            90 -> { ux = -dy; uy = dx }
            180 -> { ux = -dx; uy = -dy }
            270 -> { ux = dy; uy = -dx }
            else -> { ux = dx; uy = dy }
        }
        return floatArrayOf(-ux, -uy, -dz)
    }

    private fun setupPoseLandmarker() {
        // Manchette : axe de l'avant-bras. Collier : épaules + tête, c'est le
        // détecteur PRINCIPAL (aucune main dans le cadrage). La bague suit le
        // doigt et n'a pas besoin de la pose.
        if (anchor != "wrist" && anchor != "neck") return
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(POSE_MODEL_ASSET)
                .build()
            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                // Masque de segmentation personne : sert à mesurer la largeur
                // RÉELLE de la silhouette du bras (dimensionnement du bracelet
                // et de l'occluseur sur le bras réel, pas sur une estimation).
                .setOutputSegmentationMasks(true)
                .setResultListener { result, _ -> onPose(result) }
                .setErrorListener { e -> Log.e(TAG, "Pose error: ${e.message}") }
                .build()
            poseLandmarker = PoseLandmarker.createFromOptions(activity, options)
            Log.i(TAG, "PoseLandmarker prêt")
        } catch (e: Exception) {
            Log.e(TAG, "échec init PoseLandmarker", e)
        }
    }

    /** Extrait l'axe réel de l'avant-bras (coude→poignet) côté du bras qui
     *  porte la main détectée.
     *
     *  Les x/y viennent des landmarks IMAGE de la pose, convertis par la même
     *  chaîne (rotation + recadrage + miroir) que le suivi main — conventions
     *  PROUVÉES à l'écran — car l'orientation du repère des world landmarks
     *  de POSE (centré hanches) n'est pas fiable : l'utiliser directement
     *  affichait le bracelet à l'horizontale. Seule la profondeur z
     *  (invariante à la rotation d'image) vient des world landmarks, remise
     *  à l'échelle écran via le rapport des longueurs planaires. */
    private fun onPose(result: PoseLandmarkerResult) {
        if (anchor == "neck") {
            onPoseNeck(result)
            return
        }
        val lms = result.landmarks()
        if (lms.isEmpty()) return
        val lm = lms[0]
        if (lm.size < 17) return
        // Indices Pose : 13/14 = coude gauche/droit, 15/16 = poignet g./d.
        // Choix du bras : poignet pose le plus proche du poignet main, en
        // espace image BRUT (mêmes conventions des deux détecteurs).
        val hw = handWristImg
        fun d2(i: Int): Float {
            val dx = lm[i].x() - hw[0]
            val dy = lm[i].y() - hw[1]
            return dx * dx + dy * dy
        }
        val leftD = d2(15)
        val rightD = d2(16)
        // Poignet pose trop loin du poignet main = mauvaise détection.
        if (minOf(leftD, rightD) > kPoseMaxWristDistSq) return
        val wristIdx = if (leftD <= rightD) 15 else 16
        val elbowIdx = if (leftD <= rightD) 13 else 14

        // Conversion image→écran identique à toScreen (recalculée ici : les
        // facteurs de recadrage dépendent de la géométrie de frame courante).
        val rot = frameRotation
        val uprightW = if (rot == 90 || rot == 270) frameH else frameW
        val uprightH = if (rot == 90 || rot == 270) frameW else frameH
        var cx = 1f
        var cy = 1f
        if (uprightW > 0 && uprightH > 0 && root.width > 0 && root.height > 0) {
            val imgAspect = uprightW.toFloat() / uprightH
            val viewAspect = root.width.toFloat() / root.height
            if (imgAspect > viewAspect) cx = imgAspect / viewAspect
            else cy = viewAspect / imgAspect
        }
        // DIAGNOSTIC VÉRIFIÉ SUR DEVICE : contrairement aux mains (espace
        // capteur brut), les landmarks du task POSE arrivent DÉJÀ redressés
        // (portrait). Comme cette convention peut varier selon device ou
        // version, on calcule les DEUX candidats (avec/sans rotation) et on
        // garde celui qui s'accorde le mieux à l'axe courant du bijou.
        fun toScr(x: Float, y: Float, applyRot: Boolean): FloatArray {
            val ux: Float
            val uy: Float
            if (applyRot) {
                when (rot) {
                    90 -> { ux = 1f - y; uy = x }
                    180 -> { ux = 1f - x; uy = 1f - y }
                    270 -> { ux = y; uy = 1f - x }
                    else -> { ux = x; uy = y }
                }
            } else {
                ux = x
                uy = y
            }
            return floatArrayOf(
                1f - (0.5f + (ux - 0.5f) * cx),
                0.5f + (uy - 0.5f) * cy,
            )
        }

        fun candidate(applyRot: Boolean): FloatArray {
            val pw = toScr(lm[wristIdx].x(), lm[wristIdx].y(), applyRot)
            val pe = toScr(lm[elbowIdx].x(), lm[elbowIdx].y(), applyRot)
            return floatArrayOf(pw[0] - pe[0], -(pw[1] - pe[1]), 0f)
        }

        val ref = normalized(axisVec, fallback = floatArrayOf(0f, 1f, 0f))
        fun score(c: FloatArray): Float {
            val n = normalized(c, fallback = floatArrayOf(0f, 0f, 0f))
            return n[0] * ref[0] + n[1] * ref[1] + n[2] * ref[2]
        }

        val candRot = candidate(true)
        val candUpright = candidate(false)
        val useRot = score(candRot) >= score(candUpright)
        val chosen = if (useRot) candRot else candUpright

        // Profondeur depuis les world landmarks (z invariant à la rotation
        // d'image), remise à l'échelle écran par le rapport des longueurs.
        val wls = result.worldLandmarks()
        if (wls.isNotEmpty() && wls[0].size > 16) {
            val w = wls[0]
            val wdz = w[wristIdx].z() - w[elbowIdx].z()
            val wPlanar = hypot(
                w[wristIdx].x() - w[elbowIdx].x(),
                w[wristIdx].y() - w[elbowIdx].y(),
            )
            val sPlanar = hypot(chosen[0], chosen[1])
            if (wPlanar > 1e-4f) chosen[2] = -wdz * (sPlanar / wPlanar)
        }
        poseAxis = chosen
        poseAxisMs = SystemClock.uptimeMillis()

        // --- Largeur RÉELLE du bras via le masque de segmentation -----------
        // Balaye le masque personne perpendiculairement à l'axe coude→poignet
        // au point où se porte le bracelet : donne la pleine largeur de la
        // silhouette du bras ET son centre — le bracelet est ensuite
        // dimensionné/centré sur le bras réel (technique des essayages
        // commerciaux), plus sur un ratio anatomique estimé.
        val masksOpt = result.segmentationMasks()
        if (masksOpt.isPresent && masksOpt.get().isNotEmpty()) {
            val mask = masksOpt.get()[0]
            val bb = ByteBufferExtractor.extract(mask)
            bb.order(ByteOrder.nativeOrder())
            val fb = bb.asFloatBuffer()
            val mw = mask.width
            val mh = mask.height

            // L'espace du masque peut suivre les landmarks (déjà redressés
            // sur ce device) OU rester capteur brut — même famille de pièges
            // que les rotations de landmarks. On sonde le poignet dans les
            // deux conventions et on garde celle où il tombe dans la personne.
            fun toRawSpace(x: Float, y: Float): FloatArray = when (rot) {
                90 -> floatArrayOf(y, 1f - x)
                180 -> floatArrayOf(1f - x, 1f - y)
                270 -> floatArrayOf(1f - y, x)
                else -> floatArrayOf(x, y)
            }
            fun sample(x: Float, y: Float, raw: Boolean): Float {
                val p = if (raw) toRawSpace(x, y) else floatArrayOf(x, y)
                val xi = (p[0] * mw).toInt()
                val yi = (p[1] * mh).toInt()
                if (xi < 0 || xi >= mw || yi < 0 || yi >= mh) return 0f
                return fb.get(yi * mw + xi)
            }

            val wl = lm[wristIdx]
            val el = lm[elbowIdx]
            // La convention d'espace du masque est sondée au point PROCHE
            // (toujours sur le bras si la détection est bonne).
            val nx = wl.x() + (el.x() - wl.x()) * kScanNear
            val ny = wl.y() + (el.y() - wl.y()) * kScanNear
            val rawMode: Boolean? = when {
                sample(nx, ny, false) >= 0.5f -> false
                sample(nx, ny, true) >= 0.5f -> true
                else -> null
            }

            // Direction avant-bras en pixels masque (anisotropie corrigée),
            // pas de balayage = 1 pixel.
            val fxp = (wl.x() - el.x()) * mw
            val fyp = (wl.y() - el.y()) * mh
            val fl = hypot(fxp, fyp)

            /** Pleine largeur de la silhouette à [frac] le long de
             *  poignet→coude, en espace écran : [midX, midY, dX, dY].
             *  null = bord non trouvé (balayage parti dans le torse ou
             *  l'autre bras) ou largeur invraisemblable. */
            fun measureAt(frac: Float): FloatArray? {
                if (rawMode == null || fl <= 1e-3f) return null
                val cxN = wl.x() + (el.x() - wl.x()) * frac
                val cyN = wl.y() + (el.y() - wl.y()) * frac
                val stepX = -(fyp / fl) / mw
                val stepY = (fxp / fl) / mh
                val maxSteps = (kSegMaxWidthFrac * maxOf(mw, mh)).toInt()
                fun march(sign: Int): Int {
                    var miss = 0
                    var i = 1
                    while (i <= maxSteps) {
                        val v = sample(
                            cxN + sign * i * stepX,
                            cyN + sign * i * stepY,
                            rawMode,
                        )
                        if (v < 0.5f) {
                            if (++miss >= 3) return i - miss
                        } else {
                            miss = 0
                        }
                        i++
                    }
                    return maxSteps
                }
                val dPlus = march(1)
                val dMinus = march(-1)
                if (dPlus >= maxSteps || dMinus >= maxSteps) return null
                val e1 = toScr(cxN + dPlus * stepX, cyN + dPlus * stepY, useRot)
                val e2 = toScr(cxN - dMinus * stepX, cyN - dMinus * stepY, useRot)
                val v = floatArrayOf(
                    (e1[0] + e2[0]) * 0.5f,
                    (e1[1] + e2[1]) * 0.5f,
                    e1[0] - e2[0],
                    e1[1] - e2[1],
                )
                val w = hypot(v[2], v[3])
                return if (w in kSegMinWidthN..kSegMaxWidthN) v else null
            }

            val near = measureAt(kScanNear)
            if (near != null) {
                wristSeg = near
                // La mesure lointaine sert au seul évasement : elle peut
                // échouer (manche, bras hors cadre) sans invalider la proche.
                wristSegFar = measureAt(kScanFar)
                wristSegMs = SystemClock.uptimeMillis()
            }
            if (poseLogCount % 15 == 0) {
                val nw = near?.let { hypot(it[2], it[3]) } ?: -1f
                val fw = wristSegFar?.let { hypot(it[2], it[3]) } ?: -1f
                Log.i(
                    TAG,
                    "seg: mode=${rawMode ?: "hors masque"} " +
                        "largeurProche=$nw largeurLoin=$fw " +
                        "centre=(${near?.get(0)}, ${near?.get(1)}) " +
                        "masque=${mw}x$mh",
                )
            }
        }

        if (poseLogCount++ % 15 == 0) {
            Log.i(
                TAG,
                "pose: rot=$rot bras=${if (wristIdx == 15) "G" else "D"} " +
                    "candRot=(${candRot[0]}, ${candRot[1]}) score=${score(candRot)} " +
                    "candDroit=(${candUpright[0]}, ${candUpright[1]}) " +
                    "score=${score(candUpright)} choisi=${if (useRot) "rot" else "droit"} " +
                    "axe=(${chosen[0]}, ${chosen[1]}, ${chosen[2]})",
            )
        }
    }

    /** Suivi du COLLIER : la pose est ici le détecteur principal (il n'y a
     *  pas de main dans le cadrage), donc cette fonction écrit directement
     *  tout l'état lu par la boucle de rendu — ancre, axe, roulis, taille.
     *
     *  Repères choisis, et pourquoi :
     *   - ancre = milieu des épaules (11/12) ≈ creux sternal, là où repose
     *     l'ouverture d'un plastron ;
     *   - axe du trou (+Y modèle) = milieu des épaules → milieu des oreilles
     *     (7/8) : l'axe réel du cou, insensible à l'inclinaison de la tête
     *     autour de son propre axe ;
     *   - roulis : la LIGNE D'ÉPAULES donne colX directement. C'est le même
     *     levier que la silhouette pour la manchette — une mesure franche
     *     plutôt qu'une normale dérivée, donc pas de dérive de roulis. */
    private fun onPoseNeck(result: PoseLandmarkerResult) {
        val lms = result.landmarks()
        if (lms.isEmpty()) {
            hasHand = false
            return
        }
        val lm = lms[0]
        if (lm.size < 13) {
            hasHand = false
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastHandMs > kResetAfterMs) resetFilters()
        lastHandMs = now

        val rot = frameRotation
        val uprightW = if (rot == 90 || rot == 270) frameH else frameW
        val uprightH = if (rot == 90 || rot == 270) frameW else frameH
        var cx = 1f
        var cy = 1f
        if (uprightW > 0 && uprightH > 0 && root.width > 0 && root.height > 0) {
            val imgAspect = uprightW.toFloat() / uprightH
            val viewAspect = root.width.toFloat() / root.height
            if (imgAspect > viewAspect) cx = imgAspect / viewAspect
            else cy = viewAspect / imgAspect
        }

        fun toScr(x: Float, y: Float, applyRot: Boolean): FloatArray {
            val ux: Float
            val uy: Float
            if (applyRot) {
                when (rot) {
                    90 -> { ux = 1f - y; uy = x }
                    180 -> { ux = 1f - x; uy = 1f - y }
                    270 -> { ux = y; uy = 1f - x }
                    else -> { ux = x; uy = y }
                }
            } else {
                ux = x
                uy = y
            }
            return floatArrayOf(
                1f - (0.5f + (ux - 0.5f) * cx),
                0.5f + (uy - 0.5f) * cy,
            )
        }

        // Même piège que pour l'avant-bras : selon device/version, les
        // landmarks de pose arrivent redressés ou en espace capteur brut. On
        // calcule les deux conventions et on garde celle dont l'axe du cou
        // pointe le plus "vers le haut" de l'écran — un cou filmé de face
        // monte toujours, ce qui rend le test fiable sans référence externe.
        fun neckUp(applyRot: Boolean): FloatArray {
            val sl = toScr(lm[11].x(), lm[11].y(), applyRot)
            val sr = toScr(lm[12].x(), lm[12].y(), applyRot)
            val el = toScr(lm[7].x(), lm[7].y(), applyRot)
            val er = toScr(lm[8].x(), lm[8].y(), applyRot)
            val sx = (sl[0] + sr[0]) * 0.5f
            val sy = (sl[1] + sr[1]) * 0.5f
            val hx = (el[0] + er[0]) * 0.5f
            val hy = (el[1] + er[1]) * 0.5f
            return floatArrayOf(hx - sx, -(hy - sy), 0f)
        }

        val upRot = neckUp(true)
        val upStraight = neckUp(false)
        val useRot = normalized(upRot, floatArrayOf(0f, 0f, 0f))[1] >=
            normalized(upStraight, floatArrayOf(0f, 0f, 0f))[1]

        val shL = toScr(lm[11].x(), lm[11].y(), useRot)
        val shR = toScr(lm[12].x(), lm[12].y(), useRot)
        val shMidX = (shL[0] + shR[0]) * 0.5f
        val shMidY = (shL[1] + shR[1]) * 0.5f

        var up = normalized(
            if (useRot) upRot else upStraight,
            fallback = floatArrayOf(0f, 1f, 0f),
        )
        // Profondeur de l'axe du cou depuis les world landmarks (z invariant
        // à la rotation d'image), remise à l'échelle écran par le rapport des
        // longueurs planaires — même recette que l'avant-bras.
        val wls = result.worldLandmarks()
        if (wls.isNotEmpty() && wls[0].size > 12) {
            val w = wls[0]
            val wdz = (w[7].z() + w[8].z()) * 0.5f -
                (w[11].z() + w[12].z()) * 0.5f
            val wPlanar = hypot(
                (w[7].x() + w[8].x()) * 0.5f - (w[11].x() + w[12].x()) * 0.5f,
                (w[7].y() + w[8].y()) * 0.5f - (w[11].y() + w[12].y()) * 0.5f,
            )
            val sPlanar = hypot(up[0], up[1])
            if (wPlanar > 1e-4f && sPlanar > 1e-4f) {
                up = normalized(
                    floatArrayOf(up[0], up[1], -wdz * (sPlanar / wPlanar)),
                    fallback = up,
                )
            }
        }

        // Ligne d'épaules → colX. Orthogonalisée contre l'axe du cou, puis
        // orientée pour que colZ = colX × colY sorte vers la caméra (le motif
        // décoré du plastron est en +Z, cf. mesure du GLB : 356 k sommets
        // avant contre 78 k arrière).
        val shVec = floatArrayOf(shR[0] - shL[0], -(shR[1] - shL[1]), 0f)
        val dsh = shVec[0] * up[0] + shVec[1] * up[1] + shVec[2] * up[2]
        var side = normalized(
            floatArrayOf(
                shVec[0] - dsh * up[0],
                shVec[1] - dsh * up[1],
                shVec[2] - dsh * up[2],
            ),
            fallback = anyPerpendicular(up),
        )
        if (cross(side, up)[2] < 0f) {
            side = floatArrayOf(-side[0], -side[1], -side[2])
        }

        // Ancre : creux sternal, légèrement au-dessus du milieu des épaules.
        val shoulderW = hypot(shR[0] - shL[0], shR[1] - shL[1])
        val ax = (shMidX - up[0] * kNeckRise * shoulderW).coerceIn(0f, 1f)
        val ay = (shMidY + up[1] * kNeckRise * shoulderW).coerceIn(0f, 1f)

        val px = fX.filter(ax, now)
        val py = fY.filter(ay, now)
        smX = (px + (fX.velocity * kLeadSeconds).coerceIn(-kMaxLead, kMaxLead))
            .coerceIn(0f, 1f)
        smY = (py + (fY.velocity * kLeadSeconds).coerceIn(-kMaxLead, kMaxLead))
            .coerceIn(0f, 1f)

        axisVec = floatArrayOf(
            fAxis[0].filter(up[0], now),
            fAxis[1].filter(up[1], now),
            fAxis[2].filter(up[2], now),
        )
        normVec = floatArrayOf(
            fNorm[0].filter(side[0], now),
            fNorm[1].filter(side[1], now),
            fNorm[2].filter(side[2], now),
        )
        // Réutilise le canal "taille de main" pour la largeur d'épaules :
        // c'est la grandeur d'échelle de repli du collier.
        handDX = fHandDX.filter(shR[0] - shL[0], now)
        handDY = fHandDY.filter(shR[1] - shL[1], now)
        hasHand = true

        if (poseLogCount++ % 15 == 0) {
            Log.i(
                TAG,
                "cou: conv=${if (useRot) "rot" else "droit"} " +
                    "ancre=($smX, $smY) épaules=$shoulderW " +
                    "haut=(${axisVec[0]}, ${axisVec[1]}, ${axisVec[2]}) " +
                    "côté=(${normVec[0]}, ${normVec[1]}, ${normVec[2]})",
            )
        }
    }

    private fun onHands(result: HandLandmarkerResult) {
        val hands = result.landmarks()
        if (hands.isEmpty() || hands[0].size < 21) {
            hasHand = false
            return
        }
        val lm = hands[0]
        val now = SystemClock.uptimeMillis()
        // Main perdue puis retrouvée : repartir de zéro plutôt que de laisser
        // les filtres "rattraper" lentement l'ancienne position.
        if (now - lastHandMs > kResetAfterMs) resetFilters()
        lastHandMs = now
        // Poignet en espace image brut, pour le choix gauche/droite côté pose.
        handWristImg = floatArrayOf(lm[0].x(), lm[0].y())

        // --- Conversion espace capteur → espace écran -----------------------
        // 1) Rotation : les landmarks sont dans le buffer capteur brut
        //    (paysage) ; frameRotation (270° en frontal ici) les redresse.
        // 2) Recadrage : PreviewView (FILL_CENTER) rogne l'image 4:3 pour
        //    remplir l'écran ~20:9 → facteurs cx/cy.
        // 3) Miroir : l'aperçu frontal est affiché miroir, les landmarks non.
        val rot = frameRotation
        val uprightW = if (rot == 90 || rot == 270) frameH else frameW
        val uprightH = if (rot == 90 || rot == 270) frameW else frameH
        var cx = 1f
        var cy = 1f
        if (uprightW > 0 && uprightH > 0 && root.width > 0 && root.height > 0) {
            val imgAspect = uprightW.toFloat() / uprightH
            val viewAspect = root.width.toFloat() / root.height
            if (imgAspect > viewAspect) cx = imgAspect / viewAspect
            else cy = viewAspect / imgAspect
        }

        // Point normalisé [0..1] en coordonnées écran (origine haut-gauche).
        fun toScreen(l: NormalizedLandmark): FloatArray {
            val ux: Float
            val uy: Float
            when (rot) {
                90 -> { ux = 1f - l.y(); uy = l.x() }
                180 -> { ux = 1f - l.x(); uy = 1f - l.y() }
                270 -> { ux = l.y(); uy = 1f - l.x() }
                else -> { ux = l.x(); uy = l.y() }
            }
            // Recadrage centré puis miroir horizontal.
            return floatArrayOf(
                1f - (0.5f + (ux - 0.5f) * cx),
                0.5f + (uy - 0.5f) * cy,
            )
        }

        // Vecteur b→a en espace "monde écran" : X écran (déjà miroir), Y
        // inversé (écran vers le bas → monde vers le haut), Z MediaPipe
        // (négatif vers la caméra) → monde (positif vers la caméra).
        fun screenDelta(a: NormalizedLandmark, b: NormalizedLandmark): FloatArray {
            val pa = toScreen(a)
            val pb = toScreen(b)
            return floatArrayOf(pa[0] - pb[0], -(pa[1] - pb[1]), -(a.z() - b.z()))
        }

        val rx: Float
        val ry: Float
        if (anchor == "wrist") {
            // Bracelet : ancre au PLI du poignet (lm0). Le décalage vers
            // l'avant-bras n'est plus un offset écran arbitraire : il est
            // calculé au rendu depuis la demi-hauteur RÉELLE de la manchette
            // (kBandHalfHeight × s) pour que son bord supérieur affleure le
            // pli — centrée sur le poignet, la manchette (1.85 unités de
            // haut !) chevauchait la paume → rendu "posé sur la main".
            val p0 = toScreen(lm[0])
            rx = p0[0]
            ry = p0[1]
        } else {
            // Bague : base de l'annulaire = milieu des landmarks 13 et 14.
            val p13 = toScreen(lm[13])
            val p14 = toScreen(lm[14])
            rx = (p13[0] + p14[0]) / 2f
            ry = (p13[1] + p14[1]) / 2f
        }

        val px = fX.filter(rx.coerceIn(0f, 1f), now)
        val py = fY.filter(ry.coerceIn(0f, 1f), now)
        // Extrapolation : position filtrée + vitesse lissée × avance, bornée
        // (compense la latence détection async + rendu, cf. version Dart).
        smX = (px + (fX.velocity * kLeadSeconds).coerceIn(-kMaxLead, kMaxLead))
            .coerceIn(0f, 1f)
        smY = (py + (fY.velocity * kLeadSeconds).coerceIn(-kMaxLead, kMaxLead))
            .coerceIn(0f, 1f)

        // --- Orientation 6DoF depuis les landmarks MONDE -------------------
        // Les world landmarks sont métriques (mètres, modèle 3D de main
        // ajusté) : bien plus stables en profondeur que le z des landmarks
        // écran → c'est eux qui font que le bijou pivote comme un objet réel.
        val wls = result.worldLandmarks()
        if (wls.isNotEmpty() && wls[0].size >= 21) {
            val w = wls[0]
            // Vecteur b→a en espace "monde écran" : rotation capteur→écran
            // (mêmes formules que toScreen, version vecteur), puis miroir X,
            // Y bas→haut et Z vers la caméra positif.
            fun worldDelta(a: Landmark, b: Landmark): FloatArray =
                displayVec(a.x() - b.x(), a.y() - b.y(), a.z() - b.z(), rot)

            val axisRaw: FloatArray
            if (anchor == "wrist") {
                // Axe du trou du bracelet = AXE DE L'AVANT-BRAS (coude→poignet
                // via Pose Landmarker) : un vrai bracelet reste aligné sur
                // l'avant-bras même quand le poignet fléchit. GARDE-FOU : si
                // l'axe pose diverge trop de l'axe main (>~60°), c'est une
                // détection/convention douteuse → repli sur l'axe main (qui a
                // fait ses preuves). Idem si la pose n'est pas fraîche.
                val handAxis = worldDelta(w[9], w[0])
                val pa = poseAxis
                if (pa != null && now - poseAxisMs < kPoseFreshMs) {
                    val pn = normalized(pa, fallback = handAxis)
                    val hn = normalized(handAxis, fallback = pn)
                    val dot = pn[0] * hn[0] + pn[1] * hn[1] + pn[2] * hn[2]
                    if (dot >= kPoseCoherenceDot) {
                        axisRaw = pa
                        lastAxisSource = "pose"
                    } else {
                        axisRaw = handAxis
                        lastAxisSource = "main(pose rejetée dot=$dot)"
                    }
                } else {
                    axisRaw = handAxis
                    lastAxisSource = "main(pose absente)"
                }
            } else {
                // Axe du trou de la bague = direction du doigt (base→phalange).
                axisRaw = worldDelta(w[14], w[13])
                lastAxisSource = "doigt"
            }
            // Normale de la paume : produit vectoriel des deux bords de la
            // main (poignet→index lm5, poignet→auriculaire lm17).
            var normN = normalized(
                cross(worldDelta(w[5], w[0]), worldDelta(w[17], w[0])),
                fallback = normVec,
            )
            // Signe : cohérence temporelle (le bijou peut pivoter continûment
            // avec la main, sans saut à 90°). À l'acquisition, face bijou
            // vers la caméra (on présente le dos de la main à l'essayage).
            val prev = prevNormal
            val flip = if (prev != null) {
                normN[0] * prev[0] + normN[1] * prev[1] + normN[2] * prev[2] < 0f
            } else {
                normN[2] < 0f
            }
            if (flip) normN = floatArrayOf(-normN[0], -normN[1], -normN[2])
            prevNormal = normN

            // Normalisation avant filtrage : composantes d'amplitude stable,
            // indépendantes de la taille de main.
            val axisN = normalized(axisRaw, fallback = axisVec)
            axisVec = floatArrayOf(
                fAxis[0].filter(axisN[0], now),
                fAxis[1].filter(axisN[1], now),
                fAxis[2].filter(axisN[2], now),
            )
            normVec = floatArrayOf(
                fNorm[0].filter(normN[0], now),
                fNorm[1].filter(normN[1], now),
                fNorm[2].filter(normN[2], now),
            )
        }

        // Taille apparente de la main (poignet→base du majeur), en écran.
        val handD = screenDelta(lm[9], lm[0])
        handDX = fHandDX.filter(handD[0], now)
        handDY = fHandDY.filter(handD[1], now)

        hasHand = true
        if (logCount++ % 30 == 0) {
            Log.i(
                TAG,
                "main: target=($smX, $smY) axe=(${axisVec[0]}, ${axisVec[1]}, ${axisVec[2]}) " +
                    "src=$lastAxisSource " +
                    "norm=(${normVec[0]}, ${normVec[1]}, ${normVec[2]}) " +
                    "taille=${hypot(handDX, handDY)} rot=$rot crop=($cx, $cy)",
            )
        }
    }

    private fun resetFilters() {
        fX.reset()
        fY.reset()
        fHandDX.reset()
        fHandDY.reset()
        fAxis.forEach { it.reset() }
        fNorm.forEach { it.reset() }
        // Le signe de la normale se réinitialisera "face caméra".
        prevNormal = null
    }

    // --- petites aides vectorielles ----------------------------------------

    private fun cross(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun normalized(v: FloatArray, fallback: FloatArray): FloatArray {
        val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (len < 1e-5f) return fallback
        return floatArrayOf(v[0] / len, v[1] / len, v[2] / len)
    }

    /** Un vecteur unitaire quelconque perpendiculaire à [axis]. */
    private fun anyPerpendicular(axis: FloatArray): FloatArray {
        val ref = if (abs(axis[2]) < 0.9f) floatArrayOf(0f, 0f, 1f) else floatArrayOf(1f, 0f, 0f)
        return normalized(cross(ref, axis), fallback = floatArrayOf(1f, 0f, 0f))
    }

    // --- Caméra : preview + analyse ---------------------------------------

    private fun startCamera(context: Context) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, ::analyze) }
                provider.unbindAll()
                val camera = provider.bindToLifecycle(
                    activity as LifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis,
                )
                readCameraFov(camera.cameraInfo)
                Log.i(TAG, "Caméra liée (preview + analyse)")
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** FOV vertical réel = 2·atan(demi-grand-côté du capteur / focale). En
     *  portrait (capteur tourné de 270°), la hauteur affichée correspond au
     *  GRAND côté du capteur paysage, entièrement visible (le flux 4:3 est
     *  rogné en largeur par FILL_CENTER, pas en hauteur). */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun readCameraFov(cameraInfo: androidx.camera.core.CameraInfo) {
        try {
            val info = Camera2CameraInfo.from(cameraInfo)
            val focal = info
                .getCameraCharacteristic(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull()
            val sensor = info
                .getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            if (focal != null && sensor != null && focal > 0f) {
                val fov = 2f * atan(sensor.width / (2f * focal))
                if (fov in 0.3f..2.6f) {
                    cameraVerticalFov = fov
                    Log.i(
                        TAG,
                        "FOV vertical caméra = " +
                            "${Math.toDegrees(fov.toDouble()).toInt()}°",
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FOV caméra illisible, défaut conservé", e)
        }
    }

    private fun analyze(image: ImageProxy) {
        // Collier : handLandmarker est volontairement null, c'est la pose qui
        // pilote — ne pas sortir ici, sinon plus aucune détection.
        val lm = handLandmarker
        if (lm == null && poseLandmarker == null) {
            image.close()
            return
        }
        try {
            // Mémorise la géométrie de la frame pour la conversion des
            // landmarks (capteur brut → écran) dans onHands.
            frameRotation = image.imageInfo.rotationDegrees
            frameW = image.width
            frameH = image.height
            val bitmap = image.toBitmap()
            val mpImage = BitmapImageBuilder(bitmap).build()
            val opts = ImageProcessingOptions.builder()
                .setRotationDegrees(image.imageInfo.rotationDegrees)
                .build()
            val ts = SystemClock.uptimeMillis()
            lm?.detectAsync(mpImage, opts, ts)
            // Collier : la pose est le détecteur principal → chaque frame.
            // Manchette : une frame sur deux suffit (l'avant-bras bouge moins
            // vite que la main) et ça limite le coût du 2e détecteur.
            if (anchor == "neck" || analysisFrameCount++ % 2 == 0) {
                poseLandmarker?.detectAsync(mpImage, opts, ts)
            }
        } catch (e: Exception) {
            Log.e(TAG, "analyze error", e)
        } finally {
            image.close()
        }
    }

    // --- chargement assets -------------------------------------------------

    private fun loadModel() {
        try {
            val bytes = activity.assets
                .open("flutter_assets/$assetPath")
                .use { it.readBytes() }
            modelViewer.loadModelGlb(bytes.toDirectByteBuffer())
            Log.i(TAG, "modèle chargé: $assetPath (${bytes.size} o)")
        } catch (e: Exception) {
            Log.e(TAG, "échec chargement modèle: $assetPath", e)
        }
    }

    /** Charge le cylindre occluseur (assets/occluders/cylinder.glb : rayon 1,
     *  hauteur 1 le long de Y) via un AssetLoader dédié, puis bascule ses
     *  matériaux en écriture de profondeur SEULE. */
    private fun loadOccluder() {
        try {
            val bytes = activity.assets
                .open("flutter_assets/assets/occluders/cylinder.glb")
                .use { it.readBytes() }
            val engine = modelViewer.engine
            val provider = UbershaderProvider(engine)
            val loader = AssetLoader(engine, provider, EntityManager.get())
            val asset = loader.createAsset(bytes.toDirectByteBuffer())
                ?: throw IllegalStateException("createAsset a renvoyé null")
            val resourceLoader = ResourceLoader(engine)
            resourceLoader.loadResources(asset)
            asset.releaseSourceData()
            val rm = engine.renderableManager
            for (entity in asset.entities) {
                val ri = rm.getInstance(entity)
                if (ri == 0) continue
                for (p in 0 until rm.getPrimitiveCount(ri)) {
                    rm.getMaterialInstanceAt(ri, p).apply {
                        // En mode debug, le cylindre reste visible (rouge).
                        if (!debugOccluder) setColorWrite(false)
                        setDepthWrite(true)
                    }
                }
            }
            modelViewer.scene.addEntities(asset.entities)
            occProvider = provider
            occLoader = loader
            occResourceLoader = resourceLoader
            occAsset = asset
            Log.i(
                TAG,
                "occluseur chargé (${asset.entities.size} entités, " +
                    "debug=$debugOccluder)",
            )
        } catch (e: Exception) {
            Log.e(TAG, "échec chargement occluseur", e)
        }
    }

    private fun loadEnvironment() {
        try {
            val bytes = activity.assets
                .open("flutter_assets/assets/ibl/default_env_ibl.ktx")
                .use { it.readBytes() }
            val ibl = KTX1Loader
                .createIndirectLight(modelViewer.engine, bytes.toDirectByteBuffer())
                .indirectLight
            ibl?.intensity = 30_000f
            modelViewer.scene.indirectLight = ibl
        } catch (e: Exception) {
            Log.e(TAG, "échec chargement IBL", e)
        }
    }

    override fun getView(): View = root

    override fun dispose() {
        choreographer.removeFrameCallback(frameCallback)
        // Libère la caméra AVANT de couper l'analyse : sinon l'ImageReader
        // continue de produire des frames jamais fermées (maxImages acquired)
        // et la caméra reste occupée pour la prochaine ouverture d'écran.
        cameraProvider?.unbindAll()
        cameraProvider = null
        analysisExecutor.shutdown()
        handLandmarker?.close()
        handLandmarker = null
        poseLandmarker?.close()
        poseLandmarker = null
        // NE PAS détruire les objets gltfio de l'occluseur : Filament panique
        // (SIGABRT « destroying material ... instances still alive ») car le
        // moteur du ModelViewer vit encore. Fuite assumée, comme pour le
        // ModelViewer lui-même (l'écran suivant recrée tout).
        occAsset = null
        occResourceLoader = null
        occLoader = null
        occProvider = null
    }

    private fun ByteArray.toDirectByteBuffer(): ByteBuffer =
        ByteBuffer.allocateDirect(size).apply {
            put(this@toDirectByteBuffer)
            rewind()
        }

    companion object {
        private const val TAG = "NativeArView"
        private const val MODEL_ASSET = "hand_landmarker.task"
        private const val POSE_MODEL_ASSET = "pose_landmarker_lite.task"

        /** Fraîcheur max de l'axe avant-bras pose avant repli sur la main. */
        private const val kPoseFreshMs = 600L

        /** Distance² max (image normalisée) entre poignet pose et poignet
         *  main pour accepter la détection de pose. */
        private const val kPoseMaxWristDistSq = 0.25f * 0.25f

        /** Cohérence min (cosinus) entre axe pose et axe main : en dessous
         *  (>~60° d'écart), l'axe pose est rejeté au profit de l'axe main. */
        private const val kPoseCoherenceDot = 0.5f

        /** Vitesse de convergence de l'échelle lissée (fraction par frame,
         *  ~60 fps → converge en ~0,5 s, insensible au bruit de mesure). */
        private const val kScaleLerp = 0.06f

        // --- Calibration (à ajuster visuellement) ---
        /** Distance caméra→modèle (cohérente avec orbitHomePosition). */
        private const val kCameraDistance = 4.0f
        /** FOV vertical de repli (radians, ~60°) si Camera2 est illisible —
         *  ordre de grandeur des caméras frontales. Le FOV réel est lu au
         *  démarrage dans readCameraFov. */
        private const val kFallbackFovRad = 1.05f

        /** Plans de découpe de la projection imposée (unités monde). */
        private const val kNearPlane = 0.05
        private const val kFarPlane = 100.0

        /** Échelle du bijou par unité de taille de main (distance lm0–lm9 en
         *  monde). Proportions réelles (main ~9 cm, bague fleur Ø ~2,2 cm,
         *  bracelet manchette Ø ~7,5 cm) ; modèles mesurés à ~2 unités
         *  (script glb_measure) : s = ratio ÷ 2. À affiner visuellement. */
        private const val kRingPerHand = 0.12f

        /** Rayon intérieur du trou du bracelet en unités modèle (mesuré par
         *  glb_pivot_check : anneau complet 360°, trou le long de +Y,
         *  parfaitement centré sur l'origine du GLB). */
        private const val kModelInnerRadius = 0.652f

        /** Largeur anatomique du poignet en fraction de la taille de main
         *  (distance lm0–lm9) : poignet ~5,5–6,5 cm pour ~8–9 cm de main. */
        private const val kWristWidthPerHand = 0.70f

        /** Jeu du bracelet : Ø intérieur = kBraceletClearance × largeur du
         *  bras MESURÉE. 1.12 = bracelet ajusté qui prend exactement la
         *  taille du bras, avec juste le jeu d'un bijou rigide réel. */
        private const val kBraceletClearance = 1.12f

        /** Fraîcheur max de la mesure silhouette avant repli anatomique. */
        private const val kSegFreshMs = 400L

        /** Points de mesure de la largeur, en fraction de l'axe
         *  poignet→coude. Deux mesures : le rayon au bord haut de la
         *  manchette (proche) et l'évasement de l'avant-bras (loin). */
        private const val kScanNear = 0.10f
        private const val kScanFar = 0.45f

        /** Écart min (unités monde) entre les deux points de mesure projetés
         *  sur l'axe pour en tirer une pente : en dessous, le bras pointe
         *  vers la caméra et l'évasement mesuré serait du bruit amplifié. */
        private const val kMinTaperSpan = 0.15f

        /** Évasement max accepté (rayon gagné par unité monde vers le coude).
         *  Un avant-bras réel reste bien en dessous ; au-delà, la mesure
         *  lointaine a fui dans le coude ou le torse. */
        private const val kMaxTaper = 0.25f

        /** Plancher du dénominateur de la résolution en fermé de l'échelle
         *  (voir sRaw) : borne la boucle rayon → échelle → position. */
        private const val kMinTaperDenom = 0.5f

        /** Demi-hauteur de la manchette le long de son axe, unités modèle
         *  (profil GLB mesuré : bande pleine de Y=-0.92 à +0.92, rayon
         *  intérieur constant). Sert au placement axial : bord supérieur au
         *  pli du poignet. */
        private const val kBandHalfHeight = 0.923f

        /** Marge (fraction de s) entre le pli du poignet et le bord
         *  supérieur de la manchette. */
        private const val kBandGap = 0.05f

        // --- Collier (plastron) ---
        // Constantes MESURÉES sur assets/jewelry/colliers/collier.glb
        // (script glb_necklace/glb_neck2, scratchpad) : 434 k sommets, axe du
        // trou = Y, motif décoré en +Z (356 k sommets avant / 78 k arrière).

        /** Rayon intérieur de l'OUVERTURE du plastron, en unités modèle —
         *  pris au plus étroit (Y ≈ +0.85..+1.0), là où le cou passe. */
        private const val kNecklaceInnerRadius = 0.507f

        /** Hauteur de cette ouverture dans le modèle. Le collier ne se porte
         *  PAS par son centre : l'origine du GLB descend de cette valeur ×
         *  échelle sous le creux sternal pour que l'ouverture y affleure. */
        private const val kNecklaceOpeningY = 0.90f

        /** Jeu de l'ouverture autour du cou : un plastron repose dessus,
         *  il est donc ajusté (contrairement à un jonc qui pendouille). */
        private const val kNecklaceClearance = 1.08f

        /** Largeur du cou en fraction de la largeur d'épaules (biacromiale) :
         *  cou ~12 cm pour des épaules ~40 cm. Sert d'échelle de repli. */
        private const val kNeckWidthPerShoulder = 0.30f

        /** Montée de l'ancre au-dessus du milieu des épaules, en fraction de
         *  la largeur d'épaules : vise le creux sternal plutôt que la ligne
         *  acromiale, qui est un peu basse. */
        private const val kNeckRise = 0.08f

        /** Profondeur du cou / largeur (section légèrement aplatie). */
        private const val kNeckDepthRatio = 0.85f

        /** Occluseur du cou : longueur (× échelle) et remontée de son centre
         *  au-dessus de l'ancre, pour couvrir le cou et non la poitrine. */
        private const val kNeckOccLen = 2.4f
        private const val kNeckOccRise = 0.35f

        /** Vitesse du fondu du guide de placement (fraction par frame). */
        private const val kGuideFade = 0.08f

        /** Largeur de balayage max (fraction du grand côté du masque) :
         *  au-delà, le balayage a fui dans le torse/l'autre bras → rejet. */
        private const val kSegMaxWidthFrac = 0.35f

        /** Bornes de validité de la largeur mesurée (écran normalisé). */
        private const val kSegMinWidthN = 0.02f
        private const val kSegMaxWidthN = 0.40f

        /** Lissage (fraction/frame) et borne de la correction latérale vers
         *  le centre mesuré de la silhouette. */
        private const val kSegOffsetLerp = 0.12f
        private const val kSegOffsetMax = 0.08f

        /** Marge de l'occluseur au-delà du rayon estimé du poignet : absorbe
         *  l'erreur d'estimation pour que l'arrière de l'anneau ne
         *  réapparaisse jamais SUR la peau. Doit rester < clearance. */
        private const val kOccluderMargin = 1.05f

        /** Profondeur du poignet / largeur (ellipse anatomique aplatie). */
        private const val kWristDepthRatio = 0.70f

        /** Norme min de la composante du vecteur silhouette orthogonale à
         *  l'axe du bras (unités monde) pour en tirer un roulis : en dessous,
         *  la mesure est quasi colinéaire à l'axe → direction indéterminée. */
        private const val kMinRollNorm = 0.05f

        /** Lissage du roulis mesuré (fraction/frame) : le masque n'est
         *  ré-évalué qu'une frame d'analyse sur deux, la boucle de rendu
         *  tourne à 60 fps → convergence douce, sans à-coups d'orientation. */
        private const val kRollLerp = 0.10f

        /** Sous cette fraction "dans le plan écran" de l'axe, on arrête de
         *  compenser le raccourcissement de perspective (protège des
         *  divisions extrêmes quand le membre pointe vers la caméra). */
        private const val kMinPlanarFactor = 0.35f

        /** Occluseur bague : rayon < rayon intérieur mesuré du trou (0.242,
         *  le trou est en partie recouvert en projection par la fleur, on
         *  reste en dessous), × échelle du bijou (s). Longueur : traverse
         *  largement le bijou le long du membre (idem kWristOccLen). */
        private const val kWristOccLen = 4.0f
        private const val kRingOccRadius = 0.62f
        private const val kRingOccLen = 7.0f

        /** Avance temporelle (s) de la compensation de latence. */
        private const val kLeadSeconds = 0.09f

        /** Déplacement prédictif max (unités normalisées). */
        private const val kMaxLead = 0.06f

        /** Silence de détection au-delà duquel les filtres repartent de zéro. */
        private const val kResetAfterMs = 500L

        /** Transform "invisible" (échelle nulle) quand aucune main n'est vue. */
        private val kHiddenTransform = floatArrayOf(
            0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f,
            0f, 0f, 10f, 1f,
        )
    }
}

/**
 * Filtre One-Euro (Casiez et al., 2012), port du filtre Dart de
 * ar_service.dart. Faible latence à faible vitesse (lisse le bruit), faible
 * lissage à haute vitesse (suit le mouvement).
 */
private class OneEuroFilter(
    private val minCutoff: Float = 1.0f,
    private val beta: Float = 0.0f,
) {
    /** Vitesse lissée courante (unités/s), sert à extrapoler la position. */
    var velocity = 0f
        private set

    private var xPrev = 0f
    private var tPrevMs = -1L

    fun filter(x: Float, tMs: Long): Float {
        if (tPrevMs < 0) {
            tPrevMs = tMs
            xPrev = x
            velocity = 0f
            return x
        }
        var dt = (tMs - tPrevMs) / 1000f
        if (dt <= 0f) dt = 1f / 30f // garde-fou
        tPrevMs = tMs

        val dx = (x - xPrev) / dt
        val aD = alpha(kDCutoff, dt)
        velocity = aD * dx + (1 - aD) * velocity

        val cutoff = minCutoff + beta * abs(velocity)
        val a = alpha(cutoff, dt)
        val ex = a * x + (1 - a) * xPrev
        xPrev = ex
        return ex
    }

    fun reset() {
        tPrevMs = -1L
        velocity = 0f
    }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    companion object {
        // Fréquence de coupure du dérivé : 1 Hz, valeur usuelle du papier.
        private const val kDCutoff = 1f
    }
}
