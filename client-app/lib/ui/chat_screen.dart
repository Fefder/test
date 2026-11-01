import 'package:flutter/material.dart';
import '../models/agent_models.dart';
import '../services/agent_ipc_service.dart';

class ChatScreen extends StatefulWidget {
  const ChatScreen({super.key});

  @override
  State<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends State<ChatScreen> {
  final TextEditingController _controller = TextEditingController();
  final List<ChatEntry> _messages = [];
  final AgentIpcService _ipcService = AgentIpcService();
  bool _isProcessing = false;

  @override
  void initState() {
    super.initState();
    _ipcService.connect();
    _ipcService.thoughtsStream.listen((thought) {
      setState(() {
        if (_messages.isNotEmpty && _messages.last.sender == 'agent') {
          _messages.last.thoughts.add(thought);
          if (thought.finalAnswer != null) {
            _messages.last = ChatEntry(
              id: _messages.last.id,
              sender: 'agent',
              content: thought.finalAnswer!,
              thoughts: _messages.last.thoughts,
            );
            _isProcessing = false;
          }
        }
      });
    });
  }

  void _sendMessage() {
    final text = _controller.text.trim();
    if (text.isEmpty || _isProcessing) return;

    setState(() {
      _messages.add(ChatEntry(
        id: DateTime.now().millisecondsSinceEpoch.toString(),
        sender: 'user',
        content: text,
      ));
      _messages.add(ChatEntry(
        id: (DateTime.now().millisecondsSinceEpoch + 1).toString(),
        sender: 'agent',
        content: "Думаю...",
      ));
      _isProcessing = true;
    });

    _controller.clear();
    _ipcService.sendCommand(text);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Edge AI Autonomous Agent'),
        actions: [
          IconButton(
            icon: const Icon(Icons.speed),
            onPressed: () => Navigator.pushNamed(context, '/monitor'),
          )
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: ListView.builder(
              padding: const EdgeInsets.all(12),
              itemCount: _messages.length,
              itemBuilder: (context, index) {
                final msg = _messages[index];
                final isUser = msg.sender == 'user';
                return Align(
                  alignment: isUser ? Alignment.centerRight : Alignment.centerLeft,
                  child: Container(
                    margin: const EdgeInsets.symmetric(vertical: 4),
                    padding: const EdgeInsets.all(12),
                    constraints: BoxConstraints(
                      maxWidth: MediaQuery.of(context).size.width * 0.85,
                    ),
                    decoration: BoxDecoration(
                      color: isUser ? Colors.blue.shade700 : Colors.grey.shade800,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          msg.content,
                          style: const TextStyle(color: Colors.white, fontSize: 15),
                        ),
                        if (msg.thoughts.isNotEmpty) ...[
                          const Divider(color: Colors.white24),
                          for (final th in msg.thoughts)
                            Padding(
                              padding: const EdgeInsets.only(top: 4),
                              child: Text(
                                '⚡ ${th.thought} ${th.toolName != null ? '[Tool: ${th.toolName}]' : ''}',
                                style: const TextStyle(
                                  color: Colors.amberAccent,
                                  fontSize: 12,
                                  fontStyle: FontStyle.italic,
                                ),
                              ),
                            ),
                        ]
                      ],
                    ),
                  ),
                );
              },
            ),
          ),
          if (_isProcessing)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 4),
              child: LinearProgressIndicator(),
            ),
          Padding(
            padding: const EdgeInsets.all(8.0),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _controller,
                    onSubmitted: (_) => _sendMessage(),
                    decoration: InputDecoration(
                      hintText: 'Введіть команду (наприклад, "Перевір систему")...',
                      border: OutlineInputBorder(borderRadius: BorderRadius.circular(24)),
                      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                IconButton.filled(
                  icon: const Icon(Icons.send),
                  onPressed: _sendMessage,
                )
              ],
            ),
          )
        ],
      ),
    );
  }
}
