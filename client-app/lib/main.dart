import 'package:flutter/material.dart';
import 'ui/chat_screen.dart';
import 'ui/system_monitor_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const EdgeAgentApp());
}

class EdgeAgentApp extends StatelessWidget {
  const EdgeAgentApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Edge AI Autonomous Agent',
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark(useMaterial3: true).copyWith(
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.deepPurple,
          brightness: Brightness.dark,
        ),
      ),
      initialRoute: '/',
      routes: {
        '/': (context) => const ChatScreen(),
        '/monitor': (context) => const SystemMonitorScreen(),
      },
    );
  }
}
