package unified.llm.acp

sealed class AcpEvent {
    data class PromptDone(val stopReason: String) : AcpEvent()
    data class Error(val message: String) : AcpEvent()
}
