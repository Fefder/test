import 'dart:async';
import 'dart:convert';
import 'dart:io';
import '../models/agent_models.dart';

class AgentIpcService {
  static final AgentIpcService _instance = AgentIpcService._internal();
  factory AgentIpcService() => _instance;
  AgentIpcService._internal();

  Socket? _socket;
  final _thoughtController = StreamController<AgentThoughtMessage>.broadcast();

  Stream<AgentThoughtMessage> get thoughtsStream => _thoughtController.stream;

  Future<bool> connect({String socketPath = '/tmp/edge_agent.sock'}) async {
    try {
      _socket = await Socket.connect(
        InternetAddress(socketPath, type: InternetAddressType.unix),
        0,
        timeout: const Duration(seconds: 3),
      );

      _socket!.transform(utf8.decoder).transform(const LineSplitter()).listen(
        (line) {
          if (line.trim().isEmpty) return;
          try {
            final data = jsonDecode(line) as Map<String, dynamic>;
            final thought = AgentThoughtMessage.fromJson(data);
            _thoughtController.add(thought);
          } catch (e) {
            // Handle raw non-json line
          }
        },
        onError: (err) {
          _socket?.destroy();
          _socket = null;
        },
      );
      return true;
    } catch (e) {
      // IPC socket not reachable (e.g. running standalone or in mock mode)
      return false;
    }
  }

  Future<void> sendCommand(String prompt) async {
    if (_socket != null) {
      final payload = jsonEncode({'prompt': prompt}) + '\n';
      _socket!.write(payload);
    } else {
      // Simulation mode if socket not running
      await Future.delayed(const Duration(milliseconds: 300));
      _thoughtController.add(AgentThoughtMessage(
        thought: "Аналізую системні ресурси телефону та команди...",
        toolName: "system_monitor",
      ));
      await Future.delayed(const Duration(milliseconds: 500));
      _thoughtController.add(AgentThoughtMessage(
        thought: "Виконано локальний моніторинг.",
        finalAnswer: "Агент активний у фоні. Пам'ять: 3.8GB вільно, CPU: 36°C.",
      ));
    }
  }

  void dispose() {
    _socket?.destroy();
    _thoughtController.close();
  }
}
