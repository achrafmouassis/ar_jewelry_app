import 'package:flutter/material.dart';

import '../services/calibration_service.dart';

/// Ce qu'on mesure sur cet écran.
enum CalibrationMode { finger, wrist }

/// Calibration morphologique à l'écran : l'utilisateur pose son doigt (ou son
/// poignet) sur la dalle et resserre deux règles jusqu'à l'encadrer.
///
/// C'est le même principe que l'essayage Cartier/Perfect Corp, et il est
/// choisi pour la même raison : la profondeur monoculaire ne donne pas
/// d'échelle absolue fiable, alors qu'un écran a une taille physique connue.
/// Une mesure ponctuelle bat une estimation par frame — voir
/// [CalibrationService] pour ce qu'on en fait ensuite.
///
/// Rien n'est envoyé nulle part : la mesure reste dans un fichier local.
class CalibrationScreen extends StatefulWidget {
  final CalibrationMode mode;

  const CalibrationScreen({super.key, this.mode = CalibrationMode.finger});

  @override
  State<CalibrationScreen> createState() => _CalibrationScreenState();
}

class _CalibrationScreenState extends State<CalibrationScreen> {
  /// Écartement des règles, en pixels logiques.
  double _gapPx = 0;

  /// mm par pixel logique ; null tant que non résolu, absent si la densité
  /// de la dalle n'est pas exploitable (on masque alors la calibration).
  double? _mmPerPx;
  bool _resolved = false;

  bool get _isFinger => widget.mode == CalibrationMode.finger;

  double get _defaultMm => _isFinger
      ? Calibration.kReferenceFingerMm
      : Calibration.kReferenceWristMm;

  double get _minMm => _isFinger ? 10 : 35;
  double get _maxMm => _isFinger ? 26 : 90;

  double? get _currentMm {
    final double? k = _mmPerPx;
    if (k == null || _gapPx <= 0) return null;
    return _gapPx * k;
  }

  @override
  void initState() {
    super.initState();
    _resolve();
  }

  Future<void> _resolve() async {
    final double dpr = WidgetsBinding
        .instance.platformDispatcher.views.first.devicePixelRatio;
    final double? k = await CalibrationService.mmPerLogicalPixel(dpr);
    if (!mounted) return;
    await CalibrationService.load();
    final Calibration c = CalibrationService.current;
    final double startMm =
        (_isFinger ? c.fingerMm : c.wristMm) ?? _defaultMm;
    setState(() {
      _mmPerPx = k;
      _resolved = true;
      if (k != null) _gapPx = startMm / k;
    });
  }

  void _nudge(double deltaMm) {
    final double? k = _mmPerPx;
    if (k == null) return;
    final double mm = ((_currentMm ?? _defaultMm) + deltaMm)
        .clamp(_minMm, _maxMm);
    setState(() => _gapPx = mm / k);
  }

