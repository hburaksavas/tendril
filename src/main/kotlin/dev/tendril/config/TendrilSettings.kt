package dev.tendril.config

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

/** Project-level list of keys the "unknown property" inspection must skip (custom properties). Stored in .idea/tendril.xml. */
@Service(Service.Level.PROJECT)
@State(name = "TendrilSettings", storages = [Storage("tendril.xml")])
class TendrilSettings : PersistentStateComponent<TendrilSettings.State> {

    class State {
        var ignoredKeys: MutableList<String> = mutableListOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /** An entry ignores the key itself and everything below it ("jwt" ignores "jwt.secret"). */
    fun isIgnored(key: String): Boolean =
        state.ignoredKeys.any { key == it || key.startsWith("$it.") }

    fun ignore(key: String) {
        if (key !in state.ignoredKeys) state.ignoredKeys.add(key)
    }

    companion object {
        fun getInstance(project: Project): TendrilSettings = project.service()
    }
}
