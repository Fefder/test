class AgentThoughtMessage {
  final String thought;
  final String? toolName;
  final String? toolArgumentsJson;
  final String? finalAnswer;
  final DateTime timestamp;

  AgentThoughtMessage({
    required this.thought,
    this.toolName,
    this.toolArgumentsJson,
    this.finalAnswer,
    DateTime? timestamp,
  }) : timestamp = timestamp ?? DateTime.now();

  factory AgentThoughtMessage.fromJson(Map<String, dynamic> json) {
    return AgentThoughtMessage(
      thought: json['Thought'] ?? json['thought'] ?? '',
      toolName: json['ToolName'] ?? json['tool'],
      toolArgumentsJson: json['ToolArgumentsJson'] ?? json['args']?.toString(),
      finalAnswer: json['FinalAnswer'] ?? json['final_answer'],
    );
  }
}

class ChatEntry {
  final String id;
  final String sender; // 'user' | 'agent'
  final String content;
  final List<AgentThoughtMessage> thoughts;
  final DateTime timestamp;

  ChatEntry({
    required this.id,
    required this.sender,
    required this.content,
    List<AgentThoughtMessage>? thoughts,
    DateTime? timestamp,
  })  : thoughts = thoughts ?? [],
        timestamp = timestamp ?? DateTime.now();
}
