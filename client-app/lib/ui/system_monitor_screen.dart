import 'package:flutter/material.dart';

class SystemMonitorScreen extends StatelessWidget {
  const SystemMonitorScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('ARM Edge Telemetry')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _buildMetricCard(
            title: 'SoC & Acceleration',
            icon: Icons.memory,
            color: Colors.blueAccent,
            items: [
              'Architecture: ARM64-v8.2A / v9',
              'NEON SIMD: Active (128-bit vectorization)',
              'KleidiAI Microkernels: Enabled',
              'Active Model: Qwen2.5-1.5B-Instruct-Q4_K_M',
            ],
          ),
          const SizedBox(height: 12),
          _buildMetricCard(
            title: 'Memory Budget (RAM)',
            icon: Icons.storage,
            color: Colors.greenAccent,
            items: [
              'System RAM Total: 8.0 GB',
              'Available Buffer: ~3.8 GB (Safe from LMK)',
              'Model GGUF mmap: 1.1 GB',
              'Core Daemon (.NET Native AOT): 62 MB',
              'SQLite + sqlite-vec Cache: 12 MB',
            ],
          ),
          const SizedBox(height: 12),
          _buildMetricCard(
            title: 'Background Subsystems',
            icon: Icons.security,
            color: Colors.orangeAccent,
            items: [
              'Foreground Service: RUNNING (Persistent notification)',
              'Accessibility Service: CONNECTED',
              'Notification Listener: ACTIVE (Direct Reply ready)',
              'Battery Optimization: EXEMPTED',
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildMetricCard({
    required String title,
    required IconData icon,
    required Color color,
    required List<String> items,
  }) {
    return Card(
      elevation: 2,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(icon, color: color),
                const SizedBox(width: 8),
                Text(title, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
              ],
            ),
            const Divider(height: 20),
            for (final item in items)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 2),
                child: Text('• $item', style: const TextStyle(fontSize: 14)),
              ),
          ],
        ),
      ),
    );
  }
}
