import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:camera/camera.dart';

import 'screens/jewelry_types_screen.dart';

// Liste globale des caméras disponibles (initialisée une seule fois au démarrage
// pour éviter les appels coûteux à availableCameras() à répétition).
List<CameraDescription> cameras = <CameraDescription>[];

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Force l'orientation portrait (l'AR est calibrée pour ce mode).
  await SystemChrome.setPreferredOrientations(<DeviceOrientation>[
    DeviceOrientation.portraitUp,
  ]);

  // Récupère les caméras dispo (frontale + arrière) une seule fois.
  try {
    cameras = await availableCameras();
  } catch (e) {
    debugPrint('Erreur init caméras: $e');
  }

  runApp(const ARJewelryApp());
}

class ARJewelryApp extends StatelessWidget {
  const ARJewelryApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'AR Jewelry',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFFB8860B), // doré
          brightness: Brightness.light,
        ),
        scaffoldBackgroundColor: const Color(0xFFFAF7F2),
        appBarTheme: const AppBarTheme(
          centerTitle: true,
          elevation: 0,
          backgroundColor: Colors.transparent,
          foregroundColor: Colors.black87,
        ),
      ),
      home: const JewelryTypesScreen(),
    );
  }
}
