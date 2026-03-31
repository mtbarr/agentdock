package unified.llm.gitcommit

object GitCommitFeatureRuntimeState {
    @Volatile
    private var enabled: Boolean = false

    fun isEnabled(): Boolean = enabled

    fun setEnabled(value: Boolean) {
        enabled = value
    }
}
