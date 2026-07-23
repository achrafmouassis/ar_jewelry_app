package com.example.ar_jewelry_app

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.os.SystemClock
import android.util.Log
import android.util.Size
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
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.android.filament.Camera
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.MaterialInstance
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
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import io.flutter.plugin.platform.PlatformView
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
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

    /** Affichage LOCKSTEP (cf. [frameView]). Lu en premier : il décide quelle
     *  vue caméra est construite. */
    private val lockstep: Boolean =
        (args as? Map<*, *>)?.get("lockstep") as? Boolean ?: false

    /** Fusion par flux optique (cf. FlowTracker et fuseWithFlow). */
    private val flowEnabled: Boolean =
        (args as? Map<*, *>)?.get("flow") as? Boolean ?: false

    private val root = FrameLayout(context)

    /** Aperçu caméra « live » — chemin historique. Il affiche la frame la plus
     *  récente, donc EN AVANCE sur le bijou, qui décrit une frame déjà
     *  analysée. Null quand le lockstep prend la main. */
    private val previewView: PreviewView? =
        if (lockstep) {
            null
        } else {
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        }

    /** Bitmap actuellement affiché en mode lockstep, avec sa géométrie. Écrit
     *  par le thread de détection, lu par le thread UI au dessin. */
    @Volatile
    private var shownFrame: Bitmap? = null

    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val frameMatrix = Matrix()

    /** Vue caméra du mode LOCKSTEP : elle ne montre QUE des frames dont la
     *  détection est terminée, si bien que l'image et le bijou décrivent
     *  toujours le même instant. C'est ce que font les essayages commerciaux —
     *  l'affichage prend une ou deux frames de retard, invisible, et en échange
     *  le bijou ne « glisse » plus jamais.
     *
     *  Vue ordinaire (pas de SurfaceView) : son `onDraw` passe par le pipeline
     *  matériel de la fenêtre, exactement comme le guide de placement qui
     *  s'affiche déjà correctement au-dessus du même empilement. Une
     *  TextureView aurait été tentante, mais `lockCanvas` y rend un canevas
     *  LOGICIEL : mettre à l'échelle une image plein écran par le CPU à 30 Hz
     *  serait hors budget. */
    private val frameView: View? = if (!lockstep) {
        null
    } else {
        object : View(context) {
            override fun onDraw(canvas: Canvas) {
                val bmp = shownFrame ?: return
                if (bmp.isRecycled) return
                if (!buildFrameMatrix(bmp, width, height)) return
                canvas.drawBitmap(bmp, frameMatrix, framePaint)
            }
        }
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

    // Largeur mesurée du COU, même format que wristSeg. Le masque personne
    // était déjà produit pour l'écran collier (setOutputSegmentationMasks) et
    // intégralement jeté : le collier se dimensionnait sur kNeckWidthPerShoulder,
    // un ratio de population, alors que la mesure réelle était disponible.
    @Volatile
    private var neckSeg: FloatArray? = null
    @Volatile
    private var neckSegMs = 0L

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

    // MESURE ABSOLUE de l'utilisateur (calibration à l'écran), en millimètres.
    // 0 = non calibré. C'est la grandeur que la doc du CalibrationService décrit
    // comme devant PILOTER la taille (pattern des essayages commerciaux :
    // mesure absolue pour la taille, suivi seulement pour la pose). Elle était
    // jusqu'ici réduite à un simple facteur multiplicatif (scaleCorrection)
    // appliqué au seul repli anatomique — donc IGNORÉE dès que la segmentation
    // fournissait une largeur, même fausse. On lit désormais la mesure brute
    // pour pouvoir en dériver un rayon métrique exact (rayon = mm/2).
    private val calibWristMm: Float =
        ((args as? Map<*, *>)?.get("wristMm") as? Number)?.toFloat() ?: 0f
    private val calibFingerMm: Float =
        ((args as? Map<*, *>)?.get("fingerMm") as? Number)?.toFloat() ?: 0f

    /** Rayon poignet/doigt VOULU en mètres, déduit de la calibration absolue.
     *  0 tant que l'utilisateur n'a pas mesuré : on retombe alors sur le
     *  pipeline (silhouette puis anatomie), comportement d'origine. */
    private val calibWristRadiusM: Float =
        if (calibWristMm > 1f) 0.5f * calibWristMm / 1000f else 0f
    private val calibFingerRadiusM: Float =
        if (calibFingerMm > 1f) 0.5f * calibFingerMm / 1000f else 0f

    // --- Mesures du bijou -------------------------------------------------
    // Produites hors-ligne par tool/fit_analyzer.dart et embarquées à côté du
    // GLB. Avant, chacune de ces grandeurs était une CONSTANTE écrite à la
    // main après une mesure jetable : ajouter un bijou imposait de refaire les
    // mesures, et une erreur pouvait vivre des semaines sans se voir
    // (kModelInnerRadius est resté à 0.652 pour un vrai rayon de 0.82). Elles
    // sont désormais lues. Les constantes k* correspondantes ne servent plus
    // que de repli, pour qu'un asset sans fit.json continue de s'afficher.
    private val fit: JewelFit? = loadFit()

    /** Centre du trou dans le repère MODÈLE : l'origine d'un GLB Meshy tombe
     *  sur le centre de sa boîte englobante, donc à côté du trou dès que la
     *  pièce est dissymétrique (la fleur d'une bague la décale de 0.32). */
    private val holeCenter: FloatArray = fit?.holeCenter
        ?: when (anchor) {
            "wrist" -> floatArrayOf(kCuffHoleX, 0f, kCuffHoleZ)
            "neck" -> floatArrayOf(0f, 0f, 0f)
            else -> floatArrayOf(kRingHoleX, kRingHoleY, 0f)
        }

    /** Rayon du trou dans la direction MÉDIO-LATÉRALE (modèle X → colX).
     *  C'est celui-là qu'il faut confronter à la largeur du membre, parce que
     *  c'est dans cette direction-là que la segmentation la mesure. */
    private val holeRadiusLateral: Float =
        fit?.radiusToward("X") ?: kModelInnerRadius

    /** Demi-étendue de la pièce le long de l'axe du trou. */
    private val bandHalfHeight: Float = fit?.axisHalfExtent ?: kBandHalfHeight

    /** Endroit le plus étroit le long de l'axe : c'est là que le membre
     *  s'insère. Un plastron se porte par son ouverture haute, pas par son
     *  milieu — d'où le décalage axial appliqué au rendu. */
    private val openingAt: Float = fit?.openingAt ?: kNecklaceOpeningY
    private val openingRadius: Float =
        fit?.openingRadius ?: kNecklaceInnerRadius

    /** Petit axe du trou de la bague : borne haute de son occluseur. */
    private val ringHoleRadius: Float = fit?.holeRadiusMin ?: kRingHoleFallback

    // --- Filtres One-Euro (mêmes constantes que la version Dart) -----------
    // Le collier est un cas à part : il ne suit QUE la pose (bien plus bruitée
    // que le hand_landmarker) et son axe dérive des OREILLES (lm7/8), que le
    // modèle « invente » dès qu'elles sont un peu masquées — d'où le bijou qui
    // « bouge dans tous les sens sans s'arrêter ». Or un collier est quasi
    // statique sur le buste : on peut lisser BEAUCOUP plus fort sans latence
    // perceptible. minCutoff/beta abaissés ⇒ fréquence de coupure basse ⇒ le
    // jitter de landmarks est écrasé. La main, elle, doit rester vive : elle
    // garde ses constantes d'origine.
    private val fX = OneEuroFilter(
        minCutoff = if (anchor == "neck") 0.35f else 1.2f,
        beta = if (anchor == "neck") 0.006f else 0.02f,
    )
    private val fY = OneEuroFilter(
        minCutoff = if (anchor == "neck") 0.35f else 1.2f,
        beta = if (anchor == "neck") 0.006f else 0.02f,
    )
    private val fHandDX = OneEuroFilter(minCutoff = 0.8f, beta = 0.01f)
    private val fHandDY = OneEuroFilter(minCutoff = 0.8f, beta = 0.01f)
    // L'orientation n'est plus lissée ici : elle l'est d'un bloc, sur la
    // rotation assemblée (cf. fQuat).
    // Rapport largeur de doigt / longueur de main (cf. onHands). Fortement
    // lissé : c'est une constante morphologique, elle ne doit pas "respirer".
    private val fPitch = OneEuroFilter(minCutoff = 0.3f, beta = 0.005f)

    @Volatile
    private var fingerPitchRatio = 0f

    // --- Échelle : mesure MÉTRIQUE verrouillée -----------------------------
    // Le pipeline ré-estimait à chaque frame le rayon du membre, en arbitrant
    // entre masque de segmentation, maintien de 4 s et ratio de population.
    // Ces sources ne s'accordent pas (le garde-fou accepte jusqu'à 1.70× entre
    // elles) : chaque bascule était un saut de taille, et le lissage lent le
    // transformait en « respiration » du bijou. Or le rayon du poignet d'un
    // utilisateur est CONSTANT ; seule sa distance à la caméra varie.
    //
    // On sépare donc les deux :
    //  - le rayon en MÈTRES est mesuré sur les premières frames fiables puis
    //    VERROUILLÉ sur la médiane (insensible aux relevés aberrants) ;
    //  - la taille apparente en devient une simple conséquence de la distance,
    //    via unitsPerMeter (cf. plus bas).
    //
    // Effet : plus aucune commutation de source, donc plus de saut de taille ;
    // et le bijou grossit/rétrécit encore correctement quand la main approche.
    private val lockWristRadius = LockedMedian("rayon poignet (m)", kLockSamples, kRelockRatio)
    private val lockFingerRadius = LockedMedian("rayon doigt (m)", kLockSamples, kRelockRatio)
    private val lockNeckRadius = LockedMedian("rayon cou (m)", kLockSamples, kRelockRatio)

    // Longueur PLANAIRE (composantes x,y) du segment de référence dans les
    // world landmarks MÉTRIQUES : lm0→lm9 pour la main, ligne d'épaules pour le
    // collier. Confrontée à la même longueur mesurée à l'écran, elle donne le
    // facteur unités-monde/mètre — c'est-à-dire la distance.
    //
    // Prendre les composantes x,y des world landmarks (et non la longueur 3D)
    // est ce qui rend la mesure INSENSIBLE À L'INCLINAISON : le raccourcissement
    // de perspective affecte identiquement le numérateur et le dénominateur, il
    // se simplifie. C'est la même recette que la remise à l'échelle de la
    // profondeur dans onPose — et elle remplace avantageusement la division par
    // `planar`, dont les logs montraient qu'elle amplifiait la taille jusqu'à
    // ×1.8 sur un simple basculement de la main.
    @Volatile
    private var metricPlanar = 0f

    /** Longueur 3D MÉTRIQUE du même segment de référence (mètres). Contrairement
     *  à [metricPlanar], elle ne se raccourcit pas quand le membre s'incline —
     *  c'est donc elle qu'il faut multiplier par le facteur distance pour
     *  obtenir une estimation anatomique qui ne « respire » pas. */
    @Volatile
    private var metric3D = 0f

    /** Demi-écart métrique entre MCP voisins = rayon du doigt, en mètres.
     *  Brut : c'est la médiane du verrou qui filtre, pas un passe-bas. */
    @Volatile
    private var fingerRadiusMetric = 0f

    /** Dernier état de suivi publié par le thread de détection, daté de la
     *  frame dont il est issu. Lu par la boucle de rendu, qui l'extrapole à
     *  SA propre date (cf. TrackSnapshot). */
    @Volatile
    private var track: TrackSnapshot? = null

    // --- RIGIDITÉ : UN SEUL filtre, sur la rotation ENTIÈRE ----------------
    // Avant, les trois vecteurs de la base étaient filtrés SÉPARÉMENT puis
    // recollés par Gram-Schmidt, avec des constantes de temps différentes :
    // position ~133 ms, axe du membre ~265 ms, normale de paume ~455 ms,
    // roulis ~150 ms. Un objet réellement porté n'a qu'UNE pose : quand on
    // tourne le poignet, le bijou se translatait donc en 133 ms mais pivotait
    // en 455 ms. C'est cette dissociation qu'on lit comme « pas attaché », et
    // aucun réglage de constante ne pouvait la corriger — il fallait supprimer
    // la structure qui la produit.
    //
    // Deux raisons de filtrer un QUATERNION plutôt que trois vecteurs :
    //  - une seule constante de temps, donc position et orientation arrivent
    //    ENSEMBLE ;
    //  - l'interpolation reste dans le groupe des rotations. Trois vecteurs
    //    filtrés indépendamment ne forment plus une base orthonormée : le
    //    résultat n'est la rotation d'AUCUNE pose réelle, c'est un mélange de
    //    trois directions en retard les unes sur les autres. Gram-Schmidt le
    //    ré-orthogonalise après coup, ce qui masque l'incohérence sans la
    //    supprimer (et introduit du cisaillement selon l'ordre des colonnes).
    //
    // Même constante que la position (fX/fY), délibérément : c'est la seule
    // façon que la translation et la rotation soient perçues simultanées — donc
    // le collier reçoit aussi ici le lissage renforcé (cf. fX/fY). C'est la
    // rotation qui portait l'essentiel de la danse du collier : l'axe du cou
    // pivote dès que les oreilles inventées bougent.
    private val fQuat = QuatFilter(
        minCutoff = if (anchor == "neck") 0.35f else 1.2f,
        beta = if (anchor == "neck") 0.006f else 0.02f,
    )

    /** Le thread de détection demande la remise à zéro de [fQuat] (main perdue
     *  puis retrouvée) ; la boucle de rendu, seule propriétaire du filtre, la
     *  consomme. Voir resetFilters. */
    @Volatile
    private var poseResetPending = false
    // Lu par la boucle de rendu (délai de grâce), écrit par le thread analyse.
    @Volatile
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
    private val auxProviders = mutableListOf<UbershaderProvider>()
    private val auxLoaders = mutableListOf<AssetLoader>()
    private val auxResourceLoaders = mutableListOf<ResourceLoader>()
    // Assets auxiliaires (occluseur, ombre) appariés à auxLoaders par index :
    // doivent être DÉTRUITS au dispose avant que le ModelViewer ne détruise le
    // moteur, sinon leurs MaterialInstance (base_lit_opaque) survivent et
    // Filament panique (SIGABRT « destroying material ... instances still
    // alive ») — le crash observé en quittant/recréant l'écran.
    private val auxAssets = mutableListOf<FilamentAsset>()
    private var occAsset: FilamentAsset? = null

    /** Jupe d'ombre de contact (cf. loadContactShadow). */
    private var shadowAsset: FilamentAsset? = null

    /** Entité de la lumière directionnelle (0 = non créée). */
    private var sunLight = 0

    /** La priorité de rendu du bijou a-t-elle pu être posée (cf. chargement
     *  asynchrone des ressources par ModelViewer) ? */
    private var prioritiesApplied = false

    // --- Décisions verrouillées (cf. StickyChoice) -------------------------
    // Conventions de repère : propriétés du device, décidées une fois puis
    // tenues. Touchées uniquement depuis le thread de détection.
    private val stickyArmRot = StickyChoice("rotation bras", kConventionVotes)
    private val stickyArmMask = StickyChoice("masque bras", kConventionVotes)
    private val stickyNeckRot = StickyChoice("rotation cou", kConventionVotes)
    private val stickyNeckMask = StickyChoice("masque cou", kConventionVotes)

    /** Source de l'axe de l'avant-bras : pose (true) ou main (false), avec
     *  hystérésis. Voir le bloc correspondant dans [onHands]. */
    private var usePoseAxis = false
    private var poseSourceVotes = 0


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

    /** Âge de l'instantané extrapolé à la dernière frame rendue (ms) — c'est la
     *  LATENCE RÉELLE du pipeline, journalisée pour pouvoir la constater au
     *  lieu de la supposer. Boucle de rendu uniquement. */
    private var lastAnchorAgeMs = 0f

    // Source de l'axe utilisée à la dernière détection (diagnostic).
    @Volatile
    private var lastAxisSource = "?"

    // Identité de l'instance, pour tracer le cycle de vie dans logcat : chaque
    // recréation de la PlatformView (changement de clé côté Flutter) construit
    // une NOUVELLE NativeArView et détruit l'ancienne. Si l'app « sort toute
    // seule », c'est presque toujours une recréation-dispose (ex. la
    // calibration qui se charge en asynchrone juste après l'ouverture et change
    // la clé). Ces logs le rendent visible sans avoir à le deviner.
    private val instanceId = ++instanceCounter
    private val createdAtMs = SystemClock.uptimeMillis()
    // Empêche la double destruction des assets auxiliaires (listener de
    // détachement + dispose Flutter peuvent tous deux se déclencher).
    private var auxDestroyed = false

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
        // Exactement une des deux vues caméra existe (cf. lockstep).
        previewView?.let { root.addView(it, mp) }
        frameView?.let { root.addView(it, mp) }
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
        // CRUCIAL — enregistré AVANT la construction du ModelViewer. Filament
        // ModelViewer ajoute, dans son constructeur, son PROPRE listener de
        // détachement qui appelle Engine.destroy() dès que la SurfaceView quitte
        // la fenêtre (quitter l'écran OU recréation de la PlatformView). Ce
        // destroy panique si une MaterialInstance de nos assets auxiliaires
        // (occluseur) est encore vivante — le SIGABRT « base_lit_opaque … still
        // alive ». Notre dispose() Flutter arrive TROP TARD (après le
        // détachement, cf. logs : aucun DISPOSE avant le crash). En insérant
        // NOTRE listener en premier, Android l'invoque avant celui du
        // ModelViewer : on relâche l'occluseur pendant que le moteur vit encore,
        // et Engine.destroy ne trouve plus d'instance vivante.
        surfaceView.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    destroyAuxAssetsOnce("détachement")
                }
            },
        )
        modelViewer = ModelViewer(surfaceView, uiHelper = uiHelper, manipulator = manipulator)
        modelViewer.view.blendMode = com.google.android.filament.View.BlendMode.TRANSLUCENT
        modelViewer.renderer.clearOptions = Renderer.ClearOptions().apply {
            clear = true
            clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        }

        loadModel()
        loadOccluder()
        // Ombre de contact DÉSACTIVÉE : la jupe noire dégradée n'a de sens que
        // si l'occluseur en retranche PARFAITEMENT la moitié avant. En pratique
        // sa moitié tournée vers la caméra restait lisible comme une tache
        // sombre sur la peau (surtout sur l'avant-bras large de la manchette) —
        // exactement l'artefact « ombre visible » signalé. On la coupe : le
        // rendu sans halo parasite est préférable à un contact approximatif.
        if (kContactShadowEnabled) loadContactShadow()
        loadEnvironment()
        setupHandLandmarker()
        setupPoseLandmarker()
        startCamera(activity)
        logStartupSummary()
        choreographer.postFrameCallback(frameCallback)
        Log.i(TAG, "CYCLE ► instance #$instanceId CRÉÉE (total vivantes ~$instanceCounter)")
    }

    /**
     * Récapitulatif de démarrage : un seul bloc qui dit si chaque étage du
     * pipeline est ACTIF ou en REPLI, avec les valeurs effectivement retenues.
     *
     * Sans ça, diagnostiquer demandait de recouper une dizaine de lignes
     * dispersées et d'en déduire les valeurs dérivées — ce sont justement les
     * valeurs dérivées qui décident du rendu, pas les constantes sources.
     */
    private fun logStartupSummary() {
        val f = fit
        Log.i(TAG, "════════ ÉTAT DU PIPELINE ($anchor) ════════")
        Log.i(
            TAG,
            "  bijou      : ${assetPath.substringAfterLast('/')}",
        )
        Log.i(
            TAG,
            "  ÉTAGE A fit: ${if (f != null) "ACTIF (fit.json)" else "REPLI (constantes)"}" +
                if (f != null) {
                    "  axe=${f.holeAxis}" +
                        " trou=${f.holeRadiusMin}..${f.holeRadiusMax}"
                } else {
                    ""
                },
        )
        Log.i(
            TAG,
            "    rayonLatéral=$holeRadiusLateral demiHauteur=$bandHalfHeight" +
                " ouverture=$openingAt@$openingRadius trouBague=$ringHoleRadius",
        )
        Log.i(
            TAG,
            "    centreTrou=(${holeCenter[0]}, ${holeCenter[1]}, ${holeCenter[2]})" +
                " — décalage du pivot appliqué au rendu",
        )
        Log.i(
            TAG,
            "  AFFICHAGE  : " +
                if (lockstep) {
                    "LOCKSTEP (frame analysée + sa pose, demandé " +
                        "${kLockstepAnalysisSize.width}x${kLockstepAnalysisSize.height})"
                } else {
                    "aperçu live (PreviewView) — bijou en retard sur l'image"
                } +
                "  flux=" +
                if (flowEnabled) {
                    "ACTIF (fenêtre $kFlowWin px, correction $kFlowCorrect)"
                } else {
                    "inactif (lissage temporel seul)"
                },
        )
        Log.i(
            TAG,
            "  ÉTAGE B    : poignet=segmentation doigt=squelette3D cou=segmentation" +
                "  calibration=$scaleCorrection" +
                "  morphologie=verrouillée sur $kLockSamples mesures (médiane)",
        )
        Log.i(
            TAG,
            "  PHOTO      : soleil=${if (sunLight != 0) "OK" else "ABSENT"}" +
                " ombre=${if (shadowAsset != null) "OK" else "ABSENTE"}" +
                " occluseur=${if (occAsset != null) "OK" else "ABSENT"}" +
                " debugOccluder=$debugOccluder",
        )
        // Vérification de cohérence des rayons : c'est l'empilement
        // occluseur < ombre < trou qui fait que l'ombre est à moitié masquée
        // et que le bijou entoure le membre. S'il est rompu, rien ne marche.
        val ok = kOccluderMargin < kShadowRadiusMargin
        Log.i(
            TAG,
            "  rayons     : occluseur=${kOccluderMargin}× ombre=${kShadowRadiusMargin}×" +
                " trou=${if (anchor == "wrist") kBraceletClearance else kRingClearance}×" +
                " (rayon du membre) → ${if (ok) "empilement cohérent" else "*** INCOHÉRENT ***"}",
        )
        Log.i(TAG, "══════════════════════════════════════════")
    }

    // --- Filament : pose complète (position + orientation + échelle) --------

    private fun updateModelTransform() {
        val asset = modelViewer.asset ?: return
        val tcm = modelViewer.engine.transformManager
        val inst = tcm.getInstance(asset.root)

        // UNE seule lecture d'horloge pour toute la frame. Les cinq lectures
        // séparées d'avant portaient des instants différents : les deux filtres
        // temporels (ancre et pose) calculaient leur dt sur des bases
        // décalées, et les tests de fraîcheur des mesures pouvaient tomber de
        // part et d'autre d'une milliseconde au sein d'une même frame. L'effet
        // est petit, mais c'est précisément le genre d'incohérence qui rend un
        // comportement non reproductible — donc indébogable.
        val nowMs = SystemClock.uptimeMillis()

        // ModelViewer charge les ressources en asynchrone et ajoute ses
        // renderables à la scène au fil de l'eau : au retour de loadModelGlb,
        // la priorité a pu ne s'appliquer à rien. On réessaie jusqu'à ce
        // qu'elle prenne — sans quoi l'ordre bijou/occluseur redeviendrait
        // indéterminé, ce qui est exactement le bug qu'on corrige.
        if (!prioritiesApplied) {
            val rm = modelViewer.engine.renderableManager
            var n = 0
            asset.entities.forEach { e ->
                val ri = rm.getInstance(e)
                if (ri != 0) {
                    rm.setPriority(ri, kPriorityJewel)
                    n++
                }
            }
            if (n > 0) {
                prioritiesApplied = true
                Log.i(TAG, "priorité du bijou appliquée à $n renderables")
            }
        }
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
            nowMs - wristSegMs < kSegFreshMs
        if (anchor == "wrist") {
            // Le guide s'efface dès que le suivi tient : main présente ET une
            // échelle acquise (silhouette fraîche, ou déjà mémorisée et gelée —
            // sinon le guide réapparaissait à chaque bouffée d'échec du masque,
            // exactement ce qu'on voit sur les captures). Il ne revient que si
            // la main est franchement perdue.
            val target = if (hasHand && (segFreshNow || smoothScale > 0f)) 0f else 1f
            guideView.alpha += kGuideFade * (target - guideView.alpha)
        }
        // Une détection manquée ne doit PAS faire disparaître le bijou : le
        // détecteur décroche régulièrement une frame ou deux, et un objet
        // réellement porté ne clignote pas. On maintient donc la dernière pose
        // connue pendant un court délai de grâce. Sans lui, chaque décrochage
        // produisait une disparition SUIVIE d'une réapparition dont la taille
        // reconvergeait lentement (kScaleLerp) — soit le bijou qui "regonfle",
        // ce qui trahit l'illusion bien plus qu'une pose légèrement figée.
        val heldMs = nowMs - lastHandMs
        if (!hasHand && heldMs > kHoldAfterLossMs) {
            tcm.setTransform(inst, kHiddenTransform)
            occAsset?.let { tcm.setTransform(tcm.getInstance(it.root), kHiddenTransform) }
            shadowAsset?.let {
                tcm.setTransform(tcm.getInstance(it.root), kHiddenTransform)
            }
            // Perte durable : à la prochaine acquisition, tout repart de la
            // mesure plutôt que de rattraper lentement une pose périmée.
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

        // Longueur du segment de référence PROJETÉE à l'écran, en unités monde
        // (le plein écran normalisé [0..1] couvre 2·halfW × 2·halfH).
        val worldHandPlanar = hypot(handDX * 2f * halfW, handDY * 2f * halfH)

        // FACTEUR DISTANCE. Le même segment, mesuré en projection écran (au
        // numérateur) et en mètres dans le plan caméra (au dénominateur) :
        // leur rapport dit combien d'unités monde vaut un mètre à la distance
        // où se trouve le membre. C'est la SEULE grandeur qui doit varier avec
        // le mouvement — tout le reste de l'échelle est une constante
        // morphologique.
        //
        // Le raccourcissement de perspective se simplifie entre les deux
        // termes : plus besoin de diviser par `planar`, dont le clamp
        // kMinPlanarFactor autorisait une amplification ×1.8 déclenchée par un
        // simple basculement de la main (logs : planar 0.94 → 0.48 d'une frame
        // à l'autre, et toute la taille du bijou qui suivait).
        val unitsPerMeter =
            if (metricPlanar > 1e-4f) worldHandPlanar / metricPlanar else -1f

        // Longueur de main compensée « à l'ancienne » : ne sert plus qu'aux
        // replis, tant que les world landmarks n'ont pas encore livré de
        // référence métrique.
        val planar = hypot(axis[0], axis[1]).coerceAtLeast(kMinPlanarFactor)
        val worldHandLen = worldHandPlanar / planar

        // Mesure fraîche de la silhouette du bras (masque de segmentation) ?
        val seg = wristSeg
        // Estimation anatomique (ratio de population sur un modèle de main
        // générique, d'où la correction morphologique) : sert de repli ET de
        // référence de vraisemblance pour la mesure du masque.
        // Bâtie sur la longueur 3D métrique × facteur distance dès qu'elle est
        // disponible : c'est ce qui rend le garde-fou de vraisemblance
        // FIABLE. Adossé à worldHandLen (donc à la division par `planar`),
        // il variait de ±80 % au gré de l'inclinaison de la main et rejetait
        // alors de bonnes mesures — ou en laissait passer de mauvaises.
        val anatomicalRadius = 0.5f * kWristWidthPerHand * scaleCorrection *
            if (metric3D > 0f && unitsPerMeter > 0f) {
                metric3D * unitsPerMeter
            } else {
                worldHandLen
            }
        val measuredRadius = if (anchor == "wrist" && seg != null && segFreshNow) {
            0.5f * hypot(seg[2] * 2f * halfW, seg[3] * 2f * halfH)
        } else {
            -1f
        }
        // GARDE-FOU DE VRAISEMBLANCE — mesuré sur device : le balayage du
        // masque part parfois dans le torse ou l'autre bras et renvoie une
        // largeur énorme (logs : rayon 1.033 contre 0.298 estimé, soit 3,5×,
        // manchette 2,5× trop grosse d'une frame à l'autre). La borne fixe
        // kSegMaxWidthN = 0.40 ne pouvait rien y faire : 40 % de l'écran, une
        // fuite dans le torse passe largement en dessous. On confronte donc la
        // mesure à l'estimation anatomique — même principe que le garde-fou
        // pose/main sur l'axe — et on rejette ce qui s'en écarte trop. Un bras
        // réel ne peut pas faire le double du poignet déduit de sa main.
        // Référence de VRAISEMBLANCE (pas de dimensionnement) : la mesure
        // absolue de l'utilisateur si elle existe — bien plus fiable —, sinon
        // l'estimation anatomique. Elle ne sert QU'À borner la silhouette
        // (rejeter les fuites dans le torse ou l'autre bras). La taille, elle,
        // vient de la silhouette écran, qui n'a besoin d'aucun facteur main.
        val expectedWristRadius = when {
            calibWristRadiusM > 0f && unitsPerMeter > 0f ->
                calibWristRadiusM * unitsPerMeter
            else -> anatomicalRadius
        }
        val segRatio =
            if (expectedWristRadius > 1e-4f) measuredRadius / expectedWristRadius else -1f
        val segFresh = measuredRadius > 0f &&
            segRatio in kSegRadiusMinRatio..kSegRadiusMaxRatio

        // Recentrage latéral : ramène l'ancre sur le CENTRE mesuré de la
        // silhouette du bras, perpendiculairement à l'axe seulement (le long
        // de l'axe, l'ancre main garde le placement voulu du bracelet). Le
        // landmark poignet peut être biaisé vers un bord du bras — c'est ce
        // biais qui donne l'impression d'un bijou posé sur le dessus.
        val anchor0 = anchorAt(nowMs)
        var aX = anchor0[0]
        var aY = anchor0[1]
        run {
            // Perpendiculaire écran de l'axe : axe écran = (x, -y) → perp = (y, x).
            var p0 = axis[1]
            var p1 = axis[0]
            val pl = hypot(p0, p1)
            if (pl > 1e-4f) {
                p0 /= pl
                p1 /= pl
                if (segFresh) {
                    val off = (seg!![0] - anchor0[0]) * p0 +
                        (seg[1] - anchor0[1]) * p1
                    segOff += kSegOffsetLerp *
                        (off.coerceIn(-kSegOffsetMax, kSegOffsetMax) - segOff)
                } else {
                    segOff *= 1f - kSegOffsetLerp
                }
                aX += segOff * p0
                aY += segOff * p1
            }
        }

        // Bracelet : l'échelle est déduite du bras RÉEL, mesuré sur la
        // silhouette (masque de segmentation, comme les essayages commerciaux)
        // puis VERROUILLÉ en mètres — le lissage lourd d'autrefois ne servait
        // qu'à masquer le va-et-vient entre sources concurrentes, il n'a plus
        // lieu d'être (cf. LockedMedian). Le Ø intérieur du trou est posé à
        // kBraceletClearance × cette largeur : le bracelet prend exactement la
        // taille du bras, l'entoure, et le léger jeu + l'occlusion arrière
        // créent l'effet de traversée.
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
            val rN = measuredRadius
            val rF = 0.5f * hypot(segFar[2] * 2f * halfW, segFar[3] * 2f * halfH)
            // Positions monde des deux points de mesure.
            val nX = (seg!![0] - 0.5f) * 2f * halfW
            val nY = (0.5f - seg!![1]) * 2f * halfH
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

        // VERROUILLAGE MÉTRIQUE du rayon du poignet.
        //
        // Chaque mesure de silhouette jugée vraisemblable est convertie en
        // MÈTRES (division par le facteur distance) et offerte au verrou. Tant
        // qu'il se remplit, il renvoie la médiane des mesures déjà vues — donc
        // une valeur qui se stabilise au lieu de sauter ; une fois plein, il
        // fige et ne bouge plus de la session.
        //
        // Ce qui disparaît ici : l'arbitrage mesuré / maintenu 4 s / ratio
        // anatomique refait à chaque frame. C'est lui qui faisait varier la
        // taille de 20 à 30 % à la cadence des échecs de mesure du masque,
        // c'est-à-dire au moindre mouvement du bras. Le diamètre du poignet de
        // l'utilisateur est une constante : on la mesure, puis on la tient.
        if (segFresh && unitsPerMeter > 0f) {
            lockWristRadius.offer(measuredRadius / unitsPerMeter)
        }
        val worldWristRadius = when {
            // AUTORITÉ D'ÉCHELLE : la silhouette du bras À L'ÉCRAN. C'est un
            // rayon en unités écran PUR — il ne passe par AUCUN facteur dérivé
            // de la main (unitsPerMeter). Il suit donc la distance tout seul
            // (bras plus proche = silhouette plus large) et reste INSENSIBLE à
            // la pose de la main : c'est exactement ce qui manquait, la taille
            // sautait parce qu'elle dépendait de la longueur projetée lm0→lm9,
            // qui change entre poing fermé et main ouverte inclinée.
            segFresh -> measuredRadius
            // Replis de BOOTSTRAP uniquement, le temps qu'une première
            // silhouette exploitable arrive (ensuite le gel prend le relais,
            // cf. mise à jour de smoothScale). Ordre : mesure absolue de
            // l'utilisateur > verrou métrique > anatomie.
            calibWristRadiusM > 0f && unitsPerMeter > 0f ->
                calibWristRadiusM * unitsPerMeter
            lockWristRadius.value > 0f && unitsPerMeter > 0f ->
                lockWristRadius.value * unitsPerMeter
            else -> anatomicalRadius
        }
        // Collier : le canal "taille de main" porte la largeur d'épaules. Le
        // rayon du cou en est déduit anatomiquement, et l'échelle est posée
        // pour que l'OUVERTURE du plastron (rayon intérieur mesuré sur le
        // GLB : 0.507 unité) épouse le cou.
        val worldShoulderW = worldHandPlanar
        // Rayon du COU : mesuré sur la silhouette quand la mesure est fraîche
        // ET vraisemblable, sinon déduit de la largeur d'épaules par ratio
        // anatomique. Même garde-fou que le poignet : le balayage peut fuir
        // dans les cheveux ou le col, et une mesure fausse vaut moins qu'une
        // estimation honnête.
        // Même correction que pour le poignet (cf. anatomicalRadius) : la
        // largeur d'épaules 3D métrique ne se raccourcit pas quand le sujet
        // se tourne, la largeur projetée si.
        val neckAnatomical = 0.5f * kNeckWidthPerShoulder * scaleCorrection *
            if (metric3D > 0f && unitsPerMeter > 0f) {
                metric3D * unitsPerMeter
            } else {
                worldShoulderW
            }
        val nseg = neckSeg
        val neckMeasured = if (anchor == "neck" && nseg != null &&
            nowMs - neckSegMs < kSegFreshMs
        ) {
            0.5f * hypot(nseg[2] * 2f * halfW, nseg[3] * 2f * halfH)
        } else {
            -1f
        }
        val neckRatio =
            if (neckAnatomical > 1e-4f) neckMeasured / neckAnatomical else -1f
        val neckFresh = neckMeasured > 0f &&
            neckRatio in kSegRadiusMinRatio..kSegRadiusMaxRatio
        // Même verrouillage métrique que pour le poignet (cf. worldWristRadius) :
        // ici la référence de distance est la ligne d'épaules.
        if (neckFresh && unitsPerMeter > 0f) {
            lockNeckRadius.offer(neckMeasured / unitsPerMeter)
        }
        val worldNeckRadius = when {
            lockNeckRadius.value > 0f && unitsPerMeter > 0f ->
                lockNeckRadius.value * unitsPerMeter
            neckFresh -> neckMeasured
            else -> neckAnatomical
        }

        val sRaw = if (anchor == "neck") {
            // On confronte au rayon MINIMAL de l'ouverture, pas au rayon
            // latéral comme pour la manchette. Différence assumée : le trou
            // d'une manchette est ovale dans le même sens qu'un poignet et
            // ALIGNÉ avec lui, donc comparer latéral à latéral est exact. Rien
            // ne garantit cet alignement pour un plastron, dont l'ouverture est
            // ovale (0.54 × 0.74) dans une direction quelconque : on prend donc
            // la contrainte la plus serrée. Le collier sort ~5 % trop grand
            // plutôt que de pincer le cou.
            worldNeckRadius * kNecklaceClearance / openingRadius
        } else if (anchor == "wrist") {
            // Le rayon voulu est celui au CENTRE de la bande, situé à
            // s·(kBandHalfHeight + kBandGap) vers le coude — mais ce centre
            // dépend de s, qui dépend du rayon. Plutôt que d'itérer :
            //   r = rProche + taper·(dAncre→proche + s·B)
            //   s = A·r,  A = clearance/rayonModèle,  B = demi-hauteur + jeu
            //   ⟹ s = A·(rProche + taper·dAncre→proche) / (1 − A·taper·B)
            // Le dénominateur ne s'annule que pour un évasement absurde, déjà
            // borné par kMaxTaper ; garde-fou malgré tout.
            val a = kBraceletClearance / holeRadiusLateral
            val b = bandHalfHeight + kBandGap
            val denom = (1f - a * taper * b).coerceAtLeast(kMinTaperDenom)
            a * (worldWristRadius + taper * rNearAlong) / denom
        } else {
            // Bague : le trou doit épouser le DOIGT, pas une fraction
            // arbitraire de la main. kRingPerHand = 0.12 donnait un trou à
            // ~66 % du rayon du doigt — l'anneau était incrusté dans la chair,
            // ce qui se lisait comme un objet flottant. Il avait été réglé
            // « visuellement » à une époque où le pivot était décalé de 0.32 :
            // l'erreur de taille compensait l'erreur de position.
            //
            // Comme pour le poignet, le rayon du doigt est VERROUILLÉ en
            // mètres : il n'a pas besoin du masque de segmentation, l'écart
            // entre MCP voisins est déjà porté par les world landmarks.
            // La correction morphologique s'applique ici — contrairement au
            // poignet — parce que cette mesure vient de l'ajustement d'un
            // modèle de main GÉNÉRIQUE, là où la silhouette est celle de
            // l'utilisateur.
            if (fingerRadiusMetric > 0f) {
                lockFingerRadius.offer(fingerRadiusMetric * scaleCorrection)
            }
            val lockedFinger = lockFingerRadius.value
            val ratio = fingerPitchRatio
            when {
                // PRIORITÉ ABSOLUE : la mesure de l'utilisateur (cf. poignet).
                // Rayon exact du doigt en mètres → unités monde par la distance.
                calibFingerRadiusM > 0f && unitsPerMeter > 0f ->
                    kRingClearance * (calibFingerRadiusM * unitsPerMeter) /
                        holeRadiusLateral
                lockedFinger > 0f && unitsPerMeter > 0f ->
                    kRingClearance * (lockedFinger * unitsPerMeter) /
                        holeRadiusLateral
                ratio > 0f ->
                    kRingClearance *
                        (0.5f * worldHandLen * ratio * scaleCorrection) /
                        holeRadiusLateral
                else -> worldHandLen * kRingPerHand * scaleCorrection
            }
        }
        // Un membre ne change pas de taille. On BORNE donc l'écart accepté par
        // rapport à l'estimation courante AVANT de lisser : sans cette borne,
        // un seul relevé aberrant (mesure de masque qui fuit, compensation de
        // perspective qui s'emballe) tirait l'échelle pendant ~0,5 s — le
        // bijou "respirait", ce qui casse l'illusion d'objet rigide bien plus
        // qu'une taille légèrement fausse. Les logs montraient s passant de
        // 0.49 à 1.20 sur une frame.
        // Une fois la morphologie verrouillée, sRaw ne porte plus de bruit de
        // mesure : il ne varie que par la DISTANCE, qui est un vrai signal. Le
        // lissage lent (0,5 s) devient alors un défaut — le bijou mettrait une
        // demi-seconde à grandir quand la main approche. On converge donc
        // nettement plus vite dès que le verrou a pris.
        // La calibration absolue vaut un verrou d'emblée : le rayon métrique est
        // connu et exact, sRaw ne porte plus que le signal de distance. On
        // converge donc vite dès la 1re frame, sans attendre kLockSamples mesures.
        val scaleLocked = when (anchor) {
            "wrist" -> calibWristRadiusM > 0f || lockWristRadius.isLocked
            "neck" -> lockNeckRadius.isLocked
            else -> calibFingerRadiusM > 0f || lockFingerRadius.isLocked
        }
        val lerp = if (scaleLocked) kScaleLerpLocked else kScaleLerp
        // GEL de l'échelle du bracelet quand la silhouette est périmée. Le
        // balayage du masque échoue par bouffées (main qui s'ouvre, avant-bras
        // qui pointe vers l'objectif) : replier alors sur une estimation
        // dérivée de la main faisait s'effondrer la taille d'un coup (les deux
        // captures : ~largeur du bras poing fermé, minuscule main ouverte). Or
        // un poignet ne change pas de taille en une fraction de seconde. Tant
        // que la main est là mais la silhouette absente, on TIENT donc la
        // dernière échelle valide ; elle se remet à jour dès qu'une silhouette
        // fraîche revient. C'est le gel, pas le repli, qui tue l'effondrement.
        val wristHoldScale = anchor == "wrist" && !segFresh && smoothScale > 0f
        smoothScale = when {
            smoothScale <= 0f -> sRaw
            wristHoldScale -> smoothScale
            else -> {
                val bounded = sRaw.coerceIn(
                    smoothScale / kScaleMaxRatio,
                    smoothScale * kScaleMaxRatio,
                )
                smoothScale + lerp * (bounded - smoothScale)
            }
        }
        val s = smoothScale

        // Gram-Schmidt : composante de la normale orthogonale à l'axe.
        val d = axis[0] * norm[0] + axis[1] * norm[1] + axis[2] * norm[2]
        norm = normalized(
            floatArrayOf(norm[0] - d * axis[0], norm[1] - d * axis[1], norm[2] - d * axis[2]),
            fallback = anyPerpendicular(axis),
        )

        // --- Roulis du bracelet contraint par la silhouette MESURÉE ---------
        // La normale de paume est la mesure la plus bruitée du pipeline, et
        // surtout elle est mal CONTRAINTE quand la
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
                // Retenu BRUT : son lissage propre (kRollLerp) était le
                // troisième retard indépendant de la pose. Il part maintenant
                // dans la rotation assemblée, lissée d'un bloc par fQuat.
                smoothRoll = c
            }
        }

        // Convention des modèles Meshy (cf. mémoire projet) :
        //  - bague : axe du trou = +Z modèle, pierre = +Y → Z→doigt, Y→dos de main ;
        //  - bracelet : axe du trou = +Y modèle → Y→avant-bras, Z→caméra.
        // Le tangage +90° appliqué en V1 est absorbé par ce mapping direct.
        var colX: FloatArray
        var colY: FloatArray
        var colZ: FloatArray
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

        // --- LISSAGE UNIQUE DE LA POSE --------------------------------------
        // Tout ce qui précède est BRUT. La base assemblée est une vraie
        // rotation ; on la lisse ici, d'un seul coup, en passant par le
        // quaternion — après quoi plus personne ne retouche l'orientation.
        //
        // C'est ce qui rend le bijou solidaire : sa translation, sa rotation et
        // son échelle sortent maintenant du même instant avec le même retard,
        // comme les composantes d'une transformation rigide unique. C'est la
        // propriété que Snap obtient en attachant le bijou à un OS d'un mesh de
        // main riggé ; on l'obtient ici sans mesh, en refusant simplement de
        // filtrer les morceaux séparément.
        run {
            // Réinitialisation demandée par le thread de détection (main
            // perdue puis retrouvée) : consommée ICI, par le thread qui
            // possède le filtre. Sans ça, la pose repartirait en rattrapant
            // lentement une orientation périmée.
            if (poseResetPending) {
                poseResetPending = false
                fQuat.reset()
            }
            val q = fQuat.filter(quatFromBasis(colX, colY, colZ), nowMs)
            val b = basisFromQuat(q)
            colX = b[0]
            colY = b[1]
            colZ = b[2]
        }

        // Placement AXIAL mesuré : la manchette fait 2×kBandHalfHeight unités
        // de haut le long du bras (profil GLB mesuré : bande pleine hauteur,
        // rayon intérieur constant). Centrée sur le poignet, elle
        // chevaucherait la paume — c'est ce chevauchement qui donnait le
        // rendu "posé sur la main". On place son bord supérieur au pli du
        // poignet : centre = poignet + (demi-hauteur + marge) vers le coude.
        // Collier : même piège que la manchette, en plus marqué — l'ouverture
        // du plastron est à Y=+0.90 dans le modèle, PAS à son centre. Centrer
        // la pièce sur le cou la placerait ~1 unité trop haut, l'anneau
        // au-dessus du menton. On descend donc l'origine du modèle de
        // 0.90×s sous le creux sternal : l'ouverture y affleure et la pièce
        // retombe sur la poitrine.
        //
        // L'offset suit colY — l'axe du membre APRÈS lissage de la pose — et
        // non plus l'axe brut. Autrement le décalage de position serait calculé
        // dans une direction en avance sur l'orientation rendue, ce qui
        // rouvrirait par la bande la dissociation qu'on vient de fermer.
        val alongOff = when (anchor) {
            "wrist" -> s * (bandHalfHeight + kBandGap)
            "neck" -> s * openingAt
            else -> 0f
        }
        val wx = (aX - 0.5f) * 2f * halfW - colY[0] * alongOff
        val wy = (0.5f - aY) * 2f * halfH - colY[1] * alongOff

        // --- Recentrage du PIVOT sur le centre du TROU ----------------------
        // L'origine d'un GLB Meshy tombe sur le centre de sa boîte englobante,
        // PAS sur le centre du trou : dès que la pièce est dissymétrique, les
        // deux divergent. Mesuré (plus grand cylindre vide traversant, centre
        // optimisé, cf. scripts d'analyse) :
        //   - bague : trou décalé de 0.327 unité vers -Y, soit 59 % du rayon
        //     de son propre trou — la fleur tire la boîte englobante vers le
        //     haut, donc le doigt tombait franchement hors de l'axe du trou ;
        //   - manchette : 0.054, plus modeste mais du même mécanisme.
        // On compose donc une translation MODÈLE de −centreDuTrou, ce qui
        // revient à poser m = T · R · S · Translate(−h).
        // Le fit.json donne le centre du trou avec sa composante AXIALE déjà à
        // zéro : le décalage le long de l'axe est porté séparément par
        // alongOff, qui dépend de l'échelle.
        val hX = holeCenter[0]
        val hY = holeCenter[1]
        val hZ = holeCenter[2]
        val tx = wx - s * (colX[0] * hX + colY[0] * hY + colZ[0] * hZ)
        val ty = wy - s * (colX[1] * hX + colY[1] * hY + colZ[1] * hZ)
        val tz = -s * (colX[2] * hX + colY[2] * hY + colZ[2] * hZ)

        // Colonne-major : m = T · R · S.
        val m = floatArrayOf(
            colX[0] * s, colX[1] * s, colX[2] * s, 0f,
            colY[0] * s, colY[1] * s, colY[2] * s, 0f,
            colZ[0] * s, colZ[1] * s, colZ[2] * s, 0f,
            tx, ty, tz, 1f,
        )
        tcm.setTransform(inst, m)

        if (renderLogCount++ % 120 == 0) {
            Log.i(
                TAG,
                "rendu: fov=${Math.toDegrees(fov.toDouble()).toInt()}° " +
                    "halfH=$halfH s=$s handLen=$worldHandLen planar=$planar " +
                    "unités/m=$unitsPerMeter métriquePlanaire=$metricPlanar " +
                    "verrou=${if (scaleLocked) "FIGÉ" else "en cours"} " +
                    "latence=${lastAnchorAgeMs}ms " +
                    "poignetR=$worldWristRadius " +
                    "(${
                        when {
                            lockWristRadius.value > 0f && unitsPerMeter > 0f ->
                                "verrou ${lockWristRadius.value} m"
                            segFresh -> "mesuré masque"
                            else -> "estimé main"
                        }
                    }) " +
                    "doigtVerrou=${lockFingerRadius.value} " +
                    "mesuré=$measuredRadius estimé=$anatomicalRadius " +
                    "rapport=$segRatio " +
                    (if (anchor == "neck") {
                        "cou=$worldNeckRadius " +
                            "(${if (neckFresh) "mesuré masque" else "estimé épaules"}) " +
                            "rapportCou=$neckRatio "
                    } else {
                        ""
                    }) +
                    (if (anchor != "wrist" && anchor != "neck") {
                        "doigt/main=$fingerPitchRatio " +
                            "(${if (fingerPitchRatio > 0f) "mesuré" else "repli kRingPerHand"}) "
                    } else {
                        ""
                    }) +
                    "évasement=$taper dAncreProche=$rNearAlong " +
                    "recentrage=$segOff " +
                    "axe=(${axis[0]}, ${axis[1]}, ${axis[2]}) " +
                    "roulis=${if (roll != null) "silhouette" else "normale paume"} " +
                    "colX=(${colX[0]}, ${colX[1]}, ${colX[2]}) " +
                    "norm=(${norm[0]}, ${norm[1]}, ${norm[2]}) pos=($wx, $wy)",
            )
        }

        // Axe du trou du bijou dans le repère de rendu, et direction radiale
        // qui l'accompagne. Partagé par l'occluseur et l'ombre de contact :
        // les deux sont des cylindres dont l'axe EST celui du membre.
        val limbAxis: FloatArray
        val limbRadial: FloatArray
        if (anchor == "wrist" || anchor == "neck") {
            limbAxis = colY
            limbRadial = colZ
        } else {
            limbAxis = colZ
            limbRadial = colY
        }

        // --- Occluseur : cylindre aligné sur l'axe du trou du bijou, aux
        // dimensions du membre (ellipse du poignet / cylindre du doigt).
        val occ = occAsset
        if (occ != null) {
            // Axe du cylindre (Y du modèle occluseur) = axe du trou ; les
            // deux autres colonnes portent les rayons radiaux.
            val axisDir = limbAxis
            val radial = limbRadial
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
                r1 = s * openingRadius / kNecklaceClearance * kOccluderMargin
                r2 = r1 * kNeckDepthRatio
                len = s * kNeckOccLen
            } else if (anchor == "wrist") {
                val wristR = s * holeRadiusLateral / kBraceletClearance
                r1 = wristR * kOccluderMargin
                r2 = r1 * kWristDepthRatio
                len = s * kWristOccLen
            } else {
                // Doit rester SOUS le petit axe du trou, sinon le cylindre de
                // profondeur déborde de l'anneau et masque la bague elle-même.
                r1 = s * ringHoleRadius * kRingOccInset
                r2 = r1
                len = s * kRingOccLen
            }
            // L'occluseur suit le MEMBRE, pas l'origine du modèle. Pour la
            // manchette et la bague les deux coïncident ; pour le collier
            // l'origine a été descendue de alongOff sous le creux sternal,
            // alors que le cou à masquer est au-dessus → on remonte.
            val occRise = if (anchor == "neck") alongOff + s * kNeckOccRise else 0f
            // Le membre passe par le CENTRE DU TROU, qui est en (wx, wy, 0) par
            // construction — c'est précisément ce que le recentrage garantit.
            // PAS par (tx, ty, tz), qui est l'endroit où l'on pose l'ORIGINE DU
            // GLB pour que le trou tombe au bon endroit. Les deux diffèrent de
            // s·R·holeCenter, soit la moitié du rayon du doigt pour la bague
            // (holeCenter.y = -0.32) : l'occluseur se retrouvait à moitié hors
            // du membre qu'il est censé représenter.
            // Remontée le long de l'axe LISSÉ (colY = limbAxis), pas de l'axe
            // brut : l'occluseur doit être rigidement solidaire de la pièce
            // qu'il masque. Sur l'axe brut il aurait vibré indépendamment
            // d'elle, laissant réapparaître le dos du collier par intermittence.
            val ox = wx + limbAxis[0] * occRise
            val oy = wy + limbAxis[1] * occRise
            val oz = limbAxis[2] * occRise
            val om = floatArrayOf(
                colX[0] * r1, colX[1] * r1, colX[2] * r1, 0f,
                axisDir[0] * len, axisDir[1] * len, axisDir[2] * len, 0f,
                radial[0] * r2, radial[1] * r2, radial[2] * r2, 0f,
                ox, oy, oz, 1f,
            )
            tcm.setTransform(tcm.getInstance(occ.root), om)
        }

        // --- Ombre de contact -----------------------------------------------
        // Le maillage est en unités de DEMI-HAUTEUR DE BIJOU (y = ±1 tombe sur
        // ses bords), donc une simple mise à l'échelle par s×bandHalfHeight
        // place le dégradé correctement sur n'importe quelle pièce.
        //
        // Le rayon doit tomber entre l'occluseur de profondeur et la surface
        // intérieure du bijou : au ras de l'occluseur, la jupe serait
        // entièrement masquée au lieu de sa seule moitié arrière ; au-delà du
        // trou, elle traverserait le bijou.
        //
        // Le collier est écarté : sa géométrie est un plastron, ses « bords »
        // le long de l'axe ne correspondent pas à un contact avec le cou.
        val shadow = shadowAsset
        if (shadow != null) {
            val si = tcm.getInstance(shadow.root)
            val limbR = when (anchor) {
                "wrist" -> s * holeRadiusLateral / kBraceletClearance
                "neck" -> -1f
                else -> s * holeRadiusLateral / kRingClearance
            }
            if (limbR <= 0f) {
                tcm.setTransform(si, kHiddenTransform)
            } else {
                val sr1 = limbR * kShadowRadiusMargin
                val sr2 = sr1 * if (anchor == "wrist") kWristDepthRatio else 1f
                val sLen = s * bandHalfHeight
                val sm = floatArrayOf(
                    colX[0] * sr1, colX[1] * sr1, colX[2] * sr1, 0f,
                    limbAxis[0] * sLen, limbAxis[1] * sLen,
                    limbAxis[2] * sLen, 0f,
                    limbRadial[0] * sr2, limbRadial[1] * sr2,
                    limbRadial[2] * sr2, 0f,
                    // Comme l'occluseur : centré sur le MEMBRE (wx, wy, 0),
                    // pas sur l'origine du GLB.
                    wx, wy, 0f, 1f,
                )
                tcm.setTransform(si, sm)
            }
        }
    }

    // --- MediaPipe ---------------------------------------------------------

    private fun setupHandLandmarker() {
        // Le collier ne dépend que de la pose (épaules + tête) : pas de main
        // dans le cadrage, et un 2e détecteur coûterait du CPU pour rien.
        if (anchor == "neck") return
        // Même traitement que la pose : le GPU libère du temps CPU, et les
        // deux détecteurs se partagent la même cadence de frames d'analyse.
        fun build(delegate: Delegate): HandLandmarker {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(delegate)
                .build()
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .setResultListener { result, _ -> onHands(result) }
                .setErrorListener { e -> Log.e(TAG, "MediaPipe error: ${e.message}") }
                .build()
            return HandLandmarker.createFromOptions(activity, options)
        }
        handLandmarker = try {
            build(Delegate.GPU).also { Log.i(TAG, "HandLandmarker prêt (GPU)") }
        } catch (e: Exception) {
            Log.w(TAG, "délégué GPU refusé pour la main, repli CPU", e)
            try {
                build(Delegate.CPU).also { Log.i(TAG, "HandLandmarker prêt (CPU)") }
            } catch (e2: Exception) {
                Log.e(TAG, "échec init HandLandmarker", e2)
                null
            }
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
        // La pose AVEC masque de segmentation est de loin l'étage le plus cher
        // du pipeline. MESURÉ SUR DEVICE (logcat) : en CPU elle tournait à
        // MOINS DE 1 Hz au lieu des ~15 Hz visés — MediaPipe en LIVE_STREAM
        // jette les frames tant que le graphe est occupé. Le suivi se
        // retrouvait alors privé de l'axe d'avant-bras ET de la mesure de
        // silhouette la quasi-totalité du temps (logs : "pose absente" et
        // "estimé main" quasi partout), donc la manchette suivait la PAUME.
        // On demande le GPU, avec repli CPU si le device le refuse.
        fun build(delegate: Delegate): PoseLandmarker {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(POSE_MODEL_ASSET)
                .setDelegate(delegate)
                .build()
            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                // Masque de segmentation personne : sert à mesurer la largeur
                // RÉELLE de la silhouette du bras (dimensionnement/centrage de
                // la manchette). RÉSERVÉ AU BRAS : c'est l'étage le plus cher du
                // pipeline (le commentaire d'origine mesurait <1 Hz en CPU), et
                // pour le collier la pose est le SEUL détecteur — la ralentir
                // avec le masque faisait décrocher tout le suivi (« le cou n'est
                // jamais détecté »). Le collier n'a de toute façon pas besoin du
                // masque : sa largeur de cou a un repli anatomique (épaules) et
                // il ne fait pas de recentrage silhouette. On le coupe pour lui.
                .setOutputSegmentationMasks(anchor == "wrist")
                .setResultListener { result, _ -> onPose(result) }
                .setErrorListener { e -> Log.e(TAG, "Pose error: ${e.message}") }
                .build()
            return PoseLandmarker.createFromOptions(activity, options)
        }
        poseLandmarker = try {
            build(Delegate.GPU).also { Log.i(TAG, "PoseLandmarker prêt (GPU)") }
        } catch (e: Exception) {
            Log.w(TAG, "délégué GPU refusé pour la pose, repli CPU", e)
            try {
                build(Delegate.CPU).also { Log.i(TAG, "PoseLandmarker prêt (CPU)") }
            } catch (e2: Exception) {
                Log.e(TAG, "échec init PoseLandmarker", e2)
                null
            }
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

        // VISIBILITÉ. Le modèle de pose renvoie TOUJOURS 33 landmarks, même
        // pour les articulations hors cadre : il les invente. Un coude
        // inventé donne un axe d'avant-bras aberrant, et comme cet axe pilote
        // toute l'orientation de la manchette, le bijou saute d'un coup (54°
        // mesurés dans les logs) dès que la pose redevient "fraîche". Le
        // détecteur publie sa propre confiance — on la lit au lieu de la
        // deviner.
        val vw = lm[wristIdx].visibility().orElse(0f)
        val ve = lm[elbowIdx].visibility().orElse(0f)
        if (vw < kPoseMinVisibility || ve < kPoseMinVisibility) {
            if (poseLogCount % 15 == 0) {
                Log.i(
                    TAG,
                    "pose rejetée: visibilité poignet=$vw coude=$ve " +
                        "(seuil $kPoseMinVisibility)",
                )
            }
            return
        }

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

        // Écran → monde : x mis à l'échelle par l'aspect (cf. viewAspect), y
        // inversé. Sans ce facteur l'axe rendu n'était PAS celui de
        // l'avant-bras visible — la manchette se posait de travers sur le
        // bras, ce qui est exactement l'effet "cylindre placé devant le bras".
        val aspect = viewAspect()
        fun candidate(applyRot: Boolean): FloatArray {
            val pw = toScr(lm[wristIdx].x(), lm[wristIdx].y(), applyRot)
            val pe = toScr(lm[elbowIdx].x(), lm[elbowIdx].y(), applyRot)
            return floatArrayOf((pw[0] - pe[0]) * aspect, -(pw[1] - pe[1]), 0f)
        }

        val ref = normalized(axisVec, fallback = floatArrayOf(0f, 1f, 0f))
        fun score(c: FloatArray): Float {
            val n = normalized(c, fallback = floatArrayOf(0f, 0f, 0f))
            return n[0] * ref[0] + n[1] * ref[1] + n[2] * ref[2]
        }

        val candRot = candidate(true)
        val candUpright = candidate(false)
        // Décision VERROUILLÉE : la convention de rotation est une propriété du
        // device, elle ne change pas en cours de session. On n'accepte un
        // candidat instantané que s'il gagne d'une marge FRANCHE — sinon on
        // s'abstient (null) et la valeur en cours est conservée.
        val sRot = score(candRot)
        val sUp = score(candUpright)
        val instant: Boolean? =
            if (abs(sRot - sUp) < kConventionMargin) null else sRot > sUp
        val useRot = stickyArmRot.update(instant) ?: (sRot >= sUp)
        val chosen = if (useRot) candRot else candUpright

        // LONGUEUR MINIMALE. Second filtre, indépendant de la visibilité : si
        // coude et poignet se projettent quasiment au même endroit, la
        // direction qui les relie n'a aucun contenu — c'est du bruit divisé
        // par une longueur proche de zéro, donc amplifié. Les logs montraient
        // une longueur planaire de 0.03 (3 % de l'écran) là où un avant-bras
        // réel en occupe 30 à 50 %.
        val forearmLen = hypot(chosen[0], chosen[1])
        if (forearmLen < kPoseMinForearmLen) {
            if (poseLogCount % 15 == 0) {
                Log.i(
                    TAG,
                    "pose rejetée: avant-bras trop court à l'écran " +
                        "($forearmLen < $kPoseMinForearmLen)",
                )
            }
            return
        }

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
            val scanner = MaskScanner(masksOpt.get()[0], rot, kSegMaxWidthFrac)
            val wl = lm[wristIdx]
            val el = lm[elbowIdx]
            // La convention d'espace du masque est sondée au point PROCHE
            // (toujours sur le bras si la détection est bonne).
            // Verrouillée aussi : l'espace du masque est une propriété du
            // pipeline. Une sonde qui tombe hors du masque renvoie null et
            // laisse la valeur en place au lieu de la remettre en cause.
            val rawMode = stickyArmMask.update(
                scanner.probeRawMode(
                    wl.x() + (el.x() - wl.x()) * kScanNear,
                    wl.y() + (el.y() - wl.y()) * kScanNear,
                ),
            )

            /** Pleine largeur de la silhouette à [frac] le long de
             *  poignet→coude, en espace écran : [midX, midY, dX, dY].
             *  null = bord non trouvé ou largeur invraisemblable. */
            fun measureAt(frac: Float): FloatArray? {
                if (rawMode == null) return null
                val e = scanner.measureEdges(
                    wl.x() + (el.x() - wl.x()) * frac,
                    wl.y() + (el.y() - wl.y()) * frac,
                    wl.x() - el.x(),
                    wl.y() - el.y(),
                    rawMode,
                ) ?: return null
                val e1 = toScr(e[0], e[1], useRot)
                val e2 = toScr(e[2], e[3], useRot)
                val v = floatArrayOf(
                    (e1[0] + e2[0]) * 0.5f,
                    (e1[1] + e2[1]) * 0.5f,
                    e1[0] - e2[0],
                    e1[1] - e2[1],
                )
                val w = hypot(v[2], v[3])
                return if (w in kSegMinWidthN..kSegMaxWidthN) v else null
            }

            // POIGNET = point le plus ÉTROIT du bras entre la main et le milieu
            // de l'avant-bras. On balaye plusieurs positions et on garde la
            // largeur MINIMALE, au lieu d'un unique point à kScanNear qui, quand
            // la main s'ouvre, tombait sur la paume (large) et faisait échouer
            // toute la mesure — donc l'effondrement de taille. Un échantillon
            // sur la paume donne une largeur plus grande : le minimum l'écarte
            // de lui-même. Ce minimum localise aussi le pli du poignet.
            var near: FloatArray? = null
            var nearW = Float.MAX_VALUE
            run {
                var f = kWristScanFrom
                while (f <= kWristScanTo) {
                    val m = measureAt(f)
                    if (m != null) {
                        val w = hypot(m[2], m[3])
                        if (w < nearW) {
                            nearW = w
                            near = m
                        }
                    }
                    f += kWristScanStep
                }
            }
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
                        "masque=${scanner.size}",
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
            // Cf. onHands : pas de pose ≠ pas d'image.
            publishFrame(result.timestampMs())
            return
        }
        val lm = lms[0]
        if (lm.size < 13) {
            hasHand = false
            publishFrame(result.timestampMs())
            return
        }

        // VISIBILITÉ des épaules. Comme pour le bras (cf. onPose), le modèle de
        // pose renvoie TOUJOURS 33 landmarks, y compris ceux qu'il invente hors
        // cadre — et une épaule inventée fait basculer la ligne d'épaules, donc
        // colX ET l'ancre du collier, d'un coup. On lit la confiance publiée par
        // le détecteur plutôt que de la deviner : sous le seuil, on CONSERVE la
        // dernière pose (le collier ne saute pas) et on se contente de faire
        // avancer l'image. C'est l'une des sources du « bouge dans tous les
        // sens » : des frames à épaules devinées passaient telles quelles.
        // Seuil VOLONTAIREMENT BAS (kNeckMinVisibility, pas kPoseMinVisibility
        // du bras) : le collier n'a aucun détecteur de secours, donc rejeter
        // une frame = figer le bijou. En selfie collier les épaules frôlent
        // souvent le bord du cadre (visibilité ~0.5) sans être fausses pour
        // autant. On ne rejette donc QUE les épaules franchement inventées
        // (confiance quasi nulle) ; le reste, le lissage renforcé l'absorbe.
        val vshL = lm[11].visibility().orElse(1f)
        val vshR = lm[12].visibility().orElse(1f)
        if (vshL < kNeckMinVisibility || vshR < kNeckMinVisibility) {
            if (poseLogCount % 15 == 0) {
                Log.i(
                    TAG,
                    "cou rejeté: visibilité épaules g=$vshL d=$vshR " +
                        "(seuil $kNeckMinVisibility)",
                )
            }
            publishFrame(result.timestampMs())
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
        // Écart ÉCRAN (normalisé, donc anisotrope ; y vers le BAS) du milieu
        // des épaules vers le milieu des oreilles. Gardé sous cette forme
        // parce qu'il sert aux DEUX usages, qui ne vivent pas dans le même
        // espace : un décalage d'ancre en unités écran, et l'axe du cou en
        // direction monde.
        fun neckUpScr(applyRot: Boolean): FloatArray {
            val sl = toScr(lm[11].x(), lm[11].y(), applyRot)
            val sr = toScr(lm[12].x(), lm[12].y(), applyRot)
            val el = toScr(lm[7].x(), lm[7].y(), applyRot)
            val er = toScr(lm[8].x(), lm[8].y(), applyRot)
            val sx = (sl[0] + sr[0]) * 0.5f
            val sy = (sl[1] + sr[1]) * 0.5f
            val hx = (el[0] + er[0]) * 0.5f
            val hy = (el[1] + er[1]) * 0.5f
            return floatArrayOf(hx - sx, hy - sy)
        }

        // Écran → monde (cf. viewAspect). Le collier est le plus touché des
        // trois bijoux : l'axe du cou ET la ligne d'épaules sortent tous deux
        // de toScr, donc colY et colX étaient faussés SIMULTANÉMENT.
        val aspect = viewAspect()
        fun toWorldDir(d: FloatArray): FloatArray =
            floatArrayOf(d[0] * aspect, -d[1], 0f)

        val upScrRot = neckUpScr(true)
        val upScrStraight = neckUpScr(false)
        val upRot = toWorldDir(upScrRot)
        val upStraight = toWorldDir(upScrStraight)
        // Verrouillée, comme pour le bras : on ne tranche que si l'un des deux
        // candidats pointe franchement plus « vers le haut » que l'autre.
        val upScoreRot = normalized(upRot, floatArrayOf(0f, 0f, 0f))[1]
        val upScoreStraight = normalized(upStraight, floatArrayOf(0f, 0f, 0f))[1]
        val neckInstant: Boolean? =
            if (abs(upScoreRot - upScoreStraight) < kConventionMargin) {
                null
            } else {
                upScoreRot > upScoreStraight
            }
        val useRot = stickyNeckRot.update(neckInstant)
            ?: (upScoreRot >= upScoreStraight)
        val upScr = if (useRot) upScrRot else upScrStraight

        val shL = toScr(lm[11].x(), lm[11].y(), useRot)
        val shR = toScr(lm[12].x(), lm[12].y(), useRot)
        val shMidX = (shL[0] + shR[0]) * 0.5f
        val shMidY = (shL[1] + shR[1]) * 0.5f

        // --- Largeur RÉELLE du cou via le masque de segmentation ------------
        // On balaye la silhouette entre le milieu des épaules et le milieu des
        // oreilles, et on garde la largeur MINIMALE : le cou est par
        // construction l'endroit le plus étroit de ce trajet (les épaules en
        // bas, la tête en haut sont tous deux plus larges). Ce critère évite
        // d'avoir à régler une hauteur de balayage, qui aurait été un
        // paramètre de plus à deviner.
        val masksOpt = result.segmentationMasks()
        if (masksOpt.isPresent && masksOpt.get().isNotEmpty()) {
            val scanner = MaskScanner(masksOpt.get()[0], rot, kSegMaxWidthFrac)
            val sx = (lm[11].x() + lm[12].x()) * 0.5f
            val sy = (lm[11].y() + lm[12].y()) * 0.5f
            val ex = (lm[7].x() + lm[8].x()) * 0.5f
            val ey = (lm[7].y() + lm[8].y()) * 0.5f
            val dx = ex - sx
            val dy = ey - sy
            val rawMode = stickyNeckMask.update(
                scanner.probeRawMode(sx + dx * kNeckScanFrom, sy + dy * kNeckScanFrom),
            )
            var best: FloatArray? = null
            var bestW = Float.MAX_VALUE
            if (rawMode != null) {
                var f = kNeckScanFrom
                while (f <= kNeckScanTo) {
                    val e =
                        scanner.measureEdges(sx + dx * f, sy + dy * f, dx, dy, rawMode)
                    if (e != null) {
                        val e1 = toScr(e[0], e[1], useRot)
                        val e2 = toScr(e[2], e[3], useRot)
                        val v = floatArrayOf(
                            (e1[0] + e2[0]) * 0.5f,
                            (e1[1] + e2[1]) * 0.5f,
                            e1[0] - e2[0],
                            e1[1] - e2[1],
                        )
                        val w = hypot(v[2], v[3])
                        if (w in kSegMinWidthN..kSegMaxWidthN && w < bestW) {
                            bestW = w
                            best = v
                        }
                    }
                    f += kNeckScanStep
                }
            }
            if (best != null) {
                neckSeg = best
                neckSegMs = now
            }
            if (poseLogCount % 15 == 0) {
                Log.i(
                    TAG,
                    "segCou: mode=${rawMode ?: "hors masque"} " +
                        "largeur=${if (best != null) bestW else -1f} " +
                        "masque=${scanner.size}",
                )
            }
        }

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
            // Référence métrique du collier : la LIGNE D'ÉPAULES joue ici le
            // rôle que lm0→lm9 joue pour la main (cf. metricPlanar).
            val mp = hypot(w[11].x() - w[12].x(), w[11].y() - w[12].y())
            if (mp > 1e-5f) metricPlanar = mp
            val m3 = sqrt(
                (w[11].x() - w[12].x()) * (w[11].x() - w[12].x()) +
                    (w[11].y() - w[12].y()) * (w[11].y() - w[12].y()) +
                    (w[11].z() - w[12].z()) * (w[11].z() - w[12].z()),
            )
            if (m3 > 1e-5f) metric3D = m3
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
        val shVec = toWorldDir(floatArrayOf(shR[0] - shL[0], shR[1] - shL[1]))
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
        //
        // Le décalage vaut une fraction de la largeur d'épaules MESURÉE À
        // L'ÉCRAN : il vit donc en unités écran et doit suivre la direction
        // ÉCRAN du cou (upScr, y vers le bas), pas la direction monde — qui
        // n'est plus la même depuis la correction d'aspect.
        //
        // Les signes étaient de surcroît INVERSÉS sur les deux axes : monter
        // vers la tête, c'est ajouter upScr, et le code le soustrayait. Le
        // collier descendait donc de kNeckRise au lieu de monter, soit deux
        // fois l'écart voulu dans le mauvais sens. kNeckRise ayant pu être
        // réglé à l'œil sur ce comportement inversé, sa valeur est à revoir
        // visuellement maintenant qu'elle agit dans le sens annoncé.
        val shoulderW = hypot(shR[0] - shL[0], shR[1] - shL[1])
        val upScrLen = hypot(upScr[0], upScr[1]).coerceAtLeast(1e-4f)
        val riseX = upScr[0] / upScrLen
        val riseY = upScr[1] / upScrLen

        // Repères VISAGE en écran (bouche lm9/10, milieu des oreilles = milieu
        // des épaules + upScr). Le visage est TOUJOURS cadré pour un essayage
        // collier, contrairement aux épaules.
        val mthL = toScr(lm[9].x(), lm[9].y(), useRot)
        val mthR = toScr(lm[10].x(), lm[10].y(), useRot)
        val mouthX = (mthL[0] + mthR[0]) * 0.5f
        val mouthY = (mthL[1] + mthR[1]) * 0.5f
        val earMidX = shMidX + upScr[0]
        val earMidY = shMidY + upScr[1]
        // Longueur de demi-visage (oreilles→bouche) : l'unité d'échelle du
        // visage à l'écran, robuste à la distance et au cadrage.
        val faceLen = hypot(mouthX - earMidX, mouthY - earMidY).coerceAtLeast(1e-4f)
        val faceDownX = (mouthX - earMidX) / faceLen
        val faceDownY = (mouthY - earMidY) / faceLen

        // VALIDATION anatomique des épaules : une épaule réelle est TOUJOURS
        // nettement sous la bouche (y écran plus grand). Sinon, la pose les a
        // inventées trop haut (cadrage serré) → l'ancre milieu-épaules remontait
        // au visage et le collier se posait sur la bouche. Dans ce cas on dérive
        // l'ancre du visage : on descend sous le menton le long de l'axe du
        // visage jusqu'à la base du cou.
        val shouldersOk = shMidY > mouthY + kNeckShoulderGap * faceLen
        val ax: Float
        val ay: Float
        if (shouldersOk) {
            // Cas nominal : le milieu des épaules EST la base du cou (le creux
            // sternal est juste au-dessus → petite montée kNeckRise vers la tête).
            ax = (shMidX + riseX * kNeckRise * shoulderW).coerceIn(0f, 1f)
            ay = (shMidY + riseY * kNeckRise * shoulderW).coerceIn(0f, 1f)
        } else {
            // Repli VISAGE : base du cou ≈ kFaceToNeck demi-visages sous la
            // bouche, dans la direction du visage (robuste à l'inclinaison).
            ax = (mouthX + faceDownX * kFaceToNeck * faceLen).coerceIn(0f, 1f)
            ay = (mouthY + faceDownY * kFaceToNeck * faceLen).coerceIn(0f, 1f)
        }

        val px = fX.filter(ax, now)
        val py = fY.filter(ay, now)
        smX = px
        smY = py
        track = TrackSnapshot(px, py, fX.velocity, fY.velocity, result.timestampMs())

        // Bruts, comme pour la main : un seul lissage, sur la rotation
        // assemblée (cf. fQuat).
        axisVec = up
        normVec = side
        // Réutilise le canal "taille de main" pour la largeur d'épaules :
        // c'est la grandeur d'échelle de repli du collier.
        handDX = fHandDX.filter(shR[0] - shL[0], now)
        handDY = fHandDY.filter(shR[1] - shL[1], now)
        hasHand = true
        // Collier : c'est la pose qui porte tout le suivi, donc c'est elle qui
        // libère l'image de sa frame.
        publishFrame(result.timestampMs())

        if (poseLogCount++ % 15 == 0) {
            Log.i(
                TAG,
                "cou: conv=${if (useRot) "rot" else "droit"} " +
                    "ancre=($smX, $smY) source=${if (shouldersOk) "ÉPAULES" else "VISAGE"} " +
                    "épMidY=$shMidY boucheY=$mouthY demiVisage=$faceLen " +
                    "épaules=$shoulderW " +
                    "haut=(${axisVec[0]}, ${axisVec[1]}, ${axisVec[2]}) " +
                    "côté=(${normVec[0]}, ${normVec[1]}, ${normVec[2]})",
            )
        }
    }

    private fun onHands(result: HandLandmarkerResult) {
        val hands = result.landmarks()
        if (hands.isEmpty() || hands[0].size < 21) {
            hasHand = false
            // L'image doit continuer de défiler même sans main : sinon
            // l'utilisateur qui n'a pas encore cadré sa main verrait un écran
            // NOIR et croirait l'app cassée. C'est le bijou qui disparaît
            // faute de détection, pas la caméra.
            publishFrame(result.timestampMs())
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
            // Bague : lm13 = articulation MCP (base du doigt), lm14 = PIP
            // (première phalange). Une bague se porte JUSTE AU-DESSUS du MCP,
            // pas au milieu de la phalange — or le milieu de 13 et 14 tombait
            // à mi-phalange. L'écart passait inaperçu tant que la bague était
            // trop petite ; il ne le sera plus.
            val p13 = toScreen(lm[13])
            val p14 = toScreen(lm[14])
            rx = p13[0] + (p14[0] - p13[0]) * kRingAlongPhalanx
            ry = p13[1] + (p14[1] - p13[1]) * kRingAlongPhalanx
        }

        // Ancre en pixels IMAGE (repère du bitmap), pour le flux optique.
        val anchorImgX: Float
        val anchorImgY: Float
        if (anchor == "wrist") {
            anchorImgX = lm[0].x() * frameW
            anchorImgY = lm[0].y() * frameH
        } else {
            anchorImgX = (lm[13].x() + (lm[14].x() - lm[13].x()) * kRingAlongPhalanx) * frameW
            anchorImgY = (lm[13].y() + (lm[14].y() - lm[13].y()) * kRingAlongPhalanx) * frameH
        }

        // Le flux optique, quand il a quelque chose à dire, REMPLACE le
        // passe-bas : il calme le bruit par une mesure indépendante au lieu
        // d'une moyenne temporelle, donc sans le retard qui va avec. Les
        // filtres One-Euro continuent de tourner en parallèle — ils restent le
        // repli, et leur vitesse sert toujours à l'extrapolation.
        val fused = fuseWithFlow(
            rx.coerceIn(0f, 1f), ry.coerceIn(0f, 1f),
            anchorImgX, anchorImgY, result.timestampMs(),
        )
        val lpX = fX.filter(rx.coerceIn(0f, 1f), now)
        val lpY = fY.filter(ry.coerceIn(0f, 1f), now)
        val px = fused?.get(0) ?: lpX
        val py = fused?.get(1) ?: lpY
        smX = px
        smY = py
        // L'extrapolation n'a plus lieu ICI mais dans la boucle de rendu :
        // on publie l'état daté + la vitesse, le rendu l'avance jusqu'à SA
        // date (cf. TrackSnapshot et anchorAt).
        track = TrackSnapshot(px, py, fX.velocity, fY.velocity, result.timestampMs())
        // La pose de CETTE frame est posée : son image peut être montrée.
        publishFrame(result.timestampMs())

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
                // Le produit scalaire ci-dessous n'a de sens que depuis la
                // correction d'aspect : l'axe pose vient de coordonnées écran
                // et l'axe main des world landmarks métriques — deux repères
                // différents tant que le facteur halfW/halfH manquait, donc un
                // seuil qui ne mesurait pas l'angle qu'il croyait mesurer.
                val handAxis = worldDelta(w[9], w[0])
                val pa = poseAxis
                // Ce choix-ci est le plus dangereux du pipeline : l'ancien
                // seuil unique acceptait la pose jusqu'à 60° de l'axe main,
                // donc chaque bascule faisait pivoter le bijou d'un bloc. Avec
                // un seuil UNIQUE, une cohérence qui flotte autour de la limite
                // fait osciller la source à la fréquence du bruit.
                //
                // DÉCLENCHEUR DE SCHMITT : on n'ADOPTE la pose que sur une
                // cohérence franche (~25°), on ne l'ABANDONNE qu'une fois
                // nettement dégradée (~45°). Entre les deux, on garde la source
                // en cours. Le vote par-dessus interdit toute bascule sur une
                // frame isolée.
                val fresh = pa != null && now - poseAxisMs < kPoseFreshMs
                val dot = if (fresh) {
                    val pn = normalized(pa!!, fallback = handAxis)
                    val hn = normalized(handAxis, fallback = pn)
                    pn[0] * hn[0] + pn[1] * hn[1] + pn[2] * hn[2]
                } else {
                    -1f
                }
                val want = when {
                    !fresh -> false
                    dot >= kPoseEnterDot -> true
                    dot < kPoseExitDot -> false
                    else -> usePoseAxis
                }
                if (want == usePoseAxis) {
                    poseSourceVotes = 0
                } else if (++poseSourceVotes >= kSourceVotesToFlip) {
                    usePoseAxis = want
                    poseSourceVotes = 0
                    Log.i(TAG, "source de l'axe → ${if (want) "pose" else "main"} (dot=$dot)")
                }
                if (usePoseAxis && pa != null) {
                    axisRaw = pa
                    lastAxisSource = "pose(dot=$dot)"
                } else {
                    axisRaw = handAxis
                    lastAxisSource =
                        if (fresh) "main(dot=$dot)" else "main(pose absente)"
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

            // --- Largeur du DOIGT, mesurée sur le squelette métrique --------
            // L'écart entre deux articulations MCP voisines vaut sensiblement
            // la largeur d'un doigt (les doigts sont jointifs à la base).
            // Rapportée à la longueur de main, elle donne une proportion
            // propre à CET utilisateur — là où kRingPerHand appliquait la même
            // à tout le monde. Le rapport est calculé en 3D sur les world
            // landmarks, donc insensible au raccourcissement de perspective :
            // c'est justement ce qu'une mesure à l'écran ne saurait pas faire.
            fun metricDist(p: Landmark, q: Landmark): Float {
                val dx = p.x() - q.x()
                val dy = p.y() - q.y()
                val dz = p.z() - q.z()
                return sqrt(dx * dx + dy * dy + dz * dz)
            }
            val handLen3 = metricDist(w[9], w[0])
            if (handLen3 > 1e-5f) metric3D = handLen3
            if (handLen3 > 1e-5f) {
                val pitch =
                    0.5f * (metricDist(w[13], w[9]) + metricDist(w[17], w[13]))
                val raw = pitch / handLen3
                // Hors de cette fourchette, la détection est aberrante (main
                // repliée, doigts fusionnés) : on garde la valeur précédente.
                if (raw in kFingerPitchMin..kFingerPitchMax) {
                    fingerPitchRatio = fPitch.filter(raw, now)
                    // Rayon du doigt en MÈTRES, publié brut : c'est la médiane
                    // du verrou qui le stabilise. Un passe-bas de plus ne
                    // ferait que retarder une grandeur qui, une fois
                    // verrouillée, ne bouge plus du tout.
                    fingerRadiusMetric = 0.5f * pitch
                }
            }

            // Longueur planaire métrique de référence (cf. metricPlanar). La
            // magnitude en x,y est invariante à la rotation d'image appliquée
            // par displayVec (rotations de 90° + miroir), donc lisible
            // directement sur les world landmarks sans conversion.
            val mp = hypot(w[9].x() - w[0].x(), w[9].y() - w[0].y())
            if (mp > 1e-5f) metricPlanar = mp

            // Publiés BRUTS : le lissage a lieu une seule fois, sur la rotation
            // assemblée (cf. fQuat). Filtrer ici ferait exactement ce qu'on
            // cherche à supprimer — deux directions lissées séparément, donc
            // deux retards différents dans une même pose.
            axisVec = normalized(axisRaw, fallback = axisVec)
            normVec = normN
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

    // --- Affichage LOCKSTEP -------------------------------------------------

    /** Frames analysées en attente de leur résultat de détection. Un anneau
     *  suffit : MediaPipe en LIVE_STREAM jette les frames quand il est occupé,
     *  donc certaines n'auront jamais de résultat et doivent simplement être
     *  écrasées. Écrit par le thread d'analyse, lu par celui de détection. */
    // --- Fusion flux optique (cf. FlowTracker) ------------------------------

    private val flowTracker = FlowTracker(kFlowWin, kFlowWin / 2)

    /** Ancre fusionnée de la frame précédente (écran normalisé), position
     *  brute correspondante, et horodatage — le tout en repère de détection. */
    private var flowAnchorX = -1f
    private var flowAnchorY = -1f
    private var flowPrevTs = -1L
    private var flowHit = 0L
    private var flowMiss = 0L

    /**
     * Fusionne la mesure brute du réseau avec le déplacement mesuré par flux
     * optique, et renvoie l'ancre à utiliser (écran normalisé).
     *
     * Le filtre est complémentaire : on PRÉDIT par le flux — précis à court
     * terme, sans retard — puis on CORRIGE faiblement vers la mesure du réseau
     * — bruitée mais sans dérive. Le résultat suit le mouvement réel
     * immédiatement tout en restant ancré sur l'anatomie détectée.
     *
     * C'est ce qui remplace le passe-bas : là où le One-Euro n'avait d'autre
     * moyen de calmer le bruit que de retarder le signal, ici le bruit est
     * écarté par une mesure INDÉPENDANTE, pas par une moyenne temporelle.
     *
     * Renvoie null quand le flux n'a rien d'exploitable — l'appelant retombe
     * alors sur le comportement One-Euro, jamais pire qu'avant.
     */
    private fun fuseWithFlow(
        rawX: Float,
        rawY: Float,
        imgX: Float,
        imgY: Float,
        ts: Long,
    ): FloatArray? {
        if (!flowEnabled) return null
        val bmp = frameFor(ts) ?: return null

        // L'amorce est calculée par le tracker lui-même, à partir des deux
        // ancres successives en pixels IMAGE (cf. track).
        val ok = flowAnchorX >= 0f && flowPrevTs >= 0L
        val d = flowTracker.track(bmp, ts, imgX, imgY, flowPrevTs)
        flowPrevTs = ts

        if (d == null || !ok) {
            flowMiss++
            // Réamorçage : la prochaine frame repartira de la mesure réseau.
            flowAnchorX = rawX
            flowAnchorY = rawY
            return null
        }
        flowHit++

        // Déplacement image → écran normalisé, via la conversion PROUVÉE : le
        // flux vit en pixels capteur, l'ancre en espace d'affichage, et les
        // deux ne diffèrent pas que d'un facteur d'échelle (rotation, miroir,
        // recadrage). Convertir deux points et prendre leur écart est le seul
        // moyen sûr de ne pas réintroduire une convention à la main.
        val p0 = sensorToDisplay(imgX / frameW, imgY / frameH)
        val p1 = sensorToDisplay((imgX + d[0]) / frameW, (imgY + d[1]) / frameH)

        val predX = flowAnchorX + (p1[0] - p0[0])
        val predY = flowAnchorY + (p1[1] - p0[1])
        val fx = (predX + kFlowCorrect * (rawX - predX)).coerceIn(0f, 1f)
        val fy = (predY + kFlowCorrect * (rawY - predY)).coerceIn(0f, 1f)
        flowAnchorX = fx
        flowAnchorY = fy

        if ((flowHit + flowMiss) % 90 == 0L) {
            Log.i(
                TAG,
                "flux: suivies=$flowHit perdues=$flowMiss " +
                    "déplacement=(${d[0]}, ${d[1]}) px " +
                    "écartRéseau=(${rawX - fx}, ${rawY - fy})",
            )
        }
        return floatArrayOf(fx, fy)
    }

    /** Compteurs de l'affichage lockstep (threads de détection uniquement). */
    private var frameHit = 0L
    private var frameMiss = 0L

    private val frameLock = Any()
    private val frameTs = LongArray(kFrameRing)
    private val frameBmp = arrayOfNulls<Bitmap>(kFrameRing)
    private var frameIdx = 0

    private fun storeFrame(ts: Long, bmp: Bitmap) {
        synchronized(frameLock) {
            frameTs[frameIdx] = ts
            frameBmp[frameIdx] = bmp
            frameIdx = (frameIdx + 1) % kFrameRing
        }
    }

    /** Frame correspondant EXACTEMENT à [ts], ou null si elle a déjà été
     *  écrasée (détection trop lente : on garde alors l'image précédente
     *  plutôt que d'en afficher une désynchronisée). */
    private fun frameFor(ts: Long): Bitmap? = synchronized(frameLock) {
        for (i in frameBmp.indices) if (frameTs[i] == ts) return@synchronized frameBmp[i]
        null
    }

    /** Publie la frame dont la détection vient d'aboutir. Appelé depuis les
     *  callbacks de détection, juste après l'écriture de la pose : image et
     *  pose deviennent visibles ensemble. */
    private fun publishFrame(ts: Long) {
        if (!lockstep) return
        val bmp = frameFor(ts)
        if (bmp == null) {
            // La frame a été écrasée avant que sa détection n'aboutisse : on
            // garde l'image précédente plutôt que d'en afficher une
            // désynchronisée. Compté, parce qu'un taux élevé signifie que
            // kFrameRing est trop court pour la latence réelle du device —
            // diagnostic impossible à poser autrement.
            frameMiss++
            return
        }
        frameHit++
        shownFrame = bmp
        frameView?.postInvalidateOnAnimation()
        if ((frameHit + frameMiss) % 90 == 0L) {
            Log.i(
                TAG,
                "lockstep: frames affichées=$frameHit manquées=$frameMiss " +
                    "(anneau=$kFrameRing, ${frameW}x$frameH)",
            )
        }
    }

    /** Matrice image→écran, construite en faisant passer TROIS COINS de
     *  l'image par [sensorToDisplay] — la conversion utilisée par le suivi.
     *
     *  Trois points définissent exactement une affine, et les prendre de cette
     *  façon rend la géométrie de l'affichage IDENTIQUE PAR CONSTRUCTION à
     *  celle des landmarks : rotation, recadrage et miroir ne peuvent plus
     *  diverger entre ce qu'on montre et ce qu'on suit. Redériver ces
     *  formules à la main aurait rouvert toute la famille de bugs
     *  « image à l'envers / en miroir » que la chaîne toScreen a déjà payés.
     *
     *  C'est aussi un gain de justesse : le chemin historique SUPPOSE que le
     *  recadrage FILL_CENTER de PreviewView coïncide avec les facteurs cx/cy
     *  calculés ici. Ici on ne le suppose plus, on l'impose. */
    private fun buildFrameMatrix(bmp: Bitmap, viewW: Int, viewH: Int): Boolean {
        if (viewW <= 0 || viewH <= 0 || frameW <= 0 || frameH <= 0) return false
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        if (bw <= 0f || bh <= 0f) return false
        val src = floatArrayOf(0f, 0f, bw, 0f, 0f, bh)
        val unit = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
        val dst = FloatArray(6)
        for (i in 0 until 3) {
            val p = sensorToDisplay(unit[i * 2], unit[i * 2 + 1])
            dst[i * 2] = p[0] * viewW
            dst[i * 2 + 1] = p[1] * viewH
        }
        frameMatrix.reset()
        return frameMatrix.setPolyToPoly(src, 0, dst, 0, 3)
    }

    /** Point normalisé de l'espace CAPTEUR vers l'espace d'AFFICHAGE :
     *  rotation, recadrage centré, miroir frontal. Extrait de `toScreen` afin
     *  que suivi et affichage partagent une seule et même convention. */
    private fun sensorToDisplay(x: Float, y: Float): FloatArray {
        val rot = frameRotation
        val ux: Float
        val uy: Float
        when (rot) {
            90 -> { ux = 1f - y; uy = x }
            180 -> { ux = 1f - x; uy = 1f - y }
            270 -> { ux = y; uy = 1f - x }
            else -> { ux = x; uy = y }
        }
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
        return floatArrayOf(
            1f - (0.5f + (ux - 0.5f) * cx),
            0.5f + (uy - 0.5f) * cy,
        )
    }

    /** Ancre extrapolée à MAINTENANT, en espace écran normalisé.
     *
     *  Deux problèmes distincts sont réglés ici, et c'est le même calcul qui
     *  les règle tous les deux :
     *
     *  1. LE PAS D'ESCALIER. La détection tourne à 15-30 Hz, la boucle de rendu
     *     à 60 fps. L'ancre n'étant recalculée qu'à la détection, elle restait
     *     FIGÉE pendant deux à quatre frames de rendu puis sautait — un
     *     déplacement en escalier, alors que la vidéo dessous avance
     *     continûment. Extrapoler à la date du RENDU redonne un mouvement lisse
     *     à 60 fps.
     *
     *  2. LA LATENCE. L'aperçu caméra s'affiche sans délai, alors que le bijou
     *     décrit une frame vieille de 60 à 150 ms : c'est ce décalage qui fait
     *     « glisser » le bijou dès que la main bouge. L'âge de l'instantané EST
     *     ce délai, mesuré directement — plus besoin de l'estimer par une
     *     constante réglée à la main, qui était forcément fausse dès que le
     *     pipeline changeait de vitesse (passage de la pose sur GPU, device
     *     plus lent, charge thermique).
     *
     *  L'extrapolation échange du retard contre de la gigue, la vitesse étant
     *  la sortie la plus bruitée d'un One-Euro : l'âge et le déplacement sont
     *  donc tous deux bornés. Sans instantané (aucune détection encore), on
     *  retombe sur la dernière ancre filtrée. */
    private fun anchorAt(nowMs: Long): FloatArray {
        val s = track ?: return floatArrayOf(smX, smY)
        val age = ((nowMs - s.frameTimeMs).toFloat() / 1000f)
            .coerceIn(0f, kMaxLatencySec)
        lastAnchorAgeMs = age * 1000f
        return floatArrayOf(
            (s.x + (s.vx * age).coerceIn(-kMaxLead, kMaxLead)).coerceIn(0f, 1f),
            (s.y + (s.vy * age).coerceIn(-kMaxLead, kMaxLead)).coerceIn(0f, 1f),
        )
    }

    private fun resetFilters() {
        fX.reset()
        fY.reset()
        fHandDX.reset()
        fHandDY.reset()
        // PAS fQuat.reset() ici : resetFilters s'exécute sur le thread de
        // DÉTECTION, alors que fQuat est filtré par la boucle de RENDU. Toucher
        // son état interne d'ici serait une course — sans crash, mais avec des
        // à-coups d'orientation impossibles à reproduire. On signale la demande,
        // le thread propriétaire l'exécute.
        poseResetPending = true
        // Le filtre repart de zéro, mais PAS fingerPitchRatio : c'est une
        // constante morphologique, la bague ne doit pas changer de taille
        // parce que la main est sortie du champ une seconde.
        fPitch.reset()
        // Le signe de la normale se réinitialisera "face caméra".
        prevNormal = null
        // Instantané périmé : sans ça, la reprise extrapolerait depuis une
        // vitesse vieille de plusieurs centaines de millisecondes.
        track = null
        // Le flux relie deux frames CONSÉCUTIVES : après une coupure, la
        // précédente n'a plus rien à voir avec la suivante.
        flowTracker.reset()
        flowAnchorX = -1f
        flowPrevTs = -1L
    }

    // --- petites aides vectorielles ----------------------------------------

    /** Rapport largeur/hauteur de la vue = halfW/halfH.
     *
     *  Les coordonnées écran normalisées sont normalisées SÉPARÉMENT sur
     *  chaque axe ([0..1] en largeur, [0..1] en hauteur) : elles sont donc
     *  anisotropes, et un même écart en x et en y ne représente pas la même
     *  distance monde. Les POSITIONS et les LARGEURS mesurées appliquent déjà
     *  la conversion (`× 2·halfW` / `× 2·halfH`) ; les AXES l'omettaient, ce
     *  qui faussait l'orientation d'autant que l'écran est allongé — jusqu'à
     *  ~20° en diagonale sur un 20:9, nul quand le membre est vertical ou
     *  horizontal (d'où un défaut qui échappe aux essais les plus naturels).
     *  Comme les axes sont normalisés ensuite, seul le RAPPORT compte : un
     *  seul facteur sur x suffit. */
    private fun viewAspect(): Float {
        val w = root.width
        val h = root.height
        return if (w > 0 && h > 0) w.toFloat() / h.toFloat() else 1f
    }

    /** Quaternion (x, y, z, w) d'une base orthonormée donnée par ses COLONNES.
     *
     *  Méthode de Shepperd : on extrait la composante la plus grande d'abord.
     *  La formule directe par la trace perd toute précision quand celle-ci
     *  approche −1 (rotation de ~180°, ici un poignet retourné), au point de
     *  produire un quaternion non unitaire — donc une base cisaillée. */
    private fun quatFromBasis(
        cx: FloatArray,
        cy: FloatArray,
        cz: FloatArray,
    ): FloatArray {
        val m00 = cx[0]; val m10 = cx[1]; val m20 = cx[2]
        val m01 = cy[0]; val m11 = cy[1]; val m21 = cy[2]
        val m02 = cz[0]; val m12 = cz[1]; val m22 = cz[2]
        val tr = m00 + m11 + m22
        return when {
            tr > 0f -> {
                val s = sqrt(tr + 1f) * 2f
                floatArrayOf(
                    (m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s,
                )
            }
            m00 > m11 && m00 > m22 -> {
                val s = sqrt(1f + m00 - m11 - m22) * 2f
                floatArrayOf(
                    0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s,
                )
            }
            m11 > m22 -> {
                val s = sqrt(1f + m11 - m00 - m22) * 2f
                floatArrayOf(
                    (m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s,
                )
            }
            else -> {
                val s = sqrt(1f + m22 - m00 - m11) * 2f
                floatArrayOf(
                    (m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s,
                )
            }
        }
    }

    /** Colonnes de la base orthonormée d'un quaternion (x, y, z, w).
     *  Orthonormée par construction : c'est ce qui garantit qu'aucune étape du
     *  lissage ne peut déformer le bijou. */
    private fun basisFromQuat(q: FloatArray): Array<FloatArray> {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        return arrayOf(
            floatArrayOf(
                1f - 2f * (y * y + z * z), 2f * (x * y + w * z), 2f * (x * z - w * y),
            ),
            floatArrayOf(
                2f * (x * y - w * z), 1f - 2f * (x * x + z * z), 2f * (y * z + w * x),
            ),
            floatArrayOf(
                2f * (x * z + w * y), 2f * (y * z - w * x), 1f - 2f * (x * x + y * y),
            ),
        )
    }

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
                // MÊME rapport d'aspect imposé aux DEUX flux. Sans cette
                // contrainte, CameraX est libre de servir du 4:3 à l'analyse et
                // du 16:9 à la preview : les landmarks seraient alors mesurés
                // dans une géométrie et affichés dans une autre. Or le
                // recadrage cx/cy (cf. toScreen) est calculé sur la frame
                // d'ANALYSE et appliqué à ce qu'affiche la PREVIEW — l'erreur
                // serait nulle au centre de l'écran et croissante vers les
                // bords, un décalage très pénible à diagnostiquer à l'œil.
                val resBuilder = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY,
                    )
                if (lockstep) {
                    // En lockstep, la frame d'ANALYSE est aussi la frame
                    // AFFICHÉE : sa résolution devient donc celle de l'image
                    // que voit l'utilisateur. Au défaut (~640×480), le portrait
                    // n'offre que 480 px de large étirés sur une dalle de
                    // 1080 — 2,25× d'agrandissement, franchement mou. En
                    // 1280×960 l'agrandissement tombe à ~1,12×, quasi
                    // imperceptible.
                    //
                    // Le surcoût est modeste là où on pourrait le craindre :
                    // MediaPipe redimensionne en interne vers l'entrée fixe de
                    // son modèle, donc l'INFÉRENCE ne dépend pas de la taille
                    // fournie. Ne montent que la conversion en bitmap et le
                    // transfert GPU.
                    //
                    // Appliqué UNIQUEMENT en lockstep : l'interrupteur éteint
                    // doit rendre un pipeline rigoureusement identique à celui
                    // d'avant, sans quoi comparer les deux modes ne voudrait
                    // plus rien dire.
                    resBuilder.setResolutionStrategy(
                        ResolutionStrategy(
                            kLockstepAnalysisSize,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                }
                val resolution = resBuilder.build()
                // En lockstep, la caméra n'est PAS liée à un aperçu : c'est le
                // flux d'analyse lui-même qui est affiché, une fois sa
                // détection terminée. Un cas d'usage en moins pour le capteur,
                // et surtout plus aucune hypothèse sur la façon dont
                // PreviewView recadre son image.
                val preview = previewView?.let { pv ->
                    Preview.Builder()
                        .setResolutionSelector(resolution)
                        .build()
                        .also { it.setSurfaceProvider(pv.surfaceProvider) }
                }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, ::analyze) }
                provider.unbindAll()
                val useCases = listOfNotNull(preview, analysis).toTypedArray()
                val camera = provider.bindToLifecycle(
                    activity as LifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    *useCases,
                )
                readCameraFov(camera.cameraInfo)
                // Les deux résolutions DOIVENT avoir le même rapport d'aspect :
                // c'est l'hypothèse sur laquelle repose toute la conversion
                // landmark → écran. Journalisé pour pouvoir le vérifier au
                // lieu de l'espérer.
                val pr = preview?.resolutionInfo?.resolution
                val ar = analysis.resolutionInfo?.resolution
                val pa = pr?.let { it.width.toFloat() / it.height }
                val aa = ar?.let { it.width.toFloat() / it.height }
                Log.i(
                    TAG,
                    "Caméra liée — preview=$pr (aspect=$pa) " +
                        "analyse=$ar (aspect=$aa) " +
                        if (pa != null && aa != null && abs(pa - aa) > 0.02f) {
                            "*** ASPECTS DIFFÉRENTS : le mapping écran sera faux ***"
                        } else {
                            "aspects cohérents"
                        },
                )
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
            // L'horodatage est établi AVANT tout le reste : c'est la clé qui
            // relie la frame, son résultat de détection et son affichage.
            val ts = SystemClock.uptimeMillis()
            val bitmap = image.toBitmap()
            // Conservée pour l'affichage lockstep : elle ne sera montrée que
            // lorsque SA détection aboutira (cf. publishFrame).
            // Le flux optique a besoin de l'image, lui aussi : c'est le même
            // anneau qui la lui fournit.
            if (lockstep || flowEnabled) storeFrame(ts, bitmap)
            val mpImage = BitmapImageBuilder(bitmap).build()
            val opts = ImageProcessingOptions.builder()
                .setRotationDegrees(image.imageInfo.rotationDegrees)
                .build()
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

    /** Lit `<nom>.fit.json`, posé à côté du GLB par tool/fit_analyzer.dart.
     *  Absent = repli sur les constantes intégrées, pas une erreur : un asset
     *  non encore analysé doit continuer de s'afficher. */
    private fun loadFit(): JewelFit? {
        if (assetPath.isEmpty()) return null
        val path = "flutter_assets/" +
            assetPath.removeSuffix(".glb") + ".fit.json"
        return try {
            val txt = activity.assets.open(path)
                .use { it.readBytes() }
                .toString(Charsets.UTF_8)
            JewelFit(JSONObject(txt)).also {
                Log.i(
                    TAG,
                    "fit chargé: axe=${it.holeAxis} " +
                        "trou=${it.holeRadiusMin}..${it.holeRadiusMax} " +
                        "latéral=${it.radiusToward("X")} " +
                        "centre=(${it.holeCenter[0]}, ${it.holeCenter[1]}, " +
                        "${it.holeCenter[2]}) " +
                        "demiHauteur=${it.axisHalfExtent} " +
                        "ouverture=${it.openingAt}@${it.openingRadius}",
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "pas de $path — repli sur les constantes intégrées", e)
            null
        }
    }

    private fun loadModel() {
        try {
            val bytes = activity.assets
                .open("flutter_assets/$assetPath")
                .use { it.readBytes() }
            modelViewer.loadModelGlb(bytes.toDirectByteBuffer())
            // Priorité EXPLICITE : le bijou doit passer APRÈS l'occluseur, qui
            // lui écrit la profondeur. Laisser la valeur par défaut ne suffit
            // pas — c'est justement l'égalité de priorité, combinée à un
            // centroïde identique, qui rendait l'ordre indéterminé.
            val rm = modelViewer.engine.renderableManager
            var n = 0
            modelViewer.asset?.entities?.forEach { e ->
                val ri = rm.getInstance(e)
                if (ri != 0) {
                    rm.setPriority(ri, kPriorityJewel)
                    n++
                }
            }
            Log.i(
                TAG,
                "modèle chargé: $assetPath (${bytes.size} o, " +
                    "$n renderables, priorité=$kPriorityJewel)",
            )
        } catch (e: Exception) {
            Log.e(TAG, "échec chargement modèle: $assetPath", e)
        }
    }

    /** Charge un GLB auxiliaire (occluseur, ombre) via un AssetLoader dédié et
     *  laisse l'appelant configurer ses matériaux. Les loaders sont conservés
     *  vivants : Filament panique si on détruit un matériau dont des instances
     *  survivent (cf. dispose). */
    private fun loadAuxAsset(
        subPath: String,
        label: String,
        priority: Int,
        configure: (MaterialInstance) -> Unit,
    ): FilamentAsset? = try {
        val bytes = activity.assets
            .open("flutter_assets/$subPath")
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
        var renderables = 0
        var primitives = 0
        for (entity in asset.entities) {
            val ri = rm.getInstance(entity)
            if (ri == 0) continue
            renderables++
            // ORDRE DE DESSIN. Filament trie la passe opaque d'avant en
            // arrière PAR CENTROÏDE. L'occluseur et le bijou partagent
            // exactement le même centre : l'ordre était donc indéterminé. Or un
            // occulteur de profondeur DOIT passer avant ce qu'il masque —
            // sinon le bijou écrit d'abord sa couleur, l'occulteur écrit
            // ensuite la profondeur sans la couleur (colorWrite=false), et le
            // dos du bijou reste affiché. C'est très exactement le symptôme
            // observé : anneau visible à 360° et ombre de contact en anneau
            // COMPLET au lieu d'un demi-anneau.
            rm.setPriority(ri, priority)
            for (p in 0 until rm.getPrimitiveCount(ri)) {
                configure(rm.getMaterialInstanceAt(ri, p))
                primitives++
            }
        }
        modelViewer.scene.addEntities(asset.entities)
        auxProviders += provider
        auxLoaders += loader
        auxResourceLoaders += resourceLoader
        auxAssets += asset
        Log.i(
            TAG,
            "$label chargé : ${asset.entities.size} entités, " +
                "$renderables renderables, $primitives primitives, " +
                "priorité=$priority" +
                if (renderables == 0) " *** AUCUN RENDERABLE, NE RENDRA PAS ***" else "",
        )
        asset
    } catch (e: Exception) {
        Log.e(TAG, "échec chargement $label", e)
        null
    }

    /** Cylindre occluseur (rayon 1, hauteur 1 le long de Y), basculé en
     *  écriture de PROFONDEUR SEULE : invisible, mais tout fragment situé
     *  derrière lui est masqué. */
    private fun loadOccluder() {
        occAsset = loadAuxAsset(
            "assets/occluders/cylinder.glb",
            "occluseur (debug=$debugOccluder)",
            kPriorityOccluder,
        ) { mi ->
            // En mode debug, le cylindre reste visible (rouge).
            if (!debugOccluder) mi.setColorWrite(false)
            mi.setDepthWrite(true)
        }
    }

    /** Jupe d'ombre de contact (cf. tool/gen_contact_shadow.dart) : noire, en
     *  alpha dégradé, posée juste au-dessus de la peau au-delà des bords du
     *  bijou. C'est le cylindre occluseur qui en élimine la moitié arrière ;
     *  ne subsiste que celle tournée vers la caméra, ce qui se lit comme un
     *  assombrissement de la peau visible. */
    private fun loadContactShadow() {
        shadowAsset = loadAuxAsset(
            "assets/occluders/contact_shadow.glb",
            "ombre de contact",
            kPriorityShadow,
        ) { mi ->
            // Surtout PAS d'écriture de profondeur : la jupe est transparente,
            // si elle écrivait la profondeur elle masquerait le bijou.
            mi.setDepthWrite(false)
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
            ibl?.intensity = kIblLux
            modelViewer.scene.indirectLight = ibl
        } catch (e: Exception) {
            Log.e(TAG, "échec chargement IBL", e)
        }

        // --- Lumière directionnelle -----------------------------------------
        // La scène n'en contenait AUCUNE : uniquement l'IBL, c'est-à-dire un
        // éclairage parfaitement uniforme. Un métal éclairé uniformément n'a
        // pas de reflet franc — il rend comme une texture plate, ce qui est
        // l'un des plus gros contributeurs à l'effet "autocollant" (le cerveau
        // lit l'incohérence des spéculaires avant de savoir la nommer).
        //
        // La direction réelle de la lumière de la pièce est inconnue. On prend
        // le cas de très loin le plus fréquent en selfie : source au-dessus et
        // légèrement derrière l'appareil (plafonnier, fenêtre). C'est faux
        // parfois, mais uniformément faux est PIRE que approximativement juste :
        // sans direction, le bijou ne réagit à aucun mouvement.
        try {
            val engine = modelViewer.engine
            val sun = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(1.0f, 0.97f, 0.92f) // légèrement chaud, comme un intérieur
                .intensity(kSunLux)
                .direction(kSunDir[0], kSunDir[1], kSunDir[2])
                .castShadows(true) // le chaton d'une bague se projette sur l'anneau
                .build(engine, sun)
            modelViewer.scene.addEntity(sun)
            sunLight = sun
            Log.i(TAG, "lumière directionnelle ajoutée (${kSunLux.toInt()} lx)")
        } catch (e: Exception) {
            Log.e(TAG, "échec création lumière directionnelle", e)
        }
    }

    override fun getView(): View = root

    /** Relâche les MaterialInstance des assets auxiliaires (occluseur/ombre)
     *  UNE seule fois, pendant que le moteur Filament vit encore. Appelé soit
     *  par notre listener de détachement (le cas normal, AVANT le destroy du
     *  ModelViewer), soit par dispose() en repli. On ne détruit QUE les
     *  instances (destroyAsset) : les matériaux seront nettoyés par
     *  Engine.destroy — les détruire ici aussi ferait un double-free. */
    private fun destroyAuxAssetsOnce(reason: String) {
        if (auxDestroyed) return
        auxDestroyed = true
        // Coupe le rendu de CETTE instance : plus aucune frame ne référencera
        // les entités qu'on s'apprête à détruire.
        choreographer.removeFrameCallback(frameCallback)
        Log.i(
            TAG,
            "CYCLE ► #$instanceId destruction de ${auxAssets.size} asset(s) " +
                "auxiliaire(s) [$reason]…",
        )
        try {
            for (i in auxAssets.indices) {
                auxLoaders.getOrNull(i)?.destroyAsset(auxAssets[i])
            }
            Log.i(TAG, "CYCLE ► #$instanceId assets auxiliaires détruits OK [$reason]")
        } catch (e: Exception) {
            Log.w(TAG, "CYCLE ► #$instanceId nettoyage des assets auxiliaires ÉCHOUÉ", e)
        }
        auxAssets.clear()
    }

    override fun dispose() {
        val ageMs = SystemClock.uptimeMillis() - createdAtMs
        Log.w(
            TAG,
            "CYCLE ► instance #$instanceId DISPOSE appelé après ${ageMs}ms de vie " +
                "(si c'est quelques centaines de ms → RECRÉATION de la vue, pas " +
                "une sortie volontaire)",
        )
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
        // DESTRUCTION DES ASSETS AUXILIAIRES — impérative, et AVANT tout le
        // reste, car le ModelViewer (filament-utils) enregistre son propre
        // listener de détachement qui appelle destroy()→Engine.destroy() dès
        // que la SurfaceView quitte la fenêtre. Flutter appelle notre dispose()
        // juste avant ce retrait de vue : c'est notre seule fenêtre pour
        // relâcher les MaterialInstance de l'occluseur/ombre pendant que le
        // moteur vit encore. Sans ça, Engine.destroy trouve une instance
        // base_lit_opaque vivante et panique (SIGABRT observé en quittant
        // l'écran). On ne détruit QUE les instances (destroyAsset) : les
        // matériaux eux-mêmes seront nettoyés par Engine.destroy, les détruire
        // ici aussi ferait un double-free.
        destroyAuxAssetsOnce("dispose")
        // Frames retenues pour le lockstep : quelques mégaoctets qui n'ont
        // plus de raison d'être. On ne les recycle PAS (le thread UI peut
        // encore être en train d'en dessiner une) — on lâche les références,
        // le ramasse-miettes fera le reste au bon moment.
        shownFrame = null
        synchronized(frameLock) { frameBmp.fill(null) }
        occAsset = null
        shadowAsset = null
        auxResourceLoaders.clear()
        auxLoaders.clear()
        auxProviders.clear()
    }

    private fun ByteArray.toDirectByteBuffer(): ByteBuffer =
        ByteBuffer.allocateDirect(size).apply {
            put(this@toDirectByteBuffer)
            rewind()
        }

    companion object {
        private const val TAG = "NativeArView"
        /** Nombre de NativeArView construites depuis le lancement (trace du
         *  cycle de vie / des recréations de PlatformView, cf. instanceId). */
        private var instanceCounter = 0
        private const val MODEL_ASSET = "hand_landmarker.task"
        private const val POSE_MODEL_ASSET = "pose_landmarker_lite.task"

        /** Fraîcheur max de l'axe avant-bras pose avant repli sur la main. */
        private const val kPoseFreshMs = 600L

        /** Distance² max (image normalisée) entre poignet pose et poignet
         *  main pour accepter la détection de pose. */
        private const val kPoseMaxWristDistSq = 0.25f * 0.25f

        /** Seuils de Schmitt sur la cohérence (cosinus) entre axe pose et axe
         *  main. On ADOPTE la pose à ~25° d'écart, on ne l'ABANDONNE qu'à
         *  ~45°. L'ancien seuil unique de 0.5 (60°) laissait la source osciller
         *  dès que la cohérence flottait autour de la limite — et chaque
         *  oscillation faisait pivoter le bijou d'un bloc. */
        private const val kPoseEnterDot = 0.90f
        private const val kPoseExitDot = 0.70f

        /** Frames contraires consécutives avant de changer de source d'axe. */
        private const val kSourceVotesToFlip = 6

        /** Frames contraires consécutives avant de rouvrir une convention de
         *  repère, et écart de score minimal pour qu'une frame ait voix au
         *  chapitre. Généreux : ces conventions ne changent pas en cours de
         *  session, une bascule est presque toujours du bruit. */
        private const val kConventionVotes = 10
        private const val kConventionMargin = 0.15f

        /** Nombre de mesures accumulées avant de FIGER la morphologie.
         *
         *  Le masque n'aboutit pas à toutes les frames et la pose ne tourne
         *  qu'une frame d'analyse sur deux : ~40 mesures représentent quelques
         *  secondes de cadrage correct — assez pour que la médiane soit
         *  robuste, assez peu pour que l'utilisateur n'attende pas. */
        /** Frames d'analyse gardées en attente de leur détection (mode
         *  lockstep).
         *
         *  Doit couvrir la latence du pipeline exprimée en FRAMES : à 30 Hz et
         *  ~150 ms, trois à quatre frames sont stockées entre la soumission
         *  d'une image et le retour de sa détection. Trop court, l'image
         *  cherchée a déjà été écrasée et l'affichage saute la frame — soit
         *  précisément le hoquet qu'on veut supprimer. Six laisse une marge
         *  pour les pics de charge, au prix de ~29 Mo à 1280×960. */
        private const val kFrameRing = 6

        /** Résolution d'analyse demandée en lockstep (cf. startCamera). C'est
         *  LE curseur netteté ⇄ coût : la baisser adoucit l'image, la monter
         *  alourdit la conversion bitmap et le transfert GPU (mais pas
         *  l'inférence). Rapport 4:3 obligatoire, comme le reste du pipeline. */
        private val kLockstepAnalysisSize = Size(1280, 960)

        /** Côté de la fenêtre extraite pour le flux optique (pixels image).
         *  Doit contenir la grille de points suivis et leurs fenêtres de
         *  corrélation, plus la marge du déplacement inter-frame. */
        private const val kFlowWin = 96

        /** Gain de correction vers la mesure du réseau, par frame.
         *
         *  C'est LE curseur du filtre complémentaire. Bas = on fait confiance
         *  au flux (fluide, mais la dérive met du temps à être rattrapée) ;
         *  haut = on revient vite au réseau, en réimportant son bruit. 0.15
         *  rattrape un écart en ~6 frames (~0,2 s) : assez lent pour filtrer le
         *  tremblement de la régression, assez vif pour qu'aucune dérive du
         *  flux ne devienne visible. */
        private const val kFlowCorrect = 0.15f

        private const val kLockSamples = 40

        /** Écart au-delà duquel une mesure CONTREDIT la morphologie figée.
         *  1.35 est très au-dessus du bruit de mesure ordinaire (le garde-fou
         *  de vraisemblance accepte déjà 0.60–1.70 autour de l'anatomique) :
         *  seul un verrouillage réellement raté peut le franchir durablement. */
        private const val kRelockRatio = 1.35f

        /** Confiance minimale publiée par le modèle de pose pour accepter le
         *  poignet/coude. Abaissée de 0.6 à 0.35 d'après les LOGS DEVICE : dans
         *  le cadrage réel d'essayage (bras levé, main proche du visage), la
         *  pose publie une confiance de 0.22–0.56 pour le poignet — pourtant le
         *  bras est bien visible. À 0.6, la quasi-totalité des frames étaient
         *  rejetées → aucun axe d'avant-bras, aucun balayage de silhouette, donc
         *  la taille retombait sur l'estimation main (instable) : c'est LA cause
         *  de l'effondrement observé. À 0.35 on n'écarte plus que les
         *  articulations franchement inventées, et le pipeline silhouette reçoit
         *  enfin des données. L'axe garde ses garde-fous en aval (arbitrage
         *  pose/main à hystérésis, roulis contraint par la silhouette). */
        private const val kPoseMinVisibility = 0.35f

        /** Seuil de visibilité des épaules pour le collier. Bien plus bas que
         *  celui du bras : le collier n'a pas de détecteur de secours, donc on
         *  ne rejette que les épaules quasi certainement inventées, et on laisse
         *  le lissage renforcé absorber le reste (cf. onPoseNeck). */
        private const val kNeckMinVisibility = 0.3f

        /** Longueur planaire minimale coude→poignet (écran normalisé) pour
         *  tirer une direction. En dessous, les deux points se superposent en
         *  projection et la direction est du bruit divisé par ~zéro. */
        private const val kPoseMinForearmLen = 0.10f

        /** Écart max entre une nouvelle estimation d'échelle et l'échelle
         *  courante, avant lissage. Un poignet ne double pas de taille d'une
         *  frame à l'autre : au-delà, c'est un relevé aberrant. */
        private const val kScaleMaxRatio = 1.5f

        /** Vitesse de convergence de l'échelle lissée (fraction par frame,
         *  ~60 fps → converge en ~0,5 s, insensible au bruit de mesure).
         *  N'agit plus qu'AVANT le verrouillage de la morphologie, pendant que
         *  l'estimation est encore bruitée. */
        private const val kScaleLerp = 0.06f

        /** Après verrouillage, l'échelle ne varie plus que par la distance :
         *  c'est un signal réel, qu'il faut suivre vite (~0,15 s) sous peine
         *  de voir le bijou grandir en retard sur la main qui approche. */
        private const val kScaleLerpLocked = 0.25f

        // --- Calibration (à ajuster visuellement) ---
        /** Distance caméra→modèle (cohérente avec orbitHomePosition). */
        private const val kCameraDistance = 4.0f

        /** Éclairage. L'IBL porte l'ambiante, la directionnelle porte le
         *  reflet — c'est elle qui fait qu'un métal réagit quand on bouge.
         *  Intensités du même ordre : la directionnelle doit sculpter le
         *  bijou, pas le brûler. */
        private const val kIblLux = 30_000f
        private const val kSunLux = 25_000f

        /** Direction de la lumière (monde : X droite, Y haut, Z vers la
         *  caméra). Au-dessus et légèrement en avant du sujet. */
        private val kSunDir = floatArrayOf(-0.25f, -0.85f, -0.46f)
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

        // --- REPLIS de la géométrie des bijoux -------------------------
        // Ces valeurs ne sont plus la source de vérité : le runtime lit
        // `<nom>.fit.json` (cf. tool/fit_analyzer.dart, JewelFit). Elles ne
        // servent que si l'analyse n'a pas été lancée sur un asset, pour
        // qu'il s'affiche quand même. Ne les ajustez PAS à l'œil — relancez
        // l'analyseur, c'est tout l'intérêt du dispositif.

        /** Repli : rayon du trou de la manchette dans la direction
         *  MÉDIO-LATÉRALE (modèle X → colX). C'est bien celui-là et pas le
         *  rayon minimal, parce que la segmentation mesure la largeur du bras
         *  dans cette même direction et que le trou est nettement OVALE
         *  (0.70 × 0.90, comme un vrai poignet). */
        private const val kModelInnerRadius = 0.82f

        /** Replis : centre du trou dans le repère du modèle. */
        private const val kCuffHoleX = 0.0127f
        private const val kCuffHoleZ = -0.0531f
        private const val kRingHoleX = 0.0131f
        private const val kRingHoleY = -0.3213f

        /** Repli : petit axe du trou de la bague. */
        private const val kRingHoleFallback = 0.553f

        /** Retrait de l'occluseur de bague sous le petit axe du trou : au-delà
         *  du trou, le cylindre de profondeur masquerait la bague elle-même. */
        private const val kRingOccInset = 0.90f

        /** Largeur anatomique du poignet en fraction de la taille de main
         *  (distance lm0–lm9) : poignet ~5,5–6,5 cm pour ~8–9 cm de main. */
        private const val kWristWidthPerHand = 0.70f

        /** Jeu du bracelet : Ø intérieur = kBraceletClearance × largeur du
         *  bras MESURÉE (silhouette du masque de segmentation).
         *
         *  Ramené de 1.12 à 1.06 : à 12 %, le tube intérieur passait ~12 % au-
         *  delà du bord réel de l'avant-bras → un vide visible tout autour, le
         *  « diamètre mal ajusté » signalé. La largeur venant du masque EST déjà
         *  le bord du bras : 6 % suffisent pour le jeu d'un bijou rigide sans
         *  flotter. Doit rester > kOccluderMargin (1.05) pour que le cylindre
         *  occluseur reste à l'intérieur du trou. */
        private const val kBraceletClearance = 1.06f

        /** Fraîcheur max de la mesure silhouette avant repli anatomique. */
        private const val kSegFreshMs = 400L

        /** Balayage du cou : bornes du trajet milieu des épaules → milieu des
         *  oreilles, et pas d'échantillonnage. On garde la largeur minimale
         *  sur cette plage, le cou étant l'endroit le plus étroit du trajet. */
        private const val kNeckScanFrom = 0.25f
        private const val kNeckScanTo = 0.65f
        private const val kNeckScanStep = 0.08f

        /** Bornes de vraisemblance du rapport largeur de doigt / longueur de
         *  main. Un doigt fait ~18-20 mm pour une main (poignet→MCP) de
         *  ~90-100 mm, soit ~0.20 ; hors de cette plage la détection est
         *  aberrante (main repliée, doigts fusionnés dans le masque). */
        private const val kFingerPitchMin = 0.12f
        private const val kFingerPitchMax = 0.35f

        /** Jeu de la bague autour du doigt : une bague est ajustée, bien plus
         *  qu'une manchette — elle doit tenir sans tomber.
         *
         *  Ramené de 1.06 à 1.02 : le rayon du doigt est déduit de la moitié du
         *  PITCH entre MCP voisins (fingerRadiusMetric), qui vaut la largeur du
         *  doigt au KNUCKLE — le point le plus large — alors que la bague se
         *  pose plus haut sur la phalange, plus fine. Cette surestimation se
         *  cumulait avec les 6 % de jeu : l'anneau sortait « un peu grand ».
         *  1.02 = juste le jeu d'un anneau rigide qui glisse sur le doigt. */
        private const val kRingClearance = 1.02f

        /** Position de la bague le long de la phalange proximale, en fraction
         *  de MCP (lm13) → PIP (lm14). 0 = sur l'articulation, 1 = à la
         *  jointure suivante. Une bague repose juste au-dessus du MCP. */
        private const val kRingAlongPhalanx = 0.25f

        /** Points de mesure de la largeur, en fraction de l'axe
         *  poignet→coude. Deux mesures : le rayon au bord haut de la
         *  manchette (proche) et l'évasement de l'avant-bras (loin). */
        private const val kScanNear = 0.10f
        private const val kScanFar = 0.45f

        /** Balayage pour trouver le POIGNET = point le plus étroit du bras
         *  entre la main (~0) et le milieu de l'avant-bras. On garde la largeur
         *  minimale sur cette plage (même principe que le cou). Robuste à la
         *  main ouverte : un échantillon tombé sur la paume est plus large,
         *  donc écarté d'office. */
        private const val kWristScanFrom = 0.04f
        private const val kWristScanTo = 0.34f
        private const val kWristScanStep = 0.06f

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
         *  supérieur de la manchette. Ramenée de 0.05 à 0 : le bord supérieur
         *  affleure alors exactement le landmark poignet (lm0), qui tombe au
         *  pli — « la limite de la manchette à la limite de la main ». Le reste
         *  de la hauteur ressentie vient de la proportion RÉELLE de la pièce
         *  (manchette large : ~2.4× le rayon du poignet), pas d'un décalage. */
        private const val kBandGap = 0.0f

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

        /** Écart minimal bouche→épaules (en demi-visages oreilles→bouche) pour
         *  juger les épaules CRÉDIBLES. Une épaule réelle est bien plus bas
         *  qu'un demi-visage sous la bouche ; si la pose la place plus haut,
         *  elle l'a inventée (cadrage serré) → on bascule sur le repli visage.
         *  1.0 = permissif : on n'écarte que les épaules franchement au visage. */
        private const val kNeckShoulderGap = 1.0f

        /** Repli VISAGE : distance bouche→base du cou, en demi-visages
         *  (oreilles→bouche). ~1.6 place l'encolure sous le menton, au bas du
         *  cou. À affiner à l'œil avec le log `cou:` (source=VISAGE). */
        private const val kFaceToNeck = 1.6f

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

        /** Bornes de validité de la largeur mesurée (écran normalisé). Borne
         *  GROSSIÈRE seulement : 0.40 laisse passer un balayage parti dans le
         *  torse. Le vrai filtre est le rapport à l'estimation anatomique
         *  ci-dessous, qui a une référence à quoi se comparer. */
        private const val kSegMinWidthN = 0.02f
        private const val kSegMaxWidthN = 0.40f

        /** Rapport accepté entre le rayon MESURÉ sur le masque et le rayon
         *  ESTIMÉ depuis la taille de main. En dessous/au-dessus, la mesure a
         *  fui (torse, autre bras) ou s'est effondrée sur un bord : on garde
         *  l'estimation. Fourchette large, parce que l'estimation est elle-même
         *  un ratio de population — on ne cherche qu'à écarter l'aberrant, pas
         *  à contraindre la mesure (dont c'est tout l'intérêt d'être réelle). */
        private const val kSegRadiusMinRatio = 0.60f
        private const val kSegRadiusMaxRatio = 1.70f

        /** Lissage (fraction/frame) et borne de la correction latérale vers
         *  le centre mesuré de la silhouette. */
        private const val kSegOffsetLerp = 0.12f

        /** Amplitude max du recentrage latéral sur le centre mesuré de la
         *  silhouette. Relevée de 0.04 à 0.09 : une fois l'échelle refondée sur
         *  la silhouette (source fiable), on peut faire confiance à son CENTRE
         *  pour corriger un vrai décalage visible (le bord du bijou sortait du
         *  bras sur les captures), pas seulement un micro-biais de landmark. Le
         *  lissage kSegOffsetLerp l'empêche de sauter. */
        private const val kSegOffsetMax = 0.09f

        /** Marge de l'occluseur au-delà du rayon estimé du poignet : absorbe
         *  l'erreur d'estimation pour que l'arrière de l'anneau ne
         *  réapparaisse jamais SUR la peau. Doit rester < clearance. */
        private const val kOccluderMargin = 1.05f

        /** Profondeur du poignet / largeur (ellipse anatomique aplatie). */
        private const val kWristDepthRatio = 0.70f

        /** Rayon de la jupe d'ombre, en fraction du rayon du membre.
         *
         *  Contrainte BASSE seulement : il faut rester au-dessus de
         *  l'occluseur de profondeur (kOccluderMargin = 1.05), sinon la jupe
         *  est masquée EN ENTIER au lieu de sa seule moitié arrière et l'ombre
         *  disparaît. Le trou du bijou (1.12) ne contraint PAS : la jupe vit
         *  axialement en DEHORS de la pièce (|y| ≥ 1 demi-hauteur), là où il
         *  n'y a plus de géométrie de bijou.
         *
         *  1.14 laisse ~6 % de marge sur l'occluseur — assez pour absorber le
         *  bruit de mesure — tout en restant largement sous la silhouette
         *  extérieure du bijou, donc l'ombre hugge bien le membre. */
        private const val kShadowRadiusMargin = 1.14f

        /** Norme min de la composante du vecteur silhouette orthogonale à
         *  l'axe du bras (unités monde) pour en tirer un roulis : en dessous,
         *  la mesure est quasi colinéaire à l'axe → direction indéterminée. */
        private const val kMinRollNorm = 0.05f

        /** Sous cette fraction "dans le plan écran" de l'axe, on arrête de
         *  compenser le raccourcissement de perspective (protège des
         *  divisions extrêmes quand le membre pointe vers la caméra). */
        /*  0.35 autorisait la taille de main à être divisée par 0.35, soit
         *  amplifiée ×2.86 — et les logs montrent planar passant de 0.94 à
         *  0.48 d'une frame à l'autre, donc handLen de 1.17 à 2.17. Toute
         *  l'échelle du bijou suivait. 0.55 borne l'amplification à ×1.8 : on
         *  perd un peu de compensation quand le membre pointe franchement vers
         *  l'objectif, mais c'est précisément le cas où la mesure est la moins
         *  fiable. */
        private const val kMinPlanarFactor = 0.55f

        /** Priorités de rendu Filament (0 = dessiné en premier, 7 en dernier).
         *
         *  L'occulteur DOIT précéder le bijou : il n'écrit que la profondeur,
         *  donc s'il passe après, la couleur du dos du bijou est déjà dans le
         *  framebuffer et y reste. L'ombre passe en dernier — elle est
         *  transparente et doit être testée contre tout le reste. */
        private const val kPriorityOccluder = 0
        private const val kPriorityJewel = 4
        private const val kPriorityShadow = 7

        /** Ombre de contact (jupe noire au sol du bijou). Coupée : sa moitié
         *  avant restait visible comme un halo sombre sur la peau. Repasser à
         *  true pour la rétablir si un occluseur plus fin la retranche mieux. */
        private const val kContactShadowEnabled = false

        /** Longueur des occluseurs : traversent largement le bijou le long du
         *  membre (le rayon, lui, vient désormais du fit.json). */
        private const val kWristOccLen = 4.0f
        private const val kRingOccLen = 7.0f

        /** Âge maximal pris en compte pour extrapoler un instantané de suivi
         *  (s). Au-delà, la détection a franchement décroché : continuer à
         *  avancer sur la dernière vitesse connue projetterait le bijou loin
         *  de la main. On gèle alors plutôt que d'inventer. */
        private const val kMaxLatencySec = 0.30f

        /** Déplacement prédictif max (unités normalisées).
         *
         *  0.06 autorisait un saut de 6 % de la hauteur d'écran, soit ~145 px
         *  sur une dalle 2400 — un ordre de grandeur au-dessus de ce qu'une
         *  compensation de latence doit produire. */
        private const val kMaxLead = 0.025f

        /** Silence de détection au-delà duquel les filtres repartent de zéro. */
        private const val kResetAfterMs = 500L

        /** Délai de grâce : on maintient la dernière pose connue plutôt que de
         *  faire clignoter le bijou sur un décrochage passager du détecteur.
         *  Assez long pour couvrir quelques frames manquées, assez court pour
         *  qu'une main réellement sortie du champ ne laisse pas un bijou
         *  fantôme flotter à l'écran. */
        private const val kHoldAfterLossMs = 220L

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
 * Grandeur physiquement CONSTANTE : mesurée sur les premières frames fiables,
 * puis figée pour le reste de la session.
 *
 * Le rayon du poignet, du doigt ou du cou de l'utilisateur ne change pas
 * pendant un essayage. Les ré-estimer à chaque frame ne rendait donc pas le
 * suivi plus juste — seulement plus instable, puisque chaque frame apportait un
 * bruit de mesure différent et que les sources de repli ne s'accordaient pas
 * entre elles. C'est ce va-et-vient qui se lisait à l'écran comme un bijou qui
 * « respire » et change de taille au moindre mouvement.
 *
 * Deux propriétés font le travail :
 *  - la MÉDIANE (et non la moyenne) ignore les relevés aberrants, ceux que le
 *    balayage du masque produit quand il fuit dans le torse ou l'autre bras ;
 *  - le VERROUILLAGE garantit qu'une fois la morphologie établie, plus aucune
 *    mesure ultérieure ne peut la déplacer.
 *
 * Avant d'être plein, l'objet renvoie la médiane courante : la valeur se
 * stabilise progressivement au lieu de sauter d'une mesure à l'autre.
 *
 * Les valeurs offertes doivent être en MÈTRES, c'est-à-dire indépendantes de la
 * distance à la caméra — sans quoi on figerait une taille apparente, et le
 * bijou cesserait de grossir quand la main approche.
 */
/**
 * Suivi par FLUX OPTIQUE (Lucas-Kanade) du déplacement de l'ancre entre deux
 * frames consécutives.
 *
 * ## Pourquoi
 *
 * Chaque détection MediaPipe est une régression INDÉPENDANTE : son erreur est
 * ré-tirée à chaque frame (±1-2 %), sans corrélation avec la précédente. Le
 * seul moyen d'en réduire le tremblement est de moyenner dans le temps — ce
 * que fait le One-Euro — et moyenner dans le temps, c'est du RETARD. Tout le
 * réglage jusqu'ici consistait à choisir où placer le curseur entre « ça
 * tremble » et « ça traîne ».
 *
 * Le flux optique casse ce compromis, parce qu'il mesure autre chose : le
 * déplacement RÉEL des pixels d'une frame à la suivante, à ~0,1 px près. Il
 * dérive sur la durée (les petites erreurs s'accumulent) mais il est
 * remarquablement précis à court terme — exactement l'inverse du réseau, précis
 * à long terme et bruité à court terme. Les fusionner donne les deux :
 * l'immédiateté du flux, l'ancrage sans dérive du réseau.
 *
 * C'est le pipeline décrit par les brevets WANNA (US 11,900,559 / 11,978,174),
 * le prestataire d'essayage de Farfetch : réseau par frame, flux optique comme
 * « motion cue », fusion par filtre de suivi rigide.
 *
 * ## Comment, à moindre coût
 *
 * Un Lucas-Kanade classique exige une pyramide d'images pour rattraper les
 * grands déplacements — une main peut bouger de 30 px entre deux frames, très
 * au-delà de ce qu'une fenêtre de recherche encaisse. On s'en dispense en
 * AMORÇANT la recherche avec le déplacement que MediaPipe annonce lui-même : il
 * ne reste alors à mesurer que son ERREUR, de quelques pixels. Un seul niveau
 * suffit, et c'est justement cette erreur qu'on veut connaître.
 *
 * Le coût est borné de la même façon : on n'extrait qu'une fenêtre autour de
 * l'ancre (quelques milliers de pixels) au lieu de convertir l'image entière.
 */
private class FlowTracker(private val win: Int, private val half: Int) {

    private var prevGray: FloatArray? = null
    private var prevOx = 0
    private var prevOy = 0

    /** Ancre de la frame précédente, en pixels image : origine des points
     *  suivis ET référence de l'amorce (cf. track). */
    private var prevAx = 0f
    private var prevAy = 0f
    private var prevTs = -1L

    /** Luminance bilinéaire à la position ([x], [y]) exprimée dans le repère
     *  de la fenêtre. Renvoie NaN hors du domaine échantillonnable. */
    private fun sample(g: FloatArray, x: Float, y: Float): Float {
        if (x < 0f || y < 0f || x > win - 2f || y > win - 2f) return Float.NaN
        val xi = x.toInt()
        val yi = y.toInt()
        val fx = x - xi
        val fy = y - yi
        val i = yi * win + xi
        val a = g[i]
        val b = g[i + 1]
        val c = g[i + win]
        val d = g[i + win + 1]
        return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy
    }

    /** Affine le déplacement ([gx], [gy]) du point ([px], [py]) (repère IMAGE)
     *  par itérations de Lucas-Kanade. Renvoie le déplacement affiné, ou null
     *  si la fenêtre est mal conditionnée (zone uniforme : peau lisse, fond
     *  neutre — aucune information de mouvement à en tirer). */
    private fun refine(
        prev: FloatArray,
        cur: FloatArray,
        curOx: Int,
        curOy: Int,
        px: Float,
        py: Float,
        gx: Float,
        gy: Float,
    ): FloatArray? {
        // ([px], [py]) est la position du point dans la frame PRÉCÉDENTE ;
        // on le cherche dans la courante en (px + d, py + d). Les deux
        // fenêtres ont des origines différentes, chacune centrée sur l'ancre
        // de SA frame — d'où deux conversions distinctes.
        val ax = px - prevOx
        val ay = py - prevOy
        var dx = gx
        var dy = gy

        // Matrice des moments du gradient (constante : elle ne dépend que de
        // la frame précédente) et son inverse.
        var gxx = 0f
        var gxy = 0f
        var gyy = 0f
        val r = kPatchRadius
        var j = -r
        while (j <= r) {
            var i = -r
            while (i <= r) {
                val ix = (sample(prev, ax + i + 1, ay + j) -
                    sample(prev, ax + i - 1, ay + j)) * 0.5f
                val iy = (sample(prev, ax + i, ay + j + 1) -
                    sample(prev, ax + i, ay + j - 1)) * 0.5f
                if (!ix.isNaN() && !iy.isNaN()) {
                    gxx += ix * ix
                    gxy += ix * iy
                    gyy += iy * iy
                }
                i++
            }
            j++
        }
        val det = gxx * gyy - gxy * gxy
        // Seuil de conditionnement : sans texture, le système est singulier et
        // sa solution serait du bruit amplifié. Mieux vaut renoncer.
        if (det < kFlowMinDet) return null

        var it = 0
        while (it < kFlowIters) {
            var bx = 0f
            var by = 0f
            val cx0 = px + dx - curOx
            val cy0 = py + dy - curOy
            var jj = -r
            while (jj <= r) {
                var ii = -r
                while (ii <= r) {
                    val p = sample(prev, ax + ii, ay + jj)
                    val c = sample(cur, cx0 + ii, cy0 + jj)
                    if (!p.isNaN() && !c.isNaN()) {
                        val diff = p - c
                        val ix = (sample(prev, ax + ii + 1, ay + jj) -
                            sample(prev, ax + ii - 1, ay + jj)) * 0.5f
                        val iy = (sample(prev, ax + ii, ay + jj + 1) -
                            sample(prev, ax + ii, ay + jj - 1)) * 0.5f
                        if (!ix.isNaN() && !iy.isNaN()) {
                            bx += ix * diff
                            by += iy * diff
                        }
                    }
                    ii++
                }
                jj++
            }
            val sx = (gyy * bx - gxy * by) / det
            val sy = (gxx * by - gxy * bx) / det
            if (!sx.isFinite() || !sy.isFinite()) return null
            dx += sx
            dy += sy
            if (abs(sx) + abs(sy) < kFlowEps) break
            it++
        }
        // Un raffinement énorme signifie que la recherche a décroché sur un
        // motif voisin plutôt que suivi le bon : on préfère ne rien dire.
        if (hypot(dx - gx, dy - gy) > kFlowMaxCorrection) return null
        return floatArrayOf(dx, dy)
    }

    /** Mesure le déplacement de la zone autour de ([cxImg], [cyImg]) entre la
     *  frame précédente et [bmp], en amorçant avec ([guessDx], [guessDy]).
     *
     *  Renvoie le déplacement mesuré (pixels image), ou null quand il n'est pas
     *  exploitable : première frame, frame non consécutive, zone sans texture,
     *  ou trop peu de points d'accord. */
    fun track(
        bmp: Bitmap,
        ts: Long,
        cxImg: Float,
        cyImg: Float,
        expectPrevTs: Long,
    ): FloatArray? {
        // Fenêtre TOUJOURS centrée sur l'ancre de sa propre frame : c'est ce
        // qui permet, au tour suivant, de retrouver sans ambiguïté où se
        // trouvaient les points de la frame précédente.
        val ox = cxImg.toInt() - half
        val oy = cyImg.toInt() - half
        val gray = grabGray(bmp, ox, oy) ?: run {
            prevGray = null
            prevTs = -1L
            return null
        }

        val prev = prevGray
        val usable = prev != null && prevTs == expectPrevTs && expectPrevTs >= 0L
        var out: FloatArray? = null
        if (usable) {
            // Amorce : le déplacement de l'ancre annoncé par le réseau, en
            // pixels IMAGE. Le déduire des deux ancres successives dans CE
            // repère est le seul calcul sûr — passer par l'espace d'affichage
            // mélangerait rotation et miroir, et l'amorce partirait de travers.
            val guessDx = cxImg - prevAx
            val guessDy = cyImg - prevAy
            // Plusieurs points répartis autour de l'ancre : une seule mesure
            // serait à la merci d'un reflet ou d'une occlusion locale. On garde
            // la MÉDIANE, insensible aux points qui ont décroché.
            val dxs = ArrayList<Float>(kFlowPoints * kFlowPoints)
            val dys = ArrayList<Float>(kFlowPoints * kFlowPoints)
            val step = kFlowSpread
            var gy = -(kFlowPoints / 2)
            while (gy <= kFlowPoints / 2) {
                var gx = -(kFlowPoints / 2)
                while (gx <= kFlowPoints / 2) {
                    // Points définis autour de l'ancre PRÉCÉDENTE : ce sont
                    // eux qu'on retrouve dans la frame courante.
                    val d = refine(
                        prev!!, gray, ox, oy,
                        prevAx + gx * step, prevAy + gy * step,
                        guessDx, guessDy,
                    )
                    if (d != null) {
                        dxs.add(d[0])
                        dys.add(d[1])
                    }
                    gx++
                }
                gy++
            }
            if (dxs.size >= kFlowMinPoints) {
                dxs.sort()
                dys.sort()
                out = floatArrayOf(dxs[dxs.size / 2], dys[dys.size / 2])
            }
        }

        prevGray = gray
        prevOx = ox
        prevOy = oy
        prevAx = cxImg
        prevAy = cyImg
        prevTs = ts
        return out
    }

    fun reset() {
        prevGray = null
        prevTs = -1L
    }

    /** Extrait une fenêtre carrée en luminance. null si elle sort de l'image :
     *  compléter par des bords inventés ferait mesurer un mouvement qui
     *  n'existe pas. */
    private fun grabGray(bmp: Bitmap, ox: Int, oy: Int): FloatArray? {
        if (ox < 0 || oy < 0 || ox + win > bmp.width || oy + win > bmp.height) return null
        val px = IntArray(win * win)
        bmp.getPixels(px, 0, win, ox, oy, win, win)
        val g = FloatArray(win * win)
        for (i in px.indices) {
            val p = px[i]
            // Luminance perceptuelle en entiers : les coefficients Rec. 601
            // mis à l'échelle 1024 évitent tout flottant dans la boucle chaude.
            g[i] = (
                306 * ((p shr 16) and 0xFF) +
                    601 * ((p shr 8) and 0xFF) +
                    117 * (p and 0xFF)
                ) / 1024f
        }
        return g
    }

    companion object {
        /** Demi-côté de la fenêtre de corrélation d'UN point. */
        private const val kPatchRadius = 6

        /** Itérations de Newton par point. La correction cherchée ne vaut que
         *  quelques pixels (recherche amorcée), elle converge en 3-4 pas. */
        private const val kFlowIters = 4

        /** Sous ce déterminant, la fenêtre est trop uniforme pour porter une
         *  information de mouvement (peau lisse, mur neutre). */
        private const val kFlowMinDet = 1e4f

        /** Arrêt anticipé quand le pas devient négligeable (pixels). */
        private const val kFlowEps = 0.03f

        /** Correction max acceptée par rapport à l'amorce MediaPipe. Au-delà,
         *  le point a suivi un motif voisin, pas le bon. */
        private const val kFlowMaxCorrection = 12f

        /** Grille de points suivis (kFlowPoints²) et leur écartement. */
        private const val kFlowPoints = 3
        private const val kFlowSpread = 14f

        /** Points valides minimaux pour publier une médiane crédible. */
        private const val kFlowMinPoints = 4
    }
}

/**
 * État de suivi publié par le thread de détection, DATÉ de la frame dont il
 * est issu.
 *
 * Immuable, et publié d'un seul bloc via une référence @Volatile : la boucle de
 * rendu ne peut donc jamais lire une position venant d'une détection et une
 * vitesse venant de la suivante. Avec des champs séparés, ce mélange se produit
 * — rarement, donc sous la forme d'un sursaut inexplicable et non reproductible.
 *
 * [frameTimeMs] est l'horodatage posé par `analyze` sur la frame analysée, pas
 * la date de publication : c'est ce qui permet au rendu de connaître l'âge réel
 * de la mesure (cf. anchorAt).
 */
private class TrackSnapshot(
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val frameTimeMs: Long,
)

private class LockedMedian(
    private val name: String,
    private val samplesToLock: Int,
    private val relockRatio: Float,
) {
    private val samples = ArrayList<Float>(samplesToLock)

    /** Mesures consécutives en désaccord franc avec la valeur figée. */
    private var dissent = 0

    /** Médiane courante, ou -1 tant qu'aucune mesure n'a été offerte. */
    var value = -1f
        private set

    /** true = la valeur ne bouge plus (sauf déverrouillage, cf. offer). */
    var isLocked = false
        private set

    fun offer(x: Float) {
        if (!x.isFinite() || x <= 0f) return

        if (isLocked) {
            // SOUPAPE. Figer définitivement serait un pari : si les premières
            // secondes se passent mal (cadrage douteux, manche, mauvais bras),
            // l'utilisateur resterait coincé avec une taille fausse pour toute
            // la session — et sans aucun moyen de la corriger, ce qui est pire
            // que l'instabilité qu'on cherche à supprimer.
            //
            // On ne rouvre donc que sur un désaccord FRANC et SOUTENU : autant
            // de mesures consécutives qu'il en a fallu pour verrouiller. Le
            // bruit ordinaire ne peut pas produire ça (il change de signe), une
            // vraie erreur de verrouillage si.
            val r = x / value
            if (r < 1f / relockRatio || r > relockRatio) {
                if (++dissent >= samplesToLock) {
                    Log.w(
                        "NativeArView",
                        "morphologie: $name DÉVERROUILLÉ — $samplesToLock mesures " +
                            "consécutives incohérentes avec $value (dernière : $x)",
                    )
                    isLocked = false
                    dissent = 0
                    value = -1f
                    samples.clear()
                }
            } else {
                dissent = 0
            }
            return
        }

        samples.add(x)
        val sorted = samples.sorted()
        value = sorted[sorted.size / 2]
        if (samples.size >= samplesToLock) {
            isLocked = true
            samples.clear()
            Log.i("NativeArView", "morphologie: $name VERROUILLÉ sur $value")
        }
    }
}

/**
 * Choix binaire STABLE entre deux interprétations concurrentes.
 *
 * Plusieurs décisions du pipeline — convention de rotation des landmarks,
 * espace du masque de segmentation, source de l'axe — portent sur des
 * propriétés du DEVICE ou de la scène, qui ne changent pas d'une frame à
 * l'autre. Le code les redécidait pourtant intégralement à chaque frame, à
 * partir de scores bruités. Quand les deux candidats sont proches, le bruit du
 * capteur suffit alors à faire osciller la décision — et comme les deux
 * interprétations donnent des résultats très différents (repère tourné, largeur
 * mesurée ailleurs), chaque oscillation est un SAUT du bijou. C'est très
 * exactement le symptôme « ça bouge parfois sans aucun mouvement ».
 *
 * On accumule donc des voix : on ne change d'avis que sur une contradiction
 * SOUTENUE, jamais sur une frame isolée.
 */
private class StickyChoice(
    private val name: String,
    private val votesToFlip: Int,
) {
    var value: Boolean? = null
        private set

    private var votes = 0

    /** [candidate] = ce que déciderait un choix instantané. null = aucune
     *  information exploitable cette frame — on conserve alors la valeur en
     *  cours plutôt que de la détruire (un landmark hors masque ne prouve
     *  rien sur la convention du masque). */
    fun update(candidate: Boolean?): Boolean? {
        if (candidate == null) return value
        val current = value
        if (current == null) {
            value = candidate
            votes = 0
            Log.i("NativeArView", "convention $name verrouillée sur $candidate")
            return candidate
        }
        if (candidate == current) {
            votes = 0
        } else if (++votes >= votesToFlip) {
            value = candidate
            votes = 0
            // Journalisé en WARN : une bascule reste possible (changement
            // d'orientation de l'appareil), mais elle est visible à l'écran.
            // Si ce message défile, c'est que le seuil est encore trop bas.
            Log.w(
                "NativeArView",
                "convention $name BASCULE vers $candidate " +
                    "($votesToFlip frames contraires consécutives)",
            )
        }
        return value
    }
}

/**
 * Balayage du masque de segmentation personne : mesure la PLEINE LARGEUR d'un
 * membre perpendiculairement à son axe.
 *
 * Extrait de [NativeArView.onPose] pour servir aussi au COU. Mesurer une
 * silhouette réelle est ce qui remplace un ratio anatomique de population par
 * la morphologie de l'utilisateur — c'est la technique des essayages
 * commerciaux, et elle vaut pour tous les membres, pas seulement l'avant-bras
 * pour lequel elle avait été écrite.
 *
 * Toutes les coordonnées sont en espace LANDMARK normalisé ; la conversion
 * vers l'écran reste à l'appelant, seul à connaître la convention de rotation
 * qu'il a retenue.
 */
private class MaskScanner(
    mask: MPImage,
    private val rot: Int,
    private val maxWidthFrac: Float,
) {
    private val mw: Int = mask.width
    private val mh: Int = mask.height
    private val fb: FloatBuffer = ByteBufferExtractor.extract(mask)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private fun toRawSpace(x: Float, y: Float): FloatArray = when (rot) {
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

    /** L'espace du masque suit les landmarks redressés sur certains devices et
     *  reste en capteur brut sur d'autres — même famille de pièges que les
     *  rotations de landmarks. On sonde un point qui DOIT tomber dans la
     *  personne et on garde la convention qui répond. null = ni l'une ni
     *  l'autre, donc détection douteuse. */
    fun probeRawMode(x: Float, y: Float): Boolean? = when {
        sample(x, y, false) >= 0.5f -> false
        sample(x, y, true) >= 0.5f -> true
        else -> null
    }

    /** Bords de la silhouette au point ([cx], [cy]), balayés perpendiculairement
     *  à ([dirX], [dirY]). Renvoie [e1x, e1y, e2x, e2y] en espace landmark, ou
     *  null si un bord n'a pas été trouvé — le balayage a fui dans le torse ou
     *  l'autre bras, et une largeur fausse vaut moins que pas de largeur. */
    fun measureEdges(
        cx: Float,
        cy: Float,
        dirX: Float,
        dirY: Float,
        raw: Boolean,
    ): FloatArray? {
        // Direction en PIXELS de masque : sans cette correction d'anisotropie
        // le pas de balayage ne serait pas perpendiculaire au membre.
        val fxp = dirX * mw
        val fyp = dirY * mh
        val fl = hypot(fxp, fyp)
        if (fl <= 1e-3f) return null
        val stepX = -(fyp / fl) / mw
        val stepY = (fxp / fl) / mh
        val maxSteps = (maxWidthFrac * maxOf(mw, mh)).toInt()

        fun march(sign: Int): Int {
            var miss = 0
            var i = 1
            while (i <= maxSteps) {
                val v = sample(cx + sign * i * stepX, cy + sign * i * stepY, raw)
                if (v < 0.5f) {
                    // Trois échecs d'affilée : c'est un vrai bord, pas un trou
                    // du masque.
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
        return floatArrayOf(
            cx + dPlus * stepX, cy + dPlus * stepY,
            cx - dMinus * stepX, cy - dMinus * stepY,
        )
    }

    val size: String get() = "${mw}x$mh"
}

/**
 * Mesures géométriques d'un bijou, lues depuis `<nom>.fit.json` (produit par
 * `tool/fit_analyzer.dart`).
 *
 * Le trou d'un bijou n'est pas « le point le plus proche de l'origine » mais
 * le plus grand CYLINDRE VIDE qui le traverse de part en part, centre
 * optimisé. C'est cette grandeur-là qui décide si un membre peut y passer, et
 * c'est elle que l'analyseur mesure. Tous les champs sont optionnels : un
 * fit.json incomplet retombe sur les replis de l'appelant.
 */
private class JewelFit(private val json: JSONObject) {
    val holeAxis: String = json.optString("holeAxis", "Y")
    val holeRadiusMin: Float = json.optDouble("holeRadiusMin", 0.0).toFloat()
    val holeRadiusMax: Float = json.optDouble("holeRadiusMax", 0.0).toFloat()
    val axisHalfExtent: Float = json.optDouble("axisHalfExtent", 0.0).toFloat()
    val openingAt: Float = json.optDouble("openingAt", 0.0).toFloat()
    val openingRadius: Float = json.optDouble("openingRadius", 0.0).toFloat()

    val holeCenter: FloatArray = json.optJSONArray("holeCenter")?.let { a ->
        floatArrayOf(
            a.optDouble(0, 0.0).toFloat(),
            a.optDouble(1, 0.0).toFloat(),
            a.optDouble(2, 0.0).toFloat(),
        )
    } ?: floatArrayOf(0f, 0f, 0f)

    /** Rayon libre vers ±[axis] du modèle. L'analyseur ne publie que les deux
     *  directions PERPENDICULAIRES à l'axe du trou — demander celle de l'axe
     *  lui-même retombe donc sur le petit axe, ce qui reste conservateur. */
    fun radiusToward(axis: String): Float {
        val v = json.optDouble("radiusToward$axis", -1.0).toFloat()
        return if (v > 0f) v else holeRadiusMin
    }
}

/**
 * Filtre One-Euro appliqué à une ROTATION entière, via son quaternion.
 *
 * Lisser une orientation ne se fait pas composante par composante sur une
 * matrice : le résultat n'est plus une rotation. Sur le quaternion unitaire, en
 * revanche, un lissage composante par composante suivi d'une renormalisation
 * reste une rotation valide — et pour les petits écarts entre frames (c'est
 * toujours le cas à 30-60 Hz) il approche la slerp de très près, pour une
 * fraction du coût.
 *
 * Le point délicat est le SIGNE : q et −q décrivent la même rotation, mais un
 * changement de signe entre deux frames ferait interpoler par le grand arc,
 * c'est-à-dire un tour complet du bijou sur une seule frame. On aligne donc
 * chaque mesure sur la précédente (produit scalaire négatif → on retourne le
 * quaternion) avant de filtrer.
 */
private class QuatFilter(minCutoff: Float, beta: Float) {
    private val f = Array(4) { OneEuroFilter(minCutoff, beta) }
    private var prev: FloatArray? = null

    fun filter(q: FloatArray, tMs: Long): FloatArray {
        var qq = q
        val p = prev
        if (p != null) {
            val d = qq[0] * p[0] + qq[1] * p[1] + qq[2] * p[2] + qq[3] * p[3]
            if (d < 0f) qq = floatArrayOf(-qq[0], -qq[1], -qq[2], -qq[3])
        }
        val o = floatArrayOf(
            f[0].filter(qq[0], tMs),
            f[1].filter(qq[1], tMs),
            f[2].filter(qq[2], tMs),
            f[3].filter(qq[3], tMs),
        )
        val n = sqrt(o[0] * o[0] + o[1] * o[1] + o[2] * o[2] + o[3] * o[3])
        // Une norme quasi nulle signifie que les quatre passe-bas sont passés
        // par zéro simultanément — pas de rotation exploitable, on garde la
        // mesure brute plutôt que de diviser par presque rien.
        val r = if (n < 1e-6f) {
            qq
        } else {
            floatArrayOf(o[0] / n, o[1] / n, o[2] / n, o[3] / n)
        }
        prev = r
        return r
    }

    fun reset() {
        f.forEach { it.reset() }
        prev = null
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
