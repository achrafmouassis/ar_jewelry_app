import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

/// Compteur FPS basé sur SchedulerBinding.addTimingsCallback.
/// Affichage discret en haut à gauche.
class FpsCounter extends StatefulWidget {
  const FpsCounter({super.key});

  @override
  State<FpsCounter> createState() => _FpsCounterState();
}

class _FpsCounterState extends State<FpsCounter> {
  int _frames = 0;
  double _fps = 0;
  Timer? _timer;
  late final TimingsCallback _cb;

  @override
  void initState() {
    super.initState();
    _cb = (List<FrameTiming> timings) {
      _frames += timings.length;
    };
    SchedulerBinding.instance.addTimingsCallback(_cb);
    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      setState(() {
        _fps = _frames.toDouble();
        _frames = 0;
      });
    });
  }

  @override
  void dispose() {
    SchedulerBinding.instance.removeTimingsCallback(_cb);
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.45),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        '${_fps.toStringAsFixed(0)} FPS',
        style: const TextStyle(
          color: Colors.white,
          fontSize: 12,
          fontWeight: FontWeight.w600,
          fontFeatures: <FontFeature>[FontFeature.tabularFigures()],
        ),
      ),
    );
  }
}
