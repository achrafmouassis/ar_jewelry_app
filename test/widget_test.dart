// Smoke test minimal : on vérifie qu'un widget de base de l'app se construit
// sans dépendre de la caméra ni du manifeste d'assets (qui ne sont pas
// disponibles dans l'environnement de test headless).

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:ar_jewelry_app/widgets/loading_indicator.dart';

void main() {
  testWidgets('CenteredLoader affiche son libellé', (WidgetTester tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(body: CenteredLoader(label: 'Chargement…')),
      ),
    );

    expect(find.text('Chargement…'), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
  });
}