  Future<void> _save() async {
    final double? mm = _currentMm;
    if (mm == null) return;
    final Calibration c = CalibrationService.current;
    await CalibrationService.save(
      _isFinger ? c.copyWith(fingerMm: mm) : c.copyWith(wristMm: mm),
    );
    if (mounted) Navigator.of(context).pop(mm);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        title: Text(_isFinger ? 'Mesurer mon doigt' : 'Mesurer mon poignet'),
        backgroundColor: Colors.black87,
        foregroundColor: Colors.white,
      ),
      body: !_resolved
          ? const Center(child: CircularProgressIndicator())
          : _mmPerPx == null
              ? _buildUnavailable()
              : _buildRuler(),
    );
  }

  /// Densité de dalle non exploitable : on le dit franchement plutôt que de
  /// produire une mesure à l'air précis mais fausse.
  Widget _buildUnavailable() => const Padding(
        padding: EdgeInsets.all(28),
        child: Center(
          child: Text(
            "Cet appareil ne rapporte pas une densité d'écran fiable.\n\n"
            "La mesure à l'écran serait fausse, donc elle est désactivée : "
            "le bijou garde la taille estimée par le suivi.",
            textAlign: TextAlign.center,
            style: TextStyle(color: Colors.white70, height: 1.5),
          ),
        ),
      );

  Widget _buildRuler() {
    final double mm = _currentMm ?? _defaultMm;
    final int? size = _isFinger
        ? Calibration(fingerMm: mm).frenchRingSize
        : null;

    return Column(
      children: <Widget>[
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 20, 24, 8),
          child: Text(
            _isFinger
                ? "Posez votre annulaire à plat sur l'écran, entre les deux "
                    "repères, puis resserrez-les jusqu'à toucher les bords "
                    "du doigt."
                : "Posez votre poignet à plat sur l'écran, entre les deux "
                    "repères, puis resserrez-les jusqu'à toucher les bords.",
            textAlign: TextAlign.center,
            style: const TextStyle(color: Colors.white70, height: 1.4),
          ),
        ),
        Expanded(
          child: GestureDetector(
            // Le pincement est le geste naturel ici, mais il est impossible
            // à faire avec le doigt qu'on est en train de mesurer : le
            // glissement horizontal (autre main) écarte/resserre.
            onHorizontalDragUpdate: (DragUpdateDetails d) {
              final double? k = _mmPerPx;
              if (k == null) return;
              final double next = (_gapPx + d.delta.dx * 2)
                  .clamp(_minMm / k, _maxMm / k);
              setState(() => _gapPx = next);
            },
            child: CustomPaint(
              painter: _RulerPainter(gapPx: _gapPx),
              child: const SizedBox.expand(),
            ),
          ),
        ),
        Text(
          '${mm.toStringAsFixed(1)} mm',
          style: const TextStyle(
            color: Colors.white,
            fontSize: 34,
            fontWeight: FontWeight.w600,
          ),
        ),
        if (size != null)
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text(
              'taille de bague ≈ $size',
              style: const TextStyle(color: Colors.white54, fontSize: 15),
            ),
          ),
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 14),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: <Widget>[
              _StepButton(icon: Icons.remove, onTap: () => _nudge(-0.2)),
              const SizedBox(width: 28),
              _StepButton(icon: Icons.add, onTap: () => _nudge(0.2)),
            ],
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 0, 24, 28),
          child: SizedBox(
            width: double.infinity,
            child: FilledButton(
              onPressed: _save,
              style: FilledButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 16),
              ),
              child: const Text('Enregistrer'),
            ),
          ),
        ),
      ],
    );
  }
}

class _StepButton extends StatelessWidget {
  final IconData icon;
  final VoidCallback onTap;

  const _StepButton({required this.icon, required this.onTap});

  @override
  Widget build(BuildContext context) => Material(
        color: Colors.white12,
        shape: const CircleBorder(),
        child: InkWell(
          customBorder: const CircleBorder(),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Icon(icon, color: Colors.white, size: 26),
          ),
        ),
      );
}

/// Deux règles verticales symétriques + graduations millimétrées.
class _RulerPainter extends CustomPainter {
  final double gapPx;

  _RulerPainter({required this.gapPx});

  @override
  void paint(Canvas canvas, Size size) {
    final double cx = size.width / 2;
    final double half = gapPx / 2;
    final Paint line = Paint()
      ..color = Colors.amberAccent
      ..strokeWidth = 2.5;
    final Paint hint = Paint()
      ..color = Colors.white24
      ..strokeWidth = 1;

    // Repères d'aide horizontaux (haut/bas) pour poser le doigt droit.
    for (final double f in <double>[0.18, 0.82]) {
      canvas.drawLine(
        Offset(cx - half - 40, size.height * f),
        Offset(cx + half + 40, size.height * f),
        hint,
      );
    }

    for (final double x in <double>[cx - half, cx + half]) {
      canvas.drawLine(Offset(x, 0), Offset(x, size.height), line);
      // Poignées visuelles à mi-hauteur.
      canvas.drawCircle(
        Offset(x, size.height / 2),
        9,
        Paint()..color = Colors.amberAccent,
      );
    }
  }

  @override
  bool shouldRepaint(_RulerPainter old) => old.gapPx != gapPx;
}
